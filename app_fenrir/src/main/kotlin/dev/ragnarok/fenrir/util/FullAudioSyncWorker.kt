package dev.ragnarok.fenrir.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequest
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
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * FENRIR-CI (Этап 4): фоновый Worker полной автозагрузки всей «Моей музыки».
 *
 * Работает как ЕЖЕДНЕВНАЯ периодическая задача (примерно в 5:00). При запуске
 * внутри doWork проверяет условия: включено ли автоскачивание, есть ли активный
 * аккаунт, есть ли Wi-Fi (если задано «только по Wi-Fi») и подключено ли зарядное
 * устройство (если включено «только при зарядке»). Если условие не выполнено — молча
 * пропускает попытку (Result.success()) и повторит её через сутки. Не
 * запускается при заходе в «Мою музыку».
 *
 * Воркер сам проходит все страницы «Моей музыки», собирает список ещё не
 * скачанных треков и последовательно скачивает каждый (mp3 + обложка + ID3-теги
 * + регистрация), поддерживая ОДНО foreground-уведомление с прогрессом X / N.
 * Идемпотентность — TrackIsDownloaded(audio) == 0.
 */
class FullAudioSyncWorker(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {

    private val audioInteractor: IAudioInteractor = InteractorFactory.createAudioInteractor()

    // Канал уведомлений создаём один раз за запуск воркера, а не на каждый тик прогресса.
    private var channelCreated = false

    private fun ensureChannel() {
        if (channelCreated) {
            return
        }
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                applicationContext.getString(R.string.channel_keep_work_manager),
                NotificationManager.IMPORTANCE_NONE
            )
        )
        channelCreated = true
    }

    private fun buildForeground(text: String): ForegroundInfo {
        ensureChannel()
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

    private fun isCharging(): Boolean {
        val bm =
            applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return false
        return bm.isCharging
    }

    override fun doWork(): Result {
        // Автоскачивание выключено — тихо выходим.
        if (!Settings.get().main().isAutoDownload_music) {
            return Result.success()
        }
        val accountId = Settings.get().accounts().current
        if (accountId == ISettings.IAccountsSettings.INVALID_ID) {
            return Result.success()
        }
        // Условие Wi-Fi (если включено «только по Wi-Fi») — иначе пропускаем до следующих суток.
        if (Settings.get().main().isAutoDownload_music_wifi_only && !isWifiConnected()) {
            return Result.success()
        }
        // Условие зарядки — опциональное (по умолчанию требуется). Управляется
        // тумблером auto_download_music_charging_only в настройках. Если включено
        // и питания нет — не тратим заряд, пробуем через сутки.
        if (Settings.get().main().isAutoDownload_music_charging_only && !isCharging()) {
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
     * Без уведомлений; при ошибке/остановке/неуспешном ответе удаляет частичный
     * или пустой файл. Все ресурсы (ответ, входной и выходной потоки)
     * закрываются через use{}, даже если запись прервётся исключением.
     */
    private fun downloadRaw(url: String?, target: File): Boolean {
        if (url.isNullOrEmpty()) {
            return false
        }
        var success = false
        try {
            val client = Utils.createOkHttp(Constants.DOWNLOAD_TIMEOUT, false).build()
            val request: Request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    // Файл создаём только после подтверждения успешного ответа,
                    // чтобы не оставлять осиротевший 0-байтный файл при ошибке.
                    FileOutputStream(target).use { output ->
                        BufferedInputStream(response.body.byteStream()).use { input ->
                            val buffer = ByteArray(80 * 1024)
                            var len: Int
                            while (input.read(buffer).also { len = it } != -1) {
                                if (isStopped) {
                                    return@use
                                }
                                output.write(buffer, 0, len)
                            }
                            success = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            success = false
        }
        if (!success && target.exists()) {
            target.delete()
        }
        return success
    }

    companion object {
        private const val UNIQUE_NAME = "full_my_music_sync"
        private const val PAGE_COUNT = 100
        private const val MAX_PAGES = 1000
        private const val NOTIFICATION_FULL_SYNC = 4823
        private const val FOREGROUND_CHANNEL_ID = "worker_channel"
        private const val TARGET_HOUR = 5

        /**
         * Планирует ежедневную фоновую синхронизацию «Моей музыки» примерно на 5:00.
         * Проверка условий (Wi-Fi, зарядка, наличие нового) — внутри doWork.
         * Если автоскачивание выключено — снимает запланированную задачу.
         */
        fun schedule() {
            val context = Includes.provideApplicationContext()
            val wm = WorkManager.getInstance(context)
            if (!Settings.get().main().isAutoDownload_music) {
                wm.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            val request = PeriodicWorkRequest.Builder(
                FullAudioSyncWorker::class.java, 1, TimeUnit.DAYS
            )
                .setInitialDelay(initialDelayMillisToHour(TARGET_HOUR), TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        private fun initialDelayMillisToHour(targetHour: Int): Long {
            val now = Calendar.getInstance()
            val next = Calendar.getInstance()
            next.set(Calendar.HOUR_OF_DAY, targetHour)
            next.set(Calendar.MINUTE, 0)
            next.set(Calendar.SECOND, 0)
            next.set(Calendar.MILLISECOND, 0)
            if (!next.after(now)) {
                next.add(Calendar.DAY_OF_YEAR, 1)
            }
            return next.timeInMillis - now.timeInMillis
        }
    }
}
