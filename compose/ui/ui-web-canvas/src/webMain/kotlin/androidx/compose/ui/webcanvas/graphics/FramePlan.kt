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

/** The content of a layer, which a job draws ahead of the frame (see [FramePlan]). */
internal interface JobContent {
    /**
     * Draws the content at [contentOp] into [canvas]: the whole content for
     * [DisplayList.WholeList], or else a part of it (see [DisplayList.replayContent]).
     */
    fun replayContent(canvas: WebCanvas, contentOp: Int)
}

/**
 * The layers of a frame that are drawn through an offscreen buffer, the jobs, so that
 * [FrameRenderer] draws their content ahead of the frame into a [LayerAtlas]. A [WebCanvas] that
 * [plans][WebCanvas.startPlanning] the frame finds them: it replays the frame without drawing.
 *
 * Chrome synchronizes with its GPU process whenever a canvas is drawn as an image right after
 * being drawn into: compositing each offscreen layer right after drawing it costs that once per
 * layer. Drawing the content of all the layers of a frame first, the deepest first, costs it once
 * per level of nesting. Layers in perspective, which WebGL draws, are drawn in perspective a level
 * at a time too (see [PerspectiveProjector]).
 *
 * The jobs are reused from frame to frame.
 */
internal class FramePlan {
    /**
     * How the slot of a layer in perspective is drawn: by WebGL, into the canvas of the shared
     * context, which the layer is composited from (see [WebCanvas.drawInPerspective]).
     */
    class Projection {
        /** Maps the unit square of the slot to the device space. */
        val uvMatrix = Projective()

        // The device pixels the slot in perspective covers.
        var left = 0
        var top = 0
        var width = 0
        var height = 0

        /**
         * The [PerspectiveProjector] that drew the slot in perspective at ([x], [y]) of the canvas
         * of the shared context, as its frame [version] (see [SharedWebGL.canvasHolding]), or
         * `null` if none did.
         */
        var owner: Any? = null
        var version = 0
        var x = 0
        var y = 0
    }

    /** A layer whose content is drawn ahead of the frame. */
    class Job {
        /** What draws the content, and which part of it is the content. */
        private var content: JobContent? = null
        private var contentOp = DisplayList.WholeList

        // The device pixels the layer covers.
        var left = 0
            private set
        var top = 0
            private set
        var width = 0
            private set
        var height = 0
            private set

        /** The transformation the content is drawn with. */
        val matrix = Affine()

        /** The paint the layer is composited with, if any. */
        var paint: WebPaint? = null
            private set

        /**
         * Whether the layer is in perspective, its slot drawn as [projection] describes; its
         * content is then drawn in its own space, its pixels at the scale of [matrix].
         */
        var isInPerspective = false
            private set

        val projection = Projection()

        /**
         * How the blur of the layer by its effect is prepared ahead, from its slot, if at all (see
         * [Effects.plan]).
         */
        val blur = PreparedBlur()

        /** The image the effect of the layer works in, if it's drawn ahead (see [Effects.plan]). */
        val result = PreparedImage()

        /**
         * The image of a foreground that [result] is blended with, if it's drawn ahead: on a page of
         * its own, before [result], as a canvas drawn into itself or read while another is written
         * costs a synchronization with the GPU every time.
         */
        val foreground = PreparedImage()

        /** The jobs in this one, in the order its content meets them. */
        val children = ArrayList<Job>()

        /** The jobs this one is among (its parent's children, or the root jobs), at [index]. */
        var siblings: List<Job> = emptyList()
            private set
        var index = 0
            private set

        /**
         * Where the content is drawn: the slot of [page] at ([slotX], [slotY]), or no page if the
         * atlas had no room for it (see [LayerAtlas.layOut]), when it's drawn where it's met.
         */
        var page: AtlasPage? = null
            private set
        var slotX = 0
            private set
        var slotY = 0
            private set

        /** Gives the job its slot. */
        fun place(page: AtlasPage, x: Int, y: Int) {
            this.page = page
            slotX = x
            slotY = y
        }

        /** Whether this is the layer with [content] at [contentOp] that covers the given pixels. */
        fun isLayer(
            content: JobContent,
            contentOp: Int,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
        ): Boolean =
            content === this.content && contentOp == this.contentOp &&
                left == this.left && top == this.top && width == this.width && height == this.height

        fun replayContent(canvas: WebCanvas) {
            checkNotNull(content).replayContent(canvas, contentOp)
        }

        fun set(
            content: JobContent,
            contentOp: Int,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            matrix: Affine,
            paint: WebPaint?,
            isInPerspective: Boolean,
            siblings: List<Job>,
        ) {
            this.content = content
            this.contentOp = contentOp
            this.left = left
            this.top = top
            this.width = width
            this.height = height
            this.matrix.set(matrix)
            this.paint = paint
            this.isInPerspective = isInPerspective
            projection.owner = null
            blur.clear()
            result.clear()
            foreground.clear()
            this.siblings = siblings
            index = siblings.size
        }

        /** Drops the references to what the job draws. */
        fun clear() {
            content = null
            paint = null
            page = null
            projection.owner = null
            blur.clear()
            result.clear()
            foreground.clear()
            children.clear()
            siblings = emptyList()
        }
    }

    /** The jobs that aren't in another one, in the order the frame meets them. */
    val rootJobs = ArrayList<Job>()

    /** The number of levels of nesting of the jobs. */
    var levelCount = 0
        private set

    private val levels = ArrayList<ArrayList<Job>>()
    private val jobs = ArrayList<Job>()
    private var jobCount = 0
    private val openJobs = ArrayList<Job>()

    val isEmpty: Boolean get() = jobCount == 0

    /** The jobs at [level] of nesting, in the order the frame meets them. */
    fun jobsAt(level: Int): List<Job> = if (level < levelCount) levels[level] else emptyList()

    /**
     * Notes the layer whose content is [content] at [contentOp] (see [JobContent.replayContent]),
     * met while planning: the draws up to [closeJob] are its content. A layer [isInPerspective]
     * then has its [Job.projection] filled; a layer with an effect, what it prepares ahead (see
     * [Effects.plan]).
     */
    fun openJob(
        content: JobContent,
        contentOp: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        matrix: Affine,
        paint: WebPaint?,
        isInPerspective: Boolean = false,
    ): Job {
        val job = if (jobCount < jobs.size) jobs[jobCount] else Job().also { jobs.add(it) }
        jobCount++
        val siblings = openJobs.lastOrNull()?.children ?: rootJobs
        job.set(content, contentOp, left, top, width, height, matrix, paint, isInPerspective, siblings)
        // The level of nesting: 0 for a layer that isn't in another one.
        val level = openJobs.size
        siblings.add(job)
        if (level == levelCount) {
            if (levels.size == levelCount) levels.add(ArrayList())
            levelCount++
        }
        levels[level].add(job)
        openJobs.add(job)
        return job
    }

    fun closeJob() {
        openJobs.removeAt(openJobs.lastIndex)
    }

    /** Forgets the jobs, and what they draw. */
    fun clear() {
        for (i in 0 until jobCount) jobs[i].clear()
        jobCount = 0
        for (i in 0 until levelCount) levels[i].clear()
        levelCount = 0
        rootJobs.clear()
        openJobs.clear()
    }
}

/**
 * Matches the layers that a canvas drawing a frame meets with the jobs of the frame's plan, in the
 * order they were planned, so that the canvas composites a layer drawn ahead instead of drawing its
 * content.
 */
internal class JobMatcher {
    private var jobs: List<FramePlan.Job>? = null
    private var next = 0

    /**
     * Whether a layer was met that isn't the next job, which can happen if drawing goes another way
     * than planning (WebGL failing in between): the rest of the frame is drawn without the jobs.
     */
    var isBroken = false
        private set

    /** Matches [jobs] from now on. */
    fun start(jobs: List<FramePlan.Job>) {
        this.jobs = jobs
        next = 0
        isBroken = false
    }

    fun stop() {
        jobs = null
    }

    /**
     * Returns the next job if it's the layer with [content] at [contentOp] that covers the given
     * pixels, or `null`. Another layer breaks the match (see [isBroken]); a layer whose content
     * isn't recorded can't be planned, and is ignored.
     */
    fun take(
        content: JobContent?,
        contentOp: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): FramePlan.Job? {
        val jobs = jobs ?: return null
        if (content == null) return null
        val job = jobs.getOrNull(next)
        if (job == null || !job.isLayer(content, contentOp, left, top, width, height)) {
            this.jobs = null
            isBroken = true
            return null
        }
        next++
        return job
    }

    /** Matches the jobs in [job], whose content is drawn where it's met, until [leave]. */
    fun enter(job: FramePlan.Job) {
        if (isBroken) return
        jobs = job.children
        next = 0
    }

    /** Matches the jobs that follow [job] again. */
    fun leave(job: FramePlan.Job) {
        if (isBroken) return
        jobs = job.siblings
        next = job.index + 1
    }
}
