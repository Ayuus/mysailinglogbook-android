package com.ayuus.mysailinglogbook

import android.content.Context
import java.io.File

/**
 * Where nmea2log.log lives: the app's external files folder (the one that shows up when the phone is connected to a PC,
 * next to the Actisense folder with the .ebl files), so the log can be read from a PC. The app's own private storage
 * (filesDir) cannot be reached from outside a release build; it is only the fallback when there is no external storage.
 */
object LogFile {
    fun directory(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    @Volatile
    private var migrated = false

    fun file(context: Context): File {
        if (!migrated) migrateFromPrivateStorage(context)
        return File(directory(context), SharedConstants.LOG_FILE_NAME)
    }

    /** The log of earlier versions was in the private folder: its lines go in front of what the new file holds, once
     * (before the first line is written there, so the order stays right). */
    @Synchronized
    fun migrateFromPrivateStorage(context: Context) {
        if (migrated) return
        migrated = true
        try {
            val old = File(context.filesDir, SharedConstants.LOG_FILE_NAME)
            val new = File(directory(context), SharedConstants.LOG_FILE_NAME)
            if (old.exists() && old.absolutePath != new.absolutePath) {
                val previous = if (new.exists()) new.readText(Charsets.UTF_8) else ""
                new.writeText(old.readText(Charsets.UTF_8) + previous, Charsets.UTF_8)
                old.delete()
            }
        } catch (e: Exception) {
            // best effort only: the log is never worth breaking the app over
        }
    }
}
