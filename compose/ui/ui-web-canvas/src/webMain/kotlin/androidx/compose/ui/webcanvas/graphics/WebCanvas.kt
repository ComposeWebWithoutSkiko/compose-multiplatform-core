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

package androidx.compose.ui.webcanvas.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.webcanvas.dom.CanvasElement
import androidx.compose.ui.webcanvas.dom.CanvasFeatures
import androidx.compose.ui.webcanvas.dom.JsStrings
import androidx.compose.ui.webcanvas.dom.addRoundRect
import androidx.compose.ui.webcanvas.dom.clearLineDash
import androidx.compose.ui.webcanvas.dom.createCanvas
import androidx.compose.ui.webcanvas.dom.createElementCanvas
import androidx.compose.ui.webcanvas.dom.getContext2D
import androidx.compose.ui.webcanvas.dom.setLetterSpacing
import androidx.compose.ui.webcanvas.dom.setLineDash
import androidx.compose.ui.webcanvas.dom.setPatternTransform
import androidx.compose.ui.webcanvas.dom.toInt32Array
import androidx.compose.ui.webcanvas.dom.toJsNumberArray
import androidx.compose.ui.webcanvas.path.PathData
import androidx.compose.ui.webcanvas.path.WebPath
import androidx.compose.ui.webcanvas.path.WebPathEffect
import androidx.compose.ui.webcanvas.path.asRect
import androidx.compose.ui.webcanvas.path.toPath2D
import androidx.compose.ui.webcanvas.text.directionalRun
import kotlin.js.ExperimentalWasmJsInterop
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

    private val tmpRect = FloatArray(4)

    private class SaveEntry {
        val matrix = Affine()
        var clipLeft = 0f
        var clipTop = 0f
        var clipRight = 0f
        var clipBottom = 0f
        var alphaMultiplier = 1f
        var skipDrawing = false
        var target: DrawTarget? = null
        /** `ctx.save()` calls made on the current target since this entry was pushed. */
        var contextSaves = 0
        var layer: Layer? = null
    }

    private class Layer(
        val lease: LayerCanvasPool.Lease,
        val target: DrawTarget,
        val paint: WebPaint?,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        /** The scale of the layer content in device pixels, to scale effect parameters. */
        val scale: Float,
    )

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

    /** Unwinds all pending saves and layers, so that the context is left in its initial state. */
    fun finish() {
        while (depth > 0) restore()
        if (rootContextSaves > 0) {
            repeat(rootContextSaves) { target.ctx.restore() }
            rootContextSaves = 0
            target.invalidateState()
        }
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
        entry.contextSaves = 0
        entry.layer = null
    }

    override fun restore() {
        if (depth == 0) return
        depth--
        val entry = stack[depth]
        if (entry.contextSaves > 0) {
            repeat(entry.contextSaves) { target.ctx.restore() }
            target.invalidateState()
        }
        val layer = entry.layer
        entry.layer = null
        val previousTarget = entry.target!!
        entry.target = null
        target = previousTarget
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
        saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, paint.asWebPaint())
    }

    internal fun saveLayer(left: Float, top: Float, right: Float, bottom: Float, paint: WebPaint?) {
        save()
        if (skipDrawing) return
        val outsets = tmpRect
        val scale = matrix.averageScale
        var l = left
        var t = top
        var r = right
        var b = bottom
        val effect = paint?.renderEffect
        if (effect != null) {
            effect.outsets(outsets)
            l -= outsets[0]
            t -= outsets[1]
            r += outsets[2]
            b += outsets[3]
        }
        matrix.mapRect(l, t, r, b, outsets)
        val deviceLeft = floor(max(outsets[0], clipLeft)).toInt()
        val deviceTop = floor(max(outsets[1], clipTop)).toInt()
        val deviceRight = ceil(min(outsets[2], clipRight)).toInt()
        val deviceBottom = ceil(min(outsets[3], clipBottom)).toInt()
        val width = deviceRight - deviceLeft
        val height = deviceBottom - deviceTop
        if (width <= 0 || height <= 0) {
            skipDrawing = true
            return
        }
        if (width > MaxLayerSize || height > MaxLayerSize) {
            // Too large for an offscreen canvas: draw directly, ignoring the layer paint.
            return
        }
        val lease = LayerCanvasPool.acquire(width, height)
        val layerTarget = DrawTarget(lease.ctx, deviceLeft, deviceTop, width, height)
        stack[depth - 1].layer = Layer(
            lease = lease,
            target = layerTarget,
            paint = paint,
            left = deviceLeft,
            top = deviceTop,
            width = width,
            height = height,
            scale = scale,
        )
        target = layerTarget
        alphaMultiplier = 1f
    }

    private fun compositeLayer(layer: Layer) {
        val paint = layer.paint
        val parent = target
        val filtered = applyLayerColorFilter(layer)
        val layerSource: JsAny = (filtered ?: layer.lease).canvas
        if (!skipDrawing) {
            val blendMode = paint?.blendMode ?: BlendMode.SrcOver
            if (blendMode != BlendMode.Dst) {
                parent.onDraw?.invoke()
                parent.setPixelTransform()
                val alpha = (paint?.color?.alpha ?: 1f) * alphaMultiplier
                parent.setGlobalAlpha(alpha.toDouble())
                val dx = (layer.left - parent.originX).toDouble()
                val dy = (layer.top - parent.originY).toDouble()
                val w = layer.width.toDouble()
                val h = layer.height.toDouble()
                val operation = compositeOperationFor(blendMode) ?: SourceOverOperation
                val effect = paint?.renderEffect
                if (effect != null || isUnboundedBlendMode(blendMode)) {
                    // Keep the composite inside the layer bounds, like Skia does.
                    parent.ctx.save()
                    parent.ctx.beginPath()
                    parent.ctx.rect(dx, dy, w, h)
                    parent.ctx.clip(JsStrings.nonzero)
                    parent.setCompositeOperation(operation)
                    if (effect != null) {
                        Effects.draw(
                            parent.ctx,
                            effect,
                            layerSource,
                            layer.width,
                            layer.height,
                            layer.scale,
                            dx,
                            dy,
                        )
                    } else {
                        parent.ctx.drawImage(layerSource, 0.0, 0.0, w, h, dx, dy, w, h)
                    }
                    parent.ctx.restore()
                    parent.invalidateState()
                } else {
                    parent.setCompositeOperation(operation)
                    parent.ctx.drawImage(layerSource, 0.0, 0.0, w, h, dx, dy, w, h)
                }
            }
        }
        filtered?.let { LayerCanvasPool.release(it) }
        LayerCanvasPool.release(layer.lease)
    }

    /**
     * Applies the color filter of the layer paint to the layer pixels, on the GPU unless the
     * browser can't. Returns the canvas with the result if it isn't the layer canvas, to release
     * once composited.
     */
    private fun applyLayerColorFilter(layer: Layer): LayerCanvasPool.Lease? {
        val colorFilter = layer.paint?.colorFilter?.webColorFilter ?: return null
        val filterValue = if ((colorFilter as? WebColorFilter.Tint)?.appliesAsComposite == true) {
            null
        } else {
            SvgColorFilters.filterValue(colorFilter)
        }
        if (filterValue == null) {
            applyColorFilterInPlace(layer.lease.ctx, layer.width, layer.height, colorFilter)
            return null
        }
        val w = layer.width.toDouble()
        val h = layer.height.toDouble()
        val filtered = LayerCanvasPool.acquire(layer.width, layer.height, element = true)
        filtered.ctx.filter = filterValue
        filtered.ctx.drawImage(layer.lease.canvas, 0.0, 0.0, w, h, 0.0, 0.0, w, h)
        filtered.ctx.filter = JsStrings.none
        return filtered
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

    /** The current transformation; must not be modified. */
    internal val currentMatrix: Affine get() = matrix

    /** Multiplies the alpha of everything drawn until the matching [restore]. */
    internal fun modulateAlpha(alpha: Float) {
        alphaMultiplier *= alpha
    }

    /**
     * Draws the triangles of [vertices] with their vertex colors interpolated over each triangle,
     * like Skia does, which the Canvas 2D API can't do: WebGL 2 draws them. The [paint] gives the
     * alpha and the blend mode of the composition.
     *
     * Returns `false` if WebGL 2 isn't available.
     */
    internal fun drawColoredVertices(vertices: Vertices, paint: WebPaint): Boolean {
        if (skipDrawing || paint.blendMode == BlendMode.Dst) return true
        val positions = vertices.positions
        val colors = vertices.colors
        val vertexCount = min(positions.size / 2, colors.size)
        if (vertexCount < 3) return true
        if (!WebGLRenderer.isAvailable()) return false
        val indices = triangleIndices(vertices, vertexCount)
        if (indices.isEmpty()) return true

        val positionBits = IntArray(vertexCount * 2)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until vertexCount) {
            val x = matrix.mapX(positions[2 * i], positions[2 * i + 1])
            val y = matrix.mapY(positions[2 * i], positions[2 * i + 1])
            positionBits[2 * i] = x.toRawBits()
            positionBits[2 * i + 1] = y.toRawBits()
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

        val image = WebGLRenderer.drawColoredTriangles(
            positionBits = positionBits.toInt32Array(positionBits.size),
            colors = colors.toInt32Array(vertexCount),
            indices = indices.toInt32Array(indices.size),
            indexCount = indices.size,
            left = deviceLeft,
            top = deviceTop,
            width = width,
            height = height,
        ) ?: return false
        val t = target
        t.onDraw?.invoke()
        t.setPixelTransform()
        t.setGlobalAlpha((paint.color.alpha * alphaMultiplier).toDouble())
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
        return true
    }

    /** The vertex indices of the triangles of [vertices], three per triangle. */
    private fun triangleIndices(vertices: Vertices, vertexCount: Int): IntArray {
        val explicit = vertices.indices
        val count = if (explicit.isNotEmpty()) explicit.size else vertexCount
        fun vertex(i: Int): Int =
            if (explicit.isNotEmpty()) explicit[i].toInt() and 0xFFFF else i
        val triangleCount = when (vertices.vertexMode) {
            VertexMode.Triangles -> count / 3
            else -> max(0, count - 2)
        }
        val result = IntArray(triangleCount * 3)
        var n = 0
        fun add(a: Int, b: Int, c: Int) {
            if (a < vertexCount && b < vertexCount && c < vertexCount) {
                result[n++] = a
                result[n++] = b
                result[n++] = c
            }
        }
        when (vertices.vertexMode) {
            VertexMode.Triangles -> for (i in 0 until triangleCount) {
                add(vertex(3 * i), vertex(3 * i + 1), vertex(3 * i + 2))
            }
            VertexMode.TriangleStrip -> for (i in 0 until triangleCount) {
                add(vertex(i), vertex(i + 1), vertex(i + 2))
            }
            else -> for (i in 0 until triangleCount) {
                add(vertex(0), vertex(i + 1), vertex(i + 2))
            }
        }
        return if (n == result.size) result else result.copyOf(n)
    }

    /** Draws in the device space (keeping the clip) until the matching [restore]. */
    internal fun saveInDeviceSpace() {
        save()
        matrix.reset()
    }

    private val projective = Projective()
    private val uvProjective = Projective()

    /**
     * Draws content transformed in perspective by [transform] (from the space of the content to
     * the current local space), which the Canvas 2D API can't do: [drawContent] draws the content
     * that lies within the bounds ([left], [top], [right], [bottom]) into an offscreen canvas in its
     * own space, and WebGL 2 then draws that image in perspective. The result is composited with
     * [blendMode].
     *
     * Returns `false` if WebGL 2 isn't available.
     */
    internal fun drawInPerspective(
        transform: Matrix,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        blendMode: BlendMode,
        drawContent: (WebCanvas) -> Unit,
    ): Boolean {
        if (skipDrawing || blendMode == BlendMode.Dst) return true
        if (right <= left || bottom <= top) return true
        if (!WebGLRenderer.isAvailable()) return false
        val device = projective.setFrom(transform).postConcat(matrix)
        val bounds = tmpRect
        if (!device.mapRectBounds(left, top, right, bottom, bounds)) return true
        val deviceLeft = floor(max(bounds[0], clipLeft)).toInt()
        val deviceTop = floor(max(bounds[1], clipTop)).toInt()
        val deviceRight = ceil(min(bounds[2], clipRight)).toInt()
        val deviceBottom = ceil(min(bounds[3], clipBottom)).toInt()
        val width = deviceRight - deviceLeft
        val height = deviceBottom - deviceTop
        if (width <= 0 || height <= 0) return true

        // Rasterize the content at the resolution of its most magnified part, within limits.
        val contentWidth = right - left
        val contentHeight = bottom - top
        var scale = device.maxEdgeScale(left, top, right, bottom)
        if (scale.isNaN()) scale = matrix.averageScale
        scale = scale.coerceIn(MinPerspectiveScale, MaxPerspectiveScale)
        val maxSize = (min(WebGLRenderer.maxTextureSize, MaxLayerSize) - 2).toFloat()
        scale = min(scale, min(maxSize / contentWidth, maxSize / contentHeight))
        val pixels = contentWidth * scale * contentHeight * scale
        if (pixels > MaxPerspectivePixels) scale *= sqrt(MaxPerspectivePixels / pixels)
        // A transparent pixel around the content blends its edges smoothly (antialiasing).
        val rasterWidth = ceil(contentWidth * scale).toInt() + 2
        val rasterHeight = ceil(contentHeight * scale).toInt() + 2
        if (rasterWidth <= 2 || rasterHeight <= 2) return true

        val lease = LayerCanvasPool.acquire(rasterWidth, rasterHeight)
        val contentCanvas = WebCanvas(DrawTarget(lease.ctx, 0, 0, rasterWidth, rasterHeight))
        contentCanvas.translate(1f, 1f)
        contentCanvas.scale(scale, scale)
        contentCanvas.translate(-left, -top)
        drawContent(contentCanvas)
        contentCanvas.finish()

        // Map the texture coordinates of the raster to the content space, then to the device.
        val pixel = 1.0 / scale
        val uvMatrix = uvProjective.set(device).preScaleTranslate(
            sx = rasterWidth * pixel,
            sy = rasterHeight * pixel,
            tx = left - pixel,
            ty = top - pixel,
        )
        val image = WebGLRenderer.drawImageInPerspective(
            source = lease.canvas,
            sourceWidth = rasterWidth,
            sourceHeight = rasterHeight,
            uvMatrix = uvMatrix,
            left = deviceLeft,
            top = deviceTop,
            width = width,
            height = height,
        )
        if (image != null) {
            val t = target
            t.onDraw?.invoke()
            t.setPixelTransform()
            t.setGlobalAlpha(alphaMultiplier.toDouble())
            t.setCompositeOperation(compositeOperationFor(blendMode) ?: SourceOverOperation)
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
        }
        LayerCanvasPool.release(lease)
        return image != null
    }

    // ---------------------------------------------------------------------------------------------
    // Clip

    private fun ensureContextSaved() {
        if (depth == 0) {
            if (rootContextSaves == 0) {
                target.ctx.save()
                rootContextSaves = 1
            }
        } else {
            val entry = stack[depth - 1]
            if (entry.contextSaves == 0) {
                target.ctx.save()
                entry.contextSaves = 1
            }
        }
    }

    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float, clipOp: ClipOp) {
        if (skipDrawing) return
        if (clipOp == ClipOp.Intersect) {
            matrix.mapRect(left, top, right, bottom, tmpRect)
            if (matrix.isAxisAligned && containsDeviceClip(tmpRect)) {
                // The clip wouldn't remove anything.
                return
            }
            intersectClipBounds(tmpRect)
            if (skipDrawing) return
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
                intersectClipBounds(tmpRect)
            }
            if (skipDrawing) return
            ensureContextSaved()
            target.setTransform(matrix)
            target.ctx.clip(data.toPath2D(), data.fillType.toJs())
        } else {
            clipDifference(data)
        }
    }

    /** Clips out the area of [data], using an even-odd path made of a bounding rect and [data]. */
    private fun clipDifference(data: PathData) {
        if (data.isEmpty) return
        val inverse = matrix.copy()
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

    private fun containsDeviceClip(rect: FloatArray): Boolean =
        rect[0] <= clipLeft && rect[1] <= clipTop && rect[2] >= clipRight && rect[3] >= clipBottom

    private fun intersectClipBounds(rect: FloatArray) {
        clipLeft = max(clipLeft, rect[0])
        clipTop = max(clipTop, rect[1])
        clipRight = min(clipRight, rect[2])
        clipBottom = min(clipBottom, rect[3])
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
        if (blendMode == BlendMode.Clear) {
            // Clearing: punch the shape out with an opaque color.
            t.setGlobalAlpha(1.0)
            t.setCompositeOperation(compositeOperationFor(BlendMode.Clear)!!)
            if (stroke) t.setStrokeColor(OpaqueBlack) else t.setFillColor(OpaqueBlack)
        } else {
            t.setCompositeOperation(compositeOperationFor(blendMode) ?: SourceOverOperation)
            val shader = paint.shader?.webShader
            var style: JsAny? = null
            if (shader != null) {
                val colorFilter = paint.colorFilter?.webColorFilter
                style = when {
                    canonicalShader != null -> {
                        // Filled in the canonical space of the shader (see fillOrStroke).
                        styleSpace.set(matrix)
                        styleSpace.preConcat(canonicalShaderTransform)
                        fillInStyleSpace = true
                        canonicalShader.canonicalStyle()
                    }
                    colorFilter != null || shader.mayNeedRasterization ->
                        shaderStyleForDraw(shader, colorFilter, isBounded)
                    else -> shader.canvasStyle()
                }
                // Like Skia, a shader without content (e.g. an image not decoded yet) draws
                // nothing, rather than the paint color.
                if (style == null) return false
            }
            if (style == null) {
                var argb = paint.argb
                val colorFilter = paint.colorFilter?.webColorFilter
                if (colorFilter != null) argb = colorFilter.filter(argb)
                if (alphaMultiplier < 1f) argb = argb.withAlphaMultiplied(alphaMultiplier)
                if ((argb ushr 24) == 0 && blendModeKeepsDestinationForTransparent(blendMode)) {
                    return false
                }
                t.setGlobalAlpha(1.0)
                if (stroke) t.setStrokeColor(argb) else t.setFillColor(argb)
            } else {
                val alpha = paint.color.alpha * alphaMultiplier
                if (alpha <= 0f && blendModeKeepsDestinationForTransparent(blendMode)) return false
                t.setGlobalAlpha(alpha.toDouble())
                if (stroke) t.setStrokeStyle(style) else t.setFillStyle(style)
            }
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
        val outset = if (stroke) {
            max(paint.strokeWidth, 1f) * max(paint.strokeMiterLimit, 1f) / 2f
        } else {
            0f
        }
        matrix.mapRect(left - outset, top - outset, right + outset, bottom + outset, drawBounds)
        // Antialiasing reaches into the next pixel.
        drawBounds[0] -= 1f
        drawBounds[1] -= 1f
        drawBounds[2] += 1f
        drawBounds[3] += 1f
        hasDrawBounds = true
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
        if (stroke || paint.colorFilter != null) return null
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
        return shader.mayNeedRasterization || paint.colorFilter != null
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
        val cached = shader.raster
        if (cached != null && cached.matches(matrix, left, top, width, height, colorFilterKey)) {
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
        shader.raster = ShaderRaster(matrix.copy(), left, top, width, height, colorFilterKey, pattern)
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
     * Draws a shape with the paint's blur through the shadow of a far away copy, for browsers
     * without `ctx.filter`. The shadow is the only thing that lands on the canvas.
     */
    private inline fun drawBlurredWithShadow(paint: WebPaint, draw: () -> Unit) {
        val ctx = target.ctx
        val sigma = (paint.blurFilter?.sigma ?: 0f) * matrix.averageScale
        val offset = ShadowTrickOffset
        ctx.shadowColor = androidx.compose.ui.webcanvas.dom.CssColors.of(
            paint.argb.withAlphaMultiplied(alphaMultiplier)
        )
        ctx.shadowBlur = (2 * sigma).toDouble()
        ctx.shadowOffsetX = offset
        ctx.shadowOffsetY = 0.0
        // Move the shape itself out of the visible area, the shadow comes back by the offset.
        target.ctx.setTransform(
            matrix.a.toDouble(),
            matrix.b.toDouble(),
            matrix.c.toDouble(),
            matrix.d.toDouble(),
            (matrix.e - target.originX).toDouble() - offset,
            (matrix.f - target.originY).toDouble(),
        )
        target.invalidateTransform()
        draw()
        ctx.shadowColor = JsStrings.transparent
        ctx.shadowBlur = 0.0
        ctx.shadowOffsetX = 0.0
    }

    /** Fills or strokes the current path of the context, or [path2D] if given. */
    private fun drawWithPaint(paint: WebPaint, stroke: Boolean, path2D: JsAny?, fillRule: JsString) {
        val effects = beginEffects(paint, stroke)
        if (effects and EffectShadowBlur != 0) {
            drawBlurredWithShadow(paint) { fillOrStroke(stroke, path2D, fillRule) }
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
     * [paint], taking care of the special cases: unbounded blend modes, shaders that can't absorb
     * their transformation, and path effects other than a plain dash.
     */
    private inline fun drawShape(
        paint: WebPaint,
        stroke: Boolean,
        crossinline buildPath: () -> Unit,
        crossinline pathData: () -> PathData,
    ) {
        if (skipDrawing) return
        val effect = paint.pathEffect as? WebPathEffect
        if (effect != null && (effect !is WebPathEffect.Dash || !stroke)) {
            // Path effects that change the geometry are applied to the path itself.
            val transformed = effect.apply(pathData(), stroke, paint.strokeWidth)
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
        if (!applyPaint(paint, stroke, canonicalShader)) {
            fillInStyleSpace = false
            return
        }
        val ctx = target.ctx
        if (isUnboundedBlendMode(paint.blendMode)) {
            ctx.save()
            ctx.beginPath()
            buildPath()
            ctx.clip(JsStrings.nonzero)
            drawWithPaint(paint, stroke, null, JsStrings.nonzero)
            ctx.restore()
            target.invalidateState()
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
        if (!applyPaint(shaderPaint, stroke = false)) return
        ctx.save()
        ctx.clip(data.toPath2D(), data.fillType.toJs())
        val combined = matrix.copy()
        combined.preConcat(shaderTransform)
        target.setTransform(combined)
        val inverse = combined.copy()
        if (inverse.invert()) {
            inverse.mapRect(clipLeft, clipTop, clipRight, clipBottom, tmpRect)
            ctx.fillRect(
                tmpRect[0].toDouble(),
                tmpRect[1].toDouble(),
                (tmpRect[2] - tmpRect[0]).toDouble(),
                (tmpRect[3] - tmpRect[1]).toDouble(),
            )
        }
        ctx.restore()
        target.invalidateState()
    }

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

    internal fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: WebPaint) {
        if (skipDrawing) return
        val stroke = paint.style == PaintingStyle.Stroke
        // Shader fills go through drawShape, which can fill with shared canonical gradients.
        val simple = paint.pathEffect == null && (paint.shader == null || stroke) &&
            !isUnboundedBlendMode(paint.blendMode) && paint.blurFilter == null &&
            !mayRasterizeShader(paint)
        if (simple) {
            if (!applyPaint(paint, stroke)) return
            val x = min(left, right).toDouble()
            val y = min(top, bottom).toDouble()
            val w = abs(right - left).toDouble()
            val h = abs(bottom - top).toDouble()
            if (stroke) target.ctx.strokeRect(x, y, w, h) else target.ctx.fillRect(x, y, w, h)
            return
        }
        drawShape(
            paint,
            stroke,
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
                        androidx.compose.ui.geometry.RoundRect(
                            x, y, x + w, y + h,
                            androidx.compose.ui.geometry.CornerRadius(rx, ry),
                        )
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
        if (skipDrawing) return
        if (data.isEmpty) return
        val effect = paint.pathEffect as? WebPathEffect
        if (applyPathEffect && effect != null && (effect !is WebPathEffect.Dash || !stroke)) {
            // A stamped effect, for instance, turns a stroke into a filled path.
            val transformed = effect.apply(data, stroke, paint.strokeWidth)
            drawPathData(transformed, paint, effect.resultIsStroke(stroke), applyPathEffect = false)
            return
        }
        // (drawRect would apply the path effect again.)
        if (!stroke && effect == null) {
            val rect = data.asRect()
            if (rect != null && data.fillType == PathFillType.NonZero) {
                drawRect(rect.left, rect.top, rect.right, rect.bottom, paint.withFill())
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
        if (!applyPaint(paint, stroke)) return
        val path2D = data.toPath2D()
        val fillRule = data.fillType.toJs()
        if (isUnboundedBlendMode(paint.blendMode)) {
            val ctx = target.ctx
            ctx.save()
            ctx.clip(path2D, fillRule)
            drawWithPaint(paint, stroke, path2D, fillRule)
            ctx.restore()
            target.invalidateState()
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
        if (skipDrawing) return
        val webImage = image as? WebImageBitmap ?: return
        val blendMode = paint.blendMode
        if (blendMode == BlendMode.Dst) return
        if (srcRight <= srcLeft || srcBottom <= srcTop) return
        if (quickReject(min(dstLeft, dstRight), min(dstTop, dstBottom), max(dstLeft, dstRight), max(dstTop, dstBottom))) {
            return
        }
        val colorFilter = paint.colorFilter?.webColorFilter
        val source = if (colorFilter != null) {
            webImage.filteredSource(colorFilter)
        } else {
            webImage.drawableRegionSource()
        } ?: return
        val t = target
        t.onDraw?.invoke()
        t.setTransform(matrix)
        val alpha = paint.color.alpha * alphaMultiplier
        if (alpha <= 0f && blendModeKeepsDestinationForTransparent(blendMode)) return
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
            ctx.save()
            ctx.beginPath()
            ctx.rect(dx, dy, dw, dh)
            ctx.clip(JsStrings.nonzero)
            ctx.drawImage(source, sx, sy, sw, sh, dx, dy, dw, dh)
            ctx.restore()
            t.invalidateState()
        } else {
            ctx.drawImage(source, sx, sy, sw, sh, dx, dy, dw, dh)
        }
        endEffects(effects)
    }

    override fun drawPoints(pointMode: PointMode, points: List<Offset>, paint: Paint) {
        val raw = FloatArray(points.size * 2)
        for (i in points.indices) {
            raw[i * 2] = points[i].x
            raw[i * 2 + 1] = points[i].y
        }
        drawRawPoints(pointMode, raw, paint.asWebPaint())
    }

    override fun drawRawPoints(pointMode: PointMode, points: FloatArray, paint: Paint) {
        require(points.size % 2 == 0) { "points must have an even number of values" }
        drawRawPoints(pointMode, points, paint.asWebPaint())
    }

    internal fun drawRawPoints(pointMode: PointMode, points: FloatArray, paint: WebPaint) {
        if (skipDrawing || points.size < 2) return
        when (pointMode) {
            PointMode.Points -> drawPointsAsDots(points, paint)
            PointMode.Lines -> drawPolyline(points, paint, step = 2)
            else -> drawPolyline(points, paint, step = 1)
        }
    }

    private fun drawPointsAsDots(points: FloatArray, paint: WebPaint) {
        // Skia draws each point as a square or a circle (round cap) of the stroke width.
        val round = paint.strokeCap == StrokeCap.Round
        val fillPaint = paint.withFill()
        if (!applyPaint(fillPaint, stroke = false)) return
        val width = if (paint.strokeWidth > 0f) paint.strokeWidth else hairlineWidth().toFloat()
        val half = width / 2
        val ctx = target.ctx
        ctx.beginPath()
        var i = 0
        while (i + 1 < points.size) {
            val x = points[i]
            val y = points[i + 1]
            if (round) {
                ctx.moveTo((x + half).toDouble(), y.toDouble())
                ctx.arc(x.toDouble(), y.toDouble(), half.toDouble(), 0.0, 2 * PI, false)
            } else {
                ctx.rect((x - half).toDouble(), (y - half).toDouble(), width.toDouble(), width.toDouble())
            }
            i += 2
        }
        drawWithPaint(fillPaint, stroke = false, path2D = null, fillRule = JsStrings.nonzero)
    }

    private fun drawPolyline(points: FloatArray, paint: WebPaint, step: Int) {
        if (points.size < 4) return
        drawShape(
            paint,
            stroke = true,
            buildPath = {
                val ctx = target.ctx
                var i = 0
                // Each segment is a separate subpath, like individual drawLine calls.
                while (i + 3 < points.size) {
                    ctx.moveTo(points[i].toDouble(), points[i + 1].toDouble())
                    ctx.lineTo(points[i + 2].toDouble(), points[i + 3].toDouble())
                    i += step * 2
                }
            },
            pathData = {
                WebPath().apply {
                    var i = 0
                    while (i + 3 < points.size) {
                        moveTo(points[i], points[i + 1])
                        lineTo(points[i + 2], points[i + 3])
                        i += step * 2
                    }
                }.snapshot()
            },
        )
    }

    override fun drawVertices(vertices: Vertices, blendMode: BlendMode, paint: Paint) {
        if (skipDrawing) return
        Vertices2D.draw(this, vertices, blendMode, paint.asWebPaint())
    }

    /** Fills a triangle with a solid color (used to approximate meshes). */
    internal fun fillTriangle(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float,
        argb: Int,
        blendMode: BlendMode,
    ) {
        if (skipDrawing) return
        val t = target
        t.onDraw?.invoke()
        t.setTransform(matrix)
        t.setGlobalAlpha(alphaMultiplier.toDouble())
        t.setCompositeOperation(compositeOperationFor(blendMode) ?: SourceOverOperation)
        t.setFillColor(argb)
        val ctx = t.ctx
        ctx.beginPath()
        ctx.moveTo(x0.toDouble(), y0.toDouble())
        ctx.lineTo(x1.toDouble(), y1.toDouble())
        ctx.lineTo(x2.toDouble(), y2.toDouble())
        ctx.closePath()
        ctx.fill(JsStrings.nonzero)
    }

    override fun enableZ() = Unit

    override fun disableZ() = Unit

    // ---------------------------------------------------------------------------------------------
    // Text

    /** Draws a run of text at ([x], [y]), the start of its baseline. */
    internal fun drawText(run: TextRunDrawing, x: Float, y: Float) {
        if (skipDrawing) return
        val paint = run.paint
        val stroke = paint.style == PaintingStyle.Stroke
        if (!applyPaint(paint, stroke)) return
        val t = target
        t.setFont(run.font)
        t.setDirection(if (run.isRtl) JsStrings.rtl else JsStrings.ltr)
        val ctx = t.ctx
        val letterSpacing = run.letterSpacing
        if (letterSpacing != null) setLetterSpacing(ctx, letterSpacing)
        val shadow = run.shadow
        if (shadow != null) {
            val dx = matrix.a * shadow.offsetX + matrix.c * shadow.offsetY
            val dy = matrix.b * shadow.offsetX + matrix.d * shadow.offsetY
            ctx.shadowColor = androidx.compose.ui.webcanvas.dom.CssColors.of(
                shadow.argb.withAlphaMultiplied(alphaMultiplier)
            )
            ctx.shadowOffsetX = dx.toDouble()
            ctx.shadowOffsetY = dy.toDouble()
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
        if (letterSpacing != null) setLetterSpacing(ctx, ZeroPx)
    }

    // ---------------------------------------------------------------------------------------------
    // Shadows

    /**
     * Draws the blurred shadow of [shape] (in local coordinates) in the color [argb], offset by
     * ([offsetX], [offsetY]) device pixels, without drawing the shape itself.
     */
    internal fun drawShapeShadow(
        shape: JsAny,
        argb: Int,
        blurSigma: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        if (skipDrawing) return
        val color = argb.withAlphaMultiplied(alphaMultiplier)
        if ((color ushr 24) == 0) return
        val t = target
        t.onDraw?.invoke()
        t.setGlobalAlpha(1.0)
        t.setCompositeOperation(SourceOverOperation)
        t.setFillColor(OpaqueBlack)
        val ctx = t.ctx
        ctx.shadowColor = androidx.compose.ui.webcanvas.dom.CssColors.of(color)
        ctx.shadowBlur = (2 * blurSigma).toDouble()
        ctx.shadowOffsetX = ShadowTrickOffset + offsetX
        ctx.shadowOffsetY = offsetY.toDouble()
        ctx.setTransform(
            matrix.a.toDouble(),
            matrix.b.toDouble(),
            matrix.c.toDouble(),
            matrix.d.toDouble(),
            (matrix.e - t.originX).toDouble() - ShadowTrickOffset,
            (matrix.f - t.originY).toDouble(),
        )
        t.invalidateTransform()
        ctx.fill(shape, JsStrings.nonzero)
        ctx.shadowColor = JsStrings.transparent
        ctx.shadowBlur = 0.0
        ctx.shadowOffsetX = 0.0
        ctx.shadowOffsetY = 0.0
    }

    private fun Path.toPathData(): PathData =
        (this as? WebPath)?.snapshot() ?: WebPath().also { it.addPath(this) }.snapshot()

    companion object {
        private const val EffectFilter = 1
        private const val EffectDash = 2
        private const val EffectShadowBlur = 4

        private const val MaxLayerSize = 16384

        // Limits of the resolution of content drawn in perspective (see [drawInPerspective]).
        private const val MinPerspectiveScale = 0.05f
        private const val MaxPerspectiveScale = 8f
        private const val MaxPerspectivePixels = 8_388_608f
        private const val OpaqueBlack = 0xFF000000.toInt()
        private const val DegreesToRadians = PI / 180.0

        /** How far the shape is moved to draw only its shadow (see [drawShapeShadow]). */
        private const val ShadowTrickOffset = 100_000.0

        private val ZeroPx: JsString = "0px".toJsString()
    }
}

/**
 * A run of text prepared for drawing: the text, its CSS font and its paint. The paint must not be
 * modified afterwards: recorded runs are drawn again later.
 */
internal class TextRunDrawing(
    val text: String,
    val font: JsString,
    val paint: WebPaint,
    val isRtl: Boolean,
    val letterSpacing: JsString?,
    val shadow: TextShadow?,
    jsText: JsString? = null,
) {
    val jsText: JsString = jsText ?: directionalRun(text, isRtl).toJsString()
}

/** A text shadow, with its offset in local pixels and its Canvas `shadowBlur`. */
internal class TextShadow(val argb: Int, val offsetX: Float, val offsetY: Float, val blur: Float)

/** Returns this ARGB color with its alpha multiplied by [multiplier]. */
internal fun Int.withAlphaMultiplied(multiplier: Float): Int {
    if (multiplier >= 1f) return this
    val alpha = ((this ushr 24) * multiplier + 0.5f).toInt().coerceIn(0, 255)
    return (this and 0x00FFFFFF) or (alpha shl 24)
}

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

/** Returns a copy of this paint with the fill style, or this paint if it already fills. */
private fun WebPaint.withFill(): WebPaint {
    if (style == PaintingStyle.Fill) return this
    return WebPaint().also {
        it.copyFrom(this)
        it.style = PaintingStyle.Fill
    }
}

/**
 * Applies a color filter to the top-left [width] x [height] pixels of [canvas], whose context is
 * [ctx], and returns the canvas with the result: [canvas] itself, unless the filter is applied on
 * the GPU through an SVG filter, which needs a canvas element of its own.
 */
internal fun applyColorFilter(
    canvas: CanvasElement,
    ctx: androidx.compose.ui.webcanvas.dom.Context2D,
    width: Int,
    height: Int,
    filter: WebColorFilter,
): CanvasElement {
    if ((filter as? WebColorFilter.Tint)?.appliesAsComposite != true) {
        val filterValue = SvgColorFilters.filterValue(filter)
        val result = filterValue?.let { createElementCanvas(width, height) }
        val resultCtx = result?.let { getContext2D(it, willReadFrequently = false) }
        if (resultCtx != null) {
            val w = width.toDouble()
            val h = height.toDouble()
            resultCtx.filter = filterValue
            resultCtx.drawImage(canvas, 0.0, 0.0, w, h, 0.0, 0.0, w, h)
            resultCtx.filter = JsStrings.none
            return result
        }
    }
    applyColorFilterInPlace(ctx, width, height, filter)
    return canvas
}

/** Whether applying [filter] reads the pixels back (no GPU way in this browser). */
internal fun colorFilterReadsPixels(filter: WebColorFilter): Boolean =
    (filter as? WebColorFilter.Tint)?.appliesAsComposite != true && !SvgColorFilters.isSupported

/**
 * Applies a color filter to the pixels of a canvas region in place: with a compositing operation
 * for tints, through the pixels otherwise.
 */
internal fun applyColorFilterInPlace(
    ctx: androidx.compose.ui.webcanvas.dom.Context2D,
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
        ctx.rect(0.0, 0.0, width.toDouble(), height.toDouble())
        ctx.clip(JsStrings.nonzero)
        ctx.globalAlpha = 1.0
        ctx.globalCompositeOperation = operation
        ctx.fillStyle = androidx.compose.ui.webcanvas.dom.CssColors.of(tint.color.toArgb())
        ctx.fillRect(0.0, 0.0, width.toDouble(), height.toDouble())
        ctx.restore()
        return
    }
    val pixels = IntArray(width * height)
    androidx.compose.ui.webcanvas.dom.readImageDataArgb(
        ctx.getImageData(0, 0, width, height),
        pixels,
        0,
        width,
    )
    for (i in pixels.indices) {
        pixels[i] = filter.filter(pixels[i])
    }
    ctx.putImageData(
        androidx.compose.ui.webcanvas.dom.createImageDataFromArgb(pixels, 0, width, width, height),
        0,
        0,
    )
}

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
