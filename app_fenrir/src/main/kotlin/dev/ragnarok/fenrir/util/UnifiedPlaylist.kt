package dev.ragnarok.fenrir.util

import dev.ragnarok.fenrir.db.Stores
import dev.ragnarok.fenrir.model.Audio
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * FENRIR-CI (Этап 3): единый бесшовный плейлист — «Моя музыка» (VK) + офлайн-файлы из папок A/B.
 *
 * appendLocalOnly() возвращает только те локальные треки из папок A/B, которых ещё нет в
 * переданном VK-списке (дедуп по "artist|title" без учёта регистра — тот же ключ, что и в
 * AudiosLocalPresenter.mergeKey). VK-треки, у которых есть локальная копия, остаются в списке
 * и проигрываются из локального файла через MusicPlayer.makeMediaSource (приоритет локальной
 * копии, Этап 3/5).
 *
 * findLocalFallback() (доводка Этапа 3) — фолбэк для ОТДЕЛЬНЫХ VK-плейлистов (не «Моя музыка»):
 * если трек удалён/заблокирован на VK, ищем локальную копию по тому же ключу artist|title
 * среди последнего снимка папок A/B, вместо жёсткого отката на audio_error.ogg.
 */
object UnifiedPlaylist {

    private val localCache = ConcurrentHashMap<String, Audio>()

    private fun mergeKey(a: Audio): String {
        return ((a.artist ?: "") + "|" + (a.title ?: "")).lowercase(Locale.getDefault())
    }

    private fun cacheAll(locals: List<Audio>) {
        for (l in locals) {
            localCache[mergeKey(l)] = l
        }
    }

    fun appendLocalOnly(accountId: Long, existing: List<Audio>): Flow<List<Audio>> {
        val known = HashSet<String>(existing.size)
        for (a in existing) {
            known.add(mergeKey(a))
        }
        return Stores.instance.localMedia().getLocalAudiosFromFolders(accountId)
            .map { locals ->
                cacheAll(locals)
                val extras = ArrayList<Audio>()
                for (l in locals) {
                    if (known.add(mergeKey(l))) {
                        extras.add(l)
                    }
                }
                extras
            }
    }

    /**
     * Прогревает снимок локальных файлов (папки A/B) без изменения текущего списка —
     * нужен, чтобы findLocalFallback() работал и в отдельных VK-плейлистах, где
     * appendLocalOnly() не вызывается (там локальные треки не подмешиваются в список,
     * только используются как фолбэк при недоступности трека).
     */
    fun warmCache(accountId: Long): Flow<List<Audio>> {
        return Stores.instance.localMedia().getLocalAudiosFromFolders(accountId)
            .map { locals ->
                cacheAll(locals)
                locals
            }
    }

    /**
     * Ищет локальную копию удалённого/заблокированного VK-трека по artist+title в последнем
     * снимке папок A/B. Возвращает null, если снимок ещё не прогрет (тогда вызывающий код
     * должен сохранить прежнее поведение) или совпадения нет.
     */
    fun findLocalFallback(audio: Audio): Audio? {
        if (audio.artist.isNullOrEmpty() && audio.title.isNullOrEmpty()) {
            return null
        }
        return localCache[mergeKey(audio)]
    }
}
