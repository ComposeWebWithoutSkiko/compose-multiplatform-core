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

import androidx.compose.ui.webcanvas.dom.CanvasFeatures
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.filterScopeOf
import kotlin.js.JsAny
import kotlin.js.JsReference
import kotlin.js.JsString
import kotlin.js.js
import kotlin.js.toJsReference
import kotlin.js.toJsString
import kotlin.js.unsafeCast

/**
 * SVG filters applied on the GPU through `ctx.filter`, which references them (`url(#id)`): what
 * the CSS filter functions can't do, such as color matrices (see [SvgColorFilters]) and blurs of two
 * deviations (see [Effects]).
 *
 * Browsers resolve the references in the 2D contexts of `<canvas>` elements only, not of
 * `OffscreenCanvas`, and in the tree of the canvas: the document, or the shadow root of a canvas in
 * a shadow tree (see [filterScopeOf]). The filters live in a hidden `<svg>` element of each tree
 * that uses them, one `<filter>` per filter in use; the least recently used ones are reused for new
 * filters (e.g. an animated matrix).
 */
internal object SvgFilters {
    /** Whether the browser applies the filters. */
    val isSupported: Boolean by lazy { CanvasFeatures.filter && detectSvgFilters() }

    private const val MaxFilters = 32

    /**
     * What a filter does, which identifies it: equal contents make the same filter. Filters are
     * looked up with contents reused for every lookup, of which the filters keep a copy ([kept]).
     */
    abstract class Content {
        /**
         * Writes the attributes and the primitives of the filter into [filter], a `<filter>`
         * element with nothing but its id.
         */
        abstract fun writeTo(filter: JsAny)

        /** A copy of this content, which doesn't change. */
        abstract fun kept(): Content
    }

    private class Entry(val content: Content, val value: JsString, val element: JsAny)

    /** The filters of a tree: its `<svg>`, and its filters, the most recently used last. */
    private class TreeFilters(val container: JsAny) {
        val entries = ArrayList<Entry>()
    }

    /** The [TreeFilters] of the trees, by the node that resolves them: they don't keep it alive. */
    private val trees = createWeakMap()
    private var nextId = 0

    /**
     * Returns the value of `ctx.filter` that applies the filter of [content] in [ctx], or `null` if
     * [ctx] can't resolve one.
     */
    fun valueOf(ctx: Context2D, content: Content): JsString? {
        if (!isSupported) return null
        val scope = filterScopeOf(ctx) ?: return null
        val tree = weakMapGet(trees, scope)?.unsafeCast<JsReference<TreeFilters>>()?.get()
            ?: TreeFilters(createFilterContainer(scope)).also {
                weakMapSet(trees, scope, it.toJsReference())
            }
        // The page may replace the content of its body, taking the filters away.
        ensureInScope(tree.container, scope)
        val entries = tree.entries
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            if (entry.content == content) {
                if (i != entries.lastIndex) {
                    entries.removeAt(i)
                    entries.add(entry)
                }
                return entry.value
            }
        }
        // A new id for every new filter, so that contexts don't keep using what they resolved for
        // the previous filter of a reused element.
        val id = "compose-filter-${nextId++}"
        val element = if (entries.size < MaxFilters) {
            createFilter(tree.container)
        } else {
            entries.removeAt(0).element
        }
        resetFilter(element, id)
        content.writeTo(element)
        val value = "url(#$id)".toJsString()
        entries.add(Entry(content.kept(), value, element))
        return value
    }
}

// language=js
private fun createWeakMap(): JsAny = js("new WeakMap()")

// language=js
private fun weakMapGet(map: JsAny, key: JsAny): JsAny? = js("map.get(key)")

// language=js
private fun weakMapSet(map: JsAny, key: JsAny, value: JsAny): Unit = js("{ map.set(key, value); }")

/** A hidden `<svg>` element to hold filters, in the document of [scope]. */
// language=js
private fun createFilterContainer(scope: JsAny): JsAny = js(
    """{
        const document = scope.ownerDocument || scope;
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('width', '0');
        svg.setAttribute('height', '0');
        svg.setAttribute('aria-hidden', 'true');
        svg.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden;pointer-events:none';
        return svg;
    }"""
)

/** Puts [container] in [scope] (a shadow root, or the body of a document) unless it's there. */
// language=js
private fun ensureInScope(container: JsAny, scope: JsAny): Unit = js(
    """{
        if (container.getRootNode() === scope) return;
        const parent = (typeof ShadowRoot !== 'undefined' && scope instanceof ShadowRoot)
            ? scope
            : (scope.body || scope.documentElement);
        parent.appendChild(container);
    }"""
)

/** A new `<filter>` element in [container]. */
// language=js
private fun createFilter(container: JsAny): JsAny = js(
    """{
        const filter = container.ownerDocument.createElementNS('http://www.w3.org/2000/svg', 'filter');
        container.appendChild(filter);
        return filter;
    }"""
)

/** Takes the attributes and the children of [filter] away, and gives it [id]. */
// language=js
private fun resetFilter(filter: JsAny, id: String): Unit = js(
    """{
        while (filter.attributes.length > 0) filter.removeAttribute(filter.attributes[0].name);
        filter.replaceChildren();
        filter.setAttribute('id', id);
    }"""
)

/** Applies a filter turning red into green to a red pixel, and checks that it became green. */
// language=js
private fun detectSvgFilters(): Boolean = js(
    """{
        try {
            if (typeof document === 'undefined') return false;
            const ns = 'http://www.w3.org/2000/svg';
            const svg = document.createElementNS(ns, 'svg');
            svg.setAttribute('width', '0');
            svg.setAttribute('height', '0');
            svg.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden';
            const filter = document.createElementNS(ns, 'filter');
            filter.setAttribute('id', 'compose-filter-detection');
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
                ctx.filter = 'url(#compose-filter-detection)';
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
