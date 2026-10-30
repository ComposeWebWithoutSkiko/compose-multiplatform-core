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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.CssColors
import androidx.compose.ui.webcanvas.dom.createElementCanvas
import androidx.compose.ui.webcanvas.dom.requireContext2D
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** The color matrices of slots of the atlas, applied with WebGL ([SlotColorMatrices]). */
class SlotColorMatricesTest {
    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    @Test
    fun theSlotsGoThroughTheirMatricesAsThroughThePixels() {
        val filters = listOf(
            Saturation,
            ColorFilter.lighting(Color(0xFF808080), Color(0xFF202020)),
            ColorFilter.tint(Color(0x80FF0000), BlendMode.Modulate),
            Swapped,
        )
        val step = SlotWidth + LayerAtlas.Gutter
        assertMatricesApplyAsThroughThePixels(
            pageWidth = filters.size * step,
            pageHeight = SlotHeight,
            slots = filters.mapIndexed { i, filter -> Slot(i * step, 0, SlotWidth, filter) },
        )
    }

    @Test
    fun slotsAnywhereOnThePageGoAnywhereInTheCanvas() {
        // Slots half as wide as a texture, so that the canvas has them on two shelves: each slot is
        // elsewhere in the canvas than on the page, down or across.
        val wide = WebGLRenderer.maxTextureSize / 2 + 1
        val belowWide = SlotHeight + LayerAtlas.Gutter
        assertMatricesApplyAsThroughThePixels(
            pageWidth = wide + LayerAtlas.Gutter + SlotWidth,
            pageHeight = 2 * SlotHeight + LayerAtlas.Gutter,
            slots = listOf(
                // At (0, 0) of the canvas, the others after it.
                Slot(wide + LayerAtlas.Gutter, belowWide, SlotWidth, Saturation),
                Slot(0, 0, wide, Swapped),
                // On the second shelf.
                Slot(0, belowWide, wide, Saturation),
            ),
        )
    }

    /** A slot of a page at ([x], [y]), [width] x [SlotHeight] pixels, with a color [filter]. */
    private class Slot(val x: Int, val y: Int, val width: Int, val filter: ColorFilter)

    /**
     * Draws the [slots] into a page of the atlas, applies their color matrices with
     * [SlotColorMatrices], and asserts that the page looks the same as through the pixels.
     */
    private fun assertMatricesApplyAsThroughThePixels(
        pageWidth: Int,
        pageHeight: Int,
        slots: List<Slot>,
    ) {
        assertTrue(WebGLRenderer.isAvailable(), "WebGL 2 isn't available")
        val page = AtlasPage().apply { prepare(pageWidth, pageHeight) }
        try {
            // The same slots through the pixels.
            val expected = requireContext2D(createElementCanvas(pageWidth, pageHeight))
            val jobs = slots.map { slot ->
                drawSlot(page.target.ctx, slot.x, slot.y)
                drawSlot(expected, slot.x, slot.y)
                applyColorFilterInPlace(
                    expected, slot.x, slot.y, slot.width, SlotHeight, slot.filter.webColorFilter,
                )
                FramePlan.Job().apply {
                    set(
                        NoContent, DisplayList.WholeList, 0, 0, slot.width, SlotHeight, Affine(),
                        WebPaint().apply { colorFilter = slot.filter },
                        isInPerspective = false, siblings = emptyList(),
                    )
                    place(page, slot.x, slot.y)
                }
            }
            assertTrue(SlotColorMatrices().apply(jobs), "Not applied")
            val difference = maxPremultipliedDifference(
                expected.getImageData(0, 0, pageWidth, pageHeight),
                page.target.ctx.getImageData(0, 0, pageWidth, pageHeight),
            )
            // The GPU computes in floats from premultiplied bytes, the CPU in doubles from
            // non-premultiplied ones.
            assertTrue(difference <= 2, "The slots differ from the pixels by up to $difference")
        } finally {
            page.release()
        }
    }

    /**
     * An opaque rectangle, a translucent one over it and beside it, and transparency, at ([x], [y]).
     */
    private fun drawSlot(ctx: Context2D, x: Int, y: Int) {
        ctx.fillStyle = CssColors.of(0xFF3366CC.toInt())
        ctx.fillRect(x.toDouble(), y.toDouble(), 14.0, 12.0)
        ctx.fillStyle = CssColors.of(0x80C04020.toInt())
        ctx.fillRect(x + 8.0, y + 2.0, 10.0, 8.0)
    }

    private object NoContent : JobContent {
        override fun replayContent(canvas: WebCanvas, contentOp: Int) = Unit
    }

    private companion object {
        const val SlotWidth = 20
        const val SlotHeight = 12

        // Made by the implementation that the tests register first.
        val Saturation by lazy { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.2f) }) }

        /** Channels swapped and offset, and a channel mixed into the alpha. */
        val Swapped by lazy {
            ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        0f, 1f, 0f, 0f, 20f,
                        1f, 0f, 0f, 0f, -30f,
                        0f, 0f, 1f, 0f, 0f,
                        0f, 0f, 0.5f, 0.5f, 10f,
                    )
                )
            )
        }
    }
}
