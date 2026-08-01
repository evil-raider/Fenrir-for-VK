package dev.ragnarok.fenrir.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.ragnarok.fenrir.Constants
import dev.ragnarok.fenrir.Includes
import dev.ragnarok.fenrir.R
import dev.ragnarok.fenrir.domain.IAudioInteractor
import dev.ragnarok.fenrir.domain.InteractorFactory
import dev.ragnarok.fenrir.media.music.MusicPlaybackController
import dev.ragnarok.fenrir.model.Audio
import dev.ragnarok.fenrir.module.FenrirNative
import dev.ragnarok.fenrir.module.FileUtils
import dev.ragnarok.fenrir.nonNullNoEmpty
import dev.ragnarok.fenrir.settings.ISettings
import dev.ragnarok.fenrir.settings.Settings
import dev.ragnarok.fenrir.toColor
import dev.ragnarok.fenrir.util.DownloadWorkUtils.CheckDirectory
import dev.ragnarok.fenrir.util.DownloadWorkUtils.TrackIsDownloaded
import dev.ragnarok.fenrir.util.DownloadWorkUtils.makeLegalFilename
import dev.ragnarok.fenrir.util.coroutines.CoroutinesUtils.syncSingleSafe
import kotlinx.coroutines.flow.map
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

/**
 * FENRIR-CI (Этап 4): фоновый Worker полной автозагрузки всей «Моей музыки».
 *
 * Воркер сам проходит все страницы «Моей музыки» через
 * IAudioInteractor.get(...offset...), собирает список ещё не скачанных треков и
 * последовательно скачивает каждый (mp3 + обложка + ID3-теги + регистрация),
 * поддерживая ОДНО foreground-уведомление с прогрессом X / N. В отличие от
 * прежней схемы, отдельные TrackDownloadWorker больше не ставятся в очередь,
 * поэтому уведомления на каждый файл не плодятся.
 *
 * Запускается один раз за сессию (startOnceThisSession) при включённом
 * Settings.main().isAutoDownload_music (и, если включено, только по Wi-Fi —
 * см. isAutoDownload_music_wifi_only). Идемпотентность — TrackIsDownloaded(audio) == 0.
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
            .setContentTitle(applicationContext.getString(R.string.downloading))
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

    private fun progressText(done: Int, total: Int): String {
        return applicationContext.getString(R.string.downloading) + " " + done + " / " + total
    }

    private fun isWifiConnected(): Boolean {
        val manager =
            applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    override fun doWork(): Result {
        val accountId = inputData.getLong(EXTRA_ACCOUNT, ISettings.IAccountsSettings.INVALID_ID)
        if (accountId == ISettings.IAccountsSettings.INVALID_ID) {
            return Result.failure()
        }
        if (!Settings.get().main().isAutoDownload_music) {
            return Result.success()
        }
        if (Settings.get().main().isAutoDownload_music_wifi_only && !isWifiConnected()) {
            return Result.success()
        }

        setForegroundAsync(buildForeground(progressText(0, 0)))

        // Фаза 1: собрать все ещё не скачанные треки «Моей музыки».
        val toDownload = ArrayList<Audio>()
        var offset = 0
        var page = 0
        while (page < MAX_PAGES && !isStopped) {
            val data: List<Audio> =
                audioInteractor[accountId, null, accountId, offset, PAGE_COUNT, null]
                    .syncSingleSafe(emptyList())
            if (data.isEmpty()) {
                break
            }
            for (audio in data) {
                val url = audio.url
                val isLocalFile =
                    url != null && (url.contains("file://") || url.contains("content://"))
                if (!audio.isLocal && !audio.isLocalServer && !audio.isHLS && !isLocalFile &&
                    TrackIsDownloaded(audio) == 0
                ) {
                    toDownload.add(audio)
                }
            }
            offset += data.size
            page++
            if (data.size < PAGE_COUNT) {
                break
            }
        }

        val total = toDownload.size
        if (total == 0) {
            return Result.success()
        }

        CheckDirectory(Settings.get().main().musicDir)

        // Фаза 2: последовательное скачивание с единым прогресс-уведомлением.
        var done = 0
        for (audio in toDownload) {
            if (isStopped) {
                break
            }
            setForegroundAsync(buildForeground(progressText(done, total)))
            try {
                downloadOne(audio, accountId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            done++
        }
        setForegroundAsync(buildForeground(progressText(done, total)))
        return Result.success()
    }

    /**
     * Скачивает один трек в папку музыки, пишет ID3-теги и обложку и
     * регистрирует его в реестре скачанного. Без индивидуальных уведомлений.
     */
    private fun downloadOne(audio: Audio, accountId: Long) {
        // Дорезолвить ссылку, если требуется (как в TrackDownloadWorker).
        val mode = audio.needRefresh()
        if (mode.first) {
            val link: String? = InteractorFactory
                .createAudioInteractor()
                .getByIdOld(accountId, listOf(audio), mode.second)
                .map { e ->
                    e[0].url.nonNullNoEmpty({ l -> l }, { audio.url.orEmpty() })
                }.syncSingleSafe(audio.url)
            if (link.nonNullNoEmpty()) {
                audio.setUrl(link)
            }
        }

        if (audio.url.isNullOrEmpty()) {
            return
        }

        val dir = Settings.get().main().musicDir
        val baseName = makeLegalFilename(audio.artist + " - " + audio.title, null)
        val fileName = "$baseName.mp3"
        val target = File(dir, fileName)

        if (!downloadRaw(audio.url, target)) {
            return
        }

        // Обложка + ID3-теги (не критично: ошибки не роняют скачивание).
        val cover = Utils.firstNonEmptyString(
            audio.thumb_image_very_big,
            audio.thumb_image_big,
            audio.thumb_image_little
        )
        if (FenrirNative.isNativeLoaded && cover.nonNullNoEmpty()) {
            val coverFile = File(dir, "$baseName.jpg")
            if (downloadRaw(cover, coverFile)) {
                try {
                    val ifGenre = if (audio.genreByID3 != 0) audio.genreByID3.toString() else null
                    val commentText = Audio.AudioCommentTag(audio.ownerId, audio.id).toText()
                    FileUtils.audioTagModify(
                        target.absolutePath,
                        coverFile.absolutePath,
                        "image/jpg",
                        audio.title,
                        audio.artist,
                        audio.album_title,
                        ifGenre,
                        commentText
                    )
                } catch (e: Throwable) {
                    e.printStackTrace()
                } finally {
                    if (coverFile.exists()) {
                        coverFile.delete()
                    }
                }
            }
        }

        MusicPlaybackController.tracksExist.addAudio(fileName)
        applicationContext.sendBroadcast(
            @Suppress("deprecation")
            Intent(
                Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                Uri.fromFile(target)
            )
        )
    }

    /**
     * Простое потоковое скачивание url -> target. Возвращает true при успехе.
     * Без уведомлений; удаляет частичный файл при ошибке/остановке.
     */
    private fun downloadRaw(url: String?, target: File): Boolean {
        if (url.isNullOrEmpty()) {
            return false
        }
        try {
            FileOutputStream(target).use { output ->
                val client = Utils.createOkHttp(Constants.DOWNLOAD_TIMEOUT, false).build()
                val request: Request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return false
                }
                val input = BufferedInputStream(response.body.byteStream())
                val buffer = ByteArray(80 * 1024)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    if (isStopped) {
                        input.close()
                        response.close()
                        if (target.exists()) {
                            target.delete()
                        }
                        return false
                    }
                    output.write(buffer, 0, len)
                }
                input.close()
                response.close()
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            if (target.exists()) {
                target.delete()
            }
            return false
        }
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
         * Запускает полную синхронизацию не более одного раза за сессию.
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
