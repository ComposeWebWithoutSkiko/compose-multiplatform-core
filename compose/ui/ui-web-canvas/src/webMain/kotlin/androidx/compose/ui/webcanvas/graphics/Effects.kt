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

import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.webcanvas.dom.CanvasFeatures
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.JsStrings
import androidx.compose.ui.webcanvas.dom.createImageDataFromArgb
import androidx.compose.ui.webcanvas.dom.readImageDataArgb
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.toJsString
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Applies [WebRenderEffect]s to the pixels of a layer. */
internal object Effects {
    /**
     * Draws the [width] x [height] top-left area of [source], a layer, with [effect] applied, at
     * ([x], [y]) of [ctx] in pixels. The caller sets up the alpha and compositing operation of
     * [ctx], and clips it to the layer area, like Skia does. [scale] converts the effect parameters
     * from the layer space to pixels.
     *
     * The last effect is applied while drawing into [ctx] (a blur through `ctx.filter`, an offset
     * by moving the draw), so it costs no image of its own.
     */
    fun draw(
        ctx: Context2D,
        effect: WebRenderEffect,
        source: JsAny,
        width: Int,
        height: Int,
        scale: Float,
        x: Double,
        y: Double,
    ) {
        when (effect) {
            is WebRenderEffect.Translate -> {
                val dx = x + effect.offset.x * scale
                val dy = y + effect.offset.y * scale
                val input = effect.input
                if (input == null) {
                    drawArea(ctx, source, width, height, dx, dy)
                } else {
                    // The result of the input is an image of the layer size, which moves.
                    ctx.save()
                    ctx.beginPath()
                    ctx.rect(dx, dy, width.toDouble(), height.toDouble())
                    ctx.clip(JsStrings.nonzero)
                    draw(ctx, input, source, width, height, scale, dx, dy)
                    ctx.restore()
                }
            }
            is WebRenderEffect.Blur -> {
                val sigmaX = effect.sigmaX * scale
                val sigmaY = effect.sigmaY * scale
                val input = effect.input
                if (input == null) {
                    drawBlurred(ctx, source, width, height, sigmaX, sigmaY, effect.edgeTreatment, x, y)
                    return
                }
                // The blur applies to the result of the input effect, which is rendered first.
                val lease = LayerCanvasPool.acquire(width, height)
                draw(lease.ctx, input, source, width, height, scale, 0.0, 0.0)
                drawBlurred(ctx, lease.canvas, width, height, sigmaX, sigmaY, effect.edgeTreatment, x, y)
                LayerCanvasPool.release(lease)
            }
        }
    }

    private fun drawArea(ctx: Context2D, source: JsAny, width: Int, height: Int, x: Double, y: Double) {
        val w = width.toDouble()
        val h = height.toDouble()
        ctx.drawImage(source, 0.0, 0.0, w, h, x, y, w, h)
    }

    /**
     * Draws the [width] x [height] top-left area of [source] at ([x], [y]) of [ctx], blurred with a
     * Gaussian of the given deviations.
     */
    private fun drawBlurred(
        ctx: Context2D,
        source: JsAny,
        width: Int,
        height: Int,
        sigmaX: Float,
        sigmaY: Float,
        edgeTreatment: TileMode,
        x: Double,
        y: Double,
    ) {
        if (sigmaX <= 0.1f && sigmaY <= 0.1f) {
            drawArea(ctx, source, width, height, x, y)
            return
        }
        // With a margin, the edge pixels are extended into it, so that the blur near the edges
        // samples the edge colors (clamp) instead of transparency. The caller's clip then keeps the
        // blurred margin out.
        val margin = if (edgeTreatment == TileMode.Decal) 0 else ceil(3 * max(sigmaX, sigmaY)).toInt()
        val paddedWidth = width + 2 * margin
        val paddedHeight = height + 2 * margin
        val padded = if (margin > 0) {
            LayerCanvasPool.acquire(paddedWidth, paddedHeight).also {
                extendEdges(it.ctx, source, width, height, margin)
            }
        } else {
            null
        }
        val m = margin.toDouble()
        val pw = paddedWidth.toDouble()
        val ph = paddedHeight.toDouble()
        if (CanvasFeatures.filter) {
            // CSS blur() is isotropic; anisotropic blurs use the average deviation.
            val sigma = if (sigmaX == sigmaY) sigmaX else sqrt(sigmaX * sigmaY)
            ctx.filter = "blur(${sigma}px)".toJsString()
            ctx.drawImage(padded?.canvas ?: source, 0.0, 0.0, pw, ph, x - m, y - m, pw, ph)
            ctx.filter = JsStrings.none
        } else {
            val scratch = padded ?: LayerCanvasPool.acquire(width, height).also {
                drawArea(it.ctx, source, width, height, 0.0, 0.0)
            }
            val pixels = IntArray(paddedWidth * paddedHeight)
            readImageDataArgb(
                scratch.ctx.getImageData(0, 0, paddedWidth, paddedHeight),
                pixels,
                0,
                paddedWidth,
            )
            gaussianBlurArgb(pixels, paddedWidth, paddedHeight, sigmaX, sigmaY)
            scratch.ctx.putImageData(
                createImageDataFromArgb(pixels, 0, paddedWidth, paddedWidth, paddedHeight),
                0,
                0,
            )
            ctx.drawImage(scratch.canvas, 0.0, 0.0, pw, ph, x - m, y - m, pw, ph)
            if (scratch !== padded) LayerCanvasPool.release(scratch)
        }
        padded?.let { LayerCanvasPool.release(it) }
    }

    private fun extendEdges(ctx: Context2D, source: JsAny, width: Int, height: Int, margin: Int) {
        val w = width.toDouble()
        val h = height.toDouble()
        val m = margin.toDouble()
        ctx.drawImage(source, 0.0, 0.0, w, h, m, m, w, h)
        // Edges.
        ctx.drawImage(source, 0.0, 0.0, w, 1.0, m, 0.0, w, m)
        ctx.drawImage(source, 0.0, h - 1, w, 1.0, m, m + h, w, m)
        ctx.drawImage(source, 0.0, 0.0, 1.0, h, 0.0, m, m, h)
        ctx.drawImage(source, w - 1, 0.0, 1.0, h, m + w, m, m, h)
        // Corners.
        ctx.drawImage(source, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, m, m)
        ctx.drawImage(source, w - 1, 0.0, 1.0, 1.0, m + w, 0.0, m, m)
        ctx.drawImage(source, 0.0, h - 1, 1.0, 1.0, 0.0, m + h, m, m)
        ctx.drawImage(source, w - 1, h - 1, 1.0, 1.0, m + w, m + h, m, m)
    }
}

/**
 * Approximates a Gaussian blur of non-premultiplied ARGB [pixels] with three box blurs per axis,
 * blurring the premultiplied colors. Used when the browser can't blur through `ctx.filter`.
 */
internal fun gaussianBlurArgb(pixels: IntArray, width: Int, height: Int, sigmaX: Float, sigmaY: Float) {
    val count = width * height
    val a = FloatArray(count)
    val r = FloatArray(count)
    val g = FloatArray(count)
    val b = FloatArray(count)
    for (i in 0 until count) {
        val p = pixels[i]
        val alpha = ((p ushr 24) and 0xFF).toFloat()
        a[i] = alpha
        r[i] = ((p ushr 16) and 0xFF) * alpha / 255f
        g[i] = ((p ushr 8) and 0xFF) * alpha / 255f
        b[i] = (p and 0xFF) * alpha / 255f
    }
    val tmp = FloatArray(max(width, height))
    for (channel in arrayOf(a, r, g, b)) {
        for (radius in boxRadii(sigmaX)) boxBlurHorizontal(channel, tmp, width, height, radius)
        for (radius in boxRadii(sigmaY)) boxBlurVertical(channel, tmp, width, height, radius)
    }
    for (i in 0 until count) {
        val alpha = a[i]
        if (alpha <= 0.5f) {
            pixels[i] = 0
        } else {
            val inverse = 255f / alpha
            pixels[i] = argbOf(
                alpha.roundToInt().coerceIn(0, 255),
                (r[i] * inverse).roundToInt().coerceIn(0, 255),
                (g[i] * inverse).roundToInt().coerceIn(0, 255),
                (b[i] * inverse).roundToInt().coerceIn(0, 255),
            )
        }
    }
}

/** The radii of three box blurs approximating a Gaussian of deviation [sigma]. */
private fun boxRadii(sigma: Float): IntArray {
    if (sigma <= 0f) return IntArray(0)
    val n = 3
    val idealWidth = sqrt(12 * sigma * sigma / n + 1)
    var lower = idealWidth.toInt()
    if (lower % 2 == 0) lower--
    val upper = lower + 2
    val idealCount = (12 * sigma * sigma - n * lower * lower - 4 * n * lower - 3 * n) / (-4 * lower - 4)
    val m = idealCount.roundToInt()
    return IntArray(n) { i -> ((if (i < m) lower else upper) - 1) / 2 }
}

private fun boxBlurHorizontal(channel: FloatArray, tmp: FloatArray, width: Int, height: Int, radius: Int) {
    if (radius <= 0) return
    val scale = 1f / (2 * radius + 1)
    for (y in 0 until height) {
        val row = y * width
        var sum = 0f
        for (x in -radius..radius) sum += channel[row + min(width - 1, max(0, x))]
        for (x in 0 until width) {
            tmp[x] = sum * scale
            val add = row + min(width - 1, x + radius + 1)
            val remove = row + max(0, x - radius)
            sum += channel[add] - channel[remove]
        }
        for (x in 0 until width) channel[row + x] = tmp[x]
    }
}

private fun boxBlurVertical(channel: FloatArray, tmp: FloatArray, width: Int, height: Int, radius: Int) {
    if (radius <= 0) return
    val scale = 1f / (2 * radius + 1)
    for (x in 0 until width) {
        var sum = 0f
        for (y in -radius..radius) sum += channel[min(height - 1, max(0, y)) * width + x]
        for (y in 0 until height) {
            tmp[y] = sum * scale
            val add = min(height - 1, y + radius + 1) * width + x
            val remove = max(0, y - radius) * width + x
            sum += channel[add] - channel[remove]
        }
        for (y in 0 until height) channel[y * width + x] = tmp[y]
    }
}
