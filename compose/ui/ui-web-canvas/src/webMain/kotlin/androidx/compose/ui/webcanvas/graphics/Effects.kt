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
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.webcanvas.dom.CanvasFeatures
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.ImageData
import androidx.compose.ui.webcanvas.dom.JsStrings
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.js
import kotlin.js.toJsString
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** Applies [WebRenderEffect]s to the pixels of a layer. */
internal object Effects {
    /** The layer that the effect being applied reads. */
    private val layer = EffectLayer()

    /**
     * Draws the [width] x [height] area of [source], a layer, at ([sourceX], [sourceY]) with
     * [effect] applied, at ([x], [y]) of [ctx] in pixels. The caller sets up the alpha and
     * compositing operation of [ctx], and clips it to the layer area, like Skia does, in a state of
     * [ctx] it restores after: a blur through the pixels changes the image smoothing of [ctx].
     * [matrix] is the transformation of the content of the layer, whose pixels start at ([left],
     * [top]): it scales the effect parameters, and places shaders, which are in the space of the
     * content, whatever part of it the area holds. What [job] prepared ahead is used (see [plan]).
     *
     * The last effect is applied while drawing into [ctx] (a blur through `ctx.filter`, an offset
     * by moving the draw), so it costs no image of its own. The parts of an effect before it work in
     * one image, as compositing operations on it.
     */
    fun draw(
        ctx: Context2D,
        effect: WebRenderEffect,
        source: JsAny,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int,
        matrix: Affine,
        left: Int,
        top: Int,
        job: FramePlan.Job?,
        x: Double,
        y: Double,
    ) {
        layer.set(source, sourceX, sourceY, width, height, matrix, left, top)
        layer.blur = job?.blur
        layer.result = job?.result
        layer.foreground = job?.foreground
        draw(ctx, effect, x, y)
    }

    /**
     * Plans into [job] what its [width] x [height] layer, composited with [effect] at [scale], has
     * prepared ahead of the frame: the blur of the layer itself, if it extends its edges or goes
     * through its pixels (see [PreparedBlur]), the image the effect works in, if it needs one, and
     * the image of a foreground that image is blended with, if that needs one too. Other images are
     * made where they're met.
     */
    fun plan(effect: WebRenderEffect?, scale: Float, width: Int, height: Int, job: FramePlan.Job) {
        planBlur(effect, scale, width, height, job.blur)
        val result = imageOf(effect)
        job.result.effect = result
        job.foreground.effect = foregroundOf(result)
    }

    /** Draws the image of the foreground planned for [job], if it has a slot. Returns whether it does. */
    fun prepareForeground(job: FramePlan.Job): Boolean = prepare(job, job.foreground)

    /**
     * Draws the image that the effect of [job] works in, if it has a slot, from the image of its
     * foreground. Returns whether it does.
     */
    fun prepareResult(job: FramePlan.Job): Boolean = prepare(job, job.result)

    private fun prepare(job: FramePlan.Job, image: PreparedImage): Boolean {
        val page = image.page ?: return false
        layer.set(
            checkNotNull(job.page).canvas, job.slotX, job.slotY, job.width, job.height, job.matrix,
            job.left, job.top,
        )
        layer.blur = job.blur
        layer.result = job.result
        layer.foreground = job.foreground
        val target = page.target
        target.setPixelTransform()
        target.setGlobalAlpha(1.0)
        target.setCompositeOperation(SourceOverOperation)
        renderClipped(target.ctx, checkNotNull(image.effect), image.x, image.y)
        return true
    }

    /** Draws the layer through [effect] at ([x], [y]) of [ctx] (see the other overload). */
    private fun draw(ctx: Context2D, effect: WebRenderEffect, x: Double, y: Double) {
        val width = layer.width
        val height = layer.height
        when (effect) {
            is WebRenderEffect.Translate -> {
                val toPixels = layer.toPixels
                val offset = effect.offset
                val dx = x + toPixels.a * offset.x + toPixels.c * offset.y
                val dy = y + toPixels.b * offset.x + toPixels.d * offset.y
                val input = effect.input
                when {
                    input == null -> drawLayer(ctx, dx, dy)
                    // It has no edges: it moves within the area, which it keeps covering.
                    isUnbounded(input) -> draw(ctx, input, dx, dy)
                    // An image of the layer size, which moves.
                    else -> withImageOf(input) { source, sourceX, sourceY ->
                        drawArea(ctx, source, sourceX, sourceY, width, height, dx, dy)
                    }
                }
            }
            is WebRenderEffect.Blur -> {
                val sigmaX = effect.sigmaX * layer.scale
                val sigmaY = effect.sigmaY * layer.scale
                val input = effect.input
                val blur = layer.blur
                when {
                    input != null -> withImageOf(input) { source, sourceX, sourceY ->
                        drawBlurred(
                            ctx, source, sourceX, sourceY, width, height, sigmaX, sigmaY,
                            effect.edgeTreatment, x, y,
                        )
                    }
                    blur?.page != null && blur.effect === effect ->
                        drawBlurredAhead(ctx, blur, sigmaX, sigmaY, x, y)
                    else -> drawBlurred(
                        ctx, layer.source, layer.x, layer.y, width, height, sigmaX, sigmaY,
                        effect.edgeTreatment, x, y,
                    )
                }
            }
            is WebRenderEffect.ShaderFill -> fill(ctx, effect.shader, x, y)
            is WebRenderEffect.ColorFiltered -> {
                val filter = effect.filter
                val input = effect.input
                val alphaScale = effect.alphaScale
                val svgFilter = filter?.let { SvgColorFilters.filterValue(it, ctx) }
                when {
                    filter == null -> drawResult(ctx, input, x, y)
                    alphaScale != null -> {
                        val alpha = ctx.globalAlpha
                        ctx.globalAlpha = alpha * alphaScale
                        drawResult(ctx, input, x, y)
                        ctx.globalAlpha = alpha
                    }
                    svgFilter == null -> withImageOf(effect) { source, sourceX, sourceY ->
                        drawArea(ctx, source, sourceX, sourceY, width, height, x, y)
                    }
                    input == null ->
                        drawThrough(ctx, svgFilter, layer.source, layer.x, layer.y, width, height, x, y)
                    else -> withImageOf(input) { source, sourceX, sourceY ->
                        drawThrough(ctx, svgFilter, source, sourceX, sourceY, width, height, x, y)
                    }
                }
            }
            is WebRenderEffect.Blend -> withImageOf(effect) { source, sourceX, sourceY ->
                drawArea(ctx, source, sourceX, sourceY, width, height, x, y)
            }
        }
    }

    /**
     * Gives [use] the image of the result of [effect]: as it was prepared ahead, or else made here.
     * Its pixels are the ones of the layer, at the position given.
     */
    private inline fun withImageOf(
        effect: WebRenderEffect,
        use: (source: JsAny, x: Int, y: Int) -> Unit,
    ) {
        val image = layer.preparedImageOf(effect)
        val page = image?.page
        if (page != null) {
            use(page.canvas, image.x, image.y)
            return
        }
        val lease = LayerCanvasPool.acquire(layer.width, layer.height)
        renderClipped(lease.ctx, effect, 0, 0)
        use(lease.canvas, 0, 0)
        LayerCanvasPool.release(lease)
    }

    /**
     * Makes the image of the result of [effect] at ([x], [y]) of [ctx], whose area is cleared. It
     * stays in the area, which also bounds the work of the filters applied.
     */
    private fun renderClipped(ctx: Context2D, effect: WebRenderEffect, x: Int, y: Int) {
        ctx.save()
        ctx.beginPath()
        ctx.rect(x.toDouble(), y.toDouble(), layer.width.toDouble(), layer.height.toDouble())
        ctx.clip(JsStrings.nonzero)
        render(ctx, effect, x, y)
        ctx.restore()
    }

    /** Makes the image of the result of [effect] in place (see [renderClipped]). */
    private fun render(ctx: Context2D, effect: WebRenderEffect?, x: Int, y: Int) {
        val width = layer.width
        val height = layer.height
        when (effect) {
            is WebRenderEffect.ColorFiltered -> {
                val filter = effect.filter
                if (filter != null && SvgColorFilters.appliesThroughSvg(filter)) {
                    draw(ctx, effect, x.toDouble(), y.toDouble())
                } else {
                    render(ctx, effect.input, x, y)
                    if (filter != null) applyColorFilterInPlace(ctx, x, y, width, height, filter)
                }
            }
            is WebRenderEffect.Blend -> {
                render(ctx, effect.background, x, y)
                when (effect.blendMode) {
                    BlendMode.Dst -> Unit
                    BlendMode.Clear ->
                        ctx.clearRect(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
                    else -> {
                        ctx.globalCompositeOperation =
                            compositeOperationFor(effect.blendMode) ?: SourceOverOperation
                        drawResult(ctx, effect.foreground, x.toDouble(), y.toDouble())
                        ctx.globalCompositeOperation = SourceOverOperation
                    }
                }
            }
            is WebRenderEffect.Blur,
            is WebRenderEffect.ShaderFill,
            is WebRenderEffect.Translate,
            null -> drawResult(ctx, effect, x.toDouble(), y.toDouble())
        }
    }

    /** Draws the result of [effect], or the layer if `null`, at ([x], [y]) of [ctx]. */
    private fun drawResult(ctx: Context2D, effect: WebRenderEffect?, x: Double, y: Double) {
        if (effect == null) drawLayer(ctx, x, y) else draw(ctx, effect, x, y)
    }

    private fun drawLayer(ctx: Context2D, x: Double, y: Double) {
        drawArea(ctx, layer.source, layer.x, layer.y, layer.width, layer.height, x, y)
    }

    /**
     * Fills the area at ([x], [y]) of [ctx] with [shader], placed there. One draw over all of [ctx],
     * clipped by the caller, can move within the area and keep covering it; a shader that takes
     * several draws goes through the compositing operation of [ctx] as one image.
     */
    private fun fill(ctx: Context2D, shader: WebShader, x: Double, y: Double) {
        val toPixels = layer.toPixels
        val width = layer.width
        val height = layer.height
        val transform = fillTransform.set(
            toPixels.a, toPixels.b, toPixels.c, toPixels.d,
            toPixels.e + x.toFloat(), toPixels.f + y.toFloat(),
        )
        fillArea[0] = x.toFloat()
        fillArea[1] = y.toFloat()
        fillArea[2] = x.toFloat() + width
        fillArea[3] = y.toFloat() + height
        if (shader.needsRasterization(transform, fillArea)) {
            val lease = LayerCanvasPool.acquire(width, height)
            shader.rasterize(lease.ctx, transform.set(toPixels), width, height)
            drawArea(ctx, lease.canvas, 0, 0, width, height, x, y)
            LayerCanvasPool.release(lease)
            return
        }
        ctx.save()
        shader.fillWithStyle(ctx, transform, ctx.canvas.width, ctx.canvas.height)
        ctx.restore()
    }

    private val fillTransform = Affine()
    private val fillArea = FloatArray(4)

    /**
     * Whether the result of [effect] has no edges, nor anything but a shader in it, so that moving
     * it just moves the shader.
     */
    private fun isUnbounded(effect: WebRenderEffect?): Boolean =
        when (effect) {
            is WebRenderEffect.ShaderFill -> !effect.shader.mayNeedRasterization
            is WebRenderEffect.ColorFiltered ->
                (effect.filter == null || effect.alphaScale != null) && isUnbounded(effect.input)
            is WebRenderEffect.Translate -> isUnbounded(effect.input)
            is WebRenderEffect.Blend, is WebRenderEffect.Blur, null -> false
        }

    /** The effect whose image [draw] of [effect] reads first, if it reads any. */
    private fun imageOf(effect: WebRenderEffect?): WebRenderEffect? =
        when (effect) {
            is WebRenderEffect.Translate -> {
                val input = effect.input
                if (input != null && isUnbounded(input)) imageOf(input) else input
            }
            is WebRenderEffect.Blur -> effect.input
            is WebRenderEffect.ColorFiltered -> {
                val filter = effect.filter
                when {
                    filter == null || effect.alphaScale != null -> imageOf(effect.input)
                    SvgColorFilters.appliesThroughSvg(filter) -> effect.input
                    else -> effect
                }
            }
            is WebRenderEffect.Blend -> effect
            is WebRenderEffect.ShaderFill, null -> null
        }

    /**
     * The effect of the first foreground that needs an image of its own, along the backgrounds of
     * the blends of [effect] and the inputs of its filters, if any.
     */
    private fun foregroundOf(effect: WebRenderEffect?): WebRenderEffect? =
        when (effect) {
            is WebRenderEffect.ColorFiltered -> foregroundOf(effect.input)
            is WebRenderEffect.Blend -> foregroundOf(effect.background) ?: imageOf(effect.foreground)
            is WebRenderEffect.Blur, is WebRenderEffect.ShaderFill, is WebRenderEffect.Translate, null -> null
        }

    /** The blur of the layer itself in [effect], if any, wherever in the effect it is. */
    private fun layerBlurOf(effect: WebRenderEffect?): WebRenderEffect.Blur? =
        when (effect) {
            is WebRenderEffect.Blur -> if (effect.input == null) effect else layerBlurOf(effect.input)
            is WebRenderEffect.Blend -> layerBlurOf(effect.background) ?: layerBlurOf(effect.foreground)
            is WebRenderEffect.ColorFiltered -> layerBlurOf(effect.input)
            is WebRenderEffect.Translate -> layerBlurOf(effect.input)
            is WebRenderEffect.ShaderFill, null -> null
        }

    private fun planBlur(effect: WebRenderEffect?, scale: Float, width: Int, height: Int, blur: PreparedBlur) {
        val layerBlur = layerBlurOf(effect) ?: return
        val sigmaX = layerBlur.sigmaX * scale
        val sigmaY = layerBlur.sigmaY * scale
        if (sigmaX <= MinSigma && sigmaY <= MinSigma) return
        blur.effect = layerBlur
        blur.plan(sigmaX, sigmaY, layerBlur.edgeTreatment, width, height, !blursThroughFilter(sigmaX, sigmaY))
    }

    /**
     * Plans the blur of a [width] x [height] area by the given deviations, through its pixels
     * ([throughPixels]) or through `ctx.filter`: how far its edges are extended, and, through the
     * pixels, how much it's scaled down (see [downscaleOf]) and blurred.
     */
    private fun PreparedBlur.plan(
        sigmaX: Float,
        sigmaY: Float,
        edgeTreatment: TileMode,
        width: Int,
        height: Int,
        throughPixels: Boolean,
    ) {
        margin = marginOf(sigmaX, sigmaY, edgeTreatment)
        this.throughPixels = throughPixels
        paddedWidth = width + 2 * margin
        paddedHeight = height + 2 * margin
        if (!throughPixels) {
            imageWidth = paddedWidth
            imageHeight = paddedHeight
            return
        }
        val scaleX = downscaleOf(sigmaX)
        val scaleY = downscaleOf(sigmaY)
        // Whole pixels, so that no pixel at the edges is covered in part.
        imageWidth = (paddedWidth + scaleX - 1) / scaleX
        imageHeight = (paddedHeight + scaleY - 1) / scaleY
        // The deviations scaled alike.
        boxRadii((sigmaX * imageWidth.toDouble() / paddedWidth.toDouble()).toFloat(), radiiX)
        boxRadii((sigmaY * imageHeight.toDouble() / paddedHeight.toDouble()).toFloat(), radiiY)
    }

    /**
     * Sets [radii] to the radii of three box blurs whose sequence approximates a Gaussian of
     * deviation [sigma], a box of radius 0 blurring nothing.
     */
    fun boxRadii(sigma: Float, radii: IntArray) {
        radii.fill(0)
        if (!(sigma > 0f)) return
        val s = sigma.toDouble()
        var lower = floor(sqrt(4 * s * s + 1)).toInt()
        if (lower % 2 == 0) lower--
        // The boxes of the lower odd size, the others of the next one.
        val lowerCount = floor((12 * s * s - 3 * lower * lower - 12 * lower - 9) / (-4 * lower - 4) + 0.5)
        for (i in radii.indices) radii[i] = ((if (i < lowerCount) lower else lower + 2) - 1) / 2
    }

    /**
     * Whether a blur of the given deviations is drawn through `ctx.filter`: CSS `blur()` for one
     * deviation, an SVG filter for two ([SvgFilters]), or else through the pixels.
     */
    private fun blursThroughFilter(sigmaX: Float, sigmaY: Float): Boolean =
        CanvasFeatures.filter && (sigmaX == sigmaY || SvgFilters.isSupported)

    /** How far the edges of a layer blurred with the given deviations are extended. */
    private fun marginOf(sigmaX: Float, sigmaY: Float, edgeTreatment: TileMode): Int =
        if (edgeTreatment == TileMode.Decal || (sigmaX <= MinSigma && sigmaY <= MinSigma)) {
            0
        } else {
            blurExtent(max(sigmaX, sigmaY))
        }

    /**
     * Draws a layer blurred with the given deviations at ([x], [y]) of [ctx], from the image of its
     * [blur] prepared ahead (see [plan]).
     */
    private fun drawBlurredAhead(
        ctx: Context2D,
        blur: PreparedBlur,
        sigmaX: Float,
        sigmaY: Float,
        x: Double,
        y: Double,
    ) {
        val border = PreparedBlur.Border
        drawPrepared(
            ctx, blur, sigmaX, sigmaY, checkNotNull(blur.page).canvas, blur.x + border, blur.y + border, x, y,
        )
    }

    /**
     * Draws the [width] x [height] area of [source] at ([sourceX], [sourceY]) as [blur] prepares it,
     * at ([x], [y]) of [ctx]: with its edges extended over its margin, and, through the pixels,
     * scaled down to its image by halves. The image smoothing of [ctx] is left enabled, of low
     * quality.
     */
    fun drawBlurSource(
        ctx: Context2D,
        source: JsAny,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int,
        blur: PreparedBlur,
        x: Int,
        y: Int,
    ) {
        val margin = blur.margin
        val isScaled = blur.imageWidth != blur.paddedWidth || blur.imageHeight != blur.paddedHeight
        if (!isScaled) {
            ctx.imageSmoothingEnabled = false
            if (margin > 0) {
                extendEdges(ctx, source, sourceX, sourceY, width, height, margin, x, y)
            } else {
                drawArea(ctx, source, sourceX, sourceY, width, height, x.toDouble(), y.toDouble())
            }
        } else if (margin > 0) {
            val padded = LayerCanvasPool.acquire(blur.paddedWidth, blur.paddedHeight)
            padded.ctx.imageSmoothingEnabled = false
            extendEdges(padded.ctx, source, sourceX, sourceY, width, height, margin, 0, 0)
            scaledDownByHalves(padded.canvas, 0, 0, blur.paddedWidth, blur.paddedHeight, blur, ctx, x, y)
            LayerCanvasPool.release(padded)
        } else {
            scaledDownByHalves(source, sourceX, sourceY, width, height, blur, ctx, x, y)
        }
        ctx.imageSmoothingEnabled = true
        ctx.imageSmoothingQuality = JsStrings.low
    }

    /**
     * Blurs the image of [blur] at ([x], [y]) of [pixels] through its pixels, and repeats its edge
     * pixels into the pixel around it, which drawing it scaled up samples half a pixel into.
     */
    fun blurImage(pixels: ImageData, x: Int, y: Int, blur: PreparedBlur) {
        blurPixels(pixels, x, y, blur.imageWidth, blur.imageHeight, blur.radiiX, blur.radiiY)
        extendBorder(pixels, x, y, blur.imageWidth, blur.imageHeight)
    }

    /**
     * Draws the area of [blur], prepared at ([imageX], [imageY]) of [image], blurred at ([x], [y]) of
     * [ctx]: scaled up from its image blurred through its pixels, or else through `ctx.filter` by
     * the given deviations. The area is drawn with its extended edges, which the caller clips.
     */
    private fun drawPrepared(
        ctx: Context2D,
        blur: PreparedBlur,
        sigmaX: Float,
        sigmaY: Float,
        image: JsAny,
        imageX: Int,
        imageY: Int,
        x: Double,
        y: Double,
    ) {
        val left = x - blur.margin
        val top = y - blur.margin
        if (!blur.throughPixels) {
            drawFiltered(ctx, sigmaX, sigmaY, image, imageX, imageY, blur.paddedWidth, blur.paddedHeight, left, top)
            return
        }
        ctx.imageSmoothingEnabled = true
        ctx.imageSmoothingQuality = JsStrings.low
        ctx.drawImage(
            image,
            imageX.toDouble(),
            imageY.toDouble(),
            blur.imageWidth.toDouble(),
            blur.imageHeight.toDouble(),
            left,
            top,
            blur.paddedWidth.toDouble(),
            blur.paddedHeight.toDouble(),
        )
    }

    /**
     * Draws the [width] x [height] area of [source] at ([sourceX], [sourceY]) at ([x], [y]) of
     * [ctx], blurred through `ctx.filter` with the given deviations (see [blursThroughFilter]). The
     * caller clips the blur to the area.
     */
    private fun drawFiltered(
        ctx: Context2D,
        sigmaX: Float,
        sigmaY: Float,
        source: JsAny,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int,
        x: Double,
        y: Double,
    ) {
        if (sigmaX == sigmaY) {
            drawThrough(ctx, "blur(${sigmaX}px)".toJsString(), source, sourceX, sourceY, width, height, x, y)
            return
        }
        val blur = blurProbe.set(sigmaX, sigmaY)
        val filter = SvgFilters.valueOf(ctx, blur)
        if (filter != null) {
            drawThrough(ctx, filter, source, sourceX, sourceY, width, height, x, y)
            return
        }
        // A target that doesn't resolve SVG filters (an `OffscreenCanvas` given to render into): the
        // area is blurred into a canvas element first, alone, as the blur is clipped to it.
        val lease = LayerCanvasPool.acquire(width, height)
        val leaseFilter = checkNotNull(SvgFilters.valueOf(lease.ctx, blur)) {
            "A canvas element resolves SVG filters"
        }
        drawThrough(lease.ctx, leaseFilter, source, sourceX, sourceY, width, height, 0.0, 0.0)
        drawArea(ctx, lease.canvas, 0, 0, width, height, x, y)
        LayerCanvasPool.release(lease)
    }

    private fun drawThrough(
        ctx: Context2D,
        filter: JsString,
        source: JsAny,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int,
        x: Double,
        y: Double,
    ) {
        ctx.filter = filter
        drawArea(ctx, source, sourceX, sourceY, width, height, x, y)
        ctx.filter = JsStrings.none
    }

    /** The blur of every lookup of an SVG blur. */
    private val blurProbe = GaussianBlur()

    /** A blur of two deviations, as an SVG filter. */
    private class GaussianBlur(var sigmaX: Float = 0f, var sigmaY: Float = 0f) : SvgFilters.Content() {
        fun set(sigmaX: Float, sigmaY: Float): GaussianBlur {
            this.sigmaX = sigmaX
            this.sigmaY = sigmaY
            return this
        }

        override fun writeTo(filter: JsAny) {
            writeGaussianBlur(filter, "$sigmaX $sigmaY")
        }

        override fun kept(): SvgFilters.Content = GaussianBlur(sigmaX, sigmaY)

        override fun equals(other: Any?): Boolean =
            other is GaussianBlur && other.sigmaX == sigmaX && other.sigmaY == sigmaY

        override fun hashCode(): Int = 31 * sigmaX.hashBits() + sigmaY.hashBits()
    }

    private fun drawArea(
        ctx: Context2D,
        source: JsAny,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int,
        x: Double,
        y: Double,
    ) {
        val w = width.toDouble()
        val h = height.toDouble()
        ctx.drawImage(source, sourceX.toDouble(), sourceY.toDouble(), w, h, x, y, w, h)
    }

    /**
     * Draws the [width] x [height] area of [source] at ([sourceX], [sourceY]) at ([x], [y]) of
     * [ctx], blurred with a Gaussian of the given deviations.
     */
    private fun drawBlurred(
        ctx: Context2D,
        source: JsAny,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int,
        sigmaX: Float,
        sigmaY: Float,
        edgeTreatment: TileMode,
        x: Double,
        y: Double,
    ) {
        if (sigmaX <= MinSigma && sigmaY <= MinSigma) {
            drawArea(ctx, source, sourceX, sourceY, width, height, x, y)
            return
        }
        if (!blursThroughFilter(sigmaX, sigmaY)) {
            drawBlurredThroughPixels(ctx, source, sourceX, sourceY, width, height, sigmaX, sigmaY, edgeTreatment, x, y)
            return
        }
        val blur = here
        blur.plan(sigmaX, sigmaY, edgeTreatment, width, height, throughPixels = false)
        if (blur.isPrepared) {
            drawBlurredHere(ctx, source, sourceX, sourceY, width, height, blur, sigmaX, sigmaY, x, y)
        } else {
            drawFiltered(ctx, sigmaX, sigmaY, source, sourceX, sourceY, width, height, x, y)
        }
    }

    /**
     * Draws the [width] x [height] area of [source] at ([sourceX], [sourceY]) at ([x], [y]) of
     * [ctx], blurred through its pixels ([blurPixels]) with a Gaussian of the given deviations,
     * where the browser can't blur through `ctx.filter`. Along an axis whose deviation is over
     * [MaxPixelSigma], it blurs the area scaled down by halves until the deviation scaled alike
     * isn't ([scaledDownByHalves]), and draws it back scaled up, like Skia does: it has fewer
     * pixels to read, blur and write, and the details its scaling loses are those the blur removes.
     * The image smoothing of [ctx] is left enabled, of low quality.
     */
    fun drawBlurredThroughPixels(
        ctx: Context2D,
        source: JsAny,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int,
        sigmaX: Float,
        sigmaY: Float,
        edgeTreatment: TileMode,
        x: Double,
        y: Double,
    ) {
        val blur = here
        blur.plan(sigmaX, sigmaY, edgeTreatment, width, height, throughPixels = true)
        drawBlurredHere(ctx, source, sourceX, sourceY, width, height, blur, sigmaX, sigmaY, x, y)
    }

    /**
     * Draws the [width] x [height] area of [source] at ([sourceX], [sourceY]) blurred at ([x], [y])
     * of [ctx], prepared as [blur] plans it (see [drawBlurSource]) in a canvas of the pool, its
     * image with a pixel around it: the pixels of the image are read and written there at once.
     */
    private fun drawBlurredHere(
        ctx: Context2D,
        source: JsAny,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int,
        blur: PreparedBlur,
        sigmaX: Float,
        sigmaY: Float,
        x: Double,
        y: Double,
    ) {
        val border = PreparedBlur.Border
        val canvasWidth = blur.imageWidth + 2 * border
        val canvasHeight = blur.imageHeight + 2 * border
        val lease = LayerCanvasPool.acquire(canvasWidth, canvasHeight)
        drawBlurSource(lease.ctx, source, sourceX, sourceY, width, height, blur, border, border)
        if (blur.throughPixels) {
            // Pooled canvases can be larger than asked for.
            val pixels = lease.ctx.getImageData(0, 0, canvasWidth, canvasHeight)
            blurImage(pixels, border, border, blur)
            lease.ctx.putImageData(pixels, 0, 0)
        }
        drawPrepared(ctx, blur, sigmaX, sigmaY, lease.canvas, border, border, x, y)
        LayerCanvasPool.release(lease)
    }

    /** The blur planned for an area drawn blurred where it's met. */
    private val here = PreparedBlur()

    /**
     * Draws the [width] x [height] area of [source] at ([sourceX], [sourceY]) scaled down by halves
     * to the image of [blur], at ([x], [y]) of [ctx], the halves before the last one in canvases of
     * the pool. A half averages 2 x 2 pixels, bilinear between them, which no single scaling of a
     * canvas does in Chrome or Safari, whatever its quality (stripes alias), and samples the area
     * only, not the pixels around it (e.g. other layers of an atlas page).
     */
    private fun scaledDownByHalves(
        source: JsAny,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int,
        blur: PreparedBlur,
        ctx: Context2D,
        x: Int,
        y: Int,
    ) {
        val scaledWidth = blur.imageWidth
        val scaledHeight = blur.imageHeight
        var levelSource = source
        var levelX = sourceX.toDouble()
        var levelY = sourceY.toDouble()
        var levelWidth = width
        var levelHeight = height
        var level: LayerCanvasPool.Lease? = null
        while (true) {
            val halfWidth = max(scaledWidth, (levelWidth + 1) / 2)
            val halfHeight = max(scaledHeight, (levelHeight + 1) / 2)
            val w = levelWidth.toDouble()
            val h = levelHeight.toDouble()
            if (halfWidth == scaledWidth && halfHeight == scaledHeight) {
                ctx.imageSmoothingEnabled = true
                ctx.imageSmoothingQuality = JsStrings.low
                ctx.drawImage(
                    levelSource, levelX, levelY, w, h,
                    x.toDouble(), y.toDouble(), halfWidth.toDouble(), halfHeight.toDouble(),
                )
                level?.let { LayerCanvasPool.release(it) }
                return
            }
            val half = LayerCanvasPool.acquire(halfWidth, halfHeight)
            half.ctx.drawImage(levelSource, levelX, levelY, w, h, 0.0, 0.0, halfWidth.toDouble(), halfHeight.toDouble())
            level?.let { LayerCanvasPool.release(it) }
            level = half
            levelSource = half.canvas
            levelX = 0.0
            levelY = 0.0
            levelWidth = halfWidth
            levelHeight = halfHeight
        }
    }

    /** The power of 2 that divides [sigma] down to [MaxPixelSigma] at most. */
    private fun downscaleOf(sigma: Float): Int {
        var scale = 1
        while (sigma / scale > MaxPixelSigma) scale *= 2
        return scale
    }

    /**
     * Copies the [width] x [height] area of [source] at ([sourceX], [sourceY]) to ([x] + [margin],
     * [y] + [margin]) of [ctx] in pixels, with its edge pixels extended over [margin] pixels around
     * it. The image smoothing of [ctx] must be disabled: a pixel stretched with smoothing may mix
     * with the pixels around it, outside of the area.
     */
    private fun extendEdges(
        ctx: Context2D,
        source: JsAny,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int,
        margin: Int,
        x: Int,
        y: Int,
    ) {
        val sx = sourceX.toDouble()
        val sy = sourceY.toDouble()
        val w = width.toDouble()
        val h = height.toDouble()
        val m = margin.toDouble()
        val left = x.toDouble()
        val top = y.toDouble()
        ctx.drawImage(source, sx, sy, w, h, left + m, top + m, w, h)
        // Edges.
        ctx.drawImage(source, sx, sy, w, 1.0, left + m, top, w, m)
        ctx.drawImage(source, sx, sy + h - 1, w, 1.0, left + m, top + m + h, w, m)
        ctx.drawImage(source, sx, sy, 1.0, h, left, top + m, m, h)
        ctx.drawImage(source, sx + w - 1, sy, 1.0, h, left + m + w, top + m, m, h)
        // Corners.
        ctx.drawImage(source, sx, sy, 1.0, 1.0, left, top, m, m)
        ctx.drawImage(source, sx + w - 1, sy, 1.0, 1.0, left + m + w, top, m, m)
        ctx.drawImage(source, sx, sy + h - 1, 1.0, 1.0, left, top + m + h, m, m)
        ctx.drawImage(source, sx + w - 1, sy + h - 1, 1.0, 1.0, left + m + w, top + m + h, m, m)
    }

    /** The smallest deviation of a blur that changes pixels. */
    private const val MinSigma = 0.1f

    /** The largest deviation blurred through the pixels at their size. */
    private const val MaxPixelSigma = 4f
}

/** The layer that [Effects] applies an effect to, with what's prepared for it ahead of the frame. */
private class EffectLayer {
    // The area of the layer in its source.
    lateinit var source: JsAny
        private set
    var x = 0
        private set
    var y = 0
        private set
    var width = 0
        private set
    var height = 0
        private set

    /** Maps the space of the content to the pixels of the area. */
    val toPixels = Affine()

    /** The scale of the content in pixels, to scale effect parameters. */
    var scale = 1f
        private set

    /** What can be read for the layer, if there's anything (see [Effects.plan]). */
    var blur: PreparedBlur? = null
    var result: PreparedImage? = null
    var foreground: PreparedImage? = null

    /** Sets the area of the layer, whose content [matrix] draws with its pixels from ([left], [top]). */
    fun set(source: JsAny, x: Int, y: Int, width: Int, height: Int, matrix: Affine, left: Int, top: Int) {
        this.source = source
        this.x = x
        this.y = y
        this.width = width
        this.height = height
        toPixels.set(matrix.a, matrix.b, matrix.c, matrix.d, matrix.e - left, matrix.f - top)
        scale = matrix.averageScale
    }

    /** The image prepared for [effect], if any. */
    fun preparedImageOf(effect: WebRenderEffect): PreparedImage? =
        when {
            result?.effect === effect -> result
            foreground?.effect === effect -> foreground
            else -> null
        }
}

/**
 * The image of the result of an effect of a layer drawn ahead of a frame, in a slot of a page of the
 * atlas (see [Effects.plan]): written and read once per level of the frame, not once per layer.
 */
internal class PreparedImage {
    /** The effect whose result the image holds, if it's planned. */
    var effect: WebRenderEffect? = null

    /** The page of the slot of the image at ([x], [y]), if the atlas had room for it. */
    var page: AtlasPage? = null
        private set
    var x = 0
        private set
    var y = 0
        private set

    fun place(page: AtlasPage, x: Int, y: Int) {
        this.page = page
        this.x = x
        this.y = y
    }

    fun clear() {
        effect = null
        page = null
    }
}

/**
 * How the blur of an area is prepared before it's drawn (see [Effects.drawBlurSource]): its edges
 * extended over a margin, for a blur that doesn't leave them transparent, and, where the browser
 * can't blur through `ctx.filter`, its pixels blurred, the area scaled down for a large deviation.
 * A layer drawn ahead of the frame has its blur prepared ahead too (see [FramePlan.Job.blur]), in a
 * slot of a page of the atlas ([page]).
 */
internal class PreparedBlur {
    /** The blur planned, if any. */
    var effect: WebRenderEffect.Blur? = null

    /** How far the edges of the area are extended, if at all. */
    var margin = 0

    /** Whether the area is blurred through its pixels, or else through `ctx.filter` when drawn. */
    var throughPixels = false

    // The size of the area with its edges extended.
    var paddedWidth = 0
    var paddedHeight = 0

    // The size of the image prepared: the area with its edges extended, scaled down through the
    // pixels for a large deviation.
    var imageWidth = 0
    var imageHeight = 0

    // The radii of the box blurs of the image through its pixels, along each axis (see
    // [Effects.boxRadii]).
    val radiiX = IntArray(3)
    val radiiY = IntArray(3)

    // The size of the slot of the image prepared ahead: the image with its border.
    val slotWidth: Int get() = imageWidth + 2 * Border
    val slotHeight: Int get() = imageHeight + 2 * Border

    /** Whether an image is prepared: the area with its edges extended, or blurred through its pixels. */
    val isPrepared: Boolean get() = throughPixels || margin > 0

    /**
     * Where the image prepared ahead is: at ([x], [y]) of [page] with a [Border] around it, or no page
     * if the atlas had no room for it.
     */
    var page: AtlasPage? = null
        private set
    var x = 0
        private set
    var y = 0
        private set

    /** Gives the image prepared ahead its place, with its border. */
    fun place(page: AtlasPage, x: Int, y: Int) {
        this.page = page
        this.x = x
        this.y = y
    }

    /** Plans no image, at no place. */
    fun clear() {
        effect = null
        margin = 0
        throughPixels = false
        page = null
    }

    companion object {
        /**
         * The pixels around an image prepared, holding its edge pixels for a blur through the
         * pixels: drawn scaled up, it's sampled up to half a pixel past its edges.
         */
        const val Border = 1
    }
}

/** How far a Gaussian blur of deviation [sigma] spreads, in pixels: 3 deviations. */
internal fun blurExtent(sigma: Float): Int = ceil(3 * sigma).toInt()

/**
 * Blurs the [width] x [height] pixels at ([x], [y]) of [imageData] in place by box blurs of the
 * radii [radiiX] along the rows, then [radiiY] along the columns (see [Effects.boxRadii]): along
 * each line, the boxes in sequence, the line extended past its ends by its edge pixels. Used where
 * the browser can't blur through `ctx.filter`, and as [SlotPixelBlurs] blurs with WebGL.
 *
 * The colors are blurred premultiplied, as the canvas keeps them: premultiplying the image data
 * gives back the very pixels it was read from, and the canvas premultiplies the result back into the
 * very blurred pixels. The first axis blurred premultiplies the pixels as it reads them, the last one
 * unpremultiplies them as it writes them back, both rounded as the bytes of the image data round
 * them; radii of 0 leave the pixels as they are. A line is blurred in floats, the four channels of a
 * pixel at once, so that it is rounded once per axis, and because the running sum of a box blur
 * reads the pixels its results replace.
 */
internal fun blurPixels(
    imageData: ImageData,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    radiiX: IntArray,
    radiiY: IntArray,
) {
    blurPixelsByBoxes(
        imageData, x, y, width, height, radiiX[0], radiiX[1], radiiX[2], radiiY[0], radiiY[1], radiiY[2],
    )
}

// language=js
private fun blurPixelsByBoxes(
    imageData: ImageData,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    rx0: Int,
    rx1: Int,
    rx2: Int,
    ry0: Int,
    ry1: Int,
    ry2: Int,
): Unit = js(
    """{
        const d = imageData.data, stride = 4 * imageData.width, origin = y * stride + 4 * x;
        const radiiX = [rx0, rx1, rx2].filter((r) => r > 0), radiiY = [ry0, ry1, ry2].filter((r) => r > 0);
        const blursX = radiiX.length > 0, blursY = radiiY.length > 0;
        if (!blursX && !blursY) return;
        const reachOf = (radii) => radii.reduce((sum, r) => sum + r, 0);
        // A line of the four channels of its pixels, from first, padded on both sides with its edge
        // pixels as far as its boxes read past its ends, and one more for the last running sum.
        const pad = Math.max(reachOf(radiiX), reachOf(radiiY)) + 1;
        const first = 4 * pad;
        let line = new Float32Array(4 * (Math.max(width, height) + 2 * pad));
        let blurred = new Float32Array(line.length);
        // Blurs the lines of count pixels step bytes apart, line l starting at byte origin + l *
        // lineStep, premultiplying the pixels as it reads them, unpremultiplying them as it writes
        // them back.
        const blurLines = (count, step, lines, lineStep, radii, premultiplies, unpremultiplies) => {
            const end = first + 4 * count, last = end - 4, paddedEnd = end + first;
            for (let l = 0; l < lines; l++) {
                const start = origin + l * lineStep;
                for (let i = start, p = first; p < end; i += step, p += 4) {
                    const alpha = d[i + 3];
                    // Opaque pixels are their own premultiplied colors. The products are never
                    // halfway between two bytes, so rounding them gives the bytes they'd be stored as.
                    if (premultiplies && alpha !== 255) {
                        const a = alpha / 255;
                        line[p] = Math.round(d[i] * a);
                        line[p + 1] = Math.round(d[i + 1] * a);
                        line[p + 2] = Math.round(d[i + 2] * a);
                    } else {
                        line[p] = d[i];
                        line[p + 1] = d[i + 1];
                        line[p + 2] = d[i + 2];
                    }
                    line[p + 3] = alpha;
                }
                for (let p = 0; p < first; p += 4) {
                    line[p] = line[first];
                    line[p + 1] = line[first + 1];
                    line[p + 2] = line[first + 2];
                    line[p + 3] = line[first + 3];
                }
                for (let p = end; p < paddedEnd; p += 4) {
                    line[p] = line[last];
                    line[p + 1] = line[last + 1];
                    line[p + 2] = line[last + 2];
                    line[p + 3] = line[last + 3];
                }
                // Each box over the pixels that the boxes after it read, past the ends of the line.
                let reach = reachOf(radii);
                for (const r of radii) {
                    reach -= r;
                    const from = first - 4 * reach, to = end + 4 * reach;
                    const scale = 1 / (2 * r + 1);
                    let s0 = 0, s1 = 0, s2 = 0, s3 = 0;
                    for (let p = from - 4 * r; p <= from + 4 * r; p += 4) {
                        s0 += line[p];
                        s1 += line[p + 1];
                        s2 += line[p + 2];
                        s3 += line[p + 3];
                    }
                    const ahead = 4 * (r + 1), behind = 4 * r;
                    for (let p = from; p < to; p += 4) {
                        blurred[p] = s0 * scale;
                        blurred[p + 1] = s1 * scale;
                        blurred[p + 2] = s2 * scale;
                        blurred[p + 3] = s3 * scale;
                        const a = p + ahead, b = p - behind;
                        s0 += line[a] - line[b];
                        s1 += line[a + 1] - line[b + 1];
                        s2 += line[a + 2] - line[b + 2];
                        s3 += line[a + 3] - line[b + 3];
                    }
                    const result = blurred;
                    blurred = line;
                    line = result;
                }
                for (let i = start, p = first; p < end; i += step, p += 4) {
                    d[i] = line[p];
                    d[i + 1] = line[p + 1];
                    d[i + 2] = line[p + 2];
                    d[i + 3] = line[p + 3];
                    // From the bytes written, which ties round to even.
                    const alpha = d[i + 3];
                    if (unpremultiplies && alpha !== 255) {
                        const unpremultiply = alpha === 0 ? 0 : 255 / alpha;
                        d[i] *= unpremultiply;
                        d[i + 1] *= unpremultiply;
                        d[i + 2] *= unpremultiply;
                    }
                }
            }
        };
        if (blursX) blurLines(width, 4, height, stride, radiiX, true, !blursY);
        if (blursY) blurLines(height, stride, width, 4, radiiY, !blursX, true);
    }"""
)

/**
 * Repeats the edge pixels of the [width] x [height] pixels at ([x], [y]) of [imageData] into the
 * pixel around them, its corners included.
 */
// language=js
internal fun extendBorder(imageData: ImageData, x: Int, y: Int, width: Int, height: Int): Unit = js(
    """{
        const pixels = new Uint32Array(imageData.data.buffer), w = imageData.width;
        const top = y * w + x, bottom = (y + height - 1) * w + x;
        for (let i = 0; i < width; i++) {
            pixels[top - w + i] = pixels[top + i];
            pixels[bottom + w + i] = pixels[bottom + i];
        }
        for (let row = (y - 1) * w + x; row <= (y + height) * w + x; row += w) {
            pixels[row - 1] = pixels[row];
            pixels[row + width] = pixels[row + width - 1];
        }
    }"""
)

/**
 * Makes [filter] blur by [deviation] ("σx σy") like CSS `blur()` does: the sRGB values, over a
 * region that spans whatever is drawn.
 */
// language=js
private fun writeGaussianBlur(filter: JsAny, deviation: String): Unit = js(
    """{
        filter.setAttribute('color-interpolation-filters', 'sRGB');
        filter.setAttribute('filterUnits', 'userSpaceOnUse');
        filter.setAttribute('x', '-100000');
        filter.setAttribute('y', '-100000');
        filter.setAttribute('width', '200000');
        filter.setAttribute('height', '200000');
        const blur = filter.ownerDocument.createElementNS('http://www.w3.org/2000/svg', 'feGaussianBlur');
        blur.setAttribute('stdDeviation', deviation);
        filter.appendChild(blur);
    }"""
)
