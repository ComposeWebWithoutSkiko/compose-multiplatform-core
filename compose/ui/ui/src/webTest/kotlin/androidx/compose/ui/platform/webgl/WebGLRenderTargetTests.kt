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

@file:OptIn(ExperimentalComposeUiApi::class)

package androidx.compose.ui.platform.webgl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.OnCanvasTests
import androidx.compose.ui.WebApplicationScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.khronos.webgl.WebGLRenderingContext
import org.khronos.webgl.WebGLRenderingContext.Companion.BLEND
import org.khronos.webgl.WebGLRenderingContext.Companion.COLOR_ATTACHMENT0
import org.khronos.webgl.WebGLRenderingContext.Companion.COLOR_BUFFER_BIT
import org.khronos.webgl.WebGLRenderingContext.Companion.FRAMEBUFFER
import org.khronos.webgl.WebGLRenderingContext.Companion.FRAMEBUFFER_ATTACHMENT_OBJECT_NAME
import org.khronos.webgl.WebGLRenderingContext.Companion.NO_ERROR
import org.w3c.dom.HTMLCanvasElement

/** Opaque red as `0xRRGGBBAA`, chosen because every channel is exact in RGBA8. */
private const val OPAQUE_RED = (255 shl 24) or 255
private const val OPAQUE_GREEN = (255 shl 16) or 255

private fun clearTo(target: WebGLRenderTarget, red: Float, green: Float, blue: Float): Boolean =
    target.render {
        target.webGLContext.viewport(0, 0, target.size.width, target.size.height)
        target.webGLContext.clearColor(red, green, blue, 1f)
        target.webGLContext.clear(COLOR_BUFFER_BIT)
    }

private fun clearToRed(target: WebGLRenderTarget): Boolean = clearTo(target, 1f, 0f, 0f)

private fun clearToGreen(target: WebGLRenderTarget): Boolean = clearTo(target, 0f, 1f, 0f)

class WebGLRenderTargetTests : OnCanvasTests {

    @Test
    fun dpSizeIsConvertedToPixelsUsingCurrentDensity() = runApplicationTest {
        var renderTarget: WebGLRenderTarget? = null

        createComposeWindow {
            CompositionLocalProvider(LocalDensity provides Density(3f)) {
                renderTarget = rememberWebGLRenderTarget(DpSize(32.dp, 16.dp))
            }
        }

        val target = assertCreated(renderTarget)
        awaitAnimationFrame()
        awaitIdle()

        assertTrue(clearToRed(target), "render() did not run")
        assertEquals(IntSize(96, 48), target.size, "DpSize was converted incorrectly")
    }

    /**
     * The simplest possible renderer: clear the target to a known color. Verifies that a frame
     * reaches the texture, that Compose draws it, and that WebGL reports no error along the way.
     */
    @Test
    fun clearingToAKnownColorProducesAFrameWithoutGLErrors() = runApplicationTest {
        val frames = 3
        var renderTarget: WebGLRenderTarget? = null
        var renderedFrames = 0
        var drawnFrames = 0

        createComposeWindow {
            val target = rememberWebGLRenderTarget(IntSize(64, 64))
            renderTarget = target
            if (target != null) {
                LaunchedEffect(target) {
                    repeat(frames) {
                        withFrameNanos {
                            if (clearToRed(target)) renderedFrames++
                        }
                    }
                }
                // Drawn by hand rather than with Image, so that the counter sits in the very draw
                // scope that a new frame has to repeat.
                Canvas(Modifier.size(64.dp)) {
                    drawnFrames++
                    val bounds = this.size
                    with(target.painter) { draw(bounds) }
                }
            }
        }

        val target = assertCreated(renderTarget)
        repeat(frames + 2) { awaitAnimationFrame() }
        awaitIdle()

        assertTrue(renderedFrames > 0, "render() never ran the block")
        assertTrue(drawnFrames > 0, "the painter was never drawn")

        assertEquals(IntSize(64, 64), target.size, "unexpected allocated size")
        assertNotNull(target.image, "there is no image of the rendered frame for Compose to draw")
        assertTrue(target.generation > 0, "generation was never bumped")

        // Read the frame back from the target's own framebuffer, which render() keeps bound.
        var centerPixel = 0
        var glError = -1
        val rendered = target.render {
            with (target) {
                webGLContext.viewport(0, 0, size.width, size.height)
                webGLContext.clearColor(1f, 0f, 0f, 1f)
                webGLContext.clear(COLOR_BUFFER_BIT)
                centerPixel = readPixelRgba8(webGLContext, size.width / 2, size.height / 2)
                glError = webGLContext.getError()

                val attachedTexture = webGLContext.getFramebufferAttachmentParameter(
                    FRAMEBUFFER,
                    COLOR_ATTACHMENT0,
                    FRAMEBUFFER_ATTACHMENT_OBJECT_NAME,
                )
                assertSame(target.webGlTexture, attachedTexture, "unexpected framebuffer texture")
            }
        }

        assertTrue(rendered, "render() did not run after Compose's first frame")
        assertEquals(NO_ERROR, glError, "the WebGL context reported an error")
        assertEquals(
            OPAQUE_RED.toHexString(),
            centerPixel.toHexString(),
            "the cleared color did not reach the texture"
        )
    }

    /** A new size given to [rememberWebGLRenderTarget] must reallocate on the next render. */
    @Test
    fun changingTheSizeReallocatesTheTexture() = runApplicationTest {
        val requestedSize = mutableStateOf(IntSize(32, 32))
        var renderTarget: WebGLRenderTarget? = null

        createComposeWindow {
            val size by requestedSize
            val target = rememberWebGLRenderTarget(size)
            renderTarget = target
            if (target != null) {
                Image(
                    painter = target.painter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        val target = assertCreated(renderTarget)
        awaitAnimationFrame()
        awaitIdle()

        assertTrue(clearToRed(target), "the first render() did not run")
        assertEquals(IntSize(32, 32), target.size, "unexpected initial size")
        val generationBefore = target.generation
        val framebufferBefore = target.framebuffer
        val textureBefore = target.webGlTexture
        var invalidationCount = 0
        target.onTextureWillBeInvalidated = {
            invalidationCount++
            assertSame(textureBefore, target.webGlTexture, "the old texture was replaced too early")
            assertTrue(
                target.webGLContext.isTexture(textureBefore),
                "the old texture was deleted before the invalidation callback",
            )
        }

        requestedSize.value = IntSize(48, 24)
        awaitAnimationFrame()
        awaitIdle()

        assertTrue(clearToRed(target), "render() did not run after the size change")
        assertEquals(1, invalidationCount, "the texture invalidation listener was not invoked")
        assertEquals(IntSize(48, 24), target.size, "the new size was not applied")
        assertTrue(
            target.generation > generationBefore,
            "generation did not change although the texture was reallocated"
        )
        // Only the attachments are reallocated, so an engine may keep the handle from setup.
        assertSame(
            framebufferBefore,
            target.framebuffer,
            "the framebuffer itself was replaced by the size change"
        )
        assertNotSame(
            textureBefore,
            target.webGlTexture,
            "the texture deleted on the size change was reused",
        )
        assertEquals(NO_ERROR, target.webGLContext.getError(), "reallocation reported a GL error")

        val textureAfterResize = target.webGlTexture
        target.onTextureWillBeInvalidated = { error("expected invalidation failure") }
        requestedSize.value = IntSize(24, 48)
        awaitAnimationFrame()
        awaitIdle()
        assertFailsWith<IllegalStateException> { clearToRed(target) }
        assertNotSame(
            textureAfterResize,
            target.webGlTexture,
            "a failing callback kept the old texture as the current texture",
        )
        target.onTextureWillBeInvalidated = null
        assertTrue(clearToGreen(target), "render() did not recover after the callback failed")
        assertEquals(IntSize(24, 48), target.size, "the size was not applied after recovery")
    }

    /**
     * The whole pipeline: a frame rendered into the texture must end up in the pixels of the
     * Compose canvas, drawn where the composable is.
     *
     * Reading those pixels needs `preserveDrawingBuffer`, since WebGL discards the drawing buffer
     * once the browser has composited it.
     */
    @Test
    fun theRenderedFrameReachesTheComposeCanvasBeforeAndAfterResize() = runApplicationTest {
        var renderTarget: WebGLRenderTarget? = null
        val requestedSize = mutableStateOf(IntSize(64, 64))

        createComposeWindow {
            val size by requestedSize
            val target = rememberWebGLRenderTarget(size)
            renderTarget = target
            if (target != null) {
                LaunchedEffect(target) {
                    repeat(30) { withFrameNanos { clearToRed(target) } }
                }
                // Setting the density to 2 so the test works correctly on all displays
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    Image(
                        painter = target.painter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(100.dp),
                    )
                }
            }
        }

        val target = assertCreated(renderTarget)

        // Compose draws into its canvas with the Canvas 2D API, so its pixels can be read directly
        // (in device pixels, from the top).
        val inside = awaitCanvasPixel(x = 100, y = 100, expected = OPAQUE_RED)
        assertEquals(
            OPAQUE_RED.toHexString(),
            inside.toHexString(),
            "the texture did not reach the canvas inside the composable"
        )
        assertNotEquals(
            OPAQUE_RED.toHexString(),
            readCanvasPixelRgba8(getCanvas(), 600, 300).toHexString(),
            "the texture was drawn outside the composable"
        )

        requestedSize.value = IntSize(48, 24)
        awaitAnimationFrame()
        awaitIdle()
        assertTrue(clearToGreen(target), "render() did not run after the size change")
        val resized = awaitCanvasPixel(x = 100, y = 100, expected = OPAQUE_GREEN)
        assertEquals(
            OPAQUE_GREEN.toHexString(),
            resized.toHexString(),
            "the resized texture did not reach the canvas inside the composable",
        )
    }

    /**
     * All targets render with the one WebGL context of the page, and Compose shows each one's own
     * frame - also while they don't render, and something else keeps drawing with WebGL (here a
     * layer in perspective, redrawn on every frame).
     */
    @Test
    fun targetsShareTheContextAndShowTheirOwnFrames() = runApplicationTest {
        var first: WebGLRenderTarget? = null
        var second: WebGLRenderTarget? = null

        createComposeWindow {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                var rotation by remember { mutableStateOf(0f) }
                LaunchedEffect(Unit) {
                    while (true) withFrameNanos { rotation = (rotation + 3f) % 60f }
                }
                Row {
                    first = rememberWebGLRenderTarget(IntSize(50, 50))?.also {
                        Image(it.painter, contentDescription = null, modifier = Modifier.size(50.dp))
                    }
                    second = rememberWebGLRenderTarget(IntSize(50, 50))?.also {
                        Image(it.painter, contentDescription = null, modifier = Modifier.size(50.dp))
                    }
                    Box(
                        Modifier.size(50.dp)
                            .graphicsLayer {
                                rotationX = rotation
                                cameraDistance = 2f
                            }
                            .background(Color.Blue)
                    )
                }
            }
        }

        val a = assertCreated(first)
        val b = assertNotNull(second, "only one of the targets was created")
        assertSame(a.webGLContext, b.webGLContext, "the targets render with different contexts")
        // Compose never gets idle here: the layer in perspective changes on every frame.
        repeat(2) { awaitAnimationFrame() }

        assertTrue(clearToRed(a), "the first target did not render")
        assertTrue(clearToGreen(b), "the second target did not render")
        repeat(5) {
            awaitAnimationFrame()
            assertEquals(
                OPAQUE_RED.toHexString(),
                readCanvasPixelRgba8(getCanvas(), 25, 25).toHexString(),
                "the first target's frame is gone",
            )
            assertEquals(
                OPAQUE_GREEN.toHexString(),
                readCanvasPixelRgba8(getCanvas(), 75, 25).toHexString(),
                "the second target's frame is gone",
            )
        }
    }

    /** Compose's own WebGL drawing restores the state that WebGL code set up. */
    @Test
    fun stateSetUpByWebGLCodeStaysBetweenRenders() = runApplicationTest {
        var renderTarget: WebGLRenderTarget? = null

        createComposeWindow {
            val target = rememberWebGLRenderTarget(IntSize(32, 32))
            renderTarget = target
            if (target != null) {
                Image(target.painter, contentDescription = null, modifier = Modifier.size(32.dp))
                // Drawn with WebGL, through the context of the target.
                Box(
                    Modifier.size(32.dp)
                        .graphicsLayer {
                            rotationY = 30f
                            cameraDistance = 2f
                        }
                        .background(Color.Blue)
                )
            }
        }

        val target = assertCreated(renderTarget)
        awaitAnimationFrame()
        awaitIdle()

        val gl = target.webGLContext
        assertTrue(
            target.render {
                gl.enable(BLEND)
                gl.clearColor(0.25f, 0.5f, 0.75f, 1f)
                gl.viewport(1, 2, 3, 4)
            },
            "render() did not run",
        )
        repeat(3) { awaitAnimationFrame() }
        var state = ""
        target.render {
            state = "blend=${gl.isEnabled(BLEND)} clear=${describeClearColor(gl)} " +
                "viewport=${describeViewport(gl)}"
        }
        gl.disable(BLEND)
        assertEquals("blend=true clear=0.25,0.5,0.75,1 viewport=1,2,3,4", state)
    }

    /** Polls up to [frames] Compose frames for [expected] to show up at ([x], [y]). */
    private suspend fun WebApplicationScope.awaitCanvasPixel(
        x: Int,
        y: Int,
        expected: Int,
        frames: Int = 30,
    ): Int {
        var pixel = 0
        repeat(frames) {
            awaitAnimationFrame()
            pixel = readCanvasPixelRgba8(getCanvas(), x, y)
            if (pixel == expected) return pixel
        }
        return pixel
    }
}

private fun Int.toHexString(): String = toUInt().toString(16)

// language=js
private fun describeClearColor(gl: WebGLRenderingContext): String =
    js("Array.from(gl.getParameter(gl.COLOR_CLEAR_VALUE)).join(',')")

// language=js
private fun describeViewport(gl: WebGLRenderingContext): String =
    js("Array.from(gl.getParameter(gl.VIEWPORT)).join(',')")

/** Reads one pixel of the bound framebuffer as `0xRRGGBBAA`. */
// language=js
private fun readPixelRgba8(gl: WebGLRenderingContext, x: Int, y: Int): Int = js(
    """(function() {
        const pixel = new Uint8Array(4);
        gl.readPixels(x, y, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
        return (pixel[0] << 24) | (pixel[1] << 16) | (pixel[2] << 8) | pixel[3];
    })()"""
)

/** Reads one pixel of the Compose canvas, which is drawn with the Canvas 2D API, as `0xRRGGBBAA`. */
// language=js
private fun readCanvasPixelRgba8(canvas: HTMLCanvasElement, x: Int, y: Int): Int = js(
    """(function() {
        const d = canvas.getContext('2d').getImageData(x, y, 1, 1).data;
        return (d[0] << 24) | (d[1] << 16) | (d[2] << 8) | d[3];
    })()"""
)
