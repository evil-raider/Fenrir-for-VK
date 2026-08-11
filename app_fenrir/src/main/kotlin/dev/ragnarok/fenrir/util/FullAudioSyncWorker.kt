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
 * дорезолва ссылка пустая (запрет правообладателя) И нет локальной копии на диске.
 * Временные сбои (сетевые ошибки, HTTP, незагруженный нативный модуль для HLS)
 * НЕ порождают уведомление: трек остаётся нескачанным (TrackIsDownloaded==0) и будет
 * повторён при следующем суточном прогоне. Идемпотентность — TrackIsDownloaded(audio) == 0.
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
            "=== P