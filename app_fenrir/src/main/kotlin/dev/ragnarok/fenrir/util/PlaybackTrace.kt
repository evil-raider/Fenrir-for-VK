package dev.ragnarok.fenrir.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.ragnarok.fenrir.BuildConfig
import dev.ragnarok.fenrir.model.Audio
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bounded, local-only flight recorder. No URLs, tokens, titles or file paths in records. */
object PlaybackTrace {
    private const val TAG = "FenrirPlayback"
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val FILES = 4
    private val process = UUID.randomUUID().toString()
    private val sequence = AtomicLong()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var started = false // IO thread only

    fun token(value: String?): String {
        if (value == null) return "none"
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(24) {
            for (i in 0 until 12) {
                val b = bytes[i].toInt() and 255
                append(hex[b ushr 4]); append(hex[b and 15])
            }
        }
    }

    fun audio(audio: Audio?): String = audio?.let { token(PersistentShuffle.keyOf(it)) } ?: "none"

    // Different VK IDs / online+local entries for the same named recording remain
    // distinguishable by track token, but can now be correlated without exposing titles.
    fun recording(audio: Audio?): String = audio?.let {
        token((it.artist.orEmpty().trim() + "|" + it.title.orEmpty().trim()).lowercase(java.util.Locale.ROOT))
    } ?: "none"

    private fun directory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "playback-debug").apply { mkdirs() }

    fun event(context: Context, event: String, vararg fields: kotlin.Pair<String, Any?>) {
        // Freeze values on caller thread; writer never reads mutable player/Audio objects.
        val app = context.applicationContext
        val record = JSONObject().put("schema", 1).put("process", process)
            .put("seq", sequence.incrementAndGet()).put("wallMs", System.currentTimeMillis())
            .put("elapsedMs", SystemClock.elapsedRealtime()).put("event", event)
        fields.forEach { (key, value) -> record.put(key, value ?: JSONObject.NULL) }
        val line = record.toString() + "\n"
        io.execute {
            try {
                val dir = directory(app)
                if (!started) {
                    started = true
                    write(dir, JSONObject().put("schema", 1).put("event", "process_start")
                        .put("process", process).put("version", BuildConfig.VERSION_NAME)
                        .put("versionCode", BuildConfig.VERSION_CODE).put("debug", BuildConfig.DEBUG)
                        .put("wallMs", System.currentTimeMillis()).toString() + "\n")
                }
                write(dir, line)
            } catch (e: Exception) {
                // Never interrupt playback for diagnostics, and never log exception messages/URLs.
                Log.w(TAG, "trace write failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun write(dir: File, line: String) {
        val current = File(dir, "playback-0.jsonl")
        if (current.length() + line.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            File(dir, "playback-${FILES - 1}.jsonl").delete()
            for (i in FILES - 2 downTo 0) {
                val from = File(dir, "playback-$i.jsonl")
                if (from.exists()) check(from.renameTo(File(dir, "playback-${i + 1}.jsonl")))
            }
        }
        current.appendText(line, Charsets.UTF_8)
    }

    /** Same executor: ZIP includes every record queued before the export request. */
    fun export(context: Context, callback: (File?) -> Unit) {
        val app = context.applicationContext
        event(app, "export")
        io.execute {
            val result = try {
                val exportDir = File(app.cacheDir, "playback-debug").apply { mkdirs() }
                val zip = File(exportDir, "fenrir-playback-debug.zip")
                ZipOutputStream(zip.outputStream()).use { out ->
                    for (i in FILES - 1 downTo 0) {
                        val file = File(directory(app), "playback-$i.jsonl")
                        if (file.exists()) {
                            out.putNextEntry(ZipEntry(file.name))
                            file.inputStream().use { it.copyTo(out) }
                            out.closeEntry()
                        }
                    }
                }
                zip
            } catch (e: Exception) {
                Log.w(TAG, "trace export failed: ${e.javaClass.simpleName}")
                null
            }
            main.post { callback(result) }
        }
    }
}
