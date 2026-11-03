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

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.webcanvas.dom.ImageData
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.test.fail

// Reads the pixels of images where the browser holds them, without copying them into Kotlin.

/** The pixels of an image created with `ImageBitmap(width, height)`. */
internal fun ImageBitmap.imageData(): ImageData = asCanvasImage().context.getImageData(0, 0, width, height)

/** The non-premultiplied ARGB color of the pixel at ([x], [y]) of [imageData]. */
// language=js
internal fun argbAt(imageData: ImageData, x: Int, y: Int): Int = js(
    """{
        const d = imageData.data, i = 4 * (y * imageData.width + x);
        return (d[i + 3] << 24) | (d[i] << 16) | (d[i + 1] << 8) | d[i + 2];
    }"""
)

/** The largest difference of a premultiplied channel between the pixels of [a] and [b]. */
internal fun maxPremultipliedDifference(a: ImageData, b: ImageData): Int =
    comparePixels(a, b, tolerance = 0, where = null).largest

/** What [comparePixels] found. */
private external interface PixelComparison : JsAny {
    /** The largest difference of a premultiplied channel of a pixel, -1 if no pixel was compared. */
    val largest: Int

    /** The index of the first pixel off by more than the tolerance, -1 if none is. */
    val first: Int
}

/**
 * Compares the premultiplied channels of the pixels of [a] and [b], of the pixels that [where], if
 * any, covers whole along with the pixels around them (an alpha of 255).
 */
// language=js
private fun comparePixels(a: ImageData, b: ImageData, tolerance: Int, where: ImageData?): PixelComparison = js(
    """{
        const p = a.data, q = b.data, width = a.width, height = a.height;
        const m = where == null ? null : where.data;
        const coveredAround = (x, y) => {
            for (let ny = y - 1; ny <= y + 1; ny++) {
                for (let nx = x - 1; nx <= x + 1; nx++) {
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height || m[4 * (ny * width + nx) + 3] !== 255) return false;
                }
            }
            return true;
        };
        let largest = -1, first = -1;
        for (let k = 0; k < width * height; k++) {
            if (m !== null && !coveredAround(k % width, Math.floor(k / width))) continue;
            const i = 4 * k, pa = p[i + 3], qa = q[i + 3];
            let difference = Math.abs(pa - qa);
            for (let c = 0; c < 3; c++) {
                difference = Math.max(difference, Math.abs(Math.round(p[i + c] * pa / 255) - Math.round(q[i + c] * qa / 255)));
            }
            largest = Math.max(largest, difference);
            if (first < 0 && difference > tolerance) first = k;
        }
        return { largest, first };
    }"""
)

/**
 * Asserts that [actual] has the pixels of [expected], premultiplied, within [tolerance] levels, or
 * fails with the largest difference and the first pixel past [tolerance], after [what]. With [where],
 * only the pixels it covers whole along with the pixels around them are compared, and it fails if
 * there are none: at the edges of what it covers, the coverage of a draw can depend on how the draw
 * is composited.
 */
internal fun assertSamePixels(
    expected: ImageData,
    actual: ImageData,
    tolerance: Int = 0,
    what: String = "",
    where: ImageData? = null,
) {
    val comparison = comparePixels(expected, actual, tolerance, where)
    val difference = comparison.largest
    if (difference < 0) fail("${what}no pixel to compare")
    if (difference <= tolerance) return
    val index = comparison.first
    val x = index % expected.width
    val y = index / expected.width
    val colors = "${argbAt(expected, x, y).toUInt().toString(16)} expected, ${argbAt(actual, x, y).toUInt().toString(16)} drawn"
    fail("${what}off by $difference levels, first at ($x, $y): $colors")
}
