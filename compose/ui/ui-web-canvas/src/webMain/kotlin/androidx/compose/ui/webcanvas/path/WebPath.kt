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

package androidx.compose.ui.webcanvas.path

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathOperation
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Kotlin implementation of the Compose [Path] for the web canvas backend.
 *
 * The geometry is stored like Skia's `SkPath` (verbs, points, conic weights) and every builder call
 * follows the `SkPath` semantics used by the skiko backend: implicit moveTo injection, close
 * handling, arcs/ovals/round rects as conics with Skia's start points and directions, etc.
 *
 * [snapshot] returns an immutable [PathData] view that stays valid when the path is mutated later:
 * appends write past the end of the snapshot and the arrays are copied lazily before any in-place
 * rewrite.
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

    /**
     * True if the arrays may be referenced by a snapshot: they must be copied before rewriting
     * existing entries, while appending past the end of the snapshot is fine.
     */
    private var arraysShared = false

    /**
     * True if the arrays were taken from a [PathData] created elsewhere (see [setFrom]): other
     * paths may append to them too, so they must be copied before any write.
     */
    private var arraysBorrowed = false

    /**
     * Returns an immutable view of the current geometry. Repeated calls without mutations in
     * between return the same instance (so its caches, e.g. the `Path2D`, are reused).
     */
    fun snapshot(): PathData {
        cachedSnapshot?.let {
            return it
        }
        val data =
            PathData(
                verbs,
                verbCount,
                points,
                pointCount,
                weights,
                weightCount,
                currentFillType,
                lastMoveToIndex,
            )
        cachedSnapshot = data
        arraysShared = true
        return data
    }

    /** Returns the browser `Path2D` for the current geometry (cached on the snapshot). */
    fun toPath2D(): JsAny = snapshot().toPath2D()

    override var fillType: PathFillType
        get() = currentFillType
        set(value) {
            if (value != currentFillType) {
                currentFillType = value
                cachedSnapshot = null
            }
        }

    override val isConvex: Boolean
        get() = snapshot().isConvex

    override val isEmpty: Boolean
        get() = verbCount == 0

    override fun getBounds(): Rect = snapshot().bounds

    // ---------------------------------------------------------------------------------------
    // Low level storage
    // ---------------------------------------------------------------------------------------

    private fun ensureCapacity(extraVerbs: Int, extraPoints: Int, extraWeights: Int) {
        if (arraysBorrowed) {
            verbs = verbs.copyOf(grow(verbCount, verbCount + extraVerbs))
            points = points.copyOf(2 * grow(pointCount, pointCount + extraPoints))
            weights = weights.copyOf(grow(weightCount, weightCount + extraWeights))
            arraysBorrowed = false
            arraysShared = false
            return
        }
        if (verbCount + extraVerbs > verbs.size) {
            verbs = verbs.copyOf(grow(verbs.size, verbCount + extraVerbs))
        }
        if (2 * (pointCount + extraPoints) > points.size) {
            points = points.copyOf(2 * grow(points.size / 2, pointCount + extraPoints))
        }
        if (weightCount + extraWeights > weights.size) {
            weights = weights.copyOf(grow(weights.size, weightCount + extraWeights))
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

    /** Must be called before rewriting existing entries of the arrays in place. */
    private fun prepareForRewrite() {
        cachedSnapshot = null
        if (arraysShared || arraysBorrowed) {
            verbs = verbs.copyOf(verbCount)
            points = points.copyOf(2 * pointCount)
            weights = weights.copyOf(weightCount)
            arraysShared = false
            arraysBorrowed = false
        }
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
     * Replaces the whole content of this path with [data] (including its fill type), without
     * copying the arrays until the next mutation.
     *
     * @param exclusive true if [data] was produced by a temporary path that is discarded: this path
     *   then becomes the only writer of the arrays and may append to them in place.
     */
    internal fun setFrom(data: PathData, exclusive: Boolean = false) {
        // [data] describes exactly the new state, so it can serve as the current snapshot.
        cachedSnapshot = data
        verbs = data.verbs
        verbCount = data.verbCount
        points = data.points
        pointCount = data.pointCount
        weights = data.conicWeights
        weightCount = data.conicWeightCount
        lastMoveToIndex = data.lastMoveToIndex
        currentFillType = data.fillType
        // The arrays are referenced by [data], which must stay immutable.
        arraysShared = true
        arraysBorrowed = !exclusive
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

    /** Adds a conic segment relative to the current point (Skia's `SkPath::rConicTo`). */
    fun relativeConicTo(dx1: Float, dy1: Float, dx2: Float, dy2: Float, weight: Float) {
        injectMoveToIfNeeded()
        val x = lastPointX()
        val y = lastPointY()
        conicTo(x + dx1, y + dy1, x + dx2, y + dy2, weight)
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
    // Arcs
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

    /** Port of Skia's `SkPath::arcTo(oval, startAngle, sweepAngle, forceMoveTo)`. */
    fun arcTo(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        startAngleDegrees: Float,
        sweepAngleDegrees: Float,
        forceMoveTo: Boolean,
    ) {
        // Skia computes arcs in single precision and relies on its rounding for exact
        // comparisons, hence the f32() calls (only effective on Kotlin/JS).
        val width = f32(right - left)
        val height = f32(bottom - top)
        if (width < 0f || height < 0f) return

        val startAngle = f32(startAngleDegrees % 360f)
        val sweepAngle = sweepAngleDegrees
        val moveTo = forceMoveTo || verbCount == 0

        // Lone point cases.
        if (sweepAngle == 0f && (startAngle == 0f || startAngle == 360f)) {
            // Chrome uses this path to move into and out of ovals.
            addArcPoint(right, midpoint(top, bottom), moveTo)
            return
        } else if (width == 0f && height == 0f) {
            addArcPoint(right, top, moveTo)
            return
        }

        // angles_to_unit_vectors
        val startRad = degreesToRadians(startAngle)
        var stopRad = degreesToRadians(f32(startAngle + sweepAngle))
        val startX = cosSnapToZero(startRad)
        val startY = sinSnapToZero(startRad)
        var stopX = cosSnapToZero(stopRad)
        var stopY = sinSnapToZero(stopRad)
        // If the sweep angle is nearly (but less than) 360, precision loss may produce coincident
        // vectors; tweak the stop vector so that a nearly complete circle is still drawn.
        if (startX == stopX && startY == stopY) {
            val sw = abs(sweepAngle)
            if (sw < 360f && sw > 359f) {
                val deltaRad = if (sweepAngle < 0f) -(1f / 512f) else (1f / 512f)
                do {
                    stopRad = f32(stopRad - deltaRad)
                    stopY = sinSnapToZero(stopRad)
                    stopX = cosSnapToZero(stopRad)
                } while (startX == stopX && startY == stopY)
            }
        }
        val clockwise = sweepAngle > 0f

        val centerX = midpoint(left, right)
        val centerY = midpoint(top, bottom)
        val radiusX = width * 0.5f
        val radiusY = height * 0.5f

        if (startX == stopX && startY == stopY) {
            // The sweep angle is too small for angles_to_unit_vectors: draw a line.
            val endAngle = degreesToRadians(f32(startAngle + sweepAngle))
            addArcPoint(
                centerX + radiusX * cos(endAngle),
                centerY + radiusY * sin(endAngle),
                moveTo,
            )
            return
        }

        val conics = FloatArray(MaxConicsForArc * 7)
        val count =
            buildUnitArc(
                startX,
                startY,
                stopX,
                stopY,
                clockwise,
                radiusX,
                radiusY,
                centerX,
                centerY,
                conics,
            )
        if (count == 0) {
            addArcPoint(centerX + radiusX * stopX, centerY + radiusY * stopY, moveTo)
            return
        }
        addArcPoint(conics[0], conics[1], moveTo)
        for (i in 0 until count) {
            val o = i * 7
            conicTo(conics[o + 2], conics[o + 3], conics[o + 4], conics[o + 5], conics[o + 6])
        }
    }

    /**
     * Adds a moveTo to ([x], [y]) if [moveTo] is set, otherwise a lineTo unless the current point
     * is sufficiently close already (avoids spurious lines between contiguous arcs).
     */
    private fun addArcPoint(x: Float, y: Float, moveTo: Boolean) {
        if (moveTo) {
            moveTo(x, y)
        } else if (
            pointCount == 0 || !nearlyEqual(lastPointX(), x) || !nearlyEqual(lastPointY(), y)
        ) {
            lineTo(x, y)
        }
    }

    override fun addArcRad(oval: Rect, startAngleRadians: Float, sweepAngleRadians: Float) {
        addArc(oval, startAngleRadians.toDegrees(), sweepAngleRadians.toDegrees())
    }

    override fun addArc(oval: Rect, startAngleDegrees: Float, sweepAngleDegrees: Float) {
        val left = oval.left
        val top = oval.top
        val right = oval.right
        val bottom = oval.bottom
        if (!(left < right && top < bottom) || sweepAngleDegrees == 0f) return

        if (sweepAngleDegrees >= 360f || sweepAngleDegrees <= -360f) {
            // We can treat the arc as an oval if it begins at one of our legal starting positions.
            val startOver90 = startAngleDegrees / 90f
            val startOver90I = floor(startOver90 + 0.5f)
            val error = startOver90 - startOver90I
            if (nearlyEqual(error, 0f)) {
                // Index 1 is at startAngle == 0.
                var startIndex = (startOver90I + 1f) % 4f
                if (startIndex < 0f) startIndex += 4f
                addOval(left, top, right, bottom, sweepAngleDegrees > 0f, startIndex.toInt())
                return
            }
        }
        arcTo(left, top, right, bottom, startAngleDegrees, sweepAngleDegrees, forceMoveTo = true)
    }

    // ---------------------------------------------------------------------------------------
    // Shapes
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

    /** Port of Skia's `SkPath::addRect(rect, dir, startIndex)`. */
    fun addRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        clockwise: Boolean,
        startIndex: Int,
    ) {
        ensureCapacity(5, 4, 0)
        var index = startIndex and 3
        val advance = if (clockwise) 1 else 3
        moveTo(rectPointX(index, left, right), rectPointY(index, top, bottom))
        for (i in 0 until 3) {
            index = (index + advance) and 3
            lineTo(rectPointX(index, left, right), rectPointY(index, top, bottom))
        }
        close()
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

    /** Port of Skia's `SkPath::addOval(oval, dir, startIndex)`: 4 quarter conics. */
    fun addOval(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        clockwise: Boolean,
        startIndex: Int,
    ) {
        ensureCapacity(6, 9, 4)
        val centerX = midpoint(left, right)
        val centerY = midpoint(top, bottom)
        val advance = if (clockwise) 1 else 3
        var ovalIndex = startIndex and 3
        // The corner iterator points are tracking "behind" the oval points.
        var rectIndex = (startIndex + if (clockwise) 0 else 1) and 3
        moveTo(
            ovalPointX(ovalIndex, left, right, centerX),
            ovalPointY(ovalIndex, top, bottom, centerY),
        )
        for (i in 0 until 4) {
            rectIndex = (rectIndex + advance) and 3
            ovalIndex = (ovalIndex + advance) and 3
            conicTo(
                rectPointX(rectIndex, left, right),
                rectPointY(rectIndex, top, bottom),
                ovalPointX(ovalIndex, left, right, centerX),
                ovalPointY(ovalIndex, top, bottom, centerY),
                QuarterConicWeight,
            )
        }
        close()
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

    /**
     * Port of Skia's `SkPath::addRRect(rrect, dir, startIndex)`. [radii] holds the x/y radii of the
     * top-left, top-right, bottom-right and bottom-left corners; they are normalized like
     * `SkRRect::setRectRadii` (negative radii clamped, overlapping radii scaled down).
     */
    fun addRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radii: FloatArray,
        clockwise: Boolean,
        startIndex: Int,
    ) {
        val rrect = NormalizedRoundRect(left, top, right, bottom, radii)
        val l = rrect.left
        val t = rrect.top
        val r = rrect.right
        val b = rrect.bottom
        when (rrect.type) {
            NormalizedRoundRect.TypeEmpty,
            NormalizedRoundRect.TypeRect -> {
                // Degenerate (rect) => radii points are collapsing.
                addRect(l, t, r, b, clockwise, (startIndex + 1) / 2)
            }
            NormalizedRoundRect.TypeOval -> {
                // Degenerate (oval) => line points are collapsing.
                addOval(l, t, r, b, clockwise, startIndex / 2)
            }
            else -> {
                ensureCapacity(10, 13, 4)
                val rr = rrect.radii
                // Points of SkPath_RRectPointIterator.
                val xs = floatArrayOf(l + rr[0], r - rr[2], r, r, r - rr[4], l + rr[6], l, l)
                val ys = floatArrayOf(t, t, t + rr[3], b - rr[5], b, b, b - rr[7], t + rr[1])
                // We start with a conic on odd indices when moving CW vs. even indices when moving
                // CCW.
                val startsWithConic = ((startIndex and 1) == 1) == clockwise
                val rrectAdvance = if (clockwise) 1 else 7
                val rectAdvance = if (clockwise) 1 else 3
                var rrectIndex = startIndex and 7
                // Corner iterator indices follow the collapsed radii model, adjusted such that
                // the start point is "behind" the radii start point.
                var rectIndex = (startIndex / 2 + if (clockwise) 0 else 1) and 3

                fun nextRRect() {
                    rrectIndex = (rrectIndex + rrectAdvance) and 7
                }
                fun conic() {
                    rectIndex = (rectIndex + rectAdvance) and 3
                    nextRRect()
                    conicTo(
                        rectPointX(rectIndex, l, r),
                        rectPointY(rectIndex, t, b),
                        xs[rrectIndex],
                        ys[rrectIndex],
                        QuarterConicWeight,
                    )
                }
                fun line() {
                    nextRRect()
                    lineTo(xs[rrectIndex], ys[rrectIndex])
                }

                moveTo(xs[rrectIndex], ys[rrectIndex])
                if (startsWithConic) {
                    for (i in 0 until 3) {
                        conic()
                        line()
                    }
                    conic()
                    // Final lineTo handled by close().
                } else {
                    for (i in 0 until 4) {
                        line()
                        conic()
                    }
                }
                close()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Path composition and transformation
    // ---------------------------------------------------------------------------------------

    override fun addPath(path: Path, offset: Offset) {
        addPath(path.asWebPath().snapshot(), offset.x, offset.y)
    }

    /** Appends all contours of [src] translated by ([dx], [dy]) (Skia's `kAppend` mode). */
    fun addPath(src: PathData, dx: Float = 0f, dy: Float = 0f) {
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
        cachedSnapshot = null
        if (arraysShared || arraysBorrowed) {
            verbs = EmptyBytes
            points = EmptyFloats
            weights = EmptyFloats
            arraysShared = false
            arraysBorrowed = false
        }
        verbCount = 0
        pointCount = 0
        weightCount = 0
        lastMoveToIndex = InitialLastMoveToIndex
    }

    override fun rewind() {
        cachedSnapshot = null
        if (arraysShared || arraysBorrowed) {
            // Keep the capacity for faster reuse, but don't overwrite the snapshot's data.
            verbs = ByteArray(verbs.size)
            points = FloatArray(points.size)
            weights = FloatArray(weights.size)
            arraysShared = false
            arraysBorrowed = false
        }
        verbCount = 0
        pointCount = 0
        weightCount = 0
        lastMoveToIndex = InitialLastMoveToIndex
    }

    override fun translate(offset: Offset) {
        val dx = offset.x
        val dy = offset.y
        if (pointCount == 0 || (dx == 0f && dy == 0f)) return
        prepareForRewrite()
        val pts = points
        for (i in 0 until pointCount) {
            pts[2 * i] += dx
            pts[2 * i + 1] += dy
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
            prepareForRewrite()
            val pts = points
            for (i in 0 until pointCount) {
                val x = pts[2 * i]
                val y = pts[2 * i + 1]
                pts[2 * i] = scaleX * x + skewX * y + translateX
                pts[2 * i + 1] = skewY * x + scaleY * y + translateY
            }
            return
        }
        transformWithPerspective(
            floatArrayOf(
                scaleX,
                skewX,
                translateX,
                skewY,
                scaleY,
                translateY,
                persp0,
                persp1,
                persp2,
            )
        )
    }

    /**
     * Port of the perspective branch of Skia's `SkPath::transform`: quadratics and conics become
     * conics with transformed weights (exact), cubics are subdivided, then every point is mapped
     * with the perspective division.
     */
    private fun transformWithPerspective(m: FloatArray) {
        val src = snapshot()
        val tmp = WebPath()
        val pts = src.points
        val verbs = src.verbs
        var p = 0
        var w = 0
        val cubicHalves = FloatArray(14)
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
                    tmp.subdivideCubicTo(
                        pts[2 * p - 2],
                        pts[2 * p - 1],
                        pts[2 * p],
                        pts[2 * p + 1],
                        pts[2 * p + 2],
                        pts[2 * p + 3],
                        pts[2 * p + 4],
                        pts[2 * p + 5],
                        level = 2,
                        scratch = cubicHalves,
                    )
                    p += 3
                }
                PathVerb.Close -> tmp.close()
            }
        }
        val out = tmp.points
        for (i in 0 until tmp.pointCount) {
            val x = out[2 * i]
            val y = out[2 * i + 1]
            val px = m[0] * x + m[1] * y + m[2]
            val py = m[3] * x + m[4] * y + m[5]
            var z = m[6] * x + m[7] * y + m[8]
            if (z != 0f) z = 1f / z
            out[2 * i] = px * z
            out[2 * i + 1] = py * z
        }
        val fill = currentFillType
        tmp.fillType = fill
        setFrom(tmp.snapshot(), exclusive = true)
    }

    private fun subdivideCubicTo(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x3: Float,
        y3: Float,
        level: Int,
        scratch: FloatArray,
    ) {
        if (level > 0) {
            chopCubicAt(x0, y0, x1, y1, x2, y2, x3, y3, 0.5f, scratch, 0)
            val h = scratch.copyOf()
            subdivideCubicTo(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], level - 1, scratch)
            subdivideCubicTo(h[6], h[7], h[8], h[9], h[10], h[11], h[12], h[13], level - 1, scratch)
        } else {
            cubicTo(x1, y1, x2, y2, x3, y3)
        }
    }

    override fun op(path1: Path, path2: Path, operation: PathOperation): Boolean {
        val result =
            pathOp(path1.asWebPath().snapshot(), path2.asWebPath().snapshot(), operation)
                ?: return false
        // Not exclusive: keep it safe should the operation ever return an operand as is.
        setFrom(result)
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

    override fun toString(): String = "WebPath(${snapshot()})"
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

/** Maximum number of conics produced for an arc (Skia's `SkConic::kMaxConicsForArc`). */
private const val MaxConicsForArc = 5

/** `SK_ScalarPI / 180` in single precision. */
private const val DegreesToRadians = 0.017453292f

private fun degreesToRadians(degrees: Float): Float = f32(degrees * f32(DegreesToRadians))

private fun Float.toDegrees(): Float = this * (180f / PI.toFloat())

private fun sinSnapToZero(radians: Float): Float {
    val v = f32(sin(radians))
    return if (abs(v) <= ScalarNearlyZero) 0f else v
}

private fun cosSnapToZero(radians: Float): Float {
    val v = f32(cos(radians))
    return if (abs(v) <= ScalarNearlyZero) 0f else v
}

/** Skia's `sk_float_midpoint`, used by `SkRect::centerX/centerY`. */
internal fun midpoint(a: Float, b: Float): Float = ((a.toDouble() + b.toDouble()) * 0.5).toFloat()

/** X coordinate of `SkPath_RectPointIterator` point [index]. */
private fun rectPointX(index: Int, left: Float, right: Float): Float =
    if (index == 0 || index == 3) left else right

/** Y coordinate of `SkPath_RectPointIterator` point [index]. */
private fun rectPointY(index: Int, top: Float, bottom: Float): Float =
    if (index < 2) top else bottom

/** X coordinate of `SkPath_OvalPointIterator` point [index]. */
private fun ovalPointX(index: Int, left: Float, right: Float, centerX: Float): Float =
    when (index) {
        1 -> right
        3 -> left
        else -> centerX
    }

/** Y coordinate of `SkPath_OvalPointIterator` point [index]. */
private fun ovalPointY(index: Int, top: Float, bottom: Float, centerY: Float): Float =
    when (index) {
        0 -> top
        2 -> bottom
        else -> centerY
    }

/**
 * Port of Skia's `SkConic::BuildUnitArc` followed by the mapping into the oval: writes up to
 * [MaxConicsForArc] conics as (x0, y0, x1, y1, x2, y2, w) records into [dst] and returns their
 * count. Returns 0 when the start and stop vectors are effectively coincident.
 */
private fun buildUnitArc(
    startX: Float,
    startY: Float,
    stopX: Float,
    stopY: Float,
    clockwise: Boolean,
    radiusX: Float,
    radiusY: Float,
    centerX: Float,
    centerY: Float,
    dst: FloatArray,
): Int {
    // Rotate by (startX, startY) so that the start vector is (1, 0).
    val x = f32(startX * stopX + startY * stopY)
    var y = f32(startX * stopY - startY * stopX)
    val absY = abs(y)

    // Check for (effectively) coincident vectors: this can happen if our angle is nearly 0 or
    // nearly 180 (y == 0); we use the dot product to distinguish between 0 and 180 (x > 0).
    if (absY <= ScalarNearlyZero && x > 0f && ((y >= 0f && clockwise) || (y <= 0f && !clockwise))) {
        return 0
    }
    if (!clockwise) y = -y

    // We decide to use 1 conic per quadrant of a circle. What quadrant does (x, y) lie in?
    var quadrant = 0
    if (y == 0f) {
        quadrant = 2 // 180
    } else if (x == 0f) {
        quadrant = if (y > 0f) 1 else 3 // 90 / 270
    } else {
        if (y < 0f) quadrant += 2
        if ((x < 0f) != (y < 0f)) quadrant += 1
    }

    var conicCount = 0
    for (i in 0 until quadrant) {
        val o = conicCount * 7
        dst[o] = QuadrantX[2 * i]
        dst[o + 1] = QuadrantY[2 * i]
        dst[o + 2] = QuadrantX[2 * i + 1]
        dst[o + 3] = QuadrantY[2 * i + 1]
        dst[o + 4] = QuadrantX[2 * i + 2]
        dst[o + 5] = QuadrantY[2 * i + 2]
        dst[o + 6] = QuarterConicWeight
        conicCount++
    }

    // Now compute any remaining (sub-90-degree) arc for the last conic.
    val lastQx = QuadrantX[quadrant * 2]
    val lastQy = QuadrantY[quadrant * 2]
    val dot = f32(lastQx * x + lastQy * y)
    if (dot < 1f) {
        var offX = f32(lastQx + x)
        var offY = f32(lastQy + y)
        // Compute the bisector vector, and then rescale to be the off-curve point. The length is
        // 1 / cos(theta / 2), and cos(theta / 2) is also the conic weight.
        val cosThetaOver2 = f32(sqrt(f32((1f + dot) / 2f)))
        val targetLength = f32(1f / cosThetaOver2)
        val length = sqrt(offX.toDouble() * offX + offY.toDouble() * offY)
        val scale = targetLength / length
        val scaledX = f32((offX * scale).toFloat())
        val scaledY = f32((offY * scale).toFloat())
        if (scaledX.isFinite() && scaledY.isFinite() && (scaledX != 0f || scaledY != 0f)) {
            offX = scaledX
            offY = scaledY
        } else {
            offX = 0f
            offY = 0f
        }
        if (lastQx != offX || lastQy != offY) {
            val o = conicCount * 7
            dst[o] = lastQx
            dst[o + 1] = lastQy
            dst[o + 2] = offX
            dst[o + 3] = offY
            dst[o + 4] = x
            dst[o + 5] = y
            dst[o + 6] = cosThetaOver2
            conicCount++
        }
    }

    // Undo the rotation (and the y flip for counter-clockwise arcs), then map into the oval.
    for (i in 0 until conicCount) {
        val o = i * 7
        for (k in 0 until 3) {
            val px = dst[o + 2 * k]
            val py = if (clockwise) dst[o + 2 * k + 1] else -dst[o + 2 * k + 1]
            val rx = startX * px - startY * py
            val ry = startY * px + startX * py
            dst[o + 2 * k] = rx * radiusX + centerX
            dst[o + 2 * k + 1] = ry * radiusY + centerY
        }
    }
    return conicCount
}

private val QuadrantX = floatArrayOf(1f, 1f, 0f, -1f, -1f, -1f, 0f, 1f, 1f)
private val QuadrantY = floatArrayOf(0f, 1f, 1f, 1f, 0f, -1f, -1f, -1f, 0f)

/**
 * Computes the weight of a conic after a perspective transform (Skia's `SkConic::TransformW`). [m]
 * is a row-major 3x3 matrix.
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
    val w0 = m[6].toDouble() * x0 + m[7].toDouble() * y0 + m[8]
    val w1 =
        (m[6].toDouble() * x1 * weight + m[7].toDouble() * y1 * weight + m[8].toDouble() * weight)
    val w2 = m[6].toDouble() * x2 + m[7].toDouble() * y2 + m[8]
    return sqrt((w1 * w1) / (w0 * w2)).toFloat()
}

/**
 * A round rect normalized like Skia's `SkRRect::setRectRadii`: sorted bounds, clamped and scaled
 * radii and the resulting type.
 */
internal class NormalizedRoundRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    inputRadii: FloatArray,
) {
    var left = 0f
        private set

    var top = 0f
        private set

    var right = 0f
        private set

    var bottom = 0f
        private set

    /** x/y radii of the top-left, top-right, bottom-right and bottom-left corners. */
    val radii = FloatArray(8)

    var type = TypeEmpty
        private set

    init {
        normalize(left, top, right, bottom, inputRadii)
    }

    private fun normalize(l: Float, t: Float, r: Float, b: Float, input: FloatArray) {
        // initializeRect: check finiteness before sorting because sorting can hide NaNs.
        if (!l.isFinite() || !t.isFinite() || !r.isFinite() || !b.isFinite()) {
            return // Empty rrect at (0, 0, 0, 0).
        }
        left = minOf(l, r)
        right = maxOf(l, r)
        top = minOf(t, b)
        bottom = maxOf(t, b)
        if (!(left < right && top < bottom)) {
            type = TypeEmpty
            return
        }
        for (i in 0 until 8) {
            if (!input[i].isFinite()) {
                type = TypeRect // Devolve into a simple rect.
                return
            }
            radii[i] = input[i]
        }
        if (clampToZero(radii)) {
            type = TypeRect
            return
        }
        scaleRadii()
    }

    private fun scaleRadii() {
        // Proportionally scale down all radii to fit (W3C css3-background, "Overlapping Curves").
        var scale = 1.0
        val width = right.toDouble() - left.toDouble()
        val height = bottom.toDouble() - top.toDouble()
        scale = computeMinScale(radii[0], radii[2], width, scale)
        scale = computeMinScale(radii[3], radii[5], height, scale)
        scale = computeMinScale(radii[4], radii[6], width, scale)
        scale = computeMinScale(radii[7], radii[1], height, scale)

        flushToZero(0, 2)
        flushToZero(3, 5)
        flushToZero(4, 6)
        flushToZero(7, 1)

        if (scale < 1.0) {
            adjustRadii(width, scale, 0, 2)
            adjustRadii(height, scale, 3, 5)
            adjustRadii(width, scale, 4, 6)
            adjustRadii(height, scale, 7, 1)
        }
        // adjustRadii may set x or y to zero; set the companion to zero as well.
        clampToZero(radii)
        computeType()
    }

    private fun computeMinScale(rad1: Float, rad2: Float, limit: Double, curMin: Double): Double {
        val sum = rad1.toDouble() + rad2.toDouble()
        return if (sum > limit) minOf(curMin, limit / sum) else curMin
    }

    private fun flushToZero(a: Int, b: Int) {
        // Single precision sum, like Skia (see f32).
        val sum = f32(radii[a] + radii[b])
        if (sum == radii[a]) {
            radii[b] = 0f
        } else if (sum == radii[b]) {
            radii[a] = 0f
        }
    }

    /** Port of `SkScaleToSides::AdjustRadii`. */
    private fun adjustRadii(limit: Double, scale: Double, a: Int, b: Int) {
        radii[a] = (radii[a].toDouble() * scale).toFloat()
        radii[b] = (radii[b].toDouble() * scale).toFloat()
        // The sums are computed in single precision, like Skia.
        if (f32(radii[a] + radii[b]).toDouble() > limit) {
            val minIndex: Int
            val maxIndex: Int
            if (radii[a] > radii[b]) {
                minIndex = b
                maxIndex = a
            } else {
                minIndex = a
                maxIndex = b
            }
            val newMin = radii[minIndex]
            var newMax = f32((limit - newMin).toFloat())
            // Reduce newMax an ulp at a time until it fits.
            while (f32(newMax + newMin).toDouble() > limit) {
                newMax = nextFloatTowardsZero(newMax)
            }
            radii[maxIndex] = newMax
        }
    }

    private fun computeType() {
        var allRadiiEqual = true
        var allCornersSquare = radii[0] == 0f || radii[1] == 0f
        for (i in 1 until 4) {
            if (radii[2 * i] != 0f && radii[2 * i + 1] != 0f) {
                // If either radius is zero the corner is square so both have to be non-zero to
                // have a rounded corner.
                allCornersSquare = false
            }
            if (radii[2 * i] != radii[2 * i - 2] || radii[2 * i + 1] != radii[2 * i - 1]) {
                allRadiiEqual = false
            }
        }
        type =
            when {
                allCornersSquare -> TypeRect
                allRadiiEqual &&
                    radii[0] >= f32(f32(right - left) * 0.5f) &&
                    radii[1] >= f32(f32(bottom - top) * 0.5f) -> TypeOval
                else -> TypeComplex
            }
    }

    companion object {
        const val TypeEmpty = 0
        const val TypeRect = 1
        const val TypeOval = 2
        const val TypeComplex = 3

        /** Clamps non-positive radii to zero; returns true if all corners are square. */
        private fun clampToZero(radii: FloatArray): Boolean {
            var allCornersSquare = true
            for (i in 0 until 4) {
                if (radii[2 * i] <= 0f || radii[2 * i + 1] <= 0f) {
                    radii[2 * i] = 0f
                    radii[2 * i + 1] = 0f
                } else {
                    allCornersSquare = false
                }
            }
            return allCornersSquare
        }
    }
}
