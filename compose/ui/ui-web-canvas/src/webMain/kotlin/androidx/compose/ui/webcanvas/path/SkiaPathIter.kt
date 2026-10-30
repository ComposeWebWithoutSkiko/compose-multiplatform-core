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
 * [next] returns a [PathVerb] or [Done]; the points of the segment, its start point first, are read
 * with [x] and [y] where they are in the path.
 */
internal class SkiaPathIter(private val data: PathData) {
    private var verbIndex = 0
    private var pointIndex = 0
    private var weightIndex = 0

    /** The index of the first point of the contour. */
    private var moveIndex = 0

    /** The index of the point the contour has reached. */
    private var lastIndex = 0

    // The last segment: the points from [segmentStart] on, or the line closing the contour, from
    // [segmentStart] to [moveIndex]. The points of a verb follow the point it starts from, as a
    // moveTo follows every close ([WebPath] injects one).
    private var segmentStart = 0
    private var isClosingLine = false

    /** Weight of the last conic returned by [next]. */
    var conicWeight = 0f
        private set

    /** The x coordinate of point [i] of the last segment returned by [next], its start point 0. */
    fun x(i: Int): Float = data.points[2 * pointOf(i)]

    /** The y coordinate of point [i] of the last segment returned by [next], its start point 0. */
    fun y(i: Int): Float = data.points[2 * pointOf(i) + 1]

    private fun pointOf(i: Int): Int = if (isClosingLine && i == 1) moveIndex else segmentStart + i

    fun next(): Int {
        val verbs = data.verbs
        if (verbIndex == data.verbCount) return Done
        val verb = verbs[verbIndex++]
        isClosingLine = false
        when (verb) {
            PathVerb.Move -> {
                if (verbIndex == data.verbCount) {
                    // Might be a trailing moveTo.
                    return Done
                }
                moveIndex = pointIndex
                lastIndex = pointIndex
                segmentStart = pointIndex
                pointIndex += 1
            }
            PathVerb.Close -> {
                val v = autoClose()
                if (v == PathVerb.Line.toInt()) verbIndex-- // Move back one verb.
                lastIndex = moveIndex
                return v
            }
            else -> {
                if (verb == PathVerb.Conic) conicWeight = data.conicWeights[weightIndex++]
                segmentStart = lastIndex
                pointIndex += PathVerb.pointCount(verb)
                lastIndex = pointIndex - 1
            }
        }
        return verb.toInt()
    }

    private fun autoClose(): Int {
        val pts = data.points
        val lastX = pts[2 * lastIndex]
        val lastY = pts[2 * lastIndex + 1]
        val moveX = pts[2 * moveIndex]
        val moveY = pts[2 * moveIndex + 1]
        if (lastX != moveX || lastY != moveY) {
            // A special case: if both points are NaN, they are treated as the same.
            if (lastX.isNaN() || lastY.isNaN() || moveX.isNaN() || moveY.isNaN()) {
                segmentStart = moveIndex
                return PathVerb.Close.toInt()
            }
            segmentStart = lastIndex
            isClosingLine = true
            return PathVerb.Line.toInt()
        }
        segmentStart = moveIndex
        return PathVerb.Close.toInt()
    }

    /**
     * Returns true if the contour that was just started by a moveTo is closed (Skia's
     * `SkPath::Iter::isClosedContour`).
     */
    fun isClosedContour(): Boolean {
        if (verbIndex >= data.verbCount) return false
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
