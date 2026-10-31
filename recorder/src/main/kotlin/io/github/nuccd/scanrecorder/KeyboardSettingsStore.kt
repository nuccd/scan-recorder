package io.github.nuccd.scanrecorder

import android.content.Context
import androidx.core.content.edit

internal class KeyboardSettingsStore(context: Context) {
  private val preferences = context.applicationContext.getSharedPreferences("keyboard_mode", Context.MODE_PRIVATE)

  var enabled: Boolean
    get() = preferences.getBoolean("enabled", false)
    set(value) {
      preferences.edit { putBoolean("enabled", value) }
    }
}