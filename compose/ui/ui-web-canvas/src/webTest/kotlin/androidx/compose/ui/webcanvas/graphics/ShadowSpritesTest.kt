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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.webcanvas.WebCanvasGraphicsContext
import androidx.compose.ui.webcanvas.path.WebPath
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shadows of elevated layers are painted once and drawn again from their images. */
class ShadowSpritesTest {
    private lateinit var context: WebCanvasGraphicsContext

    @BeforeTest
    fun setUp() {
        registerWebCanvasComposeImplementation()
        ShadowSprites.clear()
        context = WebCanvasGraphicsContext()
        // A light right above the layers of the tests, so that their spot shadows aren't offset.
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

    /** A white rounded rectangle elevated by [elevation]. */
    private fun elevatedLayer(size: IntSize = IntSize(40, 30), elevation: Float = 8f): GraphicsLayer {
        val layer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, size) { drawRect(Color.White) }
        layer.setRoundRectOutline(Offset.Zero, size.toSize(), 8f)
        layer.shadowElevation = elevation
        return layer
    }

    /** A white layer elevated by 8, outlined by a rectangle with cut corners moved by [offset]. */
    private fun elevatedCutLayer(offset: Offset = Offset.Zero): GraphicsLayer {
        val layer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(50, 40)) { drawRect(Color.White) }
        val outline = Path().apply {
            moveTo(8f, 0f)
            lineTo(32f, 0f)
            lineTo(40f, 8f)
            lineTo(40f, 22f)
            lineTo(32f, 30f)
            lineTo(8f, 30f)
            lineTo(0f, 22f)
            lineTo(0f, 8f)
            close()
            translate(offset)
        }
        layer.setPathOutline(outline)
        layer.shadowElevation = 8f
        return layer
    }

    /** Draws [layer] at [topLeft] into a new 100x100 image, like a frame. */
    private fun frame(layer: GraphicsLayer, topLeft: IntOffset = IntOffset(30, 30)): PixelMap {
        layer.topLeft = topLeft
        val image = ImageBitmap(100, 100)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(100f, 100f)) {
            drawLayer(layer)
        }
        return image.toPixelMap()
    }

    private val shapes = HashMap<String, ShadowShape>()

    /**
     * Draws the shadows of shapes identified by [keys], each filling an image of [SpriteSize] x
     * [SpriteSize] pixels, and returns their images.
     */
    private fun drawShadows(keys: List<String>): List<ShadowSprites.Sprite?> = keys.map { key ->
        val shape = shapes.getOrPut(key) {
            ShadowShape.Path(
                WebPath().apply { addRect(Rect(0f, 0f, 10f, 10f)) }.snapshot(),
                key,
                ShadowSprites.Requests(),
            )
        }
        ShadowSprites.spriteFor(
            shape,
            Affine(),
            sigma = 2f,
            rgb = WebCanvas.OpaqueBlack,
            left = 0f,
            top = 0f,
            width = SpriteSize,
            height = SpriteSize,
        )
    }

    @Test
    fun aShadowGetsItsImageTheSecondTimeItIsDrawnAndLooksTheSame() {
        val layer = elevatedLayer()
        val direct = frame(layer)
        assertEquals(0, ShadowSprites.created)
        val fromImages = frame(layer)
        // An ambient and a spot shadow.
        assertEquals(2, ShadowSprites.created)
        var shadowPixels = 0
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                val a = direct[x, y]
                val b = fromImages[x, y]
                if (a.alpha > 0f && a.red < 1f) shadowPixels++
                // Compared premultiplied, as the pixels are stored. The spot shadow is scaled
                // around the center of the shape, so its image is drawn between pixels.
                assertTrue(
                    abs(a.red * a.alpha - b.red * b.alpha) <= Tolerance &&
                        abs(a.alpha - b.alpha) <= Tolerance,
                    "($x, $y): $a directly, $b from the images",
                )
            }
        }
        assertTrue(shadowPixels > 100, "no shadow drawn")
        context.releaseGraphicsLayer(layer)
    }

    @Test
    fun movingOrFadingAShadowKeepsItsImages() {
        val layer = elevatedLayer()
        frame(layer)
        frame(layer)
        assertEquals(2, ShadowSprites.created)
        frame(layer, IntOffset(10, 20))
        frame(layer, IntOffset(41, 7))
        layer.alpha = 0.5f
        frame(layer)
        assertEquals(2, ShadowSprites.created)
        context.releaseGraphicsLayer(layer)
    }

    @Test
    fun shapesOfTheSameSizeShareTheirImages() {
        val first = elevatedLayer()
        frame(first)
        frame(first)
        assertEquals(2, ShadowSprites.created)
        val second = elevatedLayer()
        frame(second, IntOffset(5, 50))
        assertEquals(2, ShadowSprites.created)
        val larger = elevatedLayer(IntSize(50, 30))
        frame(larger)
        frame(larger)
        assertEquals(4, ShadowSprites.created)
        listOf(first, second, larger).forEach { context.releaseGraphicsLayer(it) }
    }

    @Test
    fun equalPathsShareTheirImagesWhereverTheyAreInTheirLayers() {
        val first = elevatedCutLayer()
        frame(first)
        frame(first)
        assertEquals(2, ShadowSprites.created)
        val second = elevatedCutLayer(Offset(6f, 4f))
        frame(second, IntOffset(20, 40))
        frame(second, IntOffset(20, 40))
        assertEquals(2, ShadowSprites.created)
        listOf(first, second).forEach { context.releaseGraphicsLayer(it) }
    }

    @Test
    fun aShadowThatChangesInEveryFrameGetsNoImage() {
        val layer = elevatedLayer()
        for (elevation in 1..6) {
            layer.shadowElevation = elevation.toFloat()
            frame(layer)
        }
        assertEquals(0, ShadowSprites.created)
        context.releaseGraphicsLayer(layer)
    }

    @Test
    fun anOutlineSetAgainKeepsItsShadowsAskedFor() {
        // The outline is set again whenever the elevation changes, here back and forth before the
        // layer is drawn again, which asks for its shadows the second time.
        val layer = elevatedLayer()
        frame(layer)
        layer.shadowElevation = 4f
        layer.shadowElevation = 8f
        frame(layer)
        assertEquals(2, ShadowSprites.created)
        context.releaseGraphicsLayer(layer)
    }

    @Test
    fun aShadowOutOfTheClipGetsNoImage() {
        val layer = elevatedLayer()
        frame(layer, IntOffset(500, 500))
        frame(layer, IntOffset(500, 500))
        assertEquals(0, ShadowSprites.created)
        context.releaseGraphicsLayer(layer)
    }

    @Test
    fun overTheBudgetTheShadowsDrawnInEveryFrameKeepTheirImages() {
        // The budget holds 4 of them.
        val keys = List(6) { "shape $it" }
        var start = 0.0
        repeat(2) {
            start = ShadowSprites.startFrame(start)
            drawShadows(keys)
        }
        start = ShadowSprites.startFrame(start)
        val images = drawShadows(keys)
        assertEquals(4, images.count { it != null })
        repeat(5) {
            start = ShadowSprites.startFrame(start)
            assertEquals(images, drawShadows(keys))
        }
        assertEquals(4, ShadowSprites.created)
    }

    @Test
    fun theImagesOfShadowsNoLongerDrawnMakeRoom() {
        var start = 0.0
        for (keys in listOf(List(4) { "before $it" }, List(4) { "after $it" })) {
            repeat(3) {
                start = ShadowSprites.startFrame(start)
                drawShadows(keys)
            }
            assertTrue(drawShadows(keys).all { it != null })
        }
        assertEquals(8, ShadowSprites.created)
    }

    @Test
    fun overTheBudgetTwoRenderersMakeAnImageOfAShapeOnce() {
        // The first one scrolls: 5 shapes in view, a new one on top every 4 frames.
        var first = 0.0
        var second = 0.0
        val shapes = HashSet<String>()
        for (frame in 0 until 16) {
            first = ShadowSprites.startFrame(first)
            val inView = List(5) { "first ${frame / 4 + 4 - it}" }
            drawShadows(inView)
            shapes += inView
            second = ShadowSprites.startFrame(second)
            drawShadows(listOf("second"))
            shapes += "second"
        }
        assertTrue(
            ShadowSprites.created <= shapes.size,
            "${ShadowSprites.created} images of ${shapes.size} shapes",
        )
    }

    @Test
    fun aLargeShadowIsDrawnDirectly() {
        // Its image would take more than a quarter of the budget of the images.
        val layer = elevatedLayer(IntSize(1200, 1200))
        frame(layer, IntOffset(-500, -500))
        val pixels = frame(layer, IntOffset(-500, -500))
        assertEquals(0, ShadowSprites.created)
        assertEquals(Color.White, pixels[50, 50])
        context.releaseGraphicsLayer(layer)
    }

    private companion object {
        const val Tolerance = 3f / 255f

        /** The side of the images of [drawShadows]: the budget holds 4 of them. */
        const val SpriteSize = 1000
    }
}
