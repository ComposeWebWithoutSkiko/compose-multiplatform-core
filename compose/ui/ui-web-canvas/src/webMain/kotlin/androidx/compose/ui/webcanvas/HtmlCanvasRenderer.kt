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

@file:OptIn(ExperimentalWasmJsInterop::class)

package androidx.compose.ui.webcanvas

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.webcanvas.dom.CanvasElement
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.getContext2D
import androidx.compose.ui.webcanvas.graphics.DrawTarget
import androidx.compose.ui.webcanvas.graphics.ImageDecoding
import androidx.compose.ui.webcanvas.graphics.WebCanvas
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.unsafeCast

/**
 * Renders Compose frames into an HTML `<canvas>` element through its 2D context.
 *
 * @param canvasElement the `HTMLCanvasElement` (or `OffscreenCanvas`) to render into
 */
@InternalComposeUiApi
class HtmlCanvasRenderer(canvasElement: JsAny) {
    private val element: CanvasElement = canvasElement.unsafeCast<CanvasElement>()
    private val context: Context2D = getContext2D(canvasElement, willReadFrequently = false)
        ?: error("The browser doesn't support the Canvas 2D API")
    private val target = DrawTarget(context, 0, 0, element.width, element.height)
    private val canvas = WebCanvas(target)
    private var isRendering = false

    /**
     * Clears the canvas and lets [block] draw a frame into it. The canvas is in the pixel
     * coordinates of the element's drawing buffer.
     */
    fun render(block: (Canvas) -> Unit) {
        check(!isRendering) { "render() can't be called recursively" }
        isRendering = true
        try {
            val width = element.width
            val height = element.height
            if (target.width != width || target.height != height) {
                target.width = width
                target.height = height
            }
            // Resizing the canvas resets its context, so start from a clean slate every frame.
            target.invalidateState()
            context.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            context.clearRect(0.0, 0.0, width.toDouble(), height.toDouble())
            canvas.reset(target)
            block(canvas)
        } finally {
            // Reset the flag first: if the frame failed, finishing may fail too, and the renderer
            // must stay usable for the next frames.
            isRendering = false
            canvas.finish()
        }
    }
}

/**
 * Registers [listener] to be called whenever an image decoded with `decodeToImageBitmap` gets its
 * pixels (browsers decode asynchronously), so that the window can draw a new frame.
 *
 * @return a function that unregisters the listener
 */
@InternalComposeUiApi
fun addImageDecodedListener(listener: () -> Unit): () -> Unit {
    ImageDecoding.addListener(listener)
    return { ImageDecoding.removeListener(listener) }
}
