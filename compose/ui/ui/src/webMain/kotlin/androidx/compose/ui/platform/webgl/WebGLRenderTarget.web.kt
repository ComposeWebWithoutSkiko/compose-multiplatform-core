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

package androidx.compose.ui.platform.webgl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.webcanvas.SharedWebGLContext
import androidx.compose.ui.window.LocalComposeWindow
import org.khronos.webgl.WebGLFramebuffer
import org.khronos.webgl.WebGLRenderbuffer
import org.khronos.webgl.WebGLRenderingContext
import org.khronos.webgl.WebGLRenderingContext.Companion.CLAMP_TO_EDGE
import org.khronos.webgl.WebGLRenderingContext.Companion.COLOR_ATTACHMENT0
import org.khronos.webgl.WebGLRenderingContext.Companion.FRAMEBUFFER
import org.khronos.webgl.WebGLRenderingContext.Companion.FRAMEBUFFER_COMPLETE
import org.khronos.webgl.WebGLRenderingContext.Companion.LINEAR
import org.khronos.webgl.WebGLRenderingContext.Companion.RENDERBUFFER
import org.khronos.webgl.WebGLRenderingContext.Companion.RGBA
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_2D
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_MAG_FILTER
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_MIN_FILTER
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_WRAP_S
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_WRAP_T
import org.khronos.webgl.WebGLRenderingContext.Companion.UNSIGNED_BYTE
import org.khronos.webgl.WebGLTexture
import org.w3c.dom.HTMLCanvasElement

// WebGL2 only; see https://registry.khronos.org/OpenGL/api/GL/glcorearb.h
// #define GL_DEPTH24_STENCIL8 0x88F0
// #define GL_DEPTH_STENCIL_ATTACHMENT 0x821A
private const val GL_DEPTH24_STENCIL8 = 0x88F0
private const val GL_DEPTH_STENCIL_ATTACHMENT = 0x821A

/**
 * An offscreen render target that lets WebGL content take part in Compose rendering.
 *
 * WebGL code renders into this target with [render]. Compose displays the resulting texture through
 * [painter]. The target owns the framebuffer, color texture, and depth/stencil buffer.
 *
 * WebGL content renders with the WebGL2 context of the page ([webGLContext], on [htmlCanvas], a
 * canvas that isn't part of the page), which Compose itself draws with where the Canvas 2D API
 * falls short (e.g. layers in perspective). There is a single one for all targets and windows, so
 * the programs and other GL objects created for it are created once. Compose's own draws restore
 * the GL state they change, so the state WebGL code sets up stays between [render] calls; but
 * Compose shows the frames through the drawing buffer of [htmlCanvas], so WebGL code must render
 * into [framebuffer] rather than into the default framebuffer.
 *
 * Obtain an instance with [rememberWebGLRenderTarget], which disposes it when it leaves the
 * composition.
 *
 * Usage example:
 * ```
 * val renderTarget = rememberWebGLRenderTarget(IntSize(1024, 640)) ?: return
 *
 * LaunchedEffect(renderTarget) {
 *     while (true) {
 *         withFrameNanos { frameTimeNanos ->
 *             renderTarget.render {
 *                val phase = (frameTimeNanos % 1_000_000_000L).toFloat() / 1_000_000_000f
 *                webGLContext.viewport(0, 0, size.width, size.height)
 *                webGLContext.clearColor(phase, 0.2f, 0.4f, 1f)
 *                webGLContext.clear(WebGLRenderingContext.COLOR_BUFFER_BIT)
 *             }
 *         }
 *     }
 * }
 *
 * Image(
 *     painter = renderTarget.painter,
 *     contentDescription = null,
 *     contentScale = ContentScale.Crop,
 *     modifier = Modifier.fillMaxSize(),
 * )
 * ```
 */
@ExperimentalComposeUiApi
@Stable
class WebGLRenderTarget internal constructor(
    private val interop: WebGLInteropContext,
    initialSize: IntSize,
) {
    /** The canvas of [webGLContext]. It isn't part of the page. */
    val htmlCanvas: HTMLCanvasElement get() = interop.canvas

    /** The WebGL2 context to render into this target with, shared by all targets. */
    val webGLContext: WebGLRenderingContext get() = interop.gl

    private var requestedSize: IntSize = initialSize.coerceAtLeastOnePixel()

    /**
     * The size, in pixels, of the [framebuffer] and its color texture.
     *
     * This is [IntSize.Zero] until the first successful [render]. A size supplied to
     * [rememberWebGLRenderTarget] is applied by the next [render].
     *
     * This is snapshot state, so changes invalidate layout that depends on it, such as a painter's
     * intrinsic size.
     */
    var size: IntSize by mutableStateOf(IntSize.Zero)
        private set

    /** Applied by the next [render]; see the `size` parameter of [rememberWebGLRenderTarget]. */
    internal fun requestNewSize(size: IntSize) {
        requestedSize = size.coerceAtLeastOnePixel()
    }

    /** The generation of the shared context that the GL objects below belong to. */
    private var contextGeneration = SharedWebGLContext.generation

    private var currentFramebuffer: WebGLFramebuffer? = null

    /**
     * The framebuffer used by [render].
     *
     * This framebuffer is created once and remains stable for the target's lifetime, unless the
     * context is lost and restored, which drops every GL object. Its color and depth/stencil
     * attachments are configured and resized as needed. It is complete only after the first
     * successful [render].
     *
     * Callers may temporarily rebind it inside [render], but must restore the binding before
     * returning. Callers must not delete the framebuffer or its attachments.
     */
    val framebuffer: WebGLFramebuffer
        get() = currentFramebuffer ?: (
            webGLContext.createFramebuffer() ?: error("gl.createFramebuffer() returned null")
        ).also { currentFramebuffer = it }

    private var currentTexture: WebGLTexture? = null

    /**
     * The WebGL texture backing this render target.
     *
     * The current texture is allocated lazily and replaced when the target changes size. Callers
     * may register or attach it to another framebuffer, but must not delete it, reallocate its
     * storage, or change its texture parameters. Callers must stop using it when
     * [onTextureWillBeInvalidated] is invoked. Access this property after the callback returns to
     * obtain the replacement.
     */
    val webGlTexture: WebGLTexture
        get() {
            val current = currentTexture
            if (current != null) return current

            val created = webGLContext.createTexture() ?: error("gl.createTexture() returned null")
            currentTexture = created
            return created
        }

    /** The depth/stencil attachment of [framebuffer]; like it, created once and only resized. */
    private var currentDepthStencil: WebGLRenderbuffer? = null

    private val depthStencil: WebGLRenderbuffer
        get() = currentDepthStencil ?: (
            webGLContext.createRenderbuffer() ?: error("gl.createRenderbuffer() returned null")
        ).also { currentDepthStencil = it }

    /** The size the attachments are allocated with. */
    private var attachmentsSize: IntSize? = null

    /**
     * Increments whenever the target is first configured or its size changes.
     * Use this to refresh external render-target metadata derived from [size] or [webGlTexture].
     */
    var generation: Int = 0
        private set

    /**
     * A painter that draws the most recently rendered frame.
     *
     * Its [Painter.intrinsicSize] is [size] after the first successful [render], and unspecified
     * before then. Drawing the painter issues no WebGL commands of the caller's; Compose copies the
     * frame from [framebuffer] when it draws it.
     *
     * The same painter instance is returned on every access.
     * Examples:
     * ```
     * Image(renderTarget.painter, contentDescription = null, contentScale = ContentScale.Crop)
     * Box(Modifier.paint(renderTarget.painter, contentScale = ContentScale.Fit))
     * Canvas(Modifier.fillMaxSize()) { with(renderTarget.painter) { draw(size) } }
     * ```
     */
    val painter: Painter by lazy { WebGLRenderTargetPainter(this) }

    /**
     * Called before the current texture-backed render resource becomes unavailable.
     * It happens when the texture is about to be replaced for a new size, the
     * [WebGLRenderTarget] is being disposed, or the context was lost and restored.
     */
    var onTextureWillBeInvalidated: (() -> Unit)? = null

    private val _invalidation = mutableLongStateOf(0L)

    /** Makes the calling draw operation repeat whenever a new frame is rendered. */
    internal fun observeInvalidation() {
        _invalidation.value
    }

    /** The image Compose draws: the frames of [framebuffer], or `null` before the first frame. */
    internal var image: ImageBitmap? = null
        private set

    private var isDisposed = false
    private var isRendering = false

    /**
     * Renders one frame into this target and invalidates everything that draws its [painter], so
     * it all shows the new frame.
     *
     * Allocates or resizes GPU resources as needed, binds [framebuffer], invokes [block], then
     * binds the default framebuffer again.
     *
     * Prefer calling this from a [withFrameNanos] callback. Do not call it from a draw or layout
     * scope, such as a `Canvas` or `Modifier.drawBehind`.
     * ```
     * Canvas(Modifier.fillMaxSize()) {
     *     // Wrong: render() must not run while Compose is drawing.
     *     renderTarget.render {
     *         // webGL commands
     *     }
     *     with(renderTarget.painter) { draw(size) }
     * }
     * ```
     *
     * @return `false`, skipping [block], if the target was disposed or the context is lost.
     */
    @OptIn(InternalComposeUiApi::class)
    fun render(block: () -> Unit): Boolean {
        if (isDisposed) return false
        check(!isRendering) {
            "render() is already running: it must not be called from within another render() call, " +
                "nor from a draw or layout scope"
        }
        if (SharedWebGLContext.isContextLost()) return false
        if (contextGeneration != SharedWebGLContext.generation) {
            dropObjectsOfLostContext()
        }
        val size = requestedSize
        prepareAttachments(size)
        isRendering = true
        webGLContext.bindFramebuffer(FRAMEBUFFER, framebuffer)
        try {
            block()
        } finally {
            isRendering = false
            webGLContext.bindFramebuffer(FRAMEBUFFER, null)
            // The block may have drawn into the drawing buffer Compose shows frames through.
            SharedWebGLContext.markUsed()
        }
        updateImage(size)
        _invalidation.value++
        return true
    }

    /**
     * Tells Compose that code touched [webGLContext] outside of [render], typically while setting
     * up or tearing down a third-party engine, and binds the default framebuffer.
     *
     * Compose's own draws restore the GL state they change, so this isn't needed for the GL state;
     * but such code may have drawn into the drawing buffer of [htmlCanvas], which Compose shows
     * the frames through. [render] does this for its own block already.
     */
    @OptIn(InternalComposeUiApi::class)
    fun markGLStateStale() {
        webGLContext.bindFramebuffer(FRAMEBUFFER, null)
        SharedWebGLContext.markUsed()
    }

    /** Points the image Compose draws to the frame just rendered. */
    @OptIn(InternalComposeUiApi::class)
    private fun updateImage(size: IntSize) {
        val current = image
        if (current != null && current.width == size.width && current.height == size.height) {
            SharedWebGLContext.invalidateFramebufferImage(current)
            return
        }
        current?.let { SharedWebGLContext.releaseFramebufferImage(it) }
        image = SharedWebGLContext.createFramebufferImage(framebuffer, size.width, size.height)
    }

    /**
     * Forgets the GL objects of the target after the context was lost and restored, which deleted
     * them: the next [render] creates new ones.
     */
    @OptIn(InternalComposeUiApi::class)
    private fun dropObjectsOfLostContext() {
        contextGeneration = SharedWebGLContext.generation
        try {
            if (currentTexture != null) onTextureWillBeInvalidated?.invoke()
        } finally {
            currentTexture = null
            currentFramebuffer = null
            currentDepthStencil = null
            attachmentsSize = null
            image?.let { SharedWebGLContext.releaseFramebufferImage(it) }
            image = null
        }
    }

    /** Allocates the attachments of [framebuffer] for [size], unless they already have that size. */
    private fun prepareAttachments(size: IntSize) {
        if (attachmentsSize == size) return

        if (attachmentsSize != null) {
            releaseTexture()
        }

        val newTexture = webGlTexture
        webGLContext.configureWebGLTexture(newTexture, size)

        webGLContext.bindRenderbuffer(RENDERBUFFER, depthStencil)
        webGLContext.renderbufferStorage(RENDERBUFFER, GL_DEPTH24_STENCIL8, size.width, size.height)
        webGLContext.bindRenderbuffer(RENDERBUFFER, null)

        webGLContext.bindFramebuffer(FRAMEBUFFER, framebuffer)
        webGLContext.framebufferTexture2D(
            FRAMEBUFFER,
            COLOR_ATTACHMENT0,
            TEXTURE_2D,
            newTexture,
            0,
        )
        webGLContext.framebufferRenderbuffer(
            FRAMEBUFFER,
            GL_DEPTH_STENCIL_ATTACHMENT,
            RENDERBUFFER,
            depthStencil,
        )
        val status = webGLContext.checkFramebufferStatus(FRAMEBUFFER)
        webGLContext.bindFramebuffer(FRAMEBUFFER, null)
        check(status == FRAMEBUFFER_COMPLETE) {
            "the texture is not a complete framebuffer attachment (status $status)"
        }

        attachmentsSize = size
        this.size = size
        generation++
    }

    private fun releaseTexture() {
        val current = currentTexture ?: return
        try {
            onTextureWillBeInvalidated?.invoke()
        } finally {
            currentTexture = null
            attachmentsSize = null
            webGLContext.deleteTexture(current)
        }
    }

    /**
     * Disposes the target's GPU resources.
     *
     * Called automatically by [rememberWebGLRenderTarget] when the target leaves the composition.
     * Calling this more than once has no effect.
     */
    @OptIn(InternalComposeUiApi::class)
    internal fun dispose() {
        if (isDisposed) return
        isDisposed = true
        if (contextGeneration != SharedWebGLContext.generation) {
            // The objects are gone with the context they belonged to.
            dropObjectsOfLostContext()
        }
        try {
            releaseTexture()
        } finally {
            size = IntSize.Zero
            image?.let { SharedWebGLContext.releaseFramebufferImage(it) }
            image = null
            currentFramebuffer?.let { webGLContext.deleteFramebuffer(it) }
            currentDepthStencil?.let { webGLContext.deleteRenderbuffer(it) }
            currentFramebuffer = null
            currentDepthStencil = null
            webGLContext.bindFramebuffer(FRAMEBUFFER, null)
        }
    }
}

private fun WebGLRenderingContext.configureWebGLTexture(
    texture: WebGLTexture,
    size: IntSize
) {
    val gl = this
    gl.bindTexture(TEXTURE_2D, texture)
    // Configure the texture
    gl.texImage2D(TEXTURE_2D, 0, RGBA, size.width, size.height, 0, RGBA, UNSIGNED_BYTE, null)
    // LINEAR for smoother scaling:
    gl.texParameteri(TEXTURE_2D, TEXTURE_MIN_FILTER, LINEAR)
    gl.texParameteri(TEXTURE_2D, TEXTURE_MAG_FILTER, LINEAR)
    // Prevents Edge Artifacts
    gl.texParameteri(TEXTURE_2D, TEXTURE_WRAP_S, CLAMP_TO_EDGE)
    gl.texParameteri(TEXTURE_2D, TEXTURE_WRAP_T, CLAMP_TO_EDGE)
    gl.bindTexture(TEXTURE_2D, null)
}

/**
 * Remembers a [WebGLRenderTarget] of [size] pixels, disposing it when it leaves the composition.
 *
 * This is the only way to size the target: a changed [size] reallocates its GPU resources on the
 * next [WebGLRenderTarget.render], so avoid changing it per frame.
 *
 * @return The target, or `null` if the browser does not support WebGL2.
 */
@ExperimentalComposeUiApi
@Composable
fun rememberWebGLRenderTarget(
    size: IntSize
): WebGLRenderTarget? {
    val window = LocalComposeWindow.current ?: return null
    val renderTarget = remember(window) {
        window.webGLInterop?.let { WebGLRenderTarget(it, size) }
    } ?: return null
    SideEffect(size) { renderTarget.requestNewSize(size) }
    DisposableEffect(renderTarget) { onDispose { renderTarget.dispose() } }
    return renderTarget
}

/**
 * Remembers a [WebGLRenderTarget] of [size] in density-independent pixels, disposing it when it
 * leaves the composition.
 *
 * The size is converted to physical pixels using [LocalDensity]. A changed [size] reallocates the
 * target's GPU resources on the next [WebGLRenderTarget.render].
 *
 * @return The target, or `null` if the browser does not support WebGL2.
 */
@ExperimentalComposeUiApi
@Composable
fun rememberWebGLRenderTarget(
    size: DpSize
): WebGLRenderTarget? {
    val density = LocalDensity.current
    return rememberWebGLRenderTarget(with(density) { size.toSize().toIntSize() })
}

private fun IntSize.coerceAtLeastOnePixel(): IntSize =
    if (width >= 1 && height >= 1) this
    else IntSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
