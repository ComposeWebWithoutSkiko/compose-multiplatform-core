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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.webcanvas.WebCanvasGraphicsContext
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Layers with an alpha: content that draws a single shape takes the alpha directly instead of going
 * through an offscreen canvas, with the same pixels.
 */
class LayerAlphaTest {
    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    private fun recorded(draw: Canvas.() -> Unit): DisplayList {
        val canvas = RecordingCanvas()
        canvas.beginRecording()
        canvas.draw()
        return canvas.endRecording()
    }

    private val rect = Rect(0f, 0f, 10f, 10f)

    @Test
    fun aSingleShapeCanTakeTheAlphaDirectly() {
        assertTrue(recorded {}.canModulateAlpha)
        assertTrue(recorded { drawRect(rect, Paint()) }.canModulateAlpha)
        assertTrue(
            recorded {
                save()
                clipRect(rect)
                translate(2f, 3f)
                drawCircle(Offset(5f, 5f), 4f, Paint())
                restore()
            }.canModulateAlpha
        )
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(10f, 10f)
            lineTo(10f, 0f)
            lineTo(0f, 10f)
        }
        // A stroke crossing itself is still a single coverage.
        assertTrue(recorded { drawPath(path, Paint().apply { style = PaintingStyle.Stroke }) }.canModulateAlpha)
        assertTrue(recorded { drawImage(ImageBitmap(4, 4), Offset.Zero, Paint()) }.canModulateAlpha)
    }

    @Test
    fun contentThatCanCoverItselfCanNot() {
        assertFalse(recorded { drawRect(rect, Paint()); drawRect(rect, Paint()) }.canModulateAlpha)
        assertFalse(recorded { drawRect(rect, Paint().apply { blendMode = BlendMode.Multiply }) }.canModulateAlpha)
        assertFalse(
            recorded { drawPoints(PointMode.Points, listOf(Offset(1f, 1f)), Paint()) }.canModulateAlpha
        )
        assertFalse(
            recorded {
                saveLayer(rect, Paint())
                drawRect(rect, Paint())
                restore()
            }.canModulateAlpha
        )
        val context = WebCanvasGraphicsContext()
        val layer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(10, 10)) { drawRect(Color.Red) }
        val withLayer = recorded {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, this, Size(10f, 10f)) {
                drawLayer(layer)
            }
        }
        context.releaseGraphicsLayer(layer)
        assertFalse(withLayer.canModulateAlpha)
    }

    /** Draws a layer of [layerSize] at [topLeft] with [alpha] into a 40x40 image. */
    private fun drawLayerImage(
        layerSize: IntSize,
        topLeft: IntOffset,
        alpha: Float,
        strategy: CompositingStrategy,
        content: DrawScope.() -> Unit,
    ): ImageBitmap {
        val context = WebCanvasGraphicsContext()
        val layer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, layerSize, content)
        layer.topLeft = topLeft
        layer.alpha = alpha
        layer.compositingStrategy = strategy
        val image = ImageBitmap(40, 40)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(40f, 40f)) {
            drawLayer(layer)
        }
        context.releaseGraphicsLayer(layer)
        return image
    }

    @Test
    fun aSingleShapeLooksTheSameAsThroughAnOffscreenLayer() {
        val content: DrawScope.() -> Unit = {
            drawCircle(Color(0xFF3366CC), radius = 11.5f, center = Offset(12f, 12f))
        }
        for (alpha in listOf(0.3f, 0.75f)) {
            val direct =
                drawLayerImage(IntSize(24, 24), IntOffset(8, 8), alpha, CompositingStrategy.Auto, content)
                    .toPixelMap()
            val offscreen =
                drawLayerImage(IntSize(24, 24), IntOffset(8, 8), alpha, CompositingStrategy.Offscreen, content)
                    .toPixelMap()
            for (y in 0 until 40) {
                for (x in 0 until 40) {
                    val a = direct[x, y]
                    val b = offscreen[x, y]
                    // Compared premultiplied, as the pixels are stored: the colors of nearly
                    // transparent pixels are imprecise.
                    assertTrue(
                        abs(a.red * a.alpha - b.red * b.alpha) <= Tolerance &&
                            abs(a.green * a.alpha - b.green * b.alpha) <= Tolerance &&
                            abs(a.blue * a.alpha - b.blue * b.alpha) <= Tolerance &&
                            abs(a.alpha - b.alpha) <= Tolerance,
                        "($x, $y) with alpha $alpha: $a directly, $b through a layer",
                    )
                }
            }
        }
    }

    @Test
    fun aSingleShapeIsClippedToTheLayer() {
        // A 20x20 layer at (10, 10) whose rectangle reaches 10 px out of it on every side.
        val pixels = drawLayerImage(IntSize(20, 20), IntOffset(10, 10), 0.5f, CompositingStrategy.Auto) {
            drawRect(Color.Red, topLeft = Offset(-10f, -10f), size = Size(40f, 40f))
        }.toPixelMap()
        assertEquals(0.5f, pixels[20, 20].alpha, Tolerance)
        assertEquals(0f, pixels[5, 20].alpha)
        assertEquals(0f, pixels[34, 20].alpha)
        assertEquals(0f, pixels[20, 5].alpha)
        assertEquals(0f, pixels[20, 34].alpha)
    }

    @Test
    fun anEmptyLayerDrawsNothing() {
        val pixels = drawLayerImage(IntSize.Zero, IntOffset(10, 10), 0.5f, CompositingStrategy.Auto) {
            drawRect(Color.Red, topLeft = Offset(-10f, -10f), size = Size(40f, 40f))
        }.toPixelMap()
        for (y in 0 until 40) {
            for (x in 0 until 40) assertEquals(0f, pixels[x, y].alpha, "($x, $y)")
        }
    }

    @Test
    fun overlappingShapesStillGoThroughALayer() {
        // Two opaque rectangles overlapping at x = 8..12 in a half transparent layer: through a
        // layer the overlap is as transparent as the rest, drawn directly it would be darker.
        val pixels = drawLayerImage(IntSize(20, 10), IntOffset(0, 0), 0.5f, CompositingStrategy.Auto) {
            drawRect(Color.Red, topLeft = Offset(0f, 0f), size = Size(12f, 10f))
            drawRect(Color.Red, topLeft = Offset(8f, 0f), size = Size(12f, 10f))
        }.toPixelMap()
        assertEquals(0.5f, pixels[4, 5].alpha, Tolerance)
        assertEquals(0.5f, pixels[10, 5].alpha, Tolerance)
        assertEquals(0.5f, pixels[16, 5].alpha, Tolerance)
    }

    private companion object {
        /** The rounding of 8-bit channels, applied once more or less. */
        const val Tolerance = 2f / 255f
    }
}
