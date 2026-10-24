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

@file:OptIn(InternalComposeUiApi::class, ExperimentalWasmJsInterop::class, ExperimentalTextApi::class)

package androidx.compose.ui.webcanvas.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontResourceLoaderWithResolver
import androidx.compose.ui.text.font.GenericFontFamily
import androidx.compose.ui.text.font.LoadedFontFamily
import androidx.compose.ui.text.font.PlatformTypefacesLoader
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.font.createPlatformFontFamilyResolver
import androidx.compose.ui.text.platform.LoadedFont
import androidx.compose.ui.text.platform.PlatformFont
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.webcanvas.dom.toUint8Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

/**
 * A typeface of the web backend: the CSS family the browser knows the font by, together with the
 * weight and style of the face.
 */
internal class WebTypeface(
    /** The CSS `font-family` value of the face itself, without fallbacks. */
    val family: String,
    /** The weight of the face (`font-weight`, 1..1000). */
    val weight: Int,
    val isItalic: Boolean,
    /** Whether the face is a font file registered with [FontFaces] (rather than a system font). */
    val isRegistered: Boolean,
    /** Whether [family] is a generic family stack, which ends with the browser's fallback. */
    val isGeneric: Boolean = false,
) : Typeface {
    override val fontFamily: FontFamily? get() = null

    override fun equals(other: Any?): Boolean =
        other is WebTypeface && other.family == family && other.weight == weight &&
            other.isItalic == isItalic

    override fun hashCode(): Int = (family.hashCode() * 31 + weight) * 31 + isItalic.hashCode()

    override fun toString(): String = "WebTypeface($family, $weight, italic=$isItalic)"
}

/**
 * The CSS font families of Compose's generic families. The default and sans-serif families use the
 * UI font of the operating system, like the desktop targets do.
 */
internal object GenericFamilies {
    const val SansSerif =
        "system-ui, -apple-system, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif"
    const val Serif = "serif"
    const val Monospace = "ui-monospace, Menlo, Consolas, \"Roboto Mono\", monospace"
    const val Cursive = "cursive"

    fun of(family: FontFamily?): String = when {
        family == null || family == FontFamily.Default -> SansSerif
        family is GenericFontFamily -> when (family.name) {
            FontFamily.Serif.name -> Serif
            FontFamily.Monospace.name -> Monospace
            FontFamily.Cursive.name -> Cursive
            else -> SansSerif
        }
        else -> SansSerif
    }
}

/**
 * Registers font files with the browser (`document.fonts`), each under a unique family name, and
 * tracks when they finish loading.
 *
 * A registered font is only used by the text that asks for it: unlike with Skia, there is no need
 * to register fonts as fallbacks for characters missing in the other fonts, as the browser falls
 * back to the system fonts (including emoji and CJK ones).
 */
internal object FontFaces {
    private val families = HashMap<String, String>()

    /**
     * Changes whenever a font finishes loading after it was registered, so that text laid out with
     * a fallback font in the meantime is laid out again. It's snapshot state: text observes it.
     */
    var version: Int by mutableIntStateOf(0)
        private set

    fun register(font: LoadedFont, weight: Int, isItalic: Boolean): String {
        val key = font.cacheKey
        families[key]?.let { return it }
        val family = "cmp-font-${families.size}"
        val axes = FontAxes.of(font)
        // Each face is registered under its own family, with its variation fixed through the
        // descriptors: the browser clamps the variation axes of a variable font to the ranges the
        // descriptors declare (weight -> wght, stretch -> wdth, oblique angle -> slnt), and applies
        // `variationSettings` where it's supported.
        val style = when {
            isItalic || (axes.italic ?: 0f) >= 0.5f -> "italic"
            axes.slant != null && axes.slant != 0f -> "oblique ${cssNumber(-axes.slant)}deg"
            else -> "normal"
        }
        val loaded = addFontFace(
            family,
            font.data.toUint8Array(),
            weight.toString(),
            style,
            axes.width?.let { "${cssNumber(it)}%" } ?: "normal",
            axes.cssVariationSettings,
            onLoaded = { onFontLoaded() },
        )
        families[key] = family
        if (!loaded) {
            // The face loads asynchronously: anything measured until then used a fallback font.
            TextMeasurement.clearCaches()
        }
        return family
    }

    private fun onFontLoaded() {
        TextMeasurement.clearCaches()
        version++
    }
}

/**
 * Creates a `FontFace` from font file bytes and adds it to `document.fonts`. Returns whether the
 * face is already usable; otherwise [onLoaded] is called once it is.
 */
// language=js
private fun addFontFace(
    family: String,
    data: JsAny,
    weight: String,
    style: String,
    stretch: String,
    variationSettings: String,
    onLoaded: () -> Unit,
): Boolean = js(
    """{
        const descriptors = { weight: weight, style: style, stretch: stretch };
        if (variationSettings) descriptors.variationSettings = variationSettings;
        const face = new FontFace(family, data, descriptors);
        document.fonts.add(face);
        if (face.status === 'loaded') return true;
        face.load().then(() => onLoaded(), (e) => console.warn('Failed to load font ' + family, e));
        return false;
    }"""
)

/** The variation axes of a font that the browser can apply through `FontFace` descriptors. */
private class FontAxes(
    val width: Float?,
    val slant: Float?,
    val italic: Float?,
    /** All the axes, as a CSS `font-variation-settings` value (empty if there is none). */
    val cssVariationSettings: String,
) {
    companion object {
        fun of(font: PlatformFont): FontAxes {
            var width: Float? = null
            var slant: Float? = null
            var italic: Float? = null
            val css = StringBuilder()
            for (setting in font.variationSettings.settings) {
                // Settings depending on the density (e.g. an optical size in sp) can't be
                // resolved here; the browser derives the optical size from the font size anyway.
                if (setting.needsDensity) continue
                val value = setting.toVariationValue(null)
                when (setting.axisName) {
                    "wdth" -> width = value
                    "slnt" -> slant = value
                    "ital" -> italic = value
                }
                if (css.isNotEmpty()) css.append(", ")
                css.append('"').append(setting.axisName).append("\" ").append(cssNumber(value))
            }
            return FontAxes(width, slant, italic, css.toString())
        }
    }
}

/** The [PlatformTypefacesLoader] of the web backend. */
internal class WebFontLoader : PlatformTypefacesLoader {
    override val fontCollection: Any get() = FontFaces

    override val cacheKey: Any? get() = null

    override fun loadBlocking(font: Font): Any? {
        if (font !is PlatformFont) {
            if (font.loadingStrategy != FontLoadingStrategy.OptionalLocal) {
                throw IllegalArgumentException("Unsupported font type: $font")
            }
            return null
        }
        return when (font.loadingStrategy) {
            FontLoadingStrategy.OptionalLocal -> runCatching { load(font) }.getOrNull()
            else -> load(font)
        }
    }

    override suspend fun awaitLoad(font: Font): Any? = loadBlocking(font)

    override fun loadPlatformTypes(
        fontFamily: FontFamily,
        fontWeight: FontWeight,
        fontStyle: FontStyle,
    ): Any = when (fontFamily) {
        is LoadedFontFamily -> (fontFamily.typeface as? WebTypeface)
            ?: WebTypeface(
                GenericFamilies.SansSerif,
                fontWeight.weight,
                fontStyle == FontStyle.Italic,
                isRegistered = false,
                isGeneric = true,
            )
        is FontListFontFamily -> {
            // Only system fonts can be resolved this way.
            val names = fontFamily.fonts.mapNotNull { (it as? SystemFont)?.identity }
            if (names.isEmpty()) {
                WebTypeface(
                    GenericFamilies.SansSerif,
                    fontWeight.weight,
                    fontStyle == FontStyle.Italic,
                    isRegistered = false,
                    isGeneric = true,
                )
            } else {
                WebTypeface(
                    names.joinToString { quoteFamily(it) },
                    fontWeight.weight,
                    fontStyle == FontStyle.Italic,
                    isRegistered = false,
                )
            }
        }
        else -> WebTypeface(
            GenericFamilies.of(fontFamily),
            fontWeight.weight,
            fontStyle == FontStyle.Italic,
            isRegistered = false,
            isGeneric = true,
        )
    }

    private fun load(font: PlatformFont): WebTypeface {
        val variationWeight = font.variationSettings.settings
            .firstOrNull { it.axisName == "wght" }
            ?.toVariationValue(null)
            ?.toInt()
        val weight = variationWeight ?: font.weight.weight
        val isItalic = font.style == FontStyle.Italic
        return when (font) {
            is LoadedFont -> WebTypeface(
                family = quoteFamily(FontFaces.register(font, weight, isItalic)),
                weight = weight,
                isItalic = isItalic,
                isRegistered = true,
            )
            is SystemFont -> WebTypeface(quoteFamily(font.identity), weight, isItalic, false)
        }
    }
}

private fun quoteFamily(name: String): String =
    "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** Creates the [FontFamily.Resolver] of the web backend. */
internal fun createWebFontFamilyResolver(): FontFamily.Resolver =
    createPlatformFontFamilyResolver(WebFontLoader())

/**
 * The deprecated `Font.ResourceLoader` of the web backend, provided through `LocalFontLoader`.
 */
@InternalComposeUiApi
@Suppress("DEPRECATION", "OverridingDeprecatedMember")
class WebFontResourceLoader : Font.ResourceLoader, FontResourceLoaderWithResolver {
    override val fontFamilyResolver: FontFamily.Resolver by lazy { createWebFontFamilyResolver() }

    @Deprecated(
        "Replaced by FontFamily.Resolver, this method should not be called",
        ReplaceWith("FontFamily.Resolver.resolve(font, )"),
    )
    override fun load(font: Font): Any = WebFontLoader().loadBlocking(font)
        ?: throw IllegalArgumentException("Unsupported font type: $font")
}
