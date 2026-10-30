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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * Computes the boolean [operation] of [a] and [b] (Skia's `Op`), honoring the fill type of both
 * operands. Returns null if the operation failed: non-finite input, or boundary edges that don't
 * chain into contours (which the arrangement below rules out).
 *
 * Curves are flattened (maximum deviation 0.1 unit, less for small paths), so the result only
 * contains lines. Like Skia, the result consists of non-overlapping closed contours and uses the
 * [PathFillType.EvenOdd] fill type; outer contours and holes have opposite orientations, so the
 * result also fills correctly with [PathFillType.NonZero].
 *
 * The algorithm computes the planar arrangement of the edges of both polygons:
 * 1. Polygons are snapped onto an integer grid (2^24 units across the inputs), on which the
 *    orientation predicates are exact in double precision.
 * 2. Edges are snap rounded (Hobby): the pixels (unit squares around grid points) holding a vertex
 *    or a crossing are hot, and every edge is bent through the centers of the hot pixels it goes
 *    through. The pieces then only meet at their ends or coincide: none crosses another.
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

/**
 * How far from a computed crossing the pixels that may hold it are made hot: the crossing is
 * computed with an error of about 2^-26 grid units, so the pixel holding the exact one is hot.
 */
private const val CrossingError = 1.0 / (1 shl 20)

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

    fun run(): PathData? {
        if (!reduceInputs()) return EmptyResult
        if (!computeGrid()) return EmptyResult
        val edges = EdgeStore(polyA.pointCount + polyB.pointCount + 8)
        addPolygonEdges(edges, polyA, owner = 0)
        addPolygonEdges(edges, polyB, owner = 1)
        if (edges.size == 0) return EmptyResult
        val pieces = mergeCoincidentEdges(snapRound(edges))
        if (pieces.size == 0) return EmptyResult
        val boundary = selectBoundaryEdges(pieces)
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
                        edges.add(prevX, prevY, x, y, 1, 0)
                    } else {
                        edges.add(prevX, prevY, x, y, 0, 1)
                    }
                }
                prevX = x
                prevY = y
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Snap rounding
    // ---------------------------------------------------------------------------------------

    /**
     * Snap rounding (Hobby): the pixels of the vertices and of the crossings of the edges are hot,
     * and every edge becomes the polyline through the centers of the hot pixels it goes through,
     * in order, keeping its winding contributions. Rounding each crossing on its own instead bends
     * the edges into new crossings with the edges close to them, which nearly coincident edges
     * never stop making; snap rounding bends those edges through the same centers, so that the
     * pieces only meet at their ends or coincide.
     *
     * The edges going through the pixels of the vertices are found with the crossings, among the
     * pairs of edges whose bounds overlap; the pixels of the crossings are then looked up for every
     * edge. Most edges go through none of them but the pixels of their own ends.
     */
    private fun snapRound(edges: EdgeStore): EdgeStore {
        val crossings = HotPixels(16)
        val vertexHits = VertexHits()
        findHotPixels(edges, crossings, vertexHits)
        crossings.build()
        val hitEdges = vertexHits.edge
        val hitOrder = sortIndices(vertexHits.size) { p, q -> hitEdges[p].compareTo(hitEdges[q]) }
        val result = EdgeStore(edges.size + crossings.size + vertexHits.size)
        val through = Centers()
        var hit = 0
        for (e in 0 until edges.size) {
            val x0 = edges.x0[e]
            val y0 = edges.y0[e]
            val x1 = edges.x1[e]
            val y1 = edges.y1[e]
            through.clear()
            while (hit < hitOrder.size && hitEdges[hitOrder[hit]] == e) {
                through.add(vertexHits.x[hitOrder[hit]], vertexHits.y[hitOrder[hit]])
                hit++
            }
            crossings.collectThrough(x0, y0, x1, y1, through)
            // The edge goes left to right (x0 <= x1) and towards larger or smaller y: monotone in x
            // and y, it goes through the pixels column by column, rows in its direction.
            val rowStep = if (y1 < y0) -1 else 1
            val order =
                sortIndices(through.size) { p, q ->
                    val c = through.x[p].compareTo(through.x[q])
                    if (c != 0) c else rowStep * through.y[p].compareTo(through.y[q])
                }
            var px = x0
            var py = y0
            for (k in order) {
                val cx = through.x[k]
                val cy = through.y[k]
                // The pixels of the ends are the ends, and a pixel may hold a vertex and a crossing.
                if ((cx == px && cy == py) || (cx == x1 && cy == y1)) continue
                result.add(px, py, cx, cy, edges.wa[e], edges.wb[e])
                px = cx
                py = cy
            }
            result.add(px, py, x1, y1, edges.wa[e], edges.wb[e])
        }
        return result
    }

    /**
     * Sweeps the pairs of edges whose bounds overlap, adding the pixels of their crossings to
     * [crossings] and the edges going through the pixel of a vertex of the other one to
     * [vertexHits]. These are all the edges going through the pixels of vertices: the ends of the
     * edges are grid points, so an edge going through the pixel of a vertex has it in its bounds.
     */
    private fun findHotPixels(edges: EdgeStore, crossings: HotPixels, vertexHits: VertexHits) {
        val n = edges.size
        val x0 = edges.x0
        val y0 = edges.y0
        val x1 = edges.x1
        val y1 = edges.y1
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
                if (max(y0[j], y1[j]) < iMinY || min(y0[j], y1[j]) > iMaxY) continue
                addCrossing(edges, i, j, crossings)
                addVertexHit(edges, i, x0[j], y0[j], vertexHits)
                addVertexHit(edges, i, x1[j], y1[j], vertexHits)
                addVertexHit(edges, j, x0[i], y0[i], vertexHits)
                addVertexHit(edges, j, x1[i], y1[i], vertexHits)
            }
            active[activeCount++] = i
        }
    }

    /** Records edge [e] going through the pixel of the vertex ([vx], [vy]), unless it's its end. */
    private fun addVertexHit(edges: EdgeStore, e: Int, vx: Double, vy: Double, hits: VertexHits) {
        val ax = edges.x0[e]
        val ay = edges.y0[e]
        val bx = edges.x1[e]
        val by = edges.y1[e]
        if ((vx == ax && vy == ay) || (vx == bx && vy == by)) return
        if (goesThroughPixel(ax, ay, bx, by, vx, vy)) hits.add(e, vx, vy)
    }

    /**
     * Makes the pixels of the point where edges [i] and [j] cross hot, if they cross in the inside
     * of both: a vertex lying on the other edge is in a hot pixel already.
     */
    private fun addCrossing(edges: EdgeStore, i: Int, j: Int, hot: HotPixels) {
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
        if (!((d1 > 0.0 && d2 < 0.0) || (d1 < 0.0 && d2 > 0.0))) return
        val d3 = orient(cx, cy, dx, dy, ax, ay)
        val d4 = orient(cx, cy, dx, dy, bx, by)
        if (!((d3 > 0.0 && d4 < 0.0) || (d3 < 0.0 && d4 > 0.0))) return
        val t = d3 / (d3 - d4)
        hot.addAround(ax + t * (bx - ax), ay + t * (by - ay))
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
                result.add(x0[e], y0[e], x1[e], y1[e], wa, wb)
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

    /**
     * Returns true if edge [e], inserted at its start point, lies above the active edge [f]. Unless
     * they start at the same point, the start of [e] isn't on [f]: after snap rounding, no vertex
     * lies inside an edge.
     */
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
        return orient(fx0, fy0, fx1, fy1, ex, ey) > 0.0
    }

    // ---------------------------------------------------------------------------------------
    // Output
    // ---------------------------------------------------------------------------------------

    /**
     * Chains the boundary edges into contours. Returns null if they don't chain: the edges don't
     * cross, so the boundary enters every vertex as many times as it leaves it, and a walk can only
     * end where it started.
     */
    private fun buildResult(boundary: DirectedEdges): PathData? {
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
            // Every edge is used once at most: a walk longer than all of them can't close.
            var steps = 0
            while (tx[current] != startX || ty[current] != startY) {
                if (++steps > m) return null
                val vx = tx[current]
                val vy = ty[current]
                contour.add(vx, vy)
                val next = pickNextEdge(boundary, order, used, current, vx, vy)
                if (next < 0) return null
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

/**
 * Whether the segment from (ax, ay) to (bx, by), between grid points, goes through the pixel of
 * the grid point (cx, cy): the half-open unit square [cx - 0.5, cx + 0.5) × [cy - 0.5, cy + 0.5).
 * Exact: the values are integers.
 */
private fun goesThroughPixel(
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
    cx: Double,
    cy: Double,
): Boolean {
    if (!meetsBox(ax, ay, bx, by, cx - 0.5, cy - 0.5, cx + 0.5, cy + 0.5)) return false
    // The line goes through the inside, or only touches a corner (meetsBox's distances are equal):
    // the pixel holds its (min x, min y) corner, which the line goes through if
    // orient(a, b, corner) = orient(a, b, c) + ((by - ay) - (bx - ax)) / 2 = 0.
    val dx = bx - ax
    val dy = by - ay
    val twiceDistance = 2 * orient(ax, ay, bx, by, cx, cy)
    if (abs(twiceDistance) < abs(dx) + abs(dy)) return true
    val cornerX = cx - 0.5
    val cornerY = cy - 0.5
    return twiceDistance == dx - dy &&
        cornerX >= min(ax, bx) && cornerX <= max(ax, bx) &&
        cornerY >= min(ay, by) && cornerY <= max(ay, by)
}

/**
 * Whether the segment from (ax, ay) to (bx, by) meets the closed box (left, top, right, bottom)
 * (separating axes: x, y and the normal of the segment). Exact for grid points and boxes with
 * half-integer sides.
 */
private fun meetsBox(
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
    left: Double,
    top: Double,
    right: Double,
    bottom: Double,
): Boolean {
    if (max(ax, bx) < left || min(ax, bx) > right || max(ay, by) < top || min(ay, by) > bottom) {
        return false
    }
    // The distance of the center from the line, against the reach of the box along its normal.
    val twiceDistance = 2 * orient(ax, ay, bx, by, (left + right) / 2, (top + bottom) / 2)
    return abs(twiceDistance) <= abs(bx - ax) * (bottom - top) + abs(by - ay) * (right - left)
}

/**
 * The hot pixels of the crossings of snap rounding (see [BooleanOperation.snapRound]), by their
 * centers: grid points. Once [built][build], a 2D tree: the pixel splitting a range `[lo, hi)` is
 * its middle one, the pixels before it have no larger x (or y, alternately with the depth), the
 * ones after it no smaller.
 */
private class HotPixels(capacity: Int) {
    var x = DoubleArray(max(capacity, 8))
        private set

    var y = DoubleArray(max(capacity, 8))
        private set

    var size = 0
        private set

    fun add(px: Double, py: Double) {
        if (size == x.size) {
            x = x.copyOf(2 * size)
            y = y.copyOf(2 * size)
        }
        x[size] = px
        y[size] = py
        size++
    }

    // The bounds of the centers of the pixels, once built.
    private var minX = 0.0
    private var minY = 0.0
    private var maxX = 0.0
    private var maxY = 0.0

    /** Adds the pixels whose squares are within [CrossingError] of the point ([px], [py]). */
    fun addAround(px: Double, py: Double) {
        val firstX = ceil(px - 0.5 - CrossingError).toInt()
        val lastX = floor(px + 0.5 + CrossingError).toInt()
        val firstY = ceil(py - 0.5 - CrossingError).toInt()
        val lastY = floor(py + 0.5 + CrossingError).toInt()
        for (gx in firstX..lastX) {
            for (gy in firstY..lastY) add(gx.toDouble(), gy.toDouble())
        }
    }

    /** Removes the duplicates and arranges the pixels as a 2D tree. */
    fun build() {
        if (size == 0) return
        sort(0, size)
        var unique = 0
        for (i in 0 until size) {
            if (unique > 0 && x[i] == x[unique - 1] && y[i] == y[unique - 1]) continue
            x[unique] = x[i]
            y[unique] = y[i]
            unique++
        }
        size = unique
        // Sorted by x: the first and last pixels are the leftmost and rightmost ones.
        minX = x[0]
        maxX = x[size - 1]
        minY = y[0]
        maxY = y[0]
        for (i in 1 until size) {
            minY = min(minY, y[i])
            maxY = max(maxY, y[i])
        }
        arrange(0, size, byX = true)
    }

    /** Sorts `[lo, hi)` by x, then y (quicksort). */
    private fun sort(lo: Int, hi: Int) {
        var left = lo
        var right = hi - 1
        while (left < right) {
            val mid = (left + right) ushr 1
            val px = x[mid]
            val py = y[mid]
            var i = left
            var j = right
            while (i <= j) {
                while (x[i] < px || (x[i] == px && y[i] < py)) i++
                while (x[j] > px || (x[j] == px && y[j] > py)) j--
                if (i <= j) {
                    swap(i, j)
                    i++
                    j--
                }
            }
            // The smaller part recursively, the larger one in the loop: a shallow stack.
            if (j - left < right - i) {
                sort(left, j + 1)
                left = i
            } else {
                sort(i, right + 1)
                right = j
            }
        }
    }

    private fun arrange(lo: Int, hi: Int, byX: Boolean) {
        if (hi - lo <= 1) return
        val mid = (lo + hi) ushr 1
        select(lo, hi, mid, if (byX) x else y)
        arrange(lo, mid, !byX)
        arrange(mid + 1, hi, !byX)
    }

    /** Partitions `[lo, hi)` around its [k]-th smallest [key] (quickselect). */
    private fun select(lo: Int, hi: Int, k: Int, key: DoubleArray) {
        var left = lo
        var right = hi - 1
        while (left < right) {
            val pivot = key[(left + right) ushr 1]
            var i = left
            var j = right
            while (i <= j) {
                while (key[i] < pivot) i++
                while (key[j] > pivot) j--
                if (i <= j) {
                    swap(i, j)
                    i++
                    j--
                }
            }
            if (k <= j) right = j else if (k >= i) left = i else return
        }
    }

    private fun swap(i: Int, j: Int) {
        val tx = x[i]
        x[i] = x[j]
        x[j] = tx
        val ty = y[i]
        y[i] = y[j]
        y[j] = ty
    }

    /** Adds the centers of the pixels the segment from (ax, ay) to (bx, by) goes through to [out]. */
    fun collectThrough(ax: Double, ay: Double, bx: Double, by: Double, out: Centers) {
        collect(0, size, true, minX, minY, maxX, maxY, ax, ay, bx, by, out)
    }

    /**
     * Collects the pixels of `[lo, hi)`, whose centers are within (left, top, right, bottom), that
     * the segment goes through.
     */
    private fun collect(
        lo: Int,
        hi: Int,
        byX: Boolean,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        out: Centers,
    ) {
        if (lo >= hi) return
        // The pixels around the centers of the range.
        if (!meetsBox(ax, ay, bx, by, left - 0.5, top - 0.5, right + 0.5, bottom + 0.5)) return
        val mid = (lo + hi) ushr 1
        if (goesThroughPixel(ax, ay, bx, by, x[mid], y[mid])) out.add(x[mid], y[mid])
        if (byX) {
            collect(lo, mid, false, left, top, x[mid], bottom, ax, ay, bx, by, out)
            collect(mid + 1, hi, false, x[mid], top, right, bottom, ax, ay, bx, by, out)
        } else {
            collect(lo, mid, true, left, top, right, y[mid], ax, ay, bx, by, out)
            collect(mid + 1, hi, true, left, y[mid], right, bottom, ax, ay, bx, by, out)
        }
    }
}

/** The centers of the hot pixels an edge goes through (see [BooleanOperation.snapRound]). */
private class Centers {
    var x = DoubleArray(16)
        private set

    var y = DoubleArray(16)
        private set

    var size = 0
        private set

    fun add(px: Double, py: Double) {
        if (size == x.size) {
            x = x.copyOf(2 * size)
            y = y.copyOf(2 * size)
        }
        x[size] = px
        y[size] = py
        size++
    }

    fun clear() {
        size = 0
    }
}

/** Edges going through the pixels of vertices: the edge and the vertex of each. */
private class VertexHits {
    var edge = IntArray(8)
        private set

    var x = DoubleArray(8)
        private set

    var y = DoubleArray(8)
        private set

    var size = 0
        private set

    fun add(e: Int, vx: Double, vy: Double) {
        if (size == edge.size) {
            edge = edge.copyOf(2 * size)
            x = x.copyOf(2 * size)
            y = y.copyOf(2 * size)
        }
        edge[size] = e
        x[size] = vx
        y[size] = vy
        size++
    }
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
    var size = 0

    fun add(
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        windA: Int,
        windB: Int,
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
    if (n == 0) return NoIndices
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

private val NoIndices = IntArray(0)
