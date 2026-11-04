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

/**
 * Counts the frames of all the renderers, for the images kept as long as frames draw them: the
 * shadows of [ShadowSprites] and the images of shaders ([WebShader.keptRaster]). What was drawn
 * since the previous frame of the renderer drawing started is kept (see [startFrame]). Drawing
 * outside of frames, it is what was drawn since the previous frame of the renderer that started a
 * frame last: nothing ages until a frame starts, and the budgets of the caches limit what they keep
 * there (see [ShadowSprites] and [WebShader.keepRaster]).
 */
internal object FrameTicks {
    /**
     * The tick of the frame being drawn, which marks what it draws. A double counts exactly as far
     * as needed, and is a number in JavaScript, unlike a long.
     */
    var tick = 0.0
        private set

    /** What was drawn at this tick or later is kept. */
    var keptSince = 0.0
        private set

    /**
     * Starts a frame of a renderer whose previous frame started at [previousStart] (0 for its
     * first frame), and returns the start of this one. Until the next frame, what was drawn since
     * [previousStart] is kept: what its previous frame drew and what the frames of other renderers
     * drew since then.
     */
    fun startFrame(previousStart: Double): Double {
        keptSince = previousStart
        return ++tick
    }
}
