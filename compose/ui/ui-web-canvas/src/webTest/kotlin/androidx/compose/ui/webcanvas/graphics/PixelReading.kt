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
import kotlin.js.js

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
// language=js
internal fun maxPremultipliedDifference(a: ImageData, b: ImageData): Int = js(
    """{
        const p = a.data, q = b.data;
        let max = 0;
        for (let i = 0; i < p.length; i += 4) {
            const pa = p[i + 3], qa = q[i + 3];
            max = Math.max(max, Math.abs(pa - qa));
            for (let c = 0; c < 3; c++) {
                max = Math.max(max, Math.abs(Math.round(p[i + c] * pa / 255) - Math.round(q[i + c] * qa / 255)));
            }
        }
        return max;
    }"""
)
