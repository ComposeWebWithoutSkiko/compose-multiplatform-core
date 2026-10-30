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
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.webcanvas.graphics.FrameRenderer
import androidx.compose.ui.webcanvas.graphics.asCanvasImage

/**
 * Renders Compose frames into [image] the way [HtmlCanvasRenderer] renders them into a window,
 * over what the image holds: for the tests, which then check what a window draws.
 *
 * @param image an image created with `ImageBitmap(width, height)`
 */
@InternalComposeUiApi
public class ImageBitmapRenderer(image: ImageBitmap) {
    private val frameRenderer = FrameRenderer(image.asCanvasImage().drawTarget)

    /** Lets [block] draw a frame into the image, in its pixel coordinates. */
    public fun render(block: (Canvas) -> Unit) {
        frameRenderer.render(block)
    }
}
