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

@file:OptIn(UnsafeWasmMemoryApi::class)

package androidx.compose.ui.webcanvas.dom

import androidx.compose.ui.InternalComposeUiApi
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.toJsString
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.WebAssembly
import kotlin.wasm.unsafe.wasmMemory
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// Kotlin/Wasm arrays live in the garbage-collected heap, which JS can't read: their data is copied
// into the linear memory of the module, which JS views. The linear memory never shrinks, so the data
// goes through a chunk of it of a bounded size, one part after the other: a transfer grows the
// memory by a chunk at most, however large the data. Allocating may grow the memory, which detaches
// the views made before, so the chunk is allocated before any view, and nothing is allocated while
// the data goes through it.

/**
 * The size of the chunk of linear memory the data goes through: small next to the memory of the
 * module, and large enough that the Blob of a large image is made of few chunk Blobs, each of which
 * the browser registers (measured on a 10 MB image: 3 ms more than a whole Blob, 13 ms with 64 KiB).
 */
private const val ChunkSize = 256 * 1024

/** Calls [block] with a chunk of linear memory of [size] bytes. */
private inline fun <R> withChunk(size: Int, block: (start: Pointer) -> R): R =
    withScopedMemoryAllocator { allocator -> block(allocator.allocate(size)) }

/** Calls [consume] with a `Uint8Array` of each chunk of the bytes, valid during the call. */
private inline fun ByteArray.forEachChunk(consume: (chunk: JsAny, offset: Int) -> Unit) {
    if (isEmpty()) return
    withChunk(ChunkSize) { start ->
        for (offset in indices step ChunkSize) {
            val length = minOf(ChunkSize, size - offset)
            for (i in 0 until length) (start + i).storeByte(this[offset + i])
            consume(uint8View(wasmMemory, start.address.toInt(), length), offset)
        }
    }
}

@InternalComposeUiApi
public actual fun ByteArray.toBlob(type: String): JsAny {
    // A Blob made of Blobs refers to their data: the bytes are copied once, by the chunk Blobs.
    val parts = newArray()
    forEachChunk { chunk, _ -> addBlobOf(parts, chunk) }
    return blobOf(parts, type)
}

@InternalComposeUiApi
public actual fun ByteArray.decodeToJsString(): JsString {
    // The decoder keeps the bytes of a character split between two chunks.
    val decoder = newTextDecoder()
    var text = "".toJsString()
    forEachChunk { chunk, _ -> text = concat(text, decodeChunk(decoder, chunk)) }
    return concat(text, finishDecoding(decoder))
}

internal actual fun ByteArray.toUint8Array(): JsAny {
    val array = newUint8Array(size)
    forEachChunk { chunk, offset -> setAt(array, chunk, offset) }
    return array
}

internal actual fun <R> withByteReaders(
    floats: FloatArray,
    ints: IntArray,
    shorts: ShortArray,
    block: (floats: (Int) -> JsAny, ints: (Int) -> JsAny, shorts: (Int) -> JsAny) -> R,
): R = withChunk(ChunkSize) { start ->
    // Chunks of whole elements: the chunk size is a multiple of their sizes.
    block(
        { byteOffset ->
            val first = byteOffset / 4
            val count = minOf(floats.size - first, ChunkSize / 4)
            for (i in 0 until count) (start + 4 * i).storeInt(floats[first + i].toRawBits())
            uint8View(wasmMemory, start.address.toInt(), 4 * count)
        },
        { byteOffset ->
            val first = byteOffset / 4
            val count = minOf(ints.size - first, ChunkSize / 4)
            for (i in 0 until count) (start + 4 * i).storeInt(ints[first + i])
            uint8View(wasmMemory, start.address.toInt(), 4 * count)
        },
        { byteOffset ->
            val first = byteOffset / 2
            val count = minOf(shorts.size - first, ChunkSize / 2)
            for (i in 0 until count) (start + 2 * i).storeShort(shorts[first + i])
            uint8View(wasmMemory, start.address.toInt(), 2 * count)
        },
    )
}

/** The number of rows of pixels of the given [width] that go through a chunk at once: 1 at least. */
private fun rowsPerChunk(width: Int): Int = maxOf(1, ChunkSize / (4 * width))

internal actual fun readImageDataArgb(
    imageData: ImageData,
    buffer: IntArray,
    bufferOffset: Int,
    stride: Int,
) {
    val width = imageData.width
    val height = imageData.height
    val rows = rowsPerChunk(width)
    withChunk(4 * width * rows) { start ->
        val argb = int32View(wasmMemory, start.address.toInt(), width * rows)
        for (top in 0 until height step rows) {
            val count = minOf(rows, height - top)
            writeArgbRows(imageData, argb, top, count)
            for (y in 0 until count) {
                val row = start + 4 * y * width
                val offset = bufferOffset + (top + y) * stride
                for (x in 0 until width) buffer[offset + x] = (row + 4 * x).loadInt()
            }
        }
    }
}

// language=js
private fun uint8View(memory: WebAssembly.Memory, offset: Int, length: Int): JsAny =
    js("new Uint8Array(memory.buffer, offset, length)")

// language=js
private fun int32View(memory: WebAssembly.Memory, offset: Int, length: Int): JsAny =
    js("new Int32Array(memory.buffer, offset, length)")

// language=js
private fun newArray(): JsAny = js("[]")

// language=js
private fun addBlobOf(parts: JsAny, bytes: JsAny): Unit = js("{ parts.push(new Blob([bytes])); }")

// language=js
private fun blobOf(parts: JsAny, type: String): JsAny = js("new Blob(parts, { type: type })")

// language=js
private fun newTextDecoder(): JsAny = js("new TextDecoder()")

// language=js
private fun decodeChunk(decoder: JsAny, bytes: JsAny): JsString =
    js("decoder.decode(bytes, { stream: true })")

// language=js
private fun finishDecoding(decoder: JsAny): JsString = js("decoder.decode()")

// language=js
private fun concat(first: JsString, second: JsString): JsString = js("first + second")

// language=js
private fun newUint8Array(length: Int): JsAny = js("new Uint8Array(length)")

// language=js
private fun setAt(array: JsAny, bytes: JsAny, offset: Int): Unit = js("{ array.set(bytes, offset); }")

/**
 * Writes the RGBA bytes of the [count] rows of [imageData] from [top] into [argb] as
 * non-premultiplied ARGB ints.
 */
// language=js
private fun writeArgbRows(imageData: ImageData, argb: JsAny, top: Int, count: Int): Unit = js(
    """{
        const d = imageData.data, n = count * imageData.width;
        for (let p = 0, i = 4 * top * imageData.width; p < n; p++, i += 4) {
            argb[p] = (d[i + 3] << 24) | (d[i] << 16) | (d[i + 1] << 8) | d[i + 2];
        }
    }"""
)
