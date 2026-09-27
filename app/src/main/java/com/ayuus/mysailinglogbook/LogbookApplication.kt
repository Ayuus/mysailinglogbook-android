package com.ayuus.mysailinglogbook

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/** Applies the user's Licht/Donker/Apparaat choice (SettingsStore.themeMode, set on
 * SettingsActivity's own Appearance section) before any Activity is created, so the app's DayNight
 * resolution -- including the launch splash screen's own background color -- is already correct
 * on the very first frame instead of flashing the wrong theme and then switching.
 *
 * Defaults to "system", matching iOS's own default (see SettingsStore.themeMode's own comment).
 * On an Android 8.1 tablet, system-wide dark mode isn't exposed to the user at all before Android
 * 10, so MODE_NIGHT_FOLLOW_SYSTEM simply resolves to the light theme there -- "dark" is still
 * offered as an explicit choice in Settings for that device. */
class LogbookApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(
            when (SettingsStore(this).themeMode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }
}
