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

    // Максимальная глубина рекурсивного поиска локальной копии по подпапкам —
    // тот же лимит, что в LocalAudioFolderScanner (защита от глубоких/циклических деревьев).
    private const val MAX_PROBE_DEPTH = 12

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
     * FENRIR-CI: синхронно (без файлового скана и чтения ID3) возвращает локальные треки из
     * последнего снимка сканера (LocalAudioFolderScanner.cachedSnapshot), которых ещё нет в
     * переданном VK-списке. Нужен в момент старта воспроизведения «Моей музыки», чтобы
     * офлайн-файлы гарантированно попали в очередь плеера и в набор шафла, даже если фоновый
     * пересчёт mergeLocalUnified ещё не успел их дописать. Снимок наполняется из дискового
     * кэша практически сразу при открытии вкладки, поэтому к моменту нажатия Play он, как
     * правило, уже тёплый. Дедуп — по тому же ключу artist|title, что и в appendLocalOnly.
     */
    fun cachedLocalExtras(existing: List<Audio>): List<Audio> {
        val snap = LocalAudioFolderScanner.cachedSnapshot()
        if (snap.isEmpty()) {
            return emptyList()
        }
        cacheAll(snap)
        val known = HashSet<String>(existing.size)
        for (a in existing) {
            known.add(mergeKey(a))
        }
        val extras = ArrayList<Audio>()
        for (l in snap) {
            if (known.add(mergeKey(l))) {
                extras.add(l)
            }
        }
        return extras
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
     * ТОЛЬКО при сканировании списка «Моя музыка + офлайн». Пока пользователь не открыл
     * этот список, вручную добавленный файл в кэш не попадал → makeMediaSource его не находил
     * и подставлял заглушку audio_error.ogg.
     *
     * При промахе кэша сначала ищем файл СИНХРОННО и детерминированно (findLocalFileByName):
     * по тому же имени, что указано в уведомлении MissingTrackNotifier и по которому
     * качает DownloadWorkUtils. Если по имени файл не найден — как и раньше пробуем
     * прогретый кэш (совпадение по ID3-тегам artist|title).
     */
    fun findLocalFallback(audio: Audio): Audio? {
        if (audio.artist.isNullOrEmpty() && audio.title.isNullOrEmpty()) {
            return null
        }
        probeLocalFileByName(audio)?.let { return it }
        return localCache[mergeKey(audio)]
    }

    private fun probeLocalFileByName(audio: Audio): Audio? {
        val f = findLocalFileByName(audio.artist, audio.title) ?: return null
        return Audio().setUrl(Uri.fromFile(f).toString())
    }

    /**
     * Детерминированный поиск локального файла по ожидаемому имени
     * (makeLegalFilename("Исполнитель - Название") + расширение) в папках musicDir и
     * localAudioFolderA. Сначала — дешёвая проверка в КОРНЕ папок, затем, при промахе,
     * ограниченный рекурсивный обход подпапок (только сравнение имён файлов, без чтения
     * ID3 — поэтому безопасно и на main-thread: тяжёлый MediaMetadataRetriever здесь не
     * вызывается). Возвращает найденный File или null.
     *
     * FENRIR-CI (fix бага «файл во вложенной подпапке не находится»): раньше проверялся
     * только корень папок, из-за чего локальная копия недоступного трека, положенная во
     * вложенную подпапку, не находилась — плеер играл заглушку audio_error.ogg, а ночная
     * синхронизация каждую ночь показывала ложное уведомление «отсутствует локальная копия».
     */
    fun findLocalFileByName(artist: String?, title: String?): File? {
        if (artist.isNullOrEmpty() && title.isNullOrEmpty()) {
            return null
        }
        val baseName = DownloadWorkUtils.makeLegalFilename(
            (artist ?: "") + " - " + (title ?: ""), null
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
        exts.remove("")
        // Быстрый путь — файл прямо в корне папки (прежнее поведение).
        for (folder in folders) {
            for (ext in exts) {
                val f = File(folder, "$baseName.$ext")
                if (f.isFile) {
                    return f
                }
            }
        }
        // Медленный путь — рекурсивный обход подпапок по имени файла.
        val targetNames = HashSet<String>()
        for (ext in exts) {
            targetNames.add("$baseName.$ext".lowercase(Locale.getDefault()))
        }
        for (folder in folders) {
            val root = File(folder)
            if (!root.isDirectory) {
                continue
            }
            searchByNameRecursive(root, targetNames, 0)?.let { return it }
        }
        return null
    }

    private fun searchByNameRecursive(
        dir: File,
        targetNames: Set<String>,
        depth: Int
    ): File? {
        if (depth > MAX_PROBE_DEPTH) {
            return null
        }
        val children = dir.listFiles() ?: return null
        // Сначала файлы текущего уровня — дешевле, чем сразу спускаться в подпапки.
        for (f in children) {
            if (f.isFile && !f.isHidden &&
                targetNames.contains(f.name.lowercase(Locale.getDefault()))
            ) {
                return f
            }
        }
        for (f in children) {
            if (f.isDirectory && !f.isHidden) {
                searchByNameRecursive(f, targetNames, depth + 1)?.let { return it }
            }
        }
        return null
    }
}
