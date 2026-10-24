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

@file:OptIn(ExperimentalWasmJsInterop::class, InternalComposeUiApi::class)

package androidx.compose.ui.platform.webgl

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.webcanvas.SharedWebGLContext
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.unsafeCast
import org.khronos.webgl.WebGLRenderingContext
import org.w3c.dom.HTMLCanvasElement

/**
 * The WebGL2 context that WebGL content embedded in Compose renders with: the context of the page
 * that Compose itself draws with where the Canvas 2D API falls short ([SharedWebGLContext]). There
 * is a single one for all windows and render targets.
 */
internal class WebGLInteropContext private constructor(
    val canvas: HTMLCanvasElement,
    val gl: WebGLRenderingContext,
) {
    companion object {
        private var shared: WebGLInteropContext? = null

        /** Returns the context, or `null` if the browser doesn't support WebGL2. */
        fun sharedOrNull(): WebGLInteropContext? {
            shared?.let { return it }
            val gl = SharedWebGLContext.context ?: return null
            val canvas = SharedWebGLContext.canvas ?: return null
            return WebGLInteropContext(
                canvas.unsafeCast<HTMLCanvasElement>(),
                gl.unsafeCast<WebGLRenderingContext>(),
            ).also { shared = it }
        }
    }
}
