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

package androidx.compose.ui.webcanvas.graphics

import androidx.compose.ui.webcanvas.dom.logWarning
import kotlin.js.JsAny
import kotlin.js.js

/**
 * The WebGL 2 context of the page, created on first use and shared by everything that renders with
 * WebGL: Compose's own drawing ([WebGLRenderer]) and the WebGL content embedded in Compose (see
 * [FramebufferImageBitmap]). With a single context, programs and other GL objects are created once,
 * and the browser's limit on live contexts is out of reach.
 *
 * WebGL output reaches the 2D canvases that Compose draws into through the drawing buffer of the
 * context: whoever renders with WebGL puts the result into the top-left part of the drawing buffer
 * (in canvas coordinates) and draws that part of the canvas into a 2D context. The drawing buffer is
 * preserved, so a frame put there stays until something else is: [canvasHolding] tells whether it
 * still does.
 *
 * Code other than Compose's uses the context too, so Compose's draws set up the GL state they need
 * and restore the previous state afterwards.
 */
internal object SharedWebGL {
    private var handle: JsAny? = null
    private var isUnavailable = false

    /**
     * What the drawing buffer holds: the frame [ownerVersion] of [owner], put there at that size,
     * in that generation of the context.
     */
    private var owner: Any? = null
    private var ownerVersion = 0
    private var ownerWidth = 0
    private var ownerHeight = 0
    private var ownerGeneration = 0

    /** The state of the shared context, created on the first call, or `null` without WebGL 2. */
    fun handleOrNull(): JsAny? {
        handle?.let { return it }
        if (isUnavailable) return null
        val created = try {
            createSharedWebGL()
        } catch (e: Throwable) {
            logWarning("Compose: WebGL 2 initialization failed: $e")
            null
        }
        if (created == null) isUnavailable = true
        handle = created
        return created
    }

    /** The canvas of the context, or `null` without WebGL 2. */
    val canvas: JsAny? get() = handleOrNull()?.let { sharedCanvas(it) }

    /** The `WebGL2RenderingContext`, or `null` without WebGL 2. */
    val context: JsAny? get() = handleOrNull()?.let { sharedContext(it) }

    /** Increments whenever the context is restored after a loss, which drops every GL object. */
    val generation: Int get() = handle?.let { sharedGeneration(it) } ?: 0

    fun isContextLost(): Boolean = handleOrNull()?.let { sharedIsContextLost(it) } ?: true

    /** Forgets what the drawing buffer holds, after code other than [handOver] drew into it. */
    fun markUsed() {
        owner = null
    }

    /** Forgets that the drawing buffer holds a frame of [owner], which is going away. */
    fun forget(owner: Any) {
        if (this.owner === owner) this.owner = null
    }

    /**
     * Returns the canvas if its drawing buffer still holds the frame [version] of [owner] that
     * [handOver] put there, or `null` otherwise.
     */
    fun canvasHolding(owner: Any, version: Int): JsAny? {
        val current = handle ?: return null
        if (this.owner !== owner || ownerVersion != version) return null
        if (sharedIsContextLost(current) ||
            sharedGeneration(current) != ownerGeneration ||
            sharedCanvasWidth(current) != ownerWidth ||
            sharedCanvasHeight(current) != ownerHeight
        ) {
            // Lost (and maybe restored), or resized by code sharing the context: either way, the
            // drawing buffer was cleared.
            this.owner = null
            return null
        }
        return sharedCanvas(current)
    }

    /**
     * Copies the bottom-left [width] x [height] pixels of [framebuffer], the frame [version] of
     * [owner], to the top-left of the drawing buffer, the right way up for content rendered with
     * the usual bottom-left origin. Returns the canvas, or `null` if the context is lost.
     */
    fun handOver(framebuffer: JsAny, width: Int, height: Int, owner: Any, version: Int): JsAny? {
        val current = handleOrNull() ?: return null
        if (!sharedBlit(current, framebuffer, width, height)) {
            this.owner = null
            return null
        }
        this.owner = owner
        ownerVersion = version
        ownerWidth = sharedCanvasWidth(current)
        ownerHeight = sharedCanvasHeight(current)
        ownerGeneration = sharedGeneration(current)
        return sharedCanvas(current)
    }
}

/**
 * Draws what the Canvas 2D API can't with the shared WebGL 2 context ([SharedWebGL]), into its
 * canvas, which is then drawn into the 2D canvas:
 * - images in perspective ([drawImageInPerspective]): a textured quad, whose texture coordinates
 *   the GPU interpolates in a perspective-correct way;
 * - triangle meshes with a color per vertex ([drawColoredTriangles]), interpolated over the
 *   triangles like Skia does (e.g. for mesh gradients).
 */
internal object WebGLRenderer {
    /** The maximum width and height of the images [drawImageInPerspective] accepts. */
    val maxTextureSize: Int
        get() = SharedWebGL.handleOrNull()?.let { sharedMaxTextureSize(it) } ?: 0

    /** Whether WebGL 2 is available, initializing it on the first call. */
    fun isAvailable(): Boolean = !SharedWebGL.isContextLost()

    /**
     * Draws the [sourceWidth] x [sourceHeight] top-left part of [source] through [uvMatrix],
     * which maps the unit square (the texture coordinates of the image) to the device space, and
     * returns a canvas whose top-left [width] x [height] pixels show the device area at
     * ([left], [top]), or `null` on failure.
     */
    fun drawImageInPerspective(
        source: JsAny,
        sourceWidth: Int,
        sourceHeight: Int,
        uvMatrix: Projective,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): JsAny? {
        val handle = SharedWebGL.handleOrNull() ?: return null
        SharedWebGL.markUsed()
        return sharedDrawPerspective(
            handle,
            source,
            sourceWidth,
            sourceHeight,
            uvMatrix.m00,
            uvMatrix.m01,
            uvMatrix.m02,
            uvMatrix.m10,
            uvMatrix.m11,
            uvMatrix.m12,
            uvMatrix.m20,
            uvMatrix.m21,
            uvMatrix.m22,
            left,
            top,
            width,
            height,
        )
    }

    /**
     * Draws triangles with a color per vertex, interpolated (premultiplied) over each triangle,
     * and returns a canvas whose top-left [width] x [height] pixels show the device area at
     * ([left], [top]), or `null` on failure.
     *
     * @param positionBits an `Int32Array` with the raw bits of the device x and y of each vertex
     * @param colors an `Int32Array` with the non-premultiplied ARGB color of each vertex
     * @param indices an `Int32Array` with three vertex indices per triangle
     */
    fun drawColoredTriangles(
        positionBits: JsAny,
        colors: JsAny,
        indices: JsAny,
        indexCount: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): JsAny? {
        val handle = SharedWebGL.handleOrNull() ?: return null
        SharedWebGL.markUsed()
        return sharedDrawTriangles(
            handle,
            positionBits,
            colors,
            indices,
            indexCount,
            left,
            top,
            width,
            height,
        )
    }
}

// language=js
private fun createSharedWebGL(): JsAny? = js(
    """(() => {
    let canvas;
    if (typeof document !== 'undefined') {
        canvas = document.createElement('canvas');
    } else if (typeof OffscreenCanvas !== 'undefined') {
        canvas = new OffscreenCanvas(1, 1);
    } else {
        return null;
    }
    canvas.width = 1;
    canvas.height = 1;
    const gl = canvas.getContext('webgl2', {
        alpha: true, premultipliedAlpha: true, antialias: false, depth: false, stencil: false,
        preserveDrawingBuffer: true
    });
    if (!gl) return null;
    const shared = { canvas, gl, generation: 0, maxTextureSize: gl.getParameter(gl.MAX_TEXTURE_SIZE) };
    // Let the browser restore the context after a loss (e.g. a GPU reset). Every GL object is gone
    // then, so whoever created some recreates them when the generation changes.
    canvas.addEventListener('webglcontextlost', (event) => event.preventDefault());
    canvas.addEventListener('webglcontextrestored', () => {
        shared.generation++;
        shared.maxTextureSize = gl.getParameter(gl.MAX_TEXTURE_SIZE);
    });

    // The canvas grows when a draw needs more room, and shrinks back once the draws have needed
    // less for a while: animations don't reallocate it on every frame, and a single large draw
    // doesn't make every later copy of the drawing buffer large.
    let reservations = 0;
    let neededWidth = 1;
    let neededHeight = 1;
    const roundUp = (size) => Math.ceil(size / 64) * 64;
    const reserve = (width, height) => {
        neededWidth = Math.max(neededWidth, width);
        neededHeight = Math.max(neededHeight, height);
        if (++reservations >= 600) {
            const w = Math.min(canvas.width, roundUp(neededWidth));
            const h = Math.min(canvas.height, roundUp(neededHeight));
            if (canvas.width !== w) canvas.width = w;
            if (canvas.height !== h) canvas.height = h;
            reservations = 0;
            neededWidth = width;
            neededHeight = height;
        }
        if (canvas.width < width) canvas.width = roundUp(width);
        if (canvas.height < height) canvas.height = roundUp(height);
    };

    const valid = (object, isObject) => (object && isObject.call(gl, object)) ? object : null;

    // The state Compose's draws change, which they restore afterwards: the context is shared with
    // other code (embedded WebGL content), whose state must survive them.
    const capabilities = [
        gl.BLEND, gl.CULL_FACE, gl.DEPTH_TEST, gl.POLYGON_OFFSET_FILL, gl.RASTERIZER_DISCARD,
        gl.SAMPLE_ALPHA_TO_COVERAGE, gl.SAMPLE_COVERAGE, gl.SCISSOR_TEST, gl.STENCIL_TEST
    ];
    const unpackParameters = [
        gl.UNPACK_SKIP_PIXELS, gl.UNPACK_SKIP_ROWS, gl.UNPACK_ROW_LENGTH, gl.UNPACK_FLIP_Y_WEBGL,
        gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, gl.UNPACK_COLORSPACE_CONVERSION_WEBGL
    ];
    const saveState = () => {
        const state = {
            enabled: capabilities.map((capability) => gl.isEnabled(capability)),
            drawFramebuffer: gl.getParameter(gl.DRAW_FRAMEBUFFER_BINDING),
            readFramebuffer: gl.getParameter(gl.READ_FRAMEBUFFER_BINDING),
            viewport: gl.getParameter(gl.VIEWPORT),
            scissorBox: gl.getParameter(gl.SCISSOR_BOX),
            colorMask: gl.getParameter(gl.COLOR_WRITEMASK),
            clearColor: gl.getParameter(gl.COLOR_CLEAR_VALUE),
            program: gl.getParameter(gl.CURRENT_PROGRAM),
            vertexArray: gl.getParameter(gl.VERTEX_ARRAY_BINDING),
            arrayBuffer: gl.getParameter(gl.ARRAY_BUFFER_BINDING),
            unpackBuffer: gl.getParameter(gl.PIXEL_UNPACK_BUFFER_BINDING),
            unpack: unpackParameters.map((parameter) => gl.getParameter(parameter)),
            unpackColorSpace: gl.unpackColorSpace,
            activeTexture: gl.getParameter(gl.ACTIVE_TEXTURE)
        };
        gl.activeTexture(gl.TEXTURE0);
        state.texture = gl.getParameter(gl.TEXTURE_BINDING_2D);
        state.sampler = gl.getParameter(gl.SAMPLER_BINDING);
        gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, null);
        state.drawBuffer = gl.getParameter(gl.DRAW_BUFFER0);
        return state;
    };
    const restoreState = (state) => {
        capabilities.forEach((capability, i) => {
            if (state.enabled[i]) gl.enable(capability); else gl.disable(capability);
        });
        gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, null);
        gl.drawBuffers([state.drawBuffer]);
        gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, valid(state.drawFramebuffer, gl.isFramebuffer));
        gl.bindFramebuffer(gl.READ_FRAMEBUFFER, valid(state.readFramebuffer, gl.isFramebuffer));
        gl.viewport(state.viewport[0], state.viewport[1], state.viewport[2], state.viewport[3]);
        gl.scissor(state.scissorBox[0], state.scissorBox[1], state.scissorBox[2], state.scissorBox[3]);
        gl.colorMask(state.colorMask[0], state.colorMask[1], state.colorMask[2], state.colorMask[3]);
        gl.clearColor(state.clearColor[0], state.clearColor[1], state.clearColor[2], state.clearColor[3]);
        gl.useProgram(valid(state.program, gl.isProgram));
        gl.bindVertexArray(valid(state.vertexArray, gl.isVertexArray));
        gl.bindBuffer(gl.ARRAY_BUFFER, valid(state.arrayBuffer, gl.isBuffer));
        gl.bindBuffer(gl.PIXEL_UNPACK_BUFFER, valid(state.unpackBuffer, gl.isBuffer));
        unpackParameters.forEach((parameter, i) => gl.pixelStorei(parameter, state.unpack[i]));
        if (state.unpackColorSpace !== undefined) gl.unpackColorSpace = state.unpackColorSpace;
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, valid(state.texture, gl.isTexture));
        gl.bindSampler(0, valid(state.sampler, gl.isSampler));
        gl.activeTexture(state.activeTexture);
    };

    // Prepares a draw of the device area (left, top, width, height) into the top-left part of the
    // canvas (the GL viewport origin is bottom-left), with the state Compose's draws rely on.
    const begin = (width, height) => {
        reserve(width, height);
        capabilities.forEach((capability) => gl.disable(capability));
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
        gl.drawBuffers([gl.BACK]);
        gl.colorMask(true, true, true, true);
        const y = canvas.height - height;
        gl.viewport(0, y, width, height);
        gl.enable(gl.SCISSOR_TEST);
        gl.scissor(0, y, width, height);
        gl.clearColor(0, 0, 0, 0);
        gl.clear(gl.COLOR_BUFFER_BIT);
        gl.disable(gl.SCISSOR_TEST);
    };
    // Runs a draw between saving and restoring the state; returns the canvas, or null on failure.
    const draw = (prepare, width, height, render) => {
        if (gl.isContextLost()) return null;
        const state = saveState();
        try {
            const objects = prepare();
            if (!objects) return null;
            begin(width, height);
            render(objects);
            return canvas;
        } finally {
            restoreState(state);
        }
    };

    const compile = (type, source) => {
        const shader = gl.createShader(type);
        gl.shaderSource(shader, source);
        gl.compileShader(shader);
        if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
            console.warn('Compose: ' + gl.getShaderInfoLog(shader));
            return null;
        }
        return shader;
    };
    const link = (vertexSource, fragmentSource) => {
        const vertexShader = compile(gl.VERTEX_SHADER, vertexSource);
        const fragmentShader = compile(gl.FRAGMENT_SHADER, fragmentSource);
        if (!vertexShader || !fragmentShader) return null;
        const program = gl.createProgram();
        gl.attachShader(program, vertexShader);
        gl.attachShader(program, fragmentShader);
        gl.linkProgram(program);
        gl.deleteShader(vertexShader);
        gl.deleteShader(fragmentShader);
        if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
            console.warn('Compose: ' + gl.getProgramInfoLog(program));
            return null;
        }
        return program;
    };
    // The GL objects of the draws, created on first use for the current generation of the context.
    let objects = {};
    let objectsGeneration = 0;
    const objectsOf = (name, create) => {
        if (objectsGeneration !== shared.generation) {
            objects = {};
            objectsGeneration = shared.generation;
        }
        if (!(name in objects)) objects[name] = create();
        return objects[name];
    };

    // Images in perspective. The vertex position is the image of the texture coordinates, in
    // homogeneous coordinates relative to the target area: keeping w lets the GPU clip what is
    // behind the camera and interpolate the texture coordinates in perspective.
    const perspectiveObjects = () => objectsOf('perspective', () => {
        const program = link(`#version 300 es
in vec2 aUv;
uniform mat3 uMatrix;
uniform vec4 uTarget;
out vec2 vUv;
void main() {
    vec3 p = uMatrix * vec3(aUv, 1.0);
    gl_Position = vec4(
        (p.x - uTarget.x * p.z) * (2.0 / uTarget.z) - p.z,
        p.z - (p.y - uTarget.y * p.z) * (2.0 / uTarget.w),
        0.0,
        p.z);
    vUv = aUv;
}`, `#version 300 es
precision highp float;
uniform sampler2D uTexture;
in vec2 vUv;
out vec4 outColor;
void main() {
    outColor = texture(uTexture, vUv);
}`);
        if (!program) return null;
        const vao = gl.createVertexArray();
        gl.bindVertexArray(vao);
        const buffer = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
        const uvLocation = gl.getAttribLocation(program, 'aUv');
        gl.enableVertexAttribArray(uvLocation);
        gl.vertexAttribPointer(uvLocation, 2, gl.FLOAT, false, 0, 0);
        const texture = gl.createTexture();
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, texture);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        return {
            program, vao, texture,
            matrix: gl.getUniformLocation(program, 'uMatrix'),
            target: gl.getUniformLocation(program, 'uTarget'),
            sampler: gl.getUniformLocation(program, 'uTexture'),
            values: new Float32Array(9)
        };
    });

    // Triangles with interpolated vertex colors. The colors are ARGB ints, whose little-endian
    // bytes read as (b, g, r, a); like Skia, they are interpolated premultiplied.
    const triangleObjects = () => objectsOf('triangles', () => {
        const program = link(`#version 300 es
in vec2 aPosition;
in vec4 aColor;
uniform vec4 uTarget;
out vec4 vColor;
void main() {
    vec2 p = (aPosition - uTarget.xy) / uTarget.zw;
    gl_Position = vec4(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0, 0.0, 1.0);
    vec4 c = aColor.zyxw;
    vColor = vec4(c.rgb * c.a, c.a);
}`, `#version 300 es
precision highp float;
in vec4 vColor;
out vec4 outColor;
void main() {
    outColor = vColor;
}`);
        if (!program) return null;
        const vao = gl.createVertexArray();
        gl.bindVertexArray(vao);
        const positions = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, positions);
        const positionLocation = gl.getAttribLocation(program, 'aPosition');
        gl.enableVertexAttribArray(positionLocation);
        gl.vertexAttribPointer(positionLocation, 2, gl.FLOAT, false, 0, 0);
        const colors = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, colors);
        const colorLocation = gl.getAttribLocation(program, 'aColor');
        gl.enableVertexAttribArray(colorLocation);
        gl.vertexAttribPointer(colorLocation, 4, gl.UNSIGNED_BYTE, true, 0, 0);
        const indices = gl.createBuffer();
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, indices);
        return {
            program, vao, positions, colors, indices,
            target: gl.getUniformLocation(program, 'uTarget')
        };
    });

    shared.drawPerspective = (source, sw, sh, m00, m01, m02, m10, m11, m12, m20, m21, m22, left, top, width, height) =>
        draw(perspectiveObjects, width, height, (p) => {
            gl.useProgram(p.program);
            gl.bindVertexArray(p.vao);
            gl.activeTexture(gl.TEXTURE0);
            gl.bindTexture(gl.TEXTURE_2D, p.texture);
            gl.bindSampler(0, null);
            gl.bindBuffer(gl.PIXEL_UNPACK_BUFFER, null);
            gl.pixelStorei(gl.UNPACK_SKIP_PIXELS, 0);
            gl.pixelStorei(gl.UNPACK_SKIP_ROWS, 0);
            gl.pixelStorei(gl.UNPACK_ROW_LENGTH, 0);
            gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
            gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
            gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL, gl.NONE);
            if (gl.unpackColorSpace !== undefined) gl.unpackColorSpace = 'srgb';
            // Upload only the used part of the (pooled) source canvas.
            gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, sw, sh, 0, gl.RGBA, gl.UNSIGNED_BYTE, source);
            gl.generateMipmap(gl.TEXTURE_2D);
            const m = p.values;
            m[0] = m00; m[1] = m10; m[2] = m20;
            m[3] = m01; m[4] = m11; m[5] = m21;
            m[6] = m02; m[7] = m12; m[8] = m22;
            gl.uniformMatrix3fv(p.matrix, false, m);
            gl.uniform4f(p.target, left, top, width, height);
            gl.uniform1i(p.sampler, 0);
            gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
        });

    shared.drawTriangles = (positionBits, colors, indices, indexCount, left, top, width, height) =>
        draw(triangleObjects, width, height, (t) => {
            gl.useProgram(t.program);
            gl.bindVertexArray(t.vao);
            gl.bindBuffer(gl.ARRAY_BUFFER, t.positions);
            gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(positionBits.buffer, positionBits.byteOffset, positionBits.length), gl.STREAM_DRAW);
            gl.bindBuffer(gl.ARRAY_BUFFER, t.colors);
            gl.bufferData(gl.ARRAY_BUFFER, new Uint8Array(colors.buffer, colors.byteOffset, colors.length * 4), gl.STREAM_DRAW);
            gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, t.indices);
            gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint32Array(indices.buffer, indices.byteOffset, indexCount), gl.STREAM_DRAW);
            gl.uniform4f(t.target, left, top, width, height);
            gl.drawElements(gl.TRIANGLES, indexCount, gl.UNSIGNED_INT, 0);
        });

    // Copies the bottom-left (width, height) pixels of a framebuffer to the top-left of the canvas.
    // A blit only depends on the framebuffer bindings, the draw buffer, the scissor test and
    // rasterizer discard, so only those are saved.
    shared.blit = (framebuffer, width, height) => {
        if (gl.isContextLost()) return false;
        reserve(width, height);
        const scissor = gl.isEnabled(gl.SCISSOR_TEST);
        const discard = gl.isEnabled(gl.RASTERIZER_DISCARD);
        const drawFramebuffer = gl.getParameter(gl.DRAW_FRAMEBUFFER_BINDING);
        const readFramebuffer = gl.getParameter(gl.READ_FRAMEBUFFER_BINDING);
        if (scissor) gl.disable(gl.SCISSOR_TEST);
        if (discard) gl.disable(gl.RASTERIZER_DISCARD);
        gl.bindFramebuffer(gl.READ_FRAMEBUFFER, framebuffer);
        gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, null);
        const drawBuffer = gl.getParameter(gl.DRAW_BUFFER0);
        if (drawBuffer !== gl.BACK) gl.drawBuffers([gl.BACK]);
        const y = canvas.height - height;
        gl.blitFramebuffer(0, 0, width, height, 0, y, width, y + height, gl.COLOR_BUFFER_BIT, gl.NEAREST);
        if (drawBuffer !== gl.BACK) gl.drawBuffers([drawBuffer]);
        gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, valid(drawFramebuffer, gl.isFramebuffer));
        gl.bindFramebuffer(gl.READ_FRAMEBUFFER, valid(readFramebuffer, gl.isFramebuffer));
        if (scissor) gl.enable(gl.SCISSOR_TEST);
        if (discard) gl.enable(gl.RASTERIZER_DISCARD);
        return true;
    };

    return shared;
})()"""
)

// language=js
private fun sharedCanvas(shared: JsAny): JsAny = js("shared.canvas")

// language=js
private fun sharedContext(shared: JsAny): JsAny = js("shared.gl")

// language=js
private fun sharedGeneration(shared: JsAny): Int = js("shared.generation")

// language=js
private fun sharedIsContextLost(shared: JsAny): Boolean = js("shared.gl.isContextLost()")

// language=js
private fun sharedCanvasWidth(shared: JsAny): Int = js("shared.canvas.width")

// language=js
private fun sharedCanvasHeight(shared: JsAny): Int = js("shared.canvas.height")

// language=js
private fun sharedMaxTextureSize(shared: JsAny): Int = js("shared.maxTextureSize || 0")

// language=js
private fun sharedBlit(shared: JsAny, framebuffer: JsAny, width: Int, height: Int): Boolean =
    js("shared.blit(framebuffer, width, height)")

// language=js
private fun sharedDrawPerspective(
    shared: JsAny,
    source: JsAny,
    sourceWidth: Int,
    sourceHeight: Int,
    m00: Double,
    m01: Double,
    m02: Double,
    m10: Double,
    m11: Double,
    m12: Double,
    m20: Double,
    m21: Double,
    m22: Double,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
): JsAny? = js(
    "shared.drawPerspective(source, sourceWidth, sourceHeight, m00, m01, m02, m10, m11, m12, m20, m21, m22, left, top, width, height)"
)

// language=js
private fun sharedDrawTriangles(
    shared: JsAny,
    positionBits: JsAny,
    colors: JsAny,
    indices: JsAny,
    indexCount: Int,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
): JsAny? = js(
    "shared.drawTriangles(positionBits, colors, indices, indexCount, left, top, width, height)"
)
