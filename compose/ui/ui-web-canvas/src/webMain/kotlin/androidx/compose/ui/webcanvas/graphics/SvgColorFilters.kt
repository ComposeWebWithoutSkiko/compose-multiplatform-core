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

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.webcanvas.dom.CanvasFeatures
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.js
import kotlin.js.toJsString

/**
 * Color filters applied on the GPU, through SVG `<feColorMatrix>` filters that `ctx.filter`
 * references: drawing with such a filter applies the color matrix to the drawn pixels.
 *
 * This covers the filters that compositing operations can't apply: color matrices, lightings, and
 * the Modulate and Clear tints. Browsers only resolve the references in the 2D contexts of
 * `<canvas>` elements, not of `OffscreenCanvas`, so the filtered pixels are drawn into elements
 * (see `createElementCanvas`).
 *
 * The filters live in a hidden `<svg>` element of the page, one `<filter>` per color filter in use;
 * the least recently used ones are reused for new color filters (e.g. an animated matrix).
 */
internal object SvgColorFilters {
    /** Whether the browser applies the filters. */
    val isSupported: Boolean by lazy { CanvasFeatures.filter && detectSvgColorFilters() }

    private const val MaxFilters = 32

    private class Entry(val key: Any, var value: JsString, val element: JsAny)

    /** The filters, the most recently used last. */
    private val entries = ArrayList<Entry>()
    private var container: JsAny? = null
    private var nextId = 0

    /**
     * Returns the `ctx.filter` value that applies [filter], or `null` if the browser can't, or if
     * [filter] is a tint that is a compositing operation ([WebColorFilter.Tint.appliesAsComposite]).
     */
    fun filterValue(filter: WebColorFilter): JsString? {
        if (!isSupported) return null
        val key = filter.key
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            if (entry.key == key) {
                if (i != entries.lastIndex) {
                    entries.removeAt(i)
                    entries.add(entry)
                }
                container?.let { ensureConnected(it) }
                return entry.value
            }
        }
        val values = matrixValues(filter) ?: return null
        val svg = container ?: createFilterContainer().also { container = it }
        ensureConnected(svg)
        // A new id for every new matrix, so that contexts don't keep using what they resolved for
        // the previous matrix of a reused element.
        val id = "compose-color-filter-${nextId++}"
        val value = "url(#$id)".toJsString()
        val entry = if (entries.size < MaxFilters) {
            Entry(key, value, createColorMatrixFilter(svg, id, values))
        } else {
            val reused = entries.removeAt(0)
            updateColorMatrixFilter(reused.element, id, values)
            Entry(key, value, reused.element)
        }
        entries.add(entry)
        return value
    }

    /**
     * The `values` of the `<feColorMatrix>` applying [filter]: a 4x5 matrix, row by row, over
     * non-premultiplied colors with channels from 0 to 1.
     */
    private fun matrixValues(filter: WebColorFilter): String? {
        val m = FloatArray(20)
        when (filter) {
            is WebColorFilter.Matrix -> {
                // Compose's translation column is in the 0..255 range.
                for (i in 0 until 20) {
                    m[i] = if (i % 5 == 4) filter.values[i] / 255f else filter.values[i]
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
                else -> return null
            }
        }
        return buildString {
            for (i in m.indices) {
                if (i > 0) append(' ')
                val value = m[i]
                append(if (value.isFinite()) value.toString() else "0")
            }
        }
    }

    /** The channel of [argb] at [shift], from 0 to 1. */
    private fun channel(argb: Int, shift: Int): Float = ((argb ushr shift) and 0xFF) / 255f
}

// language=js
private fun createFilterContainer(): JsAny = js(
    """{
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('width', '0');
        svg.setAttribute('height', '0');
        svg.setAttribute('aria-hidden', 'true');
        svg.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden;pointer-events:none';
        return svg;
    }"""
)

// The page may replace the content of its body, taking the filters away.
// language=js
private fun ensureConnected(container: JsAny): Unit = js(
    """{
        if (!container.isConnected) (document.body || document.documentElement).appendChild(container);
    }"""
)

// language=js
private fun createColorMatrixFilter(container: JsAny, id: String, values: String): JsAny = js(
    """{
        const ns = 'http://www.w3.org/2000/svg';
        const filter = document.createElementNS(ns, 'filter');
        filter.setAttribute('id', id);
        // The matrix applies to the sRGB values, like Skia's color filters.
        filter.setAttribute('color-interpolation-filters', 'sRGB');
        const matrix = document.createElementNS(ns, 'feColorMatrix');
        matrix.setAttribute('type', 'matrix');
        matrix.setAttribute('values', values);
        filter.appendChild(matrix);
        container.appendChild(filter);
        return filter;
    }"""
)

// language=js
private fun updateColorMatrixFilter(filter: JsAny, id: String, values: String): Unit = js(
    """{
        filter.setAttribute('id', id);
        filter.firstChild.setAttribute('values', values);
    }"""
)

/** Applies a filter turning red into green to a red pixel, and checks that it became green. */
// language=js
private fun detectSvgColorFilters(): Boolean = js(
    """{
        try {
            if (typeof document === 'undefined') return false;
            const ns = 'http://www.w3.org/2000/svg';
            const svg = document.createElementNS(ns, 'svg');
            svg.setAttribute('width', '0');
            svg.setAttribute('height', '0');
            svg.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden';
            const filter = document.createElementNS(ns, 'filter');
            filter.setAttribute('id', 'compose-color-filter-detection');
            filter.setAttribute('color-interpolation-filters', 'sRGB');
            const matrix = document.createElementNS(ns, 'feColorMatrix');
            matrix.setAttribute('type', 'matrix');
            matrix.setAttribute('values', '0 0 0 0 0  1 0 0 0 0  0 0 0 0 0  0 0 0 1 0');
            filter.appendChild(matrix);
            svg.appendChild(filter);
            (document.body || document.documentElement).appendChild(svg);
            try {
                const canvas = document.createElement('canvas');
                canvas.width = 1;
                canvas.height = 1;
                const ctx = canvas.getContext('2d', { willReadFrequently: true });
                if (!ctx) return false;
                ctx.filter = 'url(#compose-color-filter-detection)';
                ctx.fillStyle = '#f00';
                ctx.fillRect(0, 0, 1, 1);
                const d = ctx.getImageData(0, 0, 1, 1).data;
                return d[0] < 8 && d[1] > 247 && d[3] > 247;
            } finally {
                svg.remove();
            }
        } catch (e) {
            return false;
        }
    }"""
)
