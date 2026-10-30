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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.webcanvas.dom.Context2D
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.js

/**
 * Color filters applied on the GPU, through SVG `<feColorMatrix>` filters (see [SvgFilters]):
 * drawing with such a filter applies the color matrix to the drawn pixels.
 *
 * This covers the filters that compositing operations can't apply: color matrices, lightings, and
 * the Modulate and Clear tints. Where the browser applies no SVG filters, the same matrices
 * ([colorMatrixOf]) apply to the pixels (see `applyColorFilterInPlace`).
 */
internal object SvgColorFilters {
    /** Whether the browser applies the filters. */
    val isSupported: Boolean get() = SvgFilters.isSupported

    /**
     * Whether [filter] applies through an SVG filter, rather than in place (see
     * `applyColorFilterInPlace`): a tint that is a compositing operation
     * ([WebColorFilter.Tint.appliesAsComposite]) doesn't, nor does any filter if the browser can't
     * apply them.
     */
    fun appliesThroughSvg(filter: WebColorFilter): Boolean =
        isSupported && (filter as? WebColorFilter.Tint)?.appliesAsComposite != true

    /** The content of every lookup. */
    private val probe = ColorMatrix()

    /**
     * Returns the value of `ctx.filter` that applies [filter] in [ctx], or `null` if [filter]
     * doesn't apply through an SVG filter (see [appliesThroughSvg]) or [ctx] can't resolve one.
     */
    fun filterValue(filter: WebColorFilter, ctx: Context2D): JsString? {
        if (!appliesThroughSvg(filter)) return null
        probe.filter = filter
        return SvgFilters.valueOf(ctx, probe)
    }

    /** The color matrix of a color [filter], the same filter for the same [WebColorFilter.key]. */
    private class ColorMatrix(var filter: WebColorFilter? = null) : SvgFilters.Content() {
        override fun writeTo(filter: JsAny) {
            writeColorMatrix(filter, checkNotNull(this.filter).colorMatrix.joinToString(" "))
        }

        override fun kept(): SvgFilters.Content = ColorMatrix(filter)

        override fun equals(other: Any?): Boolean =
            other is ColorMatrix && other.filter?.key == filter?.key

        override fun hashCode(): Int = filter?.key.hashCode()
    }

}

/**
 * The color matrix applying [filter], a filter that a paint applies ([WebPaint.appliedColorFilter])
 * and no compositing operation does: a 4x5 matrix, row by row, over non-premultiplied colors with
 * channels from 0 to 1, as `<feColorMatrix>` takes it. Values that aren't finite are 0.
 */
internal fun colorMatrixOf(filter: WebColorFilter): FloatArray {
    val m = FloatArray(20)
    when (filter) {
        is WebColorFilter.Matrix -> {
            // Compose's translation column is in the 0..255 range.
            for (i in 0 until 20) {
                val value = if (i % 5 == 4) filter.values[i] / 255f else filter.values[i]
                m[i] = if (value.isFinite()) value else 0f
            }
        }
        is WebColorFilter.Lighting -> {
            val multiply = filter.multiply.toArgb()
            val add = filter.add.toArgb()
            m[0] = channel(multiply, 16)
            m[4] = channel(add, 16)
            m[6] = channel(multiply, 8)
            m[9] = channel(add, 8)
            m[12] = channel(multiply, 0)
            m[14] = channel(add, 0)
            m[18] = 1f
        }
        is WebColorFilter.Tint -> when (filter.blendMode) {
            // The premultiplied product of the colors, so the product of the channels.
            BlendMode.Modulate -> {
                val color = filter.color.toArgb()
                m[0] = channel(color, 16)
                m[6] = channel(color, 8)
                m[12] = channel(color, 0)
                m[18] = channel(color, 24)
            }
            // Transparent everywhere.
            BlendMode.Clear -> Unit
            else -> error("A tint of ${filter.blendMode} applies as a compositing operation or not at all")
        }
    }
    return m
}

/** The channel of [argb] at [shift], from 0 to 1. */
private fun channel(argb: Int, shift: Int): Float = ((argb ushr shift) and 0xFF) / 255f

/** Makes [filter] apply the color matrix [values] to the sRGB values, like Skia's color filters. */
// language=js
private fun writeColorMatrix(filter: JsAny, values: String): Unit = js(
    """{
        filter.setAttribute('color-interpolation-filters', 'sRGB');
        const matrix = filter.ownerDocument.createElementNS('http://www.w3.org/2000/svg', 'feColorMatrix');
        matrix.setAttribute('type', 'matrix');
        matrix.setAttribute('values', values);
        filter.appendChild(matrix);
    }"""
)
