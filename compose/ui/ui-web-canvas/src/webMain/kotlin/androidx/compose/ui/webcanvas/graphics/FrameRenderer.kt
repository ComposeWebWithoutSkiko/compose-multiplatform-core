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

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.webcanvas.dom.createThrowingContext2D

/**
 * Draws frames into [target] in two phases, so that the content of the offscreen layers is drawn
 * before anything is composited (see [FramePlan]): a frame is recorded into a display list, which
 * is planned, then the content of its layers is drawn into [atlas], the deepest first, and the
 * display list is drawn into [target], compositing the layers from the atlas.
 *
 * Since a frame is drawn after it's recorded, what it draws is drawn as it is at the end of the
 * recording, as on Android, where a display list refers to render nodes: a graphics layer whose
 * properties change after it's drawn, or which is released, is drawn as it is then, and so are an
 * image drawn into and an array of points changed after they're drawn.
 */
internal class FrameRenderer(
    private val target: DrawTarget,
    private val atlas: LayerAtlas = LayerAtlas(),
) {
    private val canvas = WebCanvas(target)
    private val recorder = RecordingCanvas(reusesBuffers = true)
    private val plan = FramePlan()

    /** The context of the planner, which throws if it's used. */
    private val plannerContext = createThrowingContext2D()

    /** Follows the frame without drawing anything, on the bounds of [target]. */
    private val planner = WebCanvas(plannerTarget())

    private fun plannerTarget() =
        DrawTarget(plannerContext, target.originX, target.originY, target.width, target.height)

    /** Draws the content of the jobs into their slots. */
    private var slotCanvas: WebCanvas? = null

    /** Draws the slots of the layers in perspective in perspective, a level at a time. */
    private val projector = PerspectiveProjector()

    /** Applies the color matrices of the slots of a level with WebGL. */
    private val colorMatrices = SlotColorMatrices()

    /** The jobs of a level whose color matrices [colorMatrices] applies. */
    private val matrixJobs = ArrayList<FramePlan.Job>()

    /** Prepares the blurs of the layers of a level from their slots. */
    private val blurs = SlotBlurs()

    /** The layers whose content was drawn ahead in the last frame, as planned. */
    internal var layersDrawnAhead = 0
        private set

    /** The layers in perspective drawn in perspective ahead in the last frame. */
    internal var layersProjectedAhead = 0
        private set

    /** The layers whose blur was prepared ahead in the last frame (see [FramePlan.Job.blur]). */
    internal var layersBlurredAhead = 0
        private set

    /** The start of the last frame, which keeps the images of shadows (see [ShadowSprites]). */
    private var frameStart = 0.0

    /** Draws the frame that [block] draws into [target]. */
    fun render(block: (Canvas) -> Unit) {
        frameStart = ShadowSprites.startFrame(frameStart)
        recorder.beginRecording()
        try {
            block(recorder)
            draw(recorder.endRecording())
        } finally {
            recorder.recycle()
            plan.clear()
        }
    }

    private fun draw(frame: DisplayList) {
        // The bounds of the target may have changed since the last frame.
        planner.reset(plannerTarget())
        planner.startPlanning(plan)
        try {
            frame.replay(planner)
        } finally {
            planner.finish()
        }
        resetCounts()
        canvas.reset(target)
        // Also with no job, so that the pages unused for a while are released.
        atlas.layOut(plan)
        try {
            if (!plan.isEmpty && drawJobs()) canvas.useJobs(plan.rootJobs)
            frame.replay(canvas)
        } finally {
            canvas.finish()
        }
        if (canvas.isPlanBroken) resetCounts()
    }

    private fun resetCounts() {
        layersDrawnAhead = 0
        layersProjectedAhead = 0
        layersBlurredAhead = 0
    }

    /**
     * Draws the content of the jobs that have a slot, from the deepest level up, each level
     * compositing the jobs of the level below it, with their color filters, the blurs of a level
     * prepared, and the slots of the layers in perspective of a level in perspective, last: WebGL
     * holds them in the canvas of the shared context until they're composited, which drawing other
     * slots there (blurs, color matrices) would clear. Returns `false` if the content of a job met a
     * layer that the plan doesn't have, when the frame is to be drawn without the jobs.
     */
    private fun drawJobs(): Boolean {
        for (level in plan.levelCount - 1 downTo 0) {
            val jobs = plan.jobsAt(level)
            for (job in jobs) {
                val page = job.page ?: continue
                val slotCanvas = slotCanvas ?: WebCanvas(page.target).also { slotCanvas = it }
                if (!slotCanvas.drawJob(job, page)) {
                    resetCounts()
                    return false
                }
                layersDrawnAhead++
            }
            applyColorFilters(jobs)
            layersBlurredAhead += blurs.prepare(jobs)
            layersProjectedAhead += projector.project(jobs)
        }
        return true
    }

    /**
     * Applies the color filters of the [jobs] that apply in place to their slots, once the content
     * of all of them is drawn: reading or uploading the pixels of a slot makes the browser finish
     * drawing its page, which doing it between the jobs would make it do once per job. Those that
     * would read the pixels apply with WebGL ([colorMatrices]), unless it can't.
     */
    private fun applyColorFilters(jobs: List<FramePlan.Job>) {
        for (job in jobs) {
            if (job.page != null && readsPixels(job)) matrixJobs.add(job)
        }
        val matricesApplied = try {
            matrixJobs.isEmpty() || colorMatrices.apply(matrixJobs)
        } finally {
            // The jobs are reused from frame to frame.
            matrixJobs.clear()
        }
        for (job in jobs) {
            if (matricesApplied && readsPixels(job)) continue
            val pageTarget = (job.page ?: continue).target
            applyLayerColorFilterInPlace(
                job.paint, pageTarget.ctx, job.slotX, job.slotY, job.width, job.height,
            )
            // It sets the transformation of the context directly.
            pageTarget.invalidateTransform()
        }
    }

    /** Whether the color filter of the layer of [job] would apply through the pixels. */
    private fun readsPixels(job: FramePlan.Job): Boolean =
        job.paint?.appliedColorFilter?.let { colorFilterReadsPixels(it) } == true
}
