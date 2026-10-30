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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.webcanvas.WebCanvasGraphicsContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The outlines of layers, which they're clipped to. */
class LayerOutlineTest {
    private lateinit var context: WebCanvasGraphicsContext

    @BeforeTest
    fun setUp() {
        registerWebCanvasComposeImplementation()
        context = WebCanvasGraphicsContext()
    }

    /** Draws [layer] into a new image of its size. */
    private fun frame(layer: GraphicsLayer): PixelMap {
        val image = ImageBitmap(40, 40)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(40f, 40f)) {
            drawLayer(layer)
        }
        return image.toPixelMap()
    }

    @Test
    fun aPathChangedAndSetAgainIsClippedTo() {
        val layer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(40, 40)) { drawRect(Color.White) }
        layer.clip = true
        val outline = Path().apply { addRect(Rect(0f, 0f, 20f, 40f)) }
        layer.setPathOutline(outline)
        val left = frame(layer)
        outline.reset()
        outline.addRect(Rect(20f, 0f, 40f, 40f))
        layer.setPathOutline(outline)
        val right = frame(layer)
        assertEquals(Color.White, left[10, 20])
        assertEquals(0f, left[30, 20].alpha)
        assertEquals(0f, right[10, 20].alpha)
        assertEquals(Color.White, right[30, 20])
        context.releaseGraphicsLayer(layer)
    }
}
