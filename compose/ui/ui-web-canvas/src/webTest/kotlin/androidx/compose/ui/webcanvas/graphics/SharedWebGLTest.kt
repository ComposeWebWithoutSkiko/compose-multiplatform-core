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

package androidx.compose.ui.webcanvas.graphics

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.webcanvas.SharedWebGLContext
import androidx.compose.ui.webcanvas.WebCanvasGraphicsContext
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/** The WebGL context shared by Compose's drawing and embedded WebGL content. */
class SharedWebGLTest {
    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    /** The shared context: every browser the tests run in supports WebGL 2. */
    private fun sharedContext(): JsAny = assertNotNull(SharedWebGLContext.context, "WebGL 2 isn't available")

    private fun image(width: Int = 20, height: Int = 20, draw: Canvas.() -> Unit): ImageBitmap {
        val image = ImageBitmap(width, height)
        Canvas(image).draw()
        return image
    }

    /** Draws a layer in perspective and a mesh with vertex colors, which both use WebGL. */
    private fun drawWithWebGL() {
        val context = WebCanvasGraphicsContext()
        val layer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(100, 100)) {
            drawRect(Color.Red, size = Size(100f, 100f))
        }
        layer.rotationX = 45f
        layer.cameraDistance = 2f
        val positions = listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(0f, 100f))
        val vertices = Vertices(
            VertexMode.Triangles,
            positions = positions,
            textureCoordinates = positions,
            colors = listOf(Color.Red, Color.Green, Color.Blue),
            indices = emptyList(),
        )
        image(width = 100, height = 100) {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, this, Size(100f, 100f)) {
                drawLayer(layer)
            }
            drawVertices(vertices, BlendMode.SrcOver, Paint())
        }
        context.releaseGraphicsLayer(layer)
    }

    @Test
    fun composeDrawsLeaveTheGLStateAsTheyFoundIt() {
        val gl = sharedContext()
        val objects = setUpState(gl)
        try {
            val before = describeState(gl, objects)
            drawWithWebGL()
            assertEquals(before, describeState(gl, objects))
        } finally {
            tearDownState(gl, objects)
        }
    }

    @Test
    fun framebufferImageShowsTheFrameTheRightWayUp() {
        val gl = sharedContext()
        // Green at the bottom (the GL origin), red at the top.
        val framebuffer = createFramebuffer(gl, 20, 20, red = 0.0, green = 1.0, blue = 0.0)
        clearTop(gl, framebuffer, 20, 20, red = 1.0, green = 0.0, blue = 0.0, fraction = 0.5)
        val frames = SharedWebGLContext.createFramebufferImage(framebuffer, 20, 20)
        try {
            val image = image { drawImage(frames, Offset.Zero, Paint()) }
            assertColor(Color.Red, image.toPixelMap()[10, 2])
            assertColor(Color.Green, image.toPixelMap()[10, 17])
        } finally {
            SharedWebGLContext.releaseFramebufferImage(frames)
            deleteFramebuffer(gl, framebuffer)
        }
    }

    @Test
    fun framebufferImagesKeepTheirFramesWhenTheDrawingBufferIsReused() {
        val gl = sharedContext()
        val red = createFramebuffer(gl, 20, 20, red = 1.0, green = 0.0, blue = 0.0)
        val blue = createFramebuffer(gl, 30, 10, red = 0.0, green = 0.0, blue = 1.0)
        val redFrames = SharedWebGLContext.createFramebufferImage(red, 20, 20)
        val blueFrames = SharedWebGLContext.createFramebufferImage(blue, 30, 10)
        try {
            // Several frames of Compose, each drawing both images and something with WebGL, all
            // of which take turns in the drawing buffer.
            repeat(3) {
                val image = image(width = 60, height = 20) {
                    drawImage(redFrames, Offset.Zero, Paint())
                    drawImage(blueFrames, Offset(25f, 0f), Paint())
                }
                drawWithWebGL()
                val pixels = image.toPixelMap()
                assertColor(Color.Red, pixels[10, 10])
                assertColor(Color.Blue, pixels[40, 5])
                assertColor(Color.Transparent, pixels[40, 15])
            }
            // A new frame of one of them replaces the old one.
            clearTop(gl, red, 20, 20, red = 0.0, green = 1.0, blue = 0.0, fraction = 1.0)
            SharedWebGLContext.invalidateFramebufferImage(redFrames)
            val image = image { drawImage(redFrames, Offset.Zero, Paint()) }
            assertColor(Color.Green, image.toPixelMap()[10, 10])
        } finally {
            SharedWebGLContext.releaseFramebufferImage(redFrames)
            SharedWebGLContext.releaseFramebufferImage(blueFrames)
            deleteFramebuffer(gl, red)
            deleteFramebuffer(gl, blue)
        }
    }

    @Test
    fun aFilterKeepsTheFramesOfItsImagesApart() {
        val gl = sharedContext()
        val red = createFramebuffer(gl, 20, 20, red = 1.0, green = 0.0, blue = 0.0)
        val blue = createFramebuffer(gl, 20, 20, red = 0.0, green = 0.0, blue = 1.0)
        val redFrames = SharedWebGLContext.createFramebufferImage(red, 20, 20)
        val blueFrames = SharedWebGLContext.createFramebufferImage(blue, 20, 20)
        val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix()) }
        fun drawBoth() = image(width = 40, height = 20) {
            drawImage(redFrames, Offset.Zero, paint)
            drawImage(blueFrames, Offset(20f, 0f), paint)
        }
        try {
            val pixels = drawBoth().toPixelMap()
            assertColor(Color.Red, pixels[10, 10])
            assertColor(Color.Blue, pixels[30, 10])
            assertEquals(emptyList(), contextCallsDuring(listOf("blitFramebuffer")) { drawBoth() }.names)
        } finally {
            SharedWebGLContext.releaseFramebufferImage(redFrames)
            SharedWebGLContext.releaseFramebufferImage(blueFrames)
            deleteFramebuffer(gl, red)
            deleteFramebuffer(gl, blue)
        }
    }

    @Test
    fun framebufferImageRepeatsAsAPattern() {
        val gl = sharedContext()
        // Something larger than the frame in the drawing buffer first.
        drawWithWebGL()
        val framebuffer = createFramebuffer(gl, 10, 10, red = 0.0, green = 0.0, blue = 1.0)
        val frames = SharedWebGLContext.createFramebufferImage(framebuffer, 10, 10)
        try {
            val image = image(width = 30, height = 10) {
                drawRect(
                    Rect(0f, 0f, 30f, 10f),
                    Paint().apply { shader = ImageShader(frames, TileMode.Repeated, TileMode.Repeated) },
                )
            }
            val pixels = image.toPixelMap()
            for (x in listOf(5, 15, 25)) assertColor(Color.Blue, pixels[x, 5])
        } finally {
            SharedWebGLContext.releaseFramebufferImage(frames)
            deleteFramebuffer(gl, framebuffer)
        }
    }

    @Test
    fun releasedFramebufferImageDrawsNothing() {
        val gl = sharedContext()
        val framebuffer = createFramebuffer(gl, 10, 10, red = 1.0, green = 0.0, blue = 0.0)
        val frames = SharedWebGLContext.createFramebufferImage(framebuffer, 10, 10)
        SharedWebGLContext.releaseFramebufferImage(frames)
        deleteFramebuffer(gl, framebuffer)
        val image = image { drawImage(frames, Offset.Zero, Paint()) }
        assertColor(Color.Transparent, image.toPixelMap()[5, 5])
    }

    @Test
    fun theShadersOfAReleasedFramebufferImageDrawNothing() {
        val gl = sharedContext()
        val framebuffer = createFramebuffer(gl, 10, 10, red = 1.0, green = 0.0, blue = 0.0)
        val frames = SharedWebGLContext.createFramebufferImage(framebuffer, 10, 10)
        // A pattern of the image, and an image of the shader (clamped past the image).
        val shaders = listOf(
            ImageShader(frames, TileMode.Repeated, TileMode.Repeated),
            ImageShader(frames, TileMode.Clamp, TileMode.Clamp),
        )
        fun drawnWith(shader: Shader) = image { drawRect(Rect(0f, 0f, 20f, 20f), Paint().apply { this.shader = shader }) }
        val before = shaders.map { drawnWith(it) }
        SharedWebGLContext.releaseFramebufferImage(frames)
        deleteFramebuffer(gl, framebuffer)
        for (image in before) assertColor(Color.Red, image.toPixelMap()[5, 5])
        for (shader in shaders) assertColor(Color.Transparent, drawnWith(shader).toPixelMap()[5, 5])
    }

    @Test
    fun theContextIsCreatedOnce() {
        val gl = sharedContext()
        drawWithWebGL()
        assertSame(gl, SharedWebGLContext.context)
        assertNotNull(SharedWebGLContext.canvas)
    }
}

/** Sets up state that Compose's draws change, with objects of the test's own; returns them. */
// language=js
private fun setUpState(gl: JsAny): JsAny = js(
    """{
        const compile = (type, source) => {
            const shader = gl.createShader(type);
            gl.shaderSource(shader, source);
            gl.compileShader(shader);
            return shader;
        };
        const program = gl.createProgram();
        gl.attachShader(program, compile(gl.VERTEX_SHADER, '#version 300 es\nvoid main() { gl_Position = vec4(0.0); }'));
        gl.attachShader(program, compile(gl.FRAGMENT_SHADER, '#version 300 es\nprecision mediump float;\nout vec4 c;\nvoid main() { c = vec4(1.0); }'));
        gl.linkProgram(program);
        const objects = {
            program,
            vertexArray: gl.createVertexArray(),
            buffer: gl.createBuffer(),
            texture: gl.createTexture(),
            framebuffer: gl.createFramebuffer()
        };
        gl.useProgram(program);
        gl.bindVertexArray(objects.vertexArray);
        gl.bindBuffer(gl.ARRAY_BUFFER, objects.buffer);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, objects.texture);
        gl.activeTexture(gl.TEXTURE3);
        gl.bindFramebuffer(gl.FRAMEBUFFER, objects.framebuffer);
        gl.enable(gl.BLEND);
        gl.enable(gl.SCISSOR_TEST);
        gl.enable(gl.DEPTH_TEST);
        gl.viewport(1, 2, 3, 4);
        gl.scissor(5, 6, 7, 8);
        gl.clearColor(0.25, 0.5, 0.75, 1);
        gl.colorMask(true, false, true, false);
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
        gl.pixelStorei(gl.UNPACK_SKIP_ROWS, 3);
        return objects;
    }"""
)

/** Describes the state [setUpState] set up, as a string to compare. */
// language=js
private fun describeState(gl: JsAny, objects: JsAny): String = js(
    """{
        const activeTexture = gl.getParameter(gl.ACTIVE_TEXTURE);
        gl.activeTexture(gl.TEXTURE0);
        const texture = gl.getParameter(gl.TEXTURE_BINDING_2D);
        gl.activeTexture(activeTexture);
        return JSON.stringify({
            program: gl.getParameter(gl.CURRENT_PROGRAM) === objects.program,
            vertexArray: gl.getParameter(gl.VERTEX_ARRAY_BINDING) === objects.vertexArray,
            buffer: gl.getParameter(gl.ARRAY_BUFFER_BINDING) === objects.buffer,
            texture: texture === objects.texture,
            activeTexture: activeTexture - gl.TEXTURE0,
            drawFramebuffer: gl.getParameter(gl.DRAW_FRAMEBUFFER_BINDING) === objects.framebuffer,
            readFramebuffer: gl.getParameter(gl.READ_FRAMEBUFFER_BINDING) === objects.framebuffer,
            blend: gl.isEnabled(gl.BLEND),
            scissor: gl.isEnabled(gl.SCISSOR_TEST),
            depth: gl.isEnabled(gl.DEPTH_TEST),
            cull: gl.isEnabled(gl.CULL_FACE),
            viewport: Array.from(gl.getParameter(gl.VIEWPORT)),
            scissorBox: Array.from(gl.getParameter(gl.SCISSOR_BOX)),
            clearColor: Array.from(gl.getParameter(gl.COLOR_CLEAR_VALUE)),
            colorMask: gl.getParameter(gl.COLOR_WRITEMASK),
            flipY: gl.getParameter(gl.UNPACK_FLIP_Y_WEBGL),
            premultiply: gl.getParameter(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL),
            skipRows: gl.getParameter(gl.UNPACK_SKIP_ROWS)
        });
    }"""
)

// language=js
private fun tearDownState(gl: JsAny, objects: JsAny): Unit = js(
    """{
        gl.useProgram(null);
        gl.bindVertexArray(null);
        gl.bindBuffer(gl.ARRAY_BUFFER, null);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, null);
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
        gl.disable(gl.BLEND);
        gl.disable(gl.SCISSOR_TEST);
        gl.disable(gl.DEPTH_TEST);
        gl.colorMask(true, true, true, true);
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
        gl.pixelStorei(gl.UNPACK_SKIP_ROWS, 0);
        gl.deleteProgram(objects.program);
        gl.deleteVertexArray(objects.vertexArray);
        gl.deleteBuffer(objects.buffer);
        gl.deleteTexture(objects.texture);
        gl.deleteFramebuffer(objects.framebuffer);
    }"""
)

/** Creates a framebuffer with a texture of the given size, cleared to the given color. */
// language=js
private fun createFramebuffer(
    gl: JsAny,
    width: Int,
    height: Int,
    red: Double,
    green: Double,
    blue: Double,
): JsAny = js(
    """{
        const texture = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, texture);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, width, height, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
        gl.bindTexture(gl.TEXTURE_2D, null);
        const framebuffer = gl.createFramebuffer();
        gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0);
        gl.disable(gl.SCISSOR_TEST);
        gl.viewport(0, 0, width, height);
        gl.clearColor(red, green, blue, 1);
        gl.clear(gl.COLOR_BUFFER_BIT);
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
        framebuffer.composeTestTexture = texture;
        return framebuffer;
    }"""
)

/** Clears the top [fraction] of the framebuffer (in GL coordinates, the top is the last rows). */
// language=js
private fun clearTop(
    gl: JsAny,
    framebuffer: JsAny,
    width: Int,
    height: Int,
    red: Double,
    green: Double,
    blue: Double,
    fraction: Double,
): Unit = js(
    """{
        gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer);
        gl.enable(gl.SCISSOR_TEST);
        const rows = Math.round(height * fraction);
        gl.scissor(0, height - rows, width, rows);
        gl.clearColor(red, green, blue, 1);
        gl.clear(gl.COLOR_BUFFER_BIT);
        gl.disable(gl.SCISSOR_TEST);
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    }"""
)

// language=js
private fun deleteFramebuffer(gl: JsAny, framebuffer: JsAny): Unit = js(
    """{
        gl.deleteTexture(framebuffer.composeTestTexture);
        gl.deleteFramebuffer(framebuffer);
    }"""
)
