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
 * Redraws slots of pages of the atlas in place with WebGL, where the browser would go through their
 * pixels (see [SlotColorMatrices], [SlotPixelBlurs]): each page is uploaded once, through its pixels
 * ([AtlasPage.uploadPixels]), each slot drawn into a part of its own of the canvas of the shared
 * context, then drawn back into its place. Through the pixels, the slots would be read, computed on
 * the CPU and written back.
 */
internal abstract class SlotsThroughWebGL {
    /** The pages [redraw] uploaded, while it draws, so that it uploads each of them once. */
    private val pages = ArrayList<AtlasPage>()

    /** The parts of the canvas the slots are drawn into. */
    private val shelves = Shelves()

    /** Where in the canvas the slot of each job is drawn. */
    private var xs = IntArray(0)
    private var ys = IntArray(0)

    /** The page holding the slot of [job]. */
    protected abstract fun pageOf(job: FramePlan.Job): AtlasPage

    /** Where the slot of [job] is on its page, and its size. */
    protected abstract fun xOf(job: FramePlan.Job): Int
    protected abstract fun yOf(job: FramePlan.Job): Int
    protected abstract fun widthOf(job: FramePlan.Job): Int
    protected abstract fun heightOf(job: FramePlan.Job): Int

    /**
     * Starts drawing into the top-left [width] x [height] pixels of the canvas of the shared context,
     * which it returns, or `null` on failure, when nothing is to be drawn.
     */
    protected abstract fun begin(width: Int, height: Int): JsAny?

    /** Draws the slot of [job] from [texture], its page uploaded, at ([x], [y]) of the canvas. */
    protected abstract fun draw(job: FramePlan.Job, texture: JsAny, x: Int, y: Int)

    /**
     * Redraws the slots of [jobs] and returns `true`; or redraws none and returns `false`, without
     * WebGL, with a page larger than a texture, or without room in the canvas for all of them.
     */
    protected fun redraw(jobs: List<FramePlan.Job>): Boolean {
        if (xs.size < jobs.size) {
            xs = IntArray(jobs.size)
            ys = IntArray(jobs.size)
        }
        val maxSize = WebGLRenderer.maxTextureSize
        shelves.reset(maxSize)
        for (i in jobs.indices) {
            val job = jobs[i]
            // The page is uploaded into a texture of its size.
            val pageTarget = pageOf(job).target
            if (pageTarget.width > maxSize || pageTarget.height > maxSize) return false
            if (!shelves.place(widthOf(job), heightOf(job))) return false
            xs[i] = shelves.x
            ys[i] = shelves.y
        }
        val canvas = begin(shelves.width, shelves.height) ?: return false
        try {
            for (i in jobs.indices) {
                val job = jobs[i]
                val page = pageOf(job)
                if (page !in pages) {
                    pages.add(page)
                    page.uploadPixels()
                }
                draw(job, checkNotNull(page.texture), xs[i], ys[i])
            }
        } finally {
            WebGLRenderer.endBatch()
            pages.clear()
        }
        // Lost meanwhile, the context drew nothing: the slots are left as they are.
        if (SharedWebGL.isContextLost()) return false
        for (i in jobs.indices) {
            val job = jobs[i]
            val target = pageOf(job).target
            target.setPixelTransform()
            target.setGlobalAlpha(1.0)
            target.setCompositeOperation(SourceOverOperation)
            val x = xOf(job).toDouble()
            val y = yOf(job).toDouble()
            val w = widthOf(job).toDouble()
            val h = heightOf(job).toDouble()
            target.ctx.clearRect(x, y, w, h)
            target.ctx.drawImage(canvas, xs[i].toDouble(), ys[i].toDouble(), w, h, x, y, w, h)
        }
        return true
    }
}
