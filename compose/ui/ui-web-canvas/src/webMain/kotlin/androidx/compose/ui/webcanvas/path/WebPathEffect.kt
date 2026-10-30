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

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StampedPathEffectStyle
import kotlin.math.sqrt

/** Creates the web implementation of [PathEffect.dashPathEffect]. */
internal fun createDashPathEffect(intervals: FloatArray, phase: Float): PathEffect =
    WebPathEffect.Dash(intervals.copyOf(), phase)

/** Creates the web implementation of [PathEffect.cornerPathEffect]. */
internal fun createCornerPathEffect(radius: Float): PathEffect = WebPathEffect.Corner(radius)

/** Creates the web implementation of [PathEffect.chainPathEffect]. */
internal fun createChainPathEffect(outer: PathEffect, inner: PathEffect): PathEffect =
    WebPathEffect.Chain(outer, inner)

/** Creates the web implementation of [PathEffect.stampedPathEffect]. */
internal fun createStampedPathEffect(
    shape: Path,
    advance: Float,
    phase: Float,
    style: StampedPathEffectStyle,
): PathEffect = WebPathEffect.Stamped(shape, advance, phase, style)

/**
 * Mutable drawing style threaded through path effects (the subset of Skia's `SkStrokeRec` the
 * effects care about). A [WebPathEffect.Stamped] effect switches it to fill.
 */
internal class PathEffectStrokeRec(var isStroke: Boolean)

/**
 * Path effects of the web canvas backend, ports of Skia's `SkDashPathEffect`, `SkCornerPathEffect`,
 * `SkPath1DPathEffect` and `SkComposePathEffect`.
 *
 * The renderer applies an effect with [apply] and then draws the result with the style returned by
 * [resultIsStroke] (stamped effects turn strokes into fills, like in Skia).
 */
internal sealed class WebPathEffect : PathEffect {
    /**
     * True if the parameters make the effect a no-op, i.e. the cases where Skia's factories return
     * a null effect (invalid intervals, non-positive radius or advance, ...).
     */
    abstract val isNoOp: Boolean

    /**
     * Skia's `SkPathEffect::filterPath`: returns the transformed path, or null if the effect does
     * not apply (in which case the source path is drawn unmodified). May switch [rec] to fill.
     */
    abstract fun filterPath(src: PathData, rec: PathEffectStrokeRec): PathData?

    // The last path the effect was applied to and its result (see apply).
    private var lastSource: PathData? = null
    private var lastIsStroke = false
    private var lastResult: PathData? = null

    /**
     * Applies the effect to [src] drawn with a stroke (if [isStroke]) or a fill. Returns [src]
     * itself when the effect doesn't apply.
     *
     * The result only depends on the geometry of [src] and [isStroke] (the effects don't change),
     * so the last one is kept: a path drawn again, in every frame, or rebuilt the same (shapes are
     * turned into new paths for their effects) gets it, with its `Path2D`, without applying the
     * effect again. [src] is kept too: it must be a [snapshot][WebPath.snapshot].
     */
    fun apply(src: PathData, isStroke: Boolean): PathData {
        if (isNoOp) return src
        val source = lastSource
        val result = lastResult
        if (
            source != null &&
                result != null &&
                isStroke == lastIsStroke &&
                (source === src || source.hasSameGeometry(src))
        ) {
            return result
        }
        return (filterPath(src, PathEffectStrokeRec(isStroke)) ?: src).also {
            lastSource = src
            lastIsStroke = isStroke
            lastResult = it
        }
    }

    /**
     * Returns whether the path returned by [apply] must be stroked (true) or filled (false) for a
     * paint that strokes if [isStroke].
     */
    open fun resultIsStroke(isStroke: Boolean): Boolean = isStroke

    /**
     * Dashes stroked paths (Skia's `SkDashPathEffect`). Fills are not affected.
     *
     * For the common case of a plain dashed stroke the renderer may use the native Canvas2D
     * `setLineDash([intervals])`/`lineDashOffset = phase` when [isValid] instead of [apply]. Note
     * that, unlike Canvas2D, Skia joins the last dash of a closed contour with its first dash.
     */
    class Dash(
        /** The on/off intervals: an array this effect keeps. */
        val intervals: FloatArray,
        phase: Float,
    ) : WebPathEffect() {
        /** These dashes, [factor] times as long. */
        fun scaled(factor: Float): Dash =
            if (isValid) {
                Dash(FloatArray(intervals.size) { intervals[it] * factor }, adjustedPhase * factor)
            } else {
                this
            }

        /**
         * True if the intervals are usable: an even count >= 2, no negative entry, a positive and
         * finite sum and a finite phase.
         */
        val isValid: Boolean

        /** Sum of all [intervals]. */
        private val intervalLength: Float

        /** The phase normalized into `[0, intervalLength)` like Skia (negative phases flipped). */
        val adjustedPhase: Float

        private val initialDashLength: Float
        private val initialDashIndex: Int

        init {
            val count = this.intervals.size
            var length = 0f
            var valid = count >= 2 && count % 2 == 0
            if (valid) {
                for (interval in this.intervals) {
                    if (interval < 0f) {
                        valid = false
                        break
                    }
                    length += interval
                }
            }
            valid = valid && length > 0f && length.isFinite() && phase.isFinite()
            isValid = valid
            intervalLength = if (valid) length else 0f
            if (valid) {
                // SkDashPath::CalcDashParameters
                var p = phase
                if (p < 0f) {
                    p = -p
                    if (p > length) p %= length
                    p = length - p
                    // Due to finite precision, it's possible that phase == len.
                    if (p == length) p = 0f
                } else if (p >= length) {
                    p %= length
                }
                adjustedPhase = p
                // find_first_interval
                var index = 0
                var dashLength = this.intervals[0]
                var found = false
                for (i in 0 until count) {
                    val gap = this.intervals[i]
                    if (p > gap || (p == gap && gap != 0f)) {
                        p -= gap
                    } else {
                        index = i
                        dashLength = gap - p
                        found = true
                        break
                    }
                }
                if (!found) {
                    // Rounding made the phase look larger than the interval length.
                    index = 0
                    dashLength = this.intervals[0]
                }
                initialDashIndex = index
                initialDashLength = dashLength
            } else {
                adjustedPhase = 0f
                initialDashIndex = 0
                initialDashLength = -1f
            }
        }

        override val isNoOp: Boolean
            get() = !isValid

        override fun filterPath(src: PathData, rec: PathEffectStrokeRec): PathData? {
            // We do nothing if the source wants to be filled.
            if (!isValid || !rec.isStroke) return null
            val count = intervals.size
            val dst = WebPath()
            val iterator = ContourMeasureIter(src, forceClosed = false)
            var contour = iterator.next()
            var dashCount = 0.0
            do {
                val measure = contour
                val isClosed = measure?.isClosed ?: false
                val length = measure?.length ?: 0f
                var skipFirstSegment = isClosed
                var addedSegment = false
                var index = initialDashIndex

                // Since the path length / dash length ratio may be arbitrarily large, give up
                // dashing beyond a certain threshold to bound the memory used.
                dashCount += length.toDouble() * (count shr 1) / intervalLength
                if (dashCount > MaxDashCount) return null

                // Use double precision to avoid looping indefinitely due to single precision
                // rounding (for extreme path_length / dash_length ratios).
                var distance = 0.0
                var dashLength = initialDashLength.toDouble()
                while (distance < length) {
                    addedSegment = false
                    if (index % 2 == 0 && !skipFirstSegment) {
                        addedSegment = true
                        measure!!.getSegment(
                            distance.toFloat(),
                            (distance + dashLength).toFloat(),
                            dst,
                            startWithMoveTo = true,
                        )
                    }
                    distance += dashLength
                    // Clear this so we only respect it the first time around.
                    skipFirstSegment = false
                    // Wrap around our intervals array if necessary.
                    index += 1
                    if (index == count) index = 0
                    dashLength = intervals[index].toDouble()
                }

                // Extend if we ended on a segment and we need to join up with the (skipped)
                // initial segment.
                if (isClosed && initialDashIndex % 2 == 0 && initialDashLength >= 0f) {
                    measure.getSegment(0f, initialDashLength, dst, !addedSegment)
                }
                contour = iterator.next()
            } while (contour != null)
            return dst.snapshot()
        }

        private companion object {
            /** Skia's `kMaxDashCount`. */
            const val MaxDashCount = 1000000.0
        }
    }

    /**
     * Replaces the corners between consecutive line segments with quadratic curves of the given
     * [radius] (Skia's `SkCornerPathEffect`). Curves are kept as they are.
     */
    class Corner(private val radius: Float) : WebPathEffect() {
        override val isNoOp: Boolean
            get() = !(radius.isFinite() && radius > 0f)

        override fun filterPath(src: PathData, rec: PathEffectStrokeRec): PathData? {
            if (isNoOp || src.isEmpty) return null
            val dst = WebPath()
            val iter = SkiaPathIter(src)
            val step = FloatArray(2)
            var prevVerb = SkiaPathIter.Done
            var moveToX = 0f
            var moveToY = 0f
            var lastCornerX = 0f
            var lastCornerY = 0f
            var firstStepX = 0f
            var firstStepY = 0f
            var prevIsValid = true
            while (true) {
                val verb = iter.next()
                when (verb) {
                    PathVerb.Move.toInt() -> {
                        // Close out the previous (open) contour.
                        if (prevVerb == PathVerb.Line.toInt()) {
                            dst.lineTo(lastCornerX, lastCornerY)
                        }
                        if (iter.isClosedContour()) {
                            moveToX = iter.x(0)
                            moveToY = iter.y(0)
                            prevIsValid = false
                        } else {
                            dst.moveTo(iter.x(0), iter.y(0))
                            prevIsValid = true
                        }
                    }
                    PathVerb.Line.toInt() -> {
                        val x0 = iter.x(0)
                        val y0 = iter.y(0)
                        val x1 = iter.x(1)
                        val y1 = iter.y(1)
                        val drawSegment = computeStep(x0, y0, x1, y1, radius, step)
                        // Previous corner.
                        if (!prevIsValid) {
                            dst.moveTo(moveToX + step[0], moveToY + step[1])
                            prevIsValid = true
                        } else {
                            dst.quadraticTo(x0, y0, x0 + step[0], y0 + step[1])
                        }
                        if (drawSegment) {
                            dst.lineTo(x1 - step[0], y1 - step[1])
                        }
                        lastCornerX = x1
                        lastCornerY = y1
                        prevIsValid = true
                    }
                    PathVerb.Quad.toInt() -> {
                        // Just replicate the curve for now.
                        if (!prevIsValid) {
                            dst.moveTo(iter.x(0), iter.y(0))
                            prevIsValid = true
                        }
                        dst.quadraticTo(iter.x(1), iter.y(1), iter.x(2), iter.y(2))
                        lastCornerX = iter.x(2)
                        lastCornerY = iter.y(2)
                        firstStepX = 0f
                        firstStepY = 0f
                    }
                    PathVerb.Conic.toInt() -> {
                        if (!prevIsValid) {
                            dst.moveTo(iter.x(0), iter.y(0))
                            prevIsValid = true
                        }
                        dst.conicTo(iter.x(1), iter.y(1), iter.x(2), iter.y(2), iter.conicWeight)
                        lastCornerX = iter.x(2)
                        lastCornerY = iter.y(2)
                        firstStepX = 0f
                        firstStepY = 0f
                    }
                    PathVerb.Cubic.toInt() -> {
                        if (!prevIsValid) {
                            dst.moveTo(iter.x(0), iter.y(0))
                            prevIsValid = true
                        }
                        dst.cubicTo(
                            iter.x(1),
                            iter.y(1),
                            iter.x(2),
                            iter.y(2),
                            iter.x(3),
                            iter.y(3),
                        )
                        lastCornerX = iter.x(3)
                        lastCornerY = iter.y(3)
                        firstStepX = 0f
                        firstStepY = 0f
                    }
                    PathVerb.Close.toInt() -> {
                        if (firstStepX != 0f || firstStepY != 0f) {
                            dst.quadraticTo(
                                lastCornerX,
                                lastCornerY,
                                lastCornerX + firstStepX,
                                lastCornerY + firstStepY,
                            )
                        }
                        dst.close()
                        prevIsValid = false
                    }
                    else -> {
                        if (prevIsValid) {
                            dst.lineTo(lastCornerX, lastCornerY)
                        }
                        return dst.snapshot()
                    }
                }
                if (prevVerb == PathVerb.Move.toInt()) {
                    firstStepX = step[0]
                    firstStepY = step[1]
                }
                prevVerb = verb
            }
        }

        private fun computeStep(
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float,
            radius: Float,
            step: FloatArray,
        ): Boolean {
            val dx = bx - ax
            val dy = by - ay
            val dist = sqrt(dx * dx + dy * dy)
            return if (dist <= radius * 2f) {
                step[0] = dx * 0.5f
                step[1] = dy * 0.5f
                false
            } else {
                step[0] = dx * (radius / dist)
                step[1] = dy * (radius / dist)
                true
            }
        }
    }

    /**
     * Applies [inner] and then [outer] (Skia's `SkComposePathEffect`). Effects that are not
     * [WebPathEffect]s or that are no-ops are ignored, like null effects in
     * `SkPathEffect::MakeCompose`.
     */
    class Chain(outer: PathEffect, inner: PathEffect) : WebPathEffect() {
        private val outerEffect: WebPathEffect? =
            (outer as? WebPathEffect)?.takeUnless { it.isNoOp }
        private val innerEffect: WebPathEffect? =
            (inner as? WebPathEffect)?.takeUnless { it.isNoOp }

        override val isNoOp: Boolean
            get() = outerEffect == null && innerEffect == null

        override fun filterPath(src: PathData, rec: PathEffectStrokeRec): PathData? {
            val o = outerEffect
            val i = innerEffect
            if (o == null) return i?.filterPath(src, rec)
            if (i == null) return o.filterPath(src, rec)
            val intermediate = i.filterPath(src, rec) ?: src
            // If the outer effect does not apply, Skia draws the original source.
            return o.filterPath(intermediate, rec)
        }

        override fun resultIsStroke(isStroke: Boolean): Boolean {
            var result = isStroke
            innerEffect?.let { result = it.resultIsStroke(result) }
            outerEffect?.let { result = it.resultIsStroke(result) }
            return result
        }
    }

    /**
     * Stamps [shape] along the path every [advance] units (Skia's `SkPath1DPathEffect`). The result
     * is always filled; the stroke width is ignored.
     */
    class Stamped(
        shape: Path,
        private val advance: Float,
        phase: Float,
        private val style: StampedPathEffectStyle,
    ) : WebPathEffect() {
        /** The stamped shape, captured at creation like Skia does. */
        private val shape: PathData = shape.asWebPath().snapshot()

        private val initialOffset: Float

        override val isNoOp: Boolean =
            !(advance > 0f) || !advance.isFinite() || !phase.isFinite() || this.shape.isEmpty

        init {
            // Clean up the phase parameter, inverting it so that it becomes an offset along the
            // path (to match the interpretation in PostScript).
            var p = phase
            if (!isNoOp) {
                if (p < 0f) {
                    p = -p
                    if (p > advance) p %= advance
                } else {
                    if (p > advance) p %= advance
                    p = advance - p
                }
                // Now catch the edge case where phase == advance (within epsilon).
                if (p >= advance) p = 0f
            }
            initialOffset = p
        }

        override fun resultIsStroke(isStroke: Boolean): Boolean = if (isNoOp) isStroke else false

        override fun filterPath(src: PathData, rec: PathEffectStrokeRec): PathData? {
            if (isNoOp) return null
            rec.isStroke = false
            val dst = WebPath()
            val iterator = ContourMeasureIter(src, forceClosed = false)
            var contour = iterator.next()
            val pos = FloatArray(2)
            val tan = FloatArray(2)
            do {
                var governor = MaxReasonableIterations
                val length = contour?.length ?: 0f
                var distance = initialOffset
                while (distance < length && --governor >= 0) {
                    stamp(dst, distance, contour!!, pos, tan)
                    distance += advance
                }
                if (governor < 0) return null
                contour = iterator.next()
            } while (contour != null)
            return dst.snapshot()
        }

        private fun stamp(
            dst: WebPath,
            distance: Float,
            measure: ContourMeasure,
            pos: FloatArray,
            tan: FloatArray,
        ) {
            when (style) {
                StampedPathEffectStyle.Rotate -> {
                    if (measure.getPosTan(distance, pos, tan)) {
                        val cos = tan[0]
                        val sin = tan[1]
                        dst.addPath(shape, cos, -sin, sin, cos, pos[0], pos[1])
                    }
                }
                StampedPathEffectStyle.Morph -> morphPath(dst, measure, distance, pos, tan)
                else -> {
                    if (measure.getPosTan(distance, pos, null)) {
                        dst.addPath(shape, pos[0], pos[1])
                    }
                }
            }
        }

        /** Bends the shape along the contour (Skia's `morphpath`). */
        private fun morphPath(
            dst: WebPath,
            measure: ContourMeasure,
            distance: Float,
            pos: FloatArray,
            tan: FloatArray,
        ) {
            val iter = SkiaPathIter(shape)
            val out = FloatArray(6)
            fun morph(k: Int, x: Float, y: Float): Boolean =
                morphPoint(out, k, x, y, measure, distance, pos, tan)
            // Morphs the [count] points that follow the start of the segment (the end of the last).
            fun morphSegment(count: Int): Boolean {
                for (i in 1..count) if (!morph(i - 1, iter.x(i), iter.y(i))) return false
                return true
            }
            while (true) {
                when (iter.next()) {
                    PathVerb.Move.toInt() -> {
                        if (morph(0, iter.x(0), iter.y(0))) dst.moveTo(out[0], out[1])
                    }
                    PathVerb.Line.toInt() -> {
                        // Turn the line into a quad with its control point in the middle.
                        val midX = (iter.x(0) + iter.x(1)) * 0.5f
                        val midY = (iter.y(0) + iter.y(1)) * 0.5f
                        if (morph(0, midX, midY) && morph(1, iter.x(1), iter.y(1))) {
                            dst.quadraticTo(out[0], out[1], out[2], out[3])
                        }
                    }
                    PathVerb.Quad.toInt() -> {
                        if (morphSegment(2)) dst.quadraticTo(out[0], out[1], out[2], out[3])
                    }
                    PathVerb.Conic.toInt() -> {
                        if (morphSegment(2)) {
                            dst.conicTo(out[0], out[1], out[2], out[3], iter.conicWeight)
                        }
                    }
                    PathVerb.Cubic.toInt() -> {
                        if (morphSegment(3)) {
                            dst.cubicTo(out[0], out[1], out[2], out[3], out[4], out[5])
                        }
                    }
                    PathVerb.Close.toInt() -> dst.close()
                    else -> return
                }
            }
        }

        /**
         * Maps the point ([sx], [sy]) onto the contour, as point [k] of [dst]: x is a distance along
         * the contour (relative to [distance]) and y an offset along the normal.
         */
        private fun morphPoint(
            dst: FloatArray,
            k: Int,
            sx: Float,
            sy: Float,
            measure: ContourMeasure,
            distance: Float,
            pos: FloatArray,
            tan: FloatArray,
        ): Boolean {
            if (!measure.getPosTan(distance + sx, pos, tan)) return false
            // Rotate (0, sy) by the tangent, then translate to the position.
            dst[2 * k] = pos[0] - tan[1] * sy
            dst[2 * k + 1] = pos[1] + tan[0] * sy
            return true
        }

        private companion object {
            /** Skia's `MAX_REASONABLE_ITERATIONS`. */
            const val MaxReasonableIterations = 100000
        }
    }
}
