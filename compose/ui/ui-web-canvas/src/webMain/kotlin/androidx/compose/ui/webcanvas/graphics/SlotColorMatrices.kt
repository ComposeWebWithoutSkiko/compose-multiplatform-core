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
 * Applies the color matrices of layers drawn ahead of a frame (see [FramePlan]) to their slots with
 * WebGL ([SlotsThroughWebGL]), where the browser would apply them through the pixels (see
 * [colorFilterReadsPixels]).
 */
internal class SlotColorMatrices : SlotsThroughWebGL() {
    /**
     * Applies the color matrices ([WebColorFilter.colorMatrix]) of the color filters of the paints
     * of [jobs], which have a slot, and returns `true`; or applies none and returns `false`, without
     * WebGL, with a page larger than a texture, or without room in the canvas for all of them.
     */
    fun apply(jobs: List<FramePlan.Job>): Boolean = redraw(jobs)

    override fun pageOf(job: FramePlan.Job): AtlasPage = checkNotNull(job.page)

    override fun xOf(job: FramePlan.Job): Int = job.slotX

    override fun yOf(job: FramePlan.Job): Int = job.slotY

    override fun widthOf(job: FramePlan.Job): Int = job.width

    override fun heightOf(job: FramePlan.Job): Int = job.height

    override fun begin(width: Int, height: Int): JsAny? = WebGLRenderer.beginColorMatrices(width, height)

    override fun draw(job: FramePlan.Job, texture: JsAny, x: Int, y: Int) {
        WebGLRenderer.drawColorMatrix(
            texture,
            job.slotX,
            job.slotY,
            job.width,
            job.height,
            checkNotNull(job.paint?.appliedColorFilter).jsColorMatrix,
            x,
            y,
        )
    }
}
