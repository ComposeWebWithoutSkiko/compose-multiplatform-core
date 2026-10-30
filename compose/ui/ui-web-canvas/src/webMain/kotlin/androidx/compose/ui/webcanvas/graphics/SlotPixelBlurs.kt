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
 * Blurs the images of the blurs of layers drawn ahead of a frame (see [SlotBlurs]) through their
 * pixels with WebGL ([SlotsThroughWebGL]), as [blurPixels] would, with their borders as
 * [extendBorder] makes them: their slots, each an image with its border, are blurred in place.
 */
internal class SlotPixelBlurs : SlotsThroughWebGL() {
    /**
     * Blurs the images of the blurs ([FramePlan.Job.blur]) of [jobs], placed and through the pixels,
     * and returns `true`; or blurs none and returns `false`, without WebGL, with a page larger than a
     * texture, or without room in the canvas for all of them.
     */
    fun blur(jobs: List<FramePlan.Job>): Boolean = redraw(jobs)

    override fun pageOf(job: FramePlan.Job): AtlasPage = checkNotNull(job.blur.page)

    override fun xOf(job: FramePlan.Job): Int = job.blur.x

    override fun yOf(job: FramePlan.Job): Int = job.blur.y

    override fun widthOf(job: FramePlan.Job): Int = job.blur.slotWidth

    override fun heightOf(job: FramePlan.Job): Int = job.blur.slotHeight

    override fun begin(width: Int, height: Int): JsAny? = WebGLRenderer.beginBlurs(width, height)

    override fun draw(job: FramePlan.Job, texture: JsAny, x: Int, y: Int) {
        val blur = job.blur
        WebGLRenderer.blurImage(
            texture,
            blur.x + PreparedBlur.Border,
            blur.y + PreparedBlur.Border,
            blur.imageWidth,
            blur.imageHeight,
            blur.radiiX,
            blur.radiiY,
            x,
            y,
            PreparedBlur.Border,
        )
    }
}
