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
 * Draws the slots of the layers in perspective of a level of a frame (see [FramePlan]) in
 * perspective with WebGL, all at once: each page of the atlas they're in is uploaded once, and
 * each slot is drawn into a part of its own of the canvas of the shared context, from which the
 * layer is then composited (see [WebCanvas.drawInPerspective]). Drawing the layers one by one
 * would synchronize with the GPU twice for each of them, uploading its content and drawing the
 * result into the frame; this way, it's twice for all.
 */
internal class PerspectiveProjector {
    /** Counts the times this projector drew, which the canvas holds the last of. */
    private var version = 0

    /** The pages [project] uploaded, while it draws, so that it uploads each of them once. */
    private val pages = ArrayList<AtlasPage>()

    /** The parts of the canvas the slots are drawn into. */
    private val shelves = Shelves()

    /**
     * Draws the slots of the [jobs] in perspective that have one in perspective, as far as the
     * canvas has room for them, noting where in their [FramePlan.Job.projection], and returns how
     * many. The others are drawn where they're met.
     */
    fun project(jobs: List<FramePlan.Job>): Int {
        shelves.reset(WebGLRenderer.maxTextureSize)
        var count = 0
        for (job in jobs) {
            if (!job.isInPerspective || job.page == null) continue
            val projection = job.projection
            if (!shelves.place(projection.width, projection.height)) continue
            projection.owner = this
            projection.x = shelves.x
            projection.y = shelves.y
            count++
        }
        if (count == 0) return 0
        if (!WebGLRenderer.beginProjections(shelves.width, shelves.height)) {
            for (job in jobs) job.projection.owner = null
            return 0
        }
        version++
        try {
            for (job in jobs) {
                val projection = job.projection
                if (projection.owner !== this) continue
                val page = checkNotNull(job.page)
                if (page !in pages) {
                    pages.add(page)
                    page.uploadTexture()
                }
                // The texture has the size of the page.
                val pageWidth = page.target.width.toDouble()
                val pageHeight = page.target.height.toDouble()
                WebGLRenderer.drawProjection(
                    checkNotNull(page.texture),
                    slotX = job.slotX / pageWidth,
                    slotY = job.slotY / pageHeight,
                    slotWidth = job.width / pageWidth,
                    slotHeight = job.height / pageHeight,
                    uvMatrix = projection.uvMatrix,
                    x = projection.x,
                    y = projection.y,
                    left = projection.left,
                    top = projection.top,
                    width = projection.width,
                    height = projection.height,
                )
                projection.version = version
            }
        } finally {
            WebGLRenderer.endBatch()
            pages.clear()
        }
        SharedWebGL.hold(this, version)
        return count
    }
}
