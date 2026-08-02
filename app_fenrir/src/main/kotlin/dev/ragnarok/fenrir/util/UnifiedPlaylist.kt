package dev.ragnarok.fenrir.util

import dev.ragnarok.fenrir.db.Stores
import dev.ragnarok.fenrir.model.Audio
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * FENRIR-CI (Этап 3): единый бесшовный плейлист — «Моя музыка» (VK) + офлайн-файлы устройства.
 *
 * Источники локальных треков (те же, что и на вкладке "На устройстве", bucket_id == 0):
 * 1. getLocalAudiosFromFolders() — папки A/B + собственная папка музыки Fenrir.
 * 2. getAudios() — общий широкий скан MediaStore по всему устройству (находит треки в любых
 *    папках, включая те, что не заданы как A/B, ровно как и вкладка "На устройстве").
 * Без источника (2) треки, лежащие вне A/B/musicDir, были видны на "На устройстве", но не
 * подмешивались в "Мою музыку" — расхождение исправлено объединением обоих источников.
 *
 * appendLocalOnly() возвращает только те локальные треки, которых ещё нет в переданном
 * VK-списке (дедуп по "artist|title" без учёта регистра — тот же ключ, что и в
 * AudiosLocalPresenter.mergeKey). VK-треки, у которых есть локальная копия, остаются в списке
 * и проигрывается из локального файла через MusicPlayer.makeMediaSource (приоритет локальной
 * копии, Этап 3/5).
 *
 * findLocalFallback() (доводка Этапа 3) — фолбэк для ОТДЕЛЬНЫХ VK-плейлистов (не «Моя музыка»):
 * если трек удалён/заблокирован на VK, ищем локальную копию по тому же ключу artist|title
 * среди последнего снимка локальных треков, вместо жёсткого отката на audio_error.ogg.
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

    private fun mergedLocalSnapshot(accountId: Long): Flow<List<Audio>> {
        return combine(
            Stores.instance.localMedia().getLocalAudiosFromFolders(accountId),
            Stores.instance.localMedia().getAudios(accountId)
        ) { folderAudios, deviceAudios ->
            val merged = ArrayList<Audio>(folderAudios.size + deviceAudios.size)
            val seen = HashSet<String>(folderAudios.size + deviceAudios.size)
            for (a in folderAudios) {
                if (seen.add(mergeKey(a))) {
                    merged.add(a)
                }
            }
            for (a in deviceAudios) {
                if (seen.add(mergeKey(a))) {
                    merged.add(a)
                }
            }
            merged
        }
    }

    fun appendLocalOnly(accountId: Long, existing: List<Audio>): Flow<List<Audio>> {
        val known = HashSet<String>(existing.size)
        for (a in existing) {
            known.add(mergeKey(a))
        }
        return mergedLocalSnapshot(accountId).map { locals ->
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
     * Прогревает снимок локальных треков (папки A/B/musicDir + широкий скан устройства) без
     * изменения текущего списка — нужен, чтобы findLocalFallback() работал и в отдельных
     * VK-плейлистах, где appendLocalOnly() не вызывается (там локальные треки не подмешиваются
     * в список, только используется как фолбэк при недоступности трека).
     */
    fun warmCache(accountId: Long): Flow<List<Audio>> {
        return mergedLocalSnapshot(accountId).map { locals ->
            cacheAll(locals)
            locals
        }
    }

    /**
     * Ищет локальную копию удалённого/заблокированного VK-трека по artist+title в последнем
     * снимке локальных треков. Возвращает null, если снимок ещё не прогрет (тогда вызывающий
     * код должен сохранить прежнее поведение) или совпадения нет.
     */
    fun findLocalFallback(audio: Audio): Audio? {
        if (audio.artist.isNullOrEmpty() && audio.title.isNullOrEmpty()) {
            return null
        }
        return localCache[mergeKey(audio)]
    }
}
