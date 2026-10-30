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

import androidx.compose.ui.webcanvas.path.PathData
import kotlin.math.max
import kotlin.math.min

/** A shape casting shadows (see [ElevationShadows]), in local coordinates. */
internal sealed class ShadowShape(
    /** The outline of the shape. */
    val path: PathData,
    /**
     * What identifies the shape up to a translation, if anything: a shadow of it drawn again is
     * then drawn from an image (see [ShadowSprites]).
     */
    val key: Any?,
    /**
     * The shadows that what casts them, a layer whose shape this is, was asked for last, if it
     * has a [key] (see [ShadowSprites.spriteFor]).
     */
    val spriteRequests: ShadowSprites.Requests?,
) {
    /** The hash of [key], computed once. */
    val keyHash: Int = key.hashCode()

    /**
     * A rounded rectangle whose corners all have the same circular [radius] (a rectangle for 0),
     * whose shadows can be painted without blurring (see [ShadowPainter]).
     */
    class RoundRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val radius: Float,
        path: PathData,
        key: Any?,
        spriteRequests: ShadowSprites.Requests?,
    ) : ShadowShape(path, key, spriteRequests)

    /** Any other shape, whose shadows are blurred. */
    class Path(
        path: PathData,
        key: Any?,
        spriteRequests: ShadowSprites.Requests?,
    ) : ShadowShape(path, key, spriteRequests)
}

/** The bits of this value for a hash, the same for -0 and 0, which are equal. */
internal fun Float.hashBits(): Int = (this + 0f).toBits()

/**
 * Identifies a path up to a translation, as the key of its shape (see [ShadowShape.key]): equal to
 * the keys of the paths with the same fill type, verbs and conic weights, whose points are at the
 * same places relative to the top-left corner of their bounds. The paths are compared in place.
 */
internal class PathShapeKey(private val path: PathData) {
    private val hash: Int = run {
        var hash = path.fillType.hashCode()
        for (i in 0 until path.verbCount) hash = 31 * hash + path.verbs[i]
        for (i in 0 until path.conicWeightCount) hash = 31 * hash + path.conicWeights[i].hashBits()
        val left = path.left
        val top = path.top
        for (i in 0 until path.pointCount) {
            hash = 31 * hash + (path.points[2 * i] - left).hashBits()
            hash = 31 * hash + (path.points[2 * i + 1] - top).hashBits()
        }
        hash
    }

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PathShapeKey) return false
        val a = path
        val b = other.path
        if (a === b) return true
        if (
            hash != other.hash ||
                a.fillType != b.fillType ||
                a.verbCount != b.verbCount ||
                a.pointCount != b.pointCount ||
                a.conicWeightCount != b.conicWeightCount
        ) {
            return false
        }
        for (i in 0 until a.verbCount) if (a.verbs[i] != b.verbs[i]) return false
        for (i in 0 until a.conicWeightCount) if (a.conicWeights[i] != b.conicWeights[i]) return false
        val aLeft = a.left
        val aTop = a.top
        val bLeft = b.left
        val bTop = b.top
        for (i in 0 until a.pointCount) {
            if (
                a.points[2 * i] - aLeft != b.points[2 * i] - bLeft ||
                    a.points[2 * i + 1] - aTop != b.points[2 * i + 1] - bTop
            ) {
                return false
            }
        }
        return true
    }
}

/**
 * Draws the shadows of elevated layers (see [WebCanvas.drawShadow]), approximating Android's (and
 * Skia's `SkShadowUtils`) light model:
 * - an ambient shadow: a soft halo around the shape that grows with the elevation;
 * - a spot shadow: the shape projected away from a light above the window, blurred by the size of
 *   the light.
 */
internal object ElevationShadows {
    // Parameters of SkShadowUtils.
    private const val AmbientHeightFactor = 1f / 128f
    private const val AmbientGeomFactor = 64f
    private const val MaxAmbientRadius = 300 * AmbientHeightFactor * AmbientGeomFactor

    /**
     * How much of a Skia shadow's penumbra a Gaussian deviation corresponds to: Skia fades the
     * shadow linearly-ish over the blur radius, which looks like a Gaussian of about a third of it.
     */
    private const val PenumbraToSigma = 0.36f

    /**
     * Draws the shadows of [shape], in the ARGB colors [ambientArgb] and [spotArgb], their alpha
     * multiplied by [alpha].
     */
    fun draw(
        canvas: WebCanvas,
        shape: ShadowShape,
        elevation: Float,
        ambientArgb: Int,
        spotArgb: Int,
        alpha: Float,
        lighting: LightingInfo,
    ) {
        val path = shape.path
        if (path.isEmpty) return
        val matrix = canvas.currentMatrix
        val scale = matrix.averageScale

        // Ambient shadow: centered under the shape.
        val ambientRadius = min(elevation * AmbientHeightFactor * AmbientGeomFactor, MaxAmbientRadius)
        val ambient = ambientArgb.withAlphaMultiplied(lighting.ambientShadowAlpha * alpha)
        if (ambient ushr 24 != 0 && ambientRadius > 0f) {
            canvas.drawShadow(
                shape,
                ambient,
                blurSigma = ambientRadius * PenumbraToSigma * scale,
                offsetX = 0f,
                offsetY = 0f,
            )
        }

        // Spot shadow: projected from the light onto the plane below the shape.
        val spot = spotArgb.withAlphaMultiplied(lighting.spotShadowAlpha * alpha)
        if (spot ushr 24 != 0) {
            val localCenterX = (path.left + path.right) / 2
            val localCenterY = (path.top + path.bottom) / 2
            val centerX = matrix.mapX(localCenterX, localCenterY)
            val centerY = matrix.mapY(localCenterX, localCenterY)
            val z = elevation * scale
            val zRatio = divideAndPin(z, lighting.centerZ - z, 0f, 0.95f)
            val blurRadius = lighting.radius * zRatio
            val offsetX = zRatio * (centerX - lighting.centerX)
            val offsetY = zRatio * (centerY - lighting.centerY)
            val shadowScale = divideAndPin(lighting.centerZ, lighting.centerZ - z, 1f, 1.95f)
            if (shadowScale != 1f) {
                canvas.save()
                canvas.translate(localCenterX, localCenterY)
                canvas.scale(shadowScale, shadowScale)
                canvas.translate(-localCenterX, -localCenterY)
            }
            canvas.drawShadow(
                shape,
                spot,
                blurSigma = blurRadius * PenumbraToSigma,
                offsetX = offsetX,
                offsetY = offsetY,
            )
            if (shadowScale != 1f) {
                canvas.restore()
            }
        }
    }

    private fun divideAndPin(numerator: Float, denominator: Float, min: Float, max: Float): Float {
        if (denominator <= 0f) return max
        return max(min, min(max, numerator / denominator))
    }
}
