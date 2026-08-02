package dev.ragnarok.fenrir.media.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.ragnarok.fenrir.activity.MainActivity
import dev.ragnarok.fenrir.util.Utils

object NotificationHelper {
    fun getAudioPlayerPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        intent.action = MainActivity.ACTION_OPEN_AUDIO_PLAYER
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            Utils.makeImmutablePendingIntent(PendingIntent.FLAG_CANCEL_CURRENT)
        )
    }

    const val FENRIR_MUSIC_SERVICE = 1

    // FENRIR-CI: id уведомления «трек недоступен онлайн и нет локальной копии» — общий на все
    // такие случаи, чтобы повторные срабатывания обновляли одно уведомление, а не копились в шторке.
    const val FENRIR_TRACK_UNAVAILABLE = 2
}
