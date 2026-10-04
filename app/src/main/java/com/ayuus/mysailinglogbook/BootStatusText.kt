package com.ayuus.mysailinglogbook

import android.content.Context

/** The (translated) text for a status of the boat mode -- a nmea2log.bootmode.Status name. Which text belongs to
 * which status is nmea2log's table (bootmode.STATUS_TEXT_KEYS, in SharedConstants); the text itself is the string
 * resource of that name. */
object BootStatusText {
    /** null for a kind this version does not know. [nextAt] is a time to show where the text has one. */
    fun format(context: Context, kind: String, nextAt: Long?): String? {
        val key = SharedConstants.BOOT_STATUS_TEXT_KEYS[kind] ?: return null
        val resId = context.resources.getIdentifier(key, "string", context.packageName)
        if (resId == 0) return null
        if (nextAt == null) return context.getString(resId)
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(nextAt))
        return context.getString(resId, time)
    }
}
