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

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathFillType
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * Verb codes stored in [PathData.verbs]. The numbering matches Skia's `SkPathVerb`.
 *
 * Every verb except [Move] and [Close] implicitly starts at the last point of the previous verb,
 * which is always present: a path never contains a drawing verb that is not preceded by a [Move] in
 * the same contour.
 */
internal object PathVerb {
    /** Starts a new contour; consumes 1 point. */
    const val Move: Byte = 0
    /** Straight line; consumes 1 point (the end point). */
    const val Line: Byte = 1
    /** Quadratic Bézier; consumes 2 points (control, end). */
    const val Quad: Byte = 2
    /** Conic (rational quadratic); consumes 2 points (control, end) and 1 conic weight. */
    const val Conic: Byte = 3
    /** Cubic Bézier; consumes 3 points (control 1, control 2, end). */
    const val Cubic: Byte = 4
    /** Closes the current contour; consumes no point. */
    const val Close: Byte = 5

    /** Returns the number of points stored for [verb]. */
    fun pointCount(verb: Byte): Int =
        when (verb) {
            Move,
            Line -> 1
            Quad,
            Conic -> 2
            Cubic -> 3
            else -> 0
        }
}

/**
 * Immutable snapshot of the geometry of a [WebPath].
 *
 * The storage mirrors Skia's `SkPathRef`: a verb array, an interleaved x/y point array and an array
 * of conic weights. The arrays may be larger than the logical content and may be shared with the
 * [WebPath] the snapshot was taken from (which only appends past the end of the snapshot, or copies
 * the arrays before rewriting them), so only the first [verbCount] verbs, [pointCount] points and
 * [conicWeightCount] weights are meaningful. Callers must never mutate the arrays.
 *
 * Derived data (bounds, convexity, the browser `Path2D`, flattened polygons, ...) is computed
 * lazily and cached on the snapshot.
 */
internal class PathData
internal constructor(
    /** Path verbs, see [PathVerb]. Only the first [verbCount] entries are valid. */
    val verbs: ByteArray,
    /** Number of valid entries in [verbs]. */
    val verbCount: Int,
    /** Interleaved x/y coordinates. Only the first `2 * pointCount` entries are valid. */
    val points: FloatArray,
    /** Number of valid points (not floats) in [points]. */
    val pointCount: Int,
    /**
     * One weight per [PathVerb.Conic] verb, in order. Only [conicWeightCount] entries are valid.
     */
    val conicWeights: FloatArray,
    /** Number of valid entries in [conicWeights]. */
    val conicWeightCount: Int,
    /** The fill rule of the path. */
    val fillType: PathFillType,
    /**
     * Index of the point of the last moveTo, encoded like Skia's `fLastMoveToIndex`: the bitwise
     * inverse of the index when the last contour has been closed (or `-1` for a fresh path).
     */
    internal val lastMoveToIndex: Int,
) {
    /** True if the path has no verbs at all (Skia's `SkPath::isEmpty`). */
    val isEmpty: Boolean
        get() = verbCount == 0

    private var boundsState = BoundsUnknown
    private var boundsLeft = 0f
    private var boundsTop = 0f
    private var boundsRight = 0f
    private var boundsBottom = 0f
    private var boundsRect: Rect? = null

    /** True if all the coordinates of the path are finite. */
    val isFinite: Boolean
        get() {
            ensureBounds()
            return boundsState == BoundsFinite
        }

    /**
     * Bounds of all the points of the path, including control points and the points of moveTo
     * verbs. [Rect.Zero] for an empty path or a path with non-finite coordinates (like Skia).
     */
    val bounds: Rect
        get() {
            boundsRect?.let {
                return it
            }
            ensureBounds()
            val rect =
                if (boundsState == BoundsFinite) {
                    Rect(boundsLeft, boundsTop, boundsRight, boundsBottom)
                } else {
                    Rect.Zero
                }
            boundsRect = rect
            return rect
        }

    /** Left edge of [bounds], without allocating. */
    val left: Float
        get() {
            ensureBounds()
            return boundsLeft
        }

    /** Top edge of [bounds], without allocating. */
    val top: Float
        get() {
            ensureBounds()
            return boundsTop
        }

    /** Right edge of [bounds], without allocating. */
    val right: Float
        get() {
            ensureBounds()
            return boundsRight
        }

    /** Bottom edge of [bounds], without allocating. */
    val bottom: Float
        get() {
            ensureBounds()
            return boundsBottom
        }

    /** Whether the path is convex, with the semantics of Skia's `SkPath::isConvex`. */
    val isConvex: Boolean
        get() {
            if (convexity == ConvexityUnknown) {
                convexity = if (computeIsConvex(this)) ConvexityConvex else ConvexityConcave
            }
            return convexity == ConvexityConvex
        }

    private var convexity = ConvexityUnknown

    /** Cached browser `Path2D`, see [toPath2D]. */
    internal var path2D: JsAny? = null

    /** Cached polygonal approximation, see [flattened]. */
    internal var flattenedCache: FlattenedPath? = null

    private fun ensureBounds() {
        if (boundsState != BoundsUnknown) return
        if (pointCount == 0) {
            boundsState = BoundsFinite
            return
        }
        val pts = points
        var minX = pts[0]
        var minY = pts[1]
        var maxX = minX
        var maxY = minY
        var finite = true
        for (i in 0 until pointCount) {
            val x = pts[2 * i]
            val y = pts[2 * i + 1]
            if (!x.isFinite() || !y.isFinite()) {
                finite = false
                break
            }
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (finite) {
            boundsLeft = minX
            boundsTop = minY
            boundsRight = maxX
            boundsBottom = maxY
            boundsState = BoundsFinite
        } else {
            boundsState = BoundsNonFinite
        }
    }

    /** Returns a new mutable [WebPath] holding a copy of this geometry. */
    fun toWebPath(): WebPath = WebPath().also { it.setFrom(this) }

    override fun toString(): String = buildString {
        append("PathData(fillType=").append(fillType).append(", ")
        appendVerbs(this@PathData)
        append(")")
    }

    companion object {
        /** An empty path with the [PathFillType.NonZero] fill type. */
        val Empty: PathData =
            PathData(
                ByteArray(0),
                0,
                FloatArray(0),
                0,
                FloatArray(0),
                0,
                PathFillType.NonZero,
                InitialLastMoveToIndex,
            )
    }
}

/** Value of Skia's `fLastMoveToIndex` for a fresh path (`~0`). */
internal const val InitialLastMoveToIndex: Int = -1

private const val BoundsUnknown = 0
private const val BoundsFinite = 1
private const val BoundsNonFinite = 2

private const val ConvexityUnknown = 0
private const val ConvexityConvex = 1
private const val ConvexityConcave = 2

/** Appends an SVG-like description of the verbs of [data], used for debugging. */
private fun StringBuilder.appendVerbs(data: PathData) {
    var p = 0
    var w = 0
    val pts = data.points
    fun point() {
        append(pts[2 * p]).append(',').append(pts[2 * p + 1])
        p++
    }
    for (i in 0 until data.verbCount) {
        when (data.verbs[i]) {
            PathVerb.Move -> {
                append("M")
                point()
            }
            PathVerb.Line -> {
                append("L")
                point()
            }
            PathVerb.Quad -> {
                append("Q")
                point()
                append(' ')
                point()
            }
            PathVerb.Conic -> {
                append("K")
                point()
                append(' ')
                point()
                append(" w=").append(data.conicWeights[w++])
            }
            PathVerb.Cubic -> {
                append("C")
                point()
                append(' ')
                point()
                append(' ')
                point()
            }
            PathVerb.Close -> append("Z")
        }
        if (i != data.verbCount - 1) append(' ')
    }
}
