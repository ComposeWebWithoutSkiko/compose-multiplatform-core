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

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.webcanvas.graphics.CanvasImageBitmap
import androidx.compose.ui.webcanvas.graphics.TextRunDrawing
import androidx.compose.ui.webcanvas.graphics.WebCanvas
import androidx.compose.ui.webcanvas.graphics.WebPaint
import androidx.compose.ui.webcanvas.graphics.contextHasProperty
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TextMeasurementTest {
    private class Entry(val key: Int)

    private fun sansSerif(size: Float, language: String = "en", pixelRatio: Float = 1f) = RunFont.of(
        WebTypeface(GenericFamilies.SansSerif, 400, isItalic = false, isRegistered = false, isGeneric = true),
        size = size,
        weight = 400,
        isItalic = false,
        letterSpacing = 0f,
        scaleX = 1f,
        pixelRatio = pixelRatio,
        language = language,
    )

    /** A left-to-right run of [text] in [font], drawn in black. */
    private fun runOf(font: RunFont, text: String) = TextRunDrawing(
        directionalRun(text, isRtl = false), font.css, font.language, WebPaint(),
        isRtl = false, letterSpacing = font.letterSpacingCss, shadow = null, pixelRatio = font.pixelRatio,
    )

    /**
     * Asserts that [text] in [font] is measured as wide as a canvas it's drawn into measures it, with
     * the text state that drawing gives that canvas, and returns that width.
     */
    private fun assertMeasuredAsDrawn(font: RunFont, text: String): Float {
        val image = CanvasImageBitmap(1, 1)
        val run = runOf(font, text)
        WebCanvas(image.drawTarget).drawText(run, 0f, 0f)
        val drawn = image.context.measureText(run.jsText).width.toFloat()
        assertEquals(drawn, TextMeasurement.width(font, text), 0.01f, "$text in ${font.language}")
        return drawn
    }

    @Test
    fun aRecentCacheKeepsTheValuesUsedLately() {
        val cache = RecentCache<Int, Entry>(capacity = 2) { it.key }
        val kept = Entry(0)
        cache.put(kept)
        for (key in 1..5) {
            cache.put(Entry(key))
            // Used all along: it stays.
            assertSame(kept, cache[0])
        }
        // Not used since the cache filled up twice: forgotten.
        assertNull(cache[1])
        assertEquals(5, cache[5]?.key)
    }

    @Test
    fun aWidthUsedAllAlongStaysMeasuredAmongManyOthers() {
        val font = sansSerif(14f)
        val width = TextMeasurement.width(font, "kept")
        // More words than the cache holds, the kept one measured again now and then.
        for (i in 0 until 50_200) {
            TextMeasurement.width(font, "word $i")
            if (i % 1000 == 500) TextMeasurement.width(font, "kept")
        }
        var again = 0f
        val calls = measureTextCallsDuring { again = TextMeasurement.width(font, "kept") }
        assertEquals(width, again)
        assertEquals(0, calls, "measured again")
    }

    @Test
    fun theMetricsOfABrowserWithoutFontBoxesAreApproximatedFromTheSize() {
        // Firefox before 116 measures no font bounding boxes.
        val font = sansSerif(20f)
        val restore = hideFontBoundingBoxes()
        try {
            TextMeasurement.clearCaches()
            val metrics = TextMeasurement.metrics(font)
            assertEquals(-18f, metrics.ascent, 0.01f)
            assertEquals(5f, metrics.descent, 0.01f)
        } finally {
            call(restore)
            TextMeasurement.clearCaches()
        }
    }

    @Test
    fun textIsMeasuredAsWideAsTheCanvasesItIsDrawnIntoMeasureIt() {
        // Firefox gives the system font another face in an OffscreenCanvas than in a <canvas>, and
        // kerns small text with `normal` unlike with the default kerning (`auto`), which both keep.
        assertMeasuredAsDrawn(sansSerif(12f), KernedLatin)
    }

    @Test
    fun textIsMeasuredInTheLanguageOfItsFont() {
        val japanese = assertMeasuredAsDrawn(sansSerif(24f, "ja"), HanAndKana)
        val korean = assertMeasuredAsDrawn(sansSerif(24f, "ko"), HanAndKana)
        // Where the language picks the glyphs, the comparison above tells the languages apart.
        if (contextHasProperty("lang")) {
            assertTrue(abs(japanese - korean) > 1f, "Japanese $japanese and Korean $korean measure alike")
        }
    }

    @Test
    fun textIsMeasuredAsWideAsItIsDrawnAtAPixelRatio() {
        // Text is drawn in the font of its CSS size scaled by the pixel ratio, and measured unscaled:
        // a browser kerning by the size on the device rather than the CSS size would split the two.
        for ((language, text) in listOf("en" to KernedLatin, "ja" to HanAndKana)) {
            val font = sansSerif(24f, language, pixelRatio = 2f)
            val image = CanvasImageBitmap(800, 80)
            WebCanvas(image.drawTarget).drawText(runOf(font, text), 10f, 40f)
            val pixels = image.toPixelMap()
            val inkedRight = (0 until pixels.width).last { x ->
                (0 until pixels.height).any { y -> pixels[x, y].alpha > 0.25f }
            }
            // The ink ends within the antialiasing and the side bearing of the last letter of the
            // advance it's measured with.
            assertEquals(TextMeasurement.width(font, text), (inkedRight + 1 - 10).toFloat(), 3.5f, "$text in $language")
        }
    }

    @Test
    fun textIsMeasuredOnTheKindOfCanvasItIsDrawnInto() {
        val drawnInto = CanvasImageBitmap(1, 1).canvas
        val calls = textCallsDuring("measureText") { TextMeasurement.width(sansSerif(17f), "measured as it's drawn") }
        assertTrue(callCount(calls) > 0, "measured nothing")
        assertTrue(allOnTheClassOf(calls, drawnInto), "measured on another kind of canvas than the one text is drawn into")
    }
}

/** Latin text with pairs of letters that fonts kern (AV, AT, Wa, To). */
private const val KernedLatin = "Meditate peacefully AVATAR Wave Toy"

/** Two Han characters and five kana, which Japanese and Korean fonts draw unlike each other. */
private val HanAndKana = charArrayOf(
    Char(0x76F4), Char(0x89D2), Char(0x3053), Char(0x3093), Char(0x306B), Char(0x3061), Char(0x306F),
).concatToString()

/**
 * Makes the `TextMetrics` of the browser lack their font bounding boxes, and returns the function
 * that gives them back.
 */
// language=js
private fun hideFontBoundingBoxes(): JsAny = js(
    """{
        const names = ['fontBoundingBoxAscent', 'fontBoundingBoxDescent'];
        const saved = names.map(name => Object.getOwnPropertyDescriptor(TextMetrics.prototype, name));
        names.forEach(name => Object.defineProperty(TextMetrics.prototype, name, { get: () => undefined, configurable: true }));
        return () => names.forEach((name, i) => {
            if (saved[i]) Object.defineProperty(TextMetrics.prototype, name, saved[i]);
            else delete TextMetrics.prototype[name];
        });
    }"""
)

// language=js
private fun call(block: JsAny): Unit = js("block()")
