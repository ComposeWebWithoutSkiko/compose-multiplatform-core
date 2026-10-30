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

import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.webcanvas.dom.ImageData
import androidx.compose.ui.webcanvas.dom.logWarning
import androidx.compose.ui.webcanvas.dom.withByteReaders
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
 * Compose's draws set up the GL state they need. Once code other than Compose's took the context
 * ([context], [canvas]), they restore the previous state afterwards; until then, they leave it.
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

    /**
     * The canvas of the context, or `null` without WebGL 2, for code other than Compose's (see
     * [context]).
     */
    val canvas: JsAny? get() = handleOrNull()?.let { markShared(it); sharedCanvas(it) }

    /**
     * The `WebGL2RenderingContext`, or `null` without WebGL 2, for code other than Compose's: once
     * it's taken, Compose's draws restore the GL state they change.
     */
    val context: JsAny? get() = handleOrNull()?.let { markShared(it); sharedContext(it) }

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
        hold(owner, version)
        return sharedCanvas(current)
    }

    /** Notes that the drawing buffer holds the frame [version] of [owner], just drawn there. */
    fun hold(owner: Any, version: Int) {
        val current = handle ?: return
        this.owner = owner
        ownerVersion = version
        ownerWidth = sharedCanvasWidth(current)
        ownerHeight = sharedCanvasHeight(current)
        ownerGeneration = sharedGeneration(current)
    }
}

/**
 * Draws what the Canvas 2D API can't with the shared WebGL 2 context ([SharedWebGL]), into its
 * canvas, which is then drawn into the 2D canvas:
 * - images in perspective ([drawImageInPerspective]): a textured quad, whose texture coordinates
 *   the GPU interpolates in a perspective-correct way;
 * - triangle meshes with a color per vertex ([drawColoredTriangles]), interpolated over the
 *   triangles like Skia does (e.g. for mesh gradients);
 * - slots of the atlas through color matrices ([beginColorMatrices]), where the browser applies no
 *   filters to canvases (see [SlotColorMatrices]);
 * - images of the atlas blurred ([beginBlurs]), where the browser applies no filters to canvases
 *   (see [SlotPixelBlurs]).
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
     * Starts drawing slots of pages of the atlas in perspective at once, into separate parts of the
     * top-left [width] x [height] pixels of the canvas of the shared context: [uploadPage] for each
     * page, then [drawProjection] for each slot, then [endBatch]. Returns `false` on failure, when
     * nothing is to be drawn.
     */
    fun beginProjections(width: Int, height: Int): Boolean {
        val handle = SharedWebGL.handleOrNull() ?: return false
        SharedWebGL.markUsed()
        return sharedBeginProjections(handle, width, height)
    }

    /**
     * Starts drawing slots of pages of the atlas through color matrices at once, into separate
     * parts of the top-left [width] x [height] pixels of the canvas of the shared context:
     * [uploadPagePixels] for each page, then [drawColorMatrix] for each slot, then [endBatch].
     * Returns the canvas, or `null` on failure, when nothing is to be drawn.
     */
    fun beginColorMatrices(width: Int, height: Int): JsAny? {
        val handle = SharedWebGL.handleOrNull() ?: return null
        SharedWebGL.markUsed()
        return sharedBeginColorMatrices(handle, width, height)
    }

    /**
     * Starts blurring images of pages of the atlas through their pixels at once, into separate
     * parts of the top-left [width] x [height] pixels of the canvas of the shared context:
     * [uploadPagePixels] for each page, then [blurImage] for each image, then [endBatch]. Returns
     * the canvas, or `null` on failure, when nothing is to be drawn.
     */
    fun beginBlurs(width: Int, height: Int): JsAny? {
        val handle = SharedWebGL.handleOrNull() ?: return null
        SharedWebGL.markUsed()
        return sharedBeginBlurs(handle, width, height)
    }

    /**
     * Uploads the top-left [width] x [height] pixels of [source] into [texture], or into a new
     * [textureWidth] x [textureHeight] texture if it's `null`, and returns the texture, which
     * [deleteTexture] deletes.
     */
    fun uploadPage(
        texture: JsAny?,
        source: JsAny,
        textureWidth: Int,
        textureHeight: Int,
        width: Int,
        height: Int,
    ): JsAny =
        sharedUploadPage(
            checkNotNull(SharedWebGL.handleOrNull()),
            texture,
            source,
            textureWidth,
            textureHeight,
            width,
            height,
        )

    /**
     * Uploads [pixels], the top-left part of a page that its context read, into [texture], or into a
     * new [textureWidth] x [textureHeight] texture if it's `null`, as they are: non-premultiplied, for
     * the shaders that premultiply them as the canvas stores them ([beginColorMatrices],
     * [beginBlurs]). Returns the texture, which [deleteTexture] deletes.
     */
    fun uploadPagePixels(texture: JsAny?, pixels: ImageData, textureWidth: Int, textureHeight: Int): JsAny =
        sharedUploadPagePixels(checkNotNull(SharedWebGL.handleOrNull()), texture, pixels, textureWidth, textureHeight)

    /** Deletes a texture of [uploadPage] or [uploadPagePixels]. */
    fun deleteTexture(texture: JsAny) {
        sharedDeleteTexture(checkNotNull(SharedWebGL.handleOrNull()), texture)
    }

    /**
     * Draws the part ([slotX], [slotY], [slotWidth], [slotHeight]) of [texture], in texture
     * coordinates, through [uvMatrix], which maps the unit square to the device space, so that the
     * [width] x [height] device pixels at ([left], [top]) show at ([x], [y]) of the canvas.
     */
    fun drawProjection(
        texture: JsAny,
        slotX: Double,
        slotY: Double,
        slotWidth: Double,
        slotHeight: Double,
        uvMatrix: Projective,
        x: Int,
        y: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ) {
        sharedDrawProjection(
            checkNotNull(SharedWebGL.handleOrNull()),
            texture,
            slotX,
            slotY,
            slotWidth,
            slotHeight,
            uvMatrix.m00,
            uvMatrix.m01,
            uvMatrix.m02,
            uvMatrix.m10,
            uvMatrix.m11,
            uvMatrix.m12,
            uvMatrix.m20,
            uvMatrix.m21,
            uvMatrix.m22,
            x,
            y,
            left,
            top,
            width,
            height,
        )
    }

    /**
     * Draws the [width] x [height] slot at ([slotX], [slotY]) of [texture], a page of
     * [uploadPagePixels], through [colorMatrix] (see [WebColorFilter.jsColorMatrix]) at ([x], [y])
     * of the canvas.
     */
    fun drawColorMatrix(
        texture: JsAny,
        slotX: Int,
        slotY: Int,
        width: Int,
        height: Int,
        colorMatrix: JsAny,
        x: Int,
        y: Int,
    ) {
        sharedDrawColorMatrix(
            checkNotNull(SharedWebGL.handleOrNull()),
            texture,
            slotX,
            slotY,
            width,
            height,
            colorMatrix,
            x,
            y,
        )
    }

    /**
     * Blurs the [width] x [height] image at ([x], [y]) of [texture], a page of [uploadPagePixels],
     * as [blurPixels] blurs it by the boxes of [radiiX] along its rows and [radiiY] along its
     * columns, into the canvas at ([outX] + [border], [outY] + [border]), with [border] pixels
     * around it holding the values of its nearest pixels.
     */
    fun blurImage(
        texture: JsAny,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        radiiX: IntArray,
        radiiY: IntArray,
        outX: Int,
        outY: Int,
        border: Int,
    ) {
        sharedBlurImage(
            checkNotNull(SharedWebGL.handleOrNull()),
            texture,
            x,
            y,
            width,
            height,
            radiiX[0],
            radiiX[1],
            radiiX[2],
            radiiY[0],
            radiiY[1],
            radiiY[2],
            outX,
            outY,
            border,
        )
    }

    /**
     * Ends the draws [beginProjections], [beginColorMatrices] or [beginBlurs] started, which the
     * canvas of the shared context holds.
     */
    fun endBatch() {
        sharedEndBatch(checkNotNull(SharedWebGL.handleOrNull()))
    }

    /**
     * Draws the triangles of [vertices] with their vertex colors interpolated (premultiplied) over
     * each triangle, their positions mapped to the device by [matrix], and returns a canvas whose
     * top-left [width] x [height] pixels show the device area at ([left], [top]), or `null` on
     * failure. Like Skia, the colors are multiplied by [alpha], then go through [colorFilter]: in
     * the pixels of the triangles only.
     */
    fun drawColoredTriangles(
        vertices: Vertices,
        matrix: Affine,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        alpha: Float,
        colorFilter: WebColorFilter?,
    ): JsAny? {
        val handle = SharedWebGL.handleOrNull() ?: return null
        SharedWebGL.markUsed()
        val mode = when (vertices.vertexMode) {
            VertexMode.Triangles -> 0
            VertexMode.TriangleStrip -> 1
            else -> 2
        }
        // The tints that are compositing operations blend in the program, the other filters are
        // color matrices.
        val tint = (colorFilter as? WebColorFilter.Tint)?.takeIf { it.appliesAsComposite }
        val colorMatrix = if (tint == null) colorFilter?.jsColorMatrix else null
        val filterKind = when {
            tint != null -> TintFilter
            colorMatrix != null -> MatrixFilter
            else -> NoFilter
        }
        return withByteReaders(vertices.positions, vertices.colors, vertices.indices) { positions, colors, indices ->
            sharedDrawTriangles(
                handle,
                mode,
                vertices.colors.size,
                vertices.indices.size,
                positions,
                colors,
                indices,
                matrix.a,
                matrix.b,
                matrix.c,
                matrix.d,
                matrix.e,
                matrix.f,
                left,
                top,
                width,
                height,
                alpha,
                filterKind,
                colorMatrix,
                tint?.tintArgb ?: 0,
                tint?.blendMode?.ordinalValue() ?: 0,
            )
        }
    }

    // The kinds of color filters of drawColoredTriangles, as its program takes them.
    private const val NoFilter = 0
    private const val MatrixFilter = 1
    private const val TintFilter = 2
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
    // isShared: whether code other than Compose's took the context (see SharedWebGL.context).
    const shared = {
        canvas, gl, generation: 0, maxTextureSize: gl.getParameter(gl.MAX_TEXTURE_SIZE), isShared: false
    };
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
    // The draws place their pixels in the drawing buffer as in the canvas, so the drawing buffer
    // must have the size of the canvas: the browser gives a smaller one when it's short of memory,
    // when the canvas is sized for this draw alone, and the draw fails if that's still too large.
    let reservations = 0;
    let neededWidth = 1;
    let neededHeight = 1;
    const roundUp = (size) => Math.ceil(size / 64) * 64;
    const hasWholeBuffer = () =>
        gl.drawingBufferWidth === canvas.width && gl.drawingBufferHeight === canvas.height;
    // Makes room for a draw of (width, height) pixels; returns false if there's none.
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
        if (hasWholeBuffer()) return true;
        canvas.width = roundUp(width);
        canvas.height = roundUp(height);
        return hasWholeBuffer();
    };

    const valid = (object, isObject) => (object && isObject.call(gl, object)) ? object : null;

    // The state Compose's draws change, which they restore afterwards once the context is shared
    // with other code (embedded WebGL content), whose state must survive them. Saving and restoring
    // queries the GPU process, synchronously: until then, Compose's draws set up all the state
    // they rely on, and leave it.
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
    // Returns false if the canvas has no room for it.
    const begin = (width, height) => {
        if (!reserve(width, height)) return false;
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
        return true;
    };
    // A batch of draws into the top-left (width, height) pixels of the canvas with the objects that
    // prepare returns, between beginBatch, which returns false on failure, and endBatch, saving the
    // state and restoring it once the context is shared.
    let batch = null;
    const beginBatch = (prepare, width, height) => {
        if (gl.isContextLost()) return false;
        const state = shared.isShared ? saveState() : null;
        let begun = false;
        try {
            const objects = prepare();
            begun = !!objects && begin(width, height);
            if (begun) batch = { objects, state };
            return begun;
        } finally {
            if (!begun && state) restoreState(state);
        }
    };
    const endBatch = () => {
        if (batch.state) restoreState(batch.state);
        batch = null;
    };
    // Runs a draw as a batch of its own; returns the canvas, or null on failure.
    const draw = (prepare, width, height, render) => {
        if (!beginBatch(prepare, width, height)) return null;
        try {
            render(batch.objects);
            return canvas;
        } finally {
            endBatch();
        }
    };
    // Hands the context to code other than Compose's, once: from then on Compose's draws restore
    // the state they change, which until then they left as they set it, so it's set back to what
    // a new context has, the viewport and scissor box at the size of the drawing buffer.
    shared.share = () => {
        if (shared.isShared) return;
        shared.isShared = true;
        if (gl.isContextLost()) return;
        const area = [0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight];
        restoreState({
            enabled: capabilities.map(() => false),
            drawFramebuffer: null,
            readFramebuffer: null,
            drawBuffer: gl.BACK,
            viewport: area,
            scissorBox: area,
            colorMask: [true, true, true, true],
            clearColor: [0, 0, 0, 0],
            program: null,
            vertexArray: null,
            arrayBuffer: null,
            unpackBuffer: null,
            unpack: [0, 0, 0, false, false, gl.BROWSER_DEFAULT_WEBGL],
            unpackColorSpace: 'srgb',
            activeTexture: gl.TEXTURE0,
            texture: null,
            sampler: null
        });
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
    // behind the camera and interpolate the texture coordinates in perspective. The image is the
    // part (x, y, width, height) of the texture that uSlot gives, in texture coordinates.
    const perspectiveObjects = () => objectsOf('perspective', () => {
        const program = link(`#version 300 es
in vec2 aUv;
uniform mat3 uMatrix;
uniform vec4 uTarget;
uniform vec4 uSlot;
out vec2 vUv;
void main() {
    vec3 p = uMatrix * vec3(aUv, 1.0);
    gl_Position = vec4(
        (p.x - uTarget.x * p.z) * (2.0 / uTarget.z) - p.z,
        p.z - (p.y - uTarget.y * p.z) * (2.0 / uTarget.w),
        0.0,
        p.z);
    vUv = uSlot.xy + aUv * uSlot.zw;
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
        return {
            program, vao,
            matrix: gl.getUniformLocation(program, 'uMatrix'),
            target: gl.getUniformLocation(program, 'uTarget'),
            slot: gl.getUniformLocation(program, 'uSlot'),
            sampler: gl.getUniformLocation(program, 'uTexture'),
            values: new Float32Array(9)
        };
    });
    // Sets up the state of the draws of the program of objects o from images: canvases uploaded
    // premultiplied (uploadPagePixels uploads its bytes as they are), the right way up, into
    // textures sampled from unit 0.
    const useImages = (o) => {
        gl.useProgram(o.program);
        gl.bindVertexArray(o.vao);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindSampler(0, null);
        gl.bindBuffer(gl.PIXEL_UNPACK_BUFFER, null);
        gl.pixelStorei(gl.UNPACK_SKIP_PIXELS, 0);
        gl.pixelStorei(gl.UNPACK_SKIP_ROWS, 0);
        gl.pixelStorei(gl.UNPACK_ROW_LENGTH, 0);
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
        gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL, gl.NONE);
        if (gl.unpackColorSpace !== undefined) gl.unpackColorSpace = 'srgb';
        gl.uniform1i(o.sampler, 0);
    };
    // Creates a texture, which it binds to the active unit (0, see useImages). Like the pages
    // of an atlas, which hold several images, it's sampled without mipmaps; the images are drawn
    // at the scale of their most magnified part.
    const newTexture = () => {
        const texture = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, texture);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        return texture;
    };
    // Draws the bound texture through the matrix (m00 .. m22, row by row) into the device area
    // (left, top, width, height), which shows at (x, y) of the canvas.
    const drawInPerspective = (p, m00, m01, m02, m10, m11, m12, m20, m21, m22, x, y, left, top, width, height) => {
        const m = p.values;
        m[0] = m00; m[1] = m10; m[2] = m20;
        m[3] = m01; m[4] = m11; m[5] = m21;
        m[6] = m02; m[7] = m12; m[8] = m22;
        gl.uniformMatrix3fv(p.matrix, false, m);
        gl.uniform4f(p.target, left, top, width, height);
        gl.viewport(x, canvas.height - y - height, width, height);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    };

    // A 4x5 color matrix, like Skia's color filters: the 4x4 part uColorMatrix and the last column
    // uColorOffset, over the non-premultiplied colors, channels from 0 to 1.
    const colorMatrixFunction = `
uniform mat4 uColorMatrix;
uniform vec4 uColorOffset;
// The premultiplied color c through the color matrix.
vec4 throughColorMatrix(vec4 c) {
    vec4 u = c.a > 0.0 ? vec4(c.rgb / c.a, c.a) : vec4(0.0);
    u = clamp(uColorMatrix * u + uColorOffset, 0.0, 1.0);
    return vec4(u.rgb * u.a, u.a);
}
`;
    // The bytes of a texel (bytesOf), and those of a texel of a page uploaded through its pixels
    // (uploadPagePixels) premultiplied as the canvas stores them (premultipliedBytes): the context
    // reads non-premultiplied bytes, premultiplying which gives back the very bytes of the canvas.
    // No c * a / 255 is halfway between two integers (2 * c * a is even, 255 * (2k + 1) odd), so
    // it's rounded as exactly as in doubles.
    const premultipliedBytesFunction = `
vec4 bytesOf(vec4 texel) {
    return floor(texel * 255.0 + 0.5);
}
vec4 premultipliedBytes(vec4 texel) {
    vec4 c = bytesOf(texel);
    return vec4(floor(c.rgb * c.a / 255.0 + 0.5), c.a);
}
`;
    // Sets the 4x5 matrix m (row by row, see colorMatrixOf) as the color matrix of the program of
    // objects o, whose colorMatrix and colorOffset are the locations of its uniforms.
    const setColorMatrix = (o, m) => {
        // The 4x4 part, column by column.
        const v = o.colorValues;
        for (let row = 0; row < 4; row++) {
            for (let column = 0; column < 4; column++) v[4 * column + row] = m[5 * row + column];
        }
        gl.uniformMatrix4fv(o.colorMatrix, false, v);
        gl.uniform4f(o.colorOffset, m[4], m[9], m[14], m[19]);
    };

    // Triangles with interpolated vertex colors. The colors are ARGB ints, whose little-endian
    // bytes read as (b, g, r, a); like Skia, they are interpolated premultiplied, then multiplied
    // by uAlpha and filtered: by the color matrix (uFilter 1, see colorMatrixFunction), or by the
    // tint uTint (non-premultiplied) over them in the blend mode uMode (uFilter 2), numbered as
    // BlendMode.ordinalValue, as blendColors does. The positions are in the space that uMatrix
    // maps to the device.
    const triangleObjects = () => objectsOf('triangles', () => {
        const program = link(`#version 300 es
in vec2 aPosition;
in vec4 aColor;
uniform mat3 uMatrix;
uniform vec4 uTarget;
out vec4 vColor;
void main() {
    vec2 device = (uMatrix * vec3(aPosition, 1.0)).xy;
    vec2 p = (device - uTarget.xy) / uTarget.zw;
    gl_Position = vec4(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0, 0.0, 1.0);
    vec4 c = aColor.zyxw;
    vColor = vec4(c.rgb * c.a, c.a);
}`, `#version 300 es
precision highp float;
in vec4 vColor;
uniform float uAlpha;
uniform int uFilter;
uniform vec4 uTint;
uniform int uMode;
out vec4 outColor;
` + colorMatrixFunction + `
// The functions of the W3C compositing spec.
float lum(vec3 c) {
    return dot(c, vec3(0.3, 0.59, 0.11));
}
vec3 setLum(vec3 c, float l) {
    c += l - lum(c);
    float n = min(min(c.r, c.g), c.b);
    float x = max(max(c.r, c.g), c.b);
    if (n < 0.0) c = l + (c - l) * l / (l - n);
    if (x > 1.0) c = l + (c - l) * (1.0 - l) / (x - l);
    return c;
}
float sat(vec3 c) {
    return max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b);
}
vec3 setSat(vec3 c, float s) {
    float n = min(min(c.r, c.g), c.b);
    float x = max(max(c.r, c.g), c.b);
    return x > n ? (c - n) * s / (x - n) : vec3(0.0);
}
float hardLight(float s, float b) {
    return s <= 0.5 ? b * 2.0 * s : b + (2.0 * s - 1.0) - b * (2.0 * s - 1.0);
}
// The blend of one channel of the source s over the backdrop b, with a separable mode.
float separable(float s, float b) {
    switch (uMode) {
        case 14: return s + b - s * b; // Screen
        case 15: return hardLight(b, s); // Overlay
        case 16: return min(s, b); // Darken
        case 17: return max(s, b); // Lighten
        case 18: return b == 0.0 ? 0.0 : s >= 1.0 ? 1.0 : min(1.0, b / (1.0 - s)); // ColorDodge
        case 19: return b >= 1.0 ? 1.0 : s <= 0.0 ? 0.0 : 1.0 - min(1.0, (1.0 - b) / s); // ColorBurn
        case 20: return hardLight(s, b); // Hardlight
        case 21: // Softlight
            if (s <= 0.5) return b - (1.0 - 2.0 * s) * b * (1.0 - b);
            return b + (2.0 * s - 1.0) * ((b <= 0.25 ? ((16.0 * b - 12.0) * b + 4.0) * b : sqrt(b)) - b);
        case 22: return abs(s - b); // Difference
        case 23: return s + b - 2.0 * s * b; // Exclusion
    }
    return s * b; // Multiply
}
// The blend of the non-premultiplied source s over the backdrop b.
vec3 blend(vec3 s, vec3 b) {
    switch (uMode) {
        case 25: return setLum(setSat(s, sat(b)), lum(b)); // Hue
        case 26: return setLum(setSat(b, sat(s)), lum(b)); // Saturation
        case 27: return setLum(s, lum(b)); // Color
        case 28: return setLum(b, lum(s)); // Luminosity
    }
    return vec3(separable(s.r, b.r), separable(s.g, b.g), separable(s.b, b.b));
}
// The tint over the premultiplied color d.
vec4 tinted(vec4 d) {
    float sa = uTint.a;
    vec4 s = vec4(uTint.rgb * sa, sa);
    float da = d.a;
    switch (uMode) {
        case 1: return s; // Src
        case 3: return s + d * (1.0 - sa); // SrcOver
        case 4: return d + s * (1.0 - da); // DstOver
        case 5: return s * da; // SrcIn
        case 6: return d * sa; // DstIn
        case 7: return s * (1.0 - da); // SrcOut
        case 8: return d * (1.0 - sa); // DstOut
        case 9: return vec4(s.rgb * da + d.rgb * (1.0 - sa), da); // SrcAtop
        case 10: return vec4(d.rgb * sa + s.rgb * (1.0 - da), sa); // DstAtop
        case 11: return s * (1.0 - da) + d * (1.0 - sa); // Xor
        case 12: return min(s + d, 1.0); // Plus
    }
    // The other modes: their blend, mixed with source-over alpha compositing.
    vec3 b = blend(uTint.rgb, da > 0.0 ? d.rgb / da : vec3(0.0));
    return vec4((1.0 - da) * s.rgb + (1.0 - sa) * d.rgb + sa * da * b, sa + da * (1.0 - sa));
}
void main() {
    vec4 c = vColor * uAlpha;
    if (uFilter == 1) {
        c = throughColorMatrix(c);
    } else if (uFilter == 2) {
        c = tinted(c);
    }
    outColor = c;
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
            matrix: gl.getUniformLocation(program, 'uMatrix'),
            target: gl.getUniformLocation(program, 'uTarget'),
            alpha: gl.getUniformLocation(program, 'uAlpha'),
            filter: gl.getUniformLocation(program, 'uFilter'),
            colorMatrix: gl.getUniformLocation(program, 'uColorMatrix'),
            colorOffset: gl.getUniformLocation(program, 'uColorOffset'),
            tint: gl.getUniformLocation(program, 'uTint'),
            mode: gl.getUniformLocation(program, 'uMode'),
            values: new Float32Array(9),
            colorValues: new Float32Array(16),
            modes: [gl.TRIANGLES, gl.TRIANGLE_STRIP, gl.TRIANGLE_FAN]
        };
    });

    // A quad covering the viewport, drawn as a strip of 4 vertices without attributes: the programs
    // whose fragments compute the pixels of the target part of the canvas that the viewport is.
    const viewportVertexShader = `#version 300 es
void main() {
    vec2 corner = vec2(gl_VertexID & 1, gl_VertexID >> 1);
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}`;

    // The slots of pages of the atlas, uploaded through their pixels, through color matrices: each
    // pixel of the target part of the canvas is the texel of the slot at the same place in it,
    // premultiplied, through the color matrix (see colorMatrixFunction). gl_FragCoord counts the rows
    // of the canvas from the bottom, the texture from the top: uOrigin.y - p.y flips them, uOrigin
    // moving the slot to its place.
    const colorMatrixObjects = () => objectsOf('colorMatrices', () => {
        const program = link(viewportVertexShader, `#version 300 es
precision highp float;
// uOrigin.y reaches the height of the canvas, past what mediump ints hold.
precision highp int;
uniform sampler2D uTexture;
uniform ivec2 uOrigin;
out vec4 outColor;
` + colorMatrixFunction + premultipliedBytesFunction + `
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 texel = texelFetch(uTexture, ivec2(uOrigin.x + p.x, uOrigin.y - p.y), 0);
    outColor = throughColorMatrix(premultipliedBytes(texel) / 255.0);
}`);
        if (!program) return null;
        return {
            program,
            vao: gl.createVertexArray(),
            sampler: gl.getUniformLocation(program, 'uTexture'),
            origin: gl.getUniformLocation(program, 'uOrigin'),
            colorMatrix: gl.getUniformLocation(program, 'uColorMatrix'),
            colorOffset: gl.getUniformLocation(program, 'uColorOffset'),
            colorValues: new Float32Array(16)
        };
    });

    // Images of pages of the atlas blurred as blurPixels blurs them (see SlotPixelBlurs): along an
    // axis, the box blurs of radii uRadii in sequence over the line extended by its edge pixels, at
    // once, a texel weighing the ways the three boxes reach it from the pixel, the sum of the bytes
    // rounded to a byte. The rows go from a page uploaded through its pixels, premultiplied
    // (uPremultiplies), into the texture of rows, where the image is at its place in the canvas,
    // unflipped; the columns from there into the canvas, the image and the border around it, whose
    // pixels take the values of the nearest pixels of the image. uOrigin and uFlip map a fragment to
    // its pixel of the image, at uSource of the texture.
    const boxBlurObjects = () => objectsOf('boxBlurs', () => {
        const program = link(viewportVertexShader, `#version 300 es
precision highp float;
// uOrigin.y reaches the height of the canvas, past what mediump ints hold.
precision highp int;
uniform sampler2D uTexture;
uniform ivec2 uOrigin;
uniform int uFlip;
uniform ivec2 uSize;
uniform ivec2 uSource;
uniform ivec2 uStep;
uniform ivec3 uRadii;
uniform bool uPremultiplies;
out vec4 outColor;
` + premultipliedBytesFunction + `
// The ways the first two boxes reach an offset of m.
int pairs(int m) {
    return max(0, min(uRadii.x, m + uRadii.y) - max(-uRadii.x, m - uRadii.y) + 1);
}
// The premultiplied bytes of the texel at q.
vec4 bytesAt(ivec2 q) {
    vec4 texel = texelFetch(uTexture, q, 0);
    return uPremultiplies ? premultipliedBytes(texel) : bytesOf(texel);
}
void main() {
    ivec2 last = uSize - 1;
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 pixel = clamp(uOrigin + ivec2(p.x, uFlip * p.y), ivec2(0), last);
    int reach = uRadii.x + uRadii.y + uRadii.z;
    vec4 sum = vec4(0.0);
    // The ways the three boxes reach the offset j: the third box slides over those of the first two.
    int ways = 0;
    for (int j = -reach; j <= reach; j++) {
        ways += pairs(j + uRadii.z) - pairs(j - uRadii.z - 1);
        ivec2 q = clamp(pixel + uStep * j, ivec2(0), last);
        sum += float(ways) * bytesAt(uSource + q);
    }
    float boxes = float((2 * uRadii.x + 1) * (2 * uRadii.y + 1) * (2 * uRadii.z + 1));
    outColor = floor(sum / boxes + 0.5) / 255.0;
}`);
        if (!program) return null;
        return {
            program,
            vao: gl.createVertexArray(),
            sampler: gl.getUniformLocation(program, 'uTexture'),
            origin: gl.getUniformLocation(program, 'uOrigin'),
            flip: gl.getUniformLocation(program, 'uFlip'),
            size: gl.getUniformLocation(program, 'uSize'),
            source: gl.getUniformLocation(program, 'uSource'),
            step: gl.getUniformLocation(program, 'uStep'),
            radii: gl.getUniformLocation(program, 'uRadii'),
            premultiplies: gl.getUniformLocation(program, 'uPremultiplies'),
            // The texture of rows, of the size of the canvas, and the framebuffer drawing into it.
            rows: null,
            rowsWidth: 0,
            rowsHeight: 0,
            rowsFramebuffer: gl.createFramebuffer()
        };
    });

    // A texture lives for the draw that uploads it: the sources are pooled canvases of any size, and
    // the draw synchronizes with the GPU twice, which costs far more than making the texture.
    shared.drawPerspective = (source, sw, sh, m00, m01, m02, m10, m11, m12, m20, m21, m22, left, top, width, height) =>
        draw(perspectiveObjects, width, height, (p) => {
            useImages(p);
            const texture = newTexture();
            // Upload only the used part of the source canvas.
            gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, sw, sh, 0, gl.RGBA, gl.UNSIGNED_BYTE, source);
            gl.uniform4f(p.slot, 0, 0, 1, 1);
            drawInPerspective(p, m00, m01, m02, m10, m11, m12, m20, m21, m22, 0, 0, left, top, width, height);
            gl.deleteTexture(texture);
        });

    // Slots of pages of the atlas drawn at once, into separate parts of the top-left (width,
    // height) pixels of the canvas, in perspective (beginProjections, drawProjection), through
    // color matrices (beginColorMatrices, drawColorMatrix) or blurred (beginBlurs, blurImage): a
    // batch begins, then the pages are uploaded once each (uploadPage in perspective, sampled
    // premultiplied; uploadPagePixels otherwise, read texel by texel) and every slot drawn from the
    // texture of its page, then the batch ends (endBatch). The textures of the pages are kept with
    // them, at their size, so that their storage is allocated once (see AtlasPage.texture).
    const beginSlots = (prepare, width, height) => {
        if (!beginBatch(prepare, width, height)) return false;
        useImages(batch.objects);
        return true;
    };
    shared.beginProjections = (width, height) => beginSlots(perspectiveObjects, width, height);
    shared.beginColorMatrices = (width, height) => beginSlots(colorMatrixObjects, width, height) ? canvas : null;
    // The texture of rows follows the size of the canvas.
    shared.beginBlurs = (width, height) => {
        if (!beginSlots(boxBlurObjects, width, height)) return null;
        const o = batch.objects;
        if (o.rowsWidth !== canvas.width || o.rowsHeight !== canvas.height) {
            if (o.rows) gl.deleteTexture(o.rows);
            o.rows = newTexture();
            gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA8, canvas.width, canvas.height);
            gl.bindFramebuffer(gl.FRAMEBUFFER, o.rowsFramebuffer);
            gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, o.rows, 0);
            gl.bindFramebuffer(gl.FRAMEBUFFER, null);
            o.rowsWidth = canvas.width;
            o.rowsHeight = canvas.height;
        }
        return canvas;
    };
    // Binds texture, or a new texture of (textureWidth, textureHeight) pixels if it's null, which it
    // returns.
    const pageTexture = (texture, textureWidth, textureHeight) => {
        if (texture) {
            gl.bindTexture(gl.TEXTURE_2D, texture);
        } else {
            texture = newTexture();
            gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA8, textureWidth, textureHeight);
        }
        return texture;
    };
    // Uploads the top-left (width, height) pixels of source into texture, or into a new texture
    // of (textureWidth, textureHeight) pixels if it's null, and returns the texture.
    shared.uploadPage = (texture, source, textureWidth, textureHeight, width, height) => {
        texture = pageTexture(texture, textureWidth, textureHeight);
        gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, source);
        return texture;
    };
    // Uploads the image data pixels, read from a page, as they are, non-premultiplied (see
    // premultipliedBytesFunction), into texture or a new one as uploadPage does.
    shared.uploadPagePixels = (texture, pixels, textureWidth, textureHeight) => {
        texture = pageTexture(texture, textureWidth, textureHeight);
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
        gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, pixels.width, pixels.height, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
        // As the batches upload canvases (see useImages).
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
        return texture;
    };
    shared.drawProjection = (texture, su, sv, sw, sh, m00, m01, m02, m10, m11, m12, m20, m21, m22, x, y, left, top, width, height) => {
        const p = batch.objects;
        gl.bindTexture(gl.TEXTURE_2D, texture);
        gl.uniform4f(p.slot, su, sv, sw, sh);
        drawInPerspective(p, m00, m01, m02, m10, m11, m12, m20, m21, m22, x, y, left, top, width, height);
    };
    // Draws the (width, height) slot at (slotX, slotY) of texture through colorMatrix (4x5, row by
    // row) at (x, y) of the canvas.
    shared.drawColorMatrix = (texture, slotX, slotY, width, height, colorMatrix, x, y) => {
        const o = batch.objects;
        gl.bindTexture(gl.TEXTURE_2D, texture);
        gl.uniform2i(o.origin, slotX - x, slotY - y + canvas.height - 1);
        setColorMatrix(o, colorMatrix);
        gl.viewport(x, canvas.height - y - height, width, height);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    };
    // Blurs the (width, height) image at (x, y) of texture by the boxes of radii (rx0, rx1, rx2)
    // along its rows and (ry0, ry1, ry2) along its columns, at (outX, outY) of the canvas with a
    // border of border pixels around it.
    shared.blurImage = (texture, x, y, width, height, rx0, rx1, rx2, ry0, ry1, ry2, outX, outY, border) => {
        const o = batch.objects;
        const imageX = outX + border;
        const imageY = outY + border;
        gl.uniform2i(o.size, width, height);
        gl.bindFramebuffer(gl.FRAMEBUFFER, o.rowsFramebuffer);
        gl.bindTexture(gl.TEXTURE_2D, texture);
        gl.uniform2i(o.origin, -imageX, -imageY);
        gl.uniform1i(o.flip, 1);
        gl.uniform2i(o.source, x, y);
        gl.uniform2i(o.step, 1, 0);
        gl.uniform3i(o.radii, rx0, rx1, rx2);
        gl.uniform1i(o.premultiplies, 1);
        gl.viewport(imageX, imageY, width, height);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
        gl.bindTexture(gl.TEXTURE_2D, o.rows);
        gl.uniform2i(o.origin, -imageX, canvas.height - 1 - imageY);
        gl.uniform1i(o.flip, -1);
        gl.uniform2i(o.source, imageX, imageY);
        gl.uniform2i(o.step, 0, 1);
        gl.uniform3i(o.radii, ry0, ry1, ry2);
        gl.uniform1i(o.premultiplies, 0);
        gl.viewport(outX, canvas.height - outY - height - 2 * border, width + 2 * border, height + 2 * border);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    };
    shared.endBatch = endBatch;
    shared.deleteTexture = (texture) => gl.deleteTexture(texture);

    // Fills the buffer bound to target with byteLength bytes, which read(byteOffset) hands in
    // chunks: in a single call if they all come at once (no buffer of that size to initialize).
    const upload = (target, byteLength, read) => {
        const first = read(0);
        if (first.length === byteLength) {
            gl.bufferData(target, first, gl.STREAM_DRAW);
            return;
        }
        gl.bufferData(target, byteLength, gl.STREAM_DRAW);
        for (let offset = 0, bytes = first; offset < byteLength; bytes = read(offset)) {
            gl.bufferSubData(target, offset, bytes);
            offset += bytes.length;
        }
    };

    // Draws a mesh of vertexCount vertices in the mode of the index mode (triangles, strip, fan),
    // like Skia: from its indexCount unsigned 16-bit indices, or its vertices in order without
    // them. The positions (2 floats), colors (ARGB ints) and indices are read in chunks (see
    // upload). (a, b, c, d, e, f) maps the positions to the device. The colors are multiplied by
    // alpha and filtered (see triangleObjects) by the filter of filterKind: the 4x5 colorMatrix,
    // row by row, or the ARGB tint in the blend mode tintMode.
    shared.drawTriangles = (mode, vertexCount, indexCount, positions, colors, indices, a, b, c, d, e, f, left, top, width, height, alpha, filterKind, colorMatrix, tint, tintMode) =>
        draw(triangleObjects, width, height, (t) => {
            gl.useProgram(t.program);
            gl.bindVertexArray(t.vao);
            gl.bindBuffer(gl.ARRAY_BUFFER, t.positions);
            upload(gl.ARRAY_BUFFER, 8 * vertexCount, positions);
            gl.bindBuffer(gl.ARRAY_BUFFER, t.colors);
            upload(gl.ARRAY_BUFFER, 4 * vertexCount, colors);
            const m = t.values;
            m[0] = a; m[1] = b; m[2] = 0;
            m[3] = c; m[4] = d; m[5] = 0;
            m[6] = e; m[7] = f; m[8] = 1;
            gl.uniformMatrix3fv(t.matrix, false, m);
            gl.uniform4f(t.target, left, top, width, height);
            gl.uniform1f(t.alpha, alpha);
            gl.uniform1i(t.filter, filterKind);
            if (filterKind === 1) {
                setColorMatrix(t, colorMatrix);
            } else if (filterKind === 2) {
                gl.uniform4f(t.tint, ((tint >> 16) & 255) / 255, ((tint >> 8) & 255) / 255, (tint & 255) / 255, (tint >>> 24) / 255);
                gl.uniform1i(t.mode, tintMode);
            }
            if (indexCount > 0) {
                gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, t.indices);
                upload(gl.ELEMENT_ARRAY_BUFFER, 2 * indexCount, indices);
                gl.drawElements(t.modes[mode], indexCount, gl.UNSIGNED_SHORT, 0);
            } else {
                gl.drawArrays(t.modes[mode], 0, vertexCount);
            }
        });

    // Copies the bottom-left (width, height) pixels of a framebuffer to the top-left of the canvas.
    // A blit only depends on the framebuffer bindings, the draw buffer, the scissor test and
    // rasterizer discard, so only those are saved.
    shared.blit = (framebuffer, width, height) => {
        if (gl.isContextLost() || !reserve(width, height)) return false;
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
private fun markShared(shared: JsAny): Unit = js("shared.share()")

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
private fun sharedBeginProjections(shared: JsAny, width: Int, height: Int): Boolean =
    js("shared.beginProjections(width, height)")

// language=js
private fun sharedBeginColorMatrices(shared: JsAny, width: Int, height: Int): JsAny? =
    js("shared.beginColorMatrices(width, height)")

// language=js
private fun sharedBeginBlurs(shared: JsAny, width: Int, height: Int): JsAny? = js("shared.beginBlurs(width, height)")

// language=js
private fun sharedUploadPage(
    shared: JsAny,
    texture: JsAny?,
    source: JsAny,
    textureWidth: Int,
    textureHeight: Int,
    width: Int,
    height: Int,
): JsAny = js("shared.uploadPage(texture, source, textureWidth, textureHeight, width, height)")

// language=js
private fun sharedUploadPagePixels(
    shared: JsAny,
    texture: JsAny?,
    pixels: ImageData,
    textureWidth: Int,
    textureHeight: Int,
): JsAny = js("shared.uploadPagePixels(texture, pixels, textureWidth, textureHeight)")

// language=js
private fun sharedDeleteTexture(shared: JsAny, texture: JsAny): Unit = js("shared.deleteTexture(texture)")

// language=js
private fun sharedDrawProjection(
    shared: JsAny,
    texture: JsAny,
    slotX: Double,
    slotY: Double,
    slotWidth: Double,
    slotHeight: Double,
    m00: Double,
    m01: Double,
    m02: Double,
    m10: Double,
    m11: Double,
    m12: Double,
    m20: Double,
    m21: Double,
    m22: Double,
    x: Int,
    y: Int,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
): Unit = js(
    "shared.drawProjection(texture, slotX, slotY, slotWidth, slotHeight, m00, m01, m02, m10, m11, m12, m20, m21, m22, x, y, left, top, width, height)"
)

// language=js
private fun sharedDrawColorMatrix(
    shared: JsAny,
    texture: JsAny,
    slotX: Int,
    slotY: Int,
    width: Int,
    height: Int,
    colorMatrix: JsAny,
    x: Int,
    y: Int,
): Unit = js("shared.drawColorMatrix(texture, slotX, slotY, width, height, colorMatrix, x, y)")

// language=js
private fun sharedBlurImage(
    shared: JsAny,
    texture: JsAny,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    rx0: Int,
    rx1: Int,
    rx2: Int,
    ry0: Int,
    ry1: Int,
    ry2: Int,
    outX: Int,
    outY: Int,
    border: Int,
): Unit = js("shared.blurImage(texture, x, y, width, height, rx0, rx1, rx2, ry0, ry1, ry2, outX, outY, border)")

// language=js
private fun sharedEndBatch(shared: JsAny): Unit = js("shared.endBatch()")

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
    mode: Int,
    vertexCount: Int,
    indexCount: Int,
    positions: (Int) -> JsAny,
    colors: (Int) -> JsAny,
    indices: (Int) -> JsAny,
    a: Float,
    b: Float,
    c: Float,
    d: Float,
    e: Float,
    f: Float,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    alpha: Float,
    filterKind: Int,
    colorMatrix: JsAny?,
    tint: Int,
    tintMode: Int,
): JsAny? = js(
    "shared.drawTriangles(mode, vertexCount, indexCount, positions, colors, indices, a, b, c, d, e, f, left, top, width, height, alpha, filterKind, colorMatrix, tint, tintMode)"
)
