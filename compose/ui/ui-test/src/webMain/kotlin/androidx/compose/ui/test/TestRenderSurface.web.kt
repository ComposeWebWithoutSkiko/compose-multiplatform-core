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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.webcanvas.ImageBitmapRenderer

/**
 * Draws into an image backed by a canvas, through the web canvas implementation: frames are drawn
 * the way a window draws them (see [ImageBitmapRenderer]).
 */
@OptIn(InternalComposeUiApi::class)
private class CanvasTestRenderSurface(private val width: Int, private val height: Int) :
    TestRenderSurface {
    private val image = ImageBitmap(width, height)
    private val canvas = Canvas(image)
    private val renderer = ImageBitmapRenderer(image)
    private val clearPaint = Paint().apply { blendMode = BlendMode.Clear }
    private val copyPaint = Paint()

    override fun clear() {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), clearPaint)
    }

    override fun draw(block: (Canvas) -> Unit) {
        renderer.render(block)
    }

    override fun capture(): ImageBitmap = ImageBitmap(width, height).also {
        Canvas(it).drawImage(image, Offset.Zero, copyPaint)
    }

    override fun capture(left: Int, top: Int, right: Int, bottom: Int): ImageBitmap {
        val l = left.coerceIn(0, width)
        val t = top.coerceIn(0, height)
        val w = (right.coerceIn(0, width) - l).coerceAtLeast(1)
        val h = (bottom.coerceIn(0, height) - t).coerceAtLeast(1)
        return ImageBitmap(w, h).also {
            Canvas(it).drawImageRect(
                image,
                srcOffset = IntOffset(l, t),
                srcSize = IntSize(w, h),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(w, h),
                paint = copyPaint,
            )
        }
    }
}

internal actual fun createTestRenderSurface(width: Int, height: Int): TestRenderSurface =
    CanvasTestRenderSurface(width, height)

@OptIn(InternalComposeUiApi::class)
internal actual fun registerTestComposeImplementation() {
    registerWebCanvasComposeImplementation()
}
