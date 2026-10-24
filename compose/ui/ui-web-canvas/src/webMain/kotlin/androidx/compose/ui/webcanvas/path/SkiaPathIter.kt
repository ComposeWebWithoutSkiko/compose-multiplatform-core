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

/**
 * Port of Skia's `SkPath::Iter`, used by the algorithms (path effects) that were written against
 * it. Unlike the raw verb walk it emits an explicit closing line before a close verb when the
 * contour does not end at its start point, and it does not report a trailing moveTo.
 *
 * [next] returns a [PathVerb] or [Done] and fills [pts] with the points of the segment, the start
 * point included (`pts[0..1]`).
 */
internal class SkiaPathIter(private val data: PathData, private val forceClose: Boolean = false) {
    private var verbIndex = 0
    private var pointIndex = 0
    private var weightIndex = 0
    private var moveX = 0f
    private var moveY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var needClose = false

    /** Points of the last segment returned by [next], start point first. */
    val pts = FloatArray(8)

    /** Weight of the last conic returned by [next]. */
    var conicWeight = 0f
        private set

    fun next(): Int {
        val verbs = data.verbs
        if (verbIndex == data.verbCount) {
            // Close the curve if requested and if there is some curve to close.
            if (needClose) {
                if (autoClose() == PathVerb.Line.toInt()) return PathVerb.Line.toInt()
                needClose = false
                return PathVerb.Close.toInt()
            }
            return Done
        }
        val src = data.points
        val verb = verbs[verbIndex++]
        when (verb) {
            PathVerb.Move -> {
                if (needClose) {
                    verbIndex-- // Move back one verb.
                    val v = autoClose()
                    if (v == PathVerb.Close.toInt()) needClose = false
                    return v
                }
                if (verbIndex == data.verbCount) {
                    // Might be a trailing moveTo.
                    return Done
                }
                moveX = src[2 * pointIndex]
                moveY = src[2 * pointIndex + 1]
                pts[0] = moveX
                pts[1] = moveY
                pointIndex += 1
                lastX = moveX
                lastY = moveY
                needClose = forceClose
            }
            PathVerb.Line -> {
                pts[0] = lastX
                pts[1] = lastY
                pts[2] = src[2 * pointIndex]
                pts[3] = src[2 * pointIndex + 1]
                lastX = pts[2]
                lastY = pts[3]
                pointIndex += 1
            }
            PathVerb.Quad,
            PathVerb.Conic -> {
                if (verb == PathVerb.Conic) conicWeight = data.conicWeights[weightIndex++]
                pts[0] = lastX
                pts[1] = lastY
                src.copyInto(pts, 2, 2 * pointIndex, 2 * pointIndex + 4)
                lastX = pts[4]
                lastY = pts[5]
                pointIndex += 2
            }
            PathVerb.Cubic -> {
                pts[0] = lastX
                pts[1] = lastY
                src.copyInto(pts, 2, 2 * pointIndex, 2 * pointIndex + 6)
                lastX = pts[6]
                lastY = pts[7]
                pointIndex += 3
            }
            PathVerb.Close -> {
                val v = autoClose()
                if (v == PathVerb.Line.toInt()) {
                    verbIndex-- // Move back one verb.
                } else {
                    needClose = false
                }
                lastX = moveX
                lastY = moveY
                return v
            }
        }
        return verb.toInt()
    }

    private fun autoClose(): Int {
        if (lastX != moveX || lastY != moveY) {
            // A special case: if both points are NaN, they are treated as the same.
            if (lastX.isNaN() || lastY.isNaN() || moveX.isNaN() || moveY.isNaN()) {
                return PathVerb.Close.toInt()
            }
            pts[0] = lastX
            pts[1] = lastY
            pts[2] = moveX
            pts[3] = moveY
            lastX = moveX
            lastY = moveY
            return PathVerb.Line.toInt()
        }
        pts[0] = moveX
        pts[1] = moveY
        return PathVerb.Close.toInt()
    }

    /**
     * Returns true if the contour that was just started by a moveTo is closed (Skia's
     * `SkPath::Iter::isClosedContour`).
     */
    fun isClosedContour(): Boolean {
        if (verbIndex >= data.verbCount) return false
        if (forceClose) return true
        val verbs = data.verbs
        var v = verbIndex
        if (verbs[v] == PathVerb.Move) v++ // Skip the initial moveTo.
        while (v < data.verbCount) {
            val verb = verbs[v++]
            if (verb == PathVerb.Move) break
            if (verb == PathVerb.Close) return true
        }
        return false
    }

    companion object {
        /** Returned by [next] at the end of the path. */
        const val Done = 6
    }
}
