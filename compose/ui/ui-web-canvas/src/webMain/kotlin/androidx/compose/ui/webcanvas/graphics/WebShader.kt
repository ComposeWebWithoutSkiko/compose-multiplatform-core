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

@file:OptIn(InternalComposeUiApi::class, ExperimentalWasmJsInterop::class)

package androidx.compose.ui.webcanvas.graphics

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.platform.PlatformShader
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.webcanvas.dom.CanvasGradient
import androidx.compose.ui.webcanvas.dom.CanvasPattern
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.CssColors
import androidx.compose.ui.webcanvas.dom.JsStrings
import androidx.compose.ui.webcanvas.dom.createCanvas
import androidx.compose.ui.webcanvas.dom.createConicGradientOrNull
import androidx.compose.ui.webcanvas.dom.getContext2D
import androidx.compose.ui.webcanvas.dom.setPatternTransform
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A shader of the web backend. Gradients and patterns of the Canvas 2D API are not bound to the
 * context that created them, so each shader creates its fill style once, with a shared scratch
 * context, and reuses it for every draw.
 *
 * Canvas gradients only clamp at their ends; the other [TileMode]s are emulated by repeating or
 * extending the color stops over a longer gradient line.
 */
internal sealed class WebShader : PlatformShader {
    private var style: JsAny? = null
    private var styleCreated = false

    /**
     * The transformation from the shader's space to the space it is drawn in, when the fill style
     * can't absorb it (e.g. a non-uniformly scaled radial gradient). The canvas then has to draw
     * through this transformation.
     */
    open val styleTransform: Affine? get() = null

    /** Whether every pixel produced by this shader is opaque. */
    open val isOpaque: Boolean get() = false

    /** The `fillStyle`/`strokeStyle` value drawing this shader, or `null` if there is none. */
    fun canvasStyle(): JsAny? {
        if (!styleCreated) {
            style = createStyle(ScratchContext.ctx)
            styleCreated = true
        }
        return style
    }

    protected abstract fun createStyle(ctx: Context2D): JsAny?

    /** Returns this shader drawn through [matrix], applied to the shader's local coordinates. */
    abstract fun transformed(matrix: Affine): WebShader

    /**
     * Whether drawing with this shader may need an image of the shader over the drawn area (see
     * [rasterize]), because no fill style can express it.
     */
    open val mayNeedRasterization: Boolean get() = false

    /**
     * Whether the fill style can't express the shader over the device area [deviceBounds] (left,
     * top, right, bottom), when drawn through [toDevice].
     */
    open fun needsRasterization(toDevice: Affine, deviceBounds: FloatArray): Boolean =
        mayNeedRasterization

    /**
     * Fills the [width] x [height] pixels of [ctx] with the shader, [transform] mapping the space
     * of the shader to these pixels.
     */
    open fun rasterize(ctx: Context2D, transform: Affine, width: Int, height: Int) {
        val style = canvasStyle() ?: return
        val full = transform.copy()
        styleTransform?.let { full.preConcat(it) }
        val inverse = full.copy()
        if (!inverse.invert()) return
        val area = FloatArray(4)
        inverse.mapRect(0f, 0f, width.toFloat(), height.toFloat(), area)
        ctx.setTransform(
            full.a.toDouble(),
            full.b.toDouble(),
            full.c.toDouble(),
            full.d.toDouble(),
            full.e.toDouble(),
            full.f.toDouble(),
        )
        ctx.fillStyle = style
        ctx.fillRect(
            area[0].toDouble(),
            area[1].toDouble(),
            (area[2] - area[0]).toDouble(),
            (area[3] - area[1]).toDouble(),
        )
    }

    /** The last image of the shader made by the canvas, reused while the draws match it. */
    internal var raster: ShaderRaster? = null

    /**
     * The fill style of the shader in a canonical geometry (e.g. a gradient from (0, 0) to (1, 0)),
     * shared by all the shaders with the same colors and tile mode, or `null` if the shader has
     * no canonical form. Filling a path built in the current transformation with this style, with
     * the context transformed by [canonicalTransform], draws the shader without creating a new
     * browser gradient for every shader instance (they are often created on every frame, and many
     * short-lived gradients cause long garbage collection pauses).
     */
    open fun canonicalStyle(): JsAny? = null

    /**
     * Sets [out] to the transformation from the canonical geometry of [canonicalStyle] to the
     * space of the shader. Returns `false` if there is none (a degenerate shader).
     */
    open fun canonicalTransform(out: Affine): Boolean = false
}

/** Identifies the canonical gradients of [CanonicalGradients]. */
private class CanonicalGradientKey(
    val kind: Int,
    val tileMode: TileMode,
    val colors: IntArray,
    val positions: FloatArray,
) {
    private val hash =
        ((kind * 31 + tileMode.hashCode()) * 31 + colors.contentHashCode()) * 31 +
            positions.contentHashCode()

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean =
        other is CanonicalGradientKey && other.hash == hash && other.kind == kind &&
            other.tileMode == tileMode && other.colors.contentEquals(colors) &&
            other.positions.contentEquals(positions)
}

/** The browser gradients in canonical geometries, shared by all the shaders that match them. */
private object CanonicalGradients {
    private val cache = HashMap<CanonicalGradientKey, JsAny>()
    private const val MaxEntries = 512

    fun get(key: CanonicalGradientKey, create: () -> JsAny): JsAny {
        cache[key]?.let { return it }
        if (cache.size >= MaxEntries) cache.clear()
        return create().also { cache[key] = it }
    }
}

private const val CanonicalLinear = 0
private const val CanonicalRadial = 1
private const val CanonicalSweep = 2

/** An image of a shader over a device area, as a pattern that fills the area. */
internal class ShaderRaster(
    val transform: Affine,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val colorFilterKey: Any?,
    val pattern: JsAny,
) {
    fun matches(
        transform: Affine,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        colorFilterKey: Any?,
    ): Boolean =
        this.left == left && this.top == top && this.width == width && this.height == height &&
            this.colorFilterKey == colorFilterKey && this.transform.contentEquals(transform)
}

/** Extracts the backend shader of a Compose [Shader]. */
internal val Shader.webShader: WebShader
    get() = platformShader as? WebShader ?: error(
        "Expected a shader created by the web canvas implementation, but got $platformShader"
    )

/** Color stops of a gradient, already validated and converted to ARGB. */
internal class GradientStops(colors: List<Color>, stops: List<Float>?) {
    val colors: IntArray = IntArray(colors.size) { colors[it].toArgb() }
    val positions: FloatArray = if (stops != null) {
        FloatArray(stops.size) { stops[it] }
    } else {
        FloatArray(colors.size) { if (colors.size == 1) 0f else it.toFloat() / (colors.size - 1) }
    }

    val isOpaque: Boolean = this.colors.all { (it ushr 24) == 0xFF }

    /** Adds the stops to [gradient], mapped into the [start]..[end] fraction of the gradient line. */
    fun addTo(gradient: CanvasGradient, start: Double = 0.0, end: Double = 1.0, reverse: Boolean = false) {
        val span = end - start
        var previous = start
        for (i in colors.indices) {
            val index = if (reverse) colors.lastIndex - i else i
            val t = if (reverse) 1.0 - positions[index] else positions[index].toDouble()
            // Canvas gradients reject offsets out of order or outside 0..1.
            val offset = (start + t.coerceIn(0.0, 1.0) * span).coerceIn(previous, 1.0)
            gradient.addColorStop(offset, CssColors.of(colors[index]))
            previous = offset
        }
    }

    val firstColor: Int get() = colors[0]
    val lastColor: Int get() = colors[colors.lastIndex]
}

/**
 * The number of gradient periods emulated on each side of a repeating gradient: enough to cover a
 * canvas of [CoveredLength] pixels without making the stop list unreasonably long.
 */
private fun repetitionsFor(length: Float): Int {
    if (length <= 0f || length.isNaN()) return 0
    return min(MaxRepetitions, ceil(CoveredLength / length).toInt())
}

private const val CoveredLength = 8192f
private const val MaxRepetitions = 256
private const val DecalEpsilon = 1e-5

internal class LinearGradientShader(
    val from: Offset,
    val to: Offset,
    val stops: GradientStops,
    val tileMode: TileMode,
) : WebShader() {
    override val isOpaque: Boolean
        get() = stops.isOpaque && tileMode != TileMode.Decal

    override fun createStyle(ctx: Context2D): JsAny {
        val dx = to.x - from.x
        val dy = to.y - from.y
        return when (tileMode) {
            TileMode.Repeated, TileMode.Mirror -> {
                val n = repetitionsFor(hypot(dx, dy))
                val count = 2 * n + 1
                val gradient = ctx.createLinearGradient(
                    (from.x - n * dx).toDouble(),
                    (from.y - n * dy).toDouble(),
                    (to.x + n * dx).toDouble(),
                    (to.y + n * dy).toDouble(),
                )
                for (k in 0 until count) {
                    val reverse = tileMode == TileMode.Mirror && (k - n) % 2 != 0
                    stops.addTo(gradient, k.toDouble() / count, (k + 1).toDouble() / count, reverse)
                }
                gradient
            }
            TileMode.Decal -> {
                val gradient = ctx.createLinearGradient(
                    (from.x - dx).toDouble(),
                    (from.y - dy).toDouble(),
                    (to.x + dx).toDouble(),
                    (to.y + dy).toDouble(),
                )
                addDecalStops(gradient, stops, 1.0 / 3, 2.0 / 3)
                gradient
            }
            else -> {
                val gradient = ctx.createLinearGradient(
                    from.x.toDouble(),
                    from.y.toDouble(),
                    to.x.toDouble(),
                    to.y.toDouble(),
                )
                stops.addTo(gradient)
                gradient
            }
        }
    }

    private var canonicalKey: CanonicalGradientKey? = null

    override fun canonicalStyle(): JsAny? {
        val key = canonicalKey
            ?: CanonicalGradientKey(CanonicalLinear, tileMode, stops.colors, stops.positions)
                .also { canonicalKey = it }
        return CanonicalGradients.get(key) {
            LinearGradientShader(Offset.Zero, Offset(1f, 0f), stops, tileMode)
                .createStyle(ScratchContext.ctx)
        }
    }

    override fun canonicalTransform(out: Affine): Boolean {
        // (1, 0) maps to the gradient vector, (0, 1) to a perpendicular one.
        val dx = to.x - from.x
        val dy = to.y - from.y
        if (dx == 0f && dy == 0f) return false
        out.set(dx, dy, -dy, dx, from.x, from.y)
        return true
    }

    override fun transformed(matrix: Affine): WebShader {
        // An affine image of a linear gradient is a linear gradient: the gradient function stays
        // affine, so it is enough to find its new start point and direction.
        val inverse = matrix.copy()
        if (!inverse.invert()) return this
        val dx = to.x - from.x
        val dy = to.y - from.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return this
        // The gradient of t(p) = ((M^-1 p - from) . d) / |d|^2 is (A^T d) / |d|^2, A = M^-1 (2x2).
        val gx = (inverse.a * dx + inverse.b * dy) / lengthSquared
        val gy = (inverse.c * dx + inverse.d * dy) / lengthSquared
        val gLengthSquared = gx * gx + gy * gy
        if (gLengthSquared == 0f) return this
        val startX = matrix.mapX(from.x, from.y)
        val startY = matrix.mapY(from.x, from.y)
        return LinearGradientShader(
            from = Offset(startX, startY),
            to = Offset(startX + gx / gLengthSquared, startY + gy / gLengthSquared),
            stops = stops,
            tileMode = tileMode,
        )
    }
}

internal class RadialGradientShader(
    val center: Offset,
    val radius: Float,
    val stops: GradientStops,
    val tileMode: TileMode,
    override val styleTransform: Affine? = null,
) : WebShader() {
    override val isOpaque: Boolean
        get() = stops.isOpaque && tileMode != TileMode.Decal

    override fun createStyle(ctx: Context2D): JsAny {
        val cx = center.x.toDouble()
        val cy = center.y.toDouble()
        val r = max(radius, 0f).toDouble()
        return when (tileMode) {
            TileMode.Repeated, TileMode.Mirror -> {
                val n = repetitionsFor(radius)
                val count = n + 1
                val gradient = ctx.createRadialGradient(cx, cy, 0.0, cx, cy, r * count)
                for (k in 0 until count) {
                    val reverse = tileMode == TileMode.Mirror && k % 2 != 0
                    stops.addTo(gradient, k.toDouble() / count, (k + 1).toDouble() / count, reverse)
                }
                gradient
            }
            TileMode.Decal -> {
                val gradient = ctx.createRadialGradient(cx, cy, 0.0, cx, cy, r * 2)
                addDecalStops(gradient, stops, 0.0, 0.5)
                gradient
            }
            else -> {
                val gradient = ctx.createRadialGradient(cx, cy, 0.0, cx, cy, r)
                stops.addTo(gradient)
                gradient
            }
        }
    }

    private var canonicalKey: CanonicalGradientKey? = null

    override fun canonicalStyle(): JsAny? {
        val key = canonicalKey
            ?: CanonicalGradientKey(CanonicalRadial, tileMode, stops.colors, stops.positions)
                .also { canonicalKey = it }
        return CanonicalGradients.get(key) {
            RadialGradientShader(Offset.Zero, 1f, stops, tileMode).createStyle(ScratchContext.ctx)
        }
    }

    override fun canonicalTransform(out: Affine): Boolean {
        if (!(radius > 0f)) return false
        out.set(radius, 0f, 0f, radius, center.x, center.y)
        styleTransform?.let { transform ->
            val placed = out.copy()
            out.set(transform)
            out.preConcat(placed)
        }
        return true
    }

    override fun transformed(matrix: Affine): WebShader {
        val combined = styleTransform?.copy()?.also { it.preConcat(matrix) } ?: matrix.copy()
        if (styleTransform == null && combined.isSimilarity) {
            // Circles stay circles: transform the center and scale the radius.
            return RadialGradientShader(
                center = Offset(matrix.mapX(center.x, center.y), matrix.mapY(center.x, center.y)),
                radius = radius * matrix.averageScale,
                stops = stops,
                tileMode = tileMode,
            )
        }
        return RadialGradientShader(center, radius, stops, tileMode, combined)
    }
}

internal class SweepGradientShader(
    val center: Offset,
    val stops: GradientStops,
    override val styleTransform: Affine? = null,
) : WebShader() {
    override val isOpaque: Boolean
        get() = stops.isOpaque

    override fun createStyle(ctx: Context2D): JsAny {
        val gradient = createConicGradientOrNull(
            ctx,
            0.0,
            center.x.toDouble(),
            center.y.toDouble(),
        ) ?: return CssColors.of(stops.firstColor)
        stops.addTo(gradient)
        return gradient
    }

    private var canonicalKey: CanonicalGradientKey? = null

    override fun canonicalStyle(): JsAny? {
        val key = canonicalKey
            ?: CanonicalGradientKey(CanonicalSweep, TileMode.Clamp, stops.colors, stops.positions)
                .also { canonicalKey = it }
        return CanonicalGradients.get(key) {
            SweepGradientShader(Offset.Zero, stops).createStyle(ScratchContext.ctx)
        }
    }

    override fun canonicalTransform(out: Affine): Boolean {
        out.set(1f, 0f, 0f, 1f, center.x, center.y)
        styleTransform?.let { transform ->
            val placed = out.copy()
            out.set(transform)
            out.preConcat(placed)
        }
        return true
    }

    override fun transformed(matrix: Affine): WebShader {
        val combined = styleTransform?.copy()?.also { it.preConcat(matrix) } ?: matrix.copy()
        return SweepGradientShader(center, stops, combined)
    }
}

internal class ImageShader(
    val image: WebImageBitmap,
    val tileModeX: TileMode,
    val tileModeY: TileMode,
    private val patternTransform: Affine? = null,
) : WebShader() {
    override fun createStyle(ctx: Context2D): JsAny? {
        val source = image.drawableSource() ?: return null
        var tile: JsAny = source
        var tileWidth = image.width
        var tileHeight = image.height
        val mirrorX = tileModeX == TileMode.Mirror
        val mirrorY = tileModeY == TileMode.Mirror
        if (mirrorX || mirrorY) {
            // Mirroring is a repetition of a tile made of the image and its reflections.
            tileWidth = if (mirrorX) image.width * 2 else image.width
            tileHeight = if (mirrorY) image.height * 2 else image.height
            val canvas = createCanvas(tileWidth, tileHeight)
            val tileCtx = getContext2D(canvas, false) ?: return null
            tileCtx.drawImage(source, 0.0, 0.0)
            if (mirrorX) {
                tileCtx.setTransform(-1.0, 0.0, 0.0, 1.0, tileWidth.toDouble(), 0.0)
                tileCtx.drawImage(source, 0.0, 0.0)
            }
            if (mirrorY) {
                tileCtx.setTransform(1.0, 0.0, 0.0, -1.0, 0.0, tileHeight.toDouble())
                tileCtx.drawImage(source, 0.0, 0.0)
            }
            if (mirrorX && mirrorY) {
                tileCtx.setTransform(
                    -1.0, 0.0, 0.0, -1.0, tileWidth.toDouble(), tileHeight.toDouble()
                )
                tileCtx.drawImage(source, 0.0, 0.0)
            }
            tile = canvas
        }
        val repeatX = tileModeX == TileMode.Repeated || mirrorX
        val repeatY = tileModeY == TileMode.Repeated || mirrorY
        val repetition = when {
            repeatX && repeatY -> JsStrings.repeat
            repeatX -> JsStrings.repeatX
            repeatY -> JsStrings.repeatY
            else -> JsStrings.noRepeat
        }
        val pattern: CanvasPattern = ctx.createPattern(tile, repetition) ?: return null
        val transform = patternTransform
        if (transform != null) {
            setPatternTransform(
                pattern,
                transform.a.toDouble(),
                transform.b.toDouble(),
                transform.c.toDouble(),
                transform.d.toDouble(),
                transform.e.toDouble(),
                transform.f.toDouble(),
            )
        }
        return pattern
    }

    override fun transformed(matrix: Affine): WebShader {
        val combined = patternTransform?.copy()?.also { it.preConcat(matrix) } ?: matrix.copy()
        return ImageShader(image, tileModeX, tileModeY, combined)
    }

    // Canvas patterns can't extend the edge pixels of an image (TileMode.Clamp): when the drawn
    // area extends past the image, the image and its stretched edges are drawn instead.
    private val clampsWithoutRepetition: Boolean
        get() = (tileModeX == TileMode.Clamp || tileModeY == TileMode.Clamp) &&
            tileModeX != TileMode.Repeated && tileModeX != TileMode.Mirror &&
            tileModeY != TileMode.Repeated && tileModeY != TileMode.Mirror

    override val mayNeedRasterization: Boolean get() = clampsWithoutRepetition

    override fun needsRasterization(toDevice: Affine, deviceBounds: FloatArray): Boolean {
        if (!clampsWithoutRepetition) return false
        val full = toDevice.copy()
        patternTransform?.let { full.preConcat(it) }
        val inverse = full.copy()
        if (!inverse.invert()) return false
        // The image space bounds of the drawn area: inside the image, clamping changes nothing.
        val area = FloatArray(4)
        inverse.mapRect(deviceBounds[0], deviceBounds[1], deviceBounds[2], deviceBounds[3], area)
        val tolerance = 0.5f
        return area[0] < -tolerance || area[1] < -tolerance ||
            area[2] > image.width + tolerance || area[3] > image.height + tolerance
    }

    override fun rasterize(ctx: Context2D, transform: Affine, width: Int, height: Int) {
        if (!clampsWithoutRepetition) {
            super.rasterize(ctx, transform, width, height)
            return
        }
        val source = image.drawableSource() ?: return
        val full = transform.copy()
        patternTransform?.let { full.preConcat(it) }
        val inverse = full.copy()
        if (!inverse.invert()) return
        val area = FloatArray(4)
        inverse.mapRect(0f, 0f, width.toFloat(), height.toFloat(), area)
        ctx.setTransform(
            full.a.toDouble(),
            full.b.toDouble(),
            full.c.toDouble(),
            full.d.toDouble(),
            full.e.toDouble(),
            full.f.toDouble(),
        )
        val w = image.width.toDouble()
        val h = image.height.toDouble()
        ctx.drawImage(source, 0.0, 0.0, w, h, 0.0, 0.0, w, h)
        // The edges are single pixel rows and columns stretched outwards: no smoothing, which
        // could mix in the neighboring pixels.
        ctx.imageSmoothingEnabled = false
        val clampX = tileModeX == TileMode.Clamp
        val clampY = tileModeY == TileMode.Clamp
        val left = area[0].toDouble()
        val top = area[1].toDouble()
        val right = area[2].toDouble()
        val bottom = area[3].toDouble()
        if (clampX) {
            if (left < 0) ctx.drawImage(source, 0.0, 0.0, 1.0, h, left, 0.0, -left, h)
            if (right > w) ctx.drawImage(source, w - 1, 0.0, 1.0, h, w, 0.0, right - w, h)
        }
        if (clampY) {
            if (top < 0) ctx.drawImage(source, 0.0, 0.0, w, 1.0, 0.0, top, w, -top)
            if (bottom > h) ctx.drawImage(source, 0.0, h - 1, w, 1.0, 0.0, h, w, bottom - h)
        }
        if (clampX && clampY) {
            if (left < 0 && top < 0) {
                ctx.drawImage(source, 0.0, 0.0, 1.0, 1.0, left, top, -left, -top)
            }
            if (right > w && top < 0) {
                ctx.drawImage(source, w - 1, 0.0, 1.0, 1.0, w, top, right - w, -top)
            }
            if (left < 0 && bottom > h) {
                ctx.drawImage(source, 0.0, h - 1, 1.0, 1.0, left, h, -left, bottom - h)
            }
            if (right > w && bottom > h) {
                ctx.drawImage(source, w - 1, h - 1, 1.0, 1.0, w, h, right - w, bottom - h)
            }
        }
        ctx.imageSmoothingEnabled = true
    }
}

/**
 * Blends two shaders. The Canvas 2D API can't combine fill styles, so this shader draws its
 * [destination] and then [source] into an offscreen tile of the drawn area (see
 * [WebCanvas]); without a known area it falls back to the [source].
 */
internal class CompositeShader(
    val destination: WebShader,
    val source: WebShader,
    val blendMode: BlendMode,
) : WebShader() {
    // There is no fill style for it: it is always drawn as an image.
    override fun createStyle(ctx: Context2D): JsAny? = null

    override val mayNeedRasterization: Boolean get() = true

    override fun transformed(matrix: Affine): WebShader =
        CompositeShader(destination.transformed(matrix), source.transformed(matrix), blendMode)

    override fun rasterize(ctx: Context2D, transform: Affine, width: Int, height: Int) {
        destination.rasterize(ctx, transform, width, height)
        when (blendMode) {
            BlendMode.Dst -> return
            BlendMode.Clear -> {
                ctx.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
                ctx.clearRect(0.0, 0.0, width.toDouble(), height.toDouble())
                return
            }
            else -> Unit
        }
        val operation = compositeOperationFor(blendMode) ?: SourceOverOperation
        if (!source.mayNeedRasterization) {
            ctx.globalCompositeOperation = operation
            source.rasterize(ctx, transform, width, height)
        } else {
            val lease = LayerCanvasPool.acquire(width, height)
            source.rasterize(lease.ctx, transform, width, height)
            ctx.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            ctx.globalCompositeOperation = operation
            val w = width.toDouble()
            val h = height.toDouble()
            ctx.drawImage(lease.canvas, 0.0, 0.0, w, h, 0.0, 0.0, w, h)
            LayerCanvasPool.release(lease)
        }
        ctx.globalCompositeOperation = SourceOverOperation
    }
}

private fun addDecalStops(gradient: CanvasGradient, stops: GradientStops, start: Double, end: Double) {
    gradient.addColorStop(0.0, JsStrings.transparent)
    gradient.addColorStop(max(0.0, start - DecalEpsilon), JsStrings.transparent)
    stops.addTo(gradient, start, end)
    gradient.addColorStop(min(1.0, end + DecalEpsilon), JsStrings.transparent)
    gradient.addColorStop(1.0, JsStrings.transparent)
}

/** A 1x1 context used to create gradients and patterns, which aren't bound to their context. */
internal object ScratchContext {
    val ctx: Context2D by lazy {
        getContext2D(createCanvas(1, 1), false) ?: error("Canvas 2D is not supported")
    }
}

internal fun validateColorStops(colors: List<Color>, colorStops: List<Float>?) {
    if (colorStops == null) {
        require(colors.size >= 2) {
            "colors must have length of at least 2 if colorStops is omitted."
        }
    } else {
        require(colors.size == colorStops.size) {
            "colors and colorStops arguments must have equal length."
        }
    }
}

/** The angle of the vector ([x], [y]) in radians, in 0 until 2π, clockwise from the x axis. */
internal fun angleOf(x: Float, y: Float): Float {
    val angle = atan2(y, x)
    return if (angle < 0) angle + 2 * PI.toFloat() else angle
}

/** The Euclidean length of the vector ([x], [y]). */
internal fun lengthOf(x: Float, y: Float): Float = sqrt(x * x + y * y)

internal fun Float.nearlyZero(): Boolean = abs(this) < 1e-6f
