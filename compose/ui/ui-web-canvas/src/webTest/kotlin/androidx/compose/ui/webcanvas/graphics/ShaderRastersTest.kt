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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositeShader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.webcanvas.dom.createElementCanvas
import androidx.compose.ui.webcanvas.dom.requireContext2D
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Draws of a shader drawn as an image at other device positions reuse its image, kept while the
 * frames draw it (see [WebShader.keptRaster]).
 */
class ShaderRastersTest {
    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    /** A blend of two gradients over a card, which no fill style draws. */
    private fun composite(): Shader = CompositeShader(
        LinearGradientShader(Offset.Zero, Offset(CardWidth, 0f), listOf(Color.Red, Color.Blue)),
        LinearGradientShader(Offset.Zero, Offset(0f, CardHeight), listOf(Color.White, Color.DarkGray)),
        BlendMode.Multiply,
    )

    private fun saturation(level: Float) = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(level) })

    // Made once the implementation is registered.
    private val muted by lazy { saturation(0.2f) }
    private val vivid by lazy { saturation(0.8f) }

    /**
     * Draws a [width] x [height] card filled with [shader] through [filter], moved by [x] past
     * [Margin] and set up by [transform]. The margin keeps the pixels that antialiasing reaches into
     * inside the image: an edge would clip them, and the clipped card would get an image of its own.
     */
    private fun Canvas.drawCard(
        shader: Shader,
        filter: ColorFilter?,
        x: Float,
        width: Float = CardWidth,
        height: Float = CardHeight,
        transform: Canvas.() -> Unit = {},
    ) {
        save()
        translate(Margin + x, Margin)
        transform()
        drawRect(0f, 0f, width, height, Paint().also {
            it.shader = shader
            it.colorFilter = filter
        })
        restore()
    }

    @Test
    fun cardsAtOtherPositionsAreDrawnFromAnImageForEachFilter() {
        // Twice in a row through each filter, and alternating.
        val filters = listOf(muted, muted, vivid, muted, vivid, vivid)
        val shared = composite()
        val cached = ImageBitmap(Width, Height)
        val canvas = Canvas(cached)
        filters.forEachIndexed { i, filter -> canvas.drawCard(shared, filter, x = i * CardStep) }
        assertEquals(2, shared.webShader.rasters?.size)
        // A shader of its own for each card, drawn from an image of its own.
        val apart = ImageBitmap(Width, Height)
        val apartCanvas = Canvas(apart)
        filters.forEachIndexed { i, filter -> apartCanvas.drawCard(composite(), filter, x = i * CardStep) }
        assertSamePixels(apart.imageData(), cached.imageData())
    }

    @Test
    fun scaledAndRotatedCardsAtOtherPositionsAreDrawnFromOneImage() {
        // A quarter turn of exact values, which rotate() computes with a cosine a little off 0,
        // moved by the height of the card, so that it stays in the image.
        val quarterTurn = Matrix().apply {
            values[Matrix.ScaleX] = 0f
            values[Matrix.SkewY] = 1f
            values[Matrix.SkewX] = -1f
            values[Matrix.ScaleY] = 0f
        }
        val transforms = listOf<Pair<String, Canvas.() -> Unit>>(
            "scaled by two" to { scale(2f, 2f) },
            "turned a quarter" to {
                translate(CardHeight, 0f)
                concat(quarterTurn)
            },
        )
        for ((name, transform) in transforms) {
            val xs = listOf(0f, Width / 2f)
            val shared = composite()
            val cached = ImageBitmap(Width, Height)
            val canvas = Canvas(cached)
            for (x in xs) canvas.drawCard(shared, vivid, x, transform = transform)
            assertEquals(1, shared.webShader.rasters?.size, name)
            val apart = ImageBitmap(Width, Height)
            val apartCanvas = Canvas(apart)
            for (x in xs) apartCanvas.drawCard(composite(), vivid, x, transform = transform)
            assertSamePixels(apart.imageData(), cached.imageData(), what = "$name: ")
        }
    }

    @Test
    fun aCardOffByAnotherFractionOfAPixelHasAnImageOfItsOwn() {
        // Of the same size, 27 pixels wide: only the transformations of their images differ.
        val shared = composite()
        val canvas = Canvas(ImageBitmap(Width, Height))
        canvas.drawCard(shared, vivid, x = 0.25f)
        canvas.drawCard(shared, vivid, x = CardStep + 0.5f)
        assertEquals(2, shared.webShader.rasters?.size)
    }

    @Test
    fun aCardThatTheEdgeCutsHasAnImageOfItsOwn() {
        // The edge of the image clips the last column that the edge of the card reaches into: the
        // transformation of its image is that of the first card, its size is a pixel less.
        val xs = listOf(0f, Width - Margin - CardWidth)
        val shared = composite()
        val cached = ImageBitmap(Width, Height)
        val canvas = Canvas(cached)
        for (x in xs) canvas.drawCard(shared, vivid, x)
        assertEquals(2, shared.webShader.rasters?.size)
        val apart = ImageBitmap(Width, Height)
        val apartCanvas = Canvas(apart)
        for (x in xs) apartCanvas.drawCard(composite(), vivid, x)
        assertSamePixels(apart.imageData(), cached.imageData())
    }

    @Test
    fun cardsOnATargetWithAnOriginAreDrawnAsAtNone() {
        val originX = 40
        val originY = 24
        val element = createElementCanvas(Width, Height)
        val ctx = requireContext2D(element, willReadFrequently = true)
        val canvas = WebCanvas(DrawTarget(ctx, originX, originY, Width, Height))
        canvas.translate(originX.toFloat(), originY.toFloat())
        val shared = composite()
        for (i in 0 until 3) canvas.drawCard(shared, muted, x = i * CardStep)
        canvas.finish()
        assertEquals(1, shared.webShader.rasters?.size)
        val expected = ImageBitmap(Width, Height)
        val expectedCanvas = Canvas(expected)
        for (i in 0 until 3) expectedCanvas.drawCard(composite(), muted, x = i * CardStep)
        assertSamePixels(expected.imageData(), ctx.getImageData(0, 0, Width, Height))
    }

    @Test
    fun theImagesNotDrawnSinceThePreviousFrameGoAndThoseDrawnInEveryFrameStay() {
        val shared = composite()
        val canvas = Canvas(ImageBitmap(Width, Height))
        var start = FrameTicks.startFrame(0.0)
        canvas.drawCard(shared, muted, x = 0f)
        canvas.drawCard(shared, vivid, x = CardStep)
        val rasters = checkNotNull(shared.webShader.rasters)
        val mutedImage = rasters[0]
        val vividImage = rasters[1]
        start = FrameTicks.startFrame(start)
        canvas.drawCard(shared, muted, x = 0f)
        FrameTicks.startFrame(start)
        // A new image, of another filter, after a frame without the vivid card.
        canvas.drawCard(shared, saturation(0.5f), x = 2 * CardStep)
        canvas.drawCard(shared, muted, x = 0f)
        assertEquals(2, rasters.size)
        assertSame(mutedImage, rasters[0])
        assertFalse(vividImage in rasters)
    }

    @Test
    fun theImagesOfTheShaderAsItWasGoWhenItChanges() {
        val tile = ImageBitmap(4, 4)
        Canvas(tile).drawRect(0f, 0f, 4f, 4f, Paint().also { it.color = Color.Red })
        val shader = CompositeShader(
            ImageShader(tile, TileMode.Repeated, TileMode.Repeated),
            LinearGradientShader(Offset.Zero, Offset(CardWidth, 0f), listOf(Color.White, Color.DarkGray)),
            BlendMode.Multiply,
        )
        val canvas = Canvas(ImageBitmap(Width, Height))
        canvas.drawCard(shader, muted, x = 0f)
        val rasters = checkNotNull(shader.webShader.rasters)
        val before = rasters[0]
        Canvas(tile).drawRect(0f, 0f, 4f, 4f, Paint().also { it.color = Color.Blue })
        canvas.drawCard(shader, muted, x = 0f)
        assertEquals(1, rasters.size)
        assertNotSame(before, rasters[0])
    }

    @Test
    fun pastTheBudgetAnImageIsDrawnWithoutBeingKept() {
        // Images of 1049 x 1048 pixels: three fit the budget of 4 Mpx, a fourth doesn't.
        val shared = composite()
        val canvas = Canvas(ImageBitmap(LargeImageSize, LargeImageSize))
        for (fraction in Fractions) canvas.drawCard(shared, vivid, fraction, LargeCard, LargeCard)
        assertEquals(3, shared.webShader.rasters?.size)
        canvas.drawCard(shared, vivid, Fractions.last(), LargeCard, LargeCard)
        assertEquals(3, shared.webShader.rasters?.size, "the image past the budget was kept when drawn again")
    }

    @Test
    fun twoImagesAreKeptWhateverTheirSize() {
        // Images of 1502 x 1502 pixels: the second goes past the budget and is kept, a third isn't.
        val shared = composite()
        val canvas = Canvas(ImageBitmap(HugeImageSize, HugeImageSize))
        canvas.drawCard(shared, muted, 0f, HugeCard, HugeCard)
        canvas.drawCard(shared, vivid, 0f, HugeCard, HugeCard)
        val rasters = checkNotNull(shared.webShader.rasters)
        assertEquals(2, rasters.size)
        val mutedImage = rasters[0]
        canvas.drawCard(shared, saturation(0.5f), 0f, HugeCard, HugeCard)
        assertEquals(2, rasters.size, "a third image past the budget was kept")
        canvas.drawCard(shared, muted, 0f, HugeCard, HugeCard)
        assertEquals(2, rasters.size)
        assertSame(mutedImage, rasters[0])
    }

    @Test
    fun theImagesThatGoMakeRoomInTheBudget() {
        // Images of 1049 x 1048 pixels: with the one not drawn since the previous frame gone, a new
        // one fits beside the two drawn in it.
        val (first, second, third, fourth) = Fractions
        val shared = composite()
        val canvas = Canvas(ImageBitmap(LargeImageSize, LargeImageSize))
        var start = FrameTicks.startFrame(0.0)
        for (fraction in listOf(first, second, third)) canvas.drawCard(shared, vivid, fraction, LargeCard, LargeCard)
        val rasters = checkNotNull(shared.webShader.rasters)
        val thirdImage = rasters[2]
        start = FrameTicks.startFrame(start)
        canvas.drawCard(shared, vivid, first, LargeCard, LargeCard)
        canvas.drawCard(shared, vivid, second, LargeCard, LargeCard)
        FrameTicks.startFrame(start)
        canvas.drawCard(shared, vivid, fourth, LargeCard, LargeCard)
        assertEquals(3, rasters.size)
        assertFalse(thirdImage in rasters)
    }

    private companion object {
        const val CardWidth = 24f
        const val CardHeight = 16f
        const val CardStep = 32f
        const val Margin = 4f
        const val Width = 200
        const val Height = 56

        /** A card whose images, of about 1.1 Mpx, fit the budget three times, at [Fractions]. */
        const val LargeCard = 1046f
        const val LargeImageSize = 1060

        /** A card whose images, of about 2.3 Mpx, fit the budget once. */
        const val HugeCard = 1500f
        const val HugeImageSize = 1510

        /** Fractions of a pixel that give a card at one position images of their own. */
        val Fractions = listOf(0.125f, 0.25f, 0.5f, 0.75f)
    }
}
