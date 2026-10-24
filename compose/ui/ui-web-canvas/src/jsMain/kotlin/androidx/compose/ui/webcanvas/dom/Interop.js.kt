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

// On Kotlin/JS a ByteArray is an Int8Array and an IntArray is an Int32Array, so the data can be
// copied by JS code directly.

internal actual fun ByteArray.toUint8Array(): JsAny = copyBytes(this)

internal actual fun IntArray.toInt32Array(count: Int): JsAny = copyInts(this, count)

private fun copyInts(ints: IntArray, count: Int): JsAny = js("ints.slice(0, count)")

private fun copyBytes(bytes: ByteArray): JsAny =
    js("new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.length).slice()")

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

internal actual fun createImageDataFromArgb(
    pixels: IntArray,
    offset: Int,
    stride: Int,
    width: Int,
    height: Int,
): ImageData = createImageData(pixels, offset, stride, width, height)

private fun createImageData(
    pixels: IntArray,
    offset: Int,
    stride: Int,
    width: Int,
    height: Int,
): ImageData = js(
    """(function() {
        var image = new ImageData(width, height), d = image.data;
        for (var y = 0; y < height; y++) {
            var o = offset + y * stride, i = y * width * 4;
            for (var x = 0; x < width; x++, i += 4) {
                var p = pixels[o + x];
                d[i] = (p >> 16) & 255; d[i + 1] = (p >> 8) & 255; d[i + 2] = p & 255;
                d[i + 3] = (p >>> 24) & 255;
            }
        }
        return image;
    })()"""
)
