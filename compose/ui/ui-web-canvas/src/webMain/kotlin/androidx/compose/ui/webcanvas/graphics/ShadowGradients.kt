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

package androidx.compose.ui.webcanvas.graphics

import androidx.collection.MutableIntObjectMap
import androidx.compose.ui.webcanvas.dom.CanvasGradient
import androidx.compose.ui.webcanvas.dom.CssColors
import androidx.compose.ui.webcanvas.dom.createCanvas
import androidx.compose.ui.webcanvas.dom.requireContext2D
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The gradients filling the shadows of rounded rectangles painted without blurring (see
 * [ShadowPainter]), in an opaque color: the profile across an edge blurred by a Gaussian of
 * deviation sigma, from [Spread] sigma inside the edge (offset 0) to [Spread] sigma outside
 * (offset 1). In these units, the profile is the same for every blur, so the gradients of a color
 * serve every shadow of that color. Gradients fill in any 2D context.
 */
internal class ShadowGradients private constructor(rgb: Int) {
    private val stopColors =
        Array(StopDistances.size) { i ->
            val alpha = when (i) {
                0 -> 255
                StopDistances.lastIndex -> 0
                else -> (blurredEdgeCoverage(StopDistances[i]) * 255).roundToInt()
            }
            CssColors.of((alpha shl 24) or (rgb and 0x00FFFFFF))
        }

    /** The profile along the x axis, from 0 to 1, placed by the transformation it's filled with. */
    val edge: CanvasGradient =
        gradientContext.createLinearGradient(0.0, 0.0, 1.0, 0.0).also { addStops(it) }

    private val corners = arrayOfNulls<CanvasGradient>(CornerSteps)

    /**
     * The profile around a corner centered at the origin, from the radius [innerRatio] to 1,
     * placed by the transformation scaling it to the outer radius. The ratio is rounded to a step
     * of [CornerSteps], which puts the inner radius off by at most 1 / (2 [CornerSteps]) of the
     * outer one, so that the gradients serve every corner.
     */
    fun corner(innerRatio: Float): CanvasGradient {
        val step = (innerRatio * CornerSteps).roundToInt().coerceIn(0, CornerSteps - 1)
        return corners[step]
            ?: gradientContext
                .createRadialGradient(0.0, 0.0, step.toDouble() / CornerSteps, 0.0, 0.0, 1.0)
                .also {
                    addStops(it)
                    corners[step] = it
                }
    }

    private fun addStops(gradient: CanvasGradient) {
        for (i in StopDistances.indices) {
            gradient.addColorStop((StopDistances[i] / Spread + 1.0) / 2, stopColors[i])
        }
    }

    companion object {
        /** How far the profile spreads on both sides of an edge, in deviations of the blur. */
        const val Spread = 2.25f

        /**
         * The stops of the profile, at these distances (in deviations) outside the edge: placed so
         * that the profile, linear between them, stays within 1.3% of a blurred edge, with the
         * ends opaque and transparent, so that it meets the inside and the outside without a step.
         * Every stop makes the browser fill gradients more slowly.
         */
        private val StopDistances = floatArrayOf(-Spread, -1.35f, -0.75f, 0.75f, 1.35f, Spread)

        /** The steps of the ratio of the radii of the corners (see [corner]). */
        private const val CornerSteps = 1024

        /** Colors of shadows are few (black, usually); more than this many are forgotten. */
        private const val MaxColors = 32

        private val gradientContext by lazy { requireContext2D(createCanvas(1, 1)) }
        private val byColor = MutableIntObjectMap<ShadowGradients>()

        /** The gradients of the opaque color [rgb]. */
        fun of(rgb: Int): ShadowGradients {
            byColor[rgb]?.let { return it }
            if (byColor.size >= MaxColors) byColor.clear()
            return ShadowGradients(rgb).also { byColor[rgb] = it }
        }
    }
}

/**
 * The alpha of an opaque straight edge blurred by a Gaussian, at [distance] deviations outside
 * of it (negative inside): `erfc(distance / sqrt(2)) / 2`, with the approximation of erfc of
 * Abramowitz and Stegun (7.1.26), within 1.5e-7.
 */
internal fun blurredEdgeCoverage(distance: Float): Float {
    val x = abs(distance) / sqrt(2f)
    val t = 1f / (1f + 0.3275911f * x)
    val polynomial =
        t * (0.254829592f + t * (-0.284496736f + t * (1.421413741f + t * (-1.453152027f + t * 1.061405429f))))
    val erfc = polynomial * exp(-x * x)
    return if (distance >= 0f) erfc / 2 else 1f - erfc / 2
}
