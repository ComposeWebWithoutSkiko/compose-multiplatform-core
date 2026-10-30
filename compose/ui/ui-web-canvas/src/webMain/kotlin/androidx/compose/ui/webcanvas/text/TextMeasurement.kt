/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.webcanvas.text

import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.JsStrings
import androidx.compose.ui.webcanvas.dom.createCanvas
import androidx.compose.ui.webcanvas.dom.fontBoundingBoxAscentOf
import androidx.compose.ui.webcanvas.dom.fontBoundingBoxDescentOf
import androidx.compose.ui.webcanvas.dom.getContext2D
import androidx.compose.ui.webcanvas.dom.setFontKerning
import androidx.compose.ui.webcanvas.dom.setLetterSpacing
import androidx.compose.ui.webcanvas.text.unicode.codePointAt
import androidx.compose.ui.webcanvas.text.unicode.codePointBefore
import kotlin.js.JsString
import kotlin.js.toJsString
import kotlin.math.round

/** The vertical metrics of a font at a size, in pixels (ascent negative, like Skia). */
internal class FontMetrics(
    /** The distance from the baseline to the top of the font box, negative. */
    val ascent: Float,
    /** The distance from the baseline to the bottom of the font box, positive. */
    val descent: Float,
    /** Half of the x-height, positive: where a line-through is drawn above the baseline. */
    val strikeoutOffset: Float,
)

/**
 * Measures text with the Canvas 2D API. The widths measured lately are cached, because the line
 * breaker measures every word of every paragraph and the same words appear again and again.
 */
internal object TextMeasurement {
    private val ctx: Context2D by lazy {
        val context = getContext2D(createCanvas(1, 1), willReadFrequently = false)
            ?: error("Canvas 2D is not supported")
        context.textAlign = JsStrings.left
        context.textBaseline = JsStrings.alphabetic
        setFontKerning(context, JsStrings.normal)
        context
    }

    private var currentFont: JsString? = null
    private var currentLetterSpacing: JsString? = null
    private var currentRtl = false

    /**
     * A run measured (see [jsRunOf]) with its [width]: identified by its font, its text,
     * `text[start, end)`, compared in place, its direction and whether it joins the letters around
     * it. Runs are looked up with a key reused for every lookup, and a copy of it with its own text
     * is kept.
     */
    private class MeasuredRun {
        private var font: RunFont? = null
        private var text = ""
        private var start = 0
        private var end = 0
        private var flags = 0
        private var hash = 0
        var width = 0f
            private set

        fun set(font: RunFont, text: String, start: Int, end: Int, flags: Int): MeasuredRun {
            this.font = font
            this.text = text
            this.start = start
            this.end = end
            this.flags = flags
            var hash = 31 * font.hashCode() + flags
            for (i in start until end) hash = 31 * hash + text[i].code
            this.hash = hash
            return this
        }

        /** A copy of this key with its own text, measured [width] wide. */
        fun measured(width: Float): MeasuredRun = MeasuredRun().also {
            it.font = font
            it.text = text.substring(start, end)
            it.end = end - start
            it.flags = flags
            it.hash = hash
            it.width = width
        }

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (other !is MeasuredRun || other.hash != hash || other.flags != flags) return false
            if (other.font !== font) return false
            val length = end - start
            if (other.end - other.start != length) return false
            for (i in 0 until length) {
                if (text[start + i] != other.text[other.start + i]) return false
            }
            return true
        }
    }

    /** The key of every lookup. */
    private val probe = MeasuredRun()

    /** The runs measured lately: 50 000 at most. */
    private val widths = RecentCache<MeasuredRun, MeasuredRun>(capacity = 25_000) { it }

    /** Counts the [clearCaches], which the metrics of fonts were measured since. */
    private var epoch = 0

    /** Drops all cached measurements, e.g. after a font finished loading. */
    fun clearCaches() {
        widths.clear()
        epoch++
    }

    private fun select(font: RunFont, rtl: Boolean) {
        if (currentFont !== font.css) {
            ctx.font = font.css
            currentFont = font.css
        }
        val spacing = font.letterSpacingCss
        if (spacing !== currentLetterSpacing) {
            setLetterSpacing(ctx, spacing ?: JsStrings.zeroPx)
            currentLetterSpacing = spacing
        }
        if (rtl != currentRtl) {
            ctx.direction = if (rtl) JsStrings.rtl else JsStrings.ltr
            currentRtl = rtl
        }
    }

    /** The advance width of [text] drawn with [font], as a single run of the given direction. */
    fun width(font: RunFont, text: String, rtl: Boolean = false): Float =
        width(font, text, jsText = null, 0, text.length, rtl, joinsBefore = false, joinsAfter = false)

    /**
     * The width of [text] between [start] and [end], as laid out within the text (see [jsRunOf]):
     * letters of cursive scripts keep the forms joining them to the letters around the range where
     * it [joinsBefore] or [joinsAfter] them. [jsText] is [text] as a JS string, if the caller has it.
     */
    fun width(
        font: RunFont,
        text: String,
        jsText: JsString?,
        start: Int,
        end: Int,
        rtl: Boolean,
        joinsBefore: Boolean,
        joinsAfter: Boolean,
    ): Float {
        if (start >= end) return 0f
        val flags = (if (rtl) 1 else 0) or (if (joinsBefore) 2 else 0) or (if (joinsAfter) 4 else 0)
        val key = probe.set(font, text, start, end, flags)
        widths[key]?.let { return it.width }
        select(font, rtl)
        val run = jsRunOf(jsText ?: text.toJsString(), start, end, rtl, joinsBefore, joinsAfter)
        val width = ctx.measureText(run).width.toFloat() * font.scaleX * font.pixelRatio
        widths.put(key.measured(width))
        return width
    }

    fun metrics(font: RunFont): FontMetrics {
        if (font.metricsEpoch == epoch) font.metrics?.let { return it }
        select(font, false)
        val m = ctx.measureText(MetricsSample)
        // In whole pixels at any ratio: lines of a paragraph meet between pixels, with their backgrounds.
        var ascent = round(fontBoundingBoxAscentOf(m).toFloat() * font.pixelRatio)
        var descent = round(fontBoundingBoxDescentOf(m).toFloat() * font.pixelRatio)
        if (ascent.isNaN() || descent.isNaN() || (ascent == 0f && descent == 0f)) {
            // Browsers without font bounding boxes (old Firefox): approximate from the size.
            ascent = font.size * 0.9f
            descent = font.size * 0.25f
        }
        val x = ctx.measureText(XSample)
        val xHeight = x.actualBoundingBoxAscent.toFloat().takeIf { it > 0f }?.times(font.pixelRatio)
            ?: (font.size * 0.5f)
        val metrics = FontMetrics(-ascent, descent, xHeight / 2)
        font.metrics = metrics
        font.metricsEpoch = epoch
        return metrics
    }

    private val MetricsSample = "Hg".toJsString()
    private val XSample = "x".toJsString()
}

/**
 * The run of [text] (a JS string) between [start] and [end] as the canvas measures and draws it,
 * sliced from [text] in JS:
 * - in a directional override: the paragraph already ran the bidi algorithm, and the browser must
 *   lay out each run exactly in the resolved direction, not reorder it again (which would ignore
 *   embeddings and overrides of the paragraph and misplace neutral characters);
 * - with a zero width joiner at each end where the text continues with a letter joined to it
 *   ([joinsBefore], [joinsAfter]): the Canvas 2D API only shapes the strings it's given, so a range
 *   of Arabic text measured or drawn alone would otherwise get the isolated or final forms of its
 *   end letters (with different widths and shapes than within the word).
 */
// language=js
internal fun jsRunOf(
    text: JsString,
    start: Int,
    end: Int,
    isRtl: Boolean,
    joinsBefore: Boolean,
    joinsAfter: Boolean,
): JsString = js(
    "(isRtl ? '\\u202E' : '\\u202D') + (joinsBefore ? '\\u200D' : '') + text.substring(start, end) + (joinsAfter ? '\\u200D' : '') + '\\u202C'"
)

/** [text] in a directional override, as [jsRunOf] makes runs, for text that isn't in a paragraph. */
internal fun directionalRun(text: String, isRtl: Boolean): JsString =
    jsRunOf(text.toJsString(), 0, text.length, isRtl, joinsBefore = false, joinsAfter = false)

/** Whether the letter of [text] at [start] is joined to the letter before it. */
internal fun joinsBefore(text: CharSequence, start: Int): Boolean =
    start > 0 && start < text.length &&
        isJoiningScript(codePointBefore(text, start)) && isJoiningScript(codePointAt(text, start))

/** Whether the letter of [text] before [end] is joined to the letter at it. */
internal fun joinsAfter(text: CharSequence, end: Int): Boolean =
    end in 1 until text.length &&
        isJoiningScript(codePointBefore(text, end)) && isJoiningScript(codePointAt(text, end))

/** Whether [codePoint] belongs to a cursive script, whose letters join their neighbors. */
private fun isJoiningScript(codePoint: Int): Boolean = when {
    codePoint < 0x0600 -> false
    codePoint <= 0x08FF -> true // Arabic, Syriac, Thaana, N'Ko, Samaritan, Mandaic, Arabic Ext.
    codePoint in 0x1800..0x18AF -> true // Mongolian
    codePoint in 0xA840..0xA87F -> true // Phags-pa
    codePoint in 0xFB50..0xFDFF -> true // Arabic Presentation Forms-A
    codePoint in 0xFE70..0xFEFF -> true // Arabic Presentation Forms-B
    codePoint in 0x10AC0..0x10AFF -> true // Manichaean
    codePoint in 0x10B80..0x10BAF -> true // Psalter Pahlavi
    codePoint in 0x10D00..0x10D3F -> true // Hanifi Rohingya
    codePoint in 0x10F30..0x10FDF -> true // Sogdian, Old Uyghur, Chorasmian
    codePoint in 0x1E900..0x1E95F -> true // Adlam
    else -> false
}
