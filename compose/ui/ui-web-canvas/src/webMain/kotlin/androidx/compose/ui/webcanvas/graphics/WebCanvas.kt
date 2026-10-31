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

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.webcanvas.dom.CanvasElement
import androidx.compose.ui.webcanvas.dom.CanvasFeatures
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.CssColors
import androidx.compose.ui.webcanvas.dom.ImageData
import androidx.compose.ui.webcanvas.dom.JsStrings
import androidx.compose.ui.webcanvas.dom.addRoundRect
import androidx.compose.ui.webcanvas.dom.clearLineDash
import androidx.compose.ui.webcanvas.dom.createCanvas
import androidx.compose.ui.webcanvas.dom.createElementCanvas
import androidx.compose.ui.webcanvas.dom.getContext2D
import androidx.compose.ui.webcanvas.dom.requireContext2D
import androidx.compose.ui.webcanvas.dom.setLineDash
import androidx.compose.ui.webcanvas.dom.setPatternTransform
import androidx.compose.ui.webcanvas.dom.toJsNumberArray
import androidx.compose.ui.webcanvas.path.PathData
import androidx.compose.ui.webcanvas.path.WebPath
import androidx.compose.ui.webcanvas.path.WebPathEffect
import androidx.compose.ui.webcanvas.path.asRect
import androidx.compose.ui.webcanvas.path.asRoundRect
import androidx.compose.ui.webcanvas.path.asWebPath
import androidx.compose.ui.webcanvas.path.toPath2D
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.toJsString
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The [Canvas] of the web backend: it draws immediately into a Canvas 2D context.
 *
 * The Canvas 2D API differs from Skia in a few ways that this class bridges:
 * - there are no layers: [saveLayer] redirects the drawing into a pooled offscreen canvas, which
 *   is composited back with the layer paint on the matching [restore];
 * - transformations and clip bounds are tracked here, so that the context is only told about them
 *   when drawing (and the clip is the only state that needs `ctx.save()`/`ctx.restore()`);
 * - a clip to a rounded rectangle is applied only for the draws that may reach out of it (see
 *   [DeferredClip]);
 * - paint state is applied lazily and only when it changes (see [DrawTarget]);
 * - some blend modes affect the whole clip in Canvas 2D, so their draws are clipped to the shape.
 */
internal class WebCanvas(root: DrawTarget) : Canvas {

    private var target: DrawTarget = root

    /** The current transformation, from the local space to the device space of the root. */
    private val matrix = Affine()

    // The device-space bounds of the current clip.
    private var clipLeft = root.originX.toFloat()
    private var clipTop = root.originY.toFloat()
    private var clipRight = (root.originX + root.width).toFloat()
    private var clipBottom = (root.originY + root.height).toFloat()

    /** Multiplies the alpha of everything drawn (used for `CompositingStrategy.ModulateAlpha`). */
    private var alphaMultiplier = 1f

    /** Whether drawing is skipped, e.g. inside a layer that is clipped out entirely. */
    private var skipDrawing = false

    private val stack = ArrayList<SaveEntry>()
    private var depth = 0

    /** The `ctx.save()` calls made on the root target before any [save]. */
    private var rootContextSaves = 0

    /**
     * A clip to a rounded rectangle not applied to the context yet: applied by the draws that may
     * reach out of it (see [clipBefore]).
     */
    private var deferredClip: DeferredClip? = null

    /**
     * Whether the context is saved and clipped to [deferredClip] for the last draw, which covered
     * all of it (see [clipBefore]): until the next draw tells whether the clip holds for it too, or
     * the clips of the context change.
     */
    private var isClipOpen = false

    /** The rounded rectangle of a clip path (see [clipPathData]). */
    private val clipRoundRect = FloatArray(12)

    /** The device bounds of a draw, to compare with [deferredClip]. */
    private val clipTestBounds = FloatArray(4)

    private val tmpRect = FloatArray(4)

    // The device pixels of the layer being saved (see computeLayerBounds).
    private var layerLeft = 0
    private var layerTop = 0
    private var layerRight = 0
    private var layerBottom = 0

    private class SaveEntry {
        val matrix = Affine()
        var clipLeft = 0f
        var clipTop = 0f
        var clipRight = 0f
        var clipBottom = 0f
        var alphaMultiplier = 1f
        var skipDrawing = false
        var target: DrawTarget? = null
        var deferredClip: DeferredClip? = null
        /** `ctx.save()` calls made on the current target since this entry was pushed. */
        var contextSaves = 0
        var layer: Layer? = null
        /**
         * The job of the layer this entry saved: noted by the plan while planning, or, while
         * drawing, a job whose content is drawn where it's met.
         */
        var job: FramePlan.Job? = null
    }

    /** A layer being drawn: where its content is, and what to composite it with on [restore]. */
    private class Layer(
        /** The canvas the content is drawn into, at ([sourceX], [sourceY]). */
        val source: CanvasElement,
        val sourceCtx: Context2D,
        val sourceX: Int,
        val sourceY: Int,
        /** The pooled canvas of the content, or `null` for a slot of an atlas page. */
        val lease: LayerCanvasPool.Lease?,
        val paint: WebPaint,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        /**
         * The job of the layer, if it was drawn ahead, with what its effect prepared ahead, if it
         * has one (see [Effects.plan]).
         */
        val job: FramePlan.Job?,
    ) {
        /**
         * Whether the content was drawn ahead, its color filter included (applied a level at a time
         * by [FrameRenderer]).
         */
        val isDrawnAhead: Boolean get() = lease == null
    }

    /**
     * Set while this canvas plans a frame: it follows the state and notes the layers that need an
     * offscreen buffer, but draws nothing (see [FramePlan]).
     */
    private var plan: FramePlan? = null

    private val isPlanning: Boolean get() = plan != null

    /** Whether draws draw nothing: while [skipDrawing], and while planning. */
    private val drawsNothing: Boolean get() = skipDrawing || isPlanning

    /** Matches the layers this canvas meets with the jobs drawn ahead of the frame. */
    private val jobMatcher = JobMatcher()

    /** Whether a layer was met that the plan doesn't have (see [JobMatcher.isBroken]). */
    internal val isPlanBroken: Boolean get() = jobMatcher.isBroken

    /** Plans a frame with this canvas until [finish] (see [plan]). */
    internal fun startPlanning(plan: FramePlan) {
        this.plan = plan
    }

    /** Composites the layers of [jobs] from their slots when they're met, until [finish]. */
    internal fun useJobs(jobs: List<FramePlan.Job>) {
        jobMatcher.start(jobs)
    }

    /**
     * Draws the content of [job] into its slot of [page], with the state the plan noted. Returns
     * `false` if the content met a layer that the plan doesn't have.
     */
    internal fun drawJob(job: FramePlan.Job, page: AtlasPage): Boolean {
        val pageTarget = page.target
        // The slot shows the device pixels of the layer.
        pageTarget.originX = job.left - job.slotX
        pageTarget.originY = job.top - job.slotY
        reset(pageTarget)
        matrix.set(job.matrix)
        clipToDevicePixels(job.left, job.top, job.left + job.width, job.top + job.height)
        useJobs(job.children)
        try {
            job.replayContent(this)
        } finally {
            finish()
        }
        return !isPlanBroken
    }

    /** Draws into [newRoot] from now on, starting from a clean state. */
    fun reset(newRoot: DrawTarget) {
        finish()
        target = newRoot
        matrix.reset()
        clipLeft = newRoot.originX.toFloat()
        clipTop = newRoot.originY.toFloat()
        clipRight = (newRoot.originX + newRoot.width).toFloat()
        clipBottom = (newRoot.originY + newRoot.height).toFloat()
        alphaMultiplier = 1f
        skipDrawing = false
    }

    /**
     * Unwinds all pending saves and layers, so that the context is left in its initial state, and
     * ends planning or compositing jobs.
     */
    fun finish() {
        while (depth > 0) restore()
        closeOpenClip()
        if (rootContextSaves > 0) {
            repeat(rootContextSaves) { target.restore() }
            rootContextSaves = 0
        }
        deferredClip = null
        plan = null
        jobMatcher.stop()
    }

    // ---------------------------------------------------------------------------------------------
    // State

    override fun save() {
        val entry = if (depth < stack.size) stack[depth] else SaveEntry().also { stack.add(it) }
        depth++
        entry.matrix.set(matrix)
        entry.clipLeft = clipLeft
        entry.clipTop = clipTop
        entry.clipRight = clipRight
        entry.clipBottom = clipBottom
        entry.alphaMultiplier = alphaMultiplier
        entry.skipDrawing = skipDrawing
        entry.target = target
        entry.deferredClip = deferredClip
        entry.contextSaves = 0
        entry.layer = null
        entry.job = null
    }

    override fun restore() {
        if (depth == 0) return
        // The clip may not hold anymore, and the saves of the depth are undone below.
        closeOpenClip()
        depth--
        val entry = stack[depth]
        if (entry.contextSaves > 0) {
            repeat(entry.contextSaves) { target.restore() }
        }
        val job = entry.job
        if (job != null) {
            entry.job = null
            val plan = plan
            if (plan != null) plan.closeJob() else jobMatcher.leave(job)
        }
        val layer = entry.layer
        entry.layer = null
        val previousTarget = entry.target!!
        entry.target = null
        target = previousTarget
        deferredClip = entry.deferredClip
        entry.deferredClip = null
        matrix.set(entry.matrix)
        clipLeft = entry.clipLeft
        clipTop = entry.clipTop
        clipRight = entry.clipRight
        clipBottom = entry.clipBottom
        alphaMultiplier = entry.alphaMultiplier
        skipDrawing = entry.skipDrawing
        if (layer != null) {
            compositeLayer(layer)
        }
    }

    override fun saveLayer(bounds: Rect, paint: Paint) {
        // Like Skia, the layer is composited with the paint as it is now: the caller may change it
        // before the matching restore, e.g. to draw the content.
        saveLayer(
            bounds.left, bounds.top, bounds.right, bounds.bottom, paint.asWebPaint().snapshot(),
            content = null,
            contentOp = DisplayList.WholeList,
        )
    }

    /**
     * Saves a layer, whose content is the following draws up to the matching [restore], composited
     * with [paint], which must not change until then. When the content is recorded, [content] and
     * [contentOp] identify it (see [DisplayList.replayContent]), so that a plan can draw it ahead of
     * the frame.
     *
     * Returns whether the content is to be drawn: not if the layer is clipped out, nor if its
     * content was drawn ahead.
     */
    internal fun saveLayer(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: WebPaint,
        content: DisplayList?,
        contentOp: Int,
    ): Boolean {
        save()
        if (skipDrawing) return false
        val outsets = tmpRect
        val effect = paint.renderEffect
        if (effect != null) effect.outsets(outsets) else outsets.fill(0f)
        val inLayer = computeLayerBounds(
            left - outsets[0], top - outsets[1], right + outsets[2], bottom + outsets[3],
        )
        if (!inLayer) {
            skipDrawing = true
            return false
        }
        val width = layerRight - layerLeft
        val height = layerBottom - layerTop
        // Too large for an offscreen canvas: drawn directly, without the layer paint.
        if (width > MaxLayerSize || height > MaxLayerSize) return true
        // The content shows within the pixels of the layer only.
        intersectClipBounds(
            layerLeft.toFloat(), layerTop.toFloat(), layerRight.toFloat(), layerBottom.toFloat(),
        )
        alphaMultiplier = 1f
        val entry = stack[depth - 1]
        val plan = plan
        if (plan != null) {
            if (content != null) {
                val job = plan.openJob(content, contentOp, layerLeft, layerTop, width, height, matrix, paint)
                // The effect of a layer composited from a copy with its color filter reads the copy.
                if (svgColorFilterOf(paint) == null) {
                    Effects.plan(paint.renderEffect, matrix.averageScale, width, height, job)
                }
                entry.job = job
            }
            return true
        }
        val job = jobMatcher.take(content, contentOp, layerLeft, layerTop, width, height)
        val page = job?.page
        if (page != null) {
            entry.layer =
                layerOf(page.canvas, page.target.ctx, job.slotX, job.slotY, lease = null, paint, job)
            skipDrawing = true
            return false
        }
        if (job != null) {
            // The atlas had no room for it: its content is drawn here, with the jobs in it.
            entry.job = job
            jobMatcher.enter(job)
        }
        val lease = LayerCanvasPool.acquire(width, height)
        entry.layer = layerOf(lease.canvas, lease.ctx, 0, 0, lease, paint, job = null)
        // The clips of the target apply to the layer where it's composited into it.
        closeOpenClip()
        deferredClip = null
        target = DrawTarget(lease.ctx, layerLeft, layerTop, width, height)
        return true
    }

    /** The layer being saved, with its content at ([x], [y]) of [source]. */
    private fun layerOf(
        source: CanvasElement,
        sourceCtx: Context2D,
        x: Int,
        y: Int,
        lease: LayerCanvasPool.Lease?,
        paint: WebPaint,
        job: FramePlan.Job?,
    ) = Layer(
        source = source,
        sourceCtx = sourceCtx,
        sourceX = x,
        sourceY = y,
        lease = lease,
        paint = paint,
        left = layerLeft,
        top = layerTop,
        width = layerRight - layerLeft,
        height = layerBottom - layerTop,
        job = job,
    )

    /**
     * The equivalent of [saveLayer] with a paint that has nothing but an [alpha], for content that
     * covers nothing of itself (see [DisplayList.canModulateAlpha]): instead of going through an
     * offscreen canvas, the content is clipped to the device pixels the layer would cover and drawn
     * with the alpha, which gives the same pixels.
     */
    internal fun saveAlphaLayer(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        alpha: Float,
    ) {
        save()
        if (skipDrawing) return
        if (!computeLayerBounds(left, top, right, bottom)) {
            skipDrawing = true
            return
        }
        alphaMultiplier *= alpha
        clipToDevicePixels(layerLeft, layerTop, layerRight, layerBottom)
    }

    /**
     * Sets the layer bounds to the device pixels covered by the given rectangle (in local
     * coordinates), cut to the clip. Returns `false` if there are none.
     */
    private fun computeLayerBounds(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        matrix.mapRect(left, top, right, bottom, tmpRect)
        layerLeft = floor(max(tmpRect[0], clipLeft)).toInt()
        layerTop = floor(max(tmpRect[1], clipTop)).toInt()
        layerRight = ceil(min(tmpRect[2], clipRight)).toInt()
        layerBottom = ceil(min(tmpRect[3], clipBottom)).toInt()
        return layerRight > layerLeft && layerBottom > layerTop
    }

    /**
     * The color filter that [paint] applies, if it applies as an SVG filter (see
     * [SvgColorFilters.appliesThroughSvg]): when compositing, or else through a copy of the layer.
     */
    private fun svgColorFilterOf(paint: WebPaint): WebColorFilter? =
        paint.appliedColorFilter?.takeIf { SvgColorFilters.appliesThroughSvg(it) }

    private fun compositeLayer(layer: Layer) {
        val paint = layer.paint
        // A color filter applies in place (tints as a compositing operation, or through the pixels
        // without SVG filters), or as an SVG filter: on the composite into a target that resolves
        // it (all those of this module do, not an `OffscreenCanvas` given to render into), if
        // nothing else filters the composite, or else on a copy of the layer.
        if (!layer.isDrawnAhead) {
            applyLayerColorFilterInPlace(
                paint, layer.sourceCtx, layer.sourceX, layer.sourceY, layer.width, layer.height,
            )
        }
        val svgFilter = svgColorFilterOf(paint)
        val compositeFilter =
            if (svgFilter != null && paint.renderEffect == null) {
                SvgColorFilters.filterValue(svgFilter, target.ctx)
            } else {
                null
            }
        val copy =
            if (svgFilter != null && compositeFilter == null) filteredCopy(layer, svgFilter) else null
        if (!skipDrawing && paint.blendMode != BlendMode.Dst) {
            if (copy != null) {
                composite(layer, copy.canvas, 0, 0, filter = null)
            } else {
                composite(layer, layer.source, layer.sourceX, layer.sourceY, compositeFilter)
            }
        }
        copy?.let { LayerCanvasPool.release(it) }
        layer.lease?.let { LayerCanvasPool.release(it) }
    }

    /**
     * Draws the content of [layer], at ([sourceX], [sourceY]) of [source], into the target with
     * the layer paint and through the SVG [filter].
     */
    private fun composite(
        layer: Layer,
        source: JsAny,
        sourceX: Int,
        sourceY: Int,
        filter: JsString?,
    ) {
        val paint = layer.paint
        val t = target
        clipBefore(
            layer.left.toFloat(), layer.top.toFloat(),
            (layer.left + layer.width).toFloat(), (layer.top + layer.height).toFloat(),
        )
        t.onDraw?.invoke()
        t.setPixelTransform()
        t.setGlobalAlpha((paint.color.alpha * alphaMultiplier).toDouble())
        val operation = compositeOperationFor(paint.blendMode) ?: SourceOverOperation
        val sx = sourceX.toDouble()
        val sy = sourceY.toDouble()
        val dx = (layer.left - t.originX).toDouble()
        val dy = (layer.top - t.originY).toDouble()
        val w = layer.width.toDouble()
        val h = layer.height.toDouble()
        val effect = paint.renderEffect
        val ctx = t.ctx
        if (effect == null && filter == null && !isUnboundedBlendMode(paint.blendMode)) {
            t.setCompositeOperation(operation)
            ctx.drawImage(source, sx, sy, w, h, dx, dy, w, h)
            return
        }
        // Keep the composite inside the layer bounds, like Skia does (a color matrix can make
        // transparent pixels opaque). The clip also bounds the work of a filter, which would
        // otherwise process the whole target.
        t.save()
        ctx.beginPath()
        ctx.rect(dx, dy, w, h)
        ctx.clip(JsStrings.nonzero)
        t.setCompositeOperation(operation)
        if (effect != null) {
            Effects.draw(
                ctx, effect, source, sourceX, sourceY, layer.width, layer.height, matrix,
                layer.left, layer.top, layer.job, dx, dy,
            )
        } else {
            if (filter != null) ctx.filter = filter
            ctx.drawImage(source, sx, sy, w, h, dx, dy, w, h)
        }
        t.restore()
    }

    /** A copy of the layer content through the SVG filter of [filter], to release once used. */
    private fun filteredCopy(layer: Layer, filter: WebColorFilter): LayerCanvasPool.Lease {
        val w = layer.width.toDouble()
        val h = layer.height.toDouble()
        val copy = LayerCanvasPool.acquire(layer.width, layer.height)
        copy.ctx.filter = checkNotNull(SvgColorFilters.filterValue(filter, copy.ctx)) {
            "A canvas element resolves SVG filters"
        }
        copy.ctx.drawImage(
            layer.source, layer.sourceX.toDouble(), layer.sourceY.toDouble(), w, h, 0.0, 0.0, w, h,
        )
        copy.ctx.filter = JsStrings.none
        return copy
    }

    override fun translate(dx: Float, dy: Float) {
        matrix.preTranslate(dx, dy)
    }

    override fun scale(sx: Float, sy: Float) {
        matrix.preScale(sx, sy)
    }

    override fun rotate(degrees: Float) {
        matrix.preRotate(degrees * (PI.toFloat() / 180f))
    }

    override fun skew(sx: Float, sy: Float) {
        matrix.preSkew(sx, sy)
    }

    private val tmpAffine = Affine()

    override fun concat(matrix: Matrix) {
        concat(matrix.toAffine(tmpAffine))
    }

    internal fun concat(transform: Affine) {
        if (!transform.isIdentity) {
            matrix.preConcat(transform)
        }
    }

    internal fun concat(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
        concat(tmpAffine.set(a, b, c, d, e, f))
    }

    /** The current transformation; must not be modified. */
    internal val currentMatrix: Affine get() = matrix

    /** Multiplies the alpha of everything drawn until the matching [restore]. */
    internal fun modulateAlpha(alpha: Float) {
        alphaMultiplier *= alpha
    }

    /**
     * Draws the triangles of [vertices] with their vertex colors interpolated over each triangle,
     * like Skia does, which the Canvas 2D API can't do: WebGL 2 draws them, taking the alpha and
     * the color filter of [paint] in Skia's order. They are then composited in the blend mode of
     * [paint], not Dst, a Clear punching them out.
     *
     * Returns `false` if WebGL 2 isn't available.
     */
    internal fun drawColoredVertices(vertices: Vertices, paint: WebPaint): Boolean {
        if (drawsNothing) return true
        if (Vertices2D.triangleCount(vertices) == 0) return true
        if (!WebGLRenderer.isAvailable()) return false

        // The device bounds of the vertices.
        val positions = vertices.positions
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until positions.size / 2) {
            val x = matrix.mapX(positions[2 * i], positions[2 * i + 1])
            val y = matrix.mapY(positions[2 * i], positions[2 * i + 1])
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        val deviceLeft = floor(max(minX, clipLeft)).toInt()
        val deviceTop = floor(max(minY, clipTop)).toInt()
        val deviceRight = ceil(min(maxX, clipRight)).toInt()
        val deviceBottom = ceil(min(maxY, clipBottom)).toInt()
        val width = deviceRight - deviceLeft
        val height = deviceBottom - deviceTop
        if (width <= 0 || height <= 0) return true

        // A Clear punches out the pixels of the mesh, whatever their colors (see applyColor).
        val clears = paint.blendMode == BlendMode.Clear
        // The mesh is composited at once: where its triangles overlap, the last one is there,
        // while Skia blends them one after the other.
        val image = WebGLRenderer.drawColoredTriangles(
            vertices, matrix, deviceLeft, deviceTop, width, height,
            alpha = if (clears) 1f else paint.color.alpha,
            colorFilter = if (clears) OpaqueCoverage else paint.appliedColorFilter,
        ) ?: return false
        clipBefore(deviceLeft.toFloat(), deviceTop.toFloat(), deviceRight.toFloat(), deviceBottom.toFloat())
        val t = target
        t.onDraw?.invoke()
        // Canvas 2D composites these modes over the whole clip: within the triangles, which Skia
        // only draws.
        val unbounded = isUnboundedBlendMode(paint.blendMode)
        if (unbounded) {
            t.save()
            t.setTransform(matrix)
            t.ctx.beginPath()
            Vertices2D.forEachTriangle(vertices) { i0, i1, i2 ->
                addTriangle(
                    positions[2 * i0], positions[2 * i0 + 1],
                    positions[2 * i1], positions[2 * i1 + 1],
                    positions[2 * i2], positions[2 * i2 + 1],
                )
            }
            t.ctx.clip(JsStrings.nonzero)
        }
        t.setPixelTransform()
        t.setGlobalAlpha(if (clears) 1.0 else alphaMultiplier.toDouble())
        t.setCompositeOperation(compositeOperationFor(paint.blendMode) ?: SourceOverOperation)
        val w = width.toDouble()
        val h = height.toDouble()
        t.ctx.drawImage(
            image,
            0.0,
            0.0,
            w,
            h,
            (deviceLeft - t.originX).toDouble(),
            (deviceTop - t.originY).toDouble(),
            w,
            h,
        )
        if (unbounded) t.restore()
        return true
    }

    /** Draws in the device space (keeping the clip) until the matching [restore]. */
    internal fun saveInDeviceSpace() {
        save()
        matrix.reset()
    }

    private val projective = Projective()

    // Where the content being drawn in perspective goes (see [placeInPerspective]): the device
    // pixels it covers and the matrix drawing its raster there, and its raster.
    private val perspectiveMatrix = Projective()
    private var perspectiveLeft = 0
    private var perspectiveTop = 0
    private var perspectiveWidth = 0
    private var perspectiveHeight = 0
    private var rasterWidth = 0
    private var rasterHeight = 0
    private val rasterMatrix = Affine()

    /**
     * Draws [content] transformed in perspective by [transform] (from the space of the content to
     * the current local space), which the Canvas 2D API can't do: the content that lies within the
     * bounds ([left], [top], [right], [bottom]) is drawn into a raster in its own space, which
     * WebGL 2 then draws in perspective, and the result is composited with [blendMode].
     *
     * Planning, the layer becomes a job of the plan (see [FramePlan.Job.isInPerspective]), whose
     * raster is drawn ahead of the frame, and drawn in perspective with the others of its level;
     * it's then composited from there, or else drawn where it's met.
     *
     * Returns `false` if WebGL 2 isn't available, or fails to draw it.
     */
    internal fun drawInPerspective(
        transform: Matrix,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        blendMode: BlendMode,
        content: JobContent,
    ): Boolean {
        if (skipDrawing || blendMode == BlendMode.Dst) return true
        if (right <= left || bottom <= top) return true
        if (!WebGLRenderer.isAvailable()) return false
        if (!placeInPerspective(transform, left, top, right, bottom)) return true
        val plan = plan
        if (plan != null) {
            planInPerspective(plan, content)
            return true
        }
        val job = jobMatcher.take(content, DisplayList.WholeList, 0, 0, rasterWidth, rasterHeight)
        if (job != null) {
            val projection = job.projection
            val projected = projection.owner?.let { SharedWebGL.canvasHolding(it, projection.version) }
            if (projected != null) {
                compositeProjected(
                    projected, projection.x, projection.y,
                    projection.left, projection.top, projection.width, projection.height, blendMode,
                )
                return true
            }
        }
        return drawHereInPerspective(content, blendMode)
    }

    /**
     * Places the content within ([left], [top], [right], [bottom]) transformed in perspective by
     * [transform] (see [drawInPerspective]): sets the pixels it covers and the matrix drawing its
     * raster there, and its raster, at the resolution of its most magnified part, within limits.
     * Returns `false` if the content covers no pixel.
     */
    private fun placeInPerspective(
        transform: Matrix,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Boolean {
        val device = projective.setFrom(transform).postConcat(matrix)
        val bounds = tmpRect
        if (!device.mapRectBounds(left, top, right, bottom, bounds)) return false
        val deviceLeft = floor(max(bounds[0], clipLeft)).toInt()
        val deviceTop = floor(max(bounds[1], clipTop)).toInt()
        val width = ceil(min(bounds[2], clipRight)).toInt() - deviceLeft
        val height = ceil(min(bounds[3], clipBottom)).toInt() - deviceTop
        if (width <= 0 || height <= 0) return false

        val contentWidth = right - left
        val contentHeight = bottom - top
        var scale = device.maxEdgeScale(left, top, right, bottom)
        if (scale.isNaN()) scale = matrix.averageScale
        scale = scale.coerceIn(MinPerspectiveScale, MaxPerspectiveScale)
        val maxSize = min(WebGLRenderer.maxTextureSize, MaxLayerSize) - 2
        scale = min(scale, min(maxSize / contentWidth, maxSize / contentHeight))
        val pixels = contentWidth * scale * contentHeight * scale
        if (pixels > MaxPerspectivePixels) scale *= sqrt(MaxPerspectivePixels / pixels)
        // A transparent pixel around the content blends its edges smoothly (antialiasing). The
        // limit holds through the rounding.
        rasterWidth = min(ceil(contentWidth * scale).toInt(), maxSize) + 2
        rasterHeight = min(ceil(contentHeight * scale).toInt(), maxSize) + 2
        if (rasterWidth <= 2 || rasterHeight <= 2) return false
        rasterMatrix.set(scale, 0f, 0f, scale, 1 - left * scale, 1 - top * scale)

        perspectiveLeft = deviceLeft
        perspectiveTop = deviceTop
        perspectiveWidth = width
        perspectiveHeight = height
        // Map the texture coordinates of the raster to the content space, then to the device.
        val pixel = 1.0 / scale
        perspectiveMatrix.set(device).preScaleTranslate(
            sx = rasterWidth * pixel,
            sy = rasterHeight * pixel,
            tx = left - pixel,
            ty = top - pixel,
        )
        return true
    }

    /**
     * Notes the content placed in perspective as a job of [plan], and plans the content in the
     * space of its raster, where the layers in it are jobs too.
     */
    private fun planInPerspective(plan: FramePlan, content: JobContent) {
        save()
        val job = plan.openJob(
            content, DisplayList.WholeList, 0, 0, rasterWidth, rasterHeight, rasterMatrix,
            paint = null,
            isInPerspective = true,
        )
        stack[depth - 1].job = job
        val projection = job.projection
        projection.uvMatrix.set(perspectiveMatrix)
        projection.left = perspectiveLeft
        projection.top = perspectiveTop
        projection.width = perspectiveWidth
        projection.height = perspectiveHeight
        matrix.set(rasterMatrix)
        clipLeft = 0f
        clipTop = 0f
        clipRight = rasterWidth.toFloat()
        clipBottom = rasterHeight.toFloat()
        alphaMultiplier = 1f
        content.replayContent(this, DisplayList.WholeList)
        restore()
    }

    /**
     * Draws the content placed in perspective here: into an offscreen canvas, which WebGL draws
     * in perspective. Returns `false` if WebGL fails.
     */
    private fun drawHereInPerspective(content: JobContent, blendMode: BlendMode): Boolean {
        val lease = LayerCanvasPool.acquire(rasterWidth, rasterHeight)
        val contentCanvas = WebCanvas(DrawTarget(lease.ctx, 0, 0, rasterWidth, rasterHeight))
        contentCanvas.concat(rasterMatrix)
        content.replayContent(contentCanvas, DisplayList.WholeList)
        contentCanvas.finish()
        val image = WebGLRenderer.drawImageInPerspective(
            source = lease.canvas,
            sourceWidth = rasterWidth,
            sourceHeight = rasterHeight,
            uvMatrix = perspectiveMatrix,
            left = perspectiveLeft,
            top = perspectiveTop,
            width = perspectiveWidth,
            height = perspectiveHeight,
        )
        if (image != null) {
            compositeProjected(
                image, 0, 0,
                perspectiveLeft, perspectiveTop, perspectiveWidth, perspectiveHeight, blendMode,
            )
        }
        LayerCanvasPool.release(lease)
        return image != null
    }

    /**
     * Composites the [width] x [height] pixels at ([x], [y]) of [image], drawn in perspective, at
     * ([left], [top]) of the device space, with [blendMode].
     */
    private fun compositeProjected(
        image: JsAny,
        x: Int,
        y: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        blendMode: BlendMode,
    ) {
        clipBefore(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat())
        val t = target
        t.onDraw?.invoke()
        t.setPixelTransform()
        t.setGlobalAlpha(alphaMultiplier.toDouble())
        t.setCompositeOperation(compositeOperationFor(blendMode) ?: SourceOverOperation)
        val w = width.toDouble()
        val h = height.toDouble()
        val dx = (left - t.originX).toDouble()
        val dy = (top - t.originY).toDouble()
        val ctx = t.ctx
        // Canvas 2D composites these modes over the whole clip: within the layer bounds, as the
        // layers drawn flat (see composite).
        val unbounded = isUnboundedBlendMode(blendMode)
        if (unbounded) {
            t.save()
            ctx.beginPath()
            ctx.rect(dx, dy, w, h)
            ctx.clip(JsStrings.nonzero)
        }
        ctx.drawImage(image, x.toDouble(), y.toDouble(), w, h, dx, dy, w, h)
        if (unbounded) t.restore()
    }

    // ---------------------------------------------------------------------------------------------
    // Clip

    /**
     * Saves the context once at the depth [at] of the stack, so that the clips applied then are
     * undone when it's restored. A clip [open][isClipOpen] for a draw is closed first: a clip goes
     * into the last save of the context.
     */
    private fun ensureContextSaved(at: Int = depth) {
        closeOpenClip()
        if (at == 0) {
            if (rootContextSaves == 0) {
                target.save()
                rootContextSaves = 1
            }
        } else {
            val entry = stack[at - 1]
            if (entry.contextSaves == 0) {
                target.save()
                entry.contextSaves = 1
            }
        }
    }

    /** The `ctx.save()` calls made at the depth [at] of the stack. */
    private fun contextSavesAt(at: Int): Int = if (at == 0) rootContextSaves else stack[at - 1].contextSaves

    /**
     * Applies the deferred clip before a draw within the device rectangle ([left], [top], [right],
     * [bottom]) that may reach out of its shape. A draw covering all of the shape, a background of
     * what's clipped, is clipped on its own, in a save of the context left open: the next draw keeps
     * the clip if it needs it too, or else closes it.
     *
     * Draws call it before setting up the context: closing an open clip restores the state of the
     * context, and a save taken with transient state (shadows, filters, dashes) set would bring it
     * back when restored.
     */
    private fun clipBefore(left: Float, top: Float, right: Float, bottom: Float) {
        val clip = deferredClip ?: return
        when {
            clip.contains(left, top, right, bottom) -> closeOpenClip()
            isClipOpen || !clip.isCoveredBy(left, top, right, bottom) -> applyDeferredClip(clip)
            else -> {
                target.save()
                clip.applyTo(target)
                isClipOpen = true
            }
        }
    }

    /**
     * [clipBefore] a draw of the local rectangle ([left], [top], [right], [bottom]) with [paint]:
     * with its stroke, antialiasing and blur.
     */
    private fun clipBeforeDrawing(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: WebPaint,
        stroke: Boolean,
    ) {
        if (deferredClip == null) return
        val bounds = drawnBoundsOf(left, top, right, bottom, paint, stroke)
        clipBefore(bounds[0], bounds[1], bounds[2], bounds[3])
    }

    /**
     * The device bounds of a draw of the local rectangle ([left], [top], [right], [bottom]) with
     * [paint], with its stroke, antialiasing and blur, in [clipTestBounds].
     */
    private fun drawnBoundsOf(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: WebPaint,
        stroke: Boolean,
    ): FloatArray {
        val bounds = clipTestBounds
        mapDrawBounds(left, top, right, bottom, paint, stroke, bounds)
        val blur = paint.blurFilter
        if (blur != null && blur.radius > 0f) {
            val extent = blurExtent(blur.sigma * matrix.averageScale)
            bounds[0] -= extent
            bounds[1] -= extent
            bounds[2] += extent
            bounds[3] += extent
        }
        return bounds
    }

    /**
     * Applies [clip], the deferred clip, for good: at the shallowest depth since it was set below
     * which the context isn't saved, so that it holds for the draws that follow as if applied when
     * set, until that depth is restored. A clip open for the last draw is kept, not applied again.
     */
    private fun applyDeferredClip(clip: DeferredClip) {
        var at = depth
        while (at > clip.depth && contextSavesAt(at) == 0) at--
        if (isClipOpen) {
            // The last save of the context becomes one of the depth.
            isClipOpen = false
            if (at == 0) rootContextSaves++ else stack[at - 1].contextSaves++
        } else {
            ensureContextSaved(at)
            clip.applyTo(target)
        }
        deferredClip = null
        for (i in at until depth) stack[i].deferredClip = null
    }

    /** Closes the clip [open][isClipOpen] for the last draw: it stays deferred. */
    private fun closeOpenClip() {
        if (isClipOpen) {
            isClipOpen = false
            target.restore()
        }
    }

    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float, clipOp: ClipOp) {
        if (skipDrawing) return
        if (clipOp == ClipOp.Intersect) {
            matrix.mapRect(left, top, right, bottom, tmpRect)
            val bounds = tmpRect
            if (matrix.isAxisAligned && containsDeviceClip(bounds[0], bounds[1], bounds[2], bounds[3])) {
                // The clip wouldn't remove anything.
                return
            }
            intersectClipBounds(tmpRect[0], tmpRect[1], tmpRect[2], tmpRect[3])
            if (drawsNothing) return
            ensureContextSaved()
            target.setTransform(matrix)
            val ctx = target.ctx
            ctx.beginPath()
            ctx.rect(
                left.toDouble(),
                top.toDouble(),
                (right - left).toDouble(),
                (bottom - top).toDouble(),
            )
            ctx.clip(JsStrings.nonzero)
        } else {
            val path = WebPath()
            path.addRect(Rect(left, top, right, bottom))
            clipDifference(path.snapshot())
        }
    }

    override fun clipPath(path: Path, clipOp: ClipOp) {
        clipPathData(path.toPathData(), clipOp)
    }

    internal fun clipPathData(data: PathData, clipOp: ClipOp) {
        if (skipDrawing) return
        if (clipOp == ClipOp.Intersect) {
            val rect = data.asRect()
            if (rect != null && data.fillType == PathFillType.NonZero) {
                clipRect(rect.left, rect.top, rect.right, rect.bottom, ClipOp.Intersect)
                return
            }
            if (data.isEmpty && data.fillType == PathFillType.NonZero) {
                skipDrawing = true
                clipRight = clipLeft
                clipBottom = clipTop
                return
            }
            val bounds = data.bounds
            matrix.mapRect(bounds.left, bounds.top, bounds.right, bounds.bottom, tmpRect)
            if (data.fillType == PathFillType.NonZero || data.fillType == PathFillType.EvenOdd) {
                intersectClipBounds(tmpRect[0], tmpRect[1], tmpRect[2], tmpRect[3])
            }
            if (drawsNothing) return
            if (matrix.isAxisAligned && data.asRoundRect(clipRoundRect)) {
                // One clip deferred at a time: an older one is applied.
                deferredClip?.let { applyDeferredClip(it) }
                deferredClip = DeferredClip(data, matrix, depth, clipRoundRect)
                return
            }
            ensureContextSaved()
            target.setTransform(matrix)
            target.ctx.clip(data.toPath2D(), data.fillType.toJs())
        } else {
            clipDifference(data)
        }
    }

    /** Clips out the area of [data], using an even-odd path made of a bounding rect and [data]. */
    private fun clipDifference(data: PathData) {
        // The clip bounds don't shrink: planning has nothing to note.
        if (isPlanning || data.isEmpty) return
        val inverse = tmpAffine.set(matrix)
        if (!inverse.invert()) return
        // A rectangle covering the target, in local coordinates.
        inverse.mapRect(
            target.originX.toFloat() - 1,
            target.originY.toFloat() - 1,
            (target.originX + target.width).toFloat() + 1,
            (target.originY + target.height).toFloat() + 1,
            tmpRect,
        )
        val combined = rectWithPath2D(
            tmpRect[0].toDouble(),
            tmpRect[1].toDouble(),
            tmpRect[2].toDouble(),
            tmpRect[3].toDouble(),
            data.toPath2D(),
        )
        ensureContextSaved()
        target.setTransform(matrix)
        target.ctx.clip(combined, JsStrings.evenodd)
    }

    /** Clips to the given device pixels. */
    private fun clipToDevicePixels(left: Int, top: Int, right: Int, bottom: Int) {
        val l = left.toFloat()
        val t = top.toFloat()
        val r = right.toFloat()
        val b = bottom.toFloat()
        if (containsDeviceClip(l, t, r, b)) return
        intersectClipBounds(l, t, r, b)
        if (drawsNothing) return
        ensureContextSaved()
        val target = target
        target.setPixelTransform()
        val ctx = target.ctx
        ctx.beginPath()
        ctx.rect(
            (left - target.originX).toDouble(),
            (top - target.originY).toDouble(),
            (right - left).toDouble(),
            (bottom - top).toDouble(),
        )
        ctx.clip(JsStrings.nonzero)
    }

    /** Whether the device rectangle contains the clip bounds. */
    private fun containsDeviceClip(left: Float, top: Float, right: Float, bottom: Float): Boolean =
        left <= clipLeft && top <= clipTop && right >= clipRight && bottom >= clipBottom

    /** Cuts the clip bounds to the device rectangle. */
    private fun intersectClipBounds(left: Float, top: Float, right: Float, bottom: Float) {
        clipLeft = max(clipLeft, left)
        clipTop = max(clipTop, top)
        clipRight = min(clipRight, right)
        clipBottom = min(clipBottom, bottom)
        if (clipLeft >= clipRight || clipTop >= clipBottom) {
            skipDrawing = true
        }
    }

    /** Whether the device bounds of the local rectangle are entirely outside the clip. */
    internal fun quickReject(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        if (skipDrawing) return true
        matrix.mapRect(left, top, right, bottom, tmpRect)
        return tmpRect[2] <= clipLeft || tmpRect[0] >= clipRight ||
            tmpRect[3] <= clipTop || tmpRect[1] >= clipBottom
    }

    // ---------------------------------------------------------------------------------------------
    // Paint

    /**
     * Prepares the context to fill ([stroke] = false) or stroke with [paint]. Returns `false` if
     * the draw has no visible effect.
     */
    private fun applyPaint(
        paint: WebPaint,
        stroke: Boolean,
        canonicalShader: WebShader? = null,
    ): Boolean {
        // The bounds set for this draw only (see setDrawBounds).
        val isBounded = hasDrawBounds
        hasDrawBounds = false
        val blendMode = paint.blendMode
        if (blendMode == BlendMode.Dst) return false
        val t = target
        t.onDraw?.invoke()
        t.setTransform(matrix)
        val shader = paint.shader?.webShader
        if (shader != null && blendMode != BlendMode.Clear) {
            t.setCompositeOperation(compositeOperationFor(blendMode) ?: SourceOverOperation)
            val colorFilter = paint.appliedColorFilter
            val style = when {
                canonicalShader != null -> {
                    // Filled in the canonical space of the shader (see fillOrStroke).
                    styleSpace.set(matrix)
                    styleSpace.preConcat(canonicalShaderTransform)
                    fillInStyleSpace = true
                    canonicalShader.canonicalStyle()
                }
                // Without content, even through a color filter.
                !shader.mayNeedRasterization && shader.canvasStyle() == null -> null
                colorFilter != null || shader.mayNeedRasterization ->
                    shaderStyleForDraw(shader, colorFilter, isBounded)
                else -> shader.canvasStyle()
            }
            // Like Skia, a shader without content (e.g. an image not decoded yet) draws nothing,
            // rather than the paint color.
            if (style == null) return false
            val alpha = paint.color.alpha * alphaMultiplier
            if (alpha <= 0f && blendModeKeepsDestinationForTransparent(blendMode)) return false
            t.setGlobalAlpha(alpha.toDouble())
            if (stroke) t.setStrokeStyle(style) else t.setFillStyle(style)
        } else if (!applyColor(paint.argb, paint, stroke)) {
            return false
        }
        if (stroke) {
            val width = paint.strokeWidth
            t.setLineWidth(if (width > 0f) width.toDouble() else hairlineWidth())
            t.setLineCap(paint.strokeCap.toJs())
            t.setLineJoin(paint.strokeJoin.toJs())
            t.setMiterLimit(paint.strokeMiterLimit.toDouble())
        }
        return true
    }

    /**
     * Prepares the context to fill ([stroke] = false) or stroke with the color [argb] the way
     * [paint] draws its color: through its color filter and in its blend mode, a Clear punching the
     * shape out, with the alpha of the layers drawn into. Returns `false` if the draw has no visible
     * effect. The caller skips the draws in Dst, and sets the transformation up.
     */
    private fun applyColor(argb: Int, paint: WebPaint, stroke: Boolean): Boolean {
        val t = target
        val blendMode = paint.blendMode
        if (blendMode == BlendMode.Clear) {
            // Clearing: punch the shape out with an opaque color.
            t.setGlobalAlpha(1.0)
            t.setCompositeOperation(compositeOperationFor(BlendMode.Clear)!!)
            if (stroke) t.setStrokeColor(OpaqueBlack) else t.setFillColor(OpaqueBlack)
            return true
        }
        t.setCompositeOperation(compositeOperationFor(blendMode) ?: SourceOverOperation)
        var color = argb
        val colorFilter = paint.appliedColorFilter
        if (colorFilter != null) color = colorFilter.filter(color)
        if (alphaMultiplier < 1f) color = color.withAlphaMultiplied(alphaMultiplier)
        if ((color ushr 24) == 0 && blendModeKeepsDestinationForTransparent(blendMode)) return false
        t.setGlobalAlpha(1.0)
        if (stroke) t.setStrokeColor(color) else t.setFillColor(color)
        return true
    }

    /** The local stroke width of a one device pixel wide line. */
    private fun hairlineWidth(): Double {
        val scale = matrix.averageScale
        return if (scale > 0f) 1.0 / scale else 1.0
    }

    // The device bounds of the shape about to be drawn, if known, for shaders drawn as images.
    private val drawBounds = FloatArray(4)
    private var hasDrawBounds = false

    /** Sets the local bounds of the shape that the next [applyPaint] draws. */
    private fun setDrawBounds(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: WebPaint,
        stroke: Boolean,
    ) {
        mapDrawBounds(left, top, right, bottom, paint, stroke, drawBounds)
        hasDrawBounds = true
    }

    /**
     * Sets [out] to the device bounds of the local rectangle ([left], [top], [right], [bottom])
     * drawn with [paint], with its stroke and antialiasing.
     */
    private fun mapDrawBounds(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: WebPaint,
        stroke: Boolean,
        out: FloatArray,
    ) {
        // Like Skia's SkStrokeRec::GetInflationRadius: miter joins and square caps reach further
        // than half the width.
        val outset = if (stroke) {
            var reach = 1f
            if (paint.strokeJoin == StrokeJoin.Miter) reach = max(reach, paint.strokeMiterLimit)
            if (paint.strokeCap == StrokeCap.Square) reach = max(reach, sqrt(2f))
            max(paint.strokeWidth, 1f) * reach / 2f
        } else {
            0f
        }
        matrix.mapRect(left - outset, top - outset, right + outset, bottom + outset, out)
        // Antialiasing reaches into the next pixel.
        out[0] -= 1f
        out[1] -= 1f
        out[2] += 1f
        out[3] += 1f
    }

    // The transformation of the canonical style of a shader fill (see WebShader.canonicalStyle).
    private val canonicalShaderTransform = Affine()
    private val styleSpace = Affine()
    private var fillInStyleSpace = false

    /**
     * Returns the shader of [paint] if filling with it can use its canonical style (setting
     * [canonicalShaderTransform]), or `null`.
     */
    private fun canonicalFillShader(paint: WebPaint, stroke: Boolean): WebShader? {
        if (stroke || paint.appliedColorFilter != null) return null
        // Blurring through a shadow (without `ctx.filter`) moves the transformation itself.
        if (paint.blurFilter != null && !CanvasFeatures.filter) return null
        val shader = paint.shader?.webShader ?: return null
        if (shader.mayNeedRasterization) return null
        if (!shader.canonicalTransform(canonicalShaderTransform)) return null
        return if (shader.canonicalStyle() != null) shader else null
    }

    /** Whether drawing with [paint] may need its shader drawn as an image. */
    private fun mayRasterizeShader(paint: WebPaint): Boolean {
        val shader = paint.shader?.webShader ?: return false
        return shader.mayNeedRasterization || paint.appliedColorFilter != null
    }

    /**
     * Returns the fill style drawing [shader], filtered by [colorFilter], over the area of the
     * current draw (or the clip): an image of the shader over that area when no fill style can
     * express it. Returns `null` if nothing is visible.
     */
    private fun shaderStyleForDraw(
        shader: WebShader,
        colorFilter: WebColorFilter?,
        isBounded: Boolean,
    ): JsAny? {
        val bounds = tmpRect
        if (isBounded) {
            bounds[0] = max(drawBounds[0], clipLeft)
            bounds[1] = max(drawBounds[1], clipTop)
            bounds[2] = min(drawBounds[2], clipRight)
            bounds[3] = min(drawBounds[3], clipBottom)
        } else {
            bounds[0] = clipLeft
            bounds[1] = clipTop
            bounds[2] = clipRight
            bounds[3] = clipBottom
        }
        val left = floor(bounds[0]).toInt()
        val top = floor(bounds[1]).toInt()
        val width = ceil(bounds[2]).toInt() - left
        val height = ceil(bounds[3]).toInt() - top
        if (width <= 0 || height <= 0) return null
        if (colorFilter == null && !shader.needsRasterization(matrix, bounds)) {
            return shader.canvasStyle()
        }
        if (width > MaxLayerSize || height > MaxLayerSize) return shader.canvasStyle()
        val colorFilterKey = colorFilter?.key
        val version = shader.version
        val cached = shader.raster
        if (cached != null && cached.matches(matrix, left, top, width, height, colorFilterKey, version)) {
            return cached.pattern
        }
        val canvas = createCanvas(width, height)
        val readsPixels = colorFilter != null && colorFilterReadsPixels(colorFilter)
        val ctx = getContext2D(canvas, willReadFrequently = readsPixels) ?: return null
        val toRaster = matrix.copy()
        toRaster.e -= left
        toRaster.f -= top
        shader.rasterize(ctx, toRaster, width, height)
        var raster = canvas
        if (colorFilter != null) {
            ctx.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            raster = applyColorFilter(canvas, ctx, width, height, colorFilter)
        }
        val pattern = ctx.createPattern(raster, JsStrings.noRepeat) ?: return null
        // The pattern is in the local space of the draw: map its pixels back to the device area.
        val toLocal = matrix.copy()
        if (!toLocal.invert()) return null
        toLocal.preTranslate(left.toFloat(), top.toFloat())
        setPatternTransform(
            pattern,
            toLocal.a.toDouble(),
            toLocal.b.toDouble(),
            toLocal.c.toDouble(),
            toLocal.d.toDouble(),
            toLocal.e.toDouble(),
            toLocal.f.toDouble(),
        )
        shader.raster = ShaderRaster(matrix.copy(), left, top, width, height, colorFilterKey, version, pattern)
        return pattern
    }

    /**
     * Sets up the transient context state of a draw (blur, dashes) and returns a token to reset it
     * with [endEffects].
     */
    private fun beginEffects(paint: WebPaint, stroke: Boolean): Int {
        var flags = 0
        val blur = paint.blurFilter
        if (blur != null && blur.radius > 0f) {
            val sigma = blur.sigma * matrix.averageScale
            if (CanvasFeatures.filter) {
                target.ctx.filter = "blur(${sigma}px)".toJsString()
                flags = flags or EffectFilter
            } else {
                flags = flags or EffectShadowBlur
            }
        }
        if (stroke) {
            // Like Skia, an invalid dash (e.g. an odd number of intervals) is ignored.
            val dash = paint.pathEffect as? WebPathEffect.Dash
            if (dash != null && dash.isValid) {
                setLineDash(target.ctx, dash.intervals.toJsNumberArray())
                target.ctx.lineDashOffset = dash.adjustedPhase.toDouble()
                flags = flags or EffectDash
            }
        }
        return flags
    }

    private fun endEffects(flags: Int) {
        if (flags and EffectFilter != 0) target.ctx.filter = JsStrings.none
        if (flags and EffectDash != 0) {
            clearLineDash(target.ctx)
            target.ctx.lineDashOffset = 0.0
        }
    }

    /**
     * Fills or strokes the current path of the context, or [path2D] if given. Without `ctx.filter`,
     * the paint's blur is the shadow of an opaque copy (see [beginShadowOnly]): the shadow color
     * then applies the color and the alpha of the paint, once.
     */
    private fun drawWithPaint(paint: WebPaint, stroke: Boolean, path2D: JsAny?, fillRule: JsString) {
        val effects = beginEffects(paint, stroke)
        if (effects and EffectShadowBlur != 0) {
            val t = target
            t.setGlobalAlpha(1.0)
            if (stroke) t.setStrokeColor(OpaqueBlack) else t.setFillColor(OpaqueBlack)
            beginShadowOnly(
                t.ctx,
                paint.argb.withAlphaMultiplied(alphaMultiplier),
                (paint.blurFilter?.sigma ?: 0f) * matrix.averageScale,
                offsetX = 0.0,
                offsetY = 0.0,
                matrix.a, matrix.b, matrix.c, matrix.d,
                (matrix.e - t.originX).toDouble(), (matrix.f - t.originY).toDouble(),
            )
            t.invalidateTransform()
            fillOrStroke(stroke, path2D, fillRule)
            endShadowOnly(t.ctx)
        } else {
            fillOrStroke(stroke, path2D, fillRule)
        }
        endEffects(effects)
    }

    private fun fillOrStroke(stroke: Boolean, path2D: JsAny?, fillRule: JsString) {
        val ctx = target.ctx
        if (path2D == null) {
            if (fillInStyleSpace && !stroke) {
                // The current path keeps the coordinates it was built with: only the style is
                // interpreted in the transformation set now.
                target.setTransform(styleSpace)
            }
            fillInStyleSpace = false
            if (stroke) ctx.stroke() else ctx.fill(fillRule)
        } else {
            if (stroke) ctx.stroke(path2D) else ctx.fill(path2D, fillRule)
        }
    }

    /**
     * Draws a shape described by [buildPath] (which adds it to the context's current path) with
     * [paint], within the local bounds ([left], [top], [right], [bottom]), taking care of the special
     * cases: unbounded blend modes, shaders that can't absorb their transformation, and path effects
     * other than a plain dash.
     */
    private inline fun drawShape(
        paint: WebPaint,
        stroke: Boolean,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        crossinline buildPath: () -> Unit,
        crossinline pathData: () -> PathData,
    ) {
        if (drawsNothing) return
        val effect = paint.pathEffect as? WebPathEffect
        if (effect != null && (effect !is WebPathEffect.Dash || !stroke)) {
            // Path effects that change the geometry are applied to the path itself.
            val transformed = effect.apply(pathData(), stroke)
            drawPathData(transformed, paint, effect.resultIsStroke(stroke), applyPathEffect = false)
            return
        }
        val canonicalShader = canonicalFillShader(paint, stroke)
        if (canonicalShader == null) {
            val shaderTransform = paint.shader?.webShader?.styleTransform
            if (shaderTransform != null && !stroke) {
                fillThroughTransform(pathData(), paint, shaderTransform)
                return
            }
            if (mayRasterizeShader(paint)) {
                val data = pathData()
                setDrawBounds(data.left, data.top, data.right, data.bottom, paint, stroke)
            }
        }
        clipBeforeDrawing(left, top, right, bottom, paint, stroke)
        if (!applyPaint(paint, stroke, canonicalShader)) {
            fillInStyleSpace = false
            return
        }
        val ctx = target.ctx
        if (isUnboundedBlendMode(paint.blendMode)) {
            target.save()
            ctx.beginPath()
            buildPath()
            ctx.clip(JsStrings.nonzero)
            drawWithPaint(paint, stroke, null, JsStrings.nonzero)
            target.restore()
            return
        }
        ctx.beginPath()
        buildPath()
        drawWithPaint(paint, stroke, null, JsStrings.nonzero)
    }

    /**
     * Fills [data] with a shader whose fill style is defined in a different space: clips to the
     * shape, switches to the shader space and fills the area of the clip there.
     */
    private fun fillThroughTransform(data: PathData, paint: WebPaint, shaderTransform: Affine) {
        val shaderPaint = paint
        val ctx = target.ctx
        clipBeforeDrawing(data.left, data.top, data.right, data.bottom, paint, stroke = false)
        if (!applyPaint(shaderPaint, stroke = false)) return
        target.save()
        ctx.clip(data.toPath2D(), data.fillType.toJs())
        val space = shaderSpace.set(matrix)
        space.preConcat(shaderTransform)
        target.setTransform(space)
        // The clip in the shader space.
        if (space.invert()) {
            space.mapRect(clipLeft, clipTop, clipRight, clipBottom, tmpRect)
            ctx.fillRect(
                tmpRect[0].toDouble(),
                tmpRect[1].toDouble(),
                (tmpRect[2] - tmpRect[0]).toDouble(),
                (tmpRect[3] - tmpRect[1]).toDouble(),
            )
        }
        target.restore()
    }

    /** The space of the shader of [fillThroughTransform]. */
    private val shaderSpace = Affine()

    // ---------------------------------------------------------------------------------------------
    // Drawing

    override fun drawLine(p1: Offset, p2: Offset, paint: Paint) {
        drawLine(p1.x, p1.y, p2.x, p2.y, paint.asWebPaint())
    }

    internal fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, paint: WebPaint) {
        // Lines are always stroked, whatever the paint style.
        drawShape(
            paint,
            stroke = true,
            min(x1, x2),
            min(y1, y2),
            max(x1, x2),
            max(y1, y2),
            buildPath = {
                target.ctx.moveTo(x1.toDouble(), y1.toDouble())
                target.ctx.lineTo(x2.toDouble(), y2.toDouble())
            },
            pathData = {
                WebPath().apply {
                    moveTo(x1, y1)
                    lineTo(x2, y2)
                }.snapshot()
            },
        )
    }

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        drawRect(left, top, right, bottom, paint.asWebPaint())
    }

    internal fun drawRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: WebPaint,
        stroke: Boolean = paint.style == PaintingStyle.Stroke,
    ) {
        if (drawsNothing) return
        // Shader fills go through drawShape, which can fill with shared canonical gradients.
        val simple = paint.pathEffect == null && (paint.shader == null || stroke) &&
            !isUnboundedBlendMode(paint.blendMode) && paint.blurFilter == null &&
            !mayRasterizeShader(paint)
        val l = min(left, right)
        val t = min(top, bottom)
        val r = max(left, right)
        val b = max(top, bottom)
        if (simple) {
            clipBeforeDrawing(l, t, r, b, paint, stroke)
            if (!applyPaint(paint, stroke)) return
            val x = l.toDouble()
            val y = t.toDouble()
            val w = (r - l).toDouble()
            val h = (b - t).toDouble()
            if (stroke) target.ctx.strokeRect(x, y, w, h) else target.ctx.fillRect(x, y, w, h)
            return
        }
        drawShape(
            paint,
            stroke,
            l,
            t,
            r,
            b,
            buildPath = {
                target.ctx.rect(
                    left.toDouble(),
                    top.toDouble(),
                    (right - left).toDouble(),
                    (bottom - top).toDouble(),
                )
            },
            pathData = { WebPath().apply { addRect(Rect(left, top, right, bottom)) }.snapshot() },
        )
    }

    override fun drawRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radiusX: Float,
        radiusY: Float,
        paint: Paint,
    ) {
        drawRoundRect(left, top, right, bottom, radiusX, radiusY, paint.asWebPaint())
    }

    internal fun drawRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radiusX: Float,
        radiusY: Float,
        paint: WebPaint,
    ) {
        if (radiusX <= 0f || radiusY <= 0f) {
            drawRect(left, top, right, bottom, paint)
            return
        }
        val x = min(left, right)
        val y = min(top, bottom)
        val w = abs(right - left)
        val h = abs(bottom - top)
        // Like Skia, scale the radii down if they don't fit.
        val rx = min(radiusX, w / 2)
        val ry = min(radiusY, h / 2)
        drawShape(
            paint,
            stroke = paint.style == PaintingStyle.Stroke,
            x,
            y,
            x + w,
            y + h,
            buildPath = {
                addRoundRect(
                    target.ctx,
                    x.toDouble(),
                    y.toDouble(),
                    w.toDouble(),
                    h.toDouble(),
                    rx.toDouble(),
                    ry.toDouble(),
                )
            },
            pathData = {
                WebPath().apply {
                    addRoundRect(
                        RoundRect(x, y, x + w, y + h, CornerRadius(rx, ry))
                    )
                }.snapshot()
            },
        )
    }

    override fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        drawOval(left, top, right, bottom, paint.asWebPaint())
    }

    internal fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: WebPaint) {
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        val rx = abs(right - left) / 2
        val ry = abs(bottom - top) / 2
        drawShape(
            paint,
            stroke = paint.style == PaintingStyle.Stroke,
            cx - rx,
            cy - ry,
            cx + rx,
            cy + ry,
            buildPath = {
                target.ctx.ellipse(
                    cx.toDouble(), cy.toDouble(), rx.toDouble(), ry.toDouble(),
                    0.0, 0.0, 2 * PI, false,
                )
            },
            pathData = { WebPath().apply { addOval(Rect(left, top, right, bottom)) }.snapshot() },
        )
    }

    override fun drawCircle(center: Offset, radius: Float, paint: Paint) {
        drawCircle(center.x, center.y, radius, paint.asWebPaint())
    }

    internal fun drawCircle(cx: Float, cy: Float, radius: Float, paint: WebPaint) {
        if (radius <= 0f && paint.style == PaintingStyle.Fill) return
        val r = max(radius, 0f)
        drawShape(
            paint,
            stroke = paint.style == PaintingStyle.Stroke,
            cx - r,
            cy - r,
            cx + r,
            cy + r,
            buildPath = {
                target.ctx.arc(cx.toDouble(), cy.toDouble(), r.toDouble(), 0.0, 2 * PI, false)
            },
            pathData = {
                WebPath().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }.snapshot()
            },
        )
    }

    override fun drawArc(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        startAngle: Float,
        sweepAngle: Float,
        useCenter: Boolean,
        paint: Paint,
    ) {
        drawArc(left, top, right, bottom, startAngle, sweepAngle, useCenter, paint.asWebPaint())
    }

    internal fun drawArc(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        startAngle: Float,
        sweepAngle: Float,
        useCenter: Boolean,
        paint: WebPaint,
    ) {
        if (sweepAngle == 0f) return
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        val rx = abs(right - left) / 2
        val ry = abs(bottom - top) / 2
        val start = startAngle * DegreesToRadians
        val sweep = sweepAngle.coerceIn(-360f, 360f) * DegreesToRadians
        drawShape(
            paint,
            stroke = paint.style == PaintingStyle.Stroke,
            cx - rx,
            cy - ry,
            cx + rx,
            cy + ry,
            buildPath = {
                val ctx = target.ctx
                if (useCenter) ctx.moveTo(cx.toDouble(), cy.toDouble())
                ctx.ellipse(
                    cx.toDouble(), cy.toDouble(), rx.toDouble(), ry.toDouble(),
                    0.0, start, start + sweep, sweep < 0,
                )
                if (useCenter) ctx.closePath()
            },
            pathData = {
                WebPath().apply {
                    if (useCenter) moveTo(cx, cy)
                    arcTo(Rect(left, top, right, bottom), startAngle, sweepAngle, !useCenter)
                    if (useCenter) close()
                }.snapshot()
            },
        )
    }

    override fun drawPath(path: Path, paint: Paint) {
        drawPathData(path.toPathData(), paint.asWebPaint(), paint.style == PaintingStyle.Stroke)
    }

    internal fun drawPathData(
        data: PathData,
        paint: WebPaint,
        stroke: Boolean,
        applyPathEffect: Boolean = true,
    ) {
        if (drawsNothing) return
        if (data.isEmpty) return
        val effect = paint.pathEffect as? WebPathEffect
        if (applyPathEffect && effect != null && (effect !is WebPathEffect.Dash || !stroke)) {
            // A stamped effect, for instance, turns a stroke into a filled path.
            val transformed = effect.apply(data, stroke)
            drawPathData(transformed, paint, effect.resultIsStroke(stroke), applyPathEffect = false)
            return
        }
        // (drawRect would apply the path effect again.)
        if (!stroke && effect == null) {
            val rect = data.asRect()
            if (rect != null && data.fillType == PathFillType.NonZero) {
                drawRect(rect.left, rect.top, rect.right, rect.bottom, paint, stroke = false)
                return
            }
        }
        val shaderTransform = paint.shader?.webShader?.styleTransform
        if (shaderTransform != null && !stroke) {
            fillThroughTransform(data, paint, shaderTransform)
            return
        }
        if (mayRasterizeShader(paint)) {
            setDrawBounds(data.left, data.top, data.right, data.bottom, paint, stroke)
        }
        clipBeforeDrawing(data.left, data.top, data.right, data.bottom, paint, stroke)
        if (!applyPaint(paint, stroke)) return
        val path2D = data.toPath2D()
        val fillRule = data.fillType.toJs()
        if (isUnboundedBlendMode(paint.blendMode)) {
            target.save()
            target.ctx.clip(path2D, fillRule)
            drawWithPaint(paint, stroke, path2D, fillRule)
            target.restore()
            return
        }
        drawWithPaint(paint, stroke, path2D, fillRule)
    }

    override fun drawImage(image: ImageBitmap, topLeftOffset: Offset, paint: Paint) {
        drawImageRect(
            image,
            0f,
            0f,
            image.width.toFloat(),
            image.height.toFloat(),
            topLeftOffset.x,
            topLeftOffset.y,
            topLeftOffset.x + image.width,
            topLeftOffset.y + image.height,
            paint.asWebPaint(),
        )
    }

    override fun drawImageRect(
        image: ImageBitmap,
        srcOffset: IntOffset,
        srcSize: IntSize,
        dstOffset: IntOffset,
        dstSize: IntSize,
        paint: Paint,
    ) {
        drawImageRect(
            image,
            srcOffset.x.toFloat(),
            srcOffset.y.toFloat(),
            (srcOffset.x + srcSize.width).toFloat(),
            (srcOffset.y + srcSize.height).toFloat(),
            dstOffset.x.toFloat(),
            dstOffset.y.toFloat(),
            (dstOffset.x + dstSize.width).toFloat(),
            (dstOffset.y + dstSize.height).toFloat(),
            paint.asWebPaint(),
        )
    }

    internal fun drawImageRect(
        image: ImageBitmap,
        srcLeft: Float,
        srcTop: Float,
        srcRight: Float,
        srcBottom: Float,
        dstLeft: Float,
        dstTop: Float,
        dstRight: Float,
        dstBottom: Float,
        paint: WebPaint,
    ) {
        if (drawsNothing) return
        val webImage = image as? WebImageBitmap ?: return
        val blendMode = paint.blendMode
        if (blendMode == BlendMode.Dst) return
        if (srcRight <= srcLeft || srcBottom <= srcTop) return
        val left = min(dstLeft, dstRight)
        val top = min(dstTop, dstBottom)
        val right = max(dstLeft, dstRight)
        val bottom = max(dstTop, dstBottom)
        if (quickReject(left, top, right, bottom)) return
        val colorFilter = paint.appliedColorFilter
        val source = if (colorFilter != null) {
            webImage.filteredSource(colorFilter)
        } else {
            webImage.drawableRegionSource()
        } ?: return
        val alpha = paint.color.alpha * alphaMultiplier
        if (alpha <= 0f && blendModeKeepsDestinationForTransparent(blendMode)) return
        clipBeforeDrawing(left, top, right, bottom, paint, stroke = false)
        val t = target
        t.onDraw?.invoke()
        t.setTransform(matrix)
        t.setGlobalAlpha(alpha.toDouble())
        t.setCompositeOperation(compositeOperationFor(blendMode) ?: SourceOverOperation)
        t.setImageSmoothing(paint.filterQuality.toSmoothingLevel())
        val ctx = t.ctx
        val sx = srcLeft.toDouble()
        val sy = srcTop.toDouble()
        val sw = (srcRight - srcLeft).toDouble()
        val sh = (srcBottom - srcTop).toDouble()
        val dx = dstLeft.toDouble()
        val dy = dstTop.toDouble()
        val dw = (dstRight - dstLeft).toDouble()
        val dh = (dstBottom - dstTop).toDouble()
        val effects = beginEffects(paint, stroke = false)
        if (isUnboundedBlendMode(blendMode)) {
            t.save()
            ctx.beginPath()
            ctx.rect(dx, dy, dw, dh)
            ctx.clip(JsStrings.nonzero)
            drawImageSource(ctx, webImage, source, sx, sy, sw, sh, dx, dy, dw, dh)
            t.restore()
        } else {
            drawImageSource(ctx, webImage, source, sx, sy, sw, sh, dx, dy, dw, dh)
        }
        endEffects(effects)
    }

    private fun drawImageSource(
        ctx: Context2D,
        image: WebImageBitmap,
        source: JsAny,
        sx: Double,
        sy: Double,
        sw: Double,
        sh: Double,
        dx: Double,
        dy: Double,
        dw: Double,
        dh: Double,
    ) {
        if (image.isDrawnWhole) {
            ctx.drawImage(source, dx, dy, dw, dh)
        } else {
            ctx.drawImage(source, sx, sy, sw, sh, dx, dy, dw, dh)
        }
    }

    override fun drawPoints(pointMode: PointMode, points: List<Offset>, paint: Paint) {
        drawPoints(pointMode, points, paint.asWebPaint())
    }

    internal fun drawPoints(pointMode: PointMode, points: List<Offset>, paint: WebPaint) {
        drawPoints(pointMode, points.size, paint, { points[it].x }, { points[it].y })
    }

    override fun drawRawPoints(pointMode: PointMode, points: FloatArray, paint: Paint) {
        require(points.size % 2 == 0) { "points must have an even number of values" }
        drawRawPoints(pointMode, points, paint.asWebPaint())
    }

    internal fun drawRawPoints(pointMode: PointMode, points: FloatArray, paint: WebPaint) {
        drawPoints(pointMode, points.size / 2, paint, { points[2 * it] }, { points[2 * it + 1] })
    }

    /** Draws [count] points, the coordinates of the point `i` being `x(i)` and `y(i)`. */
    private inline fun drawPoints(
        pointMode: PointMode,
        count: Int,
        paint: WebPaint,
        crossinline x: (Int) -> Float,
        crossinline y: (Int) -> Float,
    ) {
        if (drawsNothing || count == 0) return
        when (pointMode) {
            PointMode.Points -> drawPointsAsDots(count, paint, x, y)
            PointMode.Lines -> drawSegments(count, paint, step = 2, x, y)
            else -> drawSegments(count, paint, step = 1, x, y)
        }
    }

    private inline fun drawPointsAsDots(
        count: Int,
        paint: WebPaint,
        crossinline x: (Int) -> Float,
        crossinline y: (Int) -> Float,
    ) {
        // Skia fills each point as a square or a circle (round cap) of the stroke width. They are
        // filled at once, as one path: where translucent points overlap, Skia, drawing them one
        // after the other, blends them twice.
        val round = paint.strokeCap == StrokeCap.Round
        val width = if (paint.strokeWidth > 0f) paint.strokeWidth else hairlineWidth().toFloat()
        val half = width / 2
        boundPoints(count, x, y)
        drawShape(
            paint,
            stroke = false,
            tmpRect[0] - half,
            tmpRect[1] - half,
            tmpRect[2] + half,
            tmpRect[3] + half,
            buildPath = {
                val ctx = target.ctx
                for (i in 0 until count) {
                    val px = x(i).toDouble()
                    val py = y(i).toDouble()
                    if (round) {
                        ctx.moveTo(px + half, py)
                        ctx.arc(px, py, half.toDouble(), 0.0, 2 * PI, false)
                    } else {
                        ctx.rect(px - half, py - half, width.toDouble(), width.toDouble())
                    }
                }
            },
            pathData = {
                WebPath().apply {
                    for (i in 0 until count) {
                        val dot = Rect(x(i) - half, y(i) - half, x(i) + half, y(i) + half)
                        if (round) addOval(dot) else addRect(dot)
                    }
                }.snapshot()
            },
        )
    }

    /** Draws the segments from each point to the next, starting every [step] points. */
    private inline fun drawSegments(
        count: Int,
        paint: WebPaint,
        step: Int,
        crossinline x: (Int) -> Float,
        crossinline y: (Int) -> Float,
    ) {
        if (count < 2) return
        boundPoints(count, x, y)
        drawShape(
            paint,
            stroke = true,
            tmpRect[0],
            tmpRect[1],
            tmpRect[2],
            tmpRect[3],
            buildPath = {
                val ctx = target.ctx
                // Each segment is a separate subpath, like individual drawLine calls.
                for (i in 0 until count - 1 step step) {
                    ctx.moveTo(x(i).toDouble(), y(i).toDouble())
                    ctx.lineTo(x(i + 1).toDouble(), y(i + 1).toDouble())
                }
            },
            pathData = {
                WebPath().apply {
                    for (i in 0 until count - 1 step step) {
                        moveTo(x(i), y(i))
                        lineTo(x(i + 1), y(i + 1))
                    }
                }.snapshot()
            },
        )
    }

    /** Sets [tmpRect] to the bounds of [count] points, the point `i` being at (`x(i)`, `y(i)`). */
    private inline fun boundPoints(count: Int, x: (Int) -> Float, y: (Int) -> Float) {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until count) {
            minX = min(minX, x(i))
            minY = min(minY, y(i))
            maxX = max(maxX, x(i))
            maxY = max(maxY, y(i))
        }
        tmpRect[0] = minX
        tmpRect[1] = minY
        tmpRect[2] = maxX
        tmpRect[3] = maxY
    }

    override fun drawVertices(vertices: Vertices, blendMode: BlendMode, paint: Paint) {
        if (drawsNothing || paint.blendMode == BlendMode.Dst) return
        Vertices2D.draw(this, vertices, blendMode, paint.asWebPaint())
    }

    /**
     * Adds the triangle to the current path, turning the positive way whatever the order of its
     * vertices, so that the nonzero rule fills the union of such triangles. A triangle without area
     * adds nothing.
     */
    private fun addTriangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
        val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if (area == 0f) return
        val ctx = target.ctx
        ctx.moveTo(x0.toDouble(), y0.toDouble())
        if (area > 0f) {
            ctx.lineTo(x1.toDouble(), y1.toDouble())
            ctx.lineTo(x2.toDouble(), y2.toDouble())
        } else {
            ctx.lineTo(x2.toDouble(), y2.toDouble())
            ctx.lineTo(x1.toDouble(), y1.toDouble())
        }
        ctx.closePath()
    }

    /**
     * Fills a triangle with the color [argb], drawn the way [paint] draws its color (see
     * [applyColor]), not in Dst: used to approximate meshes.
     */
    internal fun fillTriangle(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float,
        argb: Int,
        paint: WebPaint,
    ) {
        if (drawsNothing) return
        clipBeforeDrawing(
            minOf(x0, x1, x2), minOf(y0, y1, y2), maxOf(x0, x1, x2), maxOf(y0, y1, y2),
            paint,
            stroke = false,
        )
        val t = target
        t.onDraw?.invoke()
        t.setTransform(matrix)
        if (!applyColor(argb, paint, stroke = false)) return
        val ctx = t.ctx
        ctx.beginPath()
        addTriangle(x0, y0, x1, y1, x2, y2)
        if (isUnboundedBlendMode(paint.blendMode)) {
            // Within the triangle, like drawShape: Canvas 2D composites these modes over the clip.
            t.save()
            ctx.clip(JsStrings.nonzero)
            ctx.fill(JsStrings.nonzero)
            t.restore()
        } else {
            ctx.fill(JsStrings.nonzero)
        }
    }

    override fun enableZ() = Unit

    override fun disableZ() = Unit

    // ---------------------------------------------------------------------------------------------
    // Text

    /** The transformation around a run of text drawn in the units of its font. */
    private val unscaledMatrix = Affine()

    /** Draws a run of text at ([x], [y]), the start of its baseline. */
    internal fun drawText(run: TextRunDrawing, x: Float, y: Float) {
        if (drawsNothing) return
        val ratio = run.pixelRatio
        unscaledMatrix.set(matrix)
        matrix.preScale(ratio, ratio)
        drawTextInFontUnits(run, x / ratio, y / ratio)
        matrix.set(unscaledMatrix)
    }

    /** Draws a run of text at ([x], [y]), with the units of its font transformed by [matrix]. */
    private fun drawTextInFontUnits(run: TextRunDrawing, x: Float, y: Float) {
        val paint = run.paint
        val stroke = paint.style == PaintingStyle.Stroke
        val shadow = run.shadow
        // The offset of the shadow on the device.
        val shadowX = if (shadow != null) matrix.a * shadow.offsetX + matrix.c * shadow.offsetY else 0f
        val shadowY = if (shadow != null) matrix.b * shadow.offsetX + matrix.d * shadow.offsetY else 0f
        clipBeforeText(run, x, y, shadowX, shadowY)
        if (!applyPaint(paint, stroke)) return
        val t = target
        setTextStyle(run)
        val ctx = t.ctx
        if (shadow != null) {
            ctx.shadowColor = CssColors.of(
                shadow.argb.withAlphaMultiplied(alphaMultiplier)
            )
            ctx.shadowOffsetX = shadowX.toDouble()
            ctx.shadowOffsetY = shadowY.toDouble()
            ctx.shadowBlur = (shadow.blur * matrix.averageScale).toDouble()
        }
        val effects = beginEffects(paint, stroke)
        val text = run.jsText
        if (stroke) {
            ctx.strokeText(text, x.toDouble(), y.toDouble())
        } else {
            ctx.fillText(text, x.toDouble(), y.toDouble())
        }
        endEffects(effects)
        if (shadow != null) {
            ctx.shadowColor = JsStrings.transparent
            ctx.shadowOffsetX = 0.0
            ctx.shadowOffsetY = 0.0
            ctx.shadowBlur = 0.0
        }
    }

    /** Sets the font, the letter spacing and the direction of [run] to the target. */
    private fun setTextStyle(run: TextRunDrawing) {
        val t = target
        t.setFont(run.font)
        t.setLetterSpacing(run.letterSpacing ?: JsStrings.zeroPx)
        t.setDirection(if (run.isRtl) JsStrings.rtl else JsStrings.ltr)
    }

    /**
     * [clipBefore] drawing [run] at ([x], [y]), with its shadow offset by ([shadowX], [shadowY])
     * device pixels.
     */
    private fun clipBeforeText(run: TextRunDrawing, x: Float, y: Float, shadowX: Float, shadowY: Float) {
        if (deferredClip == null) return
        val ink = run.inkBounds ?: measureInk(run)
        val paint = run.paint
        val bounds = drawnBoundsOf(
            x + ink[0], y + ink[1], x + ink[2], y + ink[3], paint, paint.style == PaintingStyle.Stroke,
        )
        val shadow = run.shadow
        if (shadow != null) {
            // Blurred over three deviations, half of its blur each.
            val extent = 1.5f * shadow.blur * matrix.averageScale
            bounds[0] = min(bounds[0], bounds[0] + shadowX - extent)
            bounds[1] = min(bounds[1], bounds[1] + shadowY - extent)
            bounds[2] = max(bounds[2], bounds[2] + shadowX + extent)
            bounds[3] = max(bounds[3], bounds[3] + shadowY + extent)
        }
        clipBefore(bounds[0], bounds[1], bounds[2], bounds[3])
    }

    /** Measures the bounds of the ink of [run] (see [TextRunDrawing.inkBounds]) as it's drawn. */
    private fun measureInk(run: TextRunDrawing): FloatArray {
        setTextStyle(run)
        val metrics = target.ctx.measureText(run.jsText)
        val ink = floatArrayOf(
            -metrics.actualBoundingBoxLeft.toFloat(),
            -metrics.actualBoundingBoxAscent.toFloat(),
            metrics.actualBoundingBoxRight.toFloat(),
            metrics.actualBoundingBoxDescent.toFloat(),
        )
        run.inkBounds = ink
        return ink
    }

    // ---------------------------------------------------------------------------------------------
    // Shadows

    /**
     * Draws the shadow of [shape], blurred by [blurSigma] device pixels in the color [argb] and
     * offset by ([offsetX], [offsetY]) device pixels, without the shape itself: from its image if
     * it was drawn before (see [ShadowSprites]), painted otherwise (see [ShadowPainter]).
     */
    internal fun drawShadow(
        shape: ShadowShape,
        argb: Int,
        blurSigma: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        // Planning must not ask for a sprite: the second request would create it.
        if (drawsNothing) return
        val color = argb.withAlphaMultiplied(alphaMultiplier)
        val alpha = color ushr 24
        if (alpha == 0) return
        val rgb = color or OpaqueBlack
        val margin = blurExtent(blurSigma) + 1
        val bounds = shape.path.bounds
        matrix.mapRect(bounds.left, bounds.top, bounds.right, bounds.bottom, tmpRect)
        val left = tmpRect[0] - margin
        val top = tmpRect[1] - margin
        val width = ceil(tmpRect[2] - tmpRect[0]).toInt() + 2 * margin
        val height = ceil(tmpRect[3] - tmpRect[1]).toInt() + 2 * margin
        if (
            left + offsetX + width <= clipLeft || left + offsetX >= clipRight ||
                top + offsetY + height <= clipTop || top + offsetY >= clipBottom
        ) {
            return
        }
        val sprite =
            if (
                width <= MaxLayerSize &&
                    height <= MaxLayerSize &&
                    width.toLong() * height <= ShadowSprites.MaxSpritePixels
            ) {
                ShadowSprites.spriteFor(shape, matrix, blurSigma, rgb, left, top, width, height)
            } else {
                null
            }
        clipBefore(left + offsetX, top + offsetY, left + offsetX + width, top + offsetY + height)
        val t = target
        t.onDraw?.invoke()
        t.setCompositeOperation(SourceOverOperation)
        t.setGlobalAlpha(alpha / 255.0)
        if (sprite == null) {
            ShadowPainter.paint(t, shape, matrix, offsetX, offsetY, blurSigma, rgb)
        } else {
            t.setImageSmoothing(1)
            // At the exact position: an image of a shadow drawn between pixels is resampled, which
            // keeps it in place relative to its shape.
            t.ctx.setTransform(
                1.0, 0.0, 0.0, 1.0,
                (left + offsetX - t.originX).toDouble(), (top + offsetY - t.originY).toDouble(),
            )
            t.ctx.drawImage(sprite.canvas, 0.0, 0.0)
            t.invalidateTransform()
        }
    }

    private fun Path.toPathData(): PathData = asWebPath().snapshot()

    companion object {
        private const val EffectFilter = 1
        private const val EffectDash = 2
        private const val EffectShadowBlur = 4

        private const val MaxLayerSize = 16384

        // Limits of the resolution of content drawn in perspective (see [drawInPerspective]).
        private const val MinPerspectiveScale = 0.05f
        private const val MaxPerspectiveScale = 8f
        private const val MaxPerspectivePixels = 8_388_608f
        internal const val OpaqueBlack = 0xFF000000.toInt()
        private const val DegreesToRadians = PI / 180.0
    }
}

/**
 * A run of text prepared for drawing: the text, its CSS font and its paint. The paint must not be
 * modified afterwards: recorded runs are drawn again later. The paint and the shadow are in the units
 * of the font, which is drawn scaled by [pixelRatio] (see `RunFont`).
 */
internal class TextRunDrawing(
    /** The run drawn, in its directional override (see `jsRunOf`). */
    val jsText: JsString,
    val font: JsString,
    val paint: WebPaint,
    val isRtl: Boolean,
    val letterSpacing: JsString?,
    val shadow: TextShadow?,
    val pixelRatio: Float,
) {
    /**
     * The bounds of the ink of the run drawn at the origin (left, top, right, bottom), measured by
     * the first canvas that needs them.
     */
    var inkBounds: FloatArray? = null
}

/** A text shadow, with its offset and its Canvas `shadowBlur` in the units of the font. */
internal class TextShadow(val argb: Int, val offsetX: Float, val offsetY: Float, val blur: Float)

/**
 * Prepares [ctx] so that what's drawn next, until [endShadowOnly], lands as nothing but its shadow,
 * blurred by [sigma] pixels in the color [argb] and offset by ([offsetX], [offsetY]) pixels: the
 * Canvas 2D way to blur a shape that works in every browser. The transformation (a, b, c, d, e, f),
 * in pixels of [ctx], is set with what's drawn moved far out of the canvas, the shadow offset back.
 * What's drawn must be opaque: the shadow multiplies its color by the alpha of what's drawn.
 */
internal fun beginShadowOnly(
    ctx: Context2D,
    argb: Int,
    sigma: Float,
    offsetX: Double,
    offsetY: Double,
    a: Float,
    b: Float,
    c: Float,
    d: Float,
    e: Double,
    f: Double,
) {
    ctx.shadowColor = CssColors.of(argb)
    ctx.shadowBlur = (2 * sigma).toDouble()
    ctx.shadowOffsetX = ShadowOnlyOffset + offsetX
    ctx.shadowOffsetY = offsetY
    val dx = e - ShadowOnlyOffset
    ctx.setTransform(a.toDouble(), b.toDouble(), c.toDouble(), d.toDouble(), dx, f)
}

internal fun endShadowOnly(ctx: Context2D) {
    ctx.shadowColor = JsStrings.transparent
    ctx.shadowBlur = 0.0
    ctx.shadowOffsetX = 0.0
    ctx.shadowOffsetY = 0.0
}

/** How far what's drawn is moved to draw nothing but its shadow (see [beginShadowOnly]). */
private const val ShadowOnlyOffset = 100_000.0

/** Returns this ARGB color with its alpha multiplied by [multiplier]. */
internal fun Int.withAlphaMultiplied(multiplier: Float): Int {
    if (multiplier >= 1f) return this
    val alpha = ((this ushr 24) * multiplier + 0.5f).toInt().coerceIn(0, 255)
    return (this and 0x00FFFFFF) or (alpha shl 24)
}

/** Makes every pixel opaque black: what a Clear punches out (see `WebCanvas.applyColor`). */
private val OpaqueCoverage = WebColorFilter.Matrix(FloatArray(20).also { it[19] = 255f })

/** Whether drawing a fully transparent source with [blendMode] leaves the destination unchanged. */
internal fun blendModeKeepsDestinationForTransparent(blendMode: BlendMode): Boolean =
    when (blendMode) {
        BlendMode.Src, BlendMode.SrcIn, BlendMode.DstIn, BlendMode.SrcOut, BlendMode.DstAtop,
        BlendMode.Modulate, BlendMode.Clear -> false
        else -> true
    }

private fun FilterQuality.toSmoothingLevel(): Int = when (this) {
    FilterQuality.None -> 0
    FilterQuality.Low -> 1
    FilterQuality.Medium -> 2
    else -> 3
}

internal fun PathFillType.toJs(): JsString =
    if (this == PathFillType.EvenOdd) JsStrings.evenodd else JsStrings.nonzero

private fun StrokeCap.toJs(): JsString = when (this) {
    StrokeCap.Round -> JsStrings.round
    StrokeCap.Square -> JsStrings.square
    else -> JsStrings.butt
}

private fun StrokeJoin.toJs(): JsString = when (this) {
    StrokeJoin.Round -> JsStrings.round
    StrokeJoin.Bevel -> JsStrings.bevel
    else -> JsStrings.miter
}

/**
 * Applies a color filter to the top-left [width] x [height] pixels of [canvas], whose context is
 * [ctx], and returns the canvas with the result: [canvas] itself, unless the filter is applied on
 * the GPU through an SVG filter, which needs a canvas element of its own.
 */
internal fun applyColorFilter(
    canvas: CanvasElement,
    ctx: Context2D,
    width: Int,
    height: Int,
    filter: WebColorFilter,
): CanvasElement {
    if (!SvgColorFilters.appliesThroughSvg(filter)) {
        applyColorFilterInPlace(ctx, 0, 0, width, height, filter)
        return canvas
    }
    val result = createElementCanvas(width, height)
    val resultCtx = requireContext2D(result)
    val w = width.toDouble()
    val h = height.toDouble()
    resultCtx.filter = checkNotNull(SvgColorFilters.filterValue(filter, resultCtx)) {
        "A canvas element resolves SVG filters"
    }
    resultCtx.drawImage(canvas, 0.0, 0.0, w, h, 0.0, 0.0, w, h)
    resultCtx.filter = JsStrings.none
    return result
}

/**
 * Whether applying [filter], one that a paint applies ([WebPaint.appliedColorFilter]), reads the
 * pixels back (no GPU way in this browser).
 */
internal fun colorFilterReadsPixels(filter: WebColorFilter): Boolean =
    (filter as? WebColorFilter.Tint)?.appliesAsComposite != true && !SvgColorFilters.isSupported

/**
 * Applies the color filter that the layer [paint] applies to its content, the [width] x [height]
 * pixels at ([x], [y]) of [ctx], if it applies in place rather than when compositing (see
 * [SvgColorFilters.appliesThroughSvg]).
 */
internal fun applyLayerColorFilterInPlace(
    paint: WebPaint?,
    ctx: Context2D,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
) {
    val filter = paint?.appliedColorFilter ?: return
    if (!SvgColorFilters.appliesThroughSvg(filter)) {
        applyColorFilterInPlace(ctx, x, y, width, height, filter)
    }
}

/**
 * Applies a color filter in place to the [width] x [height] pixels at ([x], [y]) of a canvas: with a
 * compositing operation for tints, through the pixels otherwise.
 */
internal fun applyColorFilterInPlace(
    ctx: Context2D,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    filter: WebColorFilter,
) {
    val tint = filter as? WebColorFilter.Tint
    val operation = tint?.let { compositeOperationFor(it.blendMode) }
    ctx.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    if (tint != null && operation != null && tint.blendMode != BlendMode.Modulate &&
        tint.blendMode != BlendMode.Clear
    ) {
        ctx.save()
        ctx.beginPath()
        ctx.rect(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
        ctx.clip(JsStrings.nonzero)
        ctx.globalAlpha = 1.0
        ctx.globalCompositeOperation = operation
        ctx.fillStyle = CssColors.of(tint.color.toArgb())
        ctx.fillRect(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
        ctx.restore()
        return
    }
    val pixels = ctx.getImageData(x, y, width, height)
    applyColorMatrix(pixels, filter.jsColorMatrix)
    ctx.putImageData(pixels, x, y)
}

/**
 * Applies the color [matrix] (see [colorMatrixOf]) to the pixels of [imageData] in place, to their
 * non-premultiplied colors as the image data holds them.
 */
// language=js
private fun applyColorMatrix(imageData: ImageData, matrix: JsAny): Unit = js(
    """{
        const d = imageData.data, m = matrix;
        for (let i = 0; i < d.length; i += 4) {
            const r = d[i], g = d[i + 1], b = d[i + 2], a = d[i + 3];
            d[i] = m[0] * r + m[1] * g + m[2] * b + m[3] * a + m[4] * 255;
            d[i + 1] = m[5] * r + m[6] * g + m[7] * b + m[8] * a + m[9] * 255;
            d[i + 2] = m[10] * r + m[11] * g + m[12] * b + m[13] * a + m[14] * 255;
            d[i + 3] = m[15] * r + m[16] * g + m[17] * b + m[18] * a + m[19] * 255;
        }
    }"""
)

/** Creates a `Path2D` made of a rectangle and [path], used for difference clips. */
// language=js
private fun rectWithPath2D(
    left: Double,
    top: Double,
    right: Double,
    bottom: Double,
    path: JsAny,
): JsAny = js(
    "(function() { const p = new Path2D(); p.rect(left, top, right - left, bottom - top); p.addPath(path); return p; })()"
)
