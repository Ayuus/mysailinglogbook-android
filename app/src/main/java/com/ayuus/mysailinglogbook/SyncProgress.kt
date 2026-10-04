package com.ayuus.mysailinglogbook

import android.content.Context

/** The (translated) texts for the progress nmea2log reports through SyncController.onProgress(): which log
 * lines stand for progress, and what step of the build they are, is decided in Python (nmea2log/progress.py),
 * shared with the iOS app. */
object SyncProgress {
    const val DECODING = "decoding"
    const val BUILDING_TRIPS = "building_trips"

    /** The notification text for a progress report, or null for a phase this app has no text for. */
    fun notificationText(context: Context, phase: String, current: Int, total: Int): String? = when (phase) {
        DECODING -> context.getString(R.string.status_building_logbook, current, total)
        BUILDING_TRIPS -> context.getString(R.string.status_building_trips, current, total)
        else -> null
    }

    /** The label of the progress bar for a phase, or null for a phase this app has no label for. */
    fun phaseLabel(context: Context, phase: String): String? = when (phase) {
        DECODING -> context.getString(R.string.phase_decoding)
        BUILDING_TRIPS -> context.getString(R.string.phase_building_trips)
        else -> null
    }
}
