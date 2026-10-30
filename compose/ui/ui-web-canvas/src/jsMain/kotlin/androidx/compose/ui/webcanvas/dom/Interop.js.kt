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

package androidx.compose.ui.webcanvas.dom

import androidx.compose.ui.InternalComposeUiApi
import kotlin.js.JsAny
import kotlin.js.JsString

// On Kotlin/JS a ByteArray is an Int8Array, a ShortArray an Int16Array, an IntArray an Int32Array
// and a FloatArray a Float32Array, so JS code reads them directly.

@InternalComposeUiApi
public actual fun ByteArray.toBlob(type: String): JsAny = blobOf(bytesFrom(this, 0), type)

@InternalComposeUiApi
public actual fun ByteArray.decodeToJsString(): JsString = decodeUtf8(bytesFrom(this, 0))

internal actual fun ByteArray.toUint8Array(): JsAny = bytesFrom(this, 0)

internal actual fun <R> withByteReaders(
    floats: FloatArray,
    ints: IntArray,
    shorts: ShortArray,
    block: (floats: (Int) -> JsAny, ints: (Int) -> JsAny, shorts: (Int) -> JsAny) -> R,
): R =
    // Every read takes all the rest of the array.
    block({ bytesFrom(floats, it) }, { bytesFrom(ints, it) }, { bytesFrom(shorts, it) })

/** A `Uint8Array` view of the bytes of the typed [array] from [byteOffset] on. */
private fun bytesFrom(array: Any, byteOffset: Int): JsAny =
    js("new Uint8Array(array.buffer, array.byteOffset + byteOffset, array.byteLength - byteOffset)")

private fun blobOf(bytes: JsAny, type: String): JsAny = js("new Blob([bytes], { type: type })")

private fun decodeUtf8(bytes: JsAny): JsString = js("new TextDecoder().decode(bytes)")

internal actual fun readImageDataArgb(
    imageData: ImageData,
    buffer: IntArray,
    bufferOffset: Int,
    stride: Int,
) {
    readArgb(imageData, buffer, bufferOffset, stride)
}

private fun readArgb(imageData: ImageData, buffer: IntArray, offset: Int, stride: Int) {
    js(
        """
        var d = imageData.data, w = imageData.width, h = imageData.height;
        for (var y = 0; y < h; y++) {
            var o = offset + y * stride, i = y * w * 4;
            for (var x = 0; x < w; x++, i += 4) {
                buffer[o + x] = (d[i + 3] << 24) | (d[i] << 16) | (d[i + 1] << 8) | d[i + 2];
            }
        }
        """
    )
}
