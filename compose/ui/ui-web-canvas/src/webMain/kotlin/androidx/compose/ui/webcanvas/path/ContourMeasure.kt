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
 * Unlike Skia, which copies the points of the contour, the segments refer to the points of the path
 * where they are: each segment to the start point of its curve in [data].
 *
 * Instances are created by [ContourMeasureIter].
 */
internal class ContourMeasure
internal constructor(
    /** The path of the contour, whose points and conic weights the segments refer to. */
    private val data: PathData,
    /** The index of the first point of the contour, where its closing line ends. */
    private val firstPoint: Int,
    private val segDistance: FloatArray,
    /** The index of the start point of the curve of each segment. */
    private val segPointIndex: IntArray,
    private val segTValue: IntArray,
    private val segType: ByteArray,
    /** The weight of the conic of each conic segment. */
    private val segWeight: FloatArray,
    private val segCount: Int,
    /** Total length of the contour. */
    val length: Float,
    /** True if the contour is closed (or was measured with `forceClosed`). */
    val isClosed: Boolean,
) {
    /** The parameter within its segment of the distance [distanceToSegment] found last. */
    private var foundT = 0f

    /**
     * Finds the segment containing [distance] (which must be within `0..length`) and the parameter
     * `t` within that segment, set as [foundT]. Returns the segment index.
     */
    private fun distanceToSegment(distance: Float): Int {
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
        foundT = startT + (segT - startT) * (distance - startD) / (segDistance[index] - startD)
        return index
    }

    /** The offset in the points of [data] of the end point of the line segment [seg]. */
    private fun lineEnd(seg: Int): Int =
        if (segType[seg] == SegClose) 2 * firstPoint else 2 * segPointIndex[seg] + 2

    /**
     * Computes the position and the normalized tangent at [distance] (pinned to `0..length`).
     * Writes (x, y) into [pos] and (tx, ty) into [tangent] when non-null.
     *
     * @return false if the distance is NaN or the computation failed.
     */
    fun getPosTan(distance: Float, pos: FloatArray?, tangent: FloatArray?): Boolean {
        if (distance.isNaN()) return false
        val seg = distanceToSegment(distance.coerceIn(0f, length))
        val t = foundT
        if (t.isNaN()) return false
        computePosTan(seg, t, pos, tangent)
        return true
    }

    private fun computePosTan(seg: Int, t: Float, pos: FloatArray?, tangent: FloatArray?) {
        val p = data.points
        val o = 2 * segPointIndex[seg]
        when (segType[seg]) {
            SegLine,
            SegClose -> {
                val e = lineEnd(seg)
                if (pos != null) {
                    pos[0] = p[o] + (p[e] - p[o]) * t
                    pos[1] = p[o + 1] + (p[e + 1] - p[o + 1]) * t
                }
                if (tangent != null) {
                    tangent[0] = p[e] - p[o]
                    tangent[1] = p[e + 1] - p[o + 1]
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
                val w = segWeight[seg]
                if (pos != null) {
                    evalConic(p[o], p[o + 1], p[o + 2], p[o + 3], p[o + 4], p[o + 5], w, t, pos, 0)
                }
                if (tangent != null) {
                    evalConicTangent(
                        p[o],
                        p[o + 1],
                        p[o + 2],
                        p[o + 3],
                        p[o + 4],
                        p[o + 5],
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

        var seg = distanceToSegment(start)
        var startT = foundT
        if (!startT.isFinite()) return false
        val stopSeg = distanceToSegment(stop)
        val stopT = foundT
        if (!stopT.isFinite()) return false

        if (startWithMoveTo) {
            computePosTan(seg, startT, StartPoint, null)
            dst.moveTo(StartPoint[0], StartPoint[1])
        }
        if (segPointIndex[seg] == segPointIndex[stopSeg]) {
            segTo(seg, startT, stopT, dst)
        } else {
            do {
                segTo(seg, startT, 1f, dst)
                seg = nextSegment(seg)
                startT = 0f
            } while (segPointIndex[seg] < segPointIndex[stopSeg])
            segTo(seg, 0f, stopT, dst)
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

    /** Port of `SkContourMeasure_segTo`: appends the curve of [seg] from [startT] to [stopT]. */
    private fun segTo(seg: Int, startT: Float, stopT: Float, dst: WebPath) {
        if (startT == stopT) {
            if (!dst.isEmpty) {
                // If the dash has a zero-length on segment, add a corresponding zero-length line.
                // The stroke code will add end caps to zero length lines as appropriate.
                dst.lineTo(dst.lastPointX(), dst.lastPointY())
            }
            return
        }
        val p = data.points
        val o = 2 * segPointIndex[seg]
        // The halves of a curve cut at startT go to the first part of Cut, the halves of its end
        // cut again at stopT to the second.
        val cut = Cut
        when (segType[seg]) {
            SegLine,
            SegClose -> {
                val e = lineEnd(seg)
                if (stopT == 1f) {
                    dst.lineTo(p[e], p[e + 1])
                } else {
                    dst.lineTo(
                        p[o] + (p[e] - p[o]) * stopT,
                        p[o + 1] + (p[e + 1] - p[o + 1]) * stopT,
                    )
                }
            }
            SegQuad -> {
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
                            cut,
                            0,
                        )
                        dst.quadraticTo(cut[2], cut[3], cut[4], cut[5])
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
                        cut,
                        0,
                    )
                    if (stopT == 1f) {
                        dst.quadraticTo(cut[6], cut[7], cut[8], cut[9])
                    } else {
                        chopQuadAt(
                            cut[4],
                            cut[5],
                            cut[6],
                            cut[7],
                            cut[8],
                            cut[9],
                            (stopT - startT) / (1f - startT),
                            cut,
                            CutHalves,
                        )
                        dst.quadraticTo(
                            cut[CutHalves + 2],
                            cut[CutHalves + 3],
                            cut[CutHalves + 4],
                            cut[CutHalves + 5],
                        )
                    }
                }
            }
            SegConic -> {
                val x0 = p[o]
                val y0 = p[o + 1]
                val x1 = p[o + 2]
                val y1 = p[o + 3]
                val x2 = p[o + 4]
                val y2 = p[o + 5]
                val w = segWeight[seg]
                val weights = CutWeights
                if (startT == 0f) {
                    if (stopT == 1f) {
                        dst.conicTo(x1, y1, x2, y2, w)
                    } else if (chopConicAt(x0, y0, x1, y1, x2, y2, w, stopT, cut, 0, weights, 0)) {
                        dst.conicTo(cut[2], cut[3], cut[4], cut[5], weights[0])
                    }
                } else if (stopT == 1f) {
                    if (chopConicAt(x0, y0, x1, y1, x2, y2, w, startT, cut, 0, weights, 0)) {
                        dst.conicTo(cut[6], cut[7], cut[8], cut[9], weights[1])
                    }
                } else {
                    val weight = chopConicBetween(x0, y0, x1, y1, x2, y2, w, startT, stopT, cut, 0)
                    dst.conicTo(cut[2], cut[3], cut[4], cut[5], weight)
                }
            }
            else -> {
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
                            cut,
                            0,
                        )
                        dst.cubicTo(cut[2], cut[3], cut[4], cut[5], cut[6], cut[7])
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
                        cut,
                        0,
                    )
                    if (stopT == 1f) {
                        dst.cubicTo(cut[8], cut[9], cut[10], cut[11], cut[12], cut[13])
                    } else {
                        chopCubicAt(
                            cut[6],
                            cut[7],
                            cut[8],
                            cut[9],
                            cut[10],
                            cut[11],
                            cut[12],
                            cut[13],
                            (stopT - startT) / (1f - startT),
                            cut,
                            CutHalves,
                        )
                        dst.cubicTo(
                            cut[CutHalves + 2],
                            cut[CutHalves + 3],
                            cut[CutHalves + 4],
                            cut[CutHalves + 5],
                            cut[CutHalves + 6],
                            cut[CutHalves + 7],
                        )
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
 */
internal class ContourMeasureIter(
    private val data: PathData,
    private val forceClosed: Boolean,
) {
    private var verbIndex = 0
    private var pointIndex = 0
    private var weightIndex = 0

    // Segment table of the contour being built.
    private var segDistance = FloatArray(16)
    private var segPointIndex = IntArray(16)
    private var segTValue = IntArray(16)
    private var segType = ByteArray(16)
    private var segWeight = FloatArray(16)
    private var segCount = 0

    /**
     * The halves of the curves being subdivided, [CutHalves] floats per level of the recursion:
     * the parameters of the curves are 30-bit fixed point numbers, whose spans each level halves,
     * so it's at most 30 levels deep.
     */
    private val subdivisions = FloatArray(30 * CutHalves)

    /** The middle point of a conic being subdivided. */
    private val conicMiddle = FloatArray(2)

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
        var firstPoint = pointIndex
        // The segments are kept by the contour: new arrays for each one.
        segCount = 0
        segDistance = FloatArray(16)
        segPointIndex = IntArray(16)
        segTValue = IntArray(16)
        segType = ByteArray(16)
        segWeight = FloatArray(16)

        val verbs = data.verbs
        val pts = data.points
        while (verbIndex < data.verbCount) {
            val verb = verbs[verbIndex]
            if (haveSeenMoveTo && verb == PathVerb.Move) break
            // The start point of the curve of the verb: the last point of the previous one.
            val start = pointIndex - 1
            val o = 2 * start
            when (verb) {
                PathVerb.Move -> {
                    firstPoint = pointIndex
                    haveSeenMoveTo = true
                }
                PathVerb.Line -> {
                    distance =
                        computeLineSeg(pts[o], pts[o + 1], pts[o + 2], pts[o + 3], distance, start)
                }
                PathVerb.Quad -> {
                    distance = computeQuadSegs(pts, o, distance, 0, MaxTValue, start, depth = 0)
                }
                PathVerb.Conic -> {
                    distance =
                        computeConicSegs(
                            pts,
                            o,
                            data.conicWeights[weightIndex],
                            distance,
                            0,
                            pts[o],
                            pts[o + 1],
                            MaxTValue,
                            pts[o + 4],
                            pts[o + 5],
                            start,
                        )
                }
                PathVerb.Cubic -> {
                    distance = computeCubicSegs(pts, o, distance, 0, MaxTValue, start, depth = 0)
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
            // The closing line, from the last point of the contour back to its first one.
            val last = 2 * (pointIndex - 1)
            val first = 2 * firstPoint
            distance =
                computeLineSeg(
                    pts[last],
                    pts[last + 1],
                    pts[first],
                    pts[first + 1],
                    distance,
                    pointIndex - 1,
                    SegClose,
                )
        }
        return ContourMeasure(
            data,
            firstPoint,
            segDistance,
            segPointIndex,
            segTValue,
            segType,
            segWeight,
            segCount,
            distance,
            haveSeenClose,
        )
    }

    /** Adds a segment; [weight] is the weight of the conic of a conic segment. */
    private fun appendSegment(
        distance: Float,
        pointIndex: Int,
        type: Byte,
        tValue: Int,
        weight: Float = 0f,
    ) {
        if (segCount == segDistance.size) {
            val size = segCount * 2
            segDistance = segDistance.copyOf(size)
            segPointIndex = segPointIndex.copyOf(size)
            segTValue = segTValue.copyOf(size)
            segType = segType.copyOf(size)
            segWeight = segWeight.copyOf(size)
        }
        segDistance[segCount] = distance
        segPointIndex[segCount] = pointIndex
        segType[segCount] = type
        segTValue[segCount] = tValue
        segWeight[segCount] = weight
        segCount++
    }

    private fun computeLineSeg(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        distance: Float,
        pointIndex: Int,
        type: Byte = SegLine,
    ): Float {
        val d = pointDistance(x0, y0, x1, y1)
        val prevD = distance
        val newD = distance + d
        if (newD > prevD) {
            appendSegment(newD, pointIndex, type, MaxTValue)
        }
        return newD
    }

    /** Whether the quad of the 6 floats of [q] from [at] is too curved to be measured as a chord. */
    private fun quadTooCurvy(q: FloatArray, at: Int): Boolean {
        // diff = (a/4 + b/2 + c/4) - (a/2 + c/2)
        val dx = q[at + 2] * 0.5f - (q[at] + q[at + 4]) * 0.5f * 0.5f
        val dy = q[at + 3] * 0.5f - (q[at + 1] + q[at + 5]) * 0.5f * 0.5f
        val dist = max(abs(dx), abs(dy))
        return dist > CheapDistLimit
    }

    /**
     * Measures the quad of the 6 floats of [q] from [at], cutting it in halves into [subdivisions]
     * at the level [depth] of the recursion where it's too curved.
     */
    private fun computeQuadSegs(
        q: FloatArray,
        at: Int,
        distance: Float,
        minT: Int,
        maxT: Int,
        pointIndex: Int,
        depth: Int,
    ): Float {
        var d = distance
        if (tSpanBigEnough(maxT - minT) && quadTooCurvy(q, at)) {
            val halves = depth * CutHalves
            val halfT = (minT + maxT) ushr 1
            chopQuadAt(
                q[at],
                q[at + 1],
                q[at + 2],
                q[at + 3],
                q[at + 4],
                q[at + 5],
                0.5f,
                subdivisions,
                halves,
            )
            // The halves share their middle point; the next level cuts past them.
            d = computeQuadSegs(subdivisions, halves, d, minT, halfT, pointIndex, depth + 1)
            d = computeQuadSegs(subdivisions, halves + 4, d, halfT, maxT, pointIndex, depth + 1)
        } else {
            val len = pointDistance(q[at], q[at + 1], q[at + 4], q[at + 5])
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
        return dist > CheapDistLimit
    }

    /** Measures the conic of the 6 floats of [conic] from [at] between [minT] and [maxT]. */
    private fun computeConicSegs(
        conic: FloatArray,
        at: Int,
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
        evalConic(
            conic[at],
            conic[at + 1],
            conic[at + 2],
            conic[at + 3],
            conic[at + 4],
            conic[at + 5],
            weight,
            tValueToScalar(halfT),
            conicMiddle,
            0,
        )
        val halfX = conicMiddle[0]
        val halfY = conicMiddle[1]
        if (!halfX.isFinite() || !halfY.isFinite()) return d
        if (tSpanBigEnough(maxT - minT) && conicTooCurvy(minX, minY, halfX, halfY, maxX, maxY)) {
            d =
                computeConicSegs(
                    conic,
                    at,
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
                    at,
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
                appendSegment(d, pointIndex, SegConic, maxT, weight)
            }
        }
        return d
    }

    private fun cheapDistExceedsLimit(px: Float, py: Float, x: Float, y: Float): Boolean {
        val dist = max(abs(x - px), abs(y - py))
        // just made up the 1/2
        return dist > CheapDistLimit
    }

    /** Whether the cubic of the 8 floats of [c] from [at] is too curved to be measured as a chord. */
    private fun cubicTooCurvy(c: FloatArray, at: Int): Boolean =
        cheapDistExceedsLimit(
            c[at + 2],
            c[at + 3],
            c[at] + (c[at + 6] - c[at]) * (1f / 3),
            c[at + 1] + (c[at + 7] - c[at + 1]) * (1f / 3),
        ) ||
            cheapDistExceedsLimit(
                c[at + 4],
                c[at + 5],
                c[at] + (c[at + 6] - c[at]) * (2f / 3),
                c[at + 1] + (c[at + 7] - c[at + 1]) * (2f / 3),
            )

    /**
     * Measures the cubic of the 8 floats of [c] from [at], cutting it in halves into [subdivisions]
     * at the level [depth] of the recursion where it's too curved.
     */
    private fun computeCubicSegs(
        c: FloatArray,
        at: Int,
        distance: Float,
        minT: Int,
        maxT: Int,
        pointIndex: Int,
        depth: Int,
    ): Float {
        var d = distance
        if (tSpanBigEnough(maxT - minT) && cubicTooCurvy(c, at)) {
            val halves = depth * CutHalves
            val halfT = (minT + maxT) ushr 1
            chopCubicAt(
                c[at],
                c[at + 1],
                c[at + 2],
                c[at + 3],
                c[at + 4],
                c[at + 5],
                c[at + 6],
                c[at + 7],
                0.5f,
                subdivisions,
                halves,
            )
            // The halves share their middle point; the next level cuts past them.
            d = computeCubicSegs(subdivisions, halves, d, minT, halfT, pointIndex, depth + 1)
            d = computeCubicSegs(subdivisions, halves + 6, d, halfT, maxT, pointIndex, depth + 1)
        } else {
            val len = pointDistance(c[at], c[at + 1], c[at + 6], c[at + 7])
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

/** The line closing a contour, from its last point to its first one. */
private const val SegClose: Byte = 4

/** The floats of the halves of a curve cut in two: the 7 points of 2 cubics (a quad takes 5). */
private const val CutHalves = 14

/** The curves [ContourMeasure.getSegment] cuts: twice their halves. */
private val Cut = FloatArray(2 * CutHalves)

/** The weights of the conics [ContourMeasure.getSegment] cuts. */
private val CutWeights = FloatArray(2)

/** The point a segment [ContourMeasure.getSegment] appends starts at. */
private val StartPoint = FloatArray(2)

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
private fun normalize(v: FloatArray) {
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
