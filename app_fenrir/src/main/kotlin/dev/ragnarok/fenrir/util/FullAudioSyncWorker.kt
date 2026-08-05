package dev.ragnarok.fenrir.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
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
import dev.ragnarok.fenrir.module.hls.TSDemuxer
import dev.ragnarok.fenrir.nonNullNoEmpty
import dev.ragnarok.fenrir.settings.ISettings
import dev.ragnarok.fenrir.settings.Settings
import dev.ragnarok.fenrir.toColor
import dev.ragnarok.fenrir.util.DownloadWorkUtils.CheckDirectory
import dev.ragnarok.fenrir.util.DownloadWorkUtils.TrackIsDownloaded
import dev.ragnarok.fenrir.util.DownloadWorkUtils.makeLegalFilename
import dev.ragnarok.fenrir.util.coroutines.CoroutinesUtils.syncSingleSafe
import dev.ragnarok.fenrir.util.hls.M3U8
import kotlinx.coroutines.flow.map
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
 * Ручной запуск (кнопка «Ручной запуск», см. enqueueManualSync)
 * передаёт во входные данные флаг INPUT_KEY_MANUAL=true — в этом режиме
 * условия Wi-Fi и зарядки НЕ проверяются, так как пользователь запросил
 * синхронизацию явно. Условие включённого автоскачивания и наличия аккаунта
 * при этом остаётся в силе.
 *
 * Воркер сам проходит все страницы «Моей музыки», собирает список ещё не
 * скачанных треков и последовательно скачивает каждый (mp3 + обложка + ID3-теги
 * + регистрация), поддерживая ОДНО foreground-уведомление с прогрессом X / N.
 * Уведомление «отсутствует локальная копия» (MissingTrackNotifier) показывается
 * ТОЛЬКО когда трек недоступен для воспроизведения навсегда — то есть после
 * дорезолва ссылка пустая (запрет правообладателя). Временные сбои (сетевые
 * ошибки, HTTP, незагруженный нативный модуль для HLS) НЕ порождают уведомление:
 * трек остаётся нескачанным (TrackIsDownloaded==0) и будет повторён при следующем
 * суточном прогоне. Идемпотентность — TrackIsDownloaded(audio) == 0.
 *
 * DEBUG (диагностика '100/506'): весь прогон пишется в logcat (тег
 * FullAudioSync) и в файл на устройстве (getExternalFilesDir/full_sync_debug_*.log)
 * с построчным flush. Логика скачивания и пагинации при этом НЕ изменена —
 * добавлено только логирование, чтобы за один ночной проход увидеть причину.
 */
class FullAudioSyncWorker(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {

    private val audioInteractor: IAudioInteractor = InteractorFactory.createAudioInteractor()

    // Канал уведомлений создаём один раз за запуск воркера, а не на каждый тик прогресса.
    private var channelCreated = false

    // --- DEBUG logging infra ------------------------------------------------
    private val logTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var logWriter: Writer? = null

    private fun openLog() {
        try {
            val dir = applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = File(dir, "full_sync_debug_$stamp.log")
            logWriter = BufferedWriter(FileWriter(f, true))
            Log.i(LOG_TAG, "Debug log file: " + f.absolutePath)
            log("=== FullAudioSyncWorker debug log started ===")
            log("logfile=" + f.absolutePath)
            log(
                "settings: autoDownload=" + Settings.get().main().isAutoDownload_music +
                    ", wifiOnly=" + Settings.get().main().isAutoDownload_music_wifi_only +
                    ", chargingOnly=" + Settings.get().main().isAutoDownload_music_charging_only +
                    ", use_api_5_90=" + Settings.get().main().isUse_api_5_90_for_audio +
                    ", nativeLoaded=" + FenrirNative.isNativeLoaded +
                    ", musicDir=" + Settings.get().main().musicDir
            )
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "Cannot open debug log file", e)
        }
    }

    private fun log(msg: String) {
        Log.d(LOG_TAG, msg)
        try {
            val w = logWriter ?: return
            w.write(logTimeFmt.format(Date()) + "  " + msg + "\n")
            w.flush()
        } catch (_: Throwable) {
            // Логирование не должно влиять на скачивание.
        }
    }

    private fun closeLog() {
        try {
            logWriter?.flush()
            logWriter?.close()
        } catch (_: Throwable) {
        }
        logWriter = null
    }

    /** Классификация ссылки трека для диагностики (без раскрытия токенов в имени класса). */
    private fun classifyUrl(url: String?): String {
        return when {
            url.isNullOrEmpty() -> "EMPTY"
            url.contains("audio_api_unavailable") -> "API_UNAVAILABLE"
            url.contains("index.m3u8") -> "HLS"
            url.contains("file://") || url.contains("content://") -> "LOCAL"
            else -> "DIRECT"
        }
    }

    private fun inc(map: HashMap<String, Int>, key: String) {
        map[key] = (map[key] ?: 0) + 1
    }
    // -----------------------------------------------------------------------

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
        openLog()
        try {
            return doWorkInner()
        } catch (e: Throwable) {
            log("FATAL in doWork: " + e.message)
            e.printStackTrace()
            return Result.success()
        } finally {
            log("=== FullAudioSyncWorker debug log finished (isStopped=" + isStopped + ") ===")
            closeLog()
        }
    }

    private fun doWorkInner(): Result {
        // Ручной запуск (кнопка «Ручной запуск») игнорирует условия
        // Wi-Fi/зарядки — раз пользователь запросил синхронизацию явно, ждать
        // подходящих условий не нужно. Время (TARGET_HOUR) в doWork вообще не
        // проверяется — оно влияет только на первоначальную задержку периодической
        // задачи при schedule().
        val manual = inputData.getBoolean(INPUT_KEY_MANUAL, false)
        // Автоскачивание выключено — тихо выходим.
        if (!Settings.get().main().isAutoDownload_music) {
            log("GATE: autoDownload disabled -> exit")
            return Result.success()
        }
        val accountId = Settings.get().accounts().current
        if (accountId == ISettings.IAccountsSettings.INVALID_ID) {
            log("GATE: invalid account -> exit")
            return Result.success()
        }
        log("accountId=" + accountId + ", manual=" + manual)
        // Условие Wi-Fi (если включено «только по Wi-Fi») — иначе пропускаем до следующих суток.
        // Для ручного запуска не проверяется.
        if (!manual && Settings.get().main().isAutoDownload_music_wifi_only && !isWifiConnected()) {
            log("GATE: wifiOnly but no wifi -> exit")
            return Result.success()
        }
        // Условие зарядки — опциональное (по умолчанию требуется). Управляется
        // тумблером auto_download_music_charging_only в настройках. Если включено
        // и питания нет — не тратим заряд, пробуем через сутки. Для ручного
        // запуска не проверяется.
        if (!manual && Settings.get().main().isAutoDownload_music_charging_only && !isCharging()) {
            log("GATE: chargingOnly but not charging -> exit")
            return Result.success()
        }

        setForegroundAsync(buildForeground(progressText(0, 0)))

        // Фаза 1: собрать все ещё не скачанные треки «Моей музыки».
        // Пагинация завершается только через data.isEmpty() — VK может вернуть меньше
        // PAGE_COUNT треков на промежуточной странице (скрытые правообладателями),
        // поэтому break по data.size < PAGE_COUNT был бы преждевременным.
        log("=== PHASE 1: scanning My Music (PAGE_COUNT=" + PAGE_COUNT + ", MAX_PAGES=" + MAX_PAGES + ") ===")
        val toDownload = ArrayList<Audio>()
        val toDownloadIdx = ArrayList<Int>()
        var offset = 0
        var page = 0
        var globalIndex = 0
        var scanned = 0
        var alreadyDownloaded = 0
        var localSkipped = 0
        var firstNonPlayableIndex = -1
        val classCounts = HashMap<String, Int>()
        while (page < MAX_PAGES && !isStopped) {
            val data: List<Audio> =
                audioInteractor[accountId, null, accountId, offset, PAGE_COUNT, null]
                    .syncSingleSafe(emptyList())
            log("page=" + page + " offset=" + offset + " returned=" + data.size)
            if (data.isEmpty()) {
                log("page returned empty -> stop paging")
                break
            }
            for (audio in data) {
                val url = audio.url
                val cls = classifyUrl(url)
                inc(classCounts, cls)
                val isLocalFile =
                    url != null && (url.contains("file://") || url.contains("content://"))
                val dl = TrackIsDownloaded(audio)
                if (firstNonPlayableIndex < 0 && (cls == "EMPTY" || cls == "API_UNAVAILABLE")) {
                    firstNonPlayableIndex = globalIndex
                }
                log(
                    "  [" + globalIndex + "] cls=" + cls + " isHLS=" + audio.isHLS +
                        " downloaded=" + dl + " local=" + audio.isLocal + "/" + audio.isLocalServer +
                        "/" + isLocalFile + " \"" + audio.artist + " - " + audio.title + "\""
                )
                if (!audio.isLocal && !audio.isLocalServer && !isLocalFile &&
                    dl == 0
                ) {
                    toDownload.add(audio)
                    toDownloadIdx.add(globalIndex)
                } else if (dl != 0) {
                    alreadyDownloaded++
                } else {
                    localSkipped++
                }
                scanned++
                globalIndex++
            }
            offset += data.size
            page++
        }
        log(
            "=== PHASE 1 done: scanned=" + scanned + ", toDownload=" + toDownload.size +
                ", alreadyDownloaded=" + alreadyDownloaded + ", localSkipped=" + localSkipped +
                ", firstNonPlayableIndex=" + firstNonPlayableIndex + ", classCounts=" + classCounts + " ==="
        )

        val total = toDownload.size
        if (total == 0) {
            log("Nothing to download -> exit")
            return Result.success()
        }

        CheckDirectory(Settings.get().main().musicDir)

        // Фаза 2: последовательное скачивание с единым прогресс-уведомлением.
        log("=== PHASE 2: downloading " + total + " tracks ===")
        var done = 0
        var success = 0
        val failByReason = HashMap<String, Int>()
        for (i in toDownload.indices) {
            if (isStopped) {
                log("isStopped -> break at i=" + i)
                break
            }
            val audio = toDownload[i]
            val gidx = toDownloadIdx[i]
            setForegroundAsync(buildForeground(progressText(done, total)))
            log(
                "--- [dl " + (i + 1) + "/" + total + "] libIndex=" + gidx +
                    " owner=" + audio.ownerId + " id=" + audio.id +
                    " \"" + audio.artist + " - " + audio.title + "\""
            )
            var reason = "exception"
            try {
                reason = downloadOne(audio, accountId)
            } catch (e: Exception) {
                log("    -> EXCEPTION in downloadOne: " + e.message)
                e.printStackTrace()
            }
            if (reason == "ok") {
                success++
            } else {
                inc(failByReason, reason)
            }
            log("    result=" + reason + " (running success=" + success + ")")
            done++
        }
        setForegroundAsync(buildForeground(progressText(done, total)))
        log(
            "=== PHASE 2 done: processed=" + done + ", success=" + success +
                ", failures=" + (done - success) + ", failByReason=" + failByReason + " ==="
        )
        return Result.success()
    }

    /**
     * Скачивает один трек в папку музыки, пишет ID3-теги и обложку и
     * регистрирует его в реестре скачанного. Если скачать не удалось
     * из-за запрета на воспроизведение (пустая ссылка после дорезолва) —
     * показывает единое уведомление «отсутствует локальная копия»
     * (MissingTrackNotifier). Временные сбои (сеть, HTTP, нет нативного
     * модуля для HLS) уведомление НЕ показывают — такой трек остаётся
     * нескачанным и будет повторён при следующем суточном прогоне.
     * HLS-треки скачиваются через M3U8 + TSDemuxer (нативный модуль);
     * если модуль не загружен — трек считается временно недоступным.
     *
     * DEBUG: возвращает строку-причину для сводки (ok / empty_url /
     * hls_no_native / download_failed_hls / download_failed_raw / stopped).
     * Логика не изменена — добавлено только логирование и возврат причины.
     */
    private fun downloadOne(audio: Audio, accountId: Long): String {
        val urlBefore = audio.url
        // Дорезолвить ссылку, если требуется (как в TrackDownloadWorker).
        val mode = audio.needRefresh()
        log(
            "    needRefresh: first=" + mode.first + ", second(old)=" + mode.second +
                ", urlBeforeClass=" + classifyUrl(urlBefore) + ", isHLS=" + audio.isHLS
        )
        if (mode.first) {
            log("    re-resolving via getByIdOld(old=" + mode.second + ") ...")
            val link: String? = InteractorFactory
                .createAudioInteractor()
                .getByIdOld(accountId, listOf(audio), mode.second)
                .map { e ->
                    e[0].url.nonNullNoEmpty({ l -> l }, { audio.url.orEmpty() })
                }.syncSingleSafe(audio.url)
            if (link.nonNullNoEmpty()) {
                log(
                    "    re-resolve returned urlClass=" + classifyUrl(link) +
                        ", changed=" + (link != urlBefore)
                )
                audio.setUrl(link)
            } else {
                log("    re-resolve returned empty/null (kept urlClass=" + classifyUrl(audio.url) + ")")
            }
        }

        val url = audio.url
        if (url.isNullOrEmpty()) {
            // Пустая ссылка после дорезолва = трек заблокирован/недоступен для
            // воспроизведения (запрет правообладателя). Это ЕДИНСТВЕННЫЙ случай,
            // который никогда не станет доступен сам по себе, поэтому именно здесь
            // показываем уведомление «отсутствует локальная копия».
            log("    -> FAIL: url empty after refresh (playback ban -> notify)")
            MissingTrackNotifier.show(applicationContext, audio)
            return "empty_url"
        }

        // HLS без нативного модуля распаковать невозможно. Это не запрет
        // на воспроизведение, а временная проблема на стороне приложения —
        // уведомление не показываем, трек будет повторён при следующем прогоне.
        if (audio.isHLS && !FenrirNative.isNativeLoaded) {
            log("    -> FAIL: HLS but native module not loaded (no notify, will retry)")
            return "hls_no_native"
        }

        val dir = Settings.get().main().musicDir
        val baseName = makeLegalFilename(audio.artist + " - " + audio.title, null)
        val fileName = "$baseName.mp3"
        val target = File(dir, fileName)

        val isHls = audio.isHLS
        log("    downloading: branch=" + (if (isHls) "HLS" else "RAW") + ", urlClass=" + classifyUrl(url))
        val ok = if (isHls) downloadHls(url, dir, baseName, target) else downloadRaw(url, target)
        if (!ok) {
            if (!isStopped) {
                // Сетевая/серверная ошибка при наличии ссылки — это НЕ запрет на
                // воспроизведение. Трек остаётся нескачанным (TrackIsDownloaded==0),
                // поэтому будет повторён при следующем суточном прогоне. Уведомление
                // не показываем, чтобы не спамить о временных сбоях.
                log("    -> FAIL: download returned false (branch=" + (if (isHls) "HLS" else "RAW") + ") (no notify, will retry)")
            } else {
                log("    -> STOPPED during download")
            }
            return if (isStopped) "stopped" else if (isHls) "download_failed_hls" else "download_failed_raw"
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
                    log("    tag/cover error (non-fatal): " + e.message)
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
        log("    -> OK: saved " + fileName)
        return "ok"
    }

    /**
     * HLS-поток: скачиваем M3U8 в .ts и распаковываем в mp3 через нативный TSDemuxer —
     * та же связка, что в TransparentCacheWorker, но без его уведомлений.
     */
    private fun downloadHls(url: String, dir: String, baseName: String, target: File): Boolean {
        val ts = File(dir, "$baseName.ts")
        return try {
            if (!M3U8(url, ts.absolutePath).run()) {
                throw Exception("M3U8 error download")
            }
            if (!TSDemuxer.unpackTS(
                    ts.absolutePath,
                    target.absolutePath,
                    info = false,
                    print_debug = false
                )
            ) {
                throw Exception("Error TSDemuxer")
            }
            true
        } catch (e: Exception) {
            log("      downloadHls exception: " + e.message)
            e.printStackTrace()
            if (target.exists()) {
                target.delete()
            }
            false
        } finally {
            if (ts.exists()) {
                ts.delete()
            }
        }
    }

    /**
     * Простое потоковое скачивание url -> target. Возвращает true при успехе.
     * Без уведомлений; при ошибке/остановке/неуспешном ответе удаляет частичный
     * или пустой файл. Все ресурсы (ответ, входной и выходной потоки)
     * закрываются через use{}, даже если запись прервётся исключением.
     */
    private fun downloadRaw(url: String?, target: File): Boolean {
        if (url.isNullOrEmpty()) {
            log("      downloadRaw: url null/empty")
            return false
        }
        var success = false
        try {
            val client = Utils.createOkHttp(Constants.DOWNLOAD_TIMEOUT, false).build()
            val request: Request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log("      downloadRaw: HTTP " + response.code + " " + response.message + " urlClass=" + classifyUrl(url))
                }
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
            log("      downloadRaw exception: " + e.message)
            e.printStackTrace()
            success = false
        }
        if (!success && target.exists()) {
            target.delete()
        }
        return success
    }

    companion object {
        private const val LOG_TAG = "FullAudioSync"
        private const val UNIQUE_NAME = "full_my_music_sync"
        private const val PAGE_COUNT = 100
        private const val MAX_PAGES = 1000
        private const val NOTIFICATION_FULL_SYNC = 4823
        private const val FOREGROUND_CHANNEL_ID = "worker_channel"
        private const val TARGET_HOUR = 5
        private const val INPUT_KEY_MANUAL = "manual"

        /**
         * Запускает синхронизацию немедленно по требованию пользователя (кнопка
         * «Ручной запуск»). В отличие от периодического запуска по
         * расписанию, ручной прогон игнорирует условия Wi-Fi и зарядки — см.
         * doWorkInner(). Условия «включено автоскачивание» и «есть аккаунт»
         * всё равно проверяются.
         */
        fun enqueueManualSync(context: Context) {
            val data = Data.Builder()
                .putBoolean(INPUT_KEY_MANUAL, true)
                .build()
            val request = OneTimeWorkRequest.Builder(FullAudioSyncWorker::class)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        /**
         * Планирует ежедневную фоновую синхронизацию «Моей музыки» примерно на 5:00.
         * Проверка условий (Wi-Fi, зарядка, наличие нового) — внутри doWork.
         * Если автоскачивание выключено — снимает запланированную задачу.
         *
         * FENRIR-CI (fix гонки двух воркеров): schedule() вызывается из App.onCreate()
         * на КАЖДОМ старте процесса — в том числе когда сам процесс поднят системой
         * специально ради выполнения уже назревшего периодического запуска. Раньше
         * здесь стояла политика UPDATE: она на каждый такой вызов пересоздавала
         * PeriodicWorkRequest (новый id + заново посчитанный initialDelay) и
         * подменяла им текущую запись WorkManager. Если это происходило в момент,
         * когда воркер уже начал выполнять doWork() под старым id, WorkManager
         * останавливал уже стартовавший экземпляр (isStopped=true, scanned=0) и
         * тут же запускал новый — отсюда в отладочном логе видно два запуска в
         * течение одной и той же секунды. В данном прогоне это не привело к потере
         * данных (второй запуск отработал полностью), но сама гонка потенциально
         * вредна: останов мог случиться и посреди уже идущей фазы 2 (скачивания).
         * Политика KEEP ничего не делает, если периодическая задача с этим именем
         * уже существует (ждёт своего часа или уже выполняется) — поэтому повторные
         * вызовы schedule() при каждом старте приложения больше не пересоздают и не
         * прерывают её. Новый запрос вставляется только когда задачи ещё нет вовсе
         * (первая установка приложения или повторное включение тумблера после того,
         * как он был выключен, — выключение делает cancelUniqueWork ниже).
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
                ExistingPeriodicWorkPolicy.KEEP,
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
