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

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint

// The image that the tests of shaders and images tile, and a color matrix they draw it through.

/** The colors of the quarters of [tile], row by row. */
internal val TileColors = listOf(Color(0xFF102030), Color(0xFFE0F0C0), Color(0xFF7F3FBF), Color(0xFF40C080))

/** A 4 x 4 image of the [TileColors], a quarter of 2 x 2 pixels each. */
internal fun tile(): ImageBitmap = ImageBitmap(4, 4).also { image ->
    val canvas = Canvas(image)
    TileColors.forEachIndexed { i, color ->
        canvas.drawRect((i % 2) * 2f, (i / 2) * 2f, (i % 2) * 2f + 2f, (i / 2) * 2f + 2f, Paint().also { it.color = color })
    }
}

/** The color of [tile] at its pixel ([x], [y]). */
internal fun tileColorAt(x: Int, y: Int): Color = TileColors[x / 2 + 2 * (y / 2)]

/** A color matrix, in Compose's layout, that inverts the colors and keeps the alpha. */
internal val InvertingMatrix =
    floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)
