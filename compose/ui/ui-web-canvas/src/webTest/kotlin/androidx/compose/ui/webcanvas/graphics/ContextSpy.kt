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

import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.js
import kotlin.js.toJsString

/** The calls of methods of the 2D contexts that [contextCallsDuring] recorded. */
internal class ContextCalls(
    /** The names of the methods called, in the order of the calls. */
    val names: List<String>,
    /** The pixels that the calls of `getImageData` read. */
    val pixelsRead: Int,
)

/**
 * Runs [block] and returns the calls of [methods] of the 2D contexts, of canvas elements and of
 * `OffscreenCanvas`es, and of the WebGL 2 contexts while it ran.
 */
internal fun contextCallsDuring(methods: List<String>, block: () -> Unit): ContextCalls {
    val spy = spyOnContexts(methods.joinToString(",").toJsString())
    try {
        block()
    } finally {
        stopSpying(spy)
    }
    return ContextCalls(calledNames(spy).toString().split(',').filter { it.isNotEmpty() }, pixelsRead(spy))
}

// language=js
private fun spyOnContexts(methods: JsString): JsAny = js(
    """{
        const spy = { names: [], pixels: 0, restores: [] };
        const prototypes = [CanvasRenderingContext2D.prototype];
        if (typeof OffscreenCanvasRenderingContext2D !== 'undefined') {
            prototypes.push(OffscreenCanvasRenderingContext2D.prototype);
        }
        if (typeof WebGL2RenderingContext !== 'undefined') prototypes.push(WebGL2RenderingContext.prototype);
        for (const prototype of prototypes) {
            for (const name of methods.split(',')) {
                const original = prototype[name];
                if (typeof original !== 'function') continue;
                spy.restores.push(() => { prototype[name] = original; });
                prototype[name] = function () {
                    spy.names.push(name);
                    if (name === 'getImageData') spy.pixels += arguments[2] * arguments[3];
                    return original.apply(this, arguments);
                };
            }
        }
        return spy;
    }"""
)

// language=js
private fun stopSpying(spy: JsAny): Unit = js("spy.restores.forEach((restore) => restore())")

// language=js
private fun calledNames(spy: JsAny): JsString = js("spy.names.join(',')")

// language=js
private fun pixelsRead(spy: JsAny): Int = js("spy.pixels")
