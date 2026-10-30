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

import androidx.compose.ui.graphics.CloseSegment
import androidx.compose.ui.graphics.DoneSegment
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathSegment

/**
 * [PathIterator] over a [WebPath].
 *
 * The iterator walks the verbs as they are stored, like Skia's `SkPath::Iter`, which the iterator of
 * the Skia-based targets uses: a [PathSegment.Type.Close] whose contour doesn't end at its start is
 * preceded by a [PathSegment.Type.Line] back to the start, so that code walking the segments (e.g.
 * `PathHitTester`) sees every edge, and trailing moveTos are reported too. Conics are either
 * reported as [PathSegment.Type.Conic] or converted to quadratics with Skia's `chopIntoQuadsPOW2`:
 * `2^n` of them, `n` being derived from [tolerance] like Skia's `SkConic::computeQuadPOW2`, or 2
 * for a conic of an extreme weight whose first halves are a pair of lines.
 *
 * The iterator works on a snapshot taken at creation time, so mutating the path afterwards does not
 * affect an ongoing iteration.
 */
internal class WebPathIterator(
    override val path: Path,
    override val conicEvaluation: PathIterator.ConicEvaluation,
    override val tolerance: Float,
) : PathIterator {
    private val data: PathData = path.asWebPath().snapshot()
    private var verbIndex = 0
    private var pointIndex = 0
    private var weightIndex = 0

    /** The index of the first point of the current contour. */
    private var contourStart = 0

    /** Whether the line closing the current contour was reported, and its close is next. */
    private var isClosing = false

    // Quadratics converted from the current conic, see [nextConvertedQuad].
    private var quadBuffer: FloatArray? = null
    private var quadCount = 0
    private var quadIndex = 0

    // Where the points of the segment [advance] moved to are, for [writePoints]: [segmentFloats]
    // floats of [segmentSource] from [segmentOffset] on or, for the line closing the contour, the
    // point at [segmentOffset] and the first point of the contour. [segmentWeight] is its weight.
    private var segmentSource: FloatArray = data.points
    private var segmentOffset = 0
    private var segmentFloats = 0
    private var segmentIsClosingLine = false
    private var segmentWeight = 0f

    override fun calculateSize(includeConvertedConics: Boolean): Int {
        val convertConics =
            includeConvertedConics && conicEvaluation == PathIterator.ConicEvaluation.AsQuadratics
        var size = data.verbCount
        // The conics are converted as [next] converts them, to count the quadratics it returns,
        // into a buffer of their own: [quadBuffer] may hold quadratics still to be returned.
        var quads: FloatArray? = null
        var p = 0
        var w = 0
        var start = 0
        for (i in 0 until data.verbCount) {
            val verb = data.verbs[i]
            when (verb) {
                PathVerb.Move -> start = p
                PathVerb.Conic -> {
                    val weight = data.conicWeights[w++]
                    if (convertConics) {
                        val buffer = quads ?: conicQuadBuffer().also { quads = it }
                        size += chopConicIntoQuads(data.points, p, weight, tolerance, buffer) - 1
                    }
                }
                PathVerb.Close -> if (needsClosingLine(p, start)) size++
            }
            p += PathVerb.pointCount(verb)
        }
        return size
    }

    /**
     * Whether the contour starting at point [start] and ending before point [end] needs a line
     * back to its start before its close.
     */
    private fun needsClosingLine(end: Int, start: Int): Boolean {
        val last = end - 1
        val pts = data.points
        return last > start &&
            (pts[2 * last] != pts[2 * start] || pts[2 * last + 1] != pts[2 * start + 1])
    }

    override fun hasNext(): Boolean = quadIndex < quadCount || verbIndex < data.verbCount

    override fun next(outPoints: FloatArray, offset: Int): PathSegment.Type {
        check(outPoints.size - offset >= 8) { "The points array must contain at least 8 floats" }
        val type = advance()
        writePoints(outPoints, offset)
        if (type == PathSegment.Type.Conic) {
            outPoints[offset + 6] = segmentWeight
            outPoints[offset + 7] = segmentWeight
        }
        return type
    }

    override fun next(): PathSegment {
        if (!hasNext()) return DoneSegment
        return when (val type = advance()) {
            PathSegment.Type.Close -> CloseSegment
            PathSegment.Type.Done -> DoneSegment
            else -> {
                // The points go right into the array of the segment.
                val points = FloatArray(segmentFloats).also { writePoints(it, 0) }
                PathSegment(type, points, if (type == PathSegment.Type.Conic) segmentWeight else 0f)
            }
        }
    }

    /** Moves to the next segment, noting where its points are (see [writePoints]). */
    private fun advance(): PathSegment.Type {
        segmentIsClosingLine = false
        if (quadIndex < quadCount) return nextConvertedQuad()
        if (verbIndex >= data.verbCount) {
            segmentFloats = 0
            return PathSegment.Type.Done
        }

        val p = pointIndex
        val verb = data.verbs[verbIndex++]
        pointIndex += PathVerb.pointCount(verb)
        // The points of a segment are its start, the last point of the previous one, and its own.
        segmentSource = data.points
        segmentOffset = 2 * p - 2
        segmentFloats = 2 * PathVerb.pointCount(verb) + 2
        return when (verb) {
            PathVerb.Move -> {
                contourStart = p
                segmentOffset = 2 * p
                segmentFloats = 2
                PathSegment.Type.Move
            }
            PathVerb.Line -> PathSegment.Type.Line
            PathVerb.Quad -> PathSegment.Type.Quadratic
            PathVerb.Conic -> {
                segmentWeight = data.conicWeights[weightIndex++]
                if (conicEvaluation == PathIterator.ConicEvaluation.AsConic) {
                    PathSegment.Type.Conic
                } else {
                    val buffer = quadBuffer ?: conicQuadBuffer().also { quadBuffer = it }
                    quadCount = chopConicIntoQuads(data.points, p, segmentWeight, tolerance, buffer)
                    quadIndex = 0
                    nextConvertedQuad()
                }
            }
            PathVerb.Cubic -> PathSegment.Type.Cubic
            PathVerb.Close -> {
                if (!isClosing && needsClosingLine(p, contourStart)) {
                    // The line back to the start first; the close is reported next.
                    isClosing = true
                    verbIndex--
                    segmentFloats = 4
                    segmentIsClosingLine = true
                    PathSegment.Type.Line
                } else {
                    isClosing = false
                    segmentFloats = 0
                    PathSegment.Type.Close
                }
            }
            else -> PathSegment.Type.Done
        }
    }

    /** Writes the points of the last segment (see [advance]) into [out] from [offset]. */
    private fun writePoints(out: FloatArray, offset: Int) {
        if (segmentIsClosingLine) {
            val pts = data.points
            out[offset] = pts[segmentOffset]
            out[offset + 1] = pts[segmentOffset + 1]
            out[offset + 2] = pts[2 * contourStart]
            out[offset + 3] = pts[2 * contourStart + 1]
        } else {
            segmentSource.copyInto(out, offset, segmentOffset, segmentOffset + segmentFloats)
        }
    }

    private fun nextConvertedQuad(): PathSegment.Type {
        // Quad i starts at point 2 * i of the buffer (start, control, end).
        segmentSource = quadBuffer!!
        segmentOffset = 4 * quadIndex
        segmentFloats = 6
        quadIndex++
        if (quadIndex == quadCount) {
            quadIndex = 0
            quadCount = 0
        }
        return PathSegment.Type.Quadratic
    }
}
