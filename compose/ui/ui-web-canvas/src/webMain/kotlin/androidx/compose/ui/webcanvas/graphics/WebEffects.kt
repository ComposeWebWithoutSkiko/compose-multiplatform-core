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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.platform.PlatformBlurFilter
import androidx.compose.ui.graphics.platform.PlatformColorFilter
import androidx.compose.ui.graphics.platform.PlatformRenderEffect
import androidx.compose.ui.graphics.platform.platformColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.webcanvas.dom.toJsNumberArray
import kotlin.js.JsAny
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A color filter of the web backend. Every filter is a function of a single (non-premultiplied)
 * color, so it can be applied to solid paint colors and gradient stops directly, and to images and
 * layers pixel by pixel.
 */
internal sealed class WebColorFilter : PlatformColorFilter() {
    /** Applies the filter to a non-premultiplied ARGB color. */
    abstract fun filter(argb: Int): Int

    /** A key identifying the filter, to cache filtered images. */
    abstract val key: Any

    /**
     * The color matrix of the filter ([colorMatrixOf]), made once: of a filter that no compositing
     * operation applies.
     */
    val colorMatrix: FloatArray by lazy { colorMatrixOf(this) }

    /**
     * [colorMatrix] as a JS array, for the code that applies it in JS, made once: a copy, which JS
     * needs on Wasm, where it can't read a Kotlin array.
     */
    val jsColorMatrix: JsAny by lazy { colorMatrix.toJsNumberArray() }

    /** Applies the filter to a tint. */
    class Tint(val color: Color, val blendMode: BlendMode) : WebColorFilter() {
        /** [color] as an ARGB color. */
        val tintArgb: Int = color.toArgb()
        override val key: Any = TintKey(tintArgb, blendMode)

        override fun filter(argb: Int): Int = blendColors(tintArgb, argb, blendMode)

        /**
         * Whether Skia makes no filter for this tint (`SkColorFilters::Blend` returns none, and
         * Compose's Skia backend applies a pass-through filter): Dst, a transparent color over,
         * under, out of, atop, xor or darkening the colors, and an opaque color keeping them in.
         * Decided on the 8 bit alpha, as Skia is. Drawing applies no filter for it (see
         * [WebPaint.appliedColorFilter]).
         */
        val isNoOpBlend: Boolean =
            blendMode == BlendMode.Dst ||
                when (tintArgb ushr 24) {
                    0 ->
                        blendMode == BlendMode.SrcOver ||
                            blendMode == BlendMode.DstOver ||
                            blendMode == BlendMode.DstOut ||
                            blendMode == BlendMode.SrcAtop ||
                            blendMode == BlendMode.Xor ||
                            blendMode == BlendMode.Darken
                    255 -> blendMode == BlendMode.DstIn
                    else -> false
                }

        /**
         * Whether the tint is a compositing operation of the Canvas 2D API: filling the pixels
         * with [color] in that operation applies it. Modulate and Clear have no exact operation;
         * Dst has none, but is never applied ([isNoOpBlend]).
         */
        val appliesAsComposite: Boolean
            get() = blendMode != BlendMode.Modulate && blendMode != BlendMode.Clear

        override fun equals(other: Any?): Boolean =
            other is Tint && other.color == color && other.blendMode == blendMode

        override fun hashCode(): Int = color.hashCode() * 31 + blendMode.hashCode()
    }

    /**
     * A 4x5 color matrix in Compose's layout: the translation column is in the 0..255 range.
     */
    class Matrix(val values: FloatArray) : WebColorFilter() {
        override val key: Any = MatrixKey(values.copyOf())

        override fun filter(argb: Int): Int {
            val a = (argb ushr 24) and 0xFF
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            val v = values
            val nr = v[0] * r + v[1] * g + v[2] * b + v[3] * a + v[4]
            val ng = v[5] * r + v[6] * g + v[7] * b + v[8] * a + v[9]
            val nb = v[10] * r + v[11] * g + v[12] * b + v[13] * a + v[14]
            val na = v[15] * r + v[16] * g + v[17] * b + v[18] * a + v[19]
            return argbOf(na.toByteChannel(), nr.toByteChannel(), ng.toByteChannel(), nb.toByteChannel())
        }

        override fun equals(other: Any?): Boolean =
            other is Matrix && other.values.contentEquals(values)

        override fun hashCode(): Int = values.contentHashCode()
    }

    /** Multiplies the color channels by [multiply] and adds [add], keeping the alpha. */
    class Lighting(val multiply: Color, val add: Color) : WebColorFilter() {
        private val mul = multiply.toArgb()
        private val addArgb = add.toArgb()
        override val key: Any = LightingKey(mul, addArgb)

        override fun filter(argb: Int): Int {
            fun channel(shift: Int): Int {
                val value = (argb ushr shift) and 0xFF
                val m = (mul ushr shift) and 0xFF
                val a = (addArgb ushr shift) and 0xFF
                return min(255, value * m / 255 + a)
            }
            return argbOf((argb ushr 24) and 0xFF, channel(16), channel(8), channel(0))
        }

        override fun equals(other: Any?): Boolean =
            other is Lighting && other.multiply == multiply && other.add == add

        override fun hashCode(): Int = multiply.hashCode() * 31 + add.hashCode()
    }

    private data class TintKey(val argb: Int, val blendMode: BlendMode)
    private class MatrixKey(val values: FloatArray) {
        override fun equals(other: Any?): Boolean =
            other is MatrixKey && other.values.contentEquals(values)
        override fun hashCode(): Int = values.contentHashCode()
    }
    private data class LightingKey(val multiply: Int, val add: Int)
}

/** Extracts the backend color filter of a Compose [ColorFilter]. */
internal val ColorFilter.webColorFilter: WebColorFilter
    get() = platformColorFilter as? WebColorFilter ?: error(
        "Expected a color filter created by the web canvas implementation, but got " +
            platformColorFilter
    )

/** The filter to apply for this one: none for a tint that Skia makes no filter for. */
internal val WebColorFilter.applied: WebColorFilter?
    get() = if (this is WebColorFilter.Tint && isNoOpBlend) null else this

/** Builds the color filter of a Compose [ColorMatrix]. */
internal fun ColorMatrix.toWebColorFilter(): WebColorFilter = WebColorFilter.Matrix(values.copyOf())

/** A render effect of the web backend, applied when a layer is composited. */
internal sealed class WebRenderEffect : PlatformRenderEffect {
    /**
     * How far, in the layer's pixels, the effect can spread content outside the layer bounds:
     * stored as (left, top, right, bottom).
     */
    abstract fun outsets(out: FloatArray)

    class Blur(
        val input: WebRenderEffect?,
        val radiusX: Float,
        val radiusY: Float,
        val edgeTreatment: TileMode,
    ) : WebRenderEffect() {
        val sigmaX: Float get() = blurRadiusToSigma(radiusX)
        val sigmaY: Float get() = blurRadiusToSigma(radiusY)

        override fun outsets(out: FloatArray) {
            input?.outsets(out) ?: out.fill(0f)
            if (edgeTreatment == TileMode.Decal) {
                // A decal blur spreads content out of the layer; the others sample inside.
                val dx = 3 * sigmaX
                val dy = 3 * sigmaY
                out[0] += dx
                out[1] += dy
                out[2] += dx
                out[3] += dy
            }
        }
    }

    class Translate(val input: WebRenderEffect?, val offset: Offset) : WebRenderEffect() {
        override fun outsets(out: FloatArray) {
            input?.outsets(out) ?: out.fill(0f)
            out[0] = max(0f, out[0] - offset.x)
            out[1] = max(0f, out[1] - offset.y)
            out[2] = max(0f, out[2] + offset.x)
            out[3] = max(0f, out[3] + offset.y)
        }
    }

    /** The [shader] over the whole layer, whatever the layer holds. */
    class ShaderFill(val shader: WebShader) : WebRenderEffect() {
        override fun outsets(out: FloatArray) = out.fill(0f)
    }

    /** The result of [input], or the layer, through the [applied] one of a color filter. */
    class ColorFiltered(val input: WebRenderEffect?, filter: WebColorFilter) : WebRenderEffect() {
        val filter: WebColorFilter? = filter.applied

        /**
         * What the alpha of the result is multiplied by, if that's all the filter does (a tint
         * keeping the result within its color), and `ctx.globalAlpha` can do it: browsers apply it
         * before a filter of the same draw, as the one of [input] may be.
         */
        val alphaScale: Float? =
            (filter as? WebColorFilter.Tint)
                ?.takeIf { it.blendMode == BlendMode.DstIn && input !is ColorFiltered }
                ?.color
                ?.alpha

        override fun outsets(out: FloatArray) {
            input?.outsets(out) ?: out.fill(0f)
        }
    }

    /** [foreground] over [background] in [blendMode], each one the layer if `null`. */
    class Blend(
        val blendMode: BlendMode,
        val background: WebRenderEffect?,
        val foreground: WebRenderEffect?,
    ) : WebRenderEffect() {
        override fun outsets(out: FloatArray) {
            background?.outsets(out) ?: out.fill(0f)
            val left = out[0]
            val top = out[1]
            val right = out[2]
            val bottom = out[3]
            foreground?.outsets(out) ?: out.fill(0f)
            out[0] = max(out[0], left)
            out[1] = max(out[1], top)
            out[2] = max(out[2], right)
            out[3] = max(out[3], bottom)
        }
    }
}

/** Extracts the backend render effect of a Compose [RenderEffect]. */
internal val RenderEffect.webRenderEffect: WebRenderEffect
    get() = platformRenderEffect as? WebRenderEffect ?: error(
        "Expected a render effect created by the web canvas implementation, but got " +
            platformRenderEffect
    )

/** Blurs the coverage of drawn shapes, used for shadows (see `Paint.setBlurFilter`). */
internal class WebBlurFilter(val radius: Float) : PlatformBlurFilter() {
    val sigma: Float get() = blurRadiusToSigma(radius)

    override fun equals(other: Any?): Boolean = other is WebBlurFilter && other.radius == radius

    override fun hashCode(): Int = radius.hashCode()
}

/**
 * Converts a blur radius into the standard deviation of the gaussian blur, the same way the Skia
 * implementation does (approximating `SkBlurMask::Blur`'s "high quality" scaling, 1 / sqrt(3)).
 */
internal fun blurRadiusToSigma(radius: Float): Float =
    if (radius > 0) BlurSigmaScale * radius + 0.5f else 0f

private const val BlurSigmaScale = 0.57735f

internal fun Float.toByteChannel(): Int = min(255, max(0, roundToInt()))

internal fun argbOf(a: Int, r: Int, g: Int, b: Int): Int =
    (a shl 24) or (r shl 16) or (g shl 8) or b

/**
 * Blends the non-premultiplied colors [src] over [dst] with [blendMode], the way Skia's blend color
 * filter does.
 */
internal fun blendColors(src: Int, dst: Int, blendMode: BlendMode): Int {
    val sa = ((src ushr 24) and 0xFF) / 255f
    val da = ((dst ushr 24) and 0xFF) / 255f
    // Premultiplied components.
    val sr = ((src ushr 16) and 0xFF) / 255f * sa
    val sg = ((src ushr 8) and 0xFF) / 255f * sa
    val sb = (src and 0xFF) / 255f * sa
    val dr = ((dst ushr 16) and 0xFF) / 255f * da
    val dg = ((dst ushr 8) and 0xFF) / 255f * da
    val db = (dst and 0xFF) / 255f * da

    val ra: Float
    val rr: Float
    val rg: Float
    val rb: Float
    when (blendMode) {
        BlendMode.Clear -> {
            ra = 0f; rr = 0f; rg = 0f; rb = 0f
        }
        BlendMode.Src -> {
            ra = sa; rr = sr; rg = sg; rb = sb
        }
        BlendMode.Dst -> {
            ra = da; rr = dr; rg = dg; rb = db
        }
        BlendMode.SrcOver -> {
            ra = sa + da * (1 - sa); rr = sr + dr * (1 - sa); rg = sg + dg * (1 - sa); rb = sb + db * (1 - sa)
        }
        BlendMode.DstOver -> {
            ra = da + sa * (1 - da); rr = dr + sr * (1 - da); rg = dg + sg * (1 - da); rb = db + sb * (1 - da)
        }
        BlendMode.SrcIn -> {
            ra = sa * da; rr = sr * da; rg = sg * da; rb = sb * da
        }
        BlendMode.DstIn -> {
            ra = da * sa; rr = dr * sa; rg = dg * sa; rb = db * sa
        }
        BlendMode.SrcOut -> {
            ra = sa * (1 - da); rr = sr * (1 - da); rg = sg * (1 - da); rb = sb * (1 - da)
        }
        BlendMode.DstOut -> {
            ra = da * (1 - sa); rr = dr * (1 - sa); rg = dg * (1 - sa); rb = db * (1 - sa)
        }
        BlendMode.SrcAtop -> {
            ra = da; rr = sr * da + dr * (1 - sa); rg = sg * da + dg * (1 - sa); rb = sb * da + db * (1 - sa)
        }
        BlendMode.DstAtop -> {
            ra = sa; rr = dr * sa + sr * (1 - da); rg = dg * sa + sg * (1 - da); rb = db * sa + sb * (1 - da)
        }
        BlendMode.Xor -> {
            ra = sa * (1 - da) + da * (1 - sa)
            rr = sr * (1 - da) + dr * (1 - sa)
            rg = sg * (1 - da) + dg * (1 - sa)
            rb = sb * (1 - da) + db * (1 - sa)
        }
        BlendMode.Plus -> {
            ra = min(1f, sa + da); rr = min(1f, sr + dr); rg = min(1f, sg + dg); rb = min(1f, sb + db)
        }
        BlendMode.Modulate -> {
            ra = sa * da; rr = sr * dr; rg = sg * dg; rb = sb * db
        }
        else -> {
            // The other blend modes: the blend B of the non-premultiplied colors, mixed with
            // source-over alpha compositing (W3C compositing).
            val blended = Blended
            blend(
                if (sa > 0) sr / sa else 0f,
                if (sa > 0) sg / sa else 0f,
                if (sa > 0) sb / sa else 0f,
                if (da > 0) dr / da else 0f,
                if (da > 0) dg / da else 0f,
                if (da > 0) db / da else 0f,
                blendMode,
                blended,
            )
            ra = sa + da * (1 - sa)
            rr = (1 - da) * sr + (1 - sa) * dr + sa * da * blended[0]
            rg = (1 - da) * sg + (1 - sa) * dg + sa * da * blended[1]
            rb = (1 - da) * sb + (1 - sa) * db + sa * da * blended[2]
        }
    }
    if (ra <= 0f) return 0
    val a = (ra * 255).toByteChannel()
    return argbOf(
        a,
        (rr / ra * 255).toByteChannel(),
        (rg / ra * 255).toByteChannel(),
        (rb / ra * 255).toByteChannel(),
    )
}

/** The blend of [blendColors] for the modes other than the Porter-Duff ones. */
private val Blended = FloatArray(3)

/**
 * Sets [out] to the blend B(cs, cb) of the non-premultiplied source color ([sr], [sg], [sb]) and
 * backdrop color ([br], [bg], [bb]) with [blendMode], a separable or non-separable blend mode of
 * the W3C compositing spec.
 */
private fun blend(
    sr: Float,
    sg: Float,
    sb: Float,
    br: Float,
    bg: Float,
    bb: Float,
    blendMode: BlendMode,
    out: FloatArray,
) {
    when (blendMode) {
        BlendMode.Hue -> {
            setSaturation(sr, sg, sb, saturation(br, bg, bb), out)
            setLuminosity(out[0], out[1], out[2], luminosity(br, bg, bb), out)
        }
        BlendMode.Saturation -> {
            setSaturation(br, bg, bb, saturation(sr, sg, sb), out)
            setLuminosity(out[0], out[1], out[2], luminosity(br, bg, bb), out)
        }
        BlendMode.Color -> setLuminosity(sr, sg, sb, luminosity(br, bg, bb), out)
        BlendMode.Luminosity -> setLuminosity(br, bg, bb, luminosity(sr, sg, sb), out)
        else -> {
            out[0] = separable(sr, br, blendMode)
            out[1] = separable(sg, bg, blendMode)
            out[2] = separable(sb, bb, blendMode)
        }
    }
}

/** Lum(C) of the W3C compositing spec. */
private fun luminosity(r: Float, g: Float, b: Float): Float = 0.3f * r + 0.59f * g + 0.11f * b

/** Sat(C) of the W3C compositing spec. */
private fun saturation(r: Float, g: Float, b: Float): Float = max(r, max(g, b)) - min(r, min(g, b))

/**
 * Sets [out] to SetLum(C, [l]) of the W3C compositing spec, ClipColor included, for C = ([r], [g],
 * [b]).
 */
private fun setLuminosity(r: Float, g: Float, b: Float, l: Float, out: FloatArray) {
    val d = l - luminosity(r, g, b)
    var cr = r + d
    var cg = g + d
    var cb = b + d
    val n = min(cr, min(cg, cb))
    val x = max(cr, max(cg, cb))
    if (n < 0f) {
        val k = l / (l - n)
        cr = l + (cr - l) * k
        cg = l + (cg - l) * k
        cb = l + (cb - l) * k
    }
    if (x > 1f) {
        val k = (1 - l) / (x - l)
        cr = l + (cr - l) * k
        cg = l + (cg - l) * k
        cb = l + (cb - l) * k
    }
    out[0] = cr
    out[1] = cg
    out[2] = cb
}

/** Sets [out] to SetSat(C, [s]) of the W3C compositing spec for C = ([r], [g], [b]). */
private fun setSaturation(r: Float, g: Float, b: Float, s: Float, out: FloatArray) {
    val n = min(r, min(g, b))
    val x = max(r, max(g, b))
    // The largest channel becomes s, the smallest 0 and the middle one keeps its place between.
    val k = if (x > n) s / (x - n) else 0f
    out[0] = (r - n) * k
    out[1] = (g - n) * k
    out[2] = (b - n) * k
}

/** The blend B(cs, cb) of one channel with a separable blend mode (W3C compositing spec). */
private fun separable(cs: Float, cb: Float, blendMode: BlendMode): Float =
    when (blendMode) {
        BlendMode.Multiply -> cs * cb
        BlendMode.Screen -> cs + cb - cs * cb
        BlendMode.Overlay -> hardLight(cb, cs)
        BlendMode.Darken -> min(cs, cb)
        BlendMode.Lighten -> max(cs, cb)
        BlendMode.ColorDodge -> when {
            cb == 0f -> 0f
            cs >= 1f -> 1f
            else -> min(1f, cb / (1 - cs))
        }
        BlendMode.ColorBurn -> when {
            cb >= 1f -> 1f
            cs <= 0f -> 0f
            else -> 1 - min(1f, (1 - cb) / cs)
        }
        BlendMode.Hardlight -> hardLight(cs, cb)
        BlendMode.Softlight -> if (cs <= 0.5f) {
            cb - (1 - 2 * cs) * cb * (1 - cb)
        } else {
            val d = if (cb <= 0.25f) ((16 * cb - 12) * cb + 4) * cb else sqrt(cb)
            cb + (2 * cs - 1) * (d - cb)
        }
        BlendMode.Difference -> abs(cs - cb)
        BlendMode.Exclusion -> cs + cb - 2 * cs * cb
        else -> error("$blendMode is not a separable blend mode")
    }

private fun hardLight(cs: Float, cb: Float): Float =
    if (cs <= 0.5f) cb * 2 * cs else {
        val s = 2 * cs - 1
        cb + s - cb * s
    }
