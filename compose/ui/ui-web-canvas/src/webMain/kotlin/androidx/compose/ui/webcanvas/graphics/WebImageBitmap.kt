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

package androidx.compose.ui.webcanvas.graphics

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.webcanvas.dom.CanvasElement
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.ImageData
import androidx.compose.ui.webcanvas.dom.JsStrings
import androidx.compose.ui.webcanvas.dom.createCanvas
import androidx.compose.ui.webcanvas.dom.createElementCanvas
import androidx.compose.ui.webcanvas.dom.createImageDataFromArgb
import androidx.compose.ui.webcanvas.dom.getContext2D
import androidx.compose.ui.webcanvas.dom.readImageDataArgb
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.math.abs

/**
 * An [ImageBitmap] of the web backend. Its pixels live in the browser: either in a canvas, which
 * Compose can draw into, or in an image source that the browser decodes asynchronously.
 */
internal abstract class WebImageBitmap(
    final override val width: Int,
    final override val height: Int,
    final override val config: ImageBitmapConfig,
    final override val hasAlpha: Boolean,
    final override val colorSpace: ColorSpace,
) : ImageBitmap {

    /**
     * Changes whenever the pixels change, so that derived data (e.g. a tinted copy) can be
     * recomputed.
     */
    var version: Int = 0
        protected set

    /** Whether Compose can draw into this image through `Canvas(image)`. */
    abstract val isMutable: Boolean

    /**
     * The `CanvasImageSource` to draw this image with, or `null` if its pixels aren't available yet
     * (e.g. the image is still being decoded).
     */
    abstract fun drawableSource(): JsAny?

    /**
     * Like [drawableSource], but the source may be larger than the image, which is then its
     * top-left [width] x [height] part: enough for draws that take a source rectangle, and cheaper
     * for images shown through a shared source ([FramebufferImageBitmap]).
     */
    open fun drawableRegionSource(): JsAny? = drawableSource()

    private var filteredKey: Any? = null
    private var filteredVersion: Int = -1
    private var filteredSource: CanvasElement? = null

    /**
     * Returns a copy of this image with [filter] applied to each pixel. The last result is cached,
     * since an image is usually drawn with the same filter (e.g. an icon tint) over and over.
     */
    fun filteredSource(filter: WebColorFilter): JsAny? {
        if (filteredKey == filter.key && filteredVersion == version) {
            filteredSource?.let { return it }
        }
        val source = drawableRegionSource() ?: return null
        val result = applyColorFilter(source, width, height, filter) ?: return null
        filteredKey = filter.key
        filteredVersion = version
        filteredSource = result
        return result
    }

    override fun prepareToDraw() = Unit

    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) {
        // Same checks as android.graphics.Bitmap.getPixels.
        val lastScanline: Int = bufferOffset + (height - 1) * stride
        require(startX >= 0 && startY >= 0)
        require(width > 0 && startX + width <= this.width)
        require(height > 0 && startY + height <= this.height)
        require(abs(stride) >= width)
        require(bufferOffset >= 0 && bufferOffset + width <= buffer.size)
        require(lastScanline >= 0 && lastScanline + width <= buffer.size)

        val imageData = readImageData(startX, startY, width, height)
        if (imageData == null) {
            // The pixels aren't available yet.
            for (y in 0 until height) {
                buffer.fill(0, bufferOffset + y * stride, bufferOffset + y * stride + width)
            }
            return
        }
        readImageDataArgb(imageData, buffer, bufferOffset, stride)
    }

    protected open fun readImageData(x: Int, y: Int, width: Int, height: Int): ImageData? {
        val source = drawableRegionSource() ?: return null
        val canvas = createCanvas(width, height)
        val ctx = getContext2D(canvas, willReadFrequently = true) ?: return null
        ctx.drawImage(source, -x.toDouble(), -y.toDouble())
        return ctx.getImageData(0, 0, width, height)
    }
}

/** An image backed by a canvas, created with `ImageBitmap(width, height)`. Compose draws into it. */
internal class CanvasImageBitmap(
    width: Int,
    height: Int,
    config: ImageBitmapConfig = ImageBitmapConfig.Argb8888,
    hasAlpha: Boolean = true,
    colorSpace: ColorSpace = ColorSpaces.Srgb,
) : WebImageBitmap(width, height, config, hasAlpha, colorSpace) {
    val canvas: CanvasElement = createCanvas(width, height)
    val context: Context2D =
        getContext2D(canvas, willReadFrequently = false) ?: error("Canvas 2D is not supported")

    override val isMutable: Boolean get() = true

    override fun drawableSource(): JsAny = canvas

    /** Called by the canvas drawing into this image. */
    fun markChanged() {
        version++
    }

    override fun readImageData(x: Int, y: Int, width: Int, height: Int): ImageData =
        context.getImageData(x, y, width, height)

    /** Replaces the pixels in the given rectangle with ARGB values. */
    fun writePixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        context.putImageData(createImageDataFromArgb(pixels, offset, stride, width, height), x, y)
        markChanged()
    }
}

/**
 * The frames rendered into the bottom-left [width] x [height] pixels of [framebuffer], a framebuffer
 * of the shared WebGL context ([SharedWebGL]): what Compose draws for embedded WebGL content.
 *
 * Frames reach the 2D canvases through the drawing buffer of the shared context. A frame is copied
 * there when it's drawn, and drawn from there for as long as nothing else uses the drawing buffer.
 * When something else does, and the same frame has to be drawn again (it didn't change, but Compose
 * draws a new frame), it's copied into a canvas of its own, so that drawing it again is a single
 * draw.
 */
internal class FramebufferImageBitmap(
    private val framebuffer: JsAny,
    width: Int,
    height: Int,
) : WebImageBitmap(width, height, ImageBitmapConfig.Argb8888, true, ColorSpaces.Srgb) {
    private var isReleased = false

    /** The generation of the shared context [framebuffer] belongs to. */
    private val contextGeneration = SharedWebGL.generation

    /** The last frame copied into the drawing buffer. */
    private var handedOverVersion = -1

    private var snapshot: CanvasElement? = null
    private var snapshotContext: Context2D? = null
    private var snapshotVersion = -1

    override val isMutable: Boolean get() = false

    /** Called after a new frame was rendered into the framebuffer. */
    fun invalidate() {
        version++
    }

    /** Called when the framebuffer goes away: the image shows nothing from then on. */
    fun release() {
        isReleased = true
        snapshot = null
        snapshotContext = null
        SharedWebGL.forget(this)
    }

    override fun drawableRegionSource(): JsAny? {
        if (isReleased) return null
        SharedWebGL.canvasHolding(this, version)?.let { return it }
        if (snapshotVersion == version) return snapshot
        // After a context loss the framebuffer is gone: only a copy of its last frame remains.
        if (SharedWebGL.generation != contextGeneration) return snapshot
        val drawingBuffer =
            SharedWebGL.handOver(framebuffer, width, height, this, version) ?: return null
        if (handedOverVersion != version) {
            handedOverVersion = version
            return drawingBuffer
        }
        // The frame was in the drawing buffer before, and something else has used it since: keep a
        // copy, so that drawing the frame again doesn't need the drawing buffer.
        return updateSnapshot(drawingBuffer)
    }

    override fun drawableSource(): JsAny? {
        // Patterns repeat the whole source, so they need a source of exactly the image size.
        if (isReleased) return null
        if (snapshotVersion == version) return snapshot
        if (SharedWebGL.generation != contextGeneration) return snapshot
        val source = drawableRegionSource() ?: return null
        return if (source === snapshot) source else updateSnapshot(source)
    }

    private fun updateSnapshot(source: JsAny): JsAny? {
        var canvas = snapshot
        var ctx = snapshotContext
        if (canvas == null || ctx == null) {
            canvas = createCanvas(width, height)
            ctx = getContext2D(canvas, willReadFrequently = false) ?: return null
            snapshot = canvas
            snapshotContext = ctx
        }
        val w = width.toDouble()
        val h = height.toDouble()
        // A copy replaces the previous frame in a single draw, transparent pixels included.
        ctx.globalCompositeOperation = CopyOperation
        ctx.drawImage(source, 0.0, 0.0, w, h, 0.0, 0.0, w, h)
        ctx.globalCompositeOperation = SourceOverOperation
        snapshotVersion = version
        return canvas
    }
}

/**
 * Applies [filter] to the top-left [width] x [height] pixels of [source] and returns the result in
 * a new canvas. The filter is applied on the GPU, with compositing operations (tints) or an SVG
 * color matrix, unless the browser supports neither, in which case it goes through the pixels.
 */
internal fun applyColorFilter(
    source: JsAny,
    width: Int,
    height: Int,
    filter: WebColorFilter,
): CanvasElement? {
    if (width <= 0 || height <= 0) return null
    val w = width.toDouble()
    val h = height.toDouble()
    val tintComposite = (filter as? WebColorFilter.Tint)?.let { compositeOperationFor(it.blendMode) }
    if (filter is WebColorFilter.Tint && tintComposite != null && filter.appliesAsComposite) {
        val canvas = createCanvas(width, height)
        val ctx = getContext2D(canvas, willReadFrequently = false) ?: return null
        ctx.drawImage(source, 0.0, 0.0, w, h, 0.0, 0.0, w, h)
        // A blend color filter blends the filter color (source) into each pixel (destination).
        ctx.globalCompositeOperation = tintComposite
        ctx.fillStyle = androidx.compose.ui.webcanvas.dom.CssColors.of(filter.color.toArgb())
        ctx.fillRect(0.0, 0.0, w, h)
        return canvas
    }
    SvgColorFilters.filterValue(filter)?.let { filterValue ->
        val canvas = createElementCanvas(width, height)
        val ctx = getContext2D(canvas, willReadFrequently = false) ?: return null
        ctx.filter = filterValue
        ctx.drawImage(source, 0.0, 0.0, w, h, 0.0, 0.0, w, h)
        ctx.filter = JsStrings.none
        return canvas
    }
    val canvas = createCanvas(width, height)
    val ctx = getContext2D(canvas, willReadFrequently = true) ?: return null
    ctx.drawImage(source, 0.0, 0.0, w, h, 0.0, 0.0, w, h)
    val pixels = IntArray(width * height)
    readImageDataArgb(ctx.getImageData(0, 0, width, height), pixels, 0, width)
    for (i in pixels.indices) {
        pixels[i] = filter.filter(pixels[i])
    }
    ctx.putImageData(createImageDataFromArgb(pixels, 0, width, width, height), 0, 0)
    return canvas
}
