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

import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.toJsString
import kotlin.math.min

// Kotlin/Wasm arrays live in the Wasm GC heap and can't be shared with JS. Strings, however, are
// copied between the two worlds in bulk, so the binary data travels as strings of 8-bit chars.

private const val ChunkSize = 32768

internal actual fun ByteArray.toUint8Array(): JsAny {
    val result = newUint8Array(size)
    val chars = CharArray(min(ChunkSize, size))
    var start = 0
    while (start < size) {
        val count = min(ChunkSize, size - start)
        for (i in 0 until count) {
            chars[i] = (this[start + i].toInt() and 0xFF).toChar()
        }
        copyLatin1Chars(result, start, chars.concatToString(0, count).toJsString())
        start += count
    }
    return result
}

// language=js
private fun newUint8Array(size: Int): JsAny = js("new Uint8Array(size)")

internal actual fun IntArray.toInt32Array(count: Int): JsAny {
    val result = newInt32Array(count)
    // Each int travels as two UTF-16 code units, its high and its low half.
    val chars = CharArray(2 * min(ChunkSize, count))
    var start = 0
    while (start < count) {
        val chunk = min(ChunkSize, count - start)
        for (i in 0 until chunk) {
            val value = this[start + i]
            chars[2 * i] = (value ushr 16).toChar()
            chars[2 * i + 1] = (value and 0xFFFF).toChar()
        }
        copyIntChars(result, start, chars.concatToString(0, 2 * chunk).toJsString())
        start += chunk
    }
    return result
}

// language=js
private fun newInt32Array(size: Int): JsAny = js("new Int32Array(size)")

// language=js
private fun copyIntChars(target: JsAny, offset: Int, chars: JsString): Unit = js(
    "{ for (let i = 0, n = chars.length >> 1; i < n; i++) target[offset + i] = (chars.charCodeAt(2 * i) << 16) | chars.charCodeAt(2 * i + 1); }"
)

// language=js
private fun copyLatin1Chars(target: JsAny, offset: Int, chars: JsString): Unit = js(
    "{ for (let i = 0; i < chars.length; i++) target[offset + i] = chars.charCodeAt(i); }"
)

internal actual fun readImageDataArgb(
    imageData: ImageData,
    buffer: IntArray,
    bufferOffset: Int,
    stride: Int,
) {
    val width = imageData.width
    val height = imageData.height
    for (y in 0 until height) {
        // Each pixel is encoded as two UTF-16 code units, the high and the low half of its ARGB.
        val row = readRowAsChars(imageData, y).toString()
        val offset = bufferOffset + y * stride
        for (x in 0 until width) {
            val high = row[x * 2].code
            val low = row[x * 2 + 1].code
            buffer[offset + x] = (high shl 16) or low
        }
    }
}

// language=js
private fun readRowAsChars(imageData: ImageData, y: Int): JsString = js(
    """{
        const d = imageData.data, w = imageData.width;
        const units = new Uint16Array(w * 2);
        for (let x = 0, i = y * w * 4; x < w; x++, i += 4) {
            units[x * 2] = (d[i + 3] << 8) | d[i];
            units[x * 2 + 1] = (d[i + 1] << 8) | d[i + 2];
        }
        let result = '';
        for (let start = 0; start < units.length; start += 8192) {
            result += String.fromCharCode.apply(null, units.subarray(start, start + 8192));
        }
        return result;
    }"""
)

internal actual fun createImageDataFromArgb(
    pixels: IntArray,
    offset: Int,
    stride: Int,
    width: Int,
    height: Int,
): ImageData {
    val imageData = newImageData(width, height)
    val chars = CharArray(width * 2)
    for (y in 0 until height) {
        val rowOffset = offset + y * stride
        for (x in 0 until width) {
            val pixel = pixels[rowOffset + x]
            chars[x * 2] = (pixel ushr 16).toChar()
            chars[x * 2 + 1] = (pixel and 0xFFFF).toChar()
        }
        writeRowFromChars(imageData, y, chars.concatToString().toJsString())
    }
    return imageData
}

// language=js
private fun newImageData(width: Int, height: Int): ImageData = js("new ImageData(width, height)")

// language=js
private fun writeRowFromChars(imageData: ImageData, y: Int, chars: JsString): Unit = js(
    """{
        const d = imageData.data, w = imageData.width;
        for (let x = 0, i = y * w * 4; x < w; x++, i += 4) {
            const high = chars.charCodeAt(x * 2), low = chars.charCodeAt(x * 2 + 1);
            d[i + 3] = high >> 8; d[i] = high & 255; d[i + 1] = low >> 8; d[i + 2] = low & 255;
        }
    }"""
)
