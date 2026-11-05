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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
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

/** The outsets of layers, which enlarge them around their content. */
class LayerOutsetsTest {
    private val context = WebCanvasGraphicsContext()
    private val layers = ArrayList<GraphicsLayer>()

    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    @AfterTest
    fun releaseLayers() {
        layers.forEach { context.releaseGraphicsLayer(it) }
    }

    /**
     * A [width] x [height] layer of [content], with [outsets] on its left and its right set before
     * it's recorded, as the owner of a layer sets them.
     */
    private fun layer(width: Int, height: Int, outsets: Int, content: DrawScope.() -> Unit): GraphicsLayer =
        context.createGraphicsLayer().also {
            it.setOutsets(outsets, 0, outsets, 0)
            it.record(Density(1f), LayoutDirection.Ltr, IntSize(width, height), content)
            layers.add(it)
        }

    /** Draws [layer] at [topLeft] into a new image of [size] x [size] pixels. */
    private fun image(layer: GraphicsLayer, size: Int, topLeft: IntOffset): ImageBitmap {
        layer.topLeft = topLeft
        val image = ImageBitmap(size, size)
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, Canvas(image), Size(size.toFloat(), size.toFloat()),
        ) {
            drawLayer(layer)
        }
        return image
    }

    @Test
    fun anOffscreenLayerKeepsWhatItDrawsWithinItsOutsets() {
        val layer = layer(40, 40, outsets = 10) {
            // Wider than the layer with its outsets.
            drawRect(Color.White, topLeft = Offset(-20f, 0f), size = Size(80f, 40f))
        }
        layer.compositingStrategy = CompositingStrategy.Offscreen
        val pixels = image(layer, size = 100, topLeft = IntOffset(30, 30)).toPixelMap()
        // With its outsets, the layer spans 20 to 80.
        assertEquals(0f, pixels[15, 50].alpha)
        assertEquals(Color.White, pixels[25, 50])
        assertEquals(Color.White, pixels[75, 50])
        assertEquals(0f, pixels[85, 50].alpha)
    }

    @Test
    fun aLayerInPerspectiveKeepsWhatItDrawsWithinItsOutsets() {
        // A red rectangle drawn across the outsets of a layer, and one filling a layer as large as
        // the first with its outsets, at the same place: turned around the same center.
        val outset = layer(40, 30, outsets = 10) {
            drawRect(Color.Red, topLeft = Offset(-10f, 0f), size = Size(60f, 30f))
        }
        val plain = layer(60, 30, outsets = 0) { drawRect(Color.Red) }
        outset.rotationY = 30f
        plain.rotationY = 30f
        val expected = image(plain, size = 100, topLeft = IntOffset(20, 35)).imageData()
        val actual = image(outset, size = 100, topLeft = IntOffset(30, 35)).imageData()
        // Inside the rectangle: its edges go through other transformations, which round apart.
        assertSamePixels(expected, actual, where = expected)
    }
}
