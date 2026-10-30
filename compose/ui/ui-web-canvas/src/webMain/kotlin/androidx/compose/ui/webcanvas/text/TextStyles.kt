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

@file:OptIn(InternalComposeUiApi::class, ExperimentalTextApi::class)

package androidx.compose.ui.webcanvas.text

import androidx.compose.runtime.State
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextDecorationLineStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.platformInterceptedFontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import kotlin.js.JsString
import kotlin.js.toJsString

/**
 * A font as the canvas uses it: a typeface at a size, with the requested (possibly synthesized)
 * weight and style, letter spacing and horizontal scale. The fonts used lately are shared, so that
 * their measurements and JS strings are made once (see [TextMeasurement]).
 *
 * The browser gets the font at its size in CSS pixels, [pixelRatio] times smaller than [size], as
 * for DOM text: a font may change with its size (the system font of macOS does), so the same text
 * at twice the size isn't the text scaled twice. It's measured and drawn scaled by [pixelRatio].
 */
internal class RunFont private constructor(
    val typeface: WebTypeface,
    val size: Float,
    val weight: Int,
    val isItalic: Boolean,
    val letterSpacing: Float,
    val scaleX: Float,
    val pixelRatio: Float,
    val cssString: String,
    /** Identifies the font among the shared ones. */
    val key: String,
) {
    val css: JsString = cssString.toJsString()

    val letterSpacingCss: JsString? =
        if (letterSpacing != 0f) "${cssNumber(letterSpacing / pixelRatio)}px".toJsString() else null

    /** The metrics measured by [TextMeasurement.metrics], and the epoch they were measured in. */
    var metrics: FontMetrics? = null
    var metricsEpoch = 0

    companion object {
        /** The fonts used lately: animated sizes don't pile up. */
        private val instances = RecentCache<String, RunFont>(capacity = 256) { it.key }

        fun of(
            typeface: WebTypeface,
            size: Float,
            weight: Int,
            isItalic: Boolean,
            letterSpacing: Float,
            scaleX: Float,
            pixelRatio: Float,
        ): RunFont {
            val css = buildString {
                if (isItalic) append("italic ")
                append(weight.coerceIn(1, 1000))
                append(' ')
                append(cssNumber(size / pixelRatio))
                append("px ")
                append(typeface.family)
                if (!typeface.isGeneric) {
                    // Specific fonts fall back to the default one for missing characters; past
                    // it, the browser falls back to the system fonts (emoji, CJK, ...).
                    append(", ")
                    append(GenericFamilies.SansSerif)
                }
            }
            val key = "$css|$letterSpacing|$scaleX|$pixelRatio"
            return instances[key]
                ?: RunFont(typeface, size, weight, isItalic, letterSpacing, scaleX, pixelRatio, css, key)
                    .also { instances.put(it) }
        }
    }
}

/** Formats a number for CSS, which doesn't accept exponents. */
internal fun cssNumber(value: Float): String {
    if (value.isNaN() || value == 0f) return "0"
    val rounded = kotlin.math.round(value * 1000f) / 1000f
    if (kotlin.math.abs(rounded) < 0.001f) return "0"
    val asInt = rounded.toInt()
    return if (asInt.toFloat() == rounded) asInt.toString() else rounded.toString()
}

/** The text foreground of a run: a color, or a brush with an alpha. */
internal class TextForeground(val color: Color, val brush: Brush?, val alpha: Float) {
    fun merge(other: TextForeground): TextForeground = when {
        other.brush is ShaderBrush && brush is ShaderBrush ->
            TextForeground(other.color, other.brush, if (other.alpha.isNaN()) alpha else other.alpha)
        other.brush is ShaderBrush -> other
        brush is ShaderBrush -> this
        else -> if (other !== Unspecified) other else this
    }

    companion object {
        val Unspecified = TextForeground(Color.Unspecified, null, Float.NaN)

        fun of(style: SpanStyle): TextForeground = when (val brush = style.brush) {
            null -> if (style.color.isSpecified) {
                TextForeground(style.color, null, style.color.alpha)
            } else {
                Unspecified
            }
            is SolidColor -> {
                val alpha = style.alpha
                val color = if (alpha.isNaN() || alpha >= 1f) {
                    brush.value
                } else {
                    brush.value.copy(alpha = brush.value.alpha * alpha)
                }
                TextForeground(color, null, brush.value.alpha)
            }
            else -> TextForeground(Color.Unspecified, brush, style.alpha)
        }
    }
}

/** The resolved style of a run of text. Font sizes and spacings are in pixels. */
internal class RunStyle(
    val font: RunFont,
    val foreground: TextForeground,
    val background: Color,
    val decoration: TextDecoration?,
    val decorationLineStyle: TextDecorationLineStyle?,
    val shadow: Shadow?,
    val drawStyle: DrawStyle?,
    val baselineShift: BaselineShift?,
    val skewX: Float,
    /** The line height in pixels, or `null` to use the font's height. */
    val lineHeight: Float?,
    /** See [LineHeightStyle.Alignment.topRatio]: -1 scales the font's ascent and descent. */
    val topRatio: Float,
    val localeList: LocaleList?,
    val fontFeatureSettings: String?,
) {
    val fontSize: Float get() = font.size
}

/** A range of text drawn with one [RunStyle]. */
internal class StyleRun(val start: Int, val end: Int, val style: RunStyle)

/** A placeholder for inline content, replacing the text between [start] and [end]. */
internal class PlaceholderRun(
    val start: Int,
    val end: Int,
    val width: Float,
    val height: Float,
    val verticalAlign: PlaceholderVerticalAlign,
    /** The style of the text around the placeholder, for the vertical alignment. */
    val style: RunStyle,
)

/** The style runs of a paragraph, together with its placeholders. */
internal class StyledText(
    val runs: List<StyleRun>,
    val placeholders: List<PlaceholderRun>,
    val defaultStyle: RunStyle,
) {
    /** The run containing [offset], or the last run. */
    fun runAt(offset: Int): StyleRun {
        var low = 0
        var high = runs.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (runs[mid].start <= offset) low = mid else high = mid - 1
        }
        return runs[low]
    }
}

private val DefaultFontSize = 16.sp

/**
 * Resolves the styles of a paragraph: the base [textStyle] with the [annotations] on top, later
 * annotations winning over earlier ones where they overlap.
 */
internal class StyleResolver(
    private val density: Density,
    private val fontFamilyResolver: FontFamily.Resolver,
    private val onFontResolved: (State<Any>) -> Unit,
) {
    fun resolve(
        text: String,
        textStyle: TextStyle,
        annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
        placeholders: List<AnnotatedString.Range<Placeholder>>,
        drawStyle: DrawStyle? = null,
    ): StyledText {
        val base = textStyle.toSpanStyle().withDefaultFontSize(drawStyle)
        val lineHeight = textStyle.lineHeight
        val topRatio = (textStyle.lineHeightStyle?.alignment ?: LineHeightStyle.Alignment.Proportional).topRatio
        val spanStyles = annotations.mapNotNull { range ->
            (range.item as? SpanStyle)?.let { Triple(range.start, range.end, it) }
        }.filter { it.first < it.second }

        // Every position where the set of active styles changes.
        val boundaries = HashSet<Int>()
        boundaries.add(0)
        boundaries.add(text.length)
        for ((start, end, _) in spanStyles) {
            boundaries.add(start.coerceIn(0, text.length))
            boundaries.add(end.coerceIn(0, text.length))
        }
        val positions = boundaries.sorted()

        val defaultStyle = computeStyle(base, emptyList(), lineHeight, topRatio)
        val runs = ArrayList<StyleRun>(positions.size)
        val styleCache = HashMap<List<SpanStyle>, RunStyle>()
        for (i in 0 until positions.size - 1) {
            val start = positions[i]
            val end = positions[i + 1]
            if (start >= end) continue
            val active = spanStyles.filter { it.first <= start && it.second >= end }.map { it.third }
            val style = styleCache.getOrPut(active) { computeStyle(base, active, lineHeight, topRatio) }
            val previous = runs.lastOrNull()
            if (previous != null && previous.style === style) {
                runs[runs.lastIndex] = StyleRun(previous.start, end, style)
            } else {
                runs.add(StyleRun(start, end, style))
            }
        }
        if (runs.isEmpty()) {
            runs.add(StyleRun(0, text.length, defaultStyle))
        }

        val placeholderRuns = placeholders
            .filter { it.start < it.end && it.start >= 0 && it.end <= text.length }
            .sortedBy { it.start }
            .map { range ->
                val style = runs.firstOrNull { it.start <= range.start && range.start < it.end }?.style
                    ?: defaultStyle
                PlaceholderRun(
                    start = range.start,
                    end = range.end,
                    width = range.item.width.toPx(style.fontSize),
                    height = range.item.height.toPx(style.fontSize),
                    verticalAlign = range.item.placeholderVerticalAlign,
                    style = style,
                )
            }
        return StyledText(runs, placeholderRuns, defaultStyle)
    }

    private fun computeStyle(
        base: SpanStyle,
        active: List<SpanStyle>,
        lineHeight: TextUnit,
        topRatio: Float,
    ): RunStyle {
        var fontSize = base.fontSize.toPx(16f)
        var foreground = TextForeground.of(base)
        var fontFamily = base.fontFamily
        var fontWeight = base.fontWeight
        var fontStyle = base.fontStyle
        var fontSynthesis = base.fontSynthesis
        var fontFeatureSettings = base.fontFeatureSettings
        var letterSpacing = if (base.letterSpacing.isSpecified) base.letterSpacing.toPx(fontSize) else 0f
        var baselineShift = base.baselineShift
        var geometricTransform = base.textGeometricTransform
        var localeList = base.localeList
        var background = base.background
        var decoration = base.textDecoration
        var decorationLineStyle = base.platformStyle?.textDecorationLineStyle
        var shadow = base.shadow
        var drawStyle = base.drawStyle

        for (style in active) {
            fontSize = style.fontSize.toPx(fontSize)
            foreground = foreground.merge(TextForeground.of(style))
            style.fontFamily?.let { fontFamily = it }
            style.fontWeight?.let { fontWeight = it }
            style.fontStyle?.let { fontStyle = it }
            style.fontSynthesis?.let { fontSynthesis = it }
            style.fontFeatureSettings?.let { fontFeatureSettings = it }
            if (style.letterSpacing.isSpecified) letterSpacing = style.letterSpacing.toPx(fontSize)
            style.baselineShift?.let { baselineShift = it }
            style.textGeometricTransform?.let { geometricTransform = it }
            style.localeList?.let { localeList = it }
            if (style.background.isSpecified) background = style.background
            style.textDecoration?.let { decoration = it }
            style.shadow?.let { shadow = it }
            style.drawStyle?.let { drawStyle = it }
            style.platformStyle?.textDecorationLineStyle?.let { decorationLineStyle = it }
        }

        val weight = fontWeight ?: FontWeight.Normal
        val italic = (fontStyle ?: FontStyle.Normal) == FontStyle.Italic
        val synthesis = fontSynthesis ?: FontSynthesis.All
        val resolved = fontFamilyResolver.resolve(
            fontFamily,
            weight,
            fontStyle ?: FontStyle.Normal,
            synthesis,
        )
        onFontResolved(resolved)
        val typeface = resolved.value as? WebTypeface
            ?: WebTypeface(GenericFamilies.of(fontFamily), weight.weight, italic, isRegistered = false, isGeneric = true)
        val requestedWeight = platformInterceptedFontWeight(weight).weight
        // Registered faces are declared with their own weight and style, so the browser
        // synthesizes bold and italic when a different one is requested.
        val canSynthesizeWeight = synthesis == FontSynthesis.All || synthesis == FontSynthesis.Weight
        val canSynthesizeStyle = synthesis == FontSynthesis.All || synthesis == FontSynthesis.Style
        val cssWeight = if (typeface.isRegistered && !canSynthesizeWeight) typeface.weight else requestedWeight
        val cssItalic = if (typeface.isRegistered && !canSynthesizeStyle) typeface.isItalic else italic

        val scaleX = geometricTransform?.scaleX ?: 1f
        val font = RunFont.of(
            typeface = typeface,
            size = fontSize,
            weight = cssWeight,
            isItalic = cssItalic,
            letterSpacing = letterSpacing,
            scaleX = if (scaleX > 0f) scaleX else 1f,
            pixelRatio = density.density,
        )
        val lineHeightPx = if (lineHeight.isSpecified) {
            if (lineHeight.isEm) {
                lineHeight.value * fontSize
            } else {
                with(density) { lineHeight.toPx() }
            }
        } else {
            null
        }
        return RunStyle(
            font = font,
            foreground = foreground,
            background = background,
            decoration = decoration,
            decorationLineStyle = decorationLineStyle,
            shadow = shadow,
            drawStyle = drawStyle,
            baselineShift = baselineShift,
            skewX = geometricTransform?.skewX ?: 0f,
            lineHeight = lineHeightPx,
            topRatio = topRatio,
            localeList = localeList,
            fontFeatureSettings = fontFeatureSettings,
        )
    }

    private fun TextUnit.toPx(fontSize: Float): Float = when {
        isUnspecified -> fontSize
        isEm -> fontSize * value
        else -> with(density) { toPx() }
    }

    private fun SpanStyle.withDefaultFontSize(drawStyle: DrawStyle?): SpanStyle {
        val fontSize = when {
            fontSize.isUnspecified -> DefaultFontSize
            fontSize.isEm -> DefaultFontSize * fontSize.value
            else -> fontSize
        }
        return copy(fontSize = fontSize, drawStyle = drawStyle ?: this.drawStyle)
    }
}
