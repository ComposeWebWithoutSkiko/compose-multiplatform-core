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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.webcanvas.path.PathData
import androidx.compose.ui.webcanvas.path.asWebPath
import kotlin.math.max

/**
 * Recorded drawing commands, replayed into a [WebCanvas] every time the layer that owns them is
 * drawn: the Canvas 2D API has no retained display lists of its own.
 *
 * Commands are stored compactly: opcodes and integer arguments in [ops], float arguments in
 * [floats], and objects (paints, paths, images, nested layers, text) in [objects].
 */
internal class DisplayList internal constructor(
    private val ops: IntArray,
    private val opCount: Int,
    private val floats: FloatArray,
    private val objects: Array<Any?>,
    /**
     * Whether drawing the list with an alpha gives the same pixels as drawing it into an offscreen
     * layer composited with that alpha: it draws at most one shape or image (no text, points,
     * triangles or layers, which can cover themselves), with the source-over blend mode.
     */
    val canModulateAlpha: Boolean,
) : JobContent {
    val isEmpty: Boolean get() = opCount == 0

    fun replay(canvas: WebCanvas) {
        replay(canvas, 0, 0, 0, opCount)
    }

    /**
     * Replays the content of a layer: the whole list for [WholeList], or else the content of the
     * layer saved by the SaveLayer op at [contentOp], up to its matching Restore, without the
     * layer itself.
     */
    override fun replayContent(canvas: WebCanvas, contentOp: Int) {
        if (contentOp == WholeList) {
            replay(canvas)
            return
        }
        val layout = contentOp + 1
        replay(
            canvas,
            startOp = layout + SaveLayerLayout.Size,
            startFloat = ops[layout + SaveLayerLayout.ContentFloat],
            startObject = ops[layout + SaveLayerLayout.ContentObject],
            endOp = ops[layout + SaveLayerLayout.RestoreOp],
        )
    }

    private fun replay(
        canvas: WebCanvas,
        startOp: Int,
        startFloat: Int,
        startObject: Int,
        endOp: Int,
    ) {
        var o = startOp
        var f = startFloat
        var j = startObject
        val ops = ops
        val floats = floats
        val objects = objects
        while (o < endOp) {
            when (ops[o++]) {
                Op.Save -> canvas.save()
                Op.Restore -> canvas.restore()
                Op.SaveLayer -> {
                    val layout = o
                    o += SaveLayerLayout.Size
                    val drawsContent = canvas.saveLayer(
                        floats[f], floats[f + 1], floats[f + 2], floats[f + 3],
                        objects[j] as WebPaint,
                        content = this,
                        contentOp = layout - 1,
                    )
                    f += 4
                    j++
                    if (!drawsContent) {
                        // Continue at the matching Restore.
                        o = ops[layout + SaveLayerLayout.RestoreOp]
                        f = ops[layout + SaveLayerLayout.RestoreFloat]
                        j = ops[layout + SaveLayerLayout.RestoreObject]
                    }
                }
                Op.Translate -> {
                    canvas.translate(floats[f], floats[f + 1])
                    f += 2
                }
                Op.Scale -> {
                    canvas.scale(floats[f], floats[f + 1])
                    f += 2
                }
                Op.Rotate -> {
                    canvas.rotate(floats[f])
                    f += 1
                }
                Op.Skew -> {
                    canvas.skew(floats[f], floats[f + 1])
                    f += 2
                }
                Op.Concat -> {
                    canvas.concat(
                        floats[f], floats[f + 1], floats[f + 2], floats[f + 3],
                        floats[f + 4], floats[f + 5],
                    )
                    f += 6
                }
                Op.ClipRect -> {
                    val clipOp = if (ops[o++] == 0) ClipOp.Intersect else ClipOp.Difference
                    canvas.clipRect(floats[f], floats[f + 1], floats[f + 2], floats[f + 3], clipOp)
                    f += 4
                }
                Op.ClipPath -> {
                    val clipOp = if (ops[o++] == 0) ClipOp.Intersect else ClipOp.Difference
                    canvas.clipPathData(objects[j] as PathData, clipOp)
                    j++
                }
                Op.DrawLine -> {
                    canvas.drawLine(
                        floats[f], floats[f + 1], floats[f + 2], floats[f + 3],
                        objects[j] as WebPaint,
                    )
                    f += 4
                    j++
                }
                Op.DrawRect -> {
                    canvas.drawRect(
                        floats[f], floats[f + 1], floats[f + 2], floats[f + 3],
                        objects[j] as WebPaint,
                    )
                    f += 4
                    j++
                }
                Op.DrawRoundRect -> {
                    canvas.drawRoundRect(
                        floats[f], floats[f + 1], floats[f + 2], floats[f + 3],
                        floats[f + 4], floats[f + 5],
                        objects[j] as WebPaint,
                    )
                    f += 6
                    j++
                }
                Op.DrawOval -> {
                    canvas.drawOval(
                        floats[f], floats[f + 1], floats[f + 2], floats[f + 3],
                        objects[j] as WebPaint,
                    )
                    f += 4
                    j++
                }
                Op.DrawCircle -> {
                    canvas.drawCircle(floats[f], floats[f + 1], floats[f + 2], objects[j] as WebPaint)
                    f += 3
                    j++
                }
                Op.DrawArc -> {
                    val useCenter = ops[o++] != 0
                    canvas.drawArc(
                        floats[f], floats[f + 1], floats[f + 2], floats[f + 3],
                        floats[f + 4], floats[f + 5], useCenter,
                        objects[j] as WebPaint,
                    )
                    f += 6
                    j++
                }
                Op.DrawPath -> {
                    val paint = objects[j + 1] as WebPaint
                    canvas.drawPathData(
                        objects[j] as PathData,
                        paint,
                        paint.style == PaintingStyle.Stroke,
                    )
                    j += 2
                }
                Op.DrawImageRect -> {
                    canvas.drawImageRect(
                        objects[j] as ImageBitmap,
                        floats[f], floats[f + 1], floats[f + 2], floats[f + 3],
                        floats[f + 4], floats[f + 5], floats[f + 6], floats[f + 7],
                        objects[j + 1] as WebPaint,
                    )
                    f += 8
                    j += 2
                }
                Op.DrawPoints -> {
                    @Suppress("UNCHECKED_CAST")
                    val points = objects[j] as List<Offset>
                    canvas.drawPoints(pointModeOf(ops[o++]), points, objects[j + 1] as WebPaint)
                    j += 2
                }
                Op.DrawRawPoints -> {
                    val points = objects[j] as FloatArray
                    canvas.drawRawPoints(pointModeOf(ops[o++]), points, objects[j + 1] as WebPaint)
                    j += 2
                }
                Op.DrawVertices -> {
                    canvas.drawVertices(
                        objects[j] as Vertices,
                        objects[j + 1] as BlendMode,
                        objects[j + 2] as WebPaint,
                    )
                    j += 3
                }
                Op.DrawLayer -> {
                    (objects[j] as WebGraphicsLayer).drawInto(canvas)
                    j++
                }
                Op.DrawText -> {
                    canvas.drawText(objects[j] as TextRunDrawing, floats[f], floats[f + 1])
                    f += 2
                    j++
                }
            }
        }
    }

    internal companion object {
        /** The content of a layer that is a whole display list (see [replayContent]). */
        const val WholeList = -1
    }

    /**
     * The positions that follow a SaveLayer op in [ops]: the indices in [ops], [floats] and
     * [objects] of its matching Restore (a list's saves and restores are balanced), so that its
     * content can be skipped, and the indices in [floats] and [objects] where its content starts,
     * so that the content can be replayed alone (see [replayContent]).
     */
    internal object SaveLayerLayout {
        const val RestoreOp = 0
        const val RestoreFloat = 1
        const val RestoreObject = 2
        const val ContentFloat = 3
        const val ContentObject = 4
        const val Size = 5
    }

    internal object Op {
        const val Save = 0
        const val Restore = 1

        /** Followed by its [SaveLayerLayout]. */
        const val SaveLayer = 2
        const val Translate = 3
        const val Scale = 4
        const val Rotate = 5
        const val Skew = 6
        const val Concat = 7
        const val ClipRect = 8
        const val ClipPath = 9
        const val DrawLine = 10
        const val DrawRect = 11
        const val DrawRoundRect = 12
        const val DrawOval = 13
        const val DrawCircle = 14
        const val DrawArc = 15
        const val DrawPath = 16
        const val DrawImageRect = 17
        const val DrawPoints = 18
        const val DrawVertices = 19
        const val DrawLayer = 20
        const val DrawText = 21
        const val DrawRawPoints = 22
    }
}

/**
 * The [Canvas] that records the drawing of a layer into a [DisplayList].
 *
 * Paints are shared snapshots, the same for equal paints (see [WebPaint.snapshot]), and paths are
 * copy-on-write snapshots, since Compose mutates and reuses both. Other arguments are recorded by
 * reference: an image, a layer or an array of points is drawn as it is when the list is replayed.
 *
 * @param reusesBuffers whether a recording reuses the buffers of the previous display list, which
 *   must not be used anymore once the next recording begins (see [recycle])
 */
internal class RecordingCanvas(private val reusesBuffers: Boolean = false) : Canvas {
    private var ops = IntArray(64)
    private var opCount = 0
    private var floats = FloatArray(128)
    private var floatCount = 0
    private var objects = arrayOfNulls<Any>(32)
    private var objectCount = 0

    /** The shapes and images drawn so far, see [DisplayList.canModulateAlpha]. */
    private var shapeCount = 0
    private var canModulateAlpha = true

    /**
     * The saves not restored yet: for a SaveLayer, the index in [ops] of its
     * [DisplayList.SaveLayerLayout]; [PlainSave] for a Save.
     */
    private var openSaves = IntArray(8)
    private var openSaveCount = 0

    private var recording = false

    fun beginRecording() {
        check(!recording) { "Already recording" }
        recording = true
        if (!reusesBuffers) {
            // A previous display list may still share the buffers: start new ones.
            ops = IntArray(max(16, opCount))
            floats = FloatArray(max(16, floatCount))
            objects = arrayOfNulls(max(8, objectCount))
        }
        opCount = 0
        floatCount = 0
        objectCount = 0
        shapeCount = 0
        canModulateAlpha = true
        openSaveCount = 0
    }

    fun endRecording(): DisplayList {
        check(recording) { "Not recording" }
        recording = false
        // Balanced like a Skia picture: what was saved is restored at the end.
        while (openSaveCount > 0) restore()
        return DisplayList(ops, opCount, floats, objects, canModulateAlpha)
    }

    /**
     * Ends the recording, if one is in progress, and drops the references of the last display list
     * to what it draws, which it then can't draw anymore. Only when [reusesBuffers].
     */
    fun recycle() {
        check(reusesBuffers) { "The display list may still be used" }
        recording = false
        objects.fill(null, 0, objectCount)
        objectCount = 0
    }

    private fun pushSave(layout: Int) {
        if (openSaveCount == openSaves.size) openSaves = openSaves.copyOf(openSaveCount * 2)
        openSaves[openSaveCount++] = layout
    }

    private fun op(code: Int) {
        if (opCount == ops.size) ops = ops.copyOf(ops.size * 2)
        ops[opCount++] = code
    }

    private fun f(value: Float) {
        if (floatCount == floats.size) floats = floats.copyOf(floats.size * 2)
        floats[floatCount++] = value
    }

    private fun obj(value: Any?) {
        if (objectCount == objects.size) objects = objects.copyOf(objects.size * 2)
        objects[objectCount++] = value
    }

    private fun recordPaint(paint: Paint) {
        obj(paint.asWebPaint().snapshot())
    }

    /** Records the paint of a draw of a single shape or image, which covers nothing of itself. */
    private fun recordShapePaint(paint: Paint) {
        val snapshot = paint.asWebPaint().snapshot()
        if (++shapeCount > 1 || snapshot.blendMode != BlendMode.SrcOver) canModulateAlpha = false
        obj(snapshot)
    }

    /** Notes a draw that can cover itself: several glyphs, points or triangles, or a layer. */
    private fun noteOverlappingDraw() {
        canModulateAlpha = false
    }

    override fun save() {
        op(DisplayList.Op.Save)
        pushSave(PlainSave)
    }

    override fun restore() {
        // A restore without a save does nothing, like on the canvas it's replayed into.
        if (openSaveCount == 0) return
        val layout = openSaves[--openSaveCount]
        if (layout != PlainSave) {
            ops[layout + DisplayList.SaveLayerLayout.RestoreOp] = opCount
            ops[layout + DisplayList.SaveLayerLayout.RestoreFloat] = floatCount
            ops[layout + DisplayList.SaveLayerLayout.RestoreObject] = objectCount
        }
        op(DisplayList.Op.Restore)
    }

    override fun saveLayer(bounds: Rect, paint: Paint) {
        noteOverlappingDraw()
        op(DisplayList.Op.SaveLayer)
        // The positions of the content are set below, those of the restore when it's recorded.
        val layout = opCount
        repeat(DisplayList.SaveLayerLayout.Size) { op(0) }
        pushSave(layout)
        f(bounds.left)
        f(bounds.top)
        f(bounds.right)
        f(bounds.bottom)
        recordPaint(paint)
        ops[layout + DisplayList.SaveLayerLayout.ContentFloat] = floatCount
        ops[layout + DisplayList.SaveLayerLayout.ContentObject] = objectCount
    }

    override fun translate(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        op(DisplayList.Op.Translate)
        f(dx)
        f(dy)
    }

    override fun scale(sx: Float, sy: Float) {
        if (sx == 1f && sy == 1f) return
        op(DisplayList.Op.Scale)
        f(sx)
        f(sy)
    }

    override fun rotate(degrees: Float) {
        if (degrees == 0f) return
        op(DisplayList.Op.Rotate)
        f(degrees)
    }

    override fun skew(sx: Float, sy: Float) {
        op(DisplayList.Op.Skew)
        f(sx)
        f(sy)
    }

    private val concatenated = Affine()

    override fun concat(matrix: Matrix) {
        val transform = matrix.toAffine(concatenated)
        if (transform.isIdentity) return
        op(DisplayList.Op.Concat)
        f(transform.a)
        f(transform.b)
        f(transform.c)
        f(transform.d)
        f(transform.e)
        f(transform.f)
    }

    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float, clipOp: ClipOp) {
        op(DisplayList.Op.ClipRect)
        op(if (clipOp == ClipOp.Intersect) 0 else 1)
        f(left)
        f(top)
        f(right)
        f(bottom)
    }

    override fun clipPath(path: Path, clipOp: ClipOp) {
        clipPathData(path.toPathData(), clipOp)
    }

    internal fun clipPathData(data: PathData, clipOp: ClipOp) {
        op(DisplayList.Op.ClipPath)
        op(if (clipOp == ClipOp.Intersect) 0 else 1)
        obj(data)
    }

    override fun drawLine(p1: Offset, p2: Offset, paint: Paint) {
        op(DisplayList.Op.DrawLine)
        f(p1.x)
        f(p1.y)
        f(p2.x)
        f(p2.y)
        recordShapePaint(paint)
    }

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        op(DisplayList.Op.DrawRect)
        f(left)
        f(top)
        f(right)
        f(bottom)
        recordShapePaint(paint)
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
        op(DisplayList.Op.DrawRoundRect)
        f(left)
        f(top)
        f(right)
        f(bottom)
        f(radiusX)
        f(radiusY)
        recordShapePaint(paint)
    }

    override fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        op(DisplayList.Op.DrawOval)
        f(left)
        f(top)
        f(right)
        f(bottom)
        recordShapePaint(paint)
    }

    override fun drawCircle(center: Offset, radius: Float, paint: Paint) {
        op(DisplayList.Op.DrawCircle)
        f(center.x)
        f(center.y)
        f(radius)
        recordShapePaint(paint)
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
        op(DisplayList.Op.DrawArc)
        op(if (useCenter) 1 else 0)
        f(left)
        f(top)
        f(right)
        f(bottom)
        f(startAngle)
        f(sweepAngle)
        recordShapePaint(paint)
    }

    override fun drawPath(path: Path, paint: Paint) {
        op(DisplayList.Op.DrawPath)
        obj(path.toPathData())
        recordShapePaint(paint)
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
            paint,
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
            paint,
        )
    }

    private fun drawImageRect(
        image: ImageBitmap,
        srcLeft: Float,
        srcTop: Float,
        srcRight: Float,
        srcBottom: Float,
        dstLeft: Float,
        dstTop: Float,
        dstRight: Float,
        dstBottom: Float,
        paint: Paint,
    ) {
        op(DisplayList.Op.DrawImageRect)
        obj(image)
        f(srcLeft)
        f(srcTop)
        f(srcRight)
        f(srcBottom)
        f(dstLeft)
        f(dstTop)
        f(dstRight)
        f(dstBottom)
        recordShapePaint(paint)
    }

    override fun drawPoints(pointMode: PointMode, points: List<Offset>, paint: Paint) {
        noteOverlappingDraw()
        op(DisplayList.Op.DrawPoints)
        op(pointMode.code)
        obj(points)
        recordPaint(paint)
    }

    override fun drawRawPoints(pointMode: PointMode, points: FloatArray, paint: Paint) {
        require(points.size % 2 == 0) { "points must have an even number of values" }
        noteOverlappingDraw()
        op(DisplayList.Op.DrawRawPoints)
        op(pointMode.code)
        obj(points)
        recordPaint(paint)
    }

    override fun drawVertices(vertices: Vertices, blendMode: BlendMode, paint: Paint) {
        noteOverlappingDraw()
        op(DisplayList.Op.DrawVertices)
        obj(vertices)
        obj(blendMode)
        recordPaint(paint)
    }

    override fun enableZ() = Unit

    override fun disableZ() = Unit

    internal fun drawLayer(layer: WebGraphicsLayer) {
        noteOverlappingDraw()
        op(DisplayList.Op.DrawLayer)
        obj(layer)
    }

    internal fun drawText(run: TextRunDrawing, x: Float, y: Float) {
        noteOverlappingDraw()
        op(DisplayList.Op.DrawText)
        f(x)
        f(y)
        obj(run)
    }

    private fun Path.toPathData(): PathData = asWebPath().snapshot()

    private companion object {
        /** In [openSaves], a save that isn't a layer. */
        const val PlainSave = -1
    }
}

private val PointMode.code: Int
    get() = when (this) {
        PointMode.Points -> 0
        PointMode.Lines -> 1
        else -> 2
    }

private fun pointModeOf(code: Int): PointMode = when (code) {
    0 -> PointMode.Points
    1 -> PointMode.Lines
    else -> PointMode.Polygon
}
