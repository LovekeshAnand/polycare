package org.polycare.app.ocr

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * One recognised word or phrase with its bounding rectangle in image pixels. [slopeDeg] is how far the
 * box's own writing direction is tilted from level (0 when unknown).
 */
data class TextBox(
    val text: String, val minX: Float, val maxX: Float, val minY: Float, val maxY: Float,
    val slopeDeg: Float = 0f,
) {
    val cx: Float get() = (minX + maxX) / 2
    val cy: Float get() = (minY + maxY) / 2
    val height: Float get() = maxY - minY
    val width: Float get() = maxX - minX
}

/**
 * Rebuilds the rows of a form from where the words sit on the page. The OCR returns every word as its
 * own box, and sorting boxes by height scrambles a handwritten card whose lines slope ("Anand" sits
 * higher than "Name" although both are on the same row). A form row is a label on the left with its
 * value to the right, so each value box is attached to the nearest label on its left, in the same
 * band of height, and the row is written as "Label: value value".
 *
 * A photo is rarely level, so the page's tilt (the median slope of the text boxes) is measured first
 * and all positions are levelled by that angle before rows are grouped.
 */
object RowBuilder {
    /** How many label-heights apart (vertically) a value may be from its label and still share its row. */
    private const val ROW_BAND = 1.5f

    /** Tilts smaller than this are left alone. */
    private const val MIN_TILT_DEG = 1.5f

    /** A box is a line of writing (not a stray mark) when it is at least this many times wider than tall. */
    private const val LINE_ASPECT = 1.5f

    private class Level(val box: TextBox, val x: Float, val y: Float)

    fun rows(boxes: List<TextBox>): List<String> {
        val tilt = pageTilt(boxes)
        val sin = sin(Math.toRadians(tilt.toDouble())).toFloat()
        val cos = cos(Math.toRadians(tilt.toDouble())).toFloat()
        val levelled = boxes.map { Level(it, it.cx * cos + it.cy * sin, -it.cx * sin + it.cy * cos) }

        // Every box that starts with a label begins a row, whether or not a value is already in the same box.
        val anchors = levelled.filter { McpFieldExtractor.startsWithLabel(it.box.text) }
        if (anchors.isEmpty()) return levelled.sortedBy { it.y }.map { it.box.text }

        val attached = anchors.associateWith { mutableListOf<Level>() }
        val loose = mutableListOf<Level>()
        for (b in levelled) {
            if (b in anchors) continue
            val owner = anchors
                .filter { a -> b.x > a.x && abs(b.y - a.y) <= ROW_BAND * max(a.box.height, b.box.height) }
                .minByOrNull { a -> abs(b.y - a.y) }
            if (owner != null) attached.getValue(owner) += b else loose += b
        }

        val rows = anchors.map { a ->
            val text = a.box.text.trim()
            val value = attached.getValue(a).sortedBy { it.x }.joinToString(" ") { it.box.text }
            val joined = when {
                value.isBlank() -> text
                McpFieldExtractor.isLabelOnly(text) -> "${text.trimEnd(':', '=', '-', ' ')}: $value"
                else -> "$text $value"
            }
            a.y to joined
        } + loose.map { it.y to it.box.text }
        return rows.sortedBy { it.first }.map { it.second }
    }

    /** Median slope of the line-shaped boxes, or 0 for a level (or unmeasurable) page. */
    private fun pageTilt(boxes: List<TextBox>): Float {
        val slopes = boxes.filter { it.width > LINE_ASPECT * it.height && it.text.length >= 3 }.map { it.slopeDeg }.sorted()
        if (slopes.size < 2) return 0f
        val median = slopes[slopes.size / 2]
        return if (abs(median) < MIN_TILT_DEG) 0f else median
    }
}
