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
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.OffsetEffect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.webcanvas.WebCanvasGraphicsContext
import androidx.compose.ui.webcanvas.blendRenderEffect
import androidx.compose.ui.webcanvas.colorFilterRenderEffect
import androidx.compose.ui.webcanvas.dom.CanvasElement
import androidx.compose.ui.webcanvas.dom.CanvasFeatures
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.CssColors
import androidx.compose.ui.webcanvas.dom.ImageData
import androidx.compose.ui.webcanvas.dom.JsStrings
import androidx.compose.ui.webcanvas.dom.createCanvas
import androidx.compose.ui.webcanvas.dom.createElementCanvas
import androidx.compose.ui.webcanvas.dom.getContext2D
import androidx.compose.ui.webcanvas.dom.requireContext2D
import androidx.compose.ui.webcanvas.shaderRenderEffect
import kotlin.js.js
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Color filters and render effects of layers, which are applied when the layer is composited. */
class LayerEffectsTest {
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

    /** Colors to filter: primaries, a mid tone and a translucent one. */
    private val colors = listOf(
        Color(0xFFFF0000),
        Color(0xFF00FF00),
        Color(0xFF336699),
        Color(0x80C04020),
    )

    /** Draws [colors] side by side in a layer with [filter] and checks them against the CPU. */
    private fun assertLayerFilterMatchesTheCpu(filter: ColorFilter) {
        val image = image(width = 40, height = 10) {
            saveLayer(Rect(0f, 0f, 40f, 10f), Paint().apply { colorFilter = filter })
            drawColors()
            restore()
        }
        colors.forEachIndexed { i, color ->
            val expected = Color(filter.webColorFilter.filter(color.toArgb()))
            assertColor(expected, image.pixel(i * 10 + 5, 5))
        }
    }

    @Test
    fun svgColorFiltersApplyWhereverTheCanvasAppliesFilters() {
        // Without `ctx.filter` (Safari), color filters read the pixels back instead.
        assertEquals(CanvasFeatures.filter, SvgColorFilters.isSupported, "SVG color filters aren't applied")
    }

    @Test
    fun colorMatrixOnALayerMatchesTheCpu() {
        assertLayerFilterMatchesTheCpu(
            ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        0.3f, 0.6f, 0.1f, 0f, 20f,
                        0.2f, 0.5f, 0.3f, 0f, -10f,
                        0.1f, 0.1f, 0.8f, 0f, 5f,
                        0f, 0f, 0f, 0.9f, 0f,
                    )
                )
            )
        )
    }

    @Test
    fun saturationOnALayerMatchesTheCpu() {
        assertLayerFilterMatchesTheCpu(
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.2f) })
        )
    }

    @Test
    fun lightingOnALayerMatchesTheCpu() {
        assertLayerFilterMatchesTheCpu(
            ColorFilter.lighting(multiply = Color(0xFF80C040), add = Color(0xFF102030))
        )
    }

    @Test
    fun modulateTintOnALayerMatchesTheCpu() {
        assertLayerFilterMatchesTheCpu(ColorFilter.tint(Color(0xC040A0FF), BlendMode.Modulate))
    }

    @Test
    fun colorMatrixOnAnImageMatchesTheCpu() {
        val filter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        val source = image(width = 40, height = 10) { drawColors() }
        val image = image(width = 40, height = 10) {
            drawImage(source, Offset.Zero, Paint().apply { colorFilter = filter })
        }
        colors.forEachIndexed { i, color ->
            val expected = Color(filter.webColorFilter.filter(color.toArgb()))
            assertColor(expected, image.pixel(i * 10 + 5, 5))
        }
    }

    @Test
    fun colorFiltersThroughThePixelsMatchTheCpu() {
        // The filters that no compositing operation applies, which go through the pixels where the
        // browser has no SVG filters.
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
            ColorFilter.tint(Color(0xC040A0FF), BlendMode.Clear),
        )
        for (colorFilter in filters) {
            val filter = colorFilter.webColorFilter
            val ctx = context2D(createCanvas(40, 10))
            colors.forEachIndexed { i, color ->
                ctx.fillStyle = CssColors.of(color.toArgb())
                ctx.fillRect(i * 10.0, 0.0, 10.0, 10.0)
            }
            applyColorFilterInPlace(ctx, 0, 0, 40, 10, filter)
            val pixels = ctx.getImageData(0, 0, 40, 10)
            colors.forEachIndexed { i, color ->
                assertColor(Color(filter.filter(color.toArgb())), Color(argbAt(pixels, i * 10 + 5, 5)))
            }
        }
    }

    /** The tints Skia makes no filter for (`SkColorFilters::Blend` returns none). */
    private val tintsSkiaMakesNoFilterFor = listOf(
        Color.Blue to BlendMode.Dst,
        Color.Transparent to BlendMode.Dst,
        Color.Transparent to BlendMode.SrcOver,
        Color.Transparent to BlendMode.DstOver,
        Color.Transparent to BlendMode.DstOut,
        Color.Transparent to BlendMode.SrcAtop,
        Color.Transparent to BlendMode.Xor,
        Color.Transparent to BlendMode.Darken,
        Color.Blue to BlendMode.DstIn,
    )

    private fun Canvas.drawColors() {
        colors.forEachIndexed { i, color ->
            drawRect(Rect(i * 10f, 0f, i * 10f + 10f, 10f), Paint().apply { this.color = color })
        }
    }

    private fun gradient(): Shader =
        LinearGradientShader(Offset(0f, 0f), Offset(40f, 0f), listOf(Color.Red, Color(0x400000FF)))

    @Test
    fun tintsSkiaMakesNoFilterForDrawLikeNoFilterWithoutReadingPixels() {
        val source = image(width = 40, height = 10) { drawColors() }
        // Each way of applying a color filter.
        val draws = listOf<Pair<String, Canvas.(ColorFilter?) -> Unit>>(
            "a color" to { filter ->
                drawRect(
                    Rect(0f, 0f, 40f, 10f),
                    Paint().apply {
                        color = Color(0x80C04020)
                        colorFilter = filter
                    },
                )
            },
            "an image" to { filter ->
                drawImage(source, Offset.Zero, Paint().apply { colorFilter = filter })
            },
            "a layer" to { filter ->
                saveLayer(Rect(0f, 0f, 40f, 10f), Paint().apply { colorFilter = filter })
                drawColors()
                restore()
            },
            "a gradient" to { filter ->
                drawRect(
                    Rect(0f, 0f, 40f, 10f),
                    Paint().apply {
                        shader = gradient()
                        colorFilter = filter
                    },
                )
            },
        )
        for ((color, blendMode) in tintsSkiaMakesNoFilterFor) {
            val tint = ColorFilter.tint(color, blendMode)
            for ((what, draw) in draws) {
                val filtered = ImageBitmap(40, 10)
                val reads = contextCallsDuring(listOf("getImageData")) { Canvas(filtered).draw(tint) }.names.size
                val unfiltered = image(width = 40, height = 10) { draw(null) }
                val difference = maxPremultipliedDifference(unfiltered.imageData(), filtered.imageData())
                assertTrue(
                    reads == 0 && difference == 0,
                    "$what through tint($color, $blendMode): $reads pixel reads, pixels off by $difference",
                )
            }
        }
    }

    @Test
    fun tintsSkiaMakesNoFilterForLeaveAGradientDrawnAsAGradient() {
        for ((color, blendMode) in tintsSkiaMakesNoFilterFor) {
            val shader = gradient()
            image(width = 40, height = 10) {
                drawRect(
                    Rect(0f, 0f, 40f, 10f),
                    Paint().apply {
                        this.shader = shader
                        colorFilter = ColorFilter.tint(color, blendMode)
                    },
                )
            }
            assertNull(shader.webShader.raster, "tint($color, $blendMode) made an image of the gradient")
        }
    }

    @Test
    fun aLayerWithATintSkiaMakesNoFilterForIsCompositedAsAGroup() {
        // Like upstream, a color filter makes a layer a group, which its alpha applies to at once.
        fun draw(strategy: CompositingStrategy, filter: ColorFilter?): ImageBitmap =
            drawLayerImage(
                width = 40,
                height = 10,
                layerSize = IntSize(40, 10),
                topLeft = IntOffset.Zero,
                setUp = {
                    compositingStrategy = strategy
                    alpha = 0.5f
                    colorFilter = filter
                },
            ) {
                drawRect(Color.Red, size = Size(30f, 10f))
                drawRect(Color.Blue, topLeft = Offset(10f, 0f), size = Size(30f, 10f))
            }
        val group = draw(CompositingStrategy.Offscreen, null).imageData()
        val modulated = draw(CompositingStrategy.ModulateAlpha, null).imageData()
        val tinted = draw(CompositingStrategy.ModulateAlpha, ColorFilter.tint(Color.Blue, BlendMode.Dst))
        assertTrue(maxPremultipliedDifference(group, modulated) > 0, "the overlap shows nothing")
        assertEquals(0, maxPremultipliedDifference(group, tinted.imageData()))
    }

    @Test
    fun colorMatrixMakingTransparentPixelsOpaqueStaysInsideTheLayer() {
        // Every pixel, transparent ones included, becomes opaque green.
        val filter = ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 255f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 255f,
                )
            )
        )
        val image = image {
            saveLayer(Rect(5f, 5f, 15f, 15f), Paint().apply { colorFilter = filter })
            restore()
        }
        assertColor(Color.Green, image.pixel(10, 10))
        assertColor(Color.Transparent, image.pixel(2, 10))
        assertColor(Color.Transparent, image.pixel(17, 17))
    }

    @Test
    fun animatedColorMatricesAllApply() {
        // More matrices than the filters kept in the page, so that filters get reused.
        for (step in 0 until 40) {
            val level = step / 39f
            val filter = ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        level, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
            val image = image(width = 4, height = 4) {
                saveLayer(Rect(0f, 0f, 4f, 4f), Paint().apply { colorFilter = filter })
                drawRect(Rect(0f, 0f, 4f, 4f), Paint().apply { color = Color.White })
                restore()
            }
            assertColor(Color(level, 0f, 0f), image.pixel(2, 2), tolerance = 0.01f)
        }
    }

    private fun drawLayerImage(
        width: Int,
        height: Int,
        layerSize: IntSize,
        topLeft: IntOffset,
        effect: RenderEffect? = null,
        setUp: GraphicsLayer.() -> Unit = {},
        around: DrawScope.(drawTheLayer: DrawScope.() -> Unit) -> Unit = { it() },
        content: DrawScope.() -> Unit,
    ): ImageBitmap {
        val context = WebCanvasGraphicsContext()
        val layer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, layerSize, content)
        layer.topLeft = topLeft
        layer.renderEffect = effect
        layer.setUp()
        val image = image(width, height) {
            CanvasDrawScope().draw(
                Density(1f),
                LayoutDirection.Ltr,
                this,
                Size(width.toFloat(), height.toFloat()),
            ) {
                around { drawLayer(layer) }
            }
        }
        context.releaseGraphicsLayer(layer)
        return image
    }

    /** Red on the left half of a line of [length] from the origin, and blue on the other. */
    private fun halves(length: Float): Shader = LinearGradientShader(
        from = Offset.Zero,
        to = Offset(length, 0f),
        colors = listOf(Color.Red, Color.Red, Color.Blue, Color.Blue),
        colorStops = listOf(0f, 0.5f, 0.5f, 1f),
    )

    /** Opaque on the left half of a line of [length] from the origin, and transparent on the other. */
    private fun halfMask(length: Float): Shader = LinearGradientShader(
        from = Offset.Zero,
        to = Offset(length, 0f),
        colors = listOf(Color.Black, Color.Black, Color.Transparent, Color.Transparent),
        colorStops = listOf(0f, 0.5f, 0.5f, 1f),
    )

    @Test
    fun aShaderEffectFillsTheLayerInTheSpaceOfItsContent() {
        val image = drawLayerImage(
            width = 60,
            height = 20,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset(10, 0),
            effect = shaderRenderEffect(halves(40f)),
        ) {
            drawRect(Color.Green, size = Size(10f, 10f))
        }
        assertColor(Color.Transparent, image.pixel(5, 10))
        assertColor(Color.Red, image.pixel(15, 10))
        assertColor(Color.Red, image.pixel(28, 10))
        assertColor(Color.Blue, image.pixel(32, 10))
        assertColor(Color.Blue, image.pixel(45, 10))
        assertColor(Color.Transparent, image.pixel(55, 10))
    }

    @Test
    fun aShaderEffectStaysInPlaceWhenAClipCutsTheLayer() {
        val image = drawLayerImage(
            width = 60,
            height = 20,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset(10, 0),
            effect = shaderRenderEffect(halves(40f)),
            around = { drawTheLayer -> clipRect(left = 25f) { drawTheLayer() } },
        ) {
            drawRect(Color.Green, size = Size(10f, 10f))
        }
        assertColor(Color.Transparent, image.pixel(20, 10))
        assertColor(Color.Red, image.pixel(28, 10))
        assertColor(Color.Blue, image.pixel(32, 10))
    }

    @Test
    fun aShaderEffectFollowsTheScaleOfTheCanvas() {
        val image = drawLayerImage(
            width = 80,
            height = 40,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset.Zero,
            effect = shaderRenderEffect(halves(40f)),
            around = { drawTheLayer -> scale(2f, pivot = Offset.Zero) { drawTheLayer() } },
        ) {
            drawRect(Color.Green, size = Size(10f, 10f))
        }
        assertColor(Color.Red, image.pixel(36, 20))
        assertColor(Color.Blue, image.pixel(44, 20))
    }

    @Test
    fun aTintEffectAppliesToTheLayer() {
        val image = drawLayerImage(
            width = 20,
            height = 20,
            layerSize = IntSize(20, 20),
            topLeft = IntOffset.Zero,
            effect = colorFilterRenderEffect(ColorFilter.tint(Color.Green, BlendMode.SrcIn), null),
        ) {
            drawRect(Color.Red, size = Size(10f, 20f))
        }
        assertColor(Color.Green, image.pixel(5, 10))
        assertColor(Color.Transparent, image.pixel(15, 10))
    }

    @Test
    fun aColorMatrixEffectAppliesToTheResultOfItsInput() {
        val halfAlpha = ColorMatrix().apply { this[3, 3] = 0.5f }
        val image = drawLayerImage(
            width = 20,
            height = 20,
            layerSize = IntSize(20, 20),
            topLeft = IntOffset.Zero,
            effect = colorFilterRenderEffect(
                ColorFilter.colorMatrix(halfAlpha),
                OffsetEffect(10f, 0f),
            ),
        ) {
            drawRect(Color.Red, size = Size(10f, 20f))
        }
        assertColor(Color.Transparent, image.pixel(5, 10))
        assertColor(Color.Red.copy(alpha = 0.5f), image.pixel(15, 10))
    }

    @Test
    fun aBlendEffectDrawsTheLayerOverItsBackground() {
        val image = drawLayerImage(
            width = 40,
            height = 20,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset.Zero,
            effect = blendRenderEffect(BlendMode.SrcOver, shaderRenderEffect(halves(40f)), null),
        ) {
            drawRect(Color.Green, size = Size(10f, 20f))
        }
        assertColor(Color.Green, image.pixel(5, 10))
        assertColor(Color.Red, image.pixel(15, 10))
        assertColor(Color.Blue, image.pixel(30, 10))
    }

    @Test
    fun aBlendEffectMasksTheLayerWithAShader() {
        val image = drawLayerImage(
            width = 40,
            height = 20,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset.Zero,
            effect = blendRenderEffect(BlendMode.DstIn, null, shaderRenderEffect(halfMask(40f))),
        ) {
            drawRect(Color.Green, size = Size(40f, 20f))
        }
        assertColor(Color.Green, image.pixel(10, 10))
        assertColor(Color.Transparent, image.pixel(30, 10))
    }

    @Test
    fun aMovedMaskCoversAllOfTheLayer() {
        // A shader has no edges, so nothing of the layer stays unmasked where it moves away from.
        val transparentToTheLeft = LinearGradientShader(
            from = Offset.Zero,
            to = Offset(20f, 0f),
            colors = listOf(Color.Transparent, Color.Transparent, Color.Black, Color.Black),
            colorStops = listOf(0f, 0.5f, 0.5f, 1f),
        )
        val image = drawLayerImage(
            width = 40,
            height = 20,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset.Zero,
            effect = blendRenderEffect(
                BlendMode.DstIn,
                null,
                OffsetEffect(shaderRenderEffect(transparentToTheLeft), Offset(10f, 0f)),
            ),
        ) {
            drawRect(Color.Green, size = Size(40f, 20f))
        }
        assertColor(Color.Transparent, image.pixel(5, 10))
        assertColor(Color.Transparent, image.pixel(15, 10))
        assertColor(Color.Green, image.pixel(25, 10))
    }

    @Test
    fun softLightWithMiddleGrayLeavesTheBackgroundAsItIs() {
        val gray = LinearGradientShader(
            from = Offset.Zero,
            to = Offset(1f, 0f),
            colors = listOf(Color(0xFF808080), Color(0xFF808080)),
        )
        val image = drawLayerImage(
            width = 20,
            height = 20,
            layerSize = IntSize(20, 20),
            topLeft = IntOffset.Zero,
            effect = blendRenderEffect(BlendMode.Softlight, null, shaderRenderEffect(gray)),
        ) {
            drawRect(Color(0xFF336699), size = Size(20f, 20f))
        }
        assertColor(Color(0xFF336699), image.pixel(10, 10))
    }

    @Test
    fun aBlurOfABlendBlursItsResult() {
        val image = drawLayerImage(
            width = 40,
            height = 20,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset.Zero,
            effect = BlurEffect(
                blendRenderEffect(
                    BlendMode.SrcOver,
                    shaderRenderEffect(halves(40f)),
                    OffsetEffect(0f, 0f),
                ),
                4f,
                4f,
                TileMode.Clamp,
            ),
        ) {}
        val middle = image.pixel(20, 10)
        assertTrue(middle.red > 0.2f && middle.blue > 0.2f, "not blurred: $middle")
        assertColor(Color.Red, image.pixel(2, 10), tolerance = 0.05f)
        assertColor(Color.Blue, image.pixel(37, 10), tolerance = 0.05f)
    }

    @Test
    fun aTintThatSkiaMakesNoFilterForLeavesTheLayerAsItIs() {
        val image = drawLayerImage(
            width = 20,
            height = 20,
            layerSize = IntSize(20, 20),
            topLeft = IntOffset.Zero,
            effect = colorFilterRenderEffect(ColorFilter.tint(Color.Green, BlendMode.Dst), null),
        ) {
            drawRect(Color.Red, size = Size(10f, 20f))
        }
        assertColor(Color.Red, image.pixel(5, 10))
        assertColor(Color.Transparent, image.pixel(15, 10))
    }

    @Test
    fun aBlendInDstKeepsTheBackground() {
        val image = drawLayerImage(
            width = 40,
            height = 20,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset.Zero,
            effect = blendRenderEffect(BlendMode.Dst, shaderRenderEffect(halves(40f)), null),
        ) {
            drawRect(Color.Green, size = Size(10f, 20f))
        }
        assertColor(Color.Red, image.pixel(5, 10))
        assertColor(Color.Blue, image.pixel(30, 10))
    }

    @Test
    fun aBlendInClearLeavesNothing() {
        val image = drawLayerImage(
            width = 40,
            height = 20,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset.Zero,
            effect = blendRenderEffect(BlendMode.Clear, shaderRenderEffect(halves(40f)), null),
        ) {
            drawRect(Color.Green, size = Size(10f, 20f))
        }
        assertColor(Color.Transparent, image.pixel(5, 10))
        assertColor(Color.Transparent, image.pixel(30, 10))
    }

    @Test
    fun aMaskDrawnInSeveralPartsMasksTheLayerAsOneImage() {
        // Clamped past the image, which takes a draw per edge: each would mask the ones before it.
        val mask = ImageShader(
            image(10, 20) { drawRect(Rect(0f, 0f, 10f, 20f), Paint()) },
            TileMode.Clamp,
            TileMode.Clamp,
        )
        val image = drawLayerImage(
            width = 40,
            height = 20,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset.Zero,
            effect = blendRenderEffect(BlendMode.DstIn, null, shaderRenderEffect(mask)),
        ) {
            drawRect(Color.Green, size = Size(40f, 20f))
        }
        assertColor(Color.Green, image.pixel(5, 10))
        assertColor(Color.Green, image.pixel(30, 10))
    }

    @Test
    fun aMaskThatCoversTheLayerMasksItInOneDraw() {
        // Past the image, past the canvas the mask is drawn into: the clamped edges a single fill
        // style has no need for would mask what the image masked.
        val mask = ImageShader(
            image(60, 20) { drawRect(Rect(0f, 0f, 30f, 20f), Paint()) },
            TileMode.Clamp,
            TileMode.Clamp,
        )
        val image = drawLayerImage(
            width = 80,
            height = 20,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset.Zero,
            effect = blendRenderEffect(BlendMode.DstIn, null, shaderRenderEffect(mask)),
        ) {
            drawRect(Color.Green, size = Size(40f, 20f))
        }
        assertColor(Color.Green, image.pixel(10, 10))
        assertColor(Color.Transparent, image.pixel(35, 10))
    }

    @Test
    fun anOffsetOfAShaderTurnsWithTheCanvas() {
        // Turned by 90 degrees, the x axis of the content goes down: the halves meet at y = 20, and
        // 10 further along it.
        val image = drawLayerImage(
            width = 40,
            height = 40,
            layerSize = IntSize(40, 40),
            topLeft = IntOffset.Zero,
            effect = OffsetEffect(shaderRenderEffect(halves(40f)), Offset(10f, 0f)),
            around = { drawTheLayer -> rotate(90f, pivot = Offset(20f, 20f)) { drawTheLayer() } },
        ) {}
        assertColor(Color.Red, image.pixel(20, 25))
        assertColor(Color.Blue, image.pixel(20, 35))
    }

    @Test
    fun aShaderMovedBackStillCoversTheLayer() {
        val image = drawLayerImage(
            width = 80,
            height = 20,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset(30, 0),
            effect = OffsetEffect(shaderRenderEffect(halves(4f)), Offset(-10f, 0f)),
        ) {}
        assertColor(Color.Blue, image.pixel(35, 10))
        assertColor(Color.Blue, image.pixel(68, 10))
        assertColor(Color.Transparent, image.pixel(72, 10))
    }

    @Test
    fun thePartsOfAnEffectShareTheImageTheyWorkIn() {
        val noise = colorFilterRenderEffect(
            ColorFilter.tint(Color.Black.copy(alpha = 0.5f), BlendMode.DstIn),
            shaderRenderEffect(halves(40f)),
        )
        val effect = colorFilterRenderEffect(
            ColorFilter.tint(Color.White.copy(alpha = 0.3f), BlendMode.SrcOver),
            blendRenderEffect(
                BlendMode.Softlight,
                BlurEffect(3f, 3f, TileMode.Decal),
                noise,
            ),
        )
        val draws = contextCallsDuring(listOf("drawImage")) {
            drawLayerImage(
                width = 40,
                height = 20,
                layerSize = IntSize(40, 20),
                topLeft = IntOffset.Zero,
                effect = effect,
            ) {
                drawRect(Color.Green, size = Size(40f, 20f))
            }
        }.names
        // The layer blurred into the image, which all the other parts apply to, and the image drawn
        // where the layer goes. Without `ctx.filter`, the blur takes a draw of its own.
        assertEquals(if (CanvasFeatures.filter) 2 else 3, draws.size, "$draws")
    }

    @Test
    fun aTintThatSkiaMakesNoFilterForAsTheBackgroundOfABlend() {
        val image = drawLayerImage(
            width = 40,
            height = 20,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset.Zero,
            effect = blendRenderEffect(
                BlendMode.SrcOver,
                colorFilterRenderEffect(ColorFilter.tint(Color.Green, BlendMode.Dst), null),
                shaderRenderEffect(halfMask(40f)),
            ),
        ) {
            drawRect(Color.Red, size = Size(40f, 20f))
        }
        assertColor(Color.Black, image.pixel(5, 10))
        assertColor(Color.Red, image.pixel(30, 10))
    }

    @Test
    fun aMovedFilterThatFillsTheLayerStaysAnImageOfIt() {
        // Transparent pixels turn opaque, within the image of the layer.
        val opaque = ColorMatrix().apply { this[3, 4] = 255f }
        val image = drawLayerImage(
            width = 40,
            height = 20,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset.Zero,
            effect = OffsetEffect(
                colorFilterRenderEffect(
                    ColorFilter.colorMatrix(opaque),
                    shaderRenderEffect(halves(40f)),
                ),
                Offset(10f, 0f),
            ),
        ) {}
        assertColor(Color.Transparent, image.pixel(5, 10))
        assertColor(Color.Red, image.pixel(15, 10))
    }

    @Test
    fun theAlphaOfAnEffectAppliesAfterTheFilterOfItsInput() {
        val opaque = ColorMatrix().apply { this[3, 4] = 255f }
        val image = drawLayerImage(
            width = 40,
            height = 20,
            layerSize = IntSize(40, 20),
            topLeft = IntOffset.Zero,
            effect = colorFilterRenderEffect(
                ColorFilter.tint(Color.Black.copy(alpha = 0.5f), BlendMode.DstIn),
                colorFilterRenderEffect(ColorFilter.colorMatrix(opaque), null),
            ),
        ) {
            drawRect(Color.Red, size = Size(10f, 20f))
        }
        assertColor(Color.Red.copy(alpha = 0.5f), image.pixel(5, 10))
        assertColor(Color.Black.copy(alpha = 0.5f), image.pixel(30, 10))
    }

    @Test
    fun clampedBlurKeepsTheEdgeColorsAndStaysInsideTheLayer() {
        val image = drawLayerImage(
            width = 80,
            height = 80,
            layerSize = IntSize(40, 40),
            topLeft = IntOffset(20, 20),
            effect = BlurEffect(8f, 8f, TileMode.Clamp),
        ) {
            drawRect(Color.Red, size = Size(40f, 40f))
        }
        // The edges sample the edge color, so the layer stays red up to its edges.
        assertColor(Color.Red, image.pixel(20, 20))
        assertColor(Color.Red, image.pixel(40, 40))
        assertColor(Color.Red, image.pixel(59, 59))
        assertColor(Color.Transparent, image.pixel(18, 40))
        assertColor(Color.Transparent, image.pixel(61, 40))
    }

    @Test
    fun decalBlurSpreadsTheContentOut() {
        val image = drawLayerImage(
            width = 80,
            height = 80,
            layerSize = IntSize(40, 40),
            topLeft = IntOffset(20, 20),
            effect = BlurEffect(6f, 6f, TileMode.Decal),
        ) {
            drawRect(Color.Red, size = Size(40f, 40f))
        }
        val center = image.pixel(40, 40)
        val edge = image.pixel(20, 40)
        val outside = image.pixel(16, 40)
        assertColor(Color.Red, center)
        assertTrue(edge.alpha in 0.3f..0.7f, "alpha at the edge: ${edge.alpha}")
        assertTrue(outside.alpha in 0.01f..0.4f, "alpha outside: ${outside.alpha}")
    }

    /** A 20 x 20 red layer at (30, 30) of an 80 x 80 image, through [effect]. */
    private fun blurredSquare(effect: RenderEffect): ImageBitmap =
        drawLayerImage(
            width = 80,
            height = 80,
            layerSize = IntSize(20, 20),
            topLeft = IntOffset(30, 30),
            effect = effect,
        ) {
            drawRect(Color.Red, size = Size(20f, 20f))
        }

    /** The alphas left of and above the square of [blurredSquare], 3 pixels away. */
    private fun ImageBitmap.alphasBeside(): Pair<Float, Float> = pixel(27, 40).alpha to pixel(40, 27).alpha

    @Test
    fun blursOfTwoDeviationsBlurEachAxisByItsOwn() {
        // Decal: the layer is drawn into a larger one, which the blur spreads over.
        for ((effect, blurredAlongX) in listOf(
            BlurEffect(8f, 0f, TileMode.Decal) to true,
            BlurEffect(0f, 8f, TileMode.Decal) to false,
            // Through another effect first.
            BlurEffect(OffsetEffect(0f, 0f), 8f, 0f, TileMode.Decal) to true,
        )) {
            val (left, above) = blurredSquare(effect).alphasBeside()
            val (blurred, sharp) = if (blurredAlongX) left to above else above to left
            assertTrue(blurred > 0.05f && sharp == 0f, "$effect: $left on the left, $above above")
        }
        // Clamp: the edges are extended, the layer stays red to its edges and blurs nothing out.
        val clamped = blurredSquare(BlurEffect(8f, 0f, TileMode.Clamp))
        assertColor(Color.Red, clamped.pixel(31, 40))
        assertColor(Color.Transparent, clamped.pixel(27, 40))
    }

    @Test
    fun blursOfTwoDeviationsAndColorMatricesReuseTheirFilters() {
        // More filters than the page keeps, of both kinds, so that each kind reuses the other's.
        for (step in 0 until 40) {
            val blurred = blurredSquare(BlurEffect(4f + step / 10f, 0f, TileMode.Decal))
            val (left, above) = blurred.alphasBeside()
            assertTrue(left > 0.05f && above == 0f, "step $step: $left on the left, $above above")
            // Red, not through a matrix left in the filter.
            val center = blurred.pixel(40, 40)
            assertTrue(center.red > 0.98f && center.green < 0.02f, "step $step: $center at the center")
            val level = step / 39f
            val filter = ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        level, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
            val image = image(width = 4, height = 4) {
                saveLayer(Rect(0f, 0f, 4f, 4f), Paint().apply { colorFilter = filter })
                drawRect(Rect(0f, 0f, 4f, 4f), Paint().apply { color = Color.White })
                restore()
            }
            assertColor(Color(level, 0f, 0f), image.pixel(2, 2), tolerance = 0.01f)
        }
    }

    @Test
    fun offsetEffectMovesTheContent() {
        val image = drawLayerImage(
            width = 60,
            height = 20,
            layerSize = IntSize(20, 20),
            topLeft = IntOffset(0, 0),
            effect = OffsetEffect(30f, 0f),
        ) {
            drawRect(Color.Blue, size = Size(20f, 20f))
        }
        assertColor(Color.Transparent, image.pixel(10, 10))
        assertColor(Color.Blue, image.pixel(40, 10))
    }

    @Test
    fun blurOfAnOffsetLayerIsMovedToo() {
        val image = drawLayerImage(
            width = 80,
            height = 40,
            layerSize = IntSize(20, 20),
            topLeft = IntOffset(0, 10),
            effect = BlurEffect(OffsetEffect(40f, 0f), 2f, 2f, TileMode.Decal),
        ) {
            drawRect(Color.Blue, size = Size(20f, 20f))
        }
        assertColor(Color.Transparent, image.pixel(10, 20))
        assertColor(Color.Blue, image.pixel(50, 20), tolerance = 0.05f)
    }

    @Test
    fun blurDoesNotPickUpWhatAReusedCanvasHeldBefore() {
        // A large layer leaves its canvas, full of blue, to the pool...
        image(width = 120, height = 120) {
            saveLayer(Rect(0f, 0f, 120f, 120f), Paint().apply { alpha = 0.5f })
            drawRect(Rect(0f, 0f, 120f, 120f), Paint().apply { color = Color.Blue })
            restore()
        }
        // ...which a smaller blurred layer reuses: the blue must not bleed into its edges.
        val image = drawLayerImage(
            width = 40,
            height = 40,
            layerSize = IntSize(40, 40),
            topLeft = IntOffset(0, 0),
            effect = BlurEffect(4f, 4f, TileMode.Decal),
        ) {
            drawRect(Color.Red, size = Size(40f, 40f))
        }
        for (y in listOf(5, 20, 38)) {
            val pixel = image.pixel(38, y)
            assertTrue(pixel.blue < 0.02f, "blue at (38, $y): $pixel")
        }
    }

    /**
     * A 64 x 64 canvas with an opaque red square and a translucent green bar over it, 14 pixels from
     * the edges.
     */
    private fun shapes(): CanvasElement {
        val canvas = createCanvas(64, 64)
        val ctx = context2D(canvas)
        ctx.fillStyle = CssColors.of(Color.Red.toArgb())
        ctx.fillRect(20.0, 20.0, 24.0, 24.0)
        ctx.fillStyle = CssColors.of(0x8000C000.toInt())
        ctx.fillRect(30.0, 14.0, 10.0, 36.0)
        return canvas
    }

    /** A 64 x 64 canvas with an opaque red square, 20 pixels from the edges. */
    private fun redSquare(): CanvasElement {
        val canvas = createCanvas(64, 64)
        val ctx = context2D(canvas)
        ctx.fillStyle = CssColors.of(Color.Red.toArgb())
        ctx.fillRect(20.0, 20.0, 24.0, 24.0)
        return canvas
    }

    /** Blurs all of [pixels] through them by the boxes approximating Gaussians of the deviations. */
    private fun blurWhole(pixels: ImageData, sigmaX: Float, sigmaY: Float) {
        val radiiX = IntArray(3).also { Effects.boxRadii(sigmaX, it) }
        val radiiY = IntArray(3).also { Effects.boxRadii(sigmaY, it) }
        blurPixels(pixels, 0, 0, pixels.width, pixels.height, radiiX, radiiY)
    }

    @Test
    fun blurThroughThePixelsApproximatesTheGaussian() {
        for (sigma in listOf(1.5f, 4f)) {
            val pixels = context2D(shapes()).getImageData(0, 0, 64, 64)
            val gaussian = gaussianBlur(pixels, sigma)
            blurWhole(pixels, sigma, sigma)
            val difference = maxPremultipliedDifference(gaussian, pixels)
            // Three box blurs approximate the Gaussian: here, within 5 of 255 at the steepest.
            assertTrue(difference <= 6, "σ $sigma: the pixels differ by up to $difference")
        }
    }

    @Test
    fun blurThroughThePixelsBlursEachAxisByItsOwnDeviation() {
        val pixels = context2D(redSquare()).getImageData(0, 0, 64, 64)
        blurWhole(pixels, 4f, 0f)
        val left = Color(argbAt(pixels, 17, 32))
        val above = Color(argbAt(pixels, 32, 17))
        assertTrue(left.alpha > 0.05f && above.alpha == 0f, "$left on the left, $above above")
    }

    @Test
    fun blurThroughThePixelsAlongOneAxisKeepsTheColorOfATranslucentSquare() {
        // Unpremultiplied, its color would be lighter where the blur mixes it with transparency.
        val green = Color(0x8000C000)
        for ((sigmaX, sigmaY) in listOf(4f to 0f, 0f to 4f)) {
            val canvas = createCanvas(64, 64)
            val ctx = context2D(canvas)
            ctx.fillStyle = CssColors.of(green.toArgb())
            ctx.fillRect(20.0, 20.0, 24.0, 24.0)
            val pixels = ctx.getImageData(0, 0, 64, 64)
            blurWhole(pixels, sigmaX, sigmaY)
            val edge = Color(argbAt(pixels, if (sigmaX > 0f) 20 else 32, if (sigmaY > 0f) 20 else 32))
            assertTrue(edge.alpha in 0.15f..0.35f, "σ ($sigmaX, $sigmaY): alpha at the edge ${edge.alpha}")
            assertColor(green.copy(alpha = edge.alpha), edge)
        }
    }

    @Test
    fun blurThroughThePixelsKeepsTheColorOfTranslucentEdges() {
        // Blurred premultiplied, the transparent pixels around the square hold no color to mix in.
        val pixels = context2D(redSquare()).getImageData(0, 0, 64, 64)
        blurWhole(pixels, 4f, 4f)
        val edge = Color(argbAt(pixels, 20, 32))
        assertTrue(edge.alpha in 0.3f..0.7f, "alpha at the edge: ${edge.alpha}")
        assertColor(Color.Red.copy(alpha = edge.alpha), edge)
    }

    /**
     * A 160 x 120 canvas with an opaque red square and translucent green stripes 2 pixels wide
     * beside it, 45 pixels from the edges: a blur of [LargeSigma] leaves the edges transparent.
     */
    private fun squareAndStripes(): CanvasElement {
        val canvas = createCanvas(160, 120)
        val ctx = context2D(canvas)
        ctx.fillStyle = CssColors.of(Color.Red.toArgb())
        ctx.fillRect(45.0, 45.0, 30.0, 30.0)
        ctx.fillStyle = CssColors.of(0x8000C000.toInt())
        for (x in 85 until 115 step 4) ctx.fillRect(x.toDouble(), 45.0, 2.0, 30.0)
        return canvas
    }

    /**
     * The [width] x [height] area of [source] at ([sourceX], [sourceY]) blurred through its pixels
     * by [sigma] on both axes, into a `<canvas>` element of its size, like the canvases layers are
     * composited into. (Safari scales up into a canvas read frequently otherwise.)
     */
    private fun blurredThroughThePixels(
        source: CanvasElement,
        sigma: Float,
        sourceX: Int = 0,
        sourceY: Int = 0,
        width: Int = 160,
        height: Int = 120,
    ): Context2D {
        val target = requireContext2D(createElementCanvas(width, height))
        Effects.drawBlurredThroughPixels(
            target, source, sourceX, sourceY, width, height, sigma, sigma, TileMode.Decal, 0.0, 0.0,
        )
        return target
    }

    @Test
    fun aLargeBlurThroughThePixelsApproximatesTheGaussian() {
        val source = squareAndStripes()
        val gaussian = gaussianBlur(context2D(source).getImageData(0, 0, 160, 120), LargeSigma)
        val blurred = blurredThroughThePixels(source, LargeSigma).getImageData(0, 0, 160, 120)
        val difference = maxPremultipliedDifference(gaussian, blurred)
        // Scaled down and three box blurs: here, 6 of 255 at a corner of the square.
        assertTrue(difference <= 6, "the pixels differ by up to $difference")
    }

    @Test
    fun aLargeBlurThroughThePixelsReadsTheFewerPixelsOfAScaledDownCopy() {
        val source = squareAndStripes()
        val pixels = contextCallsDuring(listOf("getImageData")) { blurredThroughThePixels(source, LargeSigma) }.pixelsRead
        // Scaled down by a quarter on both axes, where the deviation is 2.5, with a pixel around.
        assertTrue(pixels <= (160 / 4 + 2) * (120 / 4 + 2), "$pixels pixels read")
    }

    @Test
    fun aLargeBlurThroughThePixelsKeepsToItsAreaUpToItsEdges() {
        // A red area of a size that a quarter doesn't divide, in a blue canvas: no blue is mixed in,
        // and the area stays opaque up to its edges, whose pixels the blur extends (see
        // blurPixels), whatever the scaling.
        val width = 163
        val height = 121
        val source = createCanvas(180, 140)
        context2D(source).apply {
            fillStyle = CssColors.of(Color.Blue.toArgb())
            fillRect(0.0, 0.0, 180.0, 140.0)
            fillStyle = CssColors.of(Color.Red.toArgb())
            fillRect(7.0, 5.0, width.toDouble(), height.toDouble())
        }
        val blurred = blurredThroughThePixels(source, LargeSigma, 7, 5, width, height)
        for (x in listOf(0, width / 2, width - 1)) {
            for (y in listOf(0, height / 2, height - 1)) {
                assertColor(Color.Red, Color(argbAt(blurred.getImageData(x, y, 1, 1), 0, 0)), tolerance = 0f)
            }
        }
    }

    @Test
    fun aCanvasOfThePoolComesBackWithItsImageSmoothing() {
        // The canvases that other tests left in the pool taken, so that it keeps the one released.
        val others = List(8) { LayerCanvasPool.acquire(1, 1) }
        val lease = LayerCanvasPool.acquire(100, 10)
        lease.ctx.imageSmoothingEnabled = false
        lease.ctx.imageSmoothingQuality = JsStrings.high
        LayerCanvasPool.release(lease)
        val reused = LayerCanvasPool.acquire(100, 10)
        assertSame(lease.canvas, reused.canvas)
        assertTrue(reused.ctx.imageSmoothingEnabled)
        assertEquals("low", reused.ctx.imageSmoothingQuality.toString())
        LayerCanvasPool.release(reused)
        others.forEach { LayerCanvasPool.release(it) }
    }

    private companion object {
        /** A deviation that a blur through the pixels scales the area down for. */
        const val LargeSigma = 10f
    }
}

private fun context2D(canvas: CanvasElement): Context2D =
    checkNotNull(getContext2D(canvas, willReadFrequently = true))

/**
 * [imageData] blurred with a Gaussian of deviation [sigma] on both axes, which CSS `blur()`
 * specifies: computed exactly (premultiplied, up to 3 deviations away, transparent outside).
 */
// language=js
private fun gaussianBlur(imageData: ImageData, sigma: Float): ImageData = js(
    """{
        const w = imageData.width, h = imageData.height, src = imageData.data;
        const radius = Math.ceil(3 * sigma);
        const kernel = [];
        let sum = 0;
        for (let i = -radius; i <= radius; i++) {
            const k = Math.exp(-i * i / (2 * sigma * sigma));
            kernel.push(k);
            sum += k;
        }
        const premultiplied = new Float64Array(w * h * 4);
        for (let i = 0; i < premultiplied.length; i += 4) {
            const a = src[i + 3];
            for (let c = 0; c < 3; c++) premultiplied[i + c] = src[i + c] * a / 255;
            premultiplied[i + 3] = a;
        }
        const pass = (input, dx, dy) => {
            const output = new Float64Array(input.length);
            for (let y = 0; y < h; y++) {
                for (let x = 0; x < w; x++) {
                    for (let k = -radius; k <= radius; k++) {
                        const sx = x + k * dx, sy = y + k * dy;
                        if (sx < 0 || sx >= w || sy < 0 || sy >= h) continue;
                        const weight = kernel[k + radius] / sum, from = 4 * (sy * w + sx), to = 4 * (y * w + x);
                        for (let c = 0; c < 4; c++) output[to + c] += input[from + c] * weight;
                    }
                }
            }
            return output;
        };
        const blurred = pass(pass(premultiplied, 1, 0), 0, 1);
        const result = new ImageData(w, h);
        for (let i = 0; i < blurred.length; i += 4) {
            const a = blurred[i + 3];
            result.data[i + 3] = Math.round(a);
            for (let c = 0; c < 3; c++) result.data[i + c] = a > 0 ? Math.round(blurred[i + c] * 255 / a) : 0;
        }
        return result;
    }"""
)

