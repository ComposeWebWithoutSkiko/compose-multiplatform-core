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

package androidx.compose.ui.webcanvas.graphics

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.webcanvas.dom.CanvasElement
import androidx.compose.ui.webcanvas.dom.deref
import androidx.compose.ui.webcanvas.dom.newWeakRef
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.unsafeCast
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class WebImageBitmapTest {
    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    private fun tint(color: Color) = WebColorFilter.Tint(color, BlendMode.SrcIn)

    /** The left half of the image opaque, the rest transparent. */
    private fun canvasImage(config: ImageBitmapConfig) =
        CanvasImageBitmap(8, 8, config).also { image ->
            Canvas(image).drawRect(Rect(0f, 0f, 4f, 8f), Paint().apply { color = Color.Black })
        }

    private fun mask() = canvasImage(ImageBitmapConfig.Alpha8)

    private fun CanvasImageBitmap.source(filter: WebColorFilter) = assertNotNull(filteredSource(filter))

    private fun CanvasImageBitmap.copy(filter: WebColorFilter) = source(filter).unsafeCast<CanvasElement>()

    /** The pixel that [image] shows at ([x], 1) drawn with [paint]. */
    private fun drawn(image: ImageBitmap, paint: Paint, x: Int = 1): Color {
        val target = ImageBitmap(image.width, image.height)
        Canvas(target).drawImage(image, Offset.Zero, paint)
        return target.toPixelMap()[x, 1]
    }

    @Test
    fun aMaskTakesItsTintInItsOwnCanvas() {
        val image = mask()
        assertSame<JsAny>(image.canvas, image.source(tint(Color.Red)))
    }

    @Test
    fun anImageWithColorsStaysAsItIs() {
        val image = canvasImage(ImageBitmapConfig.Argb8888)
        assertNotSame<JsAny>(image.canvas, image.source(tint(Color.Red)))
        assertEquals(Color.Black, image.toPixelMap()[1, 1])
    }

    @Test
    fun aMaskIsPaintedOncePerTint() {
        val image = mask()
        val red = tint(Color.Red)
        val blue = tint(Color.Blue)
        val calls = contextCallsDuring(listOf("fillRect")) {
            repeat(3) {
                image.source(red)
                image.source(blue)
            }
        }
        assertEquals(2, calls.names.size)
    }

    @Test
    fun aMaskShowsEachOfItsTintsInUse() {
        val image = mask()
        val red = ColorFilter.tint(Color.Red)
        val blue = ColorFilter.tint(Color.Blue)
        repeat(2) {
            assertColor(Color.Red, drawn(image, Paint().apply { colorFilter = red }))
            assertColor(Color.Blue, drawn(image, Paint().apply { colorFilter = blue }))
            assertColor(Color.Transparent, drawn(image, Paint().apply { colorFilter = blue }, x = 6))
        }
    }

    @Test
    fun aChangedMaskIsPaintedAgainAndDropsItsCopies() {
        val image = mask()
        val red = tint(Color.Red)
        val blue = tint(Color.Blue)
        image.source(red)
        val copy = image.copy(blue)
        image.markChanged()
        assertEquals(1, contextCallsDuring(listOf("fillRect")) { image.source(red) }.names.size)
        assertEquals(0, copy.width)
    }

    @Test
    fun aTintThatFadesKeepsItsCanvas() {
        val image = mask()
        val half = tint(Color.Red.copy(alpha = 0.5f))
        val quarter = tint(Color.Red.copy(alpha = 0.25f))
        assertSame(image.source(half), image.source(quarter))
    }

    @Test
    fun theAlphaOfATintMultipliesThatOfTheDraw() {
        val paint = Paint().apply {
            alpha = 0.5f
            colorFilter = ColorFilter.tint(Color.Red.copy(alpha = 0.5f))
        }
        assertColor(Color.Red.copy(alpha = 0.25f), drawn(mask(), paint))
    }

    @Test
    fun aTintedImageTakesTheAlphaOfItsTintOnce() {
        val paint = Paint().apply {
            alpha = 0.5f
            colorFilter = ColorFilter.tint(Color.Red.copy(alpha = 0.5f))
        }
        assertColor(Color.Red.copy(alpha = 0.25f), drawn(canvasImage(ImageBitmapConfig.Argb8888), paint))
    }

    @Test
    fun aCopySurvivesAFadeOfItsTint() {
        val image = canvasImage(ImageBitmapConfig.Argb8888)
        val half = tint(Color.Red.copy(alpha = 0.5f))
        val quarter = tint(Color.Red.copy(alpha = 0.25f))
        assertSame(image.copy(half), image.copy(quarter))
    }

    @Test
    fun aTintWithoutAlphaPaintsNothing() {
        val image = mask()
        val target = Canvas(ImageBitmap(8, 8))
        val paint = Paint().apply { colorFilter = ColorFilter.tint(Color.Red.copy(alpha = 0f)) }
        val calls = contextCallsDuring(listOf("fillRect")) { target.drawImage(image, Offset.Zero, paint) }
        assertEquals(emptyList(), calls.names)
    }

    @Test
    fun aMaskCanBeDrawnIntoAfterItsTint() {
        val image = mask()
        val canvas = Canvas(image)
        canvas.translate(2f, 2f)
        canvas.drawRect(Rect(0f, 0f, 1f, 1f), Paint().apply { color = Color.Black })
        image.source(tint(Color.Red))
        canvas.drawRect(Rect(4f, 4f, 5f, 5f), Paint().apply { color = Color.Black })
        assertEquals(1f, image.toPixelMap()[6, 6].alpha)
        assertEquals(0f, image.toPixelMap()[4, 4].alpha)
    }

    @Test
    fun aClipLeftOpenLeavesTheCanvasAsItIs() {
        val image = mask()
        Canvas(image).clipRect(Rect(0f, 0f, 2f, 8f))
        assertNotSame<JsAny>(image.canvas, image.source(tint(Color.Red)))
        assertEquals(Color.Black, image.toPixelMap()[1, 1])
    }

    @Test
    fun equalFiltersShareACopy() {
        val image = mask()
        val red = tint(Color.Red)
        image.source(red)
        val blue = image.copy(tint(Color.Blue))
        assertSame(blue, image.copy(tint(Color.Blue)))
        assertSame<JsAny>(image.canvas, image.source(red))
    }

    @Test
    fun copiesStayPastTheEntriesThatGetPruned() {
        val image = canvasImage(ImageBitmapConfig.Argb8888)
        val filters = List(120) { tint(Color(it, 0, 0)) }
        val copies = filters.map { image.copy(it) }
        filters.forEachIndexed { index, filter -> assertSame(copies[index], image.copy(filter)) }
    }

    @Test
    fun theEntriesOfCopiesThatWentArePruned(): Promise<JsAny?> {
        val image = canvasImage(ImageBitmapConfig.Argb8888)
        makeCopiesWithTheirFiltersGone(image)
        return nextTask().then {
            if (collectGarbage()) {
                image.copy(tint(Color.Blue))
                assertEquals(1, image.filteredCopyEntries)
            }
            null
        }
    }

    private fun makeCopiesWithTheirFiltersGone(image: CanvasImageBitmap) {
        repeat(120) { image.copy(tint(Color(it, 0, 0))) }
    }

    @Test
    fun aFilterKeepsTheCopiesOfItsImagesApart(): Promise<JsAny?> {
        val filter = tint(Color.Red)
        val images = List(2) { canvasImage(ImageBitmapConfig.Argb8888) }
        val copies = weakCopies(images, filter)
        return nextTask().then {
            if (collectGarbage()) copies.forEach { assertNotNull(deref(it), "The copy is gone") }
            assertNotNull(filter)
            assertEquals(2, images.size)
            null
        }
    }

    private fun weakCopies(images: List<CanvasImageBitmap>, filter: WebColorFilter): List<JsAny> =
        images.map { newWeakRef(it.copy(filter)) }

    @Test
    fun aCopyGoesWithItsFilters(): Promise<JsAny?> {
        val image = canvasImage(ImageBitmapConfig.Argb8888)
        val copy = weakCopyWithAFilterGone(image)
        return nextTask().then {
            if (collectGarbage()) assertNull(deref(copy), "The copy is kept alive")
            assertNotNull(image)
            null
        }
    }

    private fun weakCopyWithAFilterGone(image: CanvasImageBitmap): JsAny =
        newWeakRef(image.copy(tint(Color.Red)))

    @Test
    fun aCopyGoesWithItsSvgFilter(): Promise<JsAny?> {
        val image = canvasImage(ImageBitmapConfig.Argb8888)
        val copy = weakCopyWithAnSvgFilterGone(image)
        return nextTask().then {
            if (collectGarbage() && SvgColorFilters.isSupported) assertNull(deref(copy), "The copy is kept alive")
            assertNotNull(image)
            null
        }
    }

    private fun weakCopyWithAnSvgFilterGone(image: CanvasImageBitmap): JsAny =
        newWeakRef(image.copy(ColorMatrix().apply { setToSaturation(0.123f) }.toWebColorFilter()))

    @Test
    fun aCopyStaysWithAnEqualFilterThatFoundIt(): Promise<JsAny?> {
        val image = canvasImage(ImageBitmapConfig.Argb8888)
        val filter = tint(Color.Red)
        val copy = weakCopyFoundBy(image, filter)
        return nextTask().then {
            if (collectGarbage()) assertNotNull(deref(copy), "The copy is gone")
            assertNotNull(filter)
            assertNotNull(image)
            null
        }
    }

    private fun weakCopyFoundBy(image: CanvasImageBitmap, filter: WebColorFilter): JsAny {
        image.copy(tint(Color.Red))
        return newWeakRef(image.copy(filter))
    }

    @Test
    fun aFilterKeepsNothingOfAnImageThatIsGone(): Promise<JsAny?> {
        val filter = tint(Color.Red)
        val copy = weakCopyOfAnImageGone(filter)
        return nextTask().then {
            if (collectGarbage()) assertNull(deref(copy), "The copy is kept alive")
            assertNotNull(filter)
            null
        }
    }

    private fun weakCopyOfAnImageGone(filter: WebColorFilter): JsAny =
        newWeakRef(canvasImage(ImageBitmapConfig.Argb8888).copy(filter))

    @Test
    fun aTintThatLeftGivesTheCanvasToTheOneInUse(): Promise<JsAny?> {
        val image = mask()
        val blue = tint(Color.Blue)
        val copy = copyWithTheHolderGone(image, blue)
        return nextTask().then {
            if (collectGarbage()) {
                assertSame<JsAny>(image.canvas, image.source(blue))
                assertEquals(0, copy.width)
            }
            null
        }
    }

    private fun copyWithTheHolderGone(image: CanvasImageBitmap, blue: WebColorFilter): CanvasElement {
        image.source(tint(Color.Red))
        return image.copy(blue)
    }

    @Test
    fun aCanvasStaysWithAnEqualFilterThatFoundIt(): Promise<JsAny?> {
        val image = mask()
        val red = tint(Color.Red)
        takeTheCanvasAfterAnEqualFilter(image, red)
        return nextTask().then {
            if (collectGarbage()) assertNotSame<JsAny>(image.canvas, image.source(tint(Color.Blue)))
            assertNotNull(red)
            null
        }
    }

    private fun takeTheCanvasAfterAnEqualFilter(image: CanvasImageBitmap, filter: WebColorFilter) {
        image.source(tint(Color.Red))
        image.source(filter)
    }

    @Test
    fun aCanvasStaysWithTheEarlierFilterWhenTheLastOneLeaves(): Promise<JsAny?> {
        val image = mask()
        val red = tint(Color.Red)
        takeTheCanvasBeforeAnEqualFilter(image, red)
        return nextTask().then {
            if (collectGarbage()) assertNotSame<JsAny>(image.canvas, image.source(tint(Color.Blue)))
            assertNotNull(red)
            null
        }
    }

    private fun takeTheCanvasBeforeAnEqualFilter(image: CanvasImageBitmap, filter: WebColorFilter) {
        image.source(filter)
        image.source(tint(Color.Red))
    }

    @Test
    fun aTintThatComesBackHoldsItsCanvas(): Promise<JsAny?> {
        val image = mask()
        val red = tint(Color.Red)
        takeTheCanvasWithAFilterGone(image)
        return nextTask().then {
            collectGarbage()
            image.source(red)
            nextTask()
        }.then {
            if (collectGarbage()) assertNotSame<JsAny>(image.canvas, image.source(tint(Color.Blue)))
            assertNotNull(red)
            null
        }
    }

    private fun takeTheCanvasWithAFilterGone(image: CanvasImageBitmap) {
        image.source(tint(Color.Red))
    }
}
