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

@file:OptIn(InternalComposeUiApi::class, UnsafeWasmMemoryApi::class)

package androidx.compose.ui.webcanvas.dom

import androidx.compose.ui.InternalComposeUiApi
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.WebAssembly
import kotlin.wasm.unsafe.wasmMemory

/**
 * The linear memory that data handed to JS goes through: it never shrinks, so a transfer must not
 * grow it to the size of the data.
 */
class LinearMemoryTest {
    @Test
    fun largeTransfersDontGrowTheLinearMemory() {
        val before = byteLength(wasmMemory)
        fun assertBounded(transfer: String) {
            val growth = byteLength(wasmMemory) - before
            assertTrue(growth <= MaxGrowth, "$transfer grew the linear memory by $growth bytes")
        }

        val bytes = ByteArray(LargeSize)
        bytes.toUint8Array()
        assertBounded("Bytes to keep")
        bytes.toBlob("image/png")
        assertBounded("A Blob")
        val floats = FloatArray(LargeSize / 4)
        val ints = IntArray(LargeSize / 4)
        val shorts = ShortArray(LargeSize / 2)
        withByteReaders(floats, ints, shorts) { readFloats, readInts, readShorts ->
            readAll(readFloats, LargeSize)
            readAll(readInts, LargeSize)
            readAll(readShorts, LargeSize)
        }
        assertBounded("Arrays")
        val pixels = IntArray(ImageSide * ImageSide)
        readImageDataArgb(newImageData(ImageSide, ImageSide), pixels, 0, ImageSide)
        assertBounded("Reading pixels")
    }
}

// Larger than anything else handed to JS in the tests, so that it would grow the memory.
private const val LargeSize = 16 * 1024 * 1024
private const val ImageSide = 2048

// Room for the chunk the data goes through (256 KiB), with a margin, far below the data sizes.
private const val MaxGrowth = 1024 * 1024

// language=js
private fun byteLength(memory: WebAssembly.Memory): Int = js("memory.buffer.byteLength")

// language=js
private fun newImageData(width: Int, height: Int): ImageData = js("new ImageData(width, height)")

/** Reads the [byteLength] bytes that [read] hands in chunks, as a WebGL upload does. */
// language=js
private fun readAll(read: (Int) -> JsAny, byteLength: Int): Unit =
    js("{ for (let offset = 0; offset < byteLength; offset += read(offset).length); }")
