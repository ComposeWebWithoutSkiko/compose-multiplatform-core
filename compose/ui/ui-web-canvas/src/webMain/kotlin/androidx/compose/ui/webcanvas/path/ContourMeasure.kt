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

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Length, position and tangent queries along one contour of a path. Port of Skia's
 * `SkContourMeasure`: curves are approximated by chords using adaptive subdivision with a 0.5 unit
 * tolerance, and positions are then evaluated on the exact curves.
 *
 * Instances are created by [ContourMeasureIter].
 */
internal class ContourMeasure
internal constructor(
    private val segDistance: FloatArray,
    private val segPointIndex: IntArray,
    private val segTValue: IntArray,
    private val segType: ByteArray,
    private val segCount: Int,
    /**
     * Points of the contour. Conic segments store their weight as the x coordinate of an extra
     * point before their control point, like Skia.
     */
    private val pts: FloatArray,
    /** Total length of the contour. */
    val length: Float,
    /** True if the contour is closed (or was measured with `forceClosed`). */
    val isClosed: Boolean,
) {
    private val scratch = FloatArray(4)

    /**
     * Finds the segment containing [distance] (which must be within `0..length`) and the parameter
     * `t` within that segment, written into [tOut]. Returns the segment index.
     */
    private fun distanceToSegment(distance: Float, tOut: FloatArray): Int {
        // Lower bound: first segment whose distance is >= the target distance.
        var lo = 0
        var hi = segCount - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (segDistance[mid] < distance) lo = mid + 1 else hi = mid
        }
        val index = hi
        var startT = 0f
        var startD = 0f
        if (index > 0) {
            startD = segDistance[index - 1]
            if (segPointIndex[index - 1] == segPointIndex[index]) {
                startT = tValueToScalar(segTValue[index - 1])
            }
        }
        val segT = tValueToScalar(segTValue[index])
        tOut[0] = startT + (segT - startT) * (distance - startD) / (segDistance[index] - startD)
        return index
    }

    /**
     * Computes the position and the normalized tangent at [distance] (pinned to `0..length`).
     * Writes (x, y) into [pos] and (tx, ty) into [tangent] when non-null.
     *
     * @return false if the distance is NaN or the computation failed.
     */
    fun getPosTan(distance: Float, pos: FloatArray?, tangent: FloatArray?): Boolean {
        if (distance.isNaN()) return false
        val d = distance.coerceIn(0f, length)
        val tOut = FloatArray(1)
        val seg = distanceToSegment(d, tOut)
        val t = tOut[0]
        if (t.isNaN()) return false
        computePosTan(segPointIndex[seg], segType[seg], t, pos, tangent)
        return true
    }

    private fun computePosTan(
        pointIndex: Int,
        type: Byte,
        t: Float,
        pos: FloatArray?,
        tangent: FloatArray?,
    ) {
        val p = pts
        val o = 2 * pointIndex
        when (type) {
            SegLine -> {
                if (pos != null) {
                    pos[0] = p[o] + (p[o + 2] - p[o]) * t
                    pos[1] = p[o + 1] + (p[o + 3] - p[o + 1]) * t
                }
                if (tangent != null) {
                    tangent[0] = p[o + 2] - p[o]
                    tangent[1] = p[o + 3] - p[o + 1]
                }
            }
            SegQuad -> {
                if (pos != null)
                    evalQuad(p[o], p[o + 1], p[o + 2], p[o + 3], p[o + 4], p[o + 5], t, pos, 0)
                if (tangent != null) {
                    evalQuadTangent(
                        p[o],
                        p[o + 1],
                        p[o + 2],
                        p[o + 3],
                        p[o + 4],
                        p[o + 5],
                        t,
                        tangent,
                        0,
                    )
                }
            }
            SegConic -> {
                val w = p[o + 2]
                if (pos != null) {
                    evalConic(p[o], p[o + 1], p[o + 4], p[o + 5], p[o + 6], p[o + 7], w, t, pos, 0)
                }
                if (tangent != null) {
                    evalConicTangent(
                        p[o],
                        p[o + 1],
                        p[o + 4],
                        p[o + 5],
                        p[o + 6],
                        p[o + 7],
                        w,
                        t,
                        tangent,
                        0,
                    )
                }
            }
            else -> {
                if (pos != null) {
                    evalCubic(
                        p[o],
                        p[o + 1],
                        p[o + 2],
                        p[o + 3],
                        p[o + 4],
                        p[o + 5],
                        p[o + 6],
                        p[o + 7],
                        t,
                        pos,
                        0,
                    )
                }
                if (tangent != null) {
                    evalCubicTangent(
                        p[o],
                        p[o + 1],
                        p[o + 2],
                        p[o + 3],
                        p[o + 4],
                        p[o + 5],
                        p[o + 6],
                        p[o + 7],
                        t,
                        tangent,
                        0,
                    )
                }
            }
        }
        if (tangent != null) normalize(tangent)
    }

    /**
     * Appends the part of the contour between [startD] and [stopD] to [dst], with the semantics of
     * Skia's `SkContourMeasure::getSegment`: distances are pinned to `0..length`, `startD == stopD`
     * is allowed (and produces a zero-length segment), and `startD > stopD` returns false.
     */
    fun getSegment(startD: Float, stopD: Float, dst: WebPath, startWithMoveTo: Boolean): Boolean {
        var start = startD
        var stop = stopD
        if (start < 0f) start = 0f
        if (stop > length) stop = length
        if (!(start <= stop)) return false // Catches NaN values as well.
        if (segCount == 0) return false

        val tOut = FloatArray(1)
        var seg = distanceToSegment(start, tOut)
        var startT = tOut[0]
        if (!startT.isFinite()) return false
        val stopSeg = distanceToSegment(stop, tOut)
        val stopT = tOut[0]
        if (!stopT.isFinite()) return false

        if (startWithMoveTo) {
            computePosTan(segPointIndex[seg], segType[seg], startT, scratch, null)
            dst.moveTo(scratch[0], scratch[1])
        }
        if (segPointIndex[seg] == segPointIndex[stopSeg]) {
            segTo(segPointIndex[seg], segType[seg], startT, stopT, dst)
        } else {
            do {
                segTo(segPointIndex[seg], segType[seg], startT, 1f, dst)
                seg = nextSegment(seg)
                startT = 0f
            } while (segPointIndex[seg] < segPointIndex[stopSeg])
            segTo(segPointIndex[seg], segType[seg], 0f, stopT, dst)
        }
        return true
    }

    /** Returns the index of the first segment that references different points than [seg]. */
    private fun nextSegment(seg: Int): Int {
        val pointIndex = segPointIndex[seg]
        var next = seg
        do {
            next++
        } while (segPointIndex[next] == pointIndex)
        return next
    }

    /** Port of `SkContourMeasure_segTo`. */
    private fun segTo(pointIndex: Int, type: Byte, startT: Float, stopT: Float, dst: WebPath) {
        if (startT == stopT) {
            if (!dst.isEmpty) {
                // If the dash has a zero-length on segment, add a corresponding zero-length line.
                // The stroke code will add end caps to zero length lines as appropriate.
                dst.lineTo(dst.lastPointX(), dst.lastPointY())
            }
            return
        }
        val p = pts
        val o = 2 * pointIndex
        when (type) {
            SegLine -> {
                if (stopT == 1f) {
                    dst.lineTo(p[o + 2], p[o + 3])
                } else {
                    dst.lineTo(
                        p[o] + (p[o + 2] - p[o]) * stopT,
                        p[o + 1] + (p[o + 3] - p[o + 1]) * stopT,
                    )
                }
            }
            SegQuad -> {
                val tmp0 = FloatArray(10)
                if (startT == 0f) {
                    if (stopT == 1f) {
                        dst.quadraticTo(p[o + 2], p[o + 3], p[o + 4], p[o + 5])
                    } else {
                        chopQuadAt(
                            p[o],
                            p[o + 1],
                            p[o + 2],
                            p[o + 3],
                            p[o + 4],
                            p[o + 5],
                            stopT,
                            tmp0,
                            0,
                        )
                        dst.quadraticTo(tmp0[2], tmp0[3], tmp0[4], tmp0[5])
                    }
                } else {
                    chopQuadAt(
                        p[o],
                        p[o + 1],
                        p[o + 2],
                        p[o + 3],
                        p[o + 4],
                        p[o + 5],
                        startT,
                        tmp0,
                        0,
                    )
                    if (stopT == 1f) {
                        dst.quadraticTo(tmp0[6], tmp0[7], tmp0[8], tmp0[9])
                    } else {
                        val tmp1 = FloatArray(10)
                        chopQuadAt(
                            tmp0[4],
                            tmp0[5],
                            tmp0[6],
                            tmp0[7],
                            tmp0[8],
                            tmp0[9],
                            (stopT - startT) / (1f - startT),
                            tmp1,
                            0,
                        )
                        dst.quadraticTo(tmp1[2], tmp1[3], tmp1[4], tmp1[5])
                    }
                }
            }
            SegConic -> {
                val x0 = p[o]
                val y0 = p[o + 1]
                val w = p[o + 2]
                val x1 = p[o + 4]
                val y1 = p[o + 5]
                val x2 = p[o + 6]
                val y2 = p[o + 7]
                if (startT == 0f) {
                    if (stopT == 1f) {
                        dst.conicTo(x1, y1, x2, y2, w)
                    } else {
                        val halves = FloatArray(10)
                        val weights = FloatArray(2)
                        if (chopConicAt(x0, y0, x1, y1, x2, y2, w, stopT, halves, 0, weights, 0)) {
                            dst.conicTo(halves[2], halves[3], halves[4], halves[5], weights[0])
                        }
                    }
                } else {
                    if (stopT == 1f) {
                        val halves = FloatArray(10)
                        val weights = FloatArray(2)
                        if (chopConicAt(x0, y0, x1, y1, x2, y2, w, startT, halves, 0, weights, 0)) {
                            dst.conicTo(halves[6], halves[7], halves[8], halves[9], weights[1])
                        }
                    } else {
                        val part = FloatArray(6)
                        val weight =
                            chopConicBetween(x0, y0, x1, y1, x2, y2, w, startT, stopT, part, 0)
                        dst.conicTo(part[2], part[3], part[4], part[5], weight)
                    }
                }
            }
            else -> {
                val tmp0 = FloatArray(14)
                if (startT == 0f) {
                    if (stopT == 1f) {
                        dst.cubicTo(p[o + 2], p[o + 3], p[o + 4], p[o + 5], p[o + 6], p[o + 7])
                    } else {
                        chopCubicAt(
                            p[o],
                            p[o + 1],
                            p[o + 2],
                            p[o + 3],
                            p[o + 4],
                            p[o + 5],
                            p[o + 6],
                            p[o + 7],
                            stopT,
                            tmp0,
                            0,
                        )
                        dst.cubicTo(tmp0[2], tmp0[3], tmp0[4], tmp0[5], tmp0[6], tmp0[7])
                    }
                } else {
                    chopCubicAt(
                        p[o],
                        p[o + 1],
                        p[o + 2],
                        p[o + 3],
                        p[o + 4],
                        p[o + 5],
                        p[o + 6],
                        p[o + 7],
                        startT,
                        tmp0,
                        0,
                    )
                    if (stopT == 1f) {
                        dst.cubicTo(tmp0[8], tmp0[9], tmp0[10], tmp0[11], tmp0[12], tmp0[13])
                    } else {
                        val tmp1 = FloatArray(14)
                        chopCubicAt(
                            tmp0[6],
                            tmp0[7],
                            tmp0[8],
                            tmp0[9],
                            tmp0[10],
                            tmp0[11],
                            tmp0[12],
                            tmp0[13],
                            (stopT - startT) / (1f - startT),
                            tmp1,
                            0,
                        )
                        dst.cubicTo(tmp1[2], tmp1[3], tmp1[4], tmp1[5], tmp1[6], tmp1[7])
                    }
                }
            }
        }
    }
}

/**
 * Iterates over the contours of a path, measuring them (Skia's `SkContourMeasureIter`). Contours
 * with a zero length are skipped.
 *
 * @param forceClosed measure every contour as if it was closed.
 * @param resScale scale applied to the measuring tolerance (values > 1 measure more precisely).
 */
internal class ContourMeasureIter(
    private val data: PathData,
    private val forceClosed: Boolean,
    resScale: Float = 1f,
) {
    private val tolerance = CheapDistLimit * (1f / resScale)
    private var verbIndex = 0
    private var pointIndex = 0
    private var weightIndex = 0

    // Segment table of the contour being built.
    private var segDistance = FloatArray(16)
    private var segPointIndex = IntArray(16)
    private var segTValue = IntArray(16)
    private var segType = ByteArray(16)
    private var segCount = 0

    // Points of the contour being built.
    private var pts = FloatArray(32)
    private var ptCount = 0

    /** Returns the next contour with a non-zero length, or null when done. */
    fun next(): ContourMeasure? {
        while (verbIndex < data.verbCount) {
            val measure = buildSegments()
            if (measure != null) return measure
        }
        return null
    }

    private fun buildSegments(): ContourMeasure? {
        var distance = 0f
        var haveSeenClose = forceClosed
        var haveSeenMoveTo = false
        segCount = 0
        ptCount = 0
        segDistance = FloatArray(16)
        segPointIndex = IntArray(16)
        segTValue = IntArray(16)
        segType = ByteArray(16)
        pts = FloatArray(32)

        val verbs = data.verbs
        val src = data.points
        while (verbIndex < data.verbCount) {
            val verb = verbs[verbIndex]
            if (haveSeenMoveTo && verb == PathVerb.Move) break
            val p = pointIndex
            when (verb) {
                PathVerb.Move -> {
                    appendPoint(src[2 * p], src[2 * p + 1])
                    haveSeenMoveTo = true
                }
                PathVerb.Line -> {
                    val prevD = distance
                    distance =
                        computeLineSeg(
                            src[2 * p - 2],
                            src[2 * p - 1],
                            src[2 * p],
                            src[2 * p + 1],
                            distance,
                            ptCount - 1,
                        )
                    if (distance > prevD) {
                        appendPoint(src[2 * p], src[2 * p + 1])
                    }
                }
                PathVerb.Quad -> {
                    val prevD = distance
                    val quad = src.copyOfRange(2 * p - 2, 2 * p + 4)
                    distance = computeQuadSegs(quad, distance, 0, MaxTValue, ptCount - 1)
                    if (distance > prevD) {
                        appendPoint(src[2 * p], src[2 * p + 1])
                        appendPoint(src[2 * p + 2], src[2 * p + 3])
                    }
                }
                PathVerb.Conic -> {
                    val w = data.conicWeights[weightIndex]
                    val prevD = distance
                    val conic = src.copyOfRange(2 * p - 2, 2 * p + 4)
                    distance =
                        computeConicSegs(
                            conic,
                            w,
                            distance,
                            0,
                            conic[0],
                            conic[1],
                            MaxTValue,
                            conic[4],
                            conic[5],
                            ptCount - 1,
                        )
                    if (distance > prevD) {
                        // Store the conic weight in the next point, followed by the last 2 points.
                        appendPoint(w, 0f)
                        appendPoint(src[2 * p], src[2 * p + 1])
                        appendPoint(src[2 * p + 2], src[2 * p + 3])
                    }
                }
                PathVerb.Cubic -> {
                    val prevD = distance
                    val cubic = src.copyOfRange(2 * p - 2, 2 * p + 6)
                    distance = computeCubicSegs(cubic, distance, 0, MaxTValue, ptCount - 1)
                    if (distance > prevD) {
                        appendPoint(src[2 * p], src[2 * p + 1])
                        appendPoint(src[2 * p + 2], src[2 * p + 3])
                        appendPoint(src[2 * p + 4], src[2 * p + 5])
                    }
                }
                PathVerb.Close -> haveSeenClose = true
            }
            if (verb == PathVerb.Conic) weightIndex++
            pointIndex += PathVerb.pointCount(verb)
            verbIndex++
        }

        if (!distance.isFinite()) return null
        if (segCount == 0) return null

        if (haveSeenClose) {
            val prevD = distance
            val lastIndex = ptCount - 1
            val firstX = pts[0]
            val firstY = pts[1]
            distance =
                computeLineSeg(
                    pts[2 * lastIndex],
                    pts[2 * lastIndex + 1],
                    firstX,
                    firstY,
                    distance,
                    lastIndex,
                )
            if (distance > prevD) {
                appendPoint(firstX, firstY)
            }
        }
        return ContourMeasure(
            segDistance,
            segPointIndex,
            segTValue,
            segType,
            segCount,
            pts,
            distance,
            haveSeenClose,
        )
    }

    private fun appendPoint(x: Float, y: Float) {
        if (2 * ptCount + 2 > pts.size) pts = pts.copyOf(pts.size * 2)
        pts[2 * ptCount] = x
        pts[2 * ptCount + 1] = y
        ptCount++
    }

    private fun appendSegment(distance: Float, pointIndex: Int, type: Byte, tValue: Int) {
        if (segCount == segDistance.size) {
            val size = segCount * 2
            segDistance = segDistance.copyOf(size)
            segPointIndex = segPointIndex.copyOf(size)
            segTValue = segTValue.copyOf(size)
            segType = segType.copyOf(size)
        }
        segDistance[segCount] = distance
        segPointIndex[segCount] = pointIndex
        segType[segCount] = type
        segTValue[segCount] = tValue
        segCount++
    }

    private fun computeLineSeg(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        distance: Float,
        pointIndex: Int,
    ): Float {
        val d = pointDistance(x0, y0, x1, y1)
        val prevD = distance
        val newD = distance + d
        if (newD > prevD) {
            appendSegment(newD, pointIndex, SegLine, MaxTValue)
        }
        return newD
    }

    private fun quadTooCurvy(q: FloatArray): Boolean {
        // diff = (a/4 + b/2 + c/4) - (a/2 + c/2)
        val dx = q[2] * 0.5f - (q[0] + q[4]) * 0.5f * 0.5f
        val dy = q[3] * 0.5f - (q[1] + q[5]) * 0.5f * 0.5f
        val dist = max(abs(dx), abs(dy))
        return dist > tolerance
    }

    private fun computeQuadSegs(
        q: FloatArray,
        distance: Float,
        minT: Int,
        maxT: Int,
        pointIndex: Int,
    ): Float {
        var d = distance
        if (tSpanBigEnough(maxT - minT) && quadTooCurvy(q)) {
            val tmp = FloatArray(10)
            val halfT = (minT + maxT) ushr 1
            chopQuadAt(q[0], q[1], q[2], q[3], q[4], q[5], 0.5f, tmp, 0)
            d = computeQuadSegs(tmp.copyOfRange(0, 6), d, minT, halfT, pointIndex)
            d = computeQuadSegs(tmp.copyOfRange(4, 10), d, halfT, maxT, pointIndex)
        } else {
            val len = pointDistance(q[0], q[1], q[4], q[5])
            val prevD = d
            d += len
            if (d > prevD) {
                appendSegment(d, pointIndex, SegQuad, maxT)
            }
        }
        return d
    }

    private fun conicTooCurvy(
        firstX: Float,
        firstY: Float,
        midX: Float,
        midY: Float,
        lastX: Float,
        lastY: Float,
    ): Boolean {
        val midEndsX = (firstX + lastX) * 0.5f
        val midEndsY = (firstY + lastY) * 0.5f
        val dist = max(abs(midX - midEndsX), abs(midY - midEndsY))
        return dist > tolerance
    }

    private fun computeConicSegs(
        conic: FloatArray,
        weight: Float,
        distance: Float,
        minT: Int,
        minX: Float,
        minY: Float,
        maxT: Int,
        maxX: Float,
        maxY: Float,
        pointIndex: Int,
    ): Float {
        var d = distance
        val halfT = (minT + maxT) ushr 1
        val half = FloatArray(2)
        evalConic(
            conic[0],
            conic[1],
            conic[2],
            conic[3],
            conic[4],
            conic[5],
            weight,
            tValueToScalar(halfT),
            half,
            0,
        )
        val halfX = half[0]
        val halfY = half[1]
        if (!halfX.isFinite() || !halfY.isFinite()) return d
        if (tSpanBigEnough(maxT - minT) && conicTooCurvy(minX, minY, halfX, halfY, maxX, maxY)) {
            d =
                computeConicSegs(
                    conic,
                    weight,
                    d,
                    minT,
                    minX,
                    minY,
                    halfT,
                    halfX,
                    halfY,
                    pointIndex,
                )
            d =
                computeConicSegs(
                    conic,
                    weight,
                    d,
                    halfT,
                    halfX,
                    halfY,
                    maxT,
                    maxX,
                    maxY,
                    pointIndex,
                )
        } else {
            val len = pointDistance(minX, minY, maxX, maxY)
            val prevD = d
            d += len
            if (d > prevD) {
                appendSegment(d, pointIndex, SegConic, maxT)
            }
        }
        return d
    }

    private fun cheapDistExceedsLimit(px: Float, py: Float, x: Float, y: Float): Boolean {
        val dist = max(abs(x - px), abs(y - py))
        // just made up the 1/2
        return dist > tolerance
    }

    private fun cubicTooCurvy(c: FloatArray): Boolean =
        cheapDistExceedsLimit(
            c[2],
            c[3],
            c[0] + (c[6] - c[0]) * (1f / 3),
            c[1] + (c[7] - c[1]) * (1f / 3),
        ) ||
            cheapDistExceedsLimit(
                c[4],
                c[5],
                c[0] + (c[6] - c[0]) * (2f / 3),
                c[1] + (c[7] - c[1]) * (2f / 3),
            )

    private fun computeCubicSegs(
        c: FloatArray,
        distance: Float,
        minT: Int,
        maxT: Int,
        pointIndex: Int,
    ): Float {
        var d = distance
        if (tSpanBigEnough(maxT - minT) && cubicTooCurvy(c)) {
            val tmp = FloatArray(14)
            val halfT = (minT + maxT) ushr 1
            chopCubicAt(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], 0.5f, tmp, 0)
            d = computeCubicSegs(tmp.copyOfRange(0, 8), d, minT, halfT, pointIndex)
            d = computeCubicSegs(tmp.copyOfRange(6, 14), d, halfT, maxT, pointIndex)
        } else {
            val len = pointDistance(c[0], c[1], c[6], c[7])
            val prevD = d
            d += len
            if (d > prevD) {
                appendSegment(d, pointIndex, SegCubic, maxT)
            }
        }
        return d
    }
}

private const val SegLine: Byte = 0
private const val SegQuad: Byte = 1
private const val SegCubic: Byte = 2
private const val SegConic: Byte = 3

/** Parameter values are stored as 30-bit fixed point numbers, like Skia. */
private const val MaxTValue = 0x3FFFFFFF
private const val MaxTReciprocal = 1.0f / MaxTValue

/** Skia's `CHEAP_DIST_LIMIT`. */
private const val CheapDistLimit = 0.5f

private fun tSpanBigEnough(tSpan: Int): Boolean = (tSpan shr 10) != 0

private fun tValueToScalar(t: Int): Float = t.toFloat() * MaxTReciprocal

private fun pointDistance(x0: Float, y0: Float, x1: Float, y1: Float): Float {
    val dx = x1 - x0
    val dy = y1 - y0
    return sqrt(dx * dx + dy * dy)
}

/** Normalizes the vector stored in [v] (Skia's `SkPoint::normalize`); zero if impossible. */
internal fun normalize(v: FloatArray) {
    val x = v[0]
    val y = v[1]
    val mag = sqrt(x.toDouble() * x + y.toDouble() * y)
    val nx = (x / mag).toFloat()
    val ny = (y / mag).toFloat()
    if (!nx.isFinite() || !ny.isFinite() || (nx == 0f && ny == 0f)) {
        v[0] = 0f
        v[1] = 0f
    } else {
        v[0] = nx
        v[1] = ny
    }
}
