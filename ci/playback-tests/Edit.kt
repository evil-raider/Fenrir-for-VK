package androidx.core.content
import android.content.SharedPreferences
inline fun SharedPreferences.edit(action: SharedPreferences.Editor.() -> Unit) {
    edit().apply { action(); apply() }
}
