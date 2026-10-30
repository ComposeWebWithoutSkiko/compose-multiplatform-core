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
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Bullet
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlatformParagraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createPlatformFontFamilyResolver
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.intl.isRtl
import androidx.compose.ui.text.platform.PlatformText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil

/** The text implementation of the web backend. */
internal object WebCanvasText : PlatformText {
    override fun createParagraph(
        text: String,
        style: TextStyle,
        annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
        placeholders: List<AnnotatedString.Range<Placeholder>>,
        maxLines: Int,
        overflow: TextOverflow,
        constraints: Constraints,
        density: Density,
        fontFamilyResolver: FontFamily.Resolver,
        defaultLocaleList: LocaleList,
    ): PlatformParagraph = WebParagraph(
        WebParagraphIntrinsics(
            text, style, annotations, placeholders, density, fontFamilyResolver, defaultLocaleList,
        ),
        maxLines,
        overflow,
        constraints,
    )

    override fun createParagraph(
        paragraphIntrinsics: ParagraphIntrinsics,
        maxLines: Int,
        overflow: TextOverflow,
        constraints: Constraints,
    ): PlatformParagraph = WebParagraph(
        paragraphIntrinsics as WebParagraphIntrinsics,
        maxLines,
        overflow,
        constraints,
    )

    override fun createParagraphIntrinsics(
        text: String,
        style: TextStyle,
        annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
        placeholders: List<AnnotatedString.Range<Placeholder>>,
        density: Density,
        fontFamilyResolver: FontFamily.Resolver,
        defaultLocaleList: LocaleList,
    ): ParagraphIntrinsics = WebParagraphIntrinsics(
        text, style, annotations, placeholders, density, fontFamilyResolver, defaultLocaleList,
    )

    override fun createFontFamilyResolver(): FontFamily.Resolver =
        createPlatformFontFamilyResolver(WebFontLoader())

    override fun createFontFamilyResolver(coroutineContext: CoroutineContext): FontFamily.Resolver =
        createPlatformFontFamilyResolver(WebFontLoader(), coroutineContext)

    override fun findPrecedingBreak(text: String, index: Int): Int =
        if (index <= 0) -1 else TextUnicode.previousGraphemeBoundary(text, index)

    override fun findFollowingBreak(text: String, index: Int): Int =
        if (index >= text.length) -1 else TextUnicode.nextGraphemeBoundary(text, index)

    // The browser rasterizes the text; these only describe what it does.
    override val defaultFontRasterizationSettings: FontRasterizationSettings =
        FontRasterizationSettings(
            subpixelPositioning = true,
            smoothing = FontSmoothing.AntiAlias,
            hinting = FontHinting.Slight,
            autoHintingForced = false,
        )
}

/** The [ParagraphIntrinsics] of the web backend: the width-independent analysis of the text. */
internal class WebParagraphIntrinsics(
    val text: String,
    val style: TextStyle,
    private val annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    private val placeholders: List<AnnotatedString.Range<Placeholder>>,
    val density: Density,
    private val fontFamilyResolver: FontFamily.Resolver,
    /** The locales of the text when [style] doesn't have any. */
    defaultLocaleList: LocaleList,
) : ParagraphIntrinsics {
    private class ResolvedFont(val state: State<Any>, val initial: Any)

    private val resolvedFonts = ArrayList<ResolvedFont>()

    // Reading the snapshot state here makes the text that is measured with a fallback font,
    // while a font loads, observe when it finishes.
    private val fontsVersion = FontFaces.version

    private val styleResolver = StyleResolver(density, fontFamilyResolver) { state ->
        if (resolvedFonts.none { it.state === state }) {
            resolvedFonts.add(ResolvedFont(state, state.value))
        }
    }

    private val styled = styleResolver.resolve(text, style, annotations, placeholders)

    private val layoutText = layoutTextOf(text, styled.placeholders)

    val textDirection: ResolvedTextDirection =
        resolveTextDirection(layoutText, style.textDirection, style.localeList ?: defaultLocaleList)

    val analysis = ParagraphAnalysis(
        text = text,
        layoutText = layoutText,
        styled = styled,
        paragraphLevel = if (textDirection == ResolvedTextDirection.Rtl) 1 else 0,
    )

    /** The default font size in pixels, which em units are relative to. */
    val fontSize: Float get() = analysis.styled.defaultStyle.fontSize

    /** The indentation of the first line in pixels. */
    val firstLineIndent: Float = style.textIndent?.firstLine?.let { indentToPx(it) } ?: 0f

    /** The indentation of the other lines in pixels. */
    val restLinesIndent: Float = style.textIndent?.restLine?.let { indentToPx(it) } ?: 0f

    // The widths of the text, plus the indentation, so that the text laid out at these widths
    // doesn't break a line early.
    override val minIntrinsicWidth: Float = ceil(analysis.minIntrinsicWidth() + firstLineIndent)

    override val maxIntrinsicWidth: Float = ceil(analysis.maxIntrinsicWidth() + firstLineIndent)

    /** The bullets drawn in the leading margin of the paragraph. */
    val bullets: List<AnnotatedString.Range<Bullet>> = bulletsOf(annotations)

    override val hasStaleResolvedFonts: Boolean
        get() = FontFaces.version != fontsVersion ||
            resolvedFonts.any { it.state.value !== it.initial }

    private fun indentToPx(value: TextUnit): Float = when {
        value.isEm -> value.value * fontSize
        value.isSp -> with(density) { value.toPx() }
        else -> 0f
    }

    /** Resolves the styles again with a different base style, for painting. */
    fun resolveStyles(style: TextStyle, drawStyle: DrawStyle?) =
        styleResolver.resolve(text, style, annotations, placeholders, drawStyle)
}

/** The direction of a paragraph of [layoutText] (see [layoutTextOf]) with the [textDirection]. */
internal fun resolveTextDirection(
    layoutText: CharSequence,
    textDirection: TextDirection?,
    localeList: LocaleList?,
): ResolvedTextDirection {
    fun contentBased(fallback: () -> ResolvedTextDirection) =
        when (TextUnicode.firstStrongDirection(layoutText)) {
            TextUnicode.DirectionLtr -> ResolvedTextDirection.Ltr
            TextUnicode.DirectionRtl -> ResolvedTextDirection.Rtl
            else -> fallback()
        }

    fun localeBased(): ResolvedTextDirection =
        if ((localeList?.firstOrNull() ?: Locale.current).isRtl()) {
            ResolvedTextDirection.Rtl
        } else {
            ResolvedTextDirection.Ltr
        }

    return when (textDirection ?: TextDirection.Content) {
        TextDirection.Ltr -> ResolvedTextDirection.Ltr
        TextDirection.Rtl -> ResolvedTextDirection.Rtl
        TextDirection.ContentOrLtr -> contentBased { ResolvedTextDirection.Ltr }
        TextDirection.ContentOrRtl -> contentBased { ResolvedTextDirection.Rtl }
        else -> contentBased(::localeBased)
    }
}
