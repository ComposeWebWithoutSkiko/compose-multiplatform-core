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

package androidx.compose.ui.webcanvas.graphics

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.webcanvas.dom.CanvasElement
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.ImageData
import androidx.compose.ui.webcanvas.dom.JsStrings
import androidx.compose.ui.webcanvas.dom.createCanvas
import androidx.compose.ui.webcanvas.dom.createElementCanvas
import androidx.compose.ui.webcanvas.dom.deref
import androidx.compose.ui.webcanvas.dom.getContext2D
import androidx.compose.ui.webcanvas.dom.newIdentity
import androidx.compose.ui.webcanvas.dom.newWeakRef
import androidx.compose.ui.webcanvas.dom.readImageDataArgb
import androidx.compose.ui.webcanvas.dom.releaseCanvas
import kotlin.js.JsAny
import kotlin.js.unsafeCast
import kotlin.math.abs

/** The references to copies an image gathers before it drops those of the copies that are gone. */
private const val MaxCopyEntries = 100

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
     * Increases whenever the pixels change, so that derived data (e.g. a tinted copy) can be
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

    /**
     * Whether this image is always drawn whole, because its source takes no source rectangle: the
     * browser gives an SVG document without a size of its own the size of the canvas it's drawn on,
     * so [width] and [height] are not coordinates in it.
     */
    open val isDrawnWhole: Boolean get() = false

    /** The filtered copies, by [WebColorFilter.painted]. */
    private val filteredCopies = HashMap<Any, FilteredCopy>()

    /** A weak reference to a filtered copy, of the pixels of [version]. */
    private class FilteredCopy(val weakRef: JsAny, var version: Int)

    /** The [version] [filteredSource] last saw. */
    private var filteredVersion: Int = -1

    /** The number of references to copies, for tests. */
    internal val filteredCopyEntries: Int get() = filteredCopies.size

    /** What the filters know this image by, for its copies and its hold that they keep. */
    protected val copiesKey: JsAny by lazy { newIdentity() }

    /**
     * Returns this image with [filter] applied to each pixel, for a draw that multiplies its alpha
     * with [WebColorFilter.drawAlpha]. A copy lives as long as the filters that asked for it
     * ([WebColorFilter.keepFor]) rather than up to a budget: tints in use never evict each other,
     * and those no longer in use go with their filters. An image only finds its copies. When the
     * pixels change, a copy is drawn again into its canvas the next time it's asked for, so that an
     * image that changes in every frame doesn't make a canvas in every frame; one no longer asked for
     * keeps the older pixels until its filters go.
     */
    fun filteredSource(filter: WebColorFilter): JsAny? {
        if (filteredVersion != version) {
            onNewVersion()
            filteredVersion = version
        }
        return ownFilteredSource(filter) ?: filteredCopy(filter)
    }

    /** Forgets what was derived from the pixels of the previous [version], apart from the copies. */
    protected open fun onNewVersion() = Unit

    /** The pixels of this image as they are, if they can show [filter]. */
    protected open fun ownFilteredSource(filter: WebColorFilter): JsAny? = null

    private fun filteredCopy(filter: WebColorFilter): JsAny? {
        val painted = filter.painted
        val key = painted.key
        val kept = filteredCopies[key]
        val keptCopy = kept?.let { deref(it.weakRef) }
        if (kept != null && keptCopy != null) {
            if (kept.version != version) {
                val source = drawableRegionSource() ?: return null
                val canvas = keptCopy.unsafeCast<CanvasElement>()
                val ctx = getContext2D(canvas, willReadFrequently = colorFilterReadsPixels(painted)) ?: return null
                ctx.clearRect(0.0, 0.0, width.toDouble(), height.toDouble())
                drawThroughColorFilter(ctx, width, height, painted) { drawWhole(it, source) }
                kept.version = version
            }
            filter.keepFor(copiesKey, keptCopy)
            return keptCopy
        }
        val source = drawableRegionSource() ?: return null
        val copy = canvasThroughColorFilter(width, height, painted) { drawWhole(it, source) } ?: return null
        if (filteredCopies.size >= MaxCopyEntries) {
            filteredCopies.values.removeAll { deref(it.weakRef) == null }
        }
        filteredCopies[key] = FilteredCopy(newWeakRef(copy), version)
        filter.keepFor(copiesKey, copy)
        return copy
    }

    /** Draws the top-left [width] x [height] pixels of [source], the pixels of this image, into [ctx]. */
    private fun drawWhole(ctx: Context2D, source: JsAny) {
        val w = width.toDouble()
        val h = height.toDouble()
        ctx.drawImage(source, 0.0, 0.0, w, h, 0.0, 0.0, w, h)
    }

    /** Frees the copy made for [painted], if it's still there. */
    protected fun releaseFilteredCopy(painted: WebColorFilter) {
        val kept = filteredCopies.remove(painted.key) ?: return
        deref(kept.weakRef)?.let { releaseCanvas(it.unsafeCast<CanvasElement>()) }
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

/** This image as one that can be drawn into: one created with `ImageBitmap(width, height)`. */
internal fun ImageBitmap.asCanvasImage(): CanvasImageBitmap =
    requireNotNull(this as? CanvasImageBitmap) { "Cannot draw on immutable ImageBitmap" }

/**
 * An image backed by a canvas, created with `ImageBitmap(width, height)`. Compose draws into it: a
 * `<canvas>` element, whose context resolves the SVG filters of the draws (see
 * [createElementCanvas]).
 */
internal class CanvasImageBitmap(
    width: Int,
    height: Int,
    config: ImageBitmapConfig = ImageBitmapConfig.Argb8888,
    hasAlpha: Boolean = true,
    colorSpace: ColorSpace = ColorSpaces.Srgb,
) : WebImageBitmap(width, height, config, hasAlpha, colorSpace) {
    val canvas: CanvasElement = createElementCanvas(width, height)
    val context: Context2D =
        getContext2D(canvas, willReadFrequently = false) ?: error("Canvas 2D is not supported")

    override val isMutable: Boolean get() = true

    override fun drawableSource(): JsAny = canvas

    /** Called by the canvas drawing into this image. */
    fun markChanged() {
        version++
    }

    /** The target that every canvas drawing into this image draws through (see [DrawTarget]). */
    val drawTarget: DrawTarget =
        DrawTarget(context, originX = 0, originY = 0, width = width, height = height).also {
            it.onDraw = ::markChanged
        }

    /**
     * The key of the tint the pixels of this mask are in, if any, and a weak reference to the object
     * that the filters of that tint keep, which goes with the last of them.
     */
    private var heldTint: Any? = null
    private var heldBy: JsAny? = null

    override fun onNewVersion() {
        heldTint = null
        heldBy = null
    }

    /**
     * An [ImageBitmapConfig.Alpha8] image means its alpha alone, so a tint that replaces its colors
     * needs no copy: the canvas takes the tint, once, as long as no filter of another tint in use
     * holds it. The colors of a mask are then that tint: a draw with no filter shows it instead of
     * black, and a filter that doesn't replace them (a translucent [BlendMode.SrcOver]) sees it.
     */
    override fun ownFilteredSource(filter: WebColorFilter): JsAny? {
        val tint = filter.painted as? WebColorFilter.Tint ?: return null
        // A clip left open would tint a part of the pixels.
        if (config != ImageBitmapConfig.Alpha8 || tint.blendMode != BlendMode.SrcIn || drawTarget.hasSavedState) {
            return null
        }
        var holder = heldBy?.let { deref(it) }
        if (heldTint != tint.key) {
            if (holder != null) return null
            releaseFilteredCopy(tint)
            drawTarget.setPixelTransform()
            applyColorFilterInPlace(context, 0, 0, width, height, tint)
            heldTint = tint.key
        }
        if (holder == null) {
            holder = newIdentity()
            heldBy = newWeakRef(holder)
        }
        filter.keepFor(copiesKey, holder)
        return canvas
    }

    override fun readImageData(x: Int, y: Int, width: Int, height: Int): ImageData =
        context.getImageData(x, y, width, height)
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
        version++
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
 * Returns a new [width] x [height] canvas of what [draw] draws into its context, through [filter],
 * if any (see [drawThroughColorFilter]): a canvas element where the filter applies through SVG,
 * which only an element's context resolves.
 */
internal fun canvasThroughColorFilter(
    width: Int,
    height: Int,
    filter: WebColorFilter?,
    draw: (Context2D) -> Unit,
): CanvasElement? {
    if (width <= 0 || height <= 0) return null
    val throughSvg = filter != null && SvgColorFilters.appliesThroughSvg(filter)
    val canvas = if (throughSvg) createElementCanvas(width, height) else createCanvas(width, height)
    val ctx = getContext2D(canvas, willReadFrequently = filter != null && colorFilterReadsPixels(filter)) ?: return null
    drawThroughColorFilter(ctx, width, height, filter, draw)
    return canvas
}

/**
 * Draws with [draw] into [ctx], whose top-left [width] x [height] pixels it fills, through [filter],
 * if any, in a canvas made for it ([canvasThroughColorFilter]). The filter is applied on the GPU,
 * through an SVG color matrix as [draw] draws, or with a compositing operation after (tints), unless
 * the browser supports neither, in which case it goes through the pixels (see
 * [applyColorFilterInPlace]).
 */
internal fun drawThroughColorFilter(
    ctx: Context2D,
    width: Int,
    height: Int,
    filter: WebColorFilter?,
    draw: (Context2D) -> Unit,
) {
    if (filter != null && SvgColorFilters.appliesThroughSvg(filter)) {
        ctx.filter = checkNotNull(SvgColorFilters.filterValue(filter, ctx)) {
            "A canvas element resolves SVG filters"
        }
        draw(ctx)
        ctx.filter = JsStrings.none
        return
    }
    draw(ctx)
    if (filter != null) applyColorFilterInPlace(ctx, 0, 0, width, height, filter)
}
