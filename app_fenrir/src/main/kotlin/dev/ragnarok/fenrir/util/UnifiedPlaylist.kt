package dev.ragnarok.fenrir.util

import dev.ragnarok.fenrir.db.Stores
import dev.ragnarok.fenrir.model.Audio
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * FENRIR-CI (Этап 3): единый бесшовный плейлист — «Моя музыка» (VK) + офлайн-файлы из папок A/B.
 *
 * appendLocalOnly() возвращает только те локальные треки из папок A/B, которых ещё нет в
 * переданном VK-списке (дедуп по "artist|title" без учёта регистра — тот же ключ, что и в
 * AudiosLocalPresenter.mergeKey). VK-треки, у которых есть локальная копия, остаются в списке
 * и проигрываются из локального файла через MusicPlayer.makeMediaSource (приоритет локальной
 * копии, Этап 3/5).
 */
object UnifiedPlaylist {

    private fun mergeKey(a: Audio): String {
        return ((a.artist ?: "") + "|" + (a.title ?: "")).lowercase(Locale.getDefault())
    }

    fun appendLocalOnly(accountId: Long, existing: List<Audio>): Flow<List<Audio>> {
        val known = HashSet<String>(existing.size)
        for (a in existing) {
            known.add(mergeKey(a))
        }
        return Stores.instance.localMedia().getLocalAudiosFromFolders(accountId)
            .map { locals ->
                val extras = ArrayList<Audio>()
                for (l in locals) {
                    if (known.add(mergeKey(l))) {
                        extras.add(l)
                    }
                }
                extras
            }
    }
}
