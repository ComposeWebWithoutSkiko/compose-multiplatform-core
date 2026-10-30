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
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.CssColors
import androidx.compose.ui.webcanvas.dom.createElementCanvas
import androidx.compose.ui.webcanvas.dom.requireContext2D
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** The images of blurs of layers, blurred through their pixels with WebGL ([SlotPixelBlurs]). */
class SlotPixelBlursTest {
    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    @Test
    fun theImagesAreBlurredAsThroughThePixels() {
        val border = PreparedBlur.Border
        assertBlurredAsThroughThePixels(
            pageWidth = 40 + 30 + 4 * border + LayerAtlas.Gutter,
            pageHeight = 24 + 18 + 4 * border + LayerAtlas.Gutter,
            images = listOf(
                Image(0, 0, 40, 24, intArrayOf(1, 2, 2), intArrayOf(2, 2, 3)),
                // Across a gutter, of other radii, one of the axes not blurred.
                Image(40 + 2 * border + LayerAtlas.Gutter, 0, 30, 20, intArrayOf(0, 0, 1), intArrayOf(0, 0, 0)),
                // On the next shelf of the page, the other way.
                Image(0, 24 + 2 * border + LayerAtlas.Gutter, 26, 18, intArrayOf(0, 0, 0), intArrayOf(1, 1, 1)),
            ),
        )
    }

    @Test
    fun imagesAnywhereOnThePageGoAnywhereInTheCanvas() {
        // Slots half as wide as a texture, so that the canvas has them on two shelves: each image is
        // elsewhere in the canvas than on the page, down, up or across.
        val border = PreparedBlur.Border
        val wideSlot = WebGLRenderer.maxTextureSize / 2 + 1
        val slotHeight = ImageHeight + 2 * border
        val belowSlot = slotHeight + LayerAtlas.Gutter
        val radii = intArrayOf(1, 1, 2)
        assertBlurredAsThroughThePixels(
            pageWidth = wideSlot + LayerAtlas.Gutter + NarrowWidth + 2 * border,
            pageHeight = 2 * slotHeight + LayerAtlas.Gutter,
            images = listOf(
                // At (0, 0) of the canvas, the others after it.
                Image(wideSlot + LayerAtlas.Gutter, 0, NarrowWidth, ImageHeight, radii, radii),
                Image(0, belowSlot, wideSlot - 2 * border, ImageHeight, radii, radii),
                // On the second shelf.
                Image(0, 0, wideSlot - 2 * border, ImageHeight, radii, radii),
            ),
        )
    }

    /**
     * An image at ([x], [y]) of a page with its border, [width] x [height] pixels, blurred by the
     * boxes of [radiiX] along its rows and [radiiY] along its columns.
     */
    private class Image(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val radiiX: IntArray,
        val radiiY: IntArray,
    )

    /**
     * Draws the [images] into a page of the atlas, blurs them with [SlotPixelBlurs], and asserts that
     * the page looks the same as with them blurred through the pixels.
     */
    private fun assertBlurredAsThroughThePixels(pageWidth: Int, pageHeight: Int, images: List<Image>) {
        val border = PreparedBlur.Border
        assertTrue(WebGLRenderer.isAvailable(), "WebGL 2 isn't available")
        val page = AtlasPage().apply { prepare(pageWidth, pageHeight) }
        try {
            // The same images through the pixels.
            val expected = requireContext2D(createElementCanvas(pageWidth, pageHeight))
            val jobs = images.map { image ->
                drawImage(page.target.ctx, image.x + border, image.y + border, image.width, image.height)
                drawImage(expected, image.x + border, image.y + border, image.width, image.height)
                FramePlan.Job().apply {
                    set(
                        NoContent, DisplayList.WholeList, 0, 0, image.width, image.height, Affine(),
                        paint = null, isInPerspective = false, siblings = emptyList(),
                    )
                    blur.throughPixels = true
                    blur.imageWidth = image.width
                    blur.imageHeight = image.height
                    image.radiiX.copyInto(blur.radiiX)
                    image.radiiY.copyInto(blur.radiiY)
                    blur.place(page, image.x, image.y)
                }
            }
            val pixels = expected.getImageData(0, 0, pageWidth, pageHeight)
            for (job in jobs) Effects.blurImage(pixels, job.blur.x + border, job.blur.y + border, job.blur)
            expected.putImageData(pixels, 0, 0)
            assertTrue(SlotPixelBlurs().blur(jobs), "Not blurred")
            val difference = maxPremultipliedDifference(
                expected.getImageData(0, 0, pageWidth, pageHeight),
                page.target.ctx.getImageData(0, 0, pageWidth, pageHeight),
            )
            assertTrue(difference <= 1, "The images differ from the pixels by up to $difference")
        } finally {
            page.release()
        }
    }

    /**
     * A [width] x [height] image at ([x], [y]): opaque up to its left and top edges, translucent
     * over it and beyond, up to its right edge, and transparent at its bottom.
     */
    private fun drawImage(ctx: Context2D, x: Int, y: Int, width: Int, height: Int) {
        ctx.fillStyle = CssColors.of(0xFF3366CC.toInt())
        ctx.fillRect(x.toDouble(), y.toDouble(), width * 0.6, height * 0.7)
        ctx.fillStyle = CssColors.of(0x80C04020.toInt())
        ctx.fillRect(x + width * 0.4, y + 2.0, width * 0.6, height * 0.5)
    }

    private object NoContent : JobContent {
        override fun replayContent(canvas: WebCanvas, contentOp: Int) = Unit
    }

    private companion object {
        const val NarrowWidth = 20
        const val ImageHeight = 10
    }
}
