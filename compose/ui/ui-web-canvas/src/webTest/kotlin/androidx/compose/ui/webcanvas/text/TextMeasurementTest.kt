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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TextMeasurementTest {
    private class Entry(val key: Int)

    @Test
    fun aRecentCacheKeepsTheValuesUsedLately() {
        val cache = RecentCache<Int, Entry>(capacity = 2) { it.key }
        val kept = Entry(0)
        cache.put(kept)
        for (key in 1..5) {
            cache.put(Entry(key))
            // Used all along: it stays.
            assertSame(kept, cache[0])
        }
        // Not used since the cache filled up twice: forgotten.
        assertNull(cache[1])
        assertEquals(5, cache[5]?.key)
    }

    @Test
    fun aWidthUsedAllAlongStaysMeasuredAmongManyOthers() {
        val font = RunFont.of(
            WebTypeface(GenericFamilies.SansSerif, 400, isItalic = false, isRegistered = false, isGeneric = true),
            size = 14f,
            weight = 400,
            isItalic = false,
            letterSpacing = 0f,
            scaleX = 1f,
            pixelRatio = 1f,
        )
        val width = TextMeasurement.width(font, "kept")
        // More words than the cache holds, the kept one measured again now and then.
        for (i in 0 until 50_200) {
            TextMeasurement.width(font, "word $i")
            if (i % 1000 == 500) TextMeasurement.width(font, "kept")
        }
        var again = 0f
        val calls = measureTextCallsDuring { again = TextMeasurement.width(font, "kept") }
        assertEquals(width, again)
        assertEquals(0, calls, "measured again")
    }

    @Test
    fun theMetricsOfABrowserWithoutFontBoxesAreApproximatedFromTheSize() {
        // Firefox before 116 measures no font bounding boxes.
        val font = RunFont.of(
            WebTypeface(GenericFamilies.SansSerif, 400, isItalic = false, isRegistered = false, isGeneric = true),
            size = 20f,
            weight = 400,
            isItalic = false,
            letterSpacing = 0f,
            scaleX = 1f,
            pixelRatio = 1f,
        )
        val restore = hideFontBoundingBoxes()
        try {
            TextMeasurement.clearCaches()
            val metrics = TextMeasurement.metrics(font)
            assertEquals(-18f, metrics.ascent, 0.01f)
            assertEquals(5f, metrics.descent, 0.01f)
        } finally {
            call(restore)
            TextMeasurement.clearCaches()
        }
    }
}

/**
 * Makes the `TextMetrics` of the browser lack their font bounding boxes, and returns the function
 * that gives them back.
 */
// language=js
private fun hideFontBoundingBoxes(): JsAny = js(
    """{
        const names = ['fontBoundingBoxAscent', 'fontBoundingBoxDescent'];
        const saved = names.map(name => Object.getOwnPropertyDescriptor(TextMetrics.prototype, name));
        names.forEach(name => Object.defineProperty(TextMetrics.prototype, name, { get: () => undefined, configurable: true }));
        return () => names.forEach((name, i) => {
            if (saved[i]) Object.defineProperty(TextMetrics.prototype, name, saved[i]);
            else delete TextMetrics.prototype[name];
        });
    }"""
)

// language=js
private fun call(block: JsAny): Unit = js("block()")

/** Counts the calls of `measureText` of the 2D contexts while [block] runs. */
internal fun measureTextCallsDuring(block: () -> Unit): Int {
    val spy = spyOnMeasureText()
    try {
        block()
    } finally {
        stopSpying(spy)
    }
    return callCount(spy)
}

// language=js
private fun spyOnMeasureText(): JsAny = js(
    """{
        const spy = { count: 0, prototypes: [CanvasRenderingContext2D.prototype], originals: [] };
        if (typeof OffscreenCanvasRenderingContext2D !== 'undefined') {
            spy.prototypes.push(OffscreenCanvasRenderingContext2D.prototype);
        }
        for (const prototype of spy.prototypes) {
            const original = prototype.measureText;
            spy.originals.push(original);
            prototype.measureText = function () {
                spy.count++;
                return original.apply(this, arguments);
            };
        }
        return spy;
    }"""
)

// language=js
private fun stopSpying(spy: JsAny): Unit =
    js("spy.prototypes.forEach((prototype, i) => { prototype.measureText = spy.originals[i]; })")

// language=js
private fun callCount(spy: JsAny): Int = js("spy.count")
