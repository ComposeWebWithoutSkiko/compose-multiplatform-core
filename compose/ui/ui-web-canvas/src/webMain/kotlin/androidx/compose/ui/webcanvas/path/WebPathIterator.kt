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
 * The iterator walks the verbs exactly as they are stored (like the Android implementation and the
 * documented contract of [PathIterator]): every [PathSegment.Type.Close] is reported as is, without
 * a synthesized closing line, and trailing moveTos are reported too. Conics are either reported as
 * [PathSegment.Type.Conic] or converted to `2^n` quadratics with Skia's `chopIntoQuadsPOW2`, `n`
 * being derived from [tolerance] like Skia's `SkConic::computeQuadPOW2`.
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

    // Quadratics converted from the current conic, see [nextConvertedQuad].
    private var quadBuffer: FloatArray? = null
    private var quadCount = 0
    private var quadIndex = 0

    override fun calculateSize(includeConvertedConics: Boolean): Int {
        var size = data.verbCount
        if (
            includeConvertedConics && conicEvaluation == PathIterator.ConicEvaluation.AsQuadratics
        ) {
            val pts = data.points
            var p = 0
            var w = 0
            for (i in 0 until data.verbCount) {
                val verb = data.verbs[i]
                if (verb == PathVerb.Conic) {
                    val pow2 =
                        computeConicQuadPow2(
                            pts[2 * p - 2],
                            pts[2 * p - 1],
                            pts[2 * p],
                            pts[2 * p + 1],
                            pts[2 * p + 2],
                            pts[2 * p + 3],
                            data.conicWeights[w++],
                            tolerance,
                        )
                    size += (1 shl pow2) - 1
                }
                p += PathVerb.pointCount(verb)
            }
        }
        return size
    }

    override fun hasNext(): Boolean = quadIndex < quadCount || verbIndex < data.verbCount

    override fun next(outPoints: FloatArray, offset: Int): PathSegment.Type {
        check(outPoints.size - offset >= 8) { "The points array must contain at least 8 floats" }
        if (quadIndex < quadCount) {
            return nextConvertedQuad(outPoints, offset)
        }
        if (verbIndex >= data.verbCount) return PathSegment.Type.Done

        val pts = data.points
        val p = pointIndex
        val verb = data.verbs[verbIndex++]
        pointIndex += PathVerb.pointCount(verb)
        return when (verb) {
            PathVerb.Move -> {
                outPoints[offset] = pts[2 * p]
                outPoints[offset + 1] = pts[2 * p + 1]
                PathSegment.Type.Move
            }
            PathVerb.Line -> {
                pts.copyInto(outPoints, offset, 2 * p - 2, 2 * p + 2)
                PathSegment.Type.Line
            }
            PathVerb.Quad -> {
                pts.copyInto(outPoints, offset, 2 * p - 2, 2 * p + 4)
                PathSegment.Type.Quadratic
            }
            PathVerb.Conic -> {
                val weight = data.conicWeights[weightIndex++]
                if (conicEvaluation == PathIterator.ConicEvaluation.AsConic) {
                    pts.copyInto(outPoints, offset, 2 * p - 2, 2 * p + 4)
                    outPoints[offset + 6] = weight
                    outPoints[offset + 7] = weight
                    PathSegment.Type.Conic
                } else {
                    convertConic(p, weight)
                    nextConvertedQuad(outPoints, offset)
                }
            }
            PathVerb.Cubic -> {
                pts.copyInto(outPoints, offset, 2 * p - 2, 2 * p + 6)
                PathSegment.Type.Cubic
            }
            PathVerb.Close -> PathSegment.Type.Close
            else -> PathSegment.Type.Done
        }
    }

    override fun next(): PathSegment {
        if (!hasNext()) return DoneSegment
        val points = FloatArray(8)
        val type = next(points, 0)
        return when (type) {
            PathSegment.Type.Move -> PathSegment(type, points.copyOf(2), 0f)
            PathSegment.Type.Line -> PathSegment(type, points.copyOf(4), 0f)
            PathSegment.Type.Quadratic -> PathSegment(type, points.copyOf(6), 0f)
            PathSegment.Type.Conic -> PathSegment(type, points.copyOf(6), points[6])
            PathSegment.Type.Cubic -> PathSegment(type, points, 0f)
            PathSegment.Type.Close -> CloseSegment
            else -> DoneSegment
        }
    }

    /** Converts the conic whose control point is at point index [p] into quadratics. */
    private fun convertConic(p: Int, weight: Float) {
        val pts = data.points
        val x0 = pts[2 * p - 2]
        val y0 = pts[2 * p - 1]
        val x1 = pts[2 * p]
        val y1 = pts[2 * p + 1]
        val x2 = pts[2 * p + 2]
        val y2 = pts[2 * p + 3]
        val pow2 = computeConicQuadPow2(x0, y0, x1, y1, x2, y2, weight, tolerance)
        val buffer = quadBuffer ?: FloatArray(2 * MaxConicQuadPoints).also { quadBuffer = it }
        quadCount = chopConicIntoQuadsPow2(x0, y0, x1, y1, x2, y2, weight, pow2, buffer, 0)
        quadIndex = 0
    }

    private fun nextConvertedQuad(outPoints: FloatArray, offset: Int): PathSegment.Type {
        val buffer = quadBuffer!!
        // Quad i starts at point 2 * i of the buffer (start, control, end).
        buffer.copyInto(outPoints, offset, 4 * quadIndex, 4 * quadIndex + 6)
        quadIndex++
        if (quadIndex == quadCount) {
            quadIndex = 0
            quadCount = 0
        }
        return PathSegment.Type.Quadratic
    }
}
