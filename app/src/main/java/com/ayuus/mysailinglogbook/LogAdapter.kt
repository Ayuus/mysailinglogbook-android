package com.ayuus.mysailinglogbook

import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Shows LogBuffer as one row per line, so only the rows in sight are laid out and drawn: a new line costs
 * the same however long the log already is, which is what keeps a 2000-file decode scrollable.
 *
 * Every "[error]" line is shown bold and red and every "[warning]"/"[anomaly]"/"[geocode]" line bold and
 * amber -- a single line like that easily got lost among dozens of plain "[info]" ones. Whole line at a
 * time, so the timestamp and the rest of the message stand out too. "[geocode]" lines are all failed
 * lookups (Overpass or Nominatim); there is no separate success line to catch by accident. Hotspot-not-on
 * and W2K-2-not-found stay plain "[info]": expected states, not warnings.
 */
class LogAdapter(
    private val textSizeSp: Float,
    private val verticalPaddingPx: Int,
    private val errorColor: Int,
    private val warningColor: Int,
) : RecyclerView.Adapter<LogAdapter.Holder>() {

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    private var shown = 0
    private var shownGeneration = LogBuffer.generation

    override fun getItemCount(): Int = shown

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = TextView(parent.context).apply {
            textSize = textSizeSp
            typeface = Typeface.MONOSPACE
            setPadding(0, verticalPaddingPx, 0, verticalPaddingPx)
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return Holder(view)
    }

    private var defaultColor: Int? = null

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val view = holder.text
        val defaults = defaultColor ?: view.currentTextColor.also { defaultColor = it }
        val line = LogBuffer[position]
        view.text = line
        val color = when {
            "[error]" in line -> errorColor
            "[warning]" in line || "[anomaly]" in line || "[geocode]" in line -> warningColor
            else -> null
        }
        view.setTextColor(color ?: defaults)
        view.setTypeface(Typeface.MONOSPACE, if (color != null) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Brings the rows up to date with LogBuffer: only appends when nothing was removed or replaced since
     * the last call, else starts over. Main thread only. Returns whether rows were added or reset. */
    fun sync(): Boolean {
        val generation = LogBuffer.generation
        val size = LogBuffer.size
        if (generation != shownGeneration) {
            shownGeneration = generation
            shown = size
            notifyDataSetChanged()
            return true
        }
        if (size == shown) return false
        val before = shown
        shown = size
        notifyItemRangeInserted(before, size - before)
        return true
    }

    val lastPosition: Int get() = shown - 1
}
