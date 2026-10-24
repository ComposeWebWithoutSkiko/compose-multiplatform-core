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

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * Computes the boolean [operation] of [a] and [b] (Skia's `Op`), honoring the fill type of both
 * operands. Returns null if the operation failed (non-finite input).
 *
 * Curves are flattened (maximum deviation 0.1 unit, less for small paths), so the result only
 * contains lines. Like Skia, the result consists of non-overlapping closed contours and uses the
 * [PathFillType.EvenOdd] fill type; outer contours and holes have opposite orientations, so the
 * result also fills correctly with [PathFillType.NonZero].
 *
 * The algorithm computes the planar arrangement of the edges of both polygons:
 * 1. Polygons are snapped onto an integer grid (2^24 units across the inputs), on which the
 *    orientation predicates are exact in double precision.
 * 2. Edges are split at all their intersections ("noding"); intersection points are rounded to the
 *    grid and the edges they perturb are re-checked until no crossing remains.
 * 3. Coincident edges are merged, adding their winding contributions.
 * 4. A sweep line computes the winding numbers of both operands on both sides of every edge.
 * 5. Edges separating "inside" from "outside" (according to the fill rules and the operation) are
 *    kept, oriented with the inside on their left, and chained into contours.
 */
internal fun pathOp(a: PathData, b: PathData, operation: PathOperation): PathData? {
    if (!a.isFinite || !b.isFinite) return null
    val op =
        when (operation) {
            PathOperation.Difference -> OpDifference
            PathOperation.Intersect -> OpIntersect
            PathOperation.Union -> OpUnion
            PathOperation.ReverseDifference -> OpReverseDifference
            else -> OpXor
        }
    return BooleanOperation(
            a.flattened(),
            a.fillType == PathFillType.EvenOdd,
            b.flattened(),
            b.fillType == PathFillType.EvenOdd,
            op,
        )
        .run()
}

private const val OpDifference = 0
private const val OpIntersect = 1
private const val OpUnion = 2
private const val OpXor = 3
private const val OpReverseDifference = 4

/** Half extent of the snapping grid. Keeps orientation predicates exact in doubles. */
private const val GridHalfExtent = 16777216.0 // 2^24

/** Maximum number of noding passes; each pass re-checks the edges perturbed by rounding. */
private const val MaxNodingRounds = 8

private val EmptyResult: PathData
    get() = WebPath().apply { fillType = PathFillType.EvenOdd }.snapshot()

private class BooleanOperation(
    private var polyA: FlattenedPath,
    private val evenOddA: Boolean,
    private var polyB: FlattenedPath,
    private val evenOddB: Boolean,
    private val op: Int,
) {
    // Grid transformation: grid = round((value - center) * scale).
    private var centerX = 0.0
    private var centerY = 0.0
    private var scale = 1.0

    fun run(): PathData {
        if (!reduceInputs()) return EmptyResult
        if (!computeGrid()) return EmptyResult
        var edges = EdgeStore(polyA.pointCount + polyB.pointCount + 8)
        addPolygonEdges(edges, polyA, owner = 0)
        addPolygonEdges(edges, polyB, owner = 1)
        if (edges.size == 0) return EmptyResult
        edges = node(edges)
        edges = mergeCoincidentEdges(edges)
        if (edges.size == 0) return EmptyResult
        val boundary = selectBoundaryEdges(edges)
        if (boundary.size == 0) return EmptyResult
        return buildResult(boundary)
    }

    // ---------------------------------------------------------------------------------------
    // Input reduction
    // ---------------------------------------------------------------------------------------

    /**
     * Clips the operands to the region where the result can lie (Sutherland–Hodgman clipping to an
     * axis-aligned rectangle preserves the winding numbers inside the rectangle). Returns false if
     * the result is known to be empty.
     */
    private fun reduceInputs(): Boolean {
        val boundsA = polygonBounds(polyA)
        val boundsB = polygonBounds(polyB)
        when (op) {
            OpIntersect -> {
                if (boundsA == null || boundsB == null) return false
                val l = max(boundsA[0], boundsB[0])
                val t = max(boundsA[1], boundsB[1])
                val r = min(boundsA[2], boundsB[2])
                val b = min(boundsA[3], boundsB[3])
                if (!(l < r && t < b)) return false
                polyA = clipToRect(polyA, boundsA, l, t, r, b)
                polyB = clipToRect(polyB, boundsB, l, t, r, b)
            }
            OpDifference -> {
                if (boundsA == null) return false
                if (boundsB != null) {
                    polyB =
                        clipToRect(polyB, boundsB, boundsA[0], boundsA[1], boundsA[2], boundsA[3])
                }
            }
            OpReverseDifference -> {
                if (boundsB == null) return false
                if (boundsA != null) {
                    polyA =
                        clipToRect(polyA, boundsA, boundsB[0], boundsB[1], boundsB[2], boundsB[3])
                }
            }
            else -> {
                if (boundsA == null && boundsB == null) return false
            }
        }
        return true
    }

    /** Chooses the grid; returns false if the inputs are degenerate. */
    private fun computeGrid(): Boolean {
        val boundsA = polygonBounds(polyA)
        val boundsB = polygonBounds(polyB)
        val bounds = boundsA ?: boundsB ?: return false
        if (boundsA != null && boundsB != null) {
            bounds[0] = min(boundsA[0], boundsB[0])
            bounds[1] = min(boundsA[1], boundsB[1])
            bounds[2] = max(boundsA[2], boundsB[2])
            bounds[3] = max(boundsA[3], boundsB[3])
        }
        centerX = (bounds[0] + bounds[2]) * 0.5
        centerY = (bounds[1] + bounds[3]) * 0.5
        val halfExtent = max(bounds[2] - bounds[0], bounds[3] - bounds[1]) * 0.5
        if (!(halfExtent > 0.0) || !halfExtent.isFinite()) return false
        // Power of two so that the scaling is exact.
        scale = 2.0.pow(floor(log2(GridHalfExtent / halfExtent)))
        return scale.isFinite() && scale > 0.0
    }

    private fun toGridX(x: Double): Double = snapToGrid((x - centerX) * scale)

    private fun toGridY(y: Double): Double = snapToGrid((y - centerY) * scale)

    private fun fromGridX(x: Double): Float = (x / scale + centerX).toFloat()

    private fun fromGridY(y: Double): Float = (y / scale + centerY).toFloat()

    private fun addPolygonEdges(edges: EdgeStore, poly: FlattenedPath, owner: Int) {
        val xy = poly.xy
        for (c in 0 until poly.contourCount) {
            val start = poly.start(c)
            val end = poly.end(c)
            if (end - start < 2) continue
            var prevX = toGridX(xy[2 * (end - 1)])
            var prevY = toGridY(xy[2 * (end - 1) + 1])
            for (i in start until end) {
                val x = toGridX(xy[2 * i])
                val y = toGridY(xy[2 * i + 1])
                if (x != prevX || y != prevY) {
                    if (owner == 0) {
                        edges.add(prevX, prevY, x, y, 1, 0, false)
                    } else {
                        edges.add(prevX, prevY, x, y, 0, 1, false)
                    }
                }
                prevX = x
                prevY = y
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Noding
    // ---------------------------------------------------------------------------------------

    private fun node(input: EdgeStore): EdgeStore {
        var edges = input
        for (pass in 0 until MaxNodingRounds) {
            val splits = SplitList()
            findIntersections(edges, splits, onlyDirty = pass > 0)
            if (splits.size == 0) break
            edges = applySplits(edges, splits)
        }
        return edges
    }

    /**
     * Records the points where edges must be split: proper crossings (rounded to the grid),
     * endpoints lying inside other edges and the ends of collinear overlaps.
     */
    private fun findIntersections(edges: EdgeStore, splits: SplitList, onlyDirty: Boolean) {
        val n = edges.size
        val x0 = edges.x0
        val y0 = edges.y0
        val x1 = edges.x1
        val y1 = edges.y1
        val dirty = edges.dirty
        val order = sortIndices(n) { i, j -> x0[i].compareTo(x0[j]) }
        val active = IntArray(n)
        var activeCount = 0
        for (k in 0 until n) {
            val i = order[k]
            val minX = x0[i]
            // Drop the edges that end before this one starts.
            var kept = 0
            for (r in 0 until activeCount) {
                val j = active[r]
                if (x1[j] >= minX) active[kept++] = j
            }
            activeCount = kept
            val iMinY = min(y0[i], y1[i])
            val iMaxY = max(y0[i], y1[i])
            for (r in 0 until activeCount) {
                val j = active[r]
                if (onlyDirty && !dirty[i] && !dirty[j]) continue
                if (max(y0[j], y1[j]) < iMinY || min(y0[j], y1[j]) > iMaxY) continue
                intersect(edges, i, j, splits)
            }
            active[activeCount++] = i
        }
    }

    private fun intersect(edges: EdgeStore, i: Int, j: Int, splits: SplitList) {
        val ax = edges.x0[i]
        val ay = edges.y0[i]
        val bx = edges.x1[i]
        val by = edges.y1[i]
        val cx = edges.x0[j]
        val cy = edges.y0[j]
        val dx = edges.x1[j]
        val dy = edges.y1[j]
        val d1 = orient(ax, ay, bx, by, cx, cy)
        val d2 = orient(ax, ay, bx, by, dx, dy)
        if ((d1 > 0.0 && d2 > 0.0) || (d1 < 0.0 && d2 < 0.0)) return
        val d3 = orient(cx, cy, dx, dy, ax, ay)
        val d4 = orient(cx, cy, dx, dy, bx, by)
        if ((d3 > 0.0 && d4 > 0.0) || (d3 < 0.0 && d4 < 0.0)) return
        if (d1 == 0.0 && d2 == 0.0) {
            // Collinear: split each edge at the endpoints of the other one lying inside it.
            if (strictlyInside(cx, cy, ax, ay, bx, by)) splits.add(i, cx, cy, false)
            if (strictlyInside(dx, dy, ax, ay, bx, by)) splits.add(i, dx, dy, false)
            if (strictlyInside(ax, ay, cx, cy, dx, dy)) splits.add(j, ax, ay, false)
            if (strictlyInside(bx, by, cx, cy, dx, dy)) splits.add(j, bx, by, false)
            return
        }
        when {
            // An endpoint of one edge lies on the other edge (T-junction or shared endpoint).
            d1 == 0.0 -> {
                if (!isEndpoint(cx, cy, ax, ay, bx, by)) splits.add(i, cx, cy, false)
            }
            d2 == 0.0 -> {
                if (!isEndpoint(dx, dy, ax, ay, bx, by)) splits.add(i, dx, dy, false)
            }
            d3 == 0.0 -> {
                if (!isEndpoint(ax, ay, cx, cy, dx, dy)) splits.add(j, ax, ay, false)
            }
            d4 == 0.0 -> {
                if (!isEndpoint(bx, by, cx, cy, dx, dy)) splits.add(j, bx, by, false)
            }
            else -> {
                // Proper crossing.
                val t = d3 / (d3 - d4)
                val px = snapToGrid(ax + t * (bx - ax))
                val py = snapToGrid(ay + t * (by - ay))
                if (!isEndpoint(px, py, ax, ay, bx, by)) splits.add(i, px, py, true)
                if (!isEndpoint(px, py, cx, cy, dx, dy)) splits.add(j, px, py, true)
            }
        }
    }

    private fun applySplits(edges: EdgeStore, splits: SplitList): EdgeStore {
        val m = splits.size
        val splitEdge = splits.edge
        val splitX = splits.x
        val splitY = splits.y
        // Position of each split point along its edge.
        val position = DoubleArray(m)
        for (k in 0 until m) {
            val e = splitEdge[k]
            position[k] =
                (splitX[k] - edges.x0[e]) * (edges.x1[e] - edges.x0[e]) +
                    (splitY[k] - edges.y0[e]) * (edges.y1[e] - edges.y0[e])
        }
        val order =
            sortIndices(m) { p, q ->
                val c = splitEdge[p].compareTo(splitEdge[q])
                if (c != 0) c else position[p].compareTo(position[q])
            }
        val result = EdgeStore(edges.size + m)
        var k = 0
        for (e in 0 until edges.size) {
            val wa = edges.wa[e]
            val wb = edges.wb[e]
            if (k >= m || splitEdge[order[k]] != e) {
                result.add(edges.x0[e], edges.y0[e], edges.x1[e], edges.y1[e], wa, wb, false)
                continue
            }
            var px = edges.x0[e]
            var py = edges.y0[e]
            var prevRounded = false
            val endX = edges.x1[e]
            val endY = edges.y1[e]
            while (k < m && splitEdge[order[k]] == e) {
                // Merge the records of identical points.
                val sx = splitX[order[k]]
                val sy = splitY[order[k]]
                var rounded = false
                while (
                    k < m &&
                        splitEdge[order[k]] == e &&
                        splitX[order[k]] == sx &&
                        splitY[order[k]] == sy
                ) {
                    rounded = rounded || splits.rounded[order[k]]
                    k++
                }
                if (sx == px && sy == py) {
                    prevRounded = prevRounded || rounded
                    continue
                }
                if (sx == endX && sy == endY) continue
                result.add(px, py, sx, sy, wa, wb, prevRounded || rounded)
                px = sx
                py = sy
                prevRounded = rounded
            }
            result.add(px, py, endX, endY, wa, wb, prevRounded)
        }
        return result
    }

    // ---------------------------------------------------------------------------------------
    // Merging and classification
    // ---------------------------------------------------------------------------------------

    private fun mergeCoincidentEdges(edges: EdgeStore): EdgeStore {
        val x0 = edges.x0
        val y0 = edges.y0
        val x1 = edges.x1
        val y1 = edges.y1
        val order =
            sortIndices(edges.size) { i, j ->
                var c = x0[i].compareTo(x0[j])
                if (c == 0) c = y0[i].compareTo(y0[j])
                if (c == 0) c = x1[i].compareTo(x1[j])
                if (c == 0) c = y1[i].compareTo(y1[j])
                c
            }
        val result = EdgeStore(edges.size)
        var k = 0
        while (k < edges.size) {
            val e = order[k]
            var wa = 0
            var wb = 0
            var next = k
            while (next < edges.size) {
                val f = order[next]
                if (x0[f] != x0[e] || y0[f] != y0[e] || x1[f] != x1[e] || y1[f] != y1[e]) break
                wa += edges.wa[f]
                wb += edges.wb[f]
                next++
            }
            if ((wa != 0 || wb != 0) && (x0[e] != x1[e] || y0[e] != y1[e])) {
                result.add(x0[e], y0[e], x1[e], y1[e], wa, wb, false)
            }
            k = next
        }
        return result
    }

    private fun isInside(windingA: Int, windingB: Int): Boolean {
        val inA = if (evenOddA) (windingA and 1) != 0 else windingA != 0
        val inB = if (evenOddB) (windingB and 1) != 0 else windingB != 0
        return when (op) {
            OpDifference -> inA && !inB
            OpIntersect -> inA && inB
            OpUnion -> inA || inB
            OpReverseDifference -> inB && !inA
            else -> inA != inB
        }
    }

    /**
     * Sweeps the (non-crossing) edges in lexicographic order of their endpoints, keeping the active
     * edges sorted from bottom to top. The region below a newly inserted edge is the region above
     * its predecessor, which gives the winding numbers on both sides of every edge.
     *
     * "Above" an edge means on the left of its canonical (lexicographically increasing) direction;
     * vertical edges are handled consistently by treating the sweep line as slightly rotated.
     */
    private fun selectBoundaryEdges(edges: EdgeStore): DirectedEdges {
        val n = edges.size
        val x0 = edges.x0
        val y0 = edges.y0
        val x1 = edges.x1
        val y1 = edges.y1
        val startOrder =
            sortIndices(n) { i, j ->
                val c = x0[i].compareTo(x0[j])
                if (c != 0) c else y0[i].compareTo(y0[j])
            }
        val endOrder =
            sortIndices(n) { i, j ->
                val c = x1[i].compareTo(x1[j])
                if (c != 0) c else y1[i].compareTo(y1[j])
            }
        val aboveA = IntArray(n)
        val aboveB = IntArray(n)
        val status = IntArray(n)
        var statusSize = 0
        val boundary = DirectedEdges(n)

        var si = 0
        var ei = 0
        while (si < n) {
            val first = startOrder[si]
            val vx = x0[first]
            val vy = y0[first]
            // Remove the edges ending at or before the event point.
            while (ei < n) {
                val e = endOrder[ei]
                if (x1[e] > vx || (x1[e] == vx && y1[e] > vy)) break
                var pos = statusSize - 1
                while (pos >= 0 && status[pos] != e) pos--
                if (pos >= 0) {
                    status.copyInto(status, pos, pos + 1, statusSize)
                    statusSize--
                }
                ei++
            }
            // Insert the edges starting at the event point.
            while (si < n && x0[startOrder[si]] == vx && y0[startOrder[si]] == vy) {
                val e = startOrder[si]
                var lo = 0
                var hi = statusSize
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (isAbove(edges, e, status[mid])) lo = mid + 1 else hi = mid
                }
                status.copyInto(status, lo + 1, lo, statusSize)
                status[lo] = e
                statusSize++
                si++
            }
            // The new edges are contiguous in the status (an edge lying between two edges that
            // start at the event point would have to go through it). Compute their winding
            // numbers from the bottom up, once all of them are in place.
            var pos = statusSize - 1
            while (pos >= 0 && status[pos] != first) pos--
            while (pos > 0 && x0[status[pos - 1]] == vx && y0[status[pos - 1]] == vy) pos--
            while (pos in 0 until statusSize && x0[status[pos]] == vx && y0[status[pos]] == vy) {
                val e = status[pos]
                val belowA = if (pos > 0) aboveA[status[pos - 1]] else 0
                val belowB = if (pos > 0) aboveB[status[pos - 1]] else 0
                aboveA[e] = belowA + edges.wa[e]
                aboveB[e] = belowB + edges.wb[e]
                val insideBelow = isInside(belowA, belowB)
                val insideAbove = isInside(aboveA[e], aboveB[e])
                if (insideBelow != insideAbove) {
                    // Orient the boundary with the inside on its left.
                    if (insideAbove) {
                        boundary.add(x0[e], y0[e], x1[e], y1[e])
                    } else {
                        boundary.add(x1[e], y1[e], x0[e], y0[e])
                    }
                }
                pos++
            }
        }
        return boundary
    }

    /** Returns true if edge [e], inserted at its start point, lies above the active edge [f]. */
    private fun isAbove(edges: EdgeStore, e: Int, f: Int): Boolean {
        val ex = edges.x0[e]
        val ey = edges.y0[e]
        val fx0 = edges.x0[f]
        val fy0 = edges.y0[f]
        val fx1 = edges.x1[f]
        val fy1 = edges.y1[f]
        if (fx0 == ex && fy0 == ey) {
            // Shared start point: compare the directions.
            return orient(ex, ey, fx1, fy1, edges.x1[e], edges.y1[e]) > 0.0
        }
        val o = orient(fx0, fy0, fx1, fy1, ex, ey)
        if (o != 0.0) return o > 0.0
        // Only possible if noding left a T-junction: use the other end point.
        return orient(fx0, fy0, fx1, fy1, edges.x1[e], edges.y1[e]) > 0.0
    }

    // ---------------------------------------------------------------------------------------
    // Output
    // ---------------------------------------------------------------------------------------

    private fun buildResult(boundary: DirectedEdges): PathData {
        val m = boundary.size
        val fx = boundary.fromX
        val fy = boundary.fromY
        val tx = boundary.toX
        val ty = boundary.toY
        val order =
            sortIndices(m) { i, j ->
                val c = fx[i].compareTo(fx[j])
                if (c != 0) c else fy[i].compareTo(fy[j])
            }
        val used = BooleanArray(m)
        val path = WebPath()
        path.fillType = PathFillType.EvenOdd
        val contour = GridPolygon()
        for (s in 0 until m) {
            val first = order[s]
            if (used[first]) continue
            used[first] = true
            contour.clear()
            contour.add(fx[first], fy[first])
            val startX = fx[first]
            val startY = fy[first]
            var current = first
            var guard = 0
            while (guard++ <= m) {
                val vx = tx[current]
                val vy = ty[current]
                if (vx == startX && vy == startY) break
                contour.add(vx, vy)
                val next = pickNextEdge(boundary, order, used, current, vx, vy)
                if (next < 0) break // Unbalanced vertex (should not happen): close the contour.
                used[next] = true
                current = next
            }
            contour.simplify()
            if (contour.size < 3) continue
            path.moveTo(fromGridX(contour.x(0)), fromGridY(contour.y(0)))
            for (i in 1 until contour.size) {
                path.lineTo(fromGridX(contour.x(i)), fromGridY(contour.y(i)))
            }
            path.close()
        }
        return path.snapshot()
    }

    /**
     * Among the unused boundary edges leaving ([vx], [vy]), returns the one making the sharpest
     * left turn after [incoming], so that contours touching at a vertex are kept separate.
     */
    private fun pickNextEdge(
        boundary: DirectedEdges,
        order: IntArray,
        used: BooleanArray,
        incoming: Int,
        vx: Double,
        vy: Double,
    ): Int {
        val fx = boundary.fromX
        val fy = boundary.fromY
        // Lower bound of the edges starting at v in [order].
        var lo = 0
        var hi = order.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val e = order[mid]
            if (fx[e] < vx || (fx[e] == vx && fy[e] < vy)) lo = mid + 1 else hi = mid
        }
        // Direction pointing back along the incoming edge.
        val rx = boundary.fromX[incoming] - vx
        val ry = boundary.fromY[incoming] - vy
        var best = -1
        var bestX = 0.0
        var bestY = 0.0
        var k = lo
        while (k < order.size) {
            val e = order[k++]
            if (fx[e] != vx || fy[e] != vy) break
            if (used[e]) continue
            val wx = boundary.toX[e] - vx
            val wy = boundary.toY[e] - vy
            if (best < 0 || angleLess(rx, ry, bestX, bestY, wx, wy)) {
                best = e
                bestX = wx
                bestY = wy
            }
        }
        return best
    }

    /**
     * Returns true if the counter-clockwise angle from (rx, ry) to (ax, ay) is smaller than the one
     * to (bx, by).
     */
    private fun angleLess(
        rx: Double,
        ry: Double,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ): Boolean {
        val ha = halfPlane(rx, ry, ax, ay)
        val hb = halfPlane(rx, ry, bx, by)
        if (ha != hb) return ha < hb
        return ax * by - ay * bx > 0.0
    }

    /** 0 if the angle from r to w is in [0, pi), 1 if it is in [pi, 2 pi). */
    private fun halfPlane(rx: Double, ry: Double, wx: Double, wy: Double): Int {
        val cross = rx * wy - ry * wx
        return if (cross > 0.0 || (cross == 0.0 && rx * wx + ry * wy > 0.0)) 0 else 1
    }

    // ---------------------------------------------------------------------------------------
    // Geometry helpers
    // ---------------------------------------------------------------------------------------

    /** Returns the bounds (left, top, right, bottom) of all the points of [poly], or null. */
    private fun polygonBounds(poly: FlattenedPath): DoubleArray? {
        val count = poly.pointCount
        if (count == 0) return null
        val xy = poly.xy
        var l = xy[0]
        var t = xy[1]
        var r = l
        var b = t
        for (i in 1 until count) {
            val x = xy[2 * i]
            val y = xy[2 * i + 1]
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        return doubleArrayOf(l, t, r, b)
    }

    /** Sutherland–Hodgman clipping of every polygon of [poly] to the rectangle. */
    private fun clipToRect(
        poly: FlattenedPath,
        polyBounds: DoubleArray,
        l: Double,
        t: Double,
        r: Double,
        b: Double,
    ): FlattenedPath {
        if (polyBounds[0] >= l && polyBounds[1] >= t && polyBounds[2] <= r && polyBounds[3] <= b) {
            return poly // Already inside.
        }
        // The clipped polygons are usually much smaller than the input (e.g. when hit testing).
        val builder = PolygonBuilder(64)
        var input = DoubleArray(64)
        var output = DoubleArray(64)
        for (c in 0 until poly.contourCount) {
            val start = poly.start(c)
            var count = poly.end(c) - start
            if (input.size < 2 * count) input = DoubleArray(2 * count + 16)
            poly.xy.copyInto(input, 0, 2 * start, 2 * (start + count))
            for (side in 0 until 4) {
                if (count == 0) break
                if (output.size < 2 * (2 * count + 1)) output = DoubleArray(4 * count + 16)
                count = clipPolygonSide(input, count, output, side, l, t, r, b)
                val swap = input
                input = output
                output = swap
            }
            if (count < 3) continue
            for (i in 0 until count) builder.addIfDistinct(input[2 * i], input[2 * i + 1])
            builder.endContour(minPoints = 3)
        }
        return builder.build()
    }

    /** Clips a closed polygon against one side of the rectangle; returns the new point count. */
    private fun clipPolygonSide(
        src: DoubleArray,
        count: Int,
        dst: DoubleArray,
        side: Int,
        l: Double,
        t: Double,
        r: Double,
        b: Double,
    ): Int {
        var out = 0
        var px = src[2 * (count - 1)]
        var py = src[2 * (count - 1) + 1]
        var pIn = insideSide(px, py, side, l, t, r, b)
        for (i in 0 until count) {
            val cx = src[2 * i]
            val cy = src[2 * i + 1]
            val cIn = insideSide(cx, cy, side, l, t, r, b)
            if (cIn != pIn) {
                // Add the intersection with the clipping line.
                val bound =
                    when (side) {
                        0 -> l
                        1 -> r
                        2 -> t
                        else -> b
                    }
                if (side < 2) {
                    val u = (bound - px) / (cx - px)
                    dst[2 * out] = bound
                    dst[2 * out + 1] = py + u * (cy - py)
                } else {
                    val u = (bound - py) / (cy - py)
                    dst[2 * out] = px + u * (cx - px)
                    dst[2 * out + 1] = bound
                }
                out++
            }
            if (cIn) {
                dst[2 * out] = cx
                dst[2 * out + 1] = cy
                out++
            }
            px = cx
            py = cy
            pIn = cIn
        }
        return out
    }

    private fun insideSide(
        x: Double,
        y: Double,
        side: Int,
        l: Double,
        t: Double,
        r: Double,
        b: Double,
    ): Boolean =
        when (side) {
            0 -> x >= l
            1 -> x <= r
            2 -> y >= t
            else -> y <= b
        }
}

/**
 * Rounds [value] to the nearest grid coordinate. Adding 0.0 turns -0.0 into 0.0: `compareTo` orders
 * -0.0 before 0.0 while `==` considers them equal, which would make the lexicographic orderings
 * used by the sweeps inconsistent.
 */
private fun snapToGrid(value: Double): Double = round(value) + 0.0

/**
 * Orientation of (cx, cy) relative to the directed line (a -> b): positive on the left (in a y-up
 * frame), negative on the right, zero if collinear. Exact for grid coordinates.
 */
private fun orient(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double =
    (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

private fun isEndpoint(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double) =
    (px == ax && py == ay) || (px == bx && py == by)

/** For a point collinear with segment (a, b): true if it lies strictly between a and b. */
private fun strictlyInside(
    px: Double,
    py: Double,
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
): Boolean {
    if (isEndpoint(px, py, ax, ay, bx, by)) return false
    return px >= min(ax, bx) && px <= max(ax, bx) && py >= min(ay, by) && py <= max(ay, by)
}

/**
 * Undirected edges on the grid, stored with their endpoints in lexicographic order (x, then y) and
 * the winding contribution of each operand (+1 if the original edge goes from the first to the
 * second endpoint, -1 otherwise).
 */
private class EdgeStore(capacity: Int) {
    var x0 = DoubleArray(max(capacity, 8))
    var y0 = DoubleArray(max(capacity, 8))
    var x1 = DoubleArray(max(capacity, 8))
    var y1 = DoubleArray(max(capacity, 8))
    var wa = IntArray(max(capacity, 8))
    var wb = IntArray(max(capacity, 8))

    /** True for edges adjacent to a rounded intersection point (re-checked by noding). */
    var dirty = BooleanArray(max(capacity, 8))
    var size = 0

    fun add(
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        windA: Int,
        windB: Int,
        isDirty: Boolean,
    ) {
        if (size == x0.size) grow()
        if (ax < bx || (ax == bx && ay <= by)) {
            x0[size] = ax
            y0[size] = ay
            x1[size] = bx
            y1[size] = by
            wa[size] = windA
            wb[size] = windB
        } else {
            x0[size] = bx
            y0[size] = by
            x1[size] = ax
            y1[size] = ay
            wa[size] = -windA
            wb[size] = -windB
        }
        dirty[size] = isDirty
        size++
    }

    private fun grow() {
        val n = x0.size * 2
        x0 = x0.copyOf(n)
        y0 = y0.copyOf(n)
        x1 = x1.copyOf(n)
        y1 = y1.copyOf(n)
        wa = wa.copyOf(n)
        wb = wb.copyOf(n)
        dirty = dirty.copyOf(n)
    }
}

/** Split points recorded during noding. */
private class SplitList {
    var edge = IntArray(16)
    var x = DoubleArray(16)
    var y = DoubleArray(16)
    var rounded = BooleanArray(16)
    var size = 0

    fun add(edgeIndex: Int, px: Double, py: Double, isRounded: Boolean) {
        if (size == edge.size) {
            val n = size * 2
            edge = edge.copyOf(n)
            x = x.copyOf(n)
            y = y.copyOf(n)
            rounded = rounded.copyOf(n)
        }
        edge[size] = edgeIndex
        x[size] = px
        y[size] = py
        rounded[size] = isRounded
        size++
    }
}

/** Directed boundary edges of the result. */
private class DirectedEdges(capacity: Int) {
    var fromX = DoubleArray(max(capacity, 8))
    var fromY = DoubleArray(max(capacity, 8))
    var toX = DoubleArray(max(capacity, 8))
    var toY = DoubleArray(max(capacity, 8))
    var size = 0

    fun add(ax: Double, ay: Double, bx: Double, by: Double) {
        if (size == fromX.size) {
            val n = size * 2
            fromX = fromX.copyOf(n)
            fromY = fromY.copyOf(n)
            toX = toX.copyOf(n)
            toY = toY.copyOf(n)
        }
        fromX[size] = ax
        fromY[size] = ay
        toX[size] = bx
        toY[size] = by
        size++
    }
}

/** A closed polygon on the grid being assembled from boundary edges. */
private class GridPolygon {
    private var xy = DoubleArray(64)
    var size = 0
        private set

    fun x(i: Int): Double = xy[2 * i]

    fun y(i: Int): Double = xy[2 * i + 1]

    fun clear() {
        size = 0
    }

    fun add(x: Double, y: Double) {
        if (2 * size + 2 > xy.size) xy = xy.copyOf(xy.size * 2)
        xy[2 * size] = x
        xy[2 * size + 1] = y
        size++
    }

    /** Removes the vertices lying in the middle of straight runs. */
    fun simplify() {
        var out = 0
        for (i in 0 until size) {
            val px = xy[2 * i]
            val py = xy[2 * i + 1]
            while (out >= 2 && isStraight(out - 2, out - 1, px, py)) out--
            xy[2 * out] = px
            xy[2 * out + 1] = py
            out++
        }
        // Handle the junction between the end and the start of the contour.
        var start = 0
        var changed = true
        while (changed && out - start >= 3) {
            changed = false
            if (isStraight(out - 2, out - 1, xy[2 * start], xy[2 * start + 1])) {
                out--
                changed = true
            } else if (
                out - start >= 3 && isStraight(out - 1, start, xy[2 * start + 2], xy[2 * start + 3])
            ) {
                start++
                changed = true
            }
        }
        if (start > 0) {
            xy.copyInto(xy, 0, 2 * start, 2 * out)
            out -= start
        }
        size = out
    }

    /** True if the vertex [b] lies on the straight segment from [a] to (cx, cy). */
    private fun isStraight(a: Int, b: Int, cx: Double, cy: Double): Boolean {
        val ax = xy[2 * a]
        val ay = xy[2 * a + 1]
        val bx = xy[2 * b]
        val by = xy[2 * b + 1]
        return orient(ax, ay, bx, by, cx, cy) == 0.0 &&
            (bx - ax) * (cx - bx) + (by - ay) * (cy - by) > 0.0
    }
}

/** Returns the indices `0 until n` stably sorted with [compare] (bottom-up merge sort). */
private inline fun sortIndices(n: Int, compare: (Int, Int) -> Int): IntArray {
    var src = IntArray(n) { it }
    if (n < 2) return src
    var dst = IntArray(n)
    var width = 1
    while (width < n) {
        var lo = 0
        while (lo < n) {
            val mid = min(lo + width, n)
            val hi = min(lo + 2 * width, n)
            var i = lo
            var j = mid
            var k = lo
            while (i < mid && j < hi) {
                if (compare(src[i], src[j]) <= 0) dst[k++] = src[i++] else dst[k++] = src[j++]
            }
            while (i < mid) dst[k++] = src[i++]
            while (j < hi) dst[k++] = src[j++]
            lo += 2 * width
        }
        val swap = src
        src = dst
        dst = swap
        width *= 2
    }
    return src
}
