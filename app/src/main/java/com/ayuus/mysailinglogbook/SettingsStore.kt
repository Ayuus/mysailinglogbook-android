package com.ayuus.mysailinglogbook

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Wraps EncryptedSharedPreferences for the handful of settings this app needs -- W2K-2 login,
 * boat identity, and the WordPress publish settings. Replaces nmea2log.ini on Android (see
 * docs/android-app-plan.md): no config file, values entered once via SettingsActivity.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var w2k2User: String
        get() = prefs.getString(KEY_W2K2_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_W2K2_USER, value).apply()

    var w2k2Password: String
        get() = prefs.getString(KEY_W2K2_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_W2K2_PASSWORD, value).apply()

    var boatName: String
        get() = prefs.getString(KEY_BOAT_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BOAT_NAME, value).apply()

    var mmsi: String
        get() = prefs.getString(KEY_MMSI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MMSI, value).apply()

    var callSign: String
        get() = prefs.getString(KEY_CALL_SIGN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CALL_SIGN, value).apply()

    val isW2k2ConfigComplete: Boolean
        get() = w2k2User.isNotBlank() && w2k2Password.isNotBlank()

    // Off by default (asked for explicitly, reversing the original always-on-for-upgraders
    // default): the owner wants to choose whether opening the app tries to reach the W2K-2
    // right away (see MainActivity.onCreate()'s own autoStartSyncWithSettingsRetry() call) or
    // only ever downloads on an explicit tap of the download button.
    var autoSyncOnLaunch: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC_ON_LAUNCH, SharedDefaults.AUTO_SYNC_ON_LAUNCH)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC_ON_LAUNCH, value).apply()

    // On by default too (same reasoning as autoSyncOnLaunch above) -- asked for explicitly: the
    // owner wants to choose whether a successful download/offline-build also publishes on its own
    // (see MainActivity.uploadIfConfigured()'s two call sites) or just builds the logbook locally
    // -- checked via MainActivity's own new 📖 button (viewLocalLogbook()) and the ☁️ button still
    // publishes on demand either way.
    var autoPublishAfterBuild: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PUBLISH_AFTER_BUILD, SharedDefaults.AUTO_PUBLISH_AFTER_BUILD)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PUBLISH_AFTER_BUILD, value).apply()

    // Password auth, not a private key -- confirmed working against the real TransIP account
    // (2026-09-02), unlike the Ed25519-key account spike 4 used. Host and remote path default to
    // the real production values (not secret); user/password are never defaulted.
    // Minimum stationary duration (minutes) to count as a real port visit -- same threshold as
    // the desktop CLI's --min-stop-minutes, but a real app setting here instead of an ini value
    // (there's no ini file on Android, see android_entry.py's run_pipeline docstring), asked for
    // explicitly. Stored as a Float (SharedPreferences has no Double getter/setter); Double at
    // the call site since that's what run_pipeline()'s own min_stop_minutes parameter expects.
    var minStopMinutes: Double
        get() = prefs.getFloat(KEY_MIN_STOP_MINUTES, DEFAULT_MIN_STOP_MINUTES).toDouble()
        set(value) = prefs.edit().putFloat(KEY_MIN_STOP_MINUTES, value.toFloat()).apply()

    // Used for publishing (see MainActivity.uploadIfConfigured()) -- posts
    // straight to the WordPress REST endpoint (see wordpress-plugin/nmea2log-remarks.php's
    // /logbook route, and upload.py's own upload_via_rest() on the desktop side, which this calls
    // into over Chaquopy rather than reimplementing HTTP + Basic Auth here), so publishing needs
    // no SSH key/password on this device at all -- just a WordPress Application Password for an
    // account in the logboek_editor role. Never defaulted -- a brand new install shouldn't show a
    // real server hostname despite nothing ever being entered on that install (a default would ship
    // inside every APK build, readable by decompiling it or reading the source in a public repo).
    var restUploadUrl: String
        get() = prefs.getString(KEY_REST_UPLOAD_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REST_UPLOAD_URL, value).apply()

    var restUploadUser: String
        get() = prefs.getString(KEY_REST_UPLOAD_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REST_UPLOAD_USER, value).apply()

    var restUploadPassword: String
        get() = prefs.getString(KEY_REST_UPLOAD_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REST_UPLOAD_PASSWORD, value).apply()

    val isRestUploadConfigComplete: Boolean
        get() = restUploadUrl.isNotBlank() && restUploadUser.isNotBlank() && restUploadPassword.isNotBlank()

    // "light" / "dark" / "system" -- read by LogbookApplication.onCreate() (before any Activity,
    // so the app's DayNight resolution, including the launch splash screen's own background, is
    // already correct on the very first frame) and re-applied by SettingsActivity's Save button.
    // Defaults to "system" -- asked for explicitly, matching iOS's own default (its equivalent
    // setting was never forced any other way to begin with) rather than each platform starting
    // fresh installs looking different for no reason. The Android 8.1 tablet with no system
    // dark-mode toggle at all (see LogbookApplication's own comment) simply resolves "system" to
    // light there -- "dark" is still available as an explicit choice in Settings for it.
    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, SharedDefaults.THEME_MODE) ?: SharedDefaults.THEME_MODE
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    // Boat mode (see BootModeController and nmea2log/bootmode.py, whose BootModeConfig has the same
    // names in snake_case and the same defaults).
    var bootRoundIntervalMinutes: Int
        get() = prefs.getInt(KEY_BOOT_ROUND_INTERVAL_MINUTES, SharedDefaults.BOOT_ROUND_INTERVAL_MINUTES)
        set(value) = prefs.edit().putInt(KEY_BOOT_ROUND_INTERVAL_MINUTES, value).apply()

    var bootPublishEveryRound: Boolean
        get() = prefs.getBoolean(KEY_BOOT_PUBLISH_EVERY_ROUND, SharedDefaults.BOOT_PUBLISH_EVERY_ROUND)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_PUBLISH_EVERY_ROUND, value).apply()

    var bootFinalOnHarbour: Boolean
        get() = prefs.getBoolean(KEY_BOOT_FINAL_ON_HARBOUR, SharedDefaults.BOOT_FINAL_ON_HARBOUR)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_FINAL_ON_HARBOUR, value).apply()

    var bootHarbourStationaryMinutes: Int
        get() = prefs.getInt(KEY_BOOT_HARBOUR_STATIONARY_MINUTES, SharedDefaults.BOOT_HARBOUR_STATIONARY_MINUTES)
        set(value) = prefs.edit().putInt(KEY_BOOT_HARBOUR_STATIONARY_MINUTES, value).apply()

    var bootHarbourEngineOffMinutes: Int
        get() = prefs.getInt(KEY_BOOT_HARBOUR_ENGINE_OFF_MINUTES, SharedDefaults.BOOT_HARBOUR_ENGINE_OFF_MINUTES)
        set(value) = prefs.edit().putInt(KEY_BOOT_HARBOUR_ENGINE_OFF_MINUTES, value).apply()

    var bootFinalOnLeftBoat: Boolean
        get() = prefs.getBoolean(KEY_BOOT_FINAL_ON_LEFT_BOAT, SharedDefaults.BOOT_FINAL_ON_LEFT_BOAT)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_FINAL_ON_LEFT_BOAT, value).apply()

    var bootLeftBoatMinutes: Int
        get() = prefs.getInt(KEY_BOOT_LEFT_BOAT_MINUTES, SharedDefaults.BOOT_LEFT_BOAT_MINUTES)
        set(value) = prefs.edit().putInt(KEY_BOOT_LEFT_BOAT_MINUTES, value).apply()

    var bootStopAfterFinal: Boolean
        get() = prefs.getBoolean(KEY_BOOT_STOP_AFTER_FINAL, SharedDefaults.BOOT_STOP_AFTER_FINAL)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_STOP_AFTER_FINAL, value).apply()

    var bootAutoStart: Boolean
        get() = prefs.getBoolean(KEY_BOOT_AUTO_START, SharedDefaults.BOOT_AUTO_START)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_AUTO_START, value).apply()

    /** The boat-mode settings as the JSON nmea2log.bootmode.BootModeConfig.from_dict() takes. */
    fun bootModeConfigJson(): String = JSONObject()
        .put("round_interval_minutes", bootRoundIntervalMinutes)
        .put("publish_every_round", bootPublishEveryRound)
        .put("final_on_harbour", bootFinalOnHarbour)
        .put("harbour_stationary_minutes", bootHarbourStationaryMinutes)
        .put("harbour_engine_off_minutes", bootHarbourEngineOffMinutes)
        .put("final_on_left_boat", bootFinalOnLeftBoat)
        .put("left_boat_minutes", bootLeftBoatMinutes)
        .put("stop_after_final", bootStopAfterFinal)
        .put("publish_configured", isRestUploadConfigComplete)
        .toString()

    companion object {
        // The defaults of both apps are defined once, in nmea2log/app_settings.py (see SharedConstants.kt).
        val DEFAULT_MIN_STOP_MINUTES = SharedDefaults.DEFAULT_MIN_STOP_MINUTES.toFloat()
        private const val KEY_W2K2_USER = "w2k2_user"
        private const val KEY_W2K2_PASSWORD = "w2k2_password"
        private const val KEY_BOAT_NAME = "boat_name"
        private const val KEY_MMSI = "mmsi"
        private const val KEY_CALL_SIGN = "call_sign"
        private const val KEY_AUTO_SYNC_ON_LAUNCH = "auto_sync_on_launch"
        private const val KEY_AUTO_PUBLISH_AFTER_BUILD = "auto_publish_after_build"
        private const val KEY_MIN_STOP_MINUTES = "min_stop_minutes"
        private const val KEY_REST_UPLOAD_URL = "rest_upload_url"
        private const val KEY_REST_UPLOAD_USER = "rest_upload_user"
        private const val KEY_REST_UPLOAD_PASSWORD = "rest_upload_password"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BOOT_ROUND_INTERVAL_MINUTES = "boot_round_interval_minutes"
        private const val KEY_BOOT_PUBLISH_EVERY_ROUND = "boot_publish_every_round"
        private const val KEY_BOOT_FINAL_ON_HARBOUR = "boot_final_on_harbour"
        private const val KEY_BOOT_HARBOUR_STATIONARY_MINUTES = "boot_harbour_stationary_minutes"
        private const val KEY_BOOT_HARBOUR_ENGINE_OFF_MINUTES = "boot_harbour_engine_off_minutes"
        private const val KEY_BOOT_FINAL_ON_LEFT_BOAT = "boot_final_on_left_boat"
        private const val KEY_BOOT_LEFT_BOAT_MINUTES = "boot_left_boat_minutes"
        private const val KEY_BOOT_STOP_AFTER_FINAL = "boot_stop_after_final"
        private const val KEY_BOOT_AUTO_START = "boot_auto_start"
    }
}
