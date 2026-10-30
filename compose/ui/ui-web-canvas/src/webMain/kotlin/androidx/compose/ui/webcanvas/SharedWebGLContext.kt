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

package androidx.compose.ui.webcanvas

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.webcanvas.graphics.FramebufferImageBitmap
import androidx.compose.ui.webcanvas.graphics.SharedWebGL
import kotlin.js.JsAny

/**
 * The WebGL 2 context of the page, which Compose draws with where the Canvas 2D API falls short
 * (e.g. perspective), and which WebGL content embedded in Compose renders with: there is a single
 * one, so that the programs and other GL objects created for it are created once.
 *
 * Compose's draws restore the GL state they change, but they use the drawing buffer of [canvas]:
 * WebGL content renders into framebuffers of its own, and Compose shows them through
 * [createFramebufferImage].
 */
@InternalComposeUiApi
public object SharedWebGLContext {
    /** The `WebGL2RenderingContext`, created on the first call, or `null` without WebGL 2. */
    public val context: JsAny? get() = SharedWebGL.context

    /** The canvas of [context] (not part of the page), or `null` without WebGL 2. */
    public val canvas: JsAny? get() = SharedWebGL.canvas

    /** Increments whenever the context is restored after a loss, which drops every GL object. */
    public val generation: Int get() = SharedWebGL.generation

    public fun isContextLost(): Boolean = SharedWebGL.isContextLost()

    /**
     * Tells Compose that code other than Compose's used the context, so that it doesn't assume
     * that the drawing buffer still holds what it put there.
     */
    public fun markUsed() {
        SharedWebGL.markUsed()
    }

    /**
     * Creates an image showing the bottom-left [width] x [height] pixels of [framebuffer] (a
     * `WebGLFramebuffer` of [context]), the right way up for content rendered with the usual
     * bottom-left origin. Call [invalidateFramebufferImage] after rendering a new frame into the
     * framebuffer, and [releaseFramebufferImage] before deleting it.
     */
    public fun createFramebufferImage(framebuffer: JsAny, width: Int, height: Int): ImageBitmap =
        FramebufferImageBitmap(framebuffer, width, height)

    /** Tells an image from [createFramebufferImage] that a new frame was rendered. */
    public fun invalidateFramebufferImage(image: ImageBitmap) {
        (image as? FramebufferImageBitmap)?.invalidate()
    }

    /** Detaches an image from [createFramebufferImage] from its framebuffer, which goes away. */
    public fun releaseFramebufferImage(image: ImageBitmap) {
        (image as? FramebufferImageBitmap)?.release()
    }
}
