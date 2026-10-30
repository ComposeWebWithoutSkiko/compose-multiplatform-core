/*
 * Copyright 2025 The Android Open Source Project
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

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package androidx.compose.mpp.demo

import androidx.compose.foundation.internal.readText
import androidx.compose.ui.platform.ClipEntry
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.WebAssembly
import kotlin.wasm.unsafe.wasmMemory
import kotlin.wasm.unsafe.withScopedMemoryAllocator
import kotlinx.browser.window
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asDeferred
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.fetch.Response

actual suspend fun ClipEntry?.getPlainText(): String? {
    return this?.readText()
}

actual fun createClipEntryWithPlainText(text: String): ClipEntry {
    return ClipEntry.withPlainText(text)
}

private suspend fun loadResAsync(url: String): Deferred<ArrayBuffer> {
    return window.fetch(url).await<Response>().arrayBuffer().asDeferred()
}

suspend fun loadRes(url: String): ArrayBuffer {
    return loadResAsync(url).await()
}

fun ArrayBuffer.toByteArray(): ByteArray {
    val source = Int8Array(this, 0, byteLength)
    return jsInt8ArrayToKotlinByteArray(source)
}

private fun wasmBuffer(memory: WebAssembly.Memory): ArrayBuffer = js("memory.buffer")

internal fun jsInt8ArrayToKotlinByteArray(x: Int8Array): ByteArray {
    val size = x.length
    val bytes = ByteArray(size)
    // The bytes go through a chunk of the linear memory one part after the other: the memory never
    // shrinks, so it must not grow to the size of the file.
    @OptIn(UnsafeWasmMemoryApi::class)
    withScopedMemoryAllocator { allocator ->
        val chunk = allocator.allocate(ChunkSize)
        val view = Int8Array(wasmBuffer(wasmMemory), chunk.address.toInt(), ChunkSize)
        for (offset in 0 until size step ChunkSize) {
            val length = minOf(ChunkSize, size - offset)
            view.set(x.subarray(offset, offset + length))
            for (i in 0 until length) bytes[offset + i] = (chunk + i).loadByte()
        }
    }
    return bytes
}

/** The size of the chunk of linear memory the bytes go through. */
private const val ChunkSize = 64 * 1024