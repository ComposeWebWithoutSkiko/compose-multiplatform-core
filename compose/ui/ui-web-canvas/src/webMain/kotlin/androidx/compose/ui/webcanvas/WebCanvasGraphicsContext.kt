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

package androidx.compose.ui.webcanvas

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.platform.PlatformGraphicsContext
import androidx.compose.ui.graphics.platform.PlatformGraphicsLayer
import androidx.compose.ui.webcanvas.graphics.LightingInfo
import androidx.compose.ui.webcanvas.graphics.WebGraphicsLayer

/**
 * The graphics context of the web implementation: it creates the layers of a Compose hierarchy,
 * which share the light source of their elevation shadows.
 */
@InternalComposeUiApi
public class WebCanvasGraphicsContext : PlatformGraphicsContext() {
    private val lighting = LightingInfo()

    override fun setLightingInfo(
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        radius: Float,
        ambientShadowAlpha: Float,
        spotShadowAlpha: Float,
    ) {
        super.setLightingInfo(centerX, centerY, centerZ, radius, ambientShadowAlpha, spotShadowAlpha)
        lighting.centerX = centerX
        lighting.centerY = centerY
        lighting.centerZ = centerZ
        lighting.radius = radius
        lighting.ambientShadowAlpha = ambientShadowAlpha
        lighting.spotShadowAlpha = spotShadowAlpha
    }

    override fun createPlatformGraphicsLayer(): PlatformGraphicsLayer = WebGraphicsLayer(lighting)
}
