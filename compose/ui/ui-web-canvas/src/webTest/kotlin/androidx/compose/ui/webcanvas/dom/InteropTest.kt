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

@file:OptIn(InternalComposeUiApi::class)

package androidx.compose.ui.webcanvas.dom

import androidx.compose.ui.InternalComposeUiApi
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The data that Kotlin and JS hand each other: empty, small, and larger than the chunk of linear
 * memory it goes through on Kotlin/Wasm.
 */
class InteropTest {
    // Every byte value.
    private val bytes = ByteArray(100_003) { (it * 7).toByte() }

    /** The index of the first byte of [array] that differs from [bytes], or -1. */
    private fun firstDifference(array: JsAny): Int {
        assertEquals(bytes.size, lengthOf(array))
        return bytes.indices.firstOrNull { (bytes[it].toInt() and 0xFF) != byteAt(array, it) } ?: -1
    }

    @Test
    fun bytesToKeepReachJsAsTheyAre() {
        assertEquals(-1, firstDifference(bytes.toUint8Array()))
        assertEquals(0, lengthOf(ByteArray(0).toUint8Array()))
    }

    @Test
    fun bytesToKeepStayAsTheyAreAfterOtherData() {
        val kept = bytes.toUint8Array()
        // Other data handed over since, through the same memory.
        ByteArray(200_000) { -1 }.toUint8Array()
        assertEquals(-1, firstDifference(kept))
    }

    @Test
    fun blobsHoldTheBytes(): Promise<JsAny?> {
        val blob = bytes.toBlob("image/png")
        assertEquals("image/png", typeOf(blob))
        assertEquals(0, sizeOf(ByteArray(0).toBlob("image/png")))
        return bytesOf(blob).then { array ->
            assertEquals(-1, firstDifference(array))
            null
        }
    }

    @Test
    fun bytesDecodeToAJsStringWithoutAByteOrderMark() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'a'.code.toByte())
        val text = bytes.decodeToJsString()
        assertEquals(1, textLength(text))
        assertEquals('a'.code, charCodeAt(text, 0))
        assertEquals(0, textLength(ByteArray(0).decodeToJsString()))
    }

    @Test
    fun aCharacterSplitBetweenChunksDecodesWhole() {
        // The bytes of U+20AC around the end of the chunk of 256 KiB that Kotlin/Wasm goes through.
        val chunk = 256 * 1024
        val bytes = ByteArray(chunk + 2) { 'a'.code.toByte() }
        bytes[chunk - 1] = 0xE2.toByte()
        bytes[chunk] = 0x82.toByte()
        bytes[chunk + 1] = 0xAC.toByte()
        val text = bytes.decodeToJsString()
        assertEquals(chunk, textLength(text))
        assertEquals(0x20AC, charCodeAt(text, chunk - 1))
    }

    @Test
    fun aTruncatedCharacterDecodesToTheReplacementCharacter() {
        val text = byteArrayOf(0xE2.toByte()).decodeToJsString()
        assertEquals(1, textLength(text))
        assertEquals(0xFFFD, charCodeAt(text, 0))
    }

    @Test
    fun arraysReachJsAsTheyAre() {
        val floats = FloatArray(20_000) { it * 0.75f - 300f }
        val ints = IntArray(20_001) { it * 65537 - 40_000_000 }
        // Read as unsigned, past 32767 too.
        val shorts = ShortArray(40_002) { (it * 97).toShort() }
        withByteReaders(floats, ints, shorts) { f, i, s ->
            // Little-endian bytes, as typed arrays hold them.
            assertEquals(-1, firstMismatch(f, 4 * floats.size) { byteOf(floats[it / 4].toRawBits(), it % 4) })
            assertEquals(-1, firstMismatch(i, 4 * ints.size) { byteOf(ints[it / 4], it % 4) })
            assertEquals(-1, firstMismatch(s, 2 * shorts.size) { byteOf(shorts[it / 2].toInt(), it % 2) })
        }
        withByteReaders(FloatArray(0), IntArray(0), ShortArray(0)) { f, i, s ->
            assertEquals(listOf(0, 0, 0), listOf(f(0), i(0), s(0)).map { lengthOf(it) })
        }
    }

    @Test
    fun pixelsAreReadFromImageDataAsTheyAre() {
        // 6 x 4 pixels read at offset 2 of rows of 7, and pixels in more rows than a chunk holds
        // (218 rows of 300 pixels in 256 KiB).
        assertPixelsAreRead(width = 6, height = 4, offset = 2, stride = 7)
        assertPixelsAreRead(width = 300, height = 250, offset = 1, stride = 301)
    }

    private fun assertPixelsAreRead(width: Int, height: Int, offset: Int, stride: Int) {
        val read = IntArray(offset + stride * height)
        readImageDataArgb(imageDataOfPixelIndices(width, height), read, offset, stride)
        for (y in 0 until height) {
            for (x in 0 until width) {
                assertEquals(argbOfPixelIndex(y * width + x), read[offset + y * stride + x], "($x, $y)")
            }
        }
    }
}

private fun byteOf(value: Int, index: Int): Int = (value ushr (8 * index)) and 0xFF

/** The ARGB of the pixel of the given index in [imageDataOfPixelIndices], whose bytes all vary. */
private fun argbOfPixelIndex(index: Int): Int = index * 0x01030507 + 0x11223344

/** `ImageData` of the given size whose pixels are [argbOfPixelIndex] of their index. */
// language=js
private fun imageDataOfPixelIndices(width: Int, height: Int): ImageData = js(
    """{
        const image = new ImageData(width, height), d = image.data;
        for (let p = 0, i = 0; p < width * height; p++, i += 4) {
            const c = (Math.imul(p, 0x01030507) + 0x11223344) | 0;
            d[i] = (c >> 16) & 255; d[i + 1] = (c >> 8) & 255; d[i + 2] = c & 255; d[i + 3] = (c >>> 24) & 255;
        }
        return image;
    }"""
)

/**
 * The offset of the first of the [byteLength] bytes that [read] hands in chunks which differs from
 * [expected], or -1.
 */
// language=js
private fun firstMismatch(read: (Int) -> JsAny, byteLength: Int, expected: (Int) -> Int): Int = js(
    """{
        let offset = 0;
        while (offset < byteLength) {
            const bytes = read(offset);
            if (bytes.length === 0) return offset;
            for (let i = 0; i < bytes.length; i++) {
                if (bytes[i] !== expected(offset + i)) return offset + i;
            }
            offset += bytes.length;
        }
        return offset === byteLength ? -1 : byteLength;
    }"""
)

// language=js
private fun lengthOf(array: JsAny): Int = js("array.length")

// language=js
private fun textLength(text: JsString): Int = js("text.length")

// language=js
private fun charCodeAt(text: JsString, index: Int): Int = js("text.charCodeAt(index)")

// language=js
private fun byteAt(array: JsAny, index: Int): Int = js("array[index]")

// language=js
private fun typeOf(blob: JsAny): String = js("blob.type")

// language=js
private fun sizeOf(blob: JsAny): Int = js("blob.size")

/** The bytes of [blob], as a `Uint8Array` of the buffer read. */
// language=js
private fun bytesOf(blob: JsAny): Promise<JsAny> = js("blob.arrayBuffer().then((buffer) => new Uint8Array(buffer))")
