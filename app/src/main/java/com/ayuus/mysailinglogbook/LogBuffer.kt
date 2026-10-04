package com.ayuus.mysailinglogbook

/**
 * The running log, one entry per line, kept for the whole process: lines come in from the main thread, the
 * sync thread and the boat-mode service, and any Activity instance (also a new one after a rotation) shows
 * it from the start. The log view (LogAdapter) draws only the rows in sight, so keeping every line costs
 * memory, not time -- the full history stays scrollable. The cap is only a guard against a service running
 * for days; nmea2log.log has everything regardless.
 */
object LogBuffer {
    private const val MAX_LINES = 200_000
    private const val TRIM_TO = 150_000

    private val lock = Any()
    private val lines = ArrayList<String>()

    /** Bumped when lines were removed from the front or the contents were replaced, so a view knows its
     * rows no longer match and has to start over instead of only appending. */
    @Volatile
    var generation: Int = 0
        private set

    val size: Int get() = synchronized(lock) { lines.size }

    fun isEmpty(): Boolean = synchronized(lock) { lines.isEmpty() }

    operator fun get(index: Int): String = synchronized(lock) { lines.getOrElse(index) { "" } }

    /** Adds [text] (an already stamped line, possibly several separated by newlines) as separate entries. */
    fun add(text: String) {
        synchronized(lock) {
            lines.addAll(text.split("\n"))
            if (lines.size > MAX_LINES) {
                lines.subList(0, lines.size - TRIM_TO).clear()
                generation++
            }
        }
    }

    /** Replaces the whole log with [newLines]. */
    fun replaceAll(newLines: List<String>) {
        synchronized(lock) {
            lines.clear()
            lines.addAll(newLines)
            generation++
        }
    }
}
