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

package androidx.compose.ui.webcanvas

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.webcanvas.dom.createCanvas
import androidx.compose.ui.webcanvas.dom.getContext2D
import androidx.compose.ui.webcanvas.graphics.WebImageBitmap
import kotlin.js.JsAny
import kotlin.js.js

/**
 * An [ImageBitmap] of [image], a JS `ImageBitmap`. It draws from [image] itself, so that images
 * decoded by the browser reach Compose without their bytes.
 */
@InternalComposeUiApi
public fun composeImageBitmapOf(image: JsAny): ImageBitmap = BrowserImageBitmap(image)

/**
 * An [ImageBitmap] of [element], an `<img>` that has loaded an SVG document: the browser renders
 * the document at the size it's drawn at, so it stays sharp, and fills any shape. Draws show the
 * whole document, whatever their source rectangle is. Its size is the natural size of [element],
 * which for a document without a size of its own is a fit of its aspect ratio into 300 x 150.
 *
 * Fails for a document that taints the canvases it's drawn on, as one with a `<foreignObject>`
 * does: the renderer reads and uploads to WebGL the canvases of its atlas, which then would fail
 * for good.
 */
@InternalComposeUiApi
public fun svgImageBitmapOf(element: JsAny): ImageBitmap {
    val ctx = getContext2D(createCanvas(1, 1), willReadFrequently = true)
        ?: error("Canvas 2D is not supported")
    ctx.drawImage(element, 0.0, 0.0, 1.0, 1.0)
    require(runCatching { ctx.getImageData(0, 0, 1, 1) }.isSuccess) {
        "An SVG document that taints canvases, as one with a <foreignObject> does, can't be drawn"
    }
    return SvgImageBitmap(element)
}

/** An image that the browser holds: it's drawn from [source] itself. */
private open class BrowserImageBitmap(private val source: JsAny) : WebImageBitmap(
    widthOf(source),
    heightOf(source),
    ImageBitmapConfig.Argb8888,
    true,
    ColorSpaces.Srgb,
) {
    override val isMutable: Boolean get() = false

    override fun drawableSource(): JsAny = source
}

private class SvgImageBitmap(element: JsAny) : BrowserImageBitmap(element) {
    override val isDrawnWhole: Boolean get() = true
}

// language=js
private fun widthOf(image: JsAny): Int = js("image.width")

// language=js
private fun heightOf(image: JsAny): Int = js("image.height")
