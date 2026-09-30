package com.ayuus.mysailinglogbook

/** Chaquopy-visible callback for nmea2log.android_entry.import_staged_ebl_files_json(): called
 * once per file, right after (not before -- see import_ebl.py's own ImportProgressCallback doc
 * comment) its outcome is decided, during that same Python call -- the same "real work, then
 * report it, one file at a time" shape SyncController.report() already uses for a download, so
 * importFromRemovableMedia()'s own log/progress bar can show files landing one at a time as they
 * actually happen, not replay an already-finished list once the call has already returned (found
 * in practice, a real regression when this didn't exist yet: nothing paces a loop over an
 * already-completed result list, no matter how that loop is throttled from the Kotlin side). */
interface ImportProgressCallback {
    fun report(current: Int, total: Int, name: String, outcome: String)
}
