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

package androidx.compose.foundation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap

/**
 * Backend-agnostic read access to the pixels of an [ImageBitmap], mirroring the accessors of
 * `org.jetbrains.skia.Bitmap` that the tests use, so that they also run on the web.
 */
class TestPixels internal constructor(private val pixelMap: PixelMap) {
    val width: Int get() = pixelMap.width
    val height: Int get() = pixelMap.height

    /** Returns the non-premultiplied ARGB color of the pixel at ([x], [y]). */
    fun getColor(x: Int, y: Int): Int = pixelMap[x, y].toArgb()
}

fun ImageBitmap.toTestPixels(): TestPixels = TestPixels(toPixelMap())
