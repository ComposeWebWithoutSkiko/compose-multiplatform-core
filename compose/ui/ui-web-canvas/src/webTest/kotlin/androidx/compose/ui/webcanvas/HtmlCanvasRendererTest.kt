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

package androidx.compose.ui.webcanvas

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.webcanvas.graphics.webColorFilter
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Frames drawn into the `<canvas>` element of a window, wherever it is in the page: color filters
 * of layers apply through SVG filters, which the canvas finds only in its own tree.
 */
class HtmlCanvasRendererTest {
    private val graphicsContext = WebCanvasGraphicsContext()
    private val layers = ArrayList<GraphicsLayer>()
    private val canvas = createCanvasElement(Width, Height)

    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    @AfterTest
    fun cleanUp() {
        layers.forEach { graphicsContext.releaseGraphicsLayer(it) }
        removeFromPage(canvas)
    }

    // Created once the graphics implementation is registered.
    private val grayFilter by lazy { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }

    /** A red square, filtered gray, at ([x], 0), blurred too if [blurred]. */
    private fun graySquare(x: Int, blurred: Boolean): GraphicsLayer =
        graphicsContext.createGraphicsLayer().also {
            it.record(Density(1f), LayoutDirection.Ltr, IntSize(SquareSize, SquareSize)) {
                drawRect(Color.Red)
            }
            it.topLeft = IntOffset(x, 0)
            it.colorFilter = grayFilter
            if (blurred) it.renderEffect = BlurEffect(2f, 2f, TileMode.Decal)
            layers.add(it)
        }

    private fun HtmlCanvasRenderer.drawSquares() {
        val plain = graySquare(0, blurred = false)
        val blurred = graySquare(BlurredX, blurred = true)
        render { canvas: Canvas ->
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(Width.toFloat(), Height.toFloat())) {
                drawLayer(plain)
                drawLayer(blurred)
            }
        }
    }

    private fun assertSquaresAreGray() {
        val expected = grayFilter.webColorFilter.filter(Color.Red.toArgb())
        val center = SquareSize / 2
        for (x in listOf(center, BlurredX + center)) {
            val actual = readPixelArgb(canvas, x, center)
            assertTrue(isClose(expected, actual), "($x, $center): ${hex(actual)} instead of ${hex(expected)}")
        }
    }

    @Test
    fun colorFiltersApplyInAShadowRoot() {
        appendToShadowRoot(canvas)
        HtmlCanvasRenderer(canvas).drawSquares()
        assertSquaresAreGray()
    }

    @Test
    fun colorFiltersApplyInTheDocument() {
        appendToBody(canvas)
        HtmlCanvasRenderer(canvas).drawSquares()
        assertSquaresAreGray()
    }

    @Test
    fun colorFiltersApplyAfterTheCanvasMovesIntoAShadowRoot() {
        appendToBody(canvas)
        val renderer = HtmlCanvasRenderer(canvas)
        renderer.drawSquares()
        assertSquaresAreGray()
        appendToShadowRoot(canvas)
        renderer.drawSquares()
        assertSquaresAreGray()
    }

    private fun isClose(expected: Int, actual: Int): Boolean =
        (0 until 32 step 8).all { shift ->
            abs(((expected ushr shift) and 0xFF) - ((actual ushr shift) and 0xFF)) <= Tolerance
        }

    private fun hex(argb: Int): String = argb.toUInt().toString(16)

    private companion object {
        const val SquareSize = 20
        const val BlurredX = 40
        const val Width = 70
        const val Height = 20

        /** The rounding of 8-bit channels, applied once more or less. */
        const val Tolerance = 2
    }
}

// language=js
private fun createCanvasElement(width: Int, height: Int): JsAny = js(
    "{ const canvas = document.createElement('canvas'); canvas.width = width; canvas.height = height; return canvas; }"
)

// language=js
private fun appendToBody(canvas: JsAny): Unit = js("{ document.body.appendChild(canvas); }")

/** Moves [canvas] into the open shadow root of a new element of the page. */
// language=js
private fun appendToShadowRoot(canvas: JsAny): Unit = js(
    """{
        const host = document.createElement('div');
        document.body.appendChild(host);
        host.attachShadow({ mode: 'open' }).appendChild(canvas);
        canvas.composeTestShadowHost = host;
    }"""
)

// language=js
private fun removeFromPage(canvas: JsAny): Unit = js(
    "{ canvas.remove(); if (canvas.composeTestShadowHost) canvas.composeTestShadowHost.remove(); }"
)

// language=js
private fun readPixelArgb(canvas: JsAny, x: Int, y: Int): Int = js(
    """{
        const d = canvas.getContext('2d').getImageData(x, y, 1, 1).data;
        return ((d[3] << 24) | (d[0] << 16) | (d[1] << 8) | d[2]) | 0;
    }"""
)
