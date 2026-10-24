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
 * Computes whether [data] is convex, porting Skia's `SkPath::computeConvexity`: a path is convex if
 * it has at most one contour (ignoring leading and trailing moveTos) whose control polygon only
 * turns in one direction and winds at most once.
 */
internal fun computeIsConvex(data: PathData): Boolean {
    if (!data.isFinite) return false
    val verbs = data.verbs
    val verbCount = data.verbCount
    val pts = data.points

    // pointCount potentially includes a block of leading moveTos and trailing moveTos. Convexity
    // only cares about the last of the initial moveTos and the verbs before the final moveTos.
    var pointCount = data.pointCount
    var leadingMoves = 0
    while (leadingMoves < verbCount && verbs[leadingMoves] == PathVerb.Move) leadingMoves++
    val skipCount = leadingMoves - 1
    val lastMoveToIndex = data.lastMoveToIndex
    if (lastMoveToIndex >= 0) {
        if (lastMoveToIndex == pointCount - 1) {
            // Ignore trailing moveTos.
            var v = verbCount - 1
            while (v > 0 && verbs[v - 1] == PathVerb.Move) {
                v--
                pointCount--
            }
        } else if (lastMoveToIndex != skipCount) {
            // There's an additional moveTo between two blocks of other verbs, so the path has
            // more than one contour and cannot be convex.
            return false
        }
    }
    var firstPoint = 0
    if (skipCount > 0) {
        firstPoint = skipCount
        pointCount -= skipCount
    }

    // Quick concave test: the path changes direction more than three times.
    if (!convexBySign(pts, firstPoint, pointCount)) return false

    val state = Convexicator()
    var contourCount = 0
    var needsClose = false
    var p = 0
    for (i in 0 until verbCount) {
        val verb = verbs[i]
        if (contourCount == 0) {
            if (verb == PathVerb.Move) {
                state.setMovePt(pts[2 * p], pts[2 * p + 1])
            } else {
                // Starting the actual contour, fall through to add the points.
                contourCount++
                needsClose = true
            }
        }
        if (contourCount == 1) {
            if (verb == PathVerb.Close || verb == PathVerb.Move) {
                if (!state.close()) return false
                needsClose = false
                contourCount++
            } else {
                val count = PathVerb.pointCount(verb)
                for (k in 0 until count) {
                    if (!state.addPt(pts[2 * (p + k)], pts[2 * (p + k) + 1])) return false
                }
            }
        } else if (contourCount > 1 && verb != PathVerb.Move) {
            // The first contour has closed and anything other than spurious trailing moves means
            // there are multiple contours and the path can't be convex.
            return false
        }
        p += PathVerb.pointCount(verb)
    }
    // If the path isn't explicitly closed do so implicitly.
    if (needsClose && !state.close()) return false

    if (state.firstDirection == 0) {
        val empty = !(data.left < data.right && data.top < data.bottom)
        if (!empty) {
            return state.reversals < 3
        }
    }
    return true
}

private fun sign(value: Float): Int = if (value < 0f) 1 else 0

/** Port of Skia's `Convexicator::BySign`. */
private fun convexBySign(pts: FloatArray, firstPoint: Int, count: Int): Boolean {
    if (count <= 3) {
        // point, line, or triangle are always convex
        return true
    }
    val never = 2 // a value never returned by sign()
    var currX = pts[2 * firstPoint]
    var currY = pts[2 * firstPoint + 1]
    val firstX = currX
    val firstY = currY
    var dxes = 0
    var dyes = 0
    var lastSx = never
    var lastSy = never
    var index = 1
    // Walk all the points once, then the vector back to the first point.
    for (outerLoop in 0 until 2) {
        while (true) {
            val px: Float
            val py: Float
            if (outerLoop == 0) {
                if (index >= count) break
                px = pts[2 * (firstPoint + index)]
                py = pts[2 * (firstPoint + index) + 1]
                index++
            } else {
                px = firstX
                py = firstY
            }
            val vx = px - currX
            val vy = py - currY
            if (vx != 0f || vy != 0f) {
                if (!vx.isFinite() || !vy.isFinite()) return false
                val sx = sign(vx)
                val sy = sign(vy)
                if (sx != lastSx) dxes++
                if (sy != lastSy) dyes++
                if (dxes > 3 || dyes > 3) return false
                lastSx = sx
                lastSy = sy
            }
            currX = px
            currY = py
            if (outerLoop == 1) break
        }
    }
    return true
}

/** Port of Skia's `Convexicator` (direction-change tracker). */
private class Convexicator {
    private var firstX = 0f
    private var firstY = 0f
    private var firstVecX = 0f
    private var firstVecY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastVecX = 0f
    private var lastVecY = 0f
    private var expectedDir = DirInvalid

    /** 0 = unknown, 1 = clockwise, 2 = counter-clockwise. */
    var firstDirection = 0
        private set

    var reversals = 0
        private set

    fun setMovePt(x: Float, y: Float) {
        firstX = x
        firstY = y
        lastX = x
        lastY = y
        expectedDir = DirInvalid
    }

    fun addPt(x: Float, y: Float): Boolean {
        if (lastX == x && lastY == y) return true
        // Should only be true for the first non-zero vector after setMovePt was called.
        if (
            firstX == lastX &&
                firstY == lastY &&
                expectedDir == DirInvalid &&
                lastVecX == 0f &&
                lastVecY == 0f
        ) {
            lastVecX = x - lastX
            lastVecY = y - lastY
            firstVecX = lastVecX
            firstVecY = lastVecY
        } else if (!addVec(x - lastX, y - lastY)) {
            return false
        }
        lastX = x
        lastY = y
        return true
    }

    fun close(): Boolean = addPt(firstX, firstY) && addVec(firstVecX, firstVecY)

    private fun addVec(vx: Float, vy: Float): Boolean {
        val cross = lastVecX * vy - lastVecY * vx
        if (!cross.isFinite()) return false
        val dir =
            when {
                cross == 0f -> if (lastVecX * vx + lastVecY * vy < 0f) DirBackwards else DirStraight
                cross > 0f -> DirRight
                else -> DirLeft
            }
        when (dir) {
            DirLeft,
            DirRight -> {
                if (expectedDir == DirInvalid) {
                    expectedDir = dir
                    firstDirection = if (dir == DirRight) 1 else 2
                } else if (dir != expectedDir) {
                    firstDirection = 0
                    return false
                }
                lastVecX = vx
                lastVecY = vy
            }
            DirStraight -> {}
            DirBackwards -> {
                // Allow path to reverse direction twice.
                lastVecX = vx
                lastVecY = vy
                return ++reversals < 3
            }
        }
        return true
    }

    private companion object {
        const val DirLeft = 0
        const val DirRight = 1
        const val DirStraight = 2
        const val DirBackwards = 3
        const val DirInvalid = 4
    }
}
