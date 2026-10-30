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

package androidx.compose.ui.webcanvas.path

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure

/**
 * [PathMeasure] for [WebPath]s, with the semantics of Skia's `SkPathMeasure` (as exposed by the
 * skiko backend): the measure operates on the first contour that has a non-zero length, distances
 * are pinned to `0..length`, and [forceClosed][setPath] measures open contours as if they were
 * closed.
 *
 * Like on other platforms the path is captured when [setPath] is called; later modifications of the
 * path are not observed.
 */
internal class WebPathMeasure : PathMeasure {
    /** The first contour of the path with a non-zero length. */
    private var contour: ContourMeasure? = null
    private val scratchPos = FloatArray(2)
    private val scratchTan = FloatArray(2)

    override val length: Float
        get() = contour?.length ?: 0f

    override fun setPath(path: Path?, forceClosed: Boolean) {
        // The contour refers to the points of the path: a snapshot, which later changes don't touch.
        contour = path?.let { ContourMeasureIter(it.asWebPath().snapshot(), forceClosed).next() }
    }

    override fun getSegment(
        startDistance: Float,
        stopDistance: Float,
        destination: Path,
        startWithMoveTo: Boolean,
    ): Boolean {
        val measure = contour ?: return false
        var start = startDistance
        var stop = stopDistance
        // Like the other Compose implementations, pin the distances and reject empty ranges
        // before delegating to the Skia algorithm.
        if (start < 0f) start = 0f
        if (stop > measure.length) stop = measure.length
        if (!(start < stop)) return false
        return measure.getSegment(start, stop, destination.asWebPath(), startWithMoveTo)
    }

    override fun getPosition(distance: Float): Offset {
        val measure = contour ?: return Offset.Unspecified
        return if (measure.getPosTan(distance, scratchPos, null)) {
            Offset(scratchPos[0], scratchPos[1])
        } else {
            Offset.Unspecified
        }
    }

    override fun getTangent(distance: Float): Offset {
        val measure = contour ?: return Offset.Unspecified
        return if (measure.getPosTan(distance, null, scratchTan)) {
            Offset(scratchTan[0], scratchTan[1])
        } else {
            Offset.Unspecified
        }
    }
}
