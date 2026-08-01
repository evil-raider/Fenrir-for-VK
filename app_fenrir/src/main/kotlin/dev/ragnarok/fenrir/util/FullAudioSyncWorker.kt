package dev.ragnarok.fenrir.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.ragnarok.fenrir.Includes
import dev.ragnarok.fenrir.R
import dev.ragnarok.fenrir.domain.IAudioInteractor
import dev.ragnarok.fenrir.domain.InteractorFactory
import dev.ragnarok.fenrir.model.Audio
import dev.ragnarok.fenrir.settings.ISettings
import dev.ragnarok.fenrir.settings.Settings
import dev.ragnarok.fenrir.toColor
import dev.ragnarok.fenrir.util.DownloadWorkUtils.TrackIsDownloaded
import dev.ragnarok.fenrir.util.DownloadWorkUtils.makeDownloadRequestAudio
import dev.ragnarok.fenrir.util.coroutines.CoroutinesUtils.syncSingleSafe
import java.util.Collections

/**
 * FENRIR-CI (Этап 4): фоновый Worker полной автозагрузки всей «Моей музыки».
 *
 * В отличие от постраничного авто-кэша в AudiosPresenter, этот Worker сам
 * проходит ВСЕ страницы «Моей музыки» через IAudioInteractor.get(...offset...)
 * и ставит в WorkManager докачку каждого ещё не скачанного трека одним
 * запуском. Идемпотентность обеспечивает TrackIsDownloaded(audio) == 0.
 * Запускается один раз за сессию приложения (startOnceThisSession) при
 * включённом Settings.main().isForce_cache.
 */
class FullAudioSyncWorker(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {

    private val audioInteractor: IAudioInteractor = InteractorFactory.createAudioInteractor()

    private fun buildForeground(text: String): ForegroundInfo {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                applicationContext.getString(R.string.channel_keep_work_manager),
                NotificationManager.IMPORTANCE_NONE
            )
        )
        val builder = NotificationCompat.Builder(applicationContext, FOREGROUND_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.work_manager))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.save)
            .setColor("#dd0000".toColor())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_FULL_SYNC,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_FULL_SYNC, builder.build())
        }
    }

    private fun progressText(enqueued: Int): String {
        return applicationContext.getString(R.string.downloading) + " (" + enqueued + ")"
    }

    override fun doWork(): Result {
        val accountId = inputData.getLong(EXTRA_ACCOUNT, ISettings.IAccountsSettings.INVALID_ID)
        if (accountId == ISettings.IAccountsSettings.INVALID_ID) {
            return Result.failure()
        }
        if (!Settings.get().main().isForce_cache) {
            return Result.success()
        }

        setForegroundAsync(buildForeground(progressText(0)))

        val workManager = WorkManager.getInstance(applicationContext)
        var offset = 0
        var enqueued = 0
        var page = 0
        while (page < MAX_PAGES && !isStopped) {
            val data: List<Audio> =
                audioInteractor[accountId, null, accountId, offset, PAGE_COUNT, null]
                    .syncSingleSafe(emptyList())
            if (data.isEmpty()) {
                break
            }
            for (audio in data) {
                if (isStopped) {
                    break
                }
                val url = audio.url
                val isLocalFile =
                    url != null && (url.contains("file://") || url.contains("content://"))
                if (!audio.isLocal && !audio.isLocalServer && !isLocalFile &&
                    TrackIsDownloaded(audio) == 0
                ) {
                    workManager.enqueue(makeDownloadRequestAudio(audio, accountId))
                    enqueued++
                }
            }
            setForegroundAsync(buildForeground(progressText(enqueued)))
            offset += data.size
            page++
            if (data.size < PAGE_COUNT) {
                break
            }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "full_my_music_sync"
        private const val EXTRA_ACCOUNT = "account_id"
        private const val PAGE_COUNT = 100
        private const val MAX_PAGES = 1000
        private const val NOTIFICATION_FULL_SYNC = 4823
        private const val FOREGROUND_CHANNEL_ID = "worker_channel"

        private val startedSessions = Collections.synchronizedSet(HashSet<Long>())

        /**
         * Запускает полную синхронизацию не более одного раза за сессию
         * приложения для конкретного аккаунта.
         */
        fun startOnceThisSession(accountId: Long) {
            if (accountId == ISettings.IAccountsSettings.INVALID_ID) {
                return
            }
            if (!startedSessions.add(accountId)) {
                return
            }
            enqueue(accountId)
        }

        fun enqueue(accountId: Long) {
            val data = Data.Builder()
                .putLong(EXTRA_ACCOUNT, accountId)
                .build()
            val request = OneTimeWorkRequest.Builder(FullAudioSyncWorker::class)
                .setInputData(data)
                .build()
            WorkManager.getInstance(Includes.provideApplicationContext())
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
