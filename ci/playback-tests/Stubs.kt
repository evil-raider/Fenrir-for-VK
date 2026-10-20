package android.content
import java.io.File
open class Context(val root: File = java.nio.file.Files.createTempDirectory("playback-test").toFile()) {
    val applicationContext get() = this
    val filesDir get() = File(root, "files").apply { mkdirs() }
    val cacheDir get() = File(root, "cache").apply { mkdirs() }
    fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
    private val stores = mutableMapOf<String, SharedPreferences>()
    fun getSharedPreferences(name: String, mode: Int) = stores.getOrPut(name) { SharedPreferences() }
    companion object { const val MODE_PRIVATE = 0 }
}
class SharedPreferences {
    private val values = mutableMapOf<String, Any>()
    fun getString(k: String, d: String?) = values[k] as? String ?: d
    fun getInt(k: String, d: Int) = values[k] as? Int ?: d
    fun getLong(k: String, d: Long) = values[k] as? Long ?: d
    fun getBoolean(k: String, d: Boolean) = values[k] as? Boolean ?: d
    @Suppress("UNCHECKED_CAST")
    fun getStringSet(k: String, d: Set<String>?) = values[k] as? Set<String> ?: d
    fun edit() = Editor()
    inner class Editor {
        fun putString(k: String, v: String?) { if (v == null) values.remove(k) else values[k] = v }
        fun putInt(k: String, v: Int) { values[k] = v }
        fun putLong(k: String, v: Long) { values[k] = v }
        fun putBoolean(k: String, v: Boolean) { values[k] = v }
        fun putStringSet(k: String, v: Set<String>) { values[k] = v.toSet() }
        fun remove(k: String) { values.remove(k) }
        fun apply() {}
        fun commit() = true
    }
}
