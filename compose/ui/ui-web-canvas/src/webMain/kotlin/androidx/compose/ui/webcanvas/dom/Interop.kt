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

@file:OptIn(ExperimentalWasmJsInterop::class)

package androidx.compose.ui.webcanvas.dom

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

/** Copies the bytes into a new JS `Uint8Array`. */
internal expect fun ByteArray.toUint8Array(): JsAny

/** Copies the first [count] ints into a new JS `Int32Array`. */
internal expect fun IntArray.toInt32Array(count: Int): JsAny

/**
 * Reads the RGBA bytes of [imageData] in the given row range as non-premultiplied ARGB ints into
 * [buffer].
 */
internal expect fun readImageDataArgb(
    imageData: ImageData,
    buffer: IntArray,
    bufferOffset: Int,
    stride: Int,
)

/** Creates `ImageData` from ARGB ints. */
internal expect fun createImageDataFromArgb(
    pixels: IntArray,
    offset: Int,
    stride: Int,
    width: Int,
    height: Int,
): ImageData

// language=js
internal fun performanceNow(): Double = js("performance.now()")

// language=js
internal fun logWarning(message: String): Unit = js("console.warn(message)")
