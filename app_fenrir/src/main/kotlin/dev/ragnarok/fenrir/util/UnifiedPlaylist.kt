package dev.ragnarok.fenrir.util

import android.net.Uri
import dev.ragnarok.fenrir.db.Stores
import dev.ragnarok.fenrir.model.Audio
import dev.ragnarok.fenrir.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * FENRIR-CI (Этап 3): единый бесшовный плейлист — «Моя музыка» (VK) + офлайн-файлы устройства.
 *
 * Источник локальных треков — getLocalAudiosFromFolders() (папки A/B + собственная папка
 * музыки Fenrir), тот же файловый сканер, что используется во всей остальной части фичи
 * локальных папок. Широкий скан всего устройства через MediaStore (getAudios()) сюда
 * намеренно НЕ подмешивается — по явному требованию источник только папки A/Б/musicDir.
 * Если трек не находится, значит его нет ни в одной из этих папок, либо приложению не
 * хватает разрешения "Доступ ко всем файлам" (Scoped Storage, Android 11+) для чтения
 * произвольного пути через File.listFiles() — в отличие от MediaStore-запросов, эта
 * проверка не подменяется системой автоматически.
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
        return Stores.instance.localMedia().getLocalAudiosFromFolders(accountId)
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
     * Прогревает снимок локальных треков (папки A/B/musicDir) без изменения текущего списка —
     * нужен, чтобы findLocalFallback() работал и в отдельных VK-плейлистах, где
     * appendLocalOnly() не вызывается (там локальные треки не подмешиваются в список, только
     * используется как фолбэк при недоступности трека).
     */
    fun warmCache(accountId: Long): Flow<List<Audio>> {
        return mergedLocalSnapshot(accountId).map { locals ->
            cacheAll(locals)
            locals
        }
    }

    /**
     * Ищет локальную копию удалённого/заблокированного VK-трека по artist+title.
     *
     * FENRIR-CI (fix бага «вставленная вручную копия не играет, пока 3-4 раза не обновить
     * список»): раньше единственным источником фолбэка был localCache, который наполняется
     * ТОЛЬКО при сканировании списка «Моя музыка + офлайн» (warmCache / appendLocalOnly из
     * AudiosPresenter.mergeLocalUnified). Пока пользователь не открыл/не обновил этот список,
     * вручную добавленный в папку файл в кэш не попадал → makeMediaSource его не находил и
     * подставлял заглушку audio_error.ogg (те самые ~3 секунды). После нескольких обновлений
     * список пересканировался, кэш прогревался — и трек «внезапно» находился. Это не совпадение
     * и не «прошло время», а именно отложенный прогрев кэша.
     *
     * Теперь при промахе кэша сначала ищем файл СИНХРОННО и детерминированно — по тому же имени,
     * которое указано в уведомлении MissingTrackNotifier и по которому качает DownloadWorkUtils
     * (GetLocalTrackLink): makeLegalFilename("Исполнитель - Название") + "." + ext, в папке
     * скачанной музыки (musicDir) и в дополнительной локальной папке (localAudioFolderA). Это
     * дешёвые File.exists() (stat), безопасные на main-thread — там же уже вызывается
     * File(...).exists() для основной локальной копии. Тяжёлый рекурсивный скан с чтением ID3
     * сюда НЕ выносим (иначе ANR при построении очереди в setSources).
     *
     * Если по имени файл не найден (например, лежит во вложенной подпапке или переименован не по
     * шаблону) — как и раньше пробуем прогретый кэш (совпадение по ID3-тегам artist|title),
     * сохраняя прежнее поведение как запасной путь.
     */
    fun findLocalFallback(audio: Audio): Audio? {
        if (audio.artist.isNullOrEmpty() && audio.title.isNullOrEmpty()) {
            return null
        }
        probeLocalFileByName(audio)?.let { return it }
        return localCache[mergeKey(audio)]
    }

    /**
     * Дешёвый детерминированный поиск локального файла по ожидаемому имени в корне папок
     * musicDir и localAudioFolderA. Возвращает минимальный Audio с url=file://... (метаданные
     * для проигрывания берутся из исходного трека в makeMediaSource), либо null.
     */
    private fun probeLocalFileByName(audio: Audio): Audio? {
        val baseName = DownloadWorkUtils.makeLegalFilename(
            (audio.artist ?: "") + " - " + (audio.title ?: ""), null
        )
        if (baseName.isEmpty()) {
            return null
        }
        val main = Settings.get().main()
        val folders = LinkedHashSet<String>()
        main.musicDir.let { if (it.isNotEmpty()) folders.add(it) }
        main.localAudioFolderA?.let { if (it.isNotEmpty()) folders.add(it) }
        if (folders.isEmpty()) {
            return null
        }
        val exts = LinkedHashSet<String>()
        // .mp3 — именно так называет файл скачивание и уведомление MissingTrackNotifier.
        exts.add("mp3")
        exts.addAll(main.audioExt)
        for (folder in folders) {
            for (ext in exts) {
                if (ext.isEmpty()) {
                    continue
                }
                val f = File(folder, "$baseName.$ext")
                if (f.isFile) {
                    return Audio().setUrl(Uri.fromFile(f).toString())
                }
            }
        }
        return null
    }
}
