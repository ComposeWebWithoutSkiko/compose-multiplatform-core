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
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositeShader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebCanvasGraphicsTest {
    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    private fun image(width: Int = 20, height: Int = 20, draw: Canvas.() -> Unit): ImageBitmap {
        val image = ImageBitmap(width, height)
        Canvas(image).draw()
        return image
    }

    private fun assertColor(expected: Color, actual: Color, tolerance: Float = 0.02f) {
        assertTrue(
            abs(expected.red - actual.red) <= tolerance &&
                abs(expected.green - actual.green) <= tolerance &&
                abs(expected.blue - actual.blue) <= tolerance &&
                abs(expected.alpha - actual.alpha) <= tolerance,
            "Expected $expected but was $actual",
        )
    }

    private fun ImageBitmap.pixel(x: Int, y: Int): Color = toPixelMap()[x, y]

    @Test
    fun drawRectFillsPixels() {
        val image = image {
            drawRect(Rect(0f, 0f, 10f, 10f), Paint().apply { color = Color.Red })
        }
        assertColor(Color.Red, image.pixel(5, 5))
        assertColor(Color.Transparent, image.pixel(15, 15))
    }

    @Test
    fun strokeDoesNotFillTheInside() {
        val image = image {
            drawRect(
                Rect(2f, 2f, 18f, 18f),
                Paint().apply {
                    color = Color.Blue
                    style = PaintingStyle.Stroke
                    strokeWidth = 2f
                },
            )
        }
        assertColor(Color.Blue, image.pixel(2, 10))
        assertColor(Color.Transparent, image.pixel(10, 10))
    }

    @Test
    fun clipRectRestrictsDrawing() {
        val image = image {
            save()
            clipRect(0f, 0f, 10f, 20f, ClipOp.Intersect)
            drawRect(Rect(0f, 0f, 20f, 20f), Paint().apply { color = Color.Green })
            restore()
        }
        assertColor(Color.Green, image.pixel(5, 5))
        assertColor(Color.Transparent, image.pixel(15, 5))
    }

    @Test
    fun differenceClipRemovesTheArea() {
        val image = image {
            save()
            clipRect(0f, 0f, 10f, 20f, ClipOp.Difference)
            drawRect(Rect(0f, 0f, 20f, 20f), Paint().apply { color = Color.Green })
            restore()
        }
        assertColor(Color.Transparent, image.pixel(5, 5))
        assertColor(Color.Green, image.pixel(15, 5))
    }

    @Test
    fun translateMovesDrawing() {
        val image = image {
            translate(10f, 10f)
            drawRect(Rect(0f, 0f, 5f, 5f), Paint().apply { color = Color.Red })
        }
        assertColor(Color.Transparent, image.pixel(2, 2))
        assertColor(Color.Red, image.pixel(12, 12))
    }

    @Test
    fun saveLayerAppliesAlpha() {
        val image = image {
            saveLayer(Rect(0f, 0f, 20f, 20f), Paint().apply { alpha = 0.5f })
            drawRect(Rect(0f, 0f, 20f, 20f), Paint().apply { color = Color.Black })
            drawRect(Rect(0f, 0f, 10f, 20f), Paint().apply { color = Color.Black })
            restore()
        }
        // Overlapping draws inside a layer don't accumulate alpha.
        assertColor(Color.Black.copy(alpha = 0.5f), image.pixel(5, 5))
        assertColor(Color.Black.copy(alpha = 0.5f), image.pixel(15, 5))
    }

    @Test
    fun clearBlendModeClears() {
        val image = image {
            drawRect(Rect(0f, 0f, 20f, 20f), Paint().apply { color = Color.Red })
            drawRect(Rect(0f, 0f, 10f, 10f), Paint().apply { blendMode = BlendMode.Clear })
        }
        assertColor(Color.Transparent, image.pixel(5, 5))
        assertColor(Color.Red, image.pixel(15, 15))
    }

    @Test
    fun srcInBlendModeDoesNotAffectOutsideOfTheShape() {
        val image = image {
            drawRect(Rect(0f, 0f, 20f, 20f), Paint().apply { color = Color.Red })
            drawRect(
                Rect(0f, 0f, 10f, 10f),
                Paint().apply {
                    color = Color.Blue
                    blendMode = BlendMode.SrcIn
                },
            )
        }
        assertColor(Color.Blue, image.pixel(5, 5))
        assertColor(Color.Red, image.pixel(15, 15))
    }

    @Test
    fun linearGradient() {
        val image = image(width = 100, height = 2) {
            drawRect(
                Rect(0f, 0f, 100f, 2f),
                Paint().apply {
                    shader = LinearGradientShader(
                        Offset(0f, 0f),
                        Offset(100f, 0f),
                        listOf(Color.Red, Color.Blue),
                    )
                },
            )
        }
        assertColor(Color.Red, image.pixel(0, 0), tolerance = 0.05f)
        assertColor(Color.Blue, image.pixel(99, 0), tolerance = 0.05f)
    }

    @Test
    fun radialGradientIsCenteredOnItsCenter() {
        val image = image(width = 40, height = 40) {
            drawRect(
                Rect(0f, 0f, 40f, 40f),
                Paint().apply {
                    shader = RadialGradientShader(
                        Offset(30f, 10f),
                        radius = 10f,
                        colors = listOf(Color.Red, Color.Blue),
                    )
                },
            )
        }
        assertColor(Color.Red, image.pixel(30, 10), tolerance = 0.1f)
        // Past the radius, the last color is clamped.
        assertColor(Color.Blue, image.pixel(5, 35), tolerance = 0.05f)
    }

    @Test
    fun sweepGradientStartsOnTheRightOfItsCenter() {
        val image = image(width = 40, height = 40) {
            drawRect(
                Rect(0f, 0f, 40f, 40f),
                Paint().apply {
                    shader = SweepGradientShader(
                        Offset(20f, 20f),
                        colors = listOf(Color.Red, Color.Blue),
                    )
                },
            )
        }
        // Clockwise from the positive x axis: red just below it, blue just above it.
        assertColor(Color.Red, image.pixel(38, 21), tolerance = 0.1f)
        assertColor(Color.Blue, image.pixel(38, 18), tolerance = 0.1f)
    }

    @Test
    fun gradientsKeepTheirGeometryWhenShared() {
        // Two shaders with the same colors share a canonical gradient: each keeps its own geometry.
        val image = image(width = 40, height = 20) {
            val colors = listOf(Color.Red, Color.Blue)
            drawRect(
                Rect(0f, 0f, 20f, 20f),
                Paint().apply { shader = LinearGradientShader(Offset(0f, 0f), Offset(20f, 0f), colors) },
            )
            drawRect(
                Rect(20f, 0f, 40f, 20f),
                Paint().apply { shader = LinearGradientShader(Offset(40f, 0f), Offset(20f, 0f), colors) },
            )
        }
        assertColor(Color.Red, image.pixel(0, 10), tolerance = 0.07f)
        assertColor(Color.Blue, image.pixel(19, 10), tolerance = 0.07f)
        assertColor(Color.Blue, image.pixel(20, 10), tolerance = 0.07f)
        assertColor(Color.Red, image.pixel(39, 10), tolerance = 0.07f)
    }

    @Test
    fun tintedImage() {
        val source = image(width = 10, height = 10) {
            drawRect(Rect(0f, 0f, 5f, 10f), Paint().apply { color = Color.Black })
        }
        val image = image(width = 10, height = 10) {
            drawImage(
                source,
                Offset.Zero,
                Paint().apply { colorFilter = ColorFilter.tint(Color.Green, BlendMode.SrcIn) },
            )
        }
        assertColor(Color.Green, image.pixel(2, 5))
        assertColor(Color.Transparent, image.pixel(8, 5))
    }

    @Test
    fun drawImageRectScales() {
        val source = image(width = 2, height = 2) {
            drawRect(Rect(0f, 0f, 2f, 2f), Paint().apply { color = Color.Red })
        }
        val image = image {
            drawImageRect(
                source,
                IntOffset.Zero,
                IntSize(2, 2),
                IntOffset(0, 0),
                IntSize(20, 20),
                Paint(),
            )
        }
        assertColor(Color.Red, image.pixel(10, 10))
    }

    @Test
    fun evenOddPathLeavesAHole() {
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, 20f, 20f))
            addRect(Rect(5f, 5f, 15f, 15f))
        }
        val image = image {
            drawPath(path, Paint().apply { color = Color.Red })
        }
        assertColor(Color.Red, image.pixel(2, 2))
        assertColor(Color.Transparent, image.pixel(10, 10))
    }

    @Test
    fun readPixelsReadsBack() {
        val image = image {
            drawRect(Rect(0f, 0f, 20f, 20f), Paint().apply { color = Color(0xFF336699) })
        }
        val buffer = IntArray(4)
        image.readPixels(buffer, startX = 1, startY = 1, width = 2, height = 2)
        for (pixel in buffer) {
            assertEquals(0xFF336699.toInt(), pixel)
        }
    }

    @Test
    fun dashPathEffectDashesStrokes() {
        val image = image(width = 40, height = 4) {
            drawLine(
                Offset(0f, 2f),
                Offset(40f, 2f),
                Paint().apply {
                    color = Color.Black
                    style = PaintingStyle.Stroke
                    strokeWidth = 4f
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                },
            )
        }
        assertColor(Color.Black, image.pixel(5, 2))
        assertColor(Color.Transparent, image.pixel(15, 2))
        assertColor(Color.Black, image.pixel(25, 2))
    }

    @Test
    fun cornerPathEffectRoundsCorners() {
        val image = image {
            drawRect(
                Rect(0f, 0f, 20f, 20f),
                Paint().apply {
                    color = Color.Black
                    pathEffect = PathEffect.cornerPathEffect(8f)
                },
            )
        }
        assertColor(Color.Transparent, image.pixel(0, 0))
        assertColor(Color.Black, image.pixel(10, 10))
    }

    @Test
    fun compositeShaderBlendsItsShaders() {
        // A frame, colored by a gradient: the gradient drawn into the frame (SrcIn).
        val frame = image(width = 20, height = 20) {
            drawRect(
                Rect(0f, 0f, 20f, 20f),
                Paint().apply {
                    color = Color.Black
                    style = PaintingStyle.Stroke
                    strokeWidth = 8f
                },
            )
        }
        val shader = CompositeShader(
            dst = ImageShader(frame),
            src = LinearGradientShader(Offset.Zero, Offset(20f, 0f), listOf(Color.Red, Color.Red)),
            blendMode = BlendMode.SrcIn,
        )
        val image = image {
            drawRect(Rect(0f, 0f, 20f, 20f), Paint().apply { this.shader = shader })
        }
        assertColor(Color.Red, image.pixel(1, 10))
        assertColor(Color.Transparent, image.pixel(10, 10))
    }

    @Test
    fun clampedImageShaderExtendsTheEdgePixels() {
        val source = image(width = 2, height = 2) {
            drawRect(Rect(0f, 0f, 1f, 2f), Paint().apply { color = Color.Red })
            drawRect(Rect(1f, 0f, 2f, 2f), Paint().apply { color = Color.Blue })
        }
        val image = image(width = 20, height = 6) {
            drawRect(
                Rect(0f, 0f, 20f, 6f),
                Paint().apply { shader = ImageShader(source, TileMode.Clamp, TileMode.Clamp) },
            )
        }
        assertColor(Color.Red, image.pixel(0, 1))
        assertColor(Color.Blue, image.pixel(15, 1))
        assertColor(Color.Blue, image.pixel(19, 5))
    }

    @Test
    fun colorFilterAppliesToShaders() {
        val image = image {
            drawRect(
                Rect(0f, 0f, 20f, 20f),
                Paint().apply {
                    shader = LinearGradientShader(
                        Offset.Zero,
                        Offset(20f, 0f),
                        listOf(Color.Red, Color.Red),
                    )
                    colorFilter = ColorFilter.tint(Color.Green, BlendMode.SrcIn)
                },
            )
        }
        assertColor(Color.Green, image.pixel(10, 10))
    }

    @Test
    fun vertexColorsAreInterpolatedOverTheTriangles() {
        if (!WebGLRenderer.isAvailable()) {
            println("WebGL 2 isn't available: skipping the vertex colors test")
            return
        }
        // A square, red on the left edge and blue on the right one: a horizontal gradient.
        val positions = listOf(
            Offset(0f, 0f), Offset(40f, 0f), Offset(0f, 40f),
            Offset(40f, 0f), Offset(40f, 40f), Offset(0f, 40f),
        )
        val vertices = Vertices(
            VertexMode.Triangles,
            positions = positions,
            textureCoordinates = positions,
            colors = listOf(Color.Red, Color.Blue, Color.Red, Color.Blue, Color.Blue, Color.Red),
            indices = emptyList(),
        )
        val image = image(width = 40, height = 40) {
            drawVertices(vertices, BlendMode.SrcOver, Paint())
        }
        assertColor(Color(0.5f, 0f, 0.5f), image.pixel(20, 20), tolerance = 0.05f)
        assertColor(Color.Red, image.pixel(0, 20), tolerance = 0.05f)
        assertColor(Color.Blue, image.pixel(39, 20), tolerance = 0.05f)
    }

    @Test
    fun layerRotatedAroundTheXAxisIsDrawnInPerspective() {
        if (!WebGLRenderer.isAvailable()) {
            println("WebGL 2 isn't available: skipping the perspective test")
            return
        }
        val context = androidx.compose.ui.webcanvas.WebCanvasGraphicsContext()
        val layer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(200, 200)) {
            drawRect(Color.Red, size = Size(200f, 200f))
        }
        layer.rotationX = 45f
        layer.cameraDistance = 2f
        val image = image(width = 200, height = 200) {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, this, Size(200f, 200f)) {
                drawLayer(layer)
            }
        }
        context.releaseGraphicsLayer(layer)
        // A rectangle tilted away from the viewer is a trapezoid: its rows have different widths.
        val pixels = image.toPixelMap()
        fun rowWidth(y: Int) = (0 until 200).count { pixels[it, y].alpha > 0.5f }
        val rows = (0 until 200).filter { rowWidth(it) > 0 }
        assertTrue(rows.size > 10, "rows: ${rows.size}")
        val top = rowWidth(rows.first() + 2)
        val bottom = rowWidth(rows.last() - 2)
        assertTrue(abs(top - bottom) > 20, "top row: $top, bottom row: $bottom")
    }

    @Test
    fun graphicsLayerRecordsAndDraws() {
        val context = androidx.compose.ui.webcanvas.WebCanvasGraphicsContext()
        val layer: GraphicsLayer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(10, 10)) {
            drawRect(Color.Red, size = Size(10f, 10f))
        }
        layer.topLeft = IntOffset(5, 5)
        layer.alpha = 0.5f
        val image = image {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, this, Size(20f, 20f)) {
                drawLayer(layer)
            }
        }
        assertColor(Color.Transparent, image.pixel(2, 2))
        assertColor(Color.Red.copy(alpha = 0.5f), image.pixel(10, 10))
        context.releaseGraphicsLayer(layer)
    }

    @Test
    fun recordedLayerReflectsLaterPropertyChanges() {
        val context = androidx.compose.ui.webcanvas.WebCanvasGraphicsContext()
        val layer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(10, 10)) {
            translate(2f, 2f) {
                drawRect(Color.Blue, size = Size(4f, 4f))
            }
        }
        layer.translationX = 10f
        val image = image {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, this, Size(20f, 20f)) {
                drawLayer(layer)
            }
        }
        assertColor(Color.Transparent, image.pixel(3, 3))
        assertColor(Color.Blue, image.pixel(13, 3))
        context.releaseGraphicsLayer(layer)
    }
}
