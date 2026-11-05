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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.webcanvas.WebCanvasGraphicsContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The outlines of layers, which they're clipped to and which cast their shadows. */
class LayerOutlineTest {
    private lateinit var context: WebCanvasGraphicsContext

    @BeforeTest
    fun setUp() {
        registerWebCanvasComposeImplementation()
        ShadowSprites.clear()
        context = WebCanvasGraphicsContext()
        // A light right above the layers of the tests, as in ShadowSpritesTest.
        context.setLightingInfo(
            centerX = 50f,
            centerY = 45f,
            centerZ = 600f,
            radius = 800f,
            ambientShadowAlpha = 0.039f,
            spotShadowAlpha = 0.19f,
        )
    }

    @AfterTest
    fun tearDown() {
        ShadowSprites.clear()
    }

    /** Draws [layer] at [topLeft] into a new image of [size] x [size] pixels. */
    private fun image(layer: GraphicsLayer, size: Int = 40, topLeft: IntOffset = IntOffset.Zero): ImageBitmap {
        layer.topLeft = topLeft
        val image = ImageBitmap(size, size)
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, Canvas(image), Size(size.toFloat(), size.toFloat()),
        ) {
            drawLayer(layer)
        }
        return image
    }

    /** The pixels of [layer] drawn at [topLeft] into a new image of [size] x [size] pixels. */
    private fun frame(layer: GraphicsLayer, size: Int = 40, topLeft: IntOffset = IntOffset.Zero): PixelMap =
        image(layer, size, topLeft).toPixelMap()

    /** A square of [size] with a square hole of [hole] in its middle, cut by the even-odd rule. */
    private fun evenOddRing(size: Float, hole: Float): Path = Path().apply {
        fillType = PathFillType.EvenOdd
        // Both wound the same way: only the rule cuts the hole.
        addRect(Rect(0f, 0f, size, size))
        val inset = (size - hole) / 2
        addRect(Rect(inset, inset, inset + hole, inset + hole))
    }

    @Test
    fun aPathChangedAndSetAgainIsClippedTo() {
        val layer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(40, 40)) { drawRect(Color.White) }
        layer.clip = true
        val outline = Path().apply { addRect(Rect(0f, 0f, 20f, 40f)) }
        layer.setPathOutline(outline)
        val left = frame(layer)
        outline.reset()
        outline.addRect(Rect(20f, 0f, 40f, 40f))
        layer.setPathOutline(outline)
        val right = frame(layer)
        assertEquals(Color.White, left[10, 20])
        assertEquals(0f, left[30, 20].alpha)
        assertEquals(0f, right[10, 20].alpha)
        assertEquals(Color.White, right[30, 20])
        context.releaseGraphicsLayer(layer)
    }

    @Test
    fun anEvenOddPathCutsItsHoleOutOfTheClip() {
        val layer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(40, 40)) { drawRect(Color.White) }
        layer.clip = true
        layer.setPathOutline(evenOddRing(size = 40f, hole = 20f))
        val pixels = frame(layer)
        assertEquals(Color.White, pixels[5, 5])
        assertEquals(0f, pixels[20, 20].alpha)
        context.releaseGraphicsLayer(layer)
    }

    @Test
    fun anEvenOddPathCutsItsHoleOutOfTheClipOfALayerWithOutsets() {
        val layer = context.createGraphicsLayer()
        // They enlarge the layer around its content and its outline, which stay where they're drawn.
        layer.setOutsets(10, 10, 0, 0)
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(40, 40)) { drawRect(Color.White) }
        layer.clip = true
        layer.setPathOutline(evenOddRing(size = 40f, hole = 20f))
        val pixels = frame(layer)
        assertEquals(Color.White, pixels[5, 5])
        assertEquals(0f, pixels[20, 20].alpha)
        context.releaseGraphicsLayer(layer)
    }

    @Test
    fun theShadowOfAnEvenOddPathHasItsHole() {
        // Nothing drawn: the shadow alone shows, under the ring and around it.
        val layer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(60, 60)) {}
        layer.setPathOutline(evenOddRing(size = 60f, hole = 30f))
        layer.shadowElevation = 2f
        val painted = frame(layer, size = 100, topLeft = IntOffset(20, 20))
        // Drawn again, from the images of its shadows.
        val fromImages = frame(layer, size = 100, topLeft = IntOffset(20, 20))
        assertEquals(2, ShadowSprites.created)
        for (pixels in listOf(painted, fromImages)) {
            assertTrue(pixels[27, 50].alpha > 0f, "no shadow under the ring: ${pixels[27, 50]}")
            assertEquals(0f, pixels[50, 50].alpha, "a shadow in the hole: ${pixels[50, 50]}")
        }
        context.releaseGraphicsLayer(layer)
    }

    /**
     * A white layer clipped to an even-odd ring, casting its shadow, turned around its center, with
     * the outsets ([left], [top], [right], [bottom]) set before it's recorded, as the owner of a
     * layer sets them.
     */
    private fun turnedRing(left: Int, top: Int, right: Int, bottom: Int): GraphicsLayer =
        context.createGraphicsLayer().also {
            it.setOutsets(left, top, right, bottom)
            it.record(Density(1f), LayoutDirection.Ltr, IntSize(60, 60)) { drawRect(Color.White) }
            it.clip = true
            it.setPathOutline(evenOddRing(size = 60f, hole = 30f))
            it.shadowElevation = 2f
            it.rotationZ = 10f
        }

    @Test
    fun theOutlineOfALayerWithOutsetsStaysWhereItIs() {
        // The clip, the shadow and the pivot are those of the layer without its outsets.
        val plain = turnedRing(0, 0, 0, 0)
        val expected = image(plain, size = 100, topLeft = IntOffset(20, 20)).imageData()
        ShadowSprites.clear()
        val outset = turnedRing(12, 7, 5, 9)
        val actual = image(outset, size = 100, topLeft = IntOffset(20, 20)).imageData()
        // The content is drawn through a translation by the outsets and one back, whose rounding
        // can change the coverage of its edges by a level.
        assertSamePixels(expected, actual, tolerance = 1)
        context.releaseGraphicsLayer(plain)
        context.releaseGraphicsLayer(outset)
    }
}
