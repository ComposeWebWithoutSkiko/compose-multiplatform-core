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

package androidx.compose.ui.test

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SkiaCanvasHolder
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import org.jetbrains.skia.Color
import org.jetbrains.skia.Surface

@OptIn(InternalComposeUiApi::class)
private class SkiaTestRenderSurface(width: Int, height: Int) : TestRenderSurface {
    private val surface = Surface.makeRasterN32Premul(width, height)
    private val canvasHolder = SkiaCanvasHolder(surface.canvas)

    override fun clear() {
        surface.canvas.clear(Color.TRANSPARENT)
    }

    override fun draw(block: (Canvas) -> Unit) {
        canvasHolder.drawInto(surface.canvas) { block(this) }
    }

    override fun capture(): ImageBitmap = surface.makeImageSnapshot().toComposeImageBitmap()

    override fun capture(left: Int, top: Int, right: Int, bottom: Int): ImageBitmap =
        surface.makeImageSnapshot(left, top, right, bottom)!!.toComposeImageBitmap()
}

internal actual fun createTestRenderSurface(width: Int, height: Int): TestRenderSurface =
    SkiaTestRenderSurface(width, height)

@OptIn(InternalComposeUiApi::class)
internal actual fun registerTestComposeImplementation() {
    registerSkikoComposeImplementation()
}
