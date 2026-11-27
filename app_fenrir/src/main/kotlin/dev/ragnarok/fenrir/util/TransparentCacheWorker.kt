package dev.ragnarok.fenrir.util

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.ragnarok.fenrir.Constants
import dev.ragnarok.fenrir.R
import dev.ragnarok.fenrir.domain.InteractorFactory
import dev.ragnarok.fenrir.media.music.MusicPlaybackController
import dev.ragnarok.fenrir.model.Audio
import dev.ragnarok.fenrir.module.FenrirNative
import dev.ragnarok.fenrir.module.FileUtils
import dev.ragnarok.fenrir.module.hls.TSDemuxer
import dev.ragnarok.fenrir.nonNullNoEmpty
import dev.ragnarok.fenrir.settings.Settings
import dev.ragnarok.fenrir.toColor
import dev.ragnarok.fenrir.util.DownloadWorkUtils.CheckDirectory
import dev.ragnarok.fenrir.util.DownloadWorkUtils.makeLegalFilename
import dev.ragnarok.fenrir.util.coroutines.CoroutinesUtils.syncSingleSafe
import dev.ragnarok.fenrir.util.hls.M3U8
import kotlinx.coroutines.flow.map
import kotlinx.serialization.msgpack.MsgPack
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * FENRIR-CI: тихое прозрачное кэширование прослушиваемого онлайн-трека.
 *
 * Отдельный воркер вместо штатного TrackDownloadWorker, потому что тот показывает
 * несколько громких heads-up уведомлений на каждый трек (начало, завершение, теги)
 * плюс тосты — для фонового кэширования это шумно. Здесь — ОДНО тихое уведомление
 * (канал IMPORTANCE_LOW: без звука и всплывающего окна), которое видно только пока
 * идёт скачивание и бесследно убирается по завершении. Если скачать файл не удалось
 * (трек недоступен онлайн, HLS без нативного модуля, сетевая ошибка) — показываем
 * ЕДИНОЕ уведомление «отсутствует локальная копия недоступного онлайн файла»
 * (MissingTrackNotifier), такое же, как в плеере и ночной синхронизации.
 *
 * Логика скачивания повторяет TrackDownloadWorker/FullAudioSyncWorker: дорезолв
 * протухшей ссылки, HLS через M3U8+TSDemuxer (нативный модуль), ID3-теги и обложка,
 * регистрация в реестре скачанного. Дублирование сознательное — чтобы не менять
 * поведение штатных уведомлений ручного скачивания в DownloadWorkUtils.kt (та же
 * причина, по которой FullAudioSyncWorker не вызывает TrackDownloadWorker напрямую).
 * Папка назначения — та же, что у всех скачиваний музыки: Settings.main().musicDir,
 * поэтому файл видят и реестр «скачано», и приоритет локальной копии в плеере,
 * и ночное автоскачивание.
 */
class TransparentCacheWorker(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {

    private val notifyManager = NotificationManagerCompat.from(context.applicationContext)
    private var channelCreated = false

    private fun ensureChannel() {
        if (channelCreated) {
            return
        }
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.downloading),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        channelCreated = true
    }

    @SuppressLint("MissingPermission")
    private fun notifyProgress(title: String, text: String) {
        if (!AppPerms.hasNotificationPermissionSimple(applicationContext)) {
            return
        }
        ensureChannel()
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.save)
            .setColor("#dd0000".toColor())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        notifyManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun cancelNotification() {
        notifyManager.cancel(NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission")
    private fun notifyError(text: String) {
        if (!AppPerms.hasNotificationPermissionSimple(applicationContext)) {
            return
        }
        ensureChannel()
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.error))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.save)
            .setColor("#dd0000".toColor())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOngoing(false)
        notifyManager.notify(NOTIFICATION_ID, builder.build())
    }

    override fun doWork(): Result {
        val audioRaw = inputData.getByteArray(EXTRA_AUDIO) ?: return Result.failure()
        val audio = MsgPack.decodeFromByteArrayEx(Audio.serializer(), audioRaw)
        val accountId = inputData.getLong(EXTRA_ACCOUNT, -1)

        val dir = Settings.get().main().musicDir
        CheckDirectory(dir)
        val baseName = makeLegalFilename(audio.artist + " - " + audio.title, null)
        val fileName = "$baseName.mp3"
        val target = File(dir, fileName)

        // Файл уже есть и не пустой — только регистрируем в реестре и тихо выходим.
        if (target.isFile && target.length() >= MIN_VALID_AUDIO_BYTES) {
            MusicPlaybackController.tracksExist.addAudio(fileName)
            return Result.success()
        }
        if (target.exists()) {
            target.delete()
        }

        val trackName = ((audio.artist ?: "") + " - " + (audio.title ?: "")).trim()
        notifyProgress(applicationContext.getString(R.string.downloading), trackName)

        try {
            // Дорезолв протухшей ссылки (как в TrackDownloadWorker). При включённом
            // api 5.90 VK обычно возвращает прямую mp3-ссылку вместо HLS.
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

            val url = audio.url
            if (url.isNullOrEmpty() || url.contains("audio_api_unavailable") ||
                (audio.isHLS && !FenrirNative.isNativeLoaded)
            ) {
                // Трек недоступен онлайн (или HLS нечем распаковать) — локальной копии не будет.
                cancelNotification()
                MissingTrackNotifier.show(applicationContext, audio)
                return Result.failure()
            }

            var ok = if (audio.isHLS) {
                downloadHls(url, dir, baseName, target)
            } else {
                when (downloadAudioRaw(url, target)) {
                    RAW_OK -> true
                    RAW_IS_HLS -> FenrirNative.isNativeLoaded && downloadHls(url, dir, baseName, target)
                    else -> false
                }
            }
            if (!ok) {
                if (target.exists()) {
                    target.delete()
                }
                if (isStopped) {
                    cancelNotification()
                } else if (audio.isHLS && !FenrirNative.isNativeLoaded) {
                    cancelNotification()
                    MissingTrackNotifier.show(applicationContext, audio)
                } else {
                    notifyError(trackName)
                }
                return Result.failure()
            }

            // Обложка + ID3-теги — то же самое уведомление, меняется только текст стадии.
            val cover = Utils.firstNonEmptyString(
                audio.thumb_image_very_big,
                audio.thumb_image_big,
                audio.thumb_image_little
            )
            if (FenrirNative.isNativeLoaded && cover.nonNullNoEmpty()) {
                notifyProgress(applicationContext.getString(R.string.tag_modified), trackName)
                val coverFile = File(dir, "$baseName.jpg")
                if (downloadRaw(cover, coverFile, isAudio = false) == RAW_OK) {
                    try {
                        val ifGenre =
                            if (audio.genreByID3 != 0) audio.genreByID3.toString() else null
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
            cancelNotification()
            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            if (target.exists()) {
                target.delete()
            }
            if (isStopped) {
                cancelNotification()
            } else {
                notifyError(trackName)
            }
            return Result.failure()
        }
    }

    /**
     * HLS-поток: скачиваем M3U8 в .ts и распаковываем во временный .part перед переносом в target.
     */
    private fun downloadHls(url: String, dir: String, baseName: String, target: File): Boolean {
        val ts = File(dir, "$baseName.ts")
        val part = File(target.parentFile, "${target.name}.part")
        return try {
            if (!M3U8(url, ts.absolutePath).run()) {
                throw Exception("M3U8 error download")
            }
            if (!TSDemuxer.unpackTS(
                    ts.absolutePath,
                    part.absolutePath,
                    info = false,
                    print_debug = false
                )
            ) {
                throw Exception("Error TSDemuxer")
            }
            if (!part.isFile || part.length() < MIN_VALID_AUDIO_BYTES) {
                throw Exception("Invalid HLS output size: ${part.length()}")
            }
            if (target.exists()) {
                target.delete()
            }
            if (!part.renameTo(target)) {
                throw Exception("Failed to move temp file to ${target.name}")
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            if (part.exists()) {
                part.delete()
            }
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

    private fun downloadAudioRaw(url: String?, target: File): Int =
        downloadRaw(url, target, isAudio = true)

    /**
     * Потоковое скачивание во временный .part-файл с проверкой HTTP Content-Type,
     * Content-Length, минимального размера и сигнатуры тела перед переименованием в target.
     */
    private fun downloadRaw(url: String?, target: File, isAudio: Boolean = true): Int {
        if (url.isNullOrEmpty()) {
            return RAW_FAILED
        }
        val part = File(target.parentFile, "${target.name}.part")
        var result = RAW_FAILED
        try {
            if (part.exists()) {
                part.delete()
            }
            val client = Utils.createOkHttp(Constants.DOWNLOAD_TIMEOUT, false).build()
            val request: Request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use
                }
                val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                if (isAudio) {
                    if (contentType.contains("mpegurl")) {
                        result = RAW_IS_HLS
                        return@use
                    }
                    if (contentType.contains("text/html") ||
                        contentType.contains("application/json") ||
                        contentType.contains("text/plain")
                    ) {
                        return@use
                    }
                }
                val expectedLen = response.body.contentLength()
                var written = 0L
                val headBuf = ByteArray(64)
                var headLen = 0
                FileOutputStream(part).use { output ->
                    BufferedInputStream(response.body.byteStream()).use { input ->
                        val buffer = ByteArray(80 * 1024)
                        var len: Int
                        while (input.read(buffer).also { len = it } != -1) {
                            if (isStopped) {
                                return@use
                            }
                            if (headLen < headBuf.size) {
                                val take = (headBuf.size - headLen).coerceAtMost(len)
                                System.arraycopy(buffer, 0, headBuf, headLen, take)
                                headLen += take
                            }
                            output.write(buffer, 0, len)
                            written += len
                        }
                    }
                }
                if (isStopped) {
                    return@use
                }
                if (expectedLen > 0 && written != expectedLen) {
                    return@use
                }
                if (isAudio) {
                    val head = String(headBuf, 0, headLen).trimStart().lowercase()
                    if (head.startsWith("#extm3u")) {
                        result = RAW_IS_HLS
                        return@use
                    }
                    if (written < MIN_VALID_AUDIO_BYTES ||
                        head.startsWith("<!doctype") ||
                        head.startsWith("<html") ||
                        head.startsWith("{")
                    ) {
                        return@use
                    }
                }
                if (target.exists()) {
                    target.delete()
                }
                if (part.renameTo(target)) {
                    result = RAW_OK
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            result = RAW_FAILED
        } finally {
            if (part.exists()) {
                part.delete()
            }
        }
        return result
    }

    companion object {
        private const val CHANNEL_ID = "transparent_cache_channel"
        private const val NOTIFICATION_ID = 4824
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_ACCOUNT = "account"
        private const val MIN_VALID_AUDIO_BYTES = 4096L
        private const val RAW_OK = 0
        private const val RAW_FAILED = 1
        private const val RAW_IS_HLS = 2

        /**
         * Собирает запрос на тихое кэширование одного трека. Ставится в очередь из
         * MusicPlaybackService под уникальным ключом transparent_cache_{owner}_{id}.
         */
        fun makeRequest(audio: Audio, accountId: Long): OneTimeWorkRequest {
            val data = Data.Builder()
            data.putByteArray(EXTRA_AUDIO, MsgPack.encodeToByteArrayEx(Audio.serializer(), audio))
            data.putLong(EXTRA_ACCOUNT, accountId)
            return OneTimeWorkRequest.Builder(TransparentCacheWorker::class)
                .setInputData(data.build())
                .build()
        }
    }
}
