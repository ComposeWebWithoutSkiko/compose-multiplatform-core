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

@file:OptIn(InternalComposeUiApi::class)

package androidx.compose.ui.webcanvas

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.webcanvas.graphics.WebImageBitmap
import androidx.compose.ui.webcanvas.text.WebTypeface
import androidx.compose.ui.webcanvas.text.createWebFontFamilyResolver
import androidx.compose.ui.webcanvas.text.loadFont
import kotlin.js.JsAny
import kotlin.js.JsReference
import kotlin.js.Promise
import kotlin.js.js
import kotlin.js.toJsReference
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Images and fonts that the browser holds, which reach Compose without their bytes. */
class BrowserResourcesTest {
    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    @Test
    fun aDecodedImageIsDrawnFromTheObjectItself(): Promise<JsAny?> = twoColorsImage().then { decoded ->
        val image = composeImageBitmapOf(decoded)
        assertEquals(4, image.width)
        assertEquals(4, image.height)
        assertTrue(isSameObject(decoded, (image as WebImageBitmap).drawableSource()!!))
        val pixels = image.toPixelMap()
        assertEquals(Color.Red, pixels[0, 1])
        assertEquals(Color.Blue, pixels[3, 1])
        null
    }

    @Test
    fun anSvgIsSharpAtTheSizeItIsDrawnAt(): Promise<JsAny?> = svgElementOf(SvgWithASize).then {
        val pixels = svgImageBitmapOf(it).drawnAt(64, 64)
        // Scaled up from 2 pixels, the middle would be a blend of both colors.
        assertEquals(Color.Red, pixels[31, 32])
        assertEquals(Color.Blue, pixels[32, 32])
        null
    }

    @Test
    fun anSvgFillsAnotherAspectRatio(): Promise<JsAny?> = svgElementOf(SvgWithASize).then {
        val pixels = svgImageBitmapOf(it).drawnAt(64, 16)
        assertEquals(Color.Red, pixels[0, 8])
        assertEquals(Color.Red, pixels[31, 8])
        assertEquals(Color.Blue, pixels[32, 8])
        assertEquals(Color.Blue, pixels[63, 8])
        null
    }

    @Test
    fun anSvgWithoutASizeFillsWhereItIsDrawn(): Promise<JsAny?> = svgElementOf(SvgOfAViewBox).then {
        val pixels = svgImageBitmapOf(it).drawnAt(64, 16)
        assertEquals(Color.Red, pixels[0, 8])
        assertEquals(Color.Red, pixels[31, 8])
        assertEquals(Color.Blue, pixels[32, 8])
        assertEquals(Color.Blue, pixels[63, 8])
        null
    }

    @Test
    fun anSvgThatTaintsCanvasesIsRefused(): Promise<JsAny?> = svgElementOf(SvgOfForeignContent).then {
        assertFailsWith<IllegalArgumentException> { svgImageBitmapOf(it) }
        null
    }

    @Test
    fun aFontInABlobIsAFaceOfTheDocumentOnce(): Promise<JsAny?> =
        loadFonts("Blob font", FontVariant(), FontVariant()).then { fonts ->
            val (first, second) = fonts.get()
            assertEquals(first, second)
            assertEquals(1, fontFaceCount(familyOf(first)))
            null
        }

    @Test
    fun aFontInABlobIsKeyedByAllItsProperties(): Promise<JsAny?> =
        loadFonts(
            "Keyed blob font",
            FontVariant(),
            FontVariant(weight = FontWeight.Bold),
            FontVariant(style = FontStyle.Italic),
            FontVariant(settings = FontVariation.Settings(FontVariation.Setting("wdth", 75f))),
        ).then { fonts ->
            assertEquals(4, fonts.get().map { familyOf(it) }.toSet().size)
            null
        }

    @Test
    fun aFontFileLoadedThroughAUrlKeepsDrawingAfterTheUrlIsRevoked(): Promise<JsAny?> =
        loadFonts("Real blob font", FontVariant(), blob = ::realFontBlob).then { fonts ->
            val family = familyOf(fonts.get().single())
            assertEquals("loaded", fontFaceStatus(family))
            assertNotEquals(textWidth("monospace"), textWidth("$family, monospace"))
            null
        }

    @Test
    fun aFontStillLoadingIsJoinedAndHandedOverOnlyOnceLoaded(): Promise<JsAny?> =
        Promise<JsReference<List<Pair<Font, String>>>> { resolve, _ ->
            val settled = mutableListOf<Pair<Font, String>>()
            fun load() = loadFont(
                "Joined blob font",
                realFontBlob(),
                FontWeight.Normal,
                FontStyle.Normal,
                FontVariation.Settings(),
            ) { font ->
                settled += font to fontFaceStatus(familyOf(font))
                if (settled.size == 2) resolve(settled.toJsReference())
            }
            load()
            load()
            assertTrue(settled.isEmpty(), "Handed over before the face has loaded")
        }.then { settled ->
            val (first, second) = settled.get()
            assertEquals(first.first, second.first)
            assertEquals("loaded", first.second)
            assertEquals("loaded", second.second)
            assertEquals(1, fontFaceCount(familyOf(first.first)))
            null
        }

    private fun familyOf(font: Font): String {
        val typeface = createWebFontFamilyResolver().resolve(FontFamily(font)).value as WebTypeface
        assertTrue(typeface.isRegistered, "$typeface")
        return typeface.family
    }
}

private class FontVariant(
    val weight: FontWeight = FontWeight.Normal,
    val style: FontStyle = FontStyle.Normal,
    val settings: FontVariation.Settings = FontVariation.Settings(),
)

private fun ImageBitmap.drawnAt(width: Int, height: Int): PixelMap {
    val drawn = ImageBitmap(width, height)
    Canvas(drawn).drawImageRect(
        this,
        srcSize = IntSize(this.width, this.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(width, height),
        paint = Paint(),
    )
    return drawn.toPixelMap()
}

/** Fonts of the given [variants], loaded one after the other, each from a new [blob]. */
private fun loadFonts(
    identity: String,
    vararg variants: FontVariant,
    blob: () -> JsAny = ::emptyBlob,
): Promise<JsReference<List<Font>>> = Promise { resolve, _ ->
    val fonts = mutableListOf<Font>()
    fun loadNext() {
        if (fonts.size == variants.size) {
            resolve(fonts.toJsReference())
        } else {
            val variant = variants[fonts.size]
            loadFont(identity, blob(), variant.weight, variant.style, variant.settings) {
                fonts += it
                loadNext()
            }
        }
    }
    loadNext()
}

// language=js
private fun fontFaceCount(family: String): Int = js(
    "[...document.fonts].filter((face) => '\"' + face.family.replaceAll('\"', '') + '\"' === family).length"
)

// language=js
private fun fontFaceStatus(family: String): String = js(
    "[...document.fonts].find((face) => '\"' + face.family.replaceAll('\"', '') + '\"' === family).status"
)

// language=js
private fun textWidth(fontFamily: String): Double = js(
    """{
        const ctx = document.createElement('canvas').getContext('2d');
        ctx.font = '100px ' + fontFamily;
        return ctx.measureText('abcdefghi').width;
    }"""
)

// language=js
private fun emptyBlob(): JsAny = js("new Blob([], { type: 'font/ttf' })")

private fun realFontBlob(): JsAny = fontBlobOf(RealFont)

// language=js
private fun fontBlobOf(base64: String): JsAny = js(
    "new Blob([Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))], { type: 'font/ttf' })"
)

// language=js
private fun isSameObject(a: JsAny, b: JsAny): Boolean = js("a === b")

/** An image of 4 x 4 pixels, red on the left, blue on the right, as the browser decoded it. */
// language=js
private fun twoColorsImage(): Promise<JsAny> = js(
    """(() => {
        const canvas = document.createElement('canvas');
        canvas.width = 4;
        canvas.height = 4;
        const ctx = canvas.getContext('2d');
        ctx.fillStyle = '#f00';
        ctx.fillRect(0, 0, 2, 4);
        ctx.fillStyle = '#00f';
        ctx.fillRect(2, 0, 2, 4);
        return createImageBitmap(canvas);
    })()"""
)

private const val SvgNamespace = """xmlns="http://www.w3.org/2000/svg""""

// Red on the left, blue on the right.
private const val TwoColors =
    """<rect width="1" height="2" fill="#f00"/><rect x="1" width="1" height="2" fill="#00f"/>"""

private const val SvgWithASize =
    """<svg $SvgNamespace width="2" height="2" viewBox="0 0 2 2">$TwoColors</svg>"""

private const val SvgOfAViewBox = """<svg $SvgNamespace viewBox="0 0 2 2">$TwoColors</svg>"""

private const val SvgOfForeignContent =
    """<svg $SvgNamespace width="2" height="2"><foreignObject width="2" height="2">""" +
        """<div xmlns="http://www.w3.org/1999/xhtml"></div></foreignObject></svg>"""

/** An `<img>` that has loaded the SVG document [svg]. */
// language=js
private fun svgElementOf(svg: String): Promise<JsAny> = js(
    """new Promise((resolve, reject) => {
        const image = new Image();
        image.onload = () => resolve(image);
        image.onerror = reject;
        image.src = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
    })"""
)

/** `overshoot_test.ttf` of foundation in Base64: a real font that browsers accept. */
private const val RealFont =
    "AAEAAAAKAIAAAwAgT1MvMmgdbRQAAAEoAAAAYGNtYXAADw0DAAABoAAAAEBnbHlm2Pli4gAAAfgAAADGaGVhZBa2jK0AAACsAAAANmhoZWEAtv+9AAAA5AAAACRobXR4AGX/agAAAYgAAAAYbG9jYQETANwAAAHgAAAAFm1heHAADAAEAAABCAAAACBuYW1lwnSkSgAAAsAAAADZcG9zdP+4ADIAAAOcAAAAIAABAAAAAQAAbQ58tl8PPPUAAQBkAAAAANTxQAgAAAAA4k0O4f9qAAAA+gBkAAAABwACAAAAAAAAAAEAAABQ/+wAAABk/2r/agD6AAEAAAAAAAAAAAAAAAAAAAACAAEAAAAKAAQAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwJSAZAABQAIAooCWAAAAEsCigJYAAABXgAyASwAAAAABQAAAAAAAAAAAAABAAAAAAAAAAAAAAAAVUtXTgBAACAF2AMg/zgAyAPoAMgAAAABAAAAAAH0ArwAAAAgAAAAMgAAAGQAAAAAAAAAAP/O/5z/agAyADIAAAABAAMACgAAAAwADAAAAAAANAAAAAAAAAADAAAAIAAAACAAAAABAAAAYQAAAGkAAAABAAAF0AAABdgAAAABAAAAAAALABYAIQAsADcAQgBNAFgAYwAAAAEAAAAAAGQAZAADAAAxNTMVZGRkAAABAAAAAACWAGQAAwAAMTUzFZZkZAAAAQAAAAAAyABkAAMAADE1MxXIZGQAAAEAAAAAAPoAZAADAAAxNTMV+mRkAAAB/84AAABkAGQAAwAAIzUzFTKWZGQAAf+cAAAAZABkAAMAACM1MxVkyGRkAAH/agAAAGQAZAADAAAjNTMVlvpkZAABADIAAABkAGQAAwAAMzUzFTIyZGQAAQAAAAAAMgBkAAMAADE1MxUyZGQAAAAAAAAIAGYAAQAAAAAAAQASAAAAAQAAAAAAAgAHABIAAQAAAAAABAASAAAAAQAAAAAABgASABkAAwABBAkAAQAWACsAAwABBAkAAgAOAEEAAwABBAkABAAWACsAAwABBAkABgAkAE9Gb250IGZvciBPdmVyc2hvb3RSZWd1bGFyU2FtcGxlRm9udC1SZWd1bGFyAFMAYQBtAHAAbABlACAARgBvAG4AdABSAGUAZwB1AGwAYQByAFMAYQBtAHAAbABlAEYAbwBuAHQALQBSAGUAZwB1AGwAYQByAAAAAAMAAAAAAAD/tQAyAAAAAAAAAAAAAAAAAAAAAAAAAAA="
