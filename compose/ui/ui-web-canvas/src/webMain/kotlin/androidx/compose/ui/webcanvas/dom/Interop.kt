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
import kotlin.js.js

// Kotlin/JS arrays are JS typed arrays, which JS reads in place. Kotlin/Wasm arrays live in the
// garbage-collected heap, which JS can't read: their data goes through the linear memory of the
// module, in chunks, since the linear memory never shrinks (see Interop.wasm.kt).

/** A JS `Blob` of the bytes, of the MIME [type] (e.g. to decode an image from). */
internal expect fun ByteArray.toBlob(type: String): JsAny

/**
 * A JS `Uint8Array` of the bytes that may be read later (e.g. by a `FontFace`): on Kotlin/JS a view
 * of the array, which must then not change, on Kotlin/Wasm a copy.
 */
internal expect fun ByteArray.toUint8Array(): JsAny

/**
 * Calls [block] with a reader of the bytes of each of [floats], [ints] and [shorts], which JS calls
 * to take them in chunks (e.g. into WebGL buffers) until [block] returns: `read(byteOffset)` returns
 * a `Uint8Array` of the next bytes from `byteOffset` (some while any remain), valid until the next
 * call of a reader.
 */
internal expect fun <R> withByteReaders(
    floats: FloatArray,
    ints: IntArray,
    shorts: ShortArray,
    block: (floats: (Int) -> JsAny, ints: (Int) -> JsAny, shorts: (Int) -> JsAny) -> R,
): R

/**
 * Reads the RGBA bytes of [imageData] as non-premultiplied ARGB ints into [buffer], its rows from
 * [bufferOffset], [stride] ints apart.
 */
internal expect fun readImageDataArgb(
    imageData: ImageData,
    buffer: IntArray,
    bufferOffset: Int,
    stride: Int,
)

// language=js
internal fun performanceNow(): Double = js("performance.now()")

// language=js
internal fun logWarning(message: String): Unit = js("console.warn(message)")
