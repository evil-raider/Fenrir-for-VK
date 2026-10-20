package de.maxr1998.modernpreferences
import android.content.Context
object PreferenceScreen { fun getPreferences(context: Context) = context.getSharedPreferences("main", 0) }
