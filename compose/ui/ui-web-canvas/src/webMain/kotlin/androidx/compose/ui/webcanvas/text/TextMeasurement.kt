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

@file:OptIn(ExperimentalWasmJsInterop::class)

package androidx.compose.ui.webcanvas.text

import androidx.compose.ui.webcanvas.dom.CanvasFeatures
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.JsStrings
import androidx.compose.ui.webcanvas.dom.createCanvas
import androidx.compose.ui.webcanvas.dom.getContext2D
import androidx.compose.ui.webcanvas.dom.setFontKerning
import androidx.compose.ui.webcanvas.dom.setLetterSpacing
import androidx.compose.ui.webcanvas.text.unicode.codePointAt
import androidx.compose.ui.webcanvas.text.unicode.codePointBefore
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString
import kotlin.js.toJsString

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
 * Measures text with the Canvas 2D API. Widths are cached per font, because the line breaker
 * measures every word of every paragraph and the same words appear again and again.
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

    private class FontCache {
        val widths = HashMap<String, Float>()
        var metrics: FontMetrics? = null
    }

    private val caches = HashMap<String, FontCache>()
    private var cachedEntries = 0
    private const val MaxCachedEntries = 50_000

    /** Drops all cached measurements, e.g. after a font finished loading. */
    fun clearCaches() {
        caches.clear()
        cachedEntries = 0
    }

    private fun cacheFor(font: RunFont): FontCache {
        val key = font.key
        return caches.getOrPut(key) { FontCache() }
    }

    private fun select(font: RunFont, rtl: Boolean) {
        if (currentFont !== font.css) {
            ctx.font = font.css
            currentFont = font.css
        }
        val spacing = font.letterSpacingCss
        if (spacing !== currentLetterSpacing) {
            if (CanvasFeatures.letterSpacing) {
                setLetterSpacing(ctx, spacing ?: ZeroPx)
            }
            currentLetterSpacing = spacing
        }
        if (rtl != currentRtl) {
            ctx.direction = if (rtl) JsStrings.rtl else JsStrings.ltr
            currentRtl = rtl
        }
    }

    /** The advance width of [text] drawn with [font], as a single run of the given direction. */
    fun width(font: RunFont, text: String, rtl: Boolean = false): Float {
        if (text.isEmpty()) return 0f
        val run = directionalRun(text, rtl)
        val cache = cacheFor(font)
        cache.widths[run]?.let { return it }
        select(font, rtl)
        var width = ctx.measureText(run.toJsString()).width.toFloat()
        if (font.letterSpacing != 0f && !CanvasFeatures.letterSpacing) {
            // Emulated letter spacing: added after every character, like the CSS property.
            width += font.letterSpacing * text.codePointCountForSpacing()
        }
        width *= font.scaleX
        if (cachedEntries > MaxCachedEntries) clearCaches()
        cacheFor(font).widths[run] = width
        cachedEntries++
        return width
    }

    /**
     * The width of [text] between [start] and [end], as laid out within the text: letters of
     * cursive scripts keep the forms joining them to the letters around the range.
     */
    fun width(font: RunFont, text: String, start: Int, end: Int, rtl: Boolean = false): Float =
        if (start >= end) 0f else width(font, contextualSubstring(text, start, end), rtl)

    fun metrics(font: RunFont): FontMetrics {
        val cache = cacheFor(font)
        cache.metrics?.let { return it }
        select(font, false)
        val m = ctx.measureText(MetricsSample)
        var ascent = m.fontBoundingBoxAscent.toFloat()
        var descent = m.fontBoundingBoxDescent.toFloat()
        if (ascent.isNaN() || descent.isNaN() || (ascent == 0f && descent == 0f)) {
            // Browsers without font bounding boxes (old Firefox): approximate from the size.
            ascent = font.size * 0.9f
            descent = font.size * 0.25f
        }
        val x = ctx.measureText(XSample)
        val xHeight = x.actualBoundingBoxAscent.toFloat().takeIf { it > 0f } ?: (font.size * 0.5f)
        val metrics = FontMetrics(-ascent, descent, xHeight / 2)
        cache.metrics = metrics
        return metrics
    }

    private val MetricsSample = "Hg".toJsString()
    private val XSample = "x".toJsString()
    private val ZeroPx = "0px".toJsString()
}

/**
 * [text] wrapped in a directional override: the paragraph already ran the bidi algorithm, and
 * the browser must lay out each run exactly in the resolved direction, not reorder it again (which
 * would ignore embeddings and overrides of the paragraph and misplace neutral characters).
 */
internal fun directionalRun(text: String, isRtl: Boolean): String =
    (if (isRtl) RightToLeftOverride else LeftToRightOverride) + text + PopDirectionalFormatting

/**
 * `text[start, end)`, with a zero width joiner at each end where the text continues with a letter
 * joined to it. The Canvas 2D API only shapes the strings it's given, so a range of Arabic text
 * measured or drawn alone would otherwise get the isolated or final forms of its end letters
 * (with different widths and shapes than within the word).
 */
internal fun contextualSubstring(text: String, start: Int, end: Int): String {
    val substring = text.substring(start, end)
    if (start >= end) return substring
    val joinsBefore = start > 0 &&
        isJoiningScript(codePointBefore(text, start)) && isJoiningScript(codePointAt(text, start))
    val joinsAfter = end < text.length &&
        isJoiningScript(codePointBefore(text, end)) && isJoiningScript(codePointAt(text, end))
    if (!joinsBefore && !joinsAfter) return substring
    return (if (joinsBefore) ZeroWidthJoiner else "") + substring +
        (if (joinsAfter) ZeroWidthJoiner else "")
}

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

private const val ZeroWidthJoiner = "\u200D"

private const val LeftToRightOverride = "\u202D"
private const val RightToLeftOverride = "\u202E"
private const val PopDirectionalFormatting = "\u202C"

/** The number of characters CSS letter spacing applies to: one per code point. */
private fun String.codePointCountForSpacing(): Int {
    var count = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        i += if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}
