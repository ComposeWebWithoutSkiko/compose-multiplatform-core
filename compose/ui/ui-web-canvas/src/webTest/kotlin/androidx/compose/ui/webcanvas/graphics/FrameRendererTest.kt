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
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.OffsetEffect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.webcanvas.WebCanvasGraphicsContext
import androidx.compose.ui.webcanvas.blendRenderEffect
import androidx.compose.ui.webcanvas.colorFilterRenderEffect
import androidx.compose.ui.webcanvas.dom.CanvasFeatures
import androidx.compose.ui.webcanvas.dom.createThrowingContext2D
import androidx.compose.ui.webcanvas.shaderRenderEffect
import androidx.compose.ui.webcanvas.text.directionalRun
import kotlin.js.toJsString
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Frames drawn by [FrameRenderer], which draws the content of offscreen layers ahead into a
 * [LayerAtlas], look the same as drawn directly.
 */
class FrameRendererTest {
    private val graphicsContext = WebCanvasGraphicsContext()
    private val layers = ArrayList<GraphicsLayer>()

    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    @AfterTest
    fun releaseLayers() {
        layers.forEach { graphicsContext.releaseGraphicsLayer(it) }
    }

    private fun layer(width: Int, height: Int, content: DrawScope.() -> Unit): GraphicsLayer =
        graphicsContext.createGraphicsLayer().also {
            it.record(Density(1f), LayoutDirection.Ltr, IntSize(width, height), content)
            layers.add(it)
        }

    /**
     * A layer of a rectangle with a smaller one over it: content that covers itself, with its edges
     * on whole pixels. Safari antialiases an edge differently depending on where on its canvas it's
     * drawn, so a layer drawn ahead (in a slot of the atlas) and directly (at the origin of a canvas
     * of its own) differs there by a few levels.
     */
    private fun card(color: Color, alpha: Float, topLeft: IntOffset): GraphicsLayer =
        layer(30, 20) {
            drawRect(color)
            drawRect(Color.White, topLeft = Offset(7f, 4f), size = Size(16f, 12f))
        }.also {
            it.alpha = alpha
            it.topLeft = topLeft
        }

    private fun Canvas.drawLayers(vararg layers: GraphicsLayer) {
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, this, Size(Width.toFloat(), Height.toFloat())) {
            layers.forEach { drawLayer(it) }
        }
    }

    /** The renderer of the last [assertDrawnAheadTheSame]. */
    private lateinit var renderer: FrameRenderer

    /**
     * Draws a frame with [draw] directly and through a [FrameRenderer] with [atlas], asserts that
     * they look the same, and returns the layers the renderer drew ahead.
     */
    private fun assertDrawnAheadTheSame(atlas: LayerAtlas = LayerAtlas(), draw: Canvas.() -> Unit): Int {
        val direct = ImageBitmap(Width, Height)
        Canvas(direct).draw()
        val ahead = ImageBitmap(Width, Height) as CanvasImageBitmap
        renderer = FrameRenderer(ahead.drawTarget, atlas)
        renderer.render { it.draw() }
        assertSamePixels(direct, ahead)
        return renderer.layersDrawnAhead
    }

    private fun assertSamePixels(expected: ImageBitmap, actual: ImageBitmap) {
        val e = expected.toPixelMap()
        val a = actual.toPixelMap()
        for (y in 0 until Height) {
            for (x in 0 until Width) {
                val p = e[x, y]
                val q = a[x, y]
                // Compared premultiplied, as the pixels are stored.
                assertTrue(
                    abs(p.red * p.alpha - q.red * q.alpha) <= Tolerance &&
                        abs(p.green * p.alpha - q.green * q.alpha) <= Tolerance &&
                        abs(p.blue * p.alpha - q.blue * q.alpha) <= Tolerance &&
                        abs(p.alpha - q.alpha) <= Tolerance,
                    "($x, $y): $p drawn directly, $q drawn ahead",
                )
            }
        }
    }

    @Test
    fun fadingLayers() {
        val cards = Array(6) { i ->
            card(Palette[i], alpha = 0.3f + 0.1f * i, topLeft = IntOffset(4 + (i % 3) * 34, 4 + (i / 3) * 24))
        }
        assertEquals(6, assertDrawnAheadTheSame { drawLayers(*cards) })
    }

    @Test
    fun nestedLayers() {
        val cards = Array(3) { i -> card(Palette[i], alpha = 0.5f, topLeft = IntOffset(4 + i * 34, 4)) }
        val group = layer(110, 40) {
            drawRect(Color(0x40000000))
            cards.forEach { drawLayer(it) }
        }
        group.alpha = 0.6f
        group.topLeft = IntOffset(5, 10)
        assertEquals(4, assertDrawnAheadTheSame { drawLayers(group) })
    }

    @Test
    fun layersInPerspective() {
        val cards = Array(4) { i ->
            card(Palette[i], alpha = 1f, topLeft = IntOffset(4 + (i % 2) * 50, 4 + (i / 2) * 30)).also {
                it.rotationY = 20f + 10f * i
            }
        }
        assertEquals(4, assertDrawnAheadTheSame { drawLayers(*cards) })
        assertEquals(4, renderer.layersProjectedAhead)
    }

    @Test
    fun layersInPerspectiveInALayerInPerspective() {
        val cards = Array(2) { i ->
            card(Palette[i], alpha = 1f, topLeft = IntOffset(4 + i * 36, 8)).also { it.rotationX = 25f }
        }
        val group = layer(80, 40) {
            drawRect(Color(0x40000000))
            cards.forEach { drawLayer(it) }
        }
        group.rotationY = 20f
        group.topLeft = IntOffset(10, 10)
        // The group, and the cards in it, a level below.
        assertEquals(3, assertDrawnAheadTheSame { drawLayers(group) })
        assertEquals(3, renderer.layersProjectedAhead)
    }

    @Test
    fun aLayerInPerspectiveMetAfterOtherWebGLDrawingIsDrawnWhereItsMet() {
        val first = card(Palette[0], alpha = 1f, topLeft = IntOffset(4, 4)).also { it.rotationY = 30f }
        val second = card(Palette[1], alpha = 1f, topLeft = IntOffset(60, 4)).also { it.rotationY = -30f }
        // Drawn with WebGL too, which leaves the canvas of the shared context holding the mesh.
        val mesh = Vertices(
            VertexMode.Triangles,
            listOf(Offset(4f, 40f), Offset(40f, 40f), Offset(4f, 70f)),
            listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(0f, 10f)),
            listOf(Color.Red, Color.Green, Color.Blue),
            emptyList(),
        )
        assertDrawnAheadTheSame {
            drawLayers(first)
            drawVertices(mesh, BlendMode.SrcOver, Paint())
            drawLayers(second)
        }
        assertEquals(2, renderer.layersProjectedAhead)
    }

    @Test
    fun layersInPerspectiveDrawnFromAPageUsedOtherwiseBefore() {
        // Frames drawn with the same atlas, whose page is kept with its texture: the second frame
        // uses less of it than the first, which it still holds further away, and the third more
        // than it has, which makes it grow.
        val atlas = LayerAtlas()
        val frames = listOf(
            layer(120, 60) { drawRect(Palette[1]) },
            card(Palette[0], alpha = 1f, topLeft = IntOffset.Zero),
            layer(300, 30) { drawRect(Palette[2]) },
        )
        for (layer in frames) {
            layer.topLeft = IntOffset(10, 10)
            layer.rotationY = 35f
            // Slanted edges, which pixels meet at any distance.
            layer.rotationZ = 20f
            assertDrawnAheadTheSame(atlas) { drawLayers(layer) }
            assertEquals(1, renderer.layersProjectedAhead)
        }
    }

    @Test
    fun layersWithTheSameBounds() {
        val first = card(Palette[0], alpha = 0.5f, topLeft = IntOffset(10, 10))
        val second = card(Palette[1], alpha = 0.7f, topLeft = IntOffset(10, 10))
        // Two layers at the same place, and the same layer twice at the same place.
        assertEquals(4, assertDrawnAheadTheSame { drawLayers(first, second, second, first) })
    }

    @Test
    fun colorFilters() {
        val filters = listOf(
            ColorFilter.tint(Color.Green, BlendMode.SrcIn),
            ColorFilter.tint(Color(0x80FF0000), BlendMode.Modulate),
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.2f) }),
            ColorFilter.lighting(Color(0xFF808080), Color(0xFF202020)),
        )
        val cards = filters.mapIndexed { i, filter ->
            card(Palette[i], alpha = 1f, topLeft = IntOffset(4 + i * 34, 4)).also { it.colorFilter = filter }
        }
        // The same filters on layers in a layer, which composite into an atlas page.
        val group = layer(Width, 30) { cards.forEach { drawLayer(it) } }
        group.alpha = 0.8f
        group.topLeft = IntOffset(0, 40)
        assertEquals(4 + 5, assertDrawnAheadTheSame { drawLayers(*cards.toTypedArray(), group) })
    }

    @Test
    fun theColorMatricesOfALevelGoThroughWebGLOnceAllOfItsContentIsDrawn() {
        // Without SVG filters, the browser would apply color matrices through the pixels; WebGL
        // applies them to all the slots of the page, read and uploaded once, after all of them are
        // drawn: reading a page makes the browser finish drawing into it. With SVG filters, the GPU
        // applies them when compositing.
        val filter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.2f) })
        val cards = Array(3) { i ->
            card(Palette[i], alpha = 1f, topLeft = IntOffset(4 + i * 34, 4)).also { it.colorFilter = filter }
        }
        val image = ImageBitmap(Width, Height) as CanvasImageBitmap
        val renderer = FrameRenderer(image.drawTarget)
        val calls = contextCallsDuring(listOf("getImageData", "texSubImage2D", "fillRect", "fill")) {
            renderer.render { it.drawLayers(*cards) }
        }.names
        assertEquals(3, renderer.layersDrawnAhead)
        val throughWebGL = if (SvgColorFilters.isSupported) 0 else 1
        assertEquals(throughWebGL, calls.count { it == "getImageData" }, "pixels read: $calls")
        assertEquals(throughWebGL, calls.count { it == "texSubImage2D" }, "uploads: $calls")
        if (throughWebGL > 0) {
            val afterRead = calls.subList(calls.indexOf("getImageData"), calls.size)
            assertTrue(afterRead.none { it == "fillRect" || it == "fill" }, "drawn after the read: $calls")
        }
    }

    @Test
    fun blendModes() {
        val cards = listOf(BlendMode.Multiply, BlendMode.Screen, BlendMode.Xor, BlendMode.DstOut).mapIndexed { i, mode ->
            card(Palette[i], alpha = 0.8f, topLeft = IntOffset(4 + i * 34, 10)).also { it.blendMode = mode }
        }
        assertEquals(4, assertDrawnAheadTheSame {
            drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), Paint().apply { color = Palette[5] })
            drawLayers(*cards.toTypedArray())
        })
    }

    @Test
    fun renderEffects() {
        val effects = listOf<RenderEffect>(
            BlurEffect(3f, 3f, TileMode.Decal),
            BlurEffect(3f, 3f, TileMode.Clamp),
            BlurEffect(OffsetEffect(4f, 2f), 2f, 2f, TileMode.Decal),
            OffsetEffect(3f, -2f),
        )
        val cards = effects.mapIndexed { i, effect ->
            card(Palette[i], alpha = 1f, topLeft = IntOffset(6 + i * 34, 10)).also { it.renderEffect = effect }
        }
        val group = layer(Width, 30) { cards.forEach { drawLayer(it) } }
        group.alpha = 0.9f
        group.topLeft = IntOffset(0, 40)
        assertEquals(4 + 5, assertDrawnAheadTheSame { drawLayers(*cards.toTypedArray(), group) })
        // In the group and out of it, the card with clamped edges, whose edges are extended ahead,
        // and without `ctx.filter` (as in Safari) the one with decal edges too, both blurred ahead
        // through the pixels. The blur of an offset is blurred where it's met.
        assertEquals(if (CanvasFeatures.filter) 2 else 4, renderer.layersBlurredAhead)
    }

    @Test
    fun effectsMadeOfSeveralParts() {
        val shader = LinearGradientShader(
            from = Offset.Zero,
            to = Offset(30f, 0f),
            colors = listOf(Color.Red, Color.Black, Color.Blue),
        )
        val halfAlpha = ColorMatrix().apply { this[3, 3] = 0.5f }
        val blurred = BlurEffect(3f, 3f, TileMode.Clamp)
        val mask = LinearGradientShader(
            from = Offset.Zero,
            to = Offset(30f, 0f),
            colors = listOf(Color.Black, Color.Transparent),
        )
        val coverImage = ImageBitmap(40, 30)
        Canvas(coverImage).drawRect(Rect(0f, 0f, 40f, 30f), Paint())
        val cover = ImageShader(coverImage, TileMode.Clamp, TileMode.Clamp)
        val effects = listOf(
            shaderRenderEffect(shader),
            colorFilterRenderEffect(ColorFilter.tint(Color.Green, BlendMode.SrcIn), null),
            colorFilterRenderEffect(ColorFilter.colorMatrix(halfAlpha), OffsetEffect(4f, 2f)),
            blendRenderEffect(BlendMode.DstIn, null, shaderRenderEffect(shader)),
            blendRenderEffect(BlendMode.Softlight, blurred, shaderRenderEffect(shader)),
            colorFilterRenderEffect(
                ColorFilter.tint(Color.White.copy(alpha = 0.3f), BlendMode.SrcOver),
                blendRenderEffect(BlendMode.Softlight, blurred, shaderRenderEffect(shader)),
            ),
            colorFilterRenderEffect(ColorFilter.colorMatrix(halfAlpha), blurred),
            // A mask that a single draw covers the layer with.
            blendRenderEffect(BlendMode.DstIn, null, shaderRenderEffect(cover)),
            // What Haze makes of a default progressive blur: the layer, and a masked blur over it.
            blendRenderEffect(
                BlendMode.SrcOver,
                OffsetEffect(0f, 0f),
                blendRenderEffect(BlendMode.DstIn, blurred, shaderRenderEffect(mask)),
            ),
            OffsetEffect(blurred, Offset(2f, 1f)),
            // The same blur twice.
            blendRenderEffect(BlendMode.Softlight, blurred, blurred),
        )
        var blurredAhead = 0
        var resultsAhead = 0
        for (frame in effects.chunked(4)) {
            val cards = frame.mapIndexed { i, effect ->
                card(Palette[i], alpha = 1f, topLeft = IntOffset(6 + i * 34, 10))
                    .also { it.renderEffect = effect }
            }
            val group = layer(Width, 30) { cards.forEach { drawLayer(it) } }
            group.alpha = 0.9f
            group.topLeft = IntOffset(0, 40)
            assertEquals(
                2 * cards.size + 1,
                assertDrawnAheadTheSame { drawLayers(*cards.toTypedArray(), group) },
            )
            blurredAhead += renderer.layersBlurredAhead
            resultsAhead += renderer.layersResultsAhead
        }
        // Each of the effects with a blur, in and out of the group.
        assertEquals(2 * 6, blurredAhead)
        // All the effects but the shader, which needs no image, in and out of the group.
        assertEquals(2 * 10, resultsAhead)
    }

    /** Draws a frame of a card with [effect], and returns the images it drew, from canvases or not. */
    private fun drawsOfAFrameWith(effect: RenderEffect): Int {
        val card = card(Palette[0], alpha = 1f, topLeft = IntOffset(6, 10))
        card.renderEffect = effect
        val image = ImageBitmap(Width, Height) as CanvasImageBitmap
        renderer = FrameRenderer(image.drawTarget)
        return contextCallsDuring(listOf("drawImage")) {
            renderer.render { it.drawLayers(card) }
        }.names.size
    }

    @Test
    fun theMainPassOnlyReadsTheImagesOfEffects() {
        val mask = LinearGradientShader(
            from = Offset.Zero,
            to = Offset(30f, 0f),
            colors = listOf(Color.Black, Color.Transparent),
        )
        val blurred = BlurEffect(3f, 3f, TileMode.Clamp)
        // The edges of the blur extended (9 draws), without `ctx.filter` the blur WebGL made drawn
        // back into its slot, and the blur into the image the effect works in.
        val blurAhead = 9 + (if (CanvasFeatures.filter) 0 else 1) + 1
        val tinted = drawsOfAFrameWith(
            colorFilterRenderEffect(
                ColorFilter.tint(Color.White.copy(alpha = 0.3f), BlendMode.SrcOver),
                blendRenderEffect(BlendMode.Softlight, blurred, shaderRenderEffect(mask)),
            ),
        )
        assertEquals(1, renderer.layersBlurredAhead)
        assertEquals(1, renderer.layersResultsAhead)
        assertEquals(0, renderer.layersForegroundsAhead)
        assertEquals(blurAhead + 1, tinted)
        val progressive = drawsOfAFrameWith(
            blendRenderEffect(
                BlendMode.SrcOver,
                OffsetEffect(0f, 0f),
                blendRenderEffect(BlendMode.DstIn, blurred, shaderRenderEffect(mask)),
            ),
        )
        assertEquals(1, renderer.layersBlurredAhead)
        assertEquals(1, renderer.layersResultsAhead)
        assertEquals(1, renderer.layersForegroundsAhead)
        // Then the layer and the foreground into the image, which the main pass reads.
        assertEquals(blurAhead + 2 + 1, progressive)
    }

    @Test
    fun largeBlurs() {
        // Through the pixels, blurred scaled down.
        val cards = listOf(TileMode.Decal, TileMode.Clamp).mapIndexed { i, edges ->
            card(Palette[i], alpha = 1f, topLeft = IntOffset(30 + i * 60, 10)).also {
                it.renderEffect = BlurEffect(12f, 12f, edges)
            }
        }
        val group = layer(Width, 30) { cards.forEach { drawLayer(it) } }
        group.alpha = 0.9f
        group.topLeft = IntOffset(0, 45)
        assertEquals(2 + 3, assertDrawnAheadTheSame { drawLayers(*cards.toTypedArray(), group) })
        assertEquals(if (CanvasFeatures.filter) 2 else 4, renderer.layersBlurredAhead)
    }

    @Test
    fun theBlursOfALevelAreBlurredAtOnce() {
        // Without `ctx.filter`, layers are blurred through their pixels with WebGL, from their page
        // read and uploaded once: reading a canvas just drawn into waits for the browser to draw it,
        // as long whatever its size. With `ctx.filter`, the GPU blurs them when compositing.
        val cards = Array(3) { i ->
            card(Palette[i], alpha = 1f, topLeft = IntOffset(6 + i * 34, 10)).also {
                it.renderEffect = BlurEffect(3f, 3f, TileMode.Clamp)
            }
        }
        val image = ImageBitmap(Width, Height) as CanvasImageBitmap
        val renderer = FrameRenderer(image.drawTarget)
        val calls = contextCallsDuring(listOf("getImageData", "texSubImage2D")) {
            renderer.render { it.drawLayers(*cards) }
        }.names
        assertEquals(3, renderer.layersDrawnAhead)
        assertEquals(3, renderer.layersBlurredAhead)
        val reads = calls.count { it == "getImageData" }
        val uploads = calls.count { it == "texSubImage2D" }
        val throughPixels = if (CanvasFeatures.filter) 0 else 1
        assertEquals(throughPixels, reads, "$calls")
        assertEquals(throughPixels, uploads, "$calls")
    }

    @Test
    fun theLayersInPerspectiveOfALevelWithBlurredOnesAreCompositedFromTheirProjection() {
        // WebGL holds the slots of a level drawn in perspective until they're composited: blurring
        // others of the level with WebGL after them would make each be drawn in perspective again,
        // uploading its raster (texImage2D), where it's met.
        val turned = card(Palette[0], alpha = 1f, topLeft = IntOffset(6, 10)).also { it.rotationY = 30f }
        val blurred = card(Palette[1], alpha = 1f, topLeft = IntOffset(60, 10)).also {
            it.renderEffect = BlurEffect(3f, 3f, TileMode.Clamp)
        }
        val image = ImageBitmap(Width, Height) as CanvasImageBitmap
        val renderer = FrameRenderer(image.drawTarget)
        val calls = contextCallsDuring(listOf("texImage2D")) {
            renderer.render { it.drawLayers(turned, blurred) }
        }.names
        assertEquals(1, renderer.layersProjectedAhead)
        assertEquals(1, renderer.layersBlurredAhead)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun savedLayers() {
        val inLayer = layer(60, 30) {
            drawIntoCanvas {
                it.saveLayer(Rect(0f, 0f, 60f, 30f), Paint().apply { alpha = 0.5f })
                it.drawRect(Rect(0f, 0f, 40f, 30f), Paint().apply { color = Palette[2] })
                it.drawRect(Rect(20f, 0f, 60f, 30f), Paint().apply { color = Palette[3] })
                it.restore()
            }
        }
        inLayer.topLeft = IntOffset(40, 40)
        assertEquals(2, assertDrawnAheadTheSame {
            saveLayer(Rect(5f, 5f, 65f, 35f), Paint().apply { alpha = 0.6f })
            drawRect(Rect(5f, 5f, 45f, 35f), Paint().apply { color = Palette[0] })
            drawRect(Rect(25f, 5f, 65f, 35f), Paint().apply { color = Palette[1] })
            restore()
            drawLayers(inLayer)
        })
    }

    @Test
    fun layersWithoutRoomInTheAtlasAreDrawnWhereTheyAreMet() {
        val cards = Array(3) { i -> card(Palette[i], alpha = 0.5f, topLeft = IntOffset(4 + i * 34, 4)) }
        val group = layer(110, 40) {
            drawRect(Color(0x40000000))
            cards.forEach { drawLayer(it) }
        }
        group.alpha = 0.6f
        group.topLeft = IntOffset(5, 10)
        // Room for the cards, not for the group around them.
        val atlas = LayerAtlas(maxPixels = 3L * 30 * 20)
        assertEquals(3, assertDrawnAheadTheSame(atlas) { drawLayers(group) })
    }

    @Test
    fun layersAreDrawnAsTheyAreAtTheEndOfTheFrame() {
        val card = card(Palette[0], alpha = 0.5f, topLeft = IntOffset(10, 10))
        val image = ImageBitmap(Width, Height) as CanvasImageBitmap
        FrameRenderer(image.drawTarget).render {
            it.drawLayers(card)
            card.alpha = 1f
        }
        assertEquals(1f, image.toPixelMap()[12, 12].alpha, Tolerance)
    }

    @Test
    fun pointsAreDrawnAsTheyAreAtTheEndOfTheFrame() {
        val points = floatArrayOf(10f, 10f)
        val paint = Paint().apply {
            strokeWidth = 4f
            color = Palette[0]
        }
        val image = ImageBitmap(Width, Height) as CanvasImageBitmap
        FrameRenderer(image.drawTarget).render {
            it.drawRawPoints(PointMode.Points, points, paint)
            points[0] = 40f
        }
        val pixels = image.toPixelMap()
        assertEquals(0f, pixels[10, 10].alpha)
        assertEquals(1f, pixels[40, 10].alpha, Tolerance)
    }

    @Test
    fun aLayerMetOutOfPlanBreaksThePlan() {
        val first = card(Palette[0], alpha = 0.5f, topLeft = IntOffset(10, 10))
        val second = card(Palette[1], alpha = 0.5f, topLeft = IntOffset(30, 20))
        val planned = recorded { drawLayers(first, second) }
        val drawn = recorded { drawLayers(second, first) }
        val plan = plan(planned)

        val direct = ImageBitmap(Width, Height)
        Canvas(direct).drawLayers(second, first)
        val image = ImageBitmap(Width, Height) as CanvasImageBitmap
        val canvas = WebCanvas(image.drawTarget)
        canvas.useJobs(plan.rootJobs)
        drawn.replay(canvas)
        assertTrue(canvas.isPlanBroken)
        canvas.finish()
        assertSamePixels(direct, image)
    }

    /** Plans [frame] with a canvas whose context throws whenever it's used. */
    private fun plan(frame: DisplayList): FramePlan {
        val plan = FramePlan()
        val planner = WebCanvas(DrawTarget(createThrowingContext2D(), 0, 0, Width, Height))
        planner.startPlanning(plan)
        frame.replay(planner)
        planner.finish()
        return plan
    }

    @Test
    fun planningDrawsNothing() {
        val paint = Paint().apply { color = Palette[0] }
        val stroke = Paint().apply {
            color = Palette[1]
            style = PaintingStyle.Stroke
            strokeWidth = 2f
        }
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(20f, 5f)
            lineTo(5f, 20f)
            close()
        }
        val shadowed = layer(30, 20) { drawRect(Color.White) }.also {
            it.shadowElevation = 4f
            it.setRoundRectOutline(Offset.Zero, Size(30f, 20f), 4f)
            it.topLeft = IntOffset(40, 40)
        }
        val turned = card(Palette[2], alpha = 0.5f, topLeft = IntOffset(80, 10)).also { it.rotationY = 30f }
        val fading = card(Palette[3], alpha = 0.5f, topLeft = IntOffset(110, 50))
        val text = TextRunDrawing(directionalRun("Text", isRtl = false), "12px sans-serif".toJsString(), paint.asWebPaint(), false, null, null, pixelRatio = 1f)
        val frame = recorded {
            save()
            saveLayer(Rect(0f, 0f, 10f, 10f), Paint().apply { alpha = 0.5f })
            drawRect(Rect(0f, 0f, 10f, 10f), paint)
            restore()
            clipRect(0f, 0f, 100f, 70f)
            clipPath(path, ClipOp.Difference)
            clipPath(path)
            drawLine(Offset(0f, 0f), Offset(10f, 10f), stroke)
            drawRect(Rect(0f, 0f, 10f, 10f), paint)
            drawRoundRect(0f, 0f, 20f, 10f, 3f, 3f, stroke)
            drawOval(Rect(0f, 0f, 10f, 20f), paint)
            drawCircle(Offset(5f, 5f), 4f, paint)
            drawArc(Rect(0f, 0f, 20f, 20f), 0f, 90f, true, paint)
            drawPath(path, Paint().apply { blendMode = BlendMode.SrcIn })
            drawImage(ImageBitmap(4, 4), Offset(2f, 2f), paint)
            drawPoints(PointMode.Points, listOf(Offset(1f, 1f), Offset(3f, 3f)), stroke)
            drawVertices(
                Vertices(
                    VertexMode.Triangles,
                    listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(0f, 10f)),
                    listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(0f, 10f)),
                    listOf(Color.Red, Color.Green, Color.Blue),
                    emptyList(),
                ),
                BlendMode.SrcOver,
                paint,
            )
            (this as RecordingCanvas).drawText(text, 2f, 12f)
            restore()
            drawLayers(shadowed, turned, fading)
        }
        // The saved layer, the turned card, drawn in perspective, and the fading card.
        assertEquals(listOf(false, true, false), plan(frame).rootJobs.map { it.isInPerspective })
    }

    @Test
    fun atlasPagesHoldTheSlotsApart() {
        val content = recorded {}
        val plan = FramePlan()
        repeat(40) { i ->
            plan.openJob(content, DisplayList.WholeList, 0, 0, 300 + i * 7, 200 + i * 3, Affine(), WebPaint())
            plan.closeJob()
        }
        val atlas = LayerAtlas()
        atlas.layOut(plan)
        val jobs = plan.jobsAt(0)
        for (a in jobs.indices) {
            val job = jobs[a]
            val page = job.page!!
            assertTrue(job.slotX + job.width <= page.target.width && job.slotY + job.height <= page.target.height)
            for (b in a + 1 until jobs.size) {
                val other = jobs[b]
                if (other.page !== page) continue
                val apart = job.slotX + job.width + LayerAtlas.Gutter <= other.slotX ||
                    other.slotX + other.width + LayerAtlas.Gutter <= job.slotX ||
                    job.slotY + job.height + LayerAtlas.Gutter <= other.slotY ||
                    other.slotY + other.height + LayerAtlas.Gutter <= job.slotY
                assertTrue(apart, "slots $a and $b overlap")
            }
        }
    }

    private fun recorded(draw: Canvas.() -> Unit): DisplayList {
        val canvas = RecordingCanvas()
        canvas.beginRecording()
        canvas.draw()
        return canvas.endRecording()
    }

    private companion object {
        const val Width = 150
        const val Height = 80

        /** The rounding of 8-bit channels, applied once more or less. */
        const val Tolerance = 2f / 255f

        val Palette = listOf(
            Color(0xFF3366CC), Color(0xFFDC3912), Color(0xFFFF9900),
            Color(0xFF109618), Color(0xFF990099), Color(0xFF0099C6),
        )
    }
}
