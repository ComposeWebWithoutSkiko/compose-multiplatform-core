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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.webcanvas.path.PathData
import androidx.compose.ui.webcanvas.path.toPath2D
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the shadows of elevated layers, approximating Android's (and Skia's `SkShadowUtils`)
 * light model with blurred Canvas 2D shadows:
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

    fun draw(
        canvas: WebCanvas,
        shape: PathData,
        elevation: Float,
        ambientColor: Color,
        spotColor: Color,
        alpha: Float,
        lighting: LightingInfo,
    ) {
        if (shape.isEmpty) return
        val path2D = shape.toPath2D()
        val matrix = canvas.currentMatrix
        val scale = matrix.averageScale

        // Ambient shadow: centered under the shape.
        val ambientRadius = min(elevation * AmbientHeightFactor * AmbientGeomFactor, MaxAmbientRadius)
        val ambientAlpha = ambientColor.alpha * lighting.ambientShadowAlpha * alpha
        if (ambientAlpha > 0f && ambientRadius > 0f) {
            canvas.drawShapeShadow(
                path2D,
                ambientColor.copy(alpha = ambientAlpha.coerceAtMost(1f)).toArgb(),
                blurSigma = ambientRadius * PenumbraToSigma * scale,
                offsetX = 0f,
                offsetY = 0f,
            )
        }

        // Spot shadow: projected from the light onto the plane below the shape.
        val spotAlpha = spotColor.alpha * lighting.spotShadowAlpha * alpha
        if (spotAlpha > 0f) {
            val bounds = shape.bounds
            val centerX = matrix.mapX(bounds.center.x, bounds.center.y)
            val centerY = matrix.mapY(bounds.center.x, bounds.center.y)
            val z = elevation * scale
            val zRatio = divideAndPin(z, lighting.centerZ - z, 0f, 0.95f)
            val blurRadius = lighting.radius * zRatio
            val offsetX = zRatio * (centerX - lighting.centerX)
            val offsetY = zRatio * (centerY - lighting.centerY)
            val shadowScale = divideAndPin(lighting.centerZ, lighting.centerZ - z, 1f, 1.95f)
            if (shadowScale != 1f) {
                canvas.save()
                val localCenterX = bounds.center.x
                val localCenterY = bounds.center.y
                canvas.translate(localCenterX, localCenterY)
                canvas.scale(shadowScale, shadowScale)
                canvas.translate(-localCenterX, -localCenterY)
            }
            canvas.drawShapeShadow(
                path2D,
                spotColor.copy(alpha = spotAlpha.coerceAtMost(1f)).toArgb(),
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
