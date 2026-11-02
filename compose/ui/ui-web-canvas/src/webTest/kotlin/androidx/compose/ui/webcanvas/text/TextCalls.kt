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

package androidx.compose.ui.webcanvas.text

import kotlin.js.JsAny
import kotlin.js.js

/**
 * Records the calls of the text [method] (`measureText`, `fillText`) of the 2D contexts while
 * [block] runs: for each, the canvas of the context, its language (`ctx.lang`) and the text, as
 * they were at the call.
 */
internal fun textCallsDuring(method: String, block: () -> Unit): JsAny {
    val spy = spyOnTextCalls(method)
    try {
        block()
    } finally {
        stopSpying(spy)
    }
    return spy
}

/** Counts the calls of `measureText` of the 2D contexts while [block] runs. */
internal fun measureTextCallsDuring(block: () -> Unit): Int = callCount(textCallsDuring("measureText", block))

// language=js
internal fun callCount(spy: JsAny): Int = js("spy.calls.length")

/** Whether every call recorded by [spy] was made in a context of a canvas of the class of [canvas]. */
// language=js
internal fun allOnTheClassOf(spy: JsAny, canvas: JsAny): Boolean =
    js("spy.calls.every((call) => call.canvas.constructor === canvas.constructor)")

/** Whether [spy] recorded a call at least, and every one in the language [lang]. */
// language=js
internal fun someAndAllIn(spy: JsAny, lang: String): Boolean =
    js("spy.calls.length > 0 && spy.calls.every((call) => call.lang === lang)")

/**
 * Whether [spy] recorded a call of a text containing [part] at least, and every such call in the
 * language [lang].
 */
// language=js
internal fun someAndAllWithPartIn(spy: JsAny, part: String, lang: String): Boolean = js(
    "spy.calls.some((call) => call.text.includes(part)) && spy.calls.every((call) => !call.text.includes(part) || call.lang === lang)"
)

/**
 * Whether [spy] recorded a call of a text without [part] at least, and every such call in the
 * language [lang].
 */
// language=js
internal fun someAndAllWithoutPartIn(spy: JsAny, part: String, lang: String): Boolean = js(
    "spy.calls.some((call) => !call.text.includes(part)) && spy.calls.every((call) => call.text.includes(part) || call.lang === lang)"
)

// language=js
private fun spyOnTextCalls(method: String): JsAny = js(
    """{
        const spy = { method: method, calls: [], prototypes: [CanvasRenderingContext2D.prototype], originals: [] };
        if (typeof OffscreenCanvasRenderingContext2D !== 'undefined') {
            spy.prototypes.push(OffscreenCanvasRenderingContext2D.prototype);
        }
        for (const prototype of spy.prototypes) {
            const original = prototype[method];
            spy.originals.push(original);
            prototype[method] = function (text) {
                spy.calls.push({ canvas: this.canvas, lang: this.lang, text: text });
                return original.apply(this, arguments);
            };
        }
        return spy;
    }"""
)

// language=js
private fun stopSpying(spy: JsAny): Unit =
    js("spy.prototypes.forEach((prototype, i) => { prototype[spy.method] = spy.originals[i]; })")
