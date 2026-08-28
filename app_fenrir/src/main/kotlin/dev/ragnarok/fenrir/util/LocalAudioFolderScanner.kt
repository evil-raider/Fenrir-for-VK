package dev.ragnarok.fenrir.util

import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.ragnarok.fenrir.Constants
import dev.ragnarok.fenrir.model.Audio
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Scans plain filesystem folders for audio files and maps them to [Audio] models
 * with a file:// url so that the player can play them via the local
 * ProgressiveMediaSource path (see MusicPlaybackService.makeMediaSource).
 *
 * This does not rely on MediaStore, so it also finds files that were not (yet)
 * indexed by the system, which is the whole point of the "local folders" feature.
 *
 * FENRIR-CI (оптимизация загрузки офлайн-списка): раньше каждый вызов scanFolders заново
 * обходил папки И читал ID3-теги каждого файла через MediaMetadataRetriever (десятки-сотни
 * мс на файл), а AudiosPresenter дёргал его после КАЖДОЙ страницы VK — отсюда «десятки
 * секунд» до появления офлайн-файлов. Теперь есть двухуровневый кэш:
 *  - ОЗУ (memCache): path -> метаданные, живёт весь процесс;
 *  - диск (TSV в filesDir): переживает перезапуск приложения.
 * Ключ кэша — (абсолютный путь + размер + mtime); ID3 читается ТОЛЬКО для новых/изменённых
 * файлов (параллельно), сам обход файловой системы дешёвый. На холодном первом запуске
 * скан однократно долгий, дальше — почти мгновенно.
 */
object LocalAudioFolderScanner {
    private val DEFAULT_EXT = setOf("mp3", "ogg", "flac", "opus", "m4a", "wav", "aac")
    private const val MAX_DEPTH = 12
    private const val CACHE_FILE_NAME = "local_audio_scan_cache_v1.tsv"
    private const val META_THREADS = 4

    // FENRIR-CI: запись кэша по одному аудиофайлу. Не зависит от аккаунта (accountId подставляется
    // при сборке Audio), поэтому переиспользуется между аккаунтами и запусками.
    private class Entry(
        val path: String,
        val size: Long,
        val mtime: Long,
        val url: String,
        val artist: String,
        val title: String,
        val durationSec: Int
    )

    // FENRIR-CI: кэш метаданных в ОЗУ. Наполняется как с диска (ensureDiskLoaded), так и при
    // чтении новых/изменённых файлов.
    private val memCache = ConcurrentHashMap<String, Entry>()

    // FENRIR-CI: последний собранный снимок треков — для мгновенного СИНХРОННОГО доступа при
    // старте воспроизведения (см. UnifiedPlaylist.cachedLocalExtras / AudiosPresenter.playAudio),
    // чтобы офлайн-файлы гарантированно попадали в очередь и шафл без ожидания фонового скана.
    @Volatile
    private var lastSnapshot: List<Audio> = emptyList()

    @Volatile
    private var diskLoaded = false

    /** FENRIR-CI: последний снимок локальных треков без файлового скана (может быть пуст). */
    fun cachedSnapshot(): List<Audio> = lastSnapshot

    fun scanFolders(
        accountId: Long,
        folders: Collection<String>,
        allowedExt: Set<String>?,
        cacheDir: File? = null
    ): List<Audio> {
        ensureDiskLoaded(cacheDir)
        val ext = if (allowedExt.isNullOrEmpty()) DEFAULT_EXT else allowedExt

        // 1) Дешёвый обход ФС — только список файлов, без чтения тегов.
        val files = ArrayList<File>()
        val seenPaths = HashSet<String>()
        for (folderPath in folders) {
            if (folderPath.isEmpty()) {
                continue
            }
            val root = File(folderPath)
            if (!root.exists() || !root.isDirectory) {
                continue
            }
            collectFiles(root, ext, files, seenPaths, 0)
        }

        // 2) Определяем, для каких файлов реально нужно читать ID3 (новые/изменённые).
        val presentPaths = HashSet<String>(files.size)
        val toRead = ArrayList<File>()
        for (f in files) {
            val path = f.absolutePath
            presentPaths.add(path)
            val cached = memCache[path]
            if (cached == null || cached.size != f.length() || cached.mtime != f.lastModified()) {
                toRead.add(f)
            }
        }

        var dirty = false
        if (toRead.isNotEmpty()) {
            for (e in readMetaBatch(toRead)) {
                memCache[e.path] = e
            }
            dirty = true
        }

        // 3) Убираем из кэша записи для исчезнувших файлов.
        val iterator = memCache.keys.iterator()
        while (iterator.hasNext()) {
            if (!presentPaths.contains(iterator.next())) {
                iterator.remove()
                dirty = true
            }
        }

        // 4) Собираем результат в порядке обхода папок.
        val result = ArrayList<Audio>(files.size)
        for (f in files) {
            val e = memCache[f.absolutePath] ?: continue
            result.add(toAudio(e, accountId))
        }

        lastSnapshot = result
        if (dirty && cacheDir != null) {
            persist(cacheDir)
        }
        return result
    }

    private fun collectFiles(
        dir: File,
        ext: Set<String>,
        out: MutableList<File>,
        seen: MutableSet<String>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) {
            return
        }
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isHidden) {
                continue
            }
            if (f.isDirectory) {
                collectFiles(f, ext, out, seen, depth + 1)
            } else if (f.isFile) {
                val name = f.name
                val dot = name.lastIndexOf('.')
                if (dot < 0 || dot == name.length - 1) {
                    continue
                }
                val e = name.substring(dot + 1).lowercase()
                if (!ext.contains(e)) {
                    continue
                }
                val path = f.absolutePath
                if (!seen.add(path)) {
                    continue
                }
                out.add(f)
            }
        }
    }

    // FENRIR-CI: чтение ID3 для пачки новых/изменённых файлов. Параллелим — MediaMetadataRetriever
    // упирается в I/O, поэтому пул потоков заметно ускоряет холодный скан.
    private fun readMetaBatch(files: List<File>): List<Entry> {
        if (files.size <= 3) {
            return files.mapNotNull { readMeta(it) }
        }
        val pool = Executors.newFixedThreadPool(META_THREADS.coerceAtMost(files.size))
        return try {
            val futures = files.map { f -> pool.submit(Callable { readMeta(f) }) }
            futures.mapNotNull {
                try {
                    it.get()
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            if (Constants.IS_DEBUG) {
                e.printStackTrace()
            }
            files.mapNotNull { readMeta(it) }
        } finally {
            pool.shutdown()
        }
    }

    private fun readMeta(file: File): Entry? {
        val url = Uri.fromFile(file).toString()
        var title: String? = null
        var artist: String? = null
        var durationMs = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            durationMs =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            if (Constants.IS_DEBUG) {
                e.printStackTrace()
            }
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
        var trackName = if (title.isNullOrEmpty()) {
            val n = file.name
            val dot = n.lastIndexOf('.')
            if (dot > 0) n.substring(0, dot) else n
        } else {
            title
        }
        var trackArtist = artist ?: ""
        if (trackArtist.isEmpty()) {
            val arr = trackName.split(Regex(" - "), 2).toTypedArray()
            if (arr.size > 1) {
                trackArtist = arr[0]
                trackName = arr[1]
            }
        }
        var dur = 0
        if (durationMs > 0L) {
            dur = (durationMs / 1000L).toInt()
        }
        return Entry(
            file.absolutePath,
            file.length(),
            file.lastModified(),
            url,
            trackArtist,
            trackName,
            dur
        )
    }

    private fun toAudio(e: Entry, accountId: Long): Audio {
        return Audio()
            .setIsLocal()
            .setId(e.url.hashCode())
            .setOwnerId(accountId)
            .setDuration(e.durationSec)
            .setUrl(e.url)
            .setTitle(e.title)
            .setArtist(e.artist)
            .setThumb_image_big(e.url)
            .setThumb_image_little(e.url)
    }

    @Synchronized
    private fun ensureDiskLoaded(cacheDir: File?) {
        if (diskLoaded) {
            return
        }
        diskLoaded = true
        if (cacheDir == null) {
            return
        }
        try {
            val f = File(cacheDir, CACHE_FILE_NAME)
            if (!f.isFile) {
                return
            }
            f.forEachLine { line ->
                if (line.isEmpty()) {
                    return@forEachLine
                }
                val p = line.split('\t')
                if (p.size != 7) {
                    return@forEachLine
                }
                val size = p[1].toLongOrNull() ?: return@forEachLine
                val mtime = p[2].toLongOrNull() ?: return@forEachLine
                val dur = p[6].toIntOrNull() ?: 0
                val path = dec(p[0])
                memCache[path] = Entry(path, size, mtime, dec(p[3]), dec(p[4]), dec(p[5]), dur)
            }
        } catch (e: Exception) {
            if (Constants.IS_DEBUG) {
                e.printStackTrace()
            }
        }
    }

    @Synchronized
    private fun persist(cacheDir: File) {
        try {
            val sb = StringBuilder()
            for (e in memCache.values) {
                sb.append(enc(e.path)).append('\t')
                    .append(e.size).append('\t')
                    .append(e.mtime).append('\t')
                    .append(enc(e.url)).append('\t')
                    .append(enc(e.artist)).append('\t')
                    .append(enc(e.title)).append('\t')
                    .append(e.durationSec).append('\n')
            }
            File(cacheDir, CACHE_FILE_NAME).writeText(sb.toString())
        } catch (e: Exception) {
            if (Constants.IS_DEBUG) {
                e.printStackTrace()
            }
        }
    }

    // FENRIR-CI: URL-кодирование полей — гарантирует отсутствие разделителя (\t) и переводов
    // строки в значениях (пути/теги могут содержать любые символы).
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")
}
