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
 * Prepares the blurs of the layers of a level drawn ahead of a frame (see [FramePlan.Job.blur])
 * from their slots, once the content of all of them is drawn: draws the image of each into its
 * place, with the edges extended and, through the pixels, scaled down, then blurs the images through
 * their pixels with WebGL ([SlotPixelBlurs]), or else a page at a time: reading the pixels of a
 * canvas just drawn into makes the browser finish drawing it, as long whatever its size, so once per
 * page rather than once per layer.
 */
internal class SlotBlurs {
    /** Blurs the images through their pixels with WebGL. */
    private val pixelBlurs = SlotPixelBlurs()

    /** The jobs whose images [prepare] blurs through their pixels. */
    private val pixelJobs = ArrayList<FramePlan.Job>()

    /** The pages holding the images of [pixelJobs]. */
    private val pages = ArrayList<AtlasPage>()

    /** Prepares the blurs of [jobs] that have a place for their image. Returns how many. */
    fun prepare(jobs: List<FramePlan.Job>): Int {
        var prepared = 0
        for (job in jobs) {
            val blur = job.blur
            val page = blur.page ?: continue
            val target = page.target
            target.setPixelTransform()
            target.setGlobalAlpha(1.0)
            target.setCompositeOperation(SourceOverOperation)
            Effects.drawBlurSource(
                target.ctx, checkNotNull(job.page).canvas, job.slotX, job.slotY, job.width, job.height, blur,
                blur.x + PreparedBlur.Border, blur.y + PreparedBlur.Border,
            )
            // It sets the image smoothing of the context directly.
            target.invalidateState()
            if (blur.throughPixels) pixelJobs.add(job)
            prepared++
        }
        try {
            if (pixelJobs.isNotEmpty() && !pixelBlurs.blur(pixelJobs)) blurThroughPixels()
        } finally {
            // The jobs are reused from frame to frame.
            pixelJobs.clear()
            pages.clear()
        }
        return prepared
    }

    /** Blurs the images of [pixelJobs] on the CPU, reading and writing each of their pages once. */
    private fun blurThroughPixels() {
        for (job in pixelJobs) {
            val page = checkNotNull(job.blur.page)
            if (page !in pages) pages.add(page)
        }
        for (page in pages) {
            // The images of blurs through ctx.filter on the page, if any, are written back as they're
            // read.
            val ctx = page.target.ctx
            val pixels = ctx.getImageData(0, 0, page.usedWidth, page.usedHeight)
            for (job in pixelJobs) {
                val blur = job.blur
                if (blur.page === page) {
                    Effects.blurImage(pixels, blur.x + PreparedBlur.Border, blur.y + PreparedBlur.Border, blur)
                }
            }
            ctx.putImageData(pixels, 0, 0)
        }
    }
}
