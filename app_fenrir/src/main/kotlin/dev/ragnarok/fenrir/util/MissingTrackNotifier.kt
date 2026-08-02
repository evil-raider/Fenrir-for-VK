package dev.ragnarok.fenrir.util

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.ragnarok.fenrir.R
import dev.ragnarok.fenrir.longpoll.AppNotificationChannels
import dev.ragnarok.fenrir.media.music.NotificationHelper
import dev.ragnarok.fenrir.model.Audio
import dev.ragnarok.fenrir.util.DownloadWorkUtils.makeLegalFilename
import java.util.Locale
import kotlin.math.abs

/**
 * FENRIR-CI: ЕДИНОЕ уведомление «отсутствует локальная копия недоступного онлайн файла».
 *
 * Показывается из трёх мест одним и тем же текстом:
 *  - MusicPlaybackService — при попытке проиграть недоступный трек (играет заглушка);
 *  - TransparentCacheWorker — прозрачное кэширование при прослушивании не смогло скачать файл;
 *  - FullAudioSyncWorker — ночная синхронизация «Моей музыки» не смогла скачать трек.
 *
 * В тексте указано ожидаемое имя файла ровно в том виде, в каком его создало бы
 * скачивание (makeLegalFilename("Artist - Title") + ".mp3") — по этому же ключу
 * файл найдут реестр скачанного и поиск локальной копии (UnifiedPlaylist /
 * LocalAudioFolderScanner парсит имя по разделителю " - ", если нет ID3-тегов).
 *
 * ID уведомления стабильно выводится из (ownerId, id) трека: повторный случай с тем же
 * треком обновляет своё уведомление, разные треки не затирают друг друга.
 */
object MissingTrackNotifier {
    private const val BASE_NOTIFICATION_ID = 4830
    private const val ID_RANGE = 512L

    @SuppressLint("MissingPermission")
    fun show(context: Context, audio: Audio) {
        if (!AppPerms.hasNotificationPermissionSimple(context)) {
            return
        }
        val app = context.applicationContext
        val manager =
            app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(AppNotificationChannels.getAudioChannel(app))
        val expectedName =
            makeLegalFilename((audio.artist ?: "") + " - " + (audio.title ?: ""), null) + ".mp3"
        val isRu = Locale.getDefault().language == "ru"
        val title = if (isRu) "Трек недоступен" else "Track unavailable"
        val text = if (isRu) {
            "Отсутствует локальная копия недоступного онлайн файла. Ожидается имя “$expectedName”"
        } else {
            "Local copy of an unavailable online file is missing. Expected name “$expectedName”"
        }
        val id = (BASE_NOTIFICATION_ID + abs((audio.ownerId * 31 + audio.id) % ID_RANGE)).toInt()
        val notification = NotificationCompat.Builder(app, AppNotificationChannels.AUDIO_CHANNEL_ID)
            .setSmallIcon(R.drawable.song)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(NotificationHelper.getAudioPlayerPendingIntent(app))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager.notify(id, notification)
    }
}
