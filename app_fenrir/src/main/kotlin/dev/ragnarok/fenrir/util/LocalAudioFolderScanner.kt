package dev.ragnarok.fenrir.util

import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.ragnarok.fenrir.Constants
import dev.ragnarok.fenrir.model.Audio
import java.io.File

/**
 * Scans plain filesystem folders for audio files and maps them to [Audio] models
 * with a file:// url so that the player can play them via the local
 * ProgressiveMediaSource path (see MusicPlaybackService.makeMediaSource).
 *
 * This does not rely on MediaStore, so it also finds files that were not (yet)
 * indexed by the system, which is the whole point of the "local folders" feature.
 */
object LocalAudioFolderScanner {
    private val DEFAULT_EXT = setOf("mp3", "ogg", "flac", "opus", "m4a", "wav", "aac")
    private const val MAX_DEPTH = 12

    fun scanFolders(
        accountId: Long,
        folders: Collection<String>,
        allowedExt: Set<String>?
    ): List<Audio> {
        val ext = if (allowedExt.isNullOrEmpty()) DEFAULT_EXT else allowedExt
        val result = ArrayList<Audio>()
        val seenPaths = HashSet<String>()
        for (folderPath in folders) {
            if (folderPath.isEmpty()) {
                continue
            }
            val root = File(folderPath)
            if (!root.exists() || !root.isDirectory) {
                continue
            }
            collect(root, ext, accountId, result, seenPaths, 0)
        }
        return result
    }

    private fun collect(
        dir: File,
        ext: Set<String>,
        accountId: Long,
        out: MutableList<Audio>,
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
                collect(f, ext, accountId, out, seen, depth + 1)
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
                mapFile(f, accountId)?.let { out.add(it) }
            }
        }
    }

    private fun mapFile(file: File, accountId: Long): Audio? {
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
        return Audio()
            .setIsLocal()
            .setId(url.hashCode())
            .setOwnerId(accountId)
            .setDuration(dur)
            .setUrl(url)
            .setTitle(trackName)
            .setArtist(trackArtist)
            .setThumb_image_big(url)
            .setThumb_image_little(url)
    }
}
