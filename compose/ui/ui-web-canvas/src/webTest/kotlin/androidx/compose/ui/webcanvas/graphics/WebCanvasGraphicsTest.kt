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
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositeShader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
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

    private fun assertColor(
        expected: Color,
        actual: Color,
        tolerance: Float = 0.02f,
        message: String? = null,
    ) {
        assertTrue(
            abs(expected.red - actual.red) <= tolerance &&
                abs(expected.green - actual.green) <= tolerance &&
                abs(expected.blue - actual.blue) <= tolerance &&
                abs(expected.alpha - actual.alpha) <= tolerance,
            "${message?.let { "$it: " } ?: ""}expected $expected but was $actual",
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
    fun canvasesDrawingIntoTheSameImageDontUseEachOthersState() {
        val image = ImageBitmap(30, 10)
        val first = Canvas(image)
        val second = Canvas(image)
        val red = Paint().apply { color = Color.Red }
        first.drawRect(Rect(0f, 0f, 10f, 10f), red)
        second.drawRect(Rect(10f, 0f, 20f, 10f), Paint().apply { color = Color.Blue })
        // The context was last set to blue, by the other canvas.
        first.drawRect(Rect(20f, 0f, 30f, 10f), red)
        assertColor(Color.Red, image.pixel(5, 5))
        assertColor(Color.Blue, image.pixel(15, 5))
        assertColor(Color.Red, image.pixel(25, 5))
    }

    @Test
    fun theStateSetWithinAClipIsUndoneWithIt() {
        val blue = Paint().apply { color = Color.Blue }
        val image = image(30, 10) {
            drawRect(Rect(0f, 0f, 10f, 10f), Paint().apply { color = Color.Red })
            save()
            clipPath(Path().apply { addOval(Rect(10f, 0f, 20f, 10f)) })
            drawRect(Rect(10f, 0f, 20f, 10f), blue)
            restore()
            // The context is red again, so blue is set again.
            drawRect(Rect(20f, 0f, 30f, 10f), blue)
        }
        assertColor(Color.Red, image.pixel(5, 5))
        assertColor(Color.Blue, image.pixel(15, 5))
        assertColor(Color.Blue, image.pixel(25, 5))
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
    fun aLayerIsCompositedWithItsPaintAsItWasWhenSaved() {
        val paint = Paint().apply { alpha = 0.5f }
        val image = image {
            saveLayer(Rect(0f, 0f, 20f, 20f), paint)
            // Like Skia, the layer keeps its paint: the paint is free to draw the content.
            paint.alpha = 1f
            paint.color = Color.Black
            drawRect(Rect(0f, 0f, 20f, 20f), paint)
            restore()
        }
        assertColor(Color.Black.copy(alpha = 0.5f), image.pixel(10, 10))
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
    fun gradientsWithoutLengthOrRadiusDrawAsSkiaDoes() {
        // A canvas gradient of no length paints nothing; Skia paints the last color when clamped,
        // the average of the colors when repeated, and nothing outside the gradient (decal).
        fun drawnWith(shader: Shader, paint: Paint = Paint()) = image {
            drawRect(Rect(0f, 0f, 20f, 20f), Paint().apply { color = Color.Red })
            drawRect(Rect(0f, 0f, 20f, 20f), paint.apply { this.shader = shader })
        }
        val colors = listOf(Color.Red, Color.Blue)
        val at = Offset(10f, 10f)
        assertColor(Color.Blue, drawnWith(LinearGradientShader(at, at, colors)).pixel(5, 5))
        assertColor(Color.Blue, drawnWith(RadialGradientShader(at, 0f, colors)).pixel(5, 5))
        // Shorter than Skia takes as a length.
        for (to in listOf(at, at + Offset(1e-5f, 0f))) {
            assertColor(
                Color(0.5f, 0f, 0.5f),
                drawnWith(LinearGradientShader(at, to, colors, tileMode = TileMode.Repeated)).pixel(5, 5),
                tolerance = 0.01f,
            )
        }
        // Nothing drawn, not even with a blend mode that a transparent color would clear with, or
        // through a color filter that would color it.
        val decal = RadialGradientShader(at, 0f, colors, tileMode = TileMode.Decal)
        assertColor(Color.Red, drawnWith(decal).pixel(5, 5))
        assertColor(Color.Red, drawnWith(decal, Paint().apply { blendMode = BlendMode.Src }).pixel(5, 5))
        assertColor(
            Color.Red,
            drawnWith(decal, Paint().apply { colorFilter = ColorFilter.tint(Color.Green) }).pixel(5, 5),
        )
        // A gradient of one color is that color.
        assertColor(
            Color.Blue,
            drawnWith(RadialGradientShader(at, 0f, listOf(Color.Blue), listOf(0f), TileMode.Decal)).pixel(5, 5),
        )
    }

    @Test
    fun gradientsInterpolateTheirColorsPremultipliedLikeSkia() {
        // Compose's Skia gradients interpolate premultiplied colors, so that a gradient to a
        // transparent color doesn't darken; canvas gradients interpolate them as they are.
        fun middleOf(
            colors: List<Color>,
            tileMode: TileMode = TileMode.Clamp,
            to: Float = 101f,
            stops: List<Float>? = null,
        ): Color {
            val image = image(width = 101, height = 1) {
                drawRect(
                    Rect(0f, 0f, 101f, 1f),
                    Paint().apply {
                        shader = LinearGradientShader(Offset.Zero, Offset(to, 0f), colors, stops, tileMode)
                    },
                )
            }
            return image.pixel(50, 0)
        }
        assertColor(Color.Red.copy(alpha = 0.5f), middleOf(listOf(Color.Red, Color.Transparent)))
        // Transparent stops on both sides of a color, in a reflection: the middle is 3/4 of the
        // reflected period, halfway from the color to transparent.
        assertColor(
            Color.Blue.copy(alpha = 0.5f),
            middleOf(listOf(Color.Transparent, Color.Blue, Color.Transparent), TileMode.Mirror, to = 40.4f),
        )
        // Two colors of different alphas: (1, 0, 0) * 1 and (0, 0, 1) * 0.2 averaged.
        assertColor(
            Color(0.5f / 0.6f, 0f, 0.1f / 0.6f, 0.6f),
            middleOf(listOf(Color.Red, Color.Blue.copy(alpha = 0.2f))),
        )
        // Stops out of order are clamped to the previous ones, the same in a reflection: green from
        // 0.8 (not 0.5) on. The middle is at 2 - 50.5 / 40 = 0.7375 of the reflected period, 0.92 of
        // the way from red to blue.
        assertColor(
            Color(0.08f, 0f, 0.92f),
            middleOf(listOf(Color.Red, Color.Blue, Color.Green), TileMode.Mirror, to = 40f, stops = listOf(0f, 0.8f, 0.5f)),
            tolerance = 0.03f,
        )
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
    fun anImageShaderDrawsItsImageAsItIsAtEveryDraw() {
        val source = image(width = 2, height = 2) { drawRect(Rect(0f, 0f, 2f, 2f), Paint().apply { color = Color.Red }) }
        fun paint(shader: Shader, colorFilter: ColorFilter? = null) = Paint().apply {
            this.shader = shader
            this.colorFilter = colorFilter
        }
        // A pattern of the image (repeated, or mirrored from a tile of its reflections), an image
        // of the shader (clamped past the image, or color filtered), and the shader composed with
        // another one.
        val paints = listOf(
            paint(ImageShader(source, TileMode.Repeated, TileMode.Repeated)),
            paint(ImageShader(source, TileMode.Mirror, TileMode.Mirror)),
            paint(ImageShader(source, TileMode.Clamp, TileMode.Clamp)),
            paint(
                ImageShader(source, TileMode.Repeated, TileMode.Repeated),
                ColorFilter.tint(Color.White, BlendMode.Modulate),
            ),
            paint(
                CompositeShader(
                    dst = ImageShader(source, TileMode.Repeated, TileMode.Repeated),
                    src = LinearGradientShader(Offset.Zero, Offset(20f, 0f), listOf(Color.Green, Color.Green)),
                    blendMode = BlendMode.Dst,
                ),
            ),
        )
        fun drawnWith(paint: Paint) = image { drawRect(Rect(0f, 0f, 20f, 20f), paint) }
        for (paint in paints) assertColor(Color.Red, drawnWith(paint).pixel(10, 10))
        Canvas(source).drawRect(Rect(0f, 0f, 2f, 2f), Paint().apply { color = Color.Blue })
        for (paint in paints) assertColor(Color.Blue, drawnWith(paint).pixel(10, 10))
    }

    @Test
    fun anImageShaderOfAnImageStillDecodingDrawsItOnceDecoded() {
        val decoded = image(width = 2, height = 2) { drawRect(Rect(0f, 0f, 2f, 2f), Paint().apply { color = Color.Red }) }
        val decoding = DecodedImageBitmap(width = 2, height = 2, hasAlpha = true)
        val shader = ImageShader(decoding, TileMode.Repeated, TileMode.Repeated)
        fun drawn() = image { drawRect(Rect(0f, 0f, 20f, 20f), Paint().apply { this.shader = shader }) }
        assertColor(Color.Transparent, drawn().pixel(10, 10))
        decoding.onDecoded((decoded as CanvasImageBitmap).canvas)
        assertColor(Color.Red, drawn().pixel(10, 10))
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
        assertWebGLAvailable()
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
    fun aMeshIsDrawnThroughTheTransformation() {
        assertWebGLAvailable()
        // A 40 x 40 red square, drawn at half its size from (10, 20).
        val positions = listOf(Offset(0f, 0f), Offset(40f, 0f), Offset(0f, 40f), Offset(40f, 40f))
        val vertices = Vertices(
            VertexMode.TriangleStrip,
            positions = positions,
            textureCoordinates = positions,
            colors = List(4) { Color.Red },
            indices = emptyList(),
        )
        val image = image(width = 40, height = 40) {
            translate(10f, 20f)
            scale(0.5f, 0.5f)
            drawVertices(vertices, BlendMode.SrcOver, Paint())
        }
        assertColor(Color.Red, image.pixel(20, 30))
        assertColor(Color.Transparent, image.pixel(5, 30))
        assertColor(Color.Transparent, image.pixel(20, 15))
        assertColor(Color.Transparent, image.pixel(35, 30))
    }

    @Test
    fun meshIndicesAbove32767ReachTheirVertices() {
        assertWebGLAvailable()
        // The corners of the image, clockwise from the top-left, as the last 4 of 40000 vertices:
        // Skia reads the indices as unsigned 16-bit numbers, which Vertices keeps as shorts,
        // negative past 32767.
        val corners = listOf(Offset(0f, 0f), Offset(40f, 0f), Offset(40f, 40f), Offset(0f, 40f))
        val first = 40_000 - corners.size
        val positions = List(40_000) { i -> if (i >= first) corners[i - first] else Offset.Zero }
        // The meshes of each mode, and pixels they cover, away from the edges of their triangles.
        val meshes = listOf(
            Triple(VertexMode.Triangles, listOf(0, 1, 2), listOf(IntOffset(30, 10))),
            Triple(VertexMode.TriangleStrip, listOf(0, 1, 3, 2), listOf(IntOffset(10, 10), IntOffset(30, 30))),
            Triple(VertexMode.TriangleFan, listOf(0, 1, 2, 3), listOf(IntOffset(30, 10), IntOffset(10, 30))),
        )
        // With a shader, the triangles are filled without WebGL.
        val shaded = Paint().apply {
            shader = LinearGradientShader(Offset.Zero, Offset(40f, 0f), listOf(Color.Red, Color.Red))
        }
        val paints = listOf(shaded, Paint())
        for ((mode, corner, covered) in meshes) {
            val vertices = Vertices(
                mode,
                positions = positions,
                textureCoordinates = positions,
                colors = List(positions.size) { Color.Red },
                indices = corner.map { first + it },
            )
            for (paint in paints) {
                val image = image(width = 40, height = 40) { drawVertices(vertices, BlendMode.SrcOver, paint) }
                for (pixel in covered) {
                    assertEquals(1f, image.pixel(pixel.x, pixel.y).alpha, "$mode at $pixel, shader ${paint.shader}")
                }
            }
        }
    }

    /** Fails a test of drawing with WebGL without it: every browser the tests run in supports it. */
    private fun assertWebGLAvailable() = assertTrue(WebGLRenderer.isAvailable(), "WebGL 2 isn't available")

    /** A triangle over the top-left half of a 40 x 40 image, of the vertex color [color]. */
    private fun halfTriangle(color: Color): Vertices {
        val positions = listOf(Offset(0f, 0f), Offset(40f, 0f), Offset(0f, 40f))
        return Vertices(VertexMode.Triangles, positions, positions, List(3) { color }, emptyList())
    }

    /**
     * The ways a mesh of [vertexColor] is drawn, each with a paint of [paintColor] (whose alpha
     * applies to the vertex colors) to set up, and the color it shades the mesh with: its vertex
     * colors interpolated with WebGL, and its triangles filled with the paint color (with a shader,
     * which meshes don't support).
     */
    private fun meshDraws(vertexColor: Color, paintColor: Color): List<Triple<String, Paint, Color>> =
        buildList {
            assertWebGLAvailable()
            val shading = vertexColor.copy(alpha = vertexColor.alpha * paintColor.alpha)
            add(Triple("vertex colors", Paint().apply { color = paintColor }, shading))
            val shaded = Paint().apply {
                color = paintColor
                shader = LinearGradientShader(Offset.Zero, Offset(40f, 0f), listOf(Color.Red, Color.Red))
            }
            add(Triple("triangles", shaded, paintColor))
        }

    @Test
    fun colorFiltersApplyToMeshes() {
        val filters = listOf(
            ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        0.3f, 0.6f, 0.1f, 0f, 20f,
                        0.2f, 0.5f, 0.3f, 0f, -10f,
                        0.1f, 0.1f, 0.8f, 0f, 5f,
                        0f, 0f, 0f, 0.9f, 0f,
                    )
                )
            ),
            ColorFilter.lighting(multiply = Color(0xFF80C040), add = Color(0xFF102030)),
            ColorFilter.tint(Color(0xC040A0FF), BlendMode.Modulate),
            ColorFilter.tint(Color.Green, BlendMode.SrcIn),
            // Opaque over the colors, it would paint the transparent pixels around the mesh too.
            ColorFilter.tint(Color.Blue, BlendMode.SrcOver),
            ColorFilter.tint(Color(0xC040A0FF), BlendMode.Multiply),
            ColorFilter.tint(Color(0xFFC04020), BlendMode.Hue),
            ColorFilter.tint(Color(0xFFC04020), BlendMode.Luminosity),
        )
        for (colorFilter in filters) {
            val filter = colorFilter.webColorFilter
            for ((what, paint, shading) in meshDraws(Color(0xFF336699), Color(0xC0C04020))) {
                paint.colorFilter = colorFilter
                val pixels = image(width = 40, height = 40) {
                    drawVertices(halfTriangle(Color(0xFF336699)), BlendMode.SrcOver, paint)
                }.imageData()
                val expected = Color(filter.filter(shading.toArgb()))
                assertColor(expected, Color(argbAt(pixels, 10, 10)), message = "$what through $colorFilter")
                assertEquals(0, argbAt(pixels, 35, 35), "$what through $colorFilter, outside the mesh")
            }
        }
    }

    @Test
    fun meshesTakeThePaintAlphaBeforeTheirColorFilter() {
        // Adds a quarter of opacity: to half an opacity (Skia's order), or to a full one.
        val filter = ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 0f, 1f, 64f,
                )
            )
        )
        for ((what, paint, _) in meshDraws(Color.Red, Color.Red.copy(alpha = 0.5f))) {
            paint.colorFilter = filter
            val pixels = image(width = 40, height = 40) {
                drawVertices(halfTriangle(Color.Red), BlendMode.SrcOver, paint)
            }.imageData()
            assertColor(Color.Red.copy(alpha = 0.75f), Color(argbAt(pixels, 10, 10)), message = what)
        }
    }

    @Test
    fun aMeshClearsItsPixelsFully() {
        val halvingAlpha = ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 0f, 0.5f, 0f,
                )
            )
        )
        for (half in listOf("paint alpha", "color filter")) {
            val paintColor = if (half == "paint alpha") Color.Red.copy(alpha = 0.5f) else Color.Red
            for ((what, paint, _) in meshDraws(Color.Red, paintColor)) {
                paint.blendMode = BlendMode.Clear
                if (half == "color filter") paint.colorFilter = halvingAlpha
                val pixels = image(width = 40, height = 40) {
                    drawRect(Rect(0f, 0f, 40f, 40f), Paint().apply { color = Color.Blue })
                    drawVertices(halfTriangle(Color.Red), BlendMode.SrcOver, paint)
                }.imageData()
                assertEquals(0, argbAt(pixels, 10, 10), "$what with half the $half")
                assertColor(Color.Blue, Color(argbAt(pixels, 35, 35)), message = "$what with half the $half")
            }
        }
    }

    @Test
    fun aMeshDrawnWithDstDrawsNothing() {
        for ((what, paint, _) in meshDraws(Color.Red, Color.Red)) {
            paint.blendMode = BlendMode.Dst
            val pixels = image(width = 40, height = 40) {
                drawRect(Rect(0f, 0f, 40f, 40f), Paint().apply { color = Color.Blue })
                drawVertices(halfTriangle(Color.Red), BlendMode.SrcOver, paint)
            }.imageData()
            assertColor(Color.Blue, Color(argbAt(pixels, 10, 10)), message = what)
        }
    }

    @Test
    fun unboundedBlendModesOfMeshesKeepThePixelsOutsideThem() {
        // Translucent, so that every mode changes the pixels of the mesh.
        val destination = Color.Blue.copy(alpha = 0.5f)
        for (blendMode in listOf(BlendMode.Src, BlendMode.SrcIn, BlendMode.DstIn, BlendMode.SrcOut, BlendMode.DstAtop)) {
            for ((what, paint, shading) in meshDraws(Color.Red, Color.Red.copy(alpha = 0.5f))) {
                paint.blendMode = blendMode
                val pixels = image(width = 40, height = 40) {
                    drawRect(Rect(0f, 0f, 40f, 40f), Paint().apply { color = destination })
                    drawVertices(halfTriangle(Color.Red), BlendMode.SrcOver, paint)
                }.imageData()
                val inside = Color(blendColors(shading.toArgb(), destination.toArgb(), blendMode))
                assertColor(inside, Color(argbAt(pixels, 10, 10)), message = "$what in $blendMode")
                assertColor(destination, Color(argbAt(pixels, 35, 35)), message = "$what in $blendMode, outside")
            }
        }
    }

    @Test
    fun aMeshIsClippedToTheUnionOfItsTriangles() {
        assertWebGLAvailable()
        // The same triangle twice, turning both ways: as a path, the two would cancel each other.
        val positions = listOf(
            Offset(0f, 0f), Offset(40f, 0f), Offset(0f, 40f),
            Offset(0f, 0f), Offset(0f, 40f), Offset(40f, 0f),
        )
        val vertices = Vertices(VertexMode.Triangles, positions, positions, List(6) { Color.Red }, emptyList())
        val pixels = image(width = 40, height = 40) {
            drawRect(Rect(0f, 0f, 40f, 40f), Paint().apply { color = Color.Blue })
            drawVertices(vertices, BlendMode.SrcOver, Paint().apply { blendMode = BlendMode.SrcIn })
        }.imageData()
        assertColor(Color.Red, Color(argbAt(pixels, 10, 10)), message = "inside")
        assertColor(Color.Blue, Color(argbAt(pixels, 35, 35)), message = "outside")
    }

    @Test
    fun pointsInAnUnboundedModeKeepThePixelsOutsideThem() {
        for (cap in listOf(StrokeCap.Butt, StrokeCap.Round)) {
            val pixels = image(width = 40, height = 40) {
                drawRect(Rect(0f, 0f, 40f, 40f), Paint().apply { color = Color.Blue })
                drawPoints(
                    PointMode.Points,
                    listOf(Offset(10f, 20f), Offset(30f, 20f)),
                    Paint().apply {
                        color = Color.Red
                        strokeWidth = 8f
                        strokeCap = cap
                        blendMode = BlendMode.SrcIn
                    },
                )
            }.imageData()
            assertColor(Color.Red, Color(argbAt(pixels, 10, 20)), message = "$cap, a point")
            assertColor(Color.Blue, Color(argbAt(pixels, 20, 35)), message = "$cap, away")
        }
    }

    @Test
    fun aHueTintBlendsTheHueOfTheTintWithTheRestOfTheColor() {
        // W3C: SetLum(SetSat(red, Sat(c)), Lum(c)) for c = (0.2, 0.4, 0.6) is (0.642, 0.242, 0.242).
        val pixels = image {
            drawRect(
                Rect(0f, 0f, 20f, 20f),
                Paint().apply {
                    color = Color(0xFF336699)
                    colorFilter = ColorFilter.tint(Color.Red, BlendMode.Hue)
                },
            )
        }.imageData()
        assertEquals(0xFFA43E3E.toInt(), argbAt(pixels, 10, 10))
    }

    @Test
    fun nonSeparableTintsOfColorsMatchTheBrowser() {
        val colors = listOf(Color(0xFF336699), Color(0xC0C04020), Color(0xFF20E040))
        val source = image(width = 30, height = 10) {
            colors.forEachIndexed { i, color ->
                drawRect(Rect(i * 10f, 0f, i * 10f + 10f, 10f), Paint().apply { this.color = color })
            }
        }
        for (blendMode in listOf(BlendMode.Hue, BlendMode.Saturation, BlendMode.Color, BlendMode.Luminosity)) {
            val tint = ColorFilter.tint(Color(0xD08030C0), blendMode)
            // An image is tinted by the browser, with its compositing operation of the blend mode.
            val tintedImage = image(width = 30, height = 10) {
                drawImage(source, Offset.Zero, Paint().apply { colorFilter = tint })
            }.imageData()
            colors.forEachIndexed { i, color ->
                val tintedColor = image(width = 10, height = 10) {
                    drawRect(Rect(0f, 0f, 10f, 10f), Paint().apply {
                        this.color = color
                        colorFilter = tint
                    })
                }.imageData()
                assertColor(
                    Color(argbAt(tintedImage, i * 10 + 5, 5)),
                    Color(argbAt(tintedColor, 5, 5)),
                    message = "$color through $blendMode",
                )
            }
        }
    }

    @Test
    fun layerRotatedAroundTheXAxisIsDrawnInPerspective() {
        assertWebGLAvailable()
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
    fun aLayerInPerspectiveInAnUnboundedModeKeepsThePixelsOutsideIt() {
        assertWebGLAvailable()
        val context = androidx.compose.ui.webcanvas.WebCanvasGraphicsContext()
        val layer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(100, 100)) {
            drawRect(Color.Red, size = Size(100f, 100f))
        }
        layer.topLeft = IntOffset(50, 50)
        layer.rotationX = 45f
        layer.cameraDistance = 2f
        layer.blendMode = BlendMode.SrcIn
        val pixels = image(width = 200, height = 200) {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, this, Size(200f, 200f)) {
                drawRect(Color.Blue)
                drawLayer(layer)
            }
        }.imageData()
        context.releaseGraphicsLayer(layer)
        // The layer composites within its bounds, the corners being outside them.
        assertColor(Color.Red, Color(argbAt(pixels, 100, 100)), message = "center")
        assertColor(Color.Blue, Color(argbAt(pixels, 5, 5)), message = "top-left corner")
        assertColor(Color.Blue, Color(argbAt(pixels, 195, 195)), message = "bottom-right corner")
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
