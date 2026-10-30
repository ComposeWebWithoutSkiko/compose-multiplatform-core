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

package androidx.compose.ui.webcanvas.path

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathOperation
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * The Compose [Path] of the web canvas backend: every `Path()` on the web.
 *
 * The browser's `Path2D` only takes drawing commands and gives nothing back, while a [Path] has to
 * report its bounds and convexity, combine with other paths, be iterated and measured. So the
 * geometry is kept here, and turned into a `Path2D` to draw (see [PathData.toPath2D]).
 *
 * The geometry is stored like Skia's `SkPath` (verbs, points, conic weights) and every builder call
 * follows the `SkPath` semantics used by the skiko backend: implicit moveTo injection, close
 * handling, arcs/ovals/round rects as conics with Skia's start points and directions (see
 * PathArcs.kt and PathShapes.kt), so that paths iterate, measure and draw as they do there.
 *
 * [snapshot] returns an immutable [PathData] view that stays valid when the path is mutated later:
 * appends write past the end of the snapshot, and an array a snapshot holds is never rewritten, but
 * replaced by a new one the new content is written into.
 */
internal class WebPath : Path {
    private var verbs: ByteArray = EmptyBytes
    private var verbCount = 0
    private var points: FloatArray = EmptyFloats
    private var pointCount = 0
    private var weights: FloatArray = EmptyFloats
    private var weightCount = 0

    /** Skia's `fLastMoveToIndex`: index of the last moveTo point, `~index` once closed. */
    private var lastMoveToIndex = InitialLastMoveToIndex

    private var currentFillType = PathFillType.NonZero

    /** The current snapshot, if any. Dropped on every mutation. */
    private var cachedSnapshot: PathData? = null

    // Whether a snapshot may hold each array: its entries must not be rewritten then, while
    // appending past the end of the snapshot is fine.
    private var verbsShared = false
    private var pointsShared = false
    private var weightsShared = false

    /**
     * Returns an immutable view of the current geometry, which may be kept. Repeated calls without
     * mutations in between return the same instance (so its caches, e.g. the `Path2D`, are reused).
     */
    fun snapshot(): PathData {
        val data = currentData()
        verbsShared = true
        pointsShared = true
        weightsShared = true
        return data
    }

    /**
     * Returns a view of the current geometry to read right away (bounds, convexity, a hit test),
     * not to keep: unlike [snapshot], it leaves the arrays to be rewritten in place. It's the
     * instance [snapshot] returns until the next mutation.
     */
    internal fun currentData(): PathData =
        cachedSnapshot
            ?: PathData(
                verbs,
                verbCount,
                points,
                pointCount,
                weights,
                weightCount,
                currentFillType,
                lastMoveToIndex,
            ).also { cachedSnapshot = it }

    override var fillType: PathFillType
        get() = currentFillType
        set(value) {
            if (value != currentFillType) {
                currentFillType = value
                cachedSnapshot = null
            }
        }

    override val isConvex: Boolean
        get() = currentData().isConvex

    override val isEmpty: Boolean
        get() = verbCount == 0

    override fun getBounds(): Rect = currentData().bounds

    // ---------------------------------------------------------------------------------------
    // Low level storage
    // ---------------------------------------------------------------------------------------

    /**
     * Makes room for appending the given numbers of entries, which shapes made of several verbs ask
     * for at once, so that the arrays grow once for them.
     */
    internal fun ensureCapacity(extraVerbs: Int, extraPoints: Int, extraWeights: Int) {
        // A grown array is a new one, which no snapshot holds.
        if (verbCount + extraVerbs > verbs.size) {
            verbs = verbs.copyOf(grow(verbs.size, verbCount + extraVerbs))
            verbsShared = false
        }
        if (2 * (pointCount + extraPoints) > points.size) {
            points = points.copyOf(2 * grow(points.size / 2, pointCount + extraPoints))
            pointsShared = false
        }
        if (weightCount + extraWeights > weights.size) {
            weights = weights.copyOf(grow(weights.size, weightCount + extraWeights))
            weightsShared = false
        }
    }

    private fun grow(current: Int, required: Int): Int = maxOf(required, current * 2, 8)

    private fun addVerb(verb: Byte) {
        verbs[verbCount++] = verb
    }

    private fun addPoint(x: Float, y: Float) {
        val i = 2 * pointCount
        points[i] = x
        points[i + 1] = y
        pointCount++
    }

    /**
     * Returns the array to write all the points into when rewriting them, read from [points] as it
     * was before the call: [points] itself, or a new array replacing it if a snapshot holds it.
     */
    private fun pointsForRewrite(): FloatArray {
        cachedSnapshot = null
        if (pointsShared) {
            points = FloatArray(points.size)
            pointsShared = false
        }
        return points
    }

    /** X coordinate of the last stored point (Skia's `getLastPt`), 0 for an empty path. */
    internal fun lastPointX(): Float = if (pointCount == 0) 0f else points[2 * pointCount - 2]

    /** Y coordinate of the last stored point (Skia's `getLastPt`), 0 for an empty path. */
    internal fun lastPointY(): Float = if (pointCount == 0) 0f else points[2 * pointCount - 1]

    /** Skia's `injectMoveToIfNeeded`: starts a contour at the last moveTo point after a close. */
    private fun injectMoveToIfNeeded() {
        if (lastMoveToIndex < 0) {
            val x: Float
            val y: Float
            if (verbCount == 0) {
                x = 0f
                y = 0f
            } else {
                val index = lastMoveToIndex.inv()
                x = points[2 * index]
                y = points[2 * index + 1]
            }
            moveTo(x, y)
        }
    }

    /**
     * Replaces the whole content of this path with [data] (including its fill type), a result that
     * nothing else holds, built by a path that is discarded: this path takes its arrays over.
     */
    private fun adopt(data: PathData) {
        // [data] describes exactly the new state, so it serves as the current snapshot, which
        // [snapshot] hands out like any other.
        cachedSnapshot = data
        verbs = data.verbs
        verbCount = data.verbCount
        points = data.points
        pointCount = data.pointCount
        weights = data.conicWeights
        weightCount = data.conicWeightCount
        lastMoveToIndex = data.lastMoveToIndex
        currentFillType = data.fillType
        verbsShared = false
        pointsShared = false
        weightsShared = false
    }

    // ---------------------------------------------------------------------------------------
    // Contour building
    // ---------------------------------------------------------------------------------------

    override fun moveTo(x: Float, y: Float) {
        ensureCapacity(1, 1, 0)
        cachedSnapshot = null
        lastMoveToIndex = pointCount
        addVerb(PathVerb.Move)
        addPoint(x, y)
    }

    override fun relativeMoveTo(dx: Float, dy: Float) {
        var x = 0f
        var y = 0f
        if (pointCount > 0) {
            val index = if (lastMoveToIndex >= 0) pointCount - 1 else lastMoveToIndex.inv()
            x = points[2 * index]
            y = points[2 * index + 1]
        }
        moveTo(x + dx, y + dy)
    }

    override fun lineTo(x: Float, y: Float) {
        injectMoveToIfNeeded()
        ensureCapacity(1, 1, 0)
        cachedSnapshot = null
        addVerb(PathVerb.Line)
        addPoint(x, y)
    }

    override fun relativeLineTo(dx: Float, dy: Float) {
        injectMoveToIfNeeded()
        lineTo(lastPointX() + dx, lastPointY() + dy)
    }

    @Deprecated(
        "Use quadraticTo() for consistency with cubicTo()",
        replaceWith = ReplaceWith("quadraticTo(x1, y1, x2, y2)"),
        level = DeprecationLevel.WARNING,
    )
    override fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float) {
        quadraticTo(x1, y1, x2, y2)
    }

    override fun quadraticTo(x1: Float, y1: Float, x2: Float, y2: Float) {
        injectMoveToIfNeeded()
        ensureCapacity(1, 2, 0)
        cachedSnapshot = null
        addVerb(PathVerb.Quad)
        addPoint(x1, y1)
        addPoint(x2, y2)
    }

    @Deprecated(
        "Use relativeQuadraticTo() for consistency with relativeCubicTo()",
        replaceWith = ReplaceWith("relativeQuadraticTo(dx1, dy1, dx2, dy2)"),
        level = DeprecationLevel.WARNING,
    )
    override fun relativeQuadraticBezierTo(dx1: Float, dy1: Float, dx2: Float, dy2: Float) {
        relativeQuadraticTo(dx1, dy1, dx2, dy2)
    }

    override fun relativeQuadraticTo(dx1: Float, dy1: Float, dx2: Float, dy2: Float) {
        injectMoveToIfNeeded()
        val x = lastPointX()
        val y = lastPointY()
        quadraticTo(x + dx1, y + dy1, x + dx2, y + dy2)
    }

    /** Adds a conic segment (Skia's `SkPath::conicTo`, including its weight special cases). */
    fun conicTo(x1: Float, y1: Float, x2: Float, y2: Float, weight: Float) {
        if (!(weight > 0f)) {
            // Also catches NaN.
            lineTo(x2, y2)
        } else if (!weight.isFinite()) {
            lineTo(x1, y1)
            lineTo(x2, y2)
        } else if (weight == 1f) {
            quadraticTo(x1, y1, x2, y2)
        } else {
            injectMoveToIfNeeded()
            ensureCapacity(1, 2, 1)
            cachedSnapshot = null
            addVerb(PathVerb.Conic)
            addPoint(x1, y1)
            addPoint(x2, y2)
            weights[weightCount++] = weight
        }
    }

    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        injectMoveToIfNeeded()
        ensureCapacity(1, 3, 0)
        cachedSnapshot = null
        addVerb(PathVerb.Cubic)
        addPoint(x1, y1)
        addPoint(x2, y2)
        addPoint(x3, y3)
    }

    override fun relativeCubicTo(
        dx1: Float,
        dy1: Float,
        dx2: Float,
        dy2: Float,
        dx3: Float,
        dy3: Float,
    ) {
        injectMoveToIfNeeded()
        val x = lastPointX()
        val y = lastPointY()
        cubicTo(x + dx1, y + dy1, x + dx2, y + dy2, x + dx3, y + dy3)
    }

    override fun close() {
        if (verbCount > 0 && verbs[verbCount - 1] != PathVerb.Close) {
            ensureCapacity(1, 0, 0)
            cachedSnapshot = null
            addVerb(PathVerb.Close)
        }
        // Signal that we need a moveTo to follow us (unless we're done).
        if (lastMoveToIndex >= 0) {
            lastMoveToIndex = lastMoveToIndex.inv()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Arcs (see PathArcs.kt)
    // ---------------------------------------------------------------------------------------

    override fun arcTo(
        rect: Rect,
        startAngleDegrees: Float,
        sweepAngleDegrees: Float,
        forceMoveTo: Boolean,
    ) {
        arcTo(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            startAngleDegrees,
            sweepAngleDegrees,
            forceMoveTo,
        )
    }

    override fun addArcRad(oval: Rect, startAngleRadians: Float, sweepAngleRadians: Float) {
        addArc(oval, startAngleRadians.toDegrees(), sweepAngleRadians.toDegrees())
    }

    override fun addArc(oval: Rect, startAngleDegrees: Float, sweepAngleDegrees: Float) {
        addArc(oval.left, oval.top, oval.right, oval.bottom, startAngleDegrees, sweepAngleDegrees)
    }

    // ---------------------------------------------------------------------------------------
    // Rectangles, ovals and round rects (see PathShapes.kt)
    // ---------------------------------------------------------------------------------------

    @Deprecated(
        "Prefer usage of addRect() with a winding direction",
        replaceWith = ReplaceWith("addRect(rect)"),
        level = DeprecationLevel.HIDDEN,
    )
    override fun addRect(rect: Rect) {
        addRect(rect, Path.Direction.CounterClockwise)
    }

    override fun addRect(rect: Rect, direction: Path.Direction) {
        addRect(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            direction == Path.Direction.Clockwise,
            startIndex = 0,
        )
    }

    @Deprecated(
        "Prefer usage of addOval() with a winding direction",
        replaceWith = ReplaceWith("addOval(oval)"),
        level = DeprecationLevel.HIDDEN,
    )
    override fun addOval(oval: Rect) {
        addOval(oval, Path.Direction.CounterClockwise)
    }

    override fun addOval(oval: Rect, direction: Path.Direction) {
        addOval(
            oval.left,
            oval.top,
            oval.right,
            oval.bottom,
            direction == Path.Direction.Clockwise,
            startIndex = 1,
        )
    }

    @Deprecated(
        "Prefer usage of addRoundRect() with a winding direction",
        replaceWith = ReplaceWith("addRoundRect(roundRect)"),
        level = DeprecationLevel.HIDDEN,
    )
    override fun addRoundRect(roundRect: RoundRect) {
        addRoundRect(roundRect, Path.Direction.CounterClockwise)
    }

    override fun addRoundRect(roundRect: RoundRect, direction: Path.Direction) {
        val radii =
            floatArrayOf(
                roundRect.topLeftCornerRadius.x,
                roundRect.topLeftCornerRadius.y,
                roundRect.topRightCornerRadius.x,
                roundRect.topRightCornerRadius.y,
                roundRect.bottomRightCornerRadius.x,
                roundRect.bottomRightCornerRadius.y,
                roundRect.bottomLeftCornerRadius.x,
                roundRect.bottomLeftCornerRadius.y,
            )
        val clockwise = direction == Path.Direction.Clockwise
        addRoundRect(
            roundRect.left,
            roundRect.top,
            roundRect.right,
            roundRect.bottom,
            radii,
            clockwise,
            startIndex = if (clockwise) 6 else 7,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Path composition and transformation
    // ---------------------------------------------------------------------------------------

    override fun addPath(path: Path, offset: Offset) {
        // Read right away, even when adding this path to itself: the appended entries go past the
        // end of what is read, or into new arrays if they grow.
        addPath(path.asWebPath().currentData(), offset.x, offset.y)
    }

    /** Appends all contours of [src] translated by ([dx], [dy]) (Skia's `kAppend` mode). */
    fun addPath(src: PathData, dx: Float, dy: Float) {
        addPath(src, 1f, 0f, 0f, 1f, dx, dy)
    }

    /**
     * Appends all contours of [src] mapped by the affine matrix `x' = scaleX * x + skewX * y +
     * translateX`, `y' = skewY * x + scaleY * y + translateY`.
     */
    fun addPath(
        src: PathData,
        scaleX: Float,
        skewX: Float,
        skewY: Float,
        scaleY: Float,
        translateX: Float,
        translateY: Float,
    ) {
        if (src.isEmpty) return
        ensureCapacity(src.verbCount, src.pointCount, src.conicWeightCount)
        cachedSnapshot = null
        val base = pointCount
        lastMoveToIndex =
            if (src.lastMoveToIndex >= 0) {
                src.lastMoveToIndex + base
            } else {
                src.lastMoveToIndex - base
            }
        src.verbs.copyInto(verbs, verbCount, 0, src.verbCount)
        verbCount += src.verbCount
        val srcPoints = src.points
        val identity = scaleX == 1f && skewX == 0f && skewY == 0f && scaleY == 1f
        for (i in 0 until src.pointCount) {
            val x = srcPoints[2 * i]
            val y = srcPoints[2 * i + 1]
            if (identity) {
                addPoint(x + translateX, y + translateY)
            } else {
                addPoint(scaleX * x + skewX * y + translateX, skewY * x + scaleY * y + translateY)
            }
        }
        src.conicWeights.copyInto(weights, weightCount, 0, src.conicWeightCount)
        weightCount += src.conicWeightCount
    }

    override fun reset() {
        clear(keepCapacity = false)
    }

    override fun rewind() {
        clear(keepCapacity = true)
    }

    /**
     * Empties the path, reusing its arrays. An array a snapshot holds is replaced instead: by a new
     * one of the same capacity if [keepCapacity] (for faster reuse), by an empty one otherwise.
     */
    private fun clear(keepCapacity: Boolean) {
        cachedSnapshot = null
        if (verbsShared) verbs = if (keepCapacity) ByteArray(verbs.size) else EmptyBytes
        if (pointsShared) points = if (keepCapacity) FloatArray(points.size) else EmptyFloats
        if (weightsShared) weights = if (keepCapacity) FloatArray(weights.size) else EmptyFloats
        verbsShared = false
        pointsShared = false
        weightsShared = false
        verbCount = 0
        pointCount = 0
        weightCount = 0
        lastMoveToIndex = InitialLastMoveToIndex
    }

    override fun translate(offset: Offset) {
        val dx = offset.x
        val dy = offset.y
        if (pointCount == 0 || (dx == 0f && dy == 0f)) return
        val source = points
        val target = pointsForRewrite()
        for (i in 0 until pointCount) {
            target[2 * i] = source[2 * i] + dx
            target[2 * i + 1] = source[2 * i + 1] + dy
        }
    }

    override fun transform(matrix: Matrix) {
        // Compose matrices are 4x4 column-major. Like the skiko backend, use the 3x3 matrix with
        // perspective made of the 2D components.
        val v = matrix.values
        val scaleX = v[Matrix.ScaleX]
        val skewX = v[Matrix.SkewX]
        val translateX = v[Matrix.TranslateX]
        val skewY = v[Matrix.SkewY]
        val scaleY = v[Matrix.ScaleY]
        val translateY = v[Matrix.TranslateY]
        val persp0 = v[Matrix.Perspective0]
        val persp1 = v[Matrix.Perspective1]
        val persp2 = v[Matrix.Perspective2]
        if (pointCount == 0) return
        val hasPerspective = persp0 != 0f || persp1 != 0f || persp2 != 1f
        if (!hasPerspective) {
            if (
                scaleX == 1f &&
                    skewX == 0f &&
                    translateX == 0f &&
                    skewY == 0f &&
                    scaleY == 1f &&
                    translateY == 0f
            ) {
                return
            }
            val source = points
            val target = pointsForRewrite()
            for (i in 0 until pointCount) {
                val x = source[2 * i]
                val y = source[2 * i + 1]
                target[2 * i] = scaleX * x + skewX * y + translateX
                target[2 * i + 1] = skewY * x + scaleY * y + translateY
            }
            return
        }
        transformWithPerspective(v)
    }

    /**
     * Port of the perspective branch of Skia's `SkPath::transform`: quadratics and conics become
     * conics with transformed weights (exact), cubics are subdivided, then every point is mapped
     * with the perspective division. [m] holds the values of a Compose [Matrix].
     */
    private fun transformWithPerspective(m: FloatArray) {
        val src = currentData()
        val tmp = WebPath()
        val pts = src.points
        val verbs = src.verbs
        var p = 0
        var w = 0
        val cubicHalves = FloatArray(CubicHalvesSize * CubicSubdivisionLevel)
        for (i in 0 until src.verbCount) {
            when (verbs[i]) {
                PathVerb.Move -> {
                    tmp.moveTo(pts[2 * p], pts[2 * p + 1])
                    p += 1
                }
                PathVerb.Line -> {
                    tmp.lineTo(pts[2 * p], pts[2 * p + 1])
                    p += 1
                }
                PathVerb.Quad,
                PathVerb.Conic -> {
                    val weight = if (verbs[i] == PathVerb.Conic) src.conicWeights[w++] else 1f
                    val transformedWeight =
                        transformConicWeight(
                            pts[2 * p - 2],
                            pts[2 * p - 1],
                            pts[2 * p],
                            pts[2 * p + 1],
                            pts[2 * p + 2],
                            pts[2 * p + 3],
                            weight,
                            m,
                        )
                    tmp.conicTo(
                        pts[2 * p],
                        pts[2 * p + 1],
                        pts[2 * p + 2],
                        pts[2 * p + 3],
                        transformedWeight,
                    )
                    p += 2
                }
                PathVerb.Cubic -> {
                    tmp.subdivideCubicTo(pts, 2 * p - 2, CubicSubdivisionLevel, cubicHalves, 0)
                    p += 3
                }
                PathVerb.Close -> tmp.close()
            }
        }
        val out = tmp.points
        for (i in 0 until tmp.pointCount) {
            val x = out[2 * i]
            val y = out[2 * i + 1]
            val px = m[Matrix.ScaleX] * x + m[Matrix.SkewX] * y + m[Matrix.TranslateX]
            val py = m[Matrix.SkewY] * x + m[Matrix.ScaleY] * y + m[Matrix.TranslateY]
            var z = m[Matrix.Perspective0] * x + m[Matrix.Perspective1] * y + m[Matrix.Perspective2]
            if (z != 0f) z = 1f / z
            out[2 * i] = px * z
            out[2 * i + 1] = py * z
        }
        tmp.fillType = currentFillType
        adopt(tmp.snapshot())
    }

    /**
     * Adds the cubic whose 4 points are the 8 floats of [cubic] from [at], split in halves [level]
     * times (Skia's `subdivide_cubic_to`). The halves of each level are written into [halves] from
     * [offset], [CubicHalvesSize] floats per level.
     */
    private fun subdivideCubicTo(
        cubic: FloatArray,
        at: Int,
        level: Int,
        halves: FloatArray,
        offset: Int,
    ) {
        if (level == 0) {
            cubicTo(
                cubic[at + 2],
                cubic[at + 3],
                cubic[at + 4],
                cubic[at + 5],
                cubic[at + 6],
                cubic[at + 7],
            )
            return
        }
        chopCubicAt(
            cubic[at],
            cubic[at + 1],
            cubic[at + 2],
            cubic[at + 3],
            cubic[at + 4],
            cubic[at + 5],
            cubic[at + 6],
            cubic[at + 7],
            0.5f,
            halves,
            offset,
        )
        // The 7 points of the halves: the first ends where the second starts. The next level writes
        // past them, so both stay for their own split.
        subdivideCubicTo(halves, offset, level - 1, halves, offset + CubicHalvesSize)
        subdivideCubicTo(halves, offset + 6, level - 1, halves, offset + CubicHalvesSize)
    }

    override fun op(path1: Path, path2: Path, operation: PathOperation): Boolean {
        // The operands are read right away; the result is a new path nothing else holds.
        val result =
            pathOp(path1.asWebPath().currentData(), path2.asWebPath().currentData(), operation)
                ?: return false
        adopt(result)
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Members with default implementations relying on the backend registry: create the web
    // classes directly so that WebPath also works without a registered backend.
    // ---------------------------------------------------------------------------------------

    override fun iterator(): PathIterator =
        WebPathIterator(this, PathIterator.ConicEvaluation.AsQuadratics, 0.25f)

    override fun iterator(
        conicEvaluation: PathIterator.ConicEvaluation,
        tolerance: Float,
    ): PathIterator = WebPathIterator(this, conicEvaluation, tolerance)

    override fun plus(path: Path): Path =
        WebPath().apply { op(this@WebPath, path, PathOperation.Union) }

    override fun minus(path: Path): Path =
        WebPath().apply { op(this@WebPath, path, PathOperation.Difference) }

    override fun or(path: Path): Path = this + path

    override fun and(path: Path): Path =
        WebPath().apply { op(this@WebPath, path, PathOperation.Intersect) }

    override fun xor(path: Path): Path =
        WebPath().apply { op(this@WebPath, path, PathOperation.Xor) }

    override fun toString(): String = "WebPath(${currentData()})"
}

/** Returns this path as a [WebPath], throwing for paths created by another backend. */
internal fun Path.asWebPath(): WebPath {
    require(this is WebPath) {
        "Only WebPath instances are supported by the web canvas backend, but received ${this::class}"
    }
    return this
}

private val EmptyBytes = ByteArray(0)
private val EmptyFloats = FloatArray(0)

private fun Float.toDegrees(): Float = this * (180f / PI.toFloat())

/**
 * Computes the weight of a conic after a perspective transform (Skia's `SkConic::TransformW`). [m]
 * holds the values of a Compose [Matrix].
 */
private fun transformConicWeight(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    weight: Float,
    m: FloatArray,
): Float {
    val px = m[Matrix.Perspective0].toDouble()
    val py = m[Matrix.Perspective1].toDouble()
    val pw = m[Matrix.Perspective2].toDouble()
    val w0 = px * x0 + py * y0 + pw
    val w1 = px * x1 * weight + py * y1 * weight + pw * weight
    val w2 = px * x2 + py * y2 + pw
    return sqrt((w1 * w1) / (w0 * w2)).toFloat()
}

/** How many times a cubic is split in halves before a perspective transform. */
private const val CubicSubdivisionLevel = 2

/** The floats [WebPath.subdivideCubicTo] writes per level: the 7 points of 2 halves. */
private const val CubicHalvesSize = 14
