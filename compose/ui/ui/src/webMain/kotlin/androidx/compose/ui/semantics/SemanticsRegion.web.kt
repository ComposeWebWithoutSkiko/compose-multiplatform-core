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

package androidx.compose.ui.semantics

import androidx.compose.ui.unit.IntRect
import kotlin.math.max
import kotlin.math.min

/**
 * A region made of disjoint rectangles. The semantics code only intersects regions and subtracts
 * rectangles from them, so a flat list of rectangles is enough.
 */
private class RectListSemanticsRegion : SemanticsRegion {
    // Disjoint, non-empty rectangles, stored as (left, top, right, bottom) quadruples.
    private var rects = IntArray(16)
    private var count = 0

    override fun set(rect: IntRect) {
        count = 0
        add(rect.left, rect.top, rect.right, rect.bottom)
    }

    override val bounds: IntRect
        get() {
            if (count == 0) return IntRect.Zero
            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = Int.MIN_VALUE
            var bottom = Int.MIN_VALUE
            for (i in 0 until count) {
                left = min(left, rects[i * 4])
                top = min(top, rects[i * 4 + 1])
                right = max(right, rects[i * 4 + 2])
                bottom = max(bottom, rects[i * 4 + 3])
            }
            return IntRect(left, top, right, bottom)
        }

    override val isEmpty: Boolean
        get() = count == 0

    override fun intersect(region: SemanticsRegion): Boolean {
        val other = region as RectListSemanticsRegion
        val source = rects.copyOf(count * 4)
        val sourceCount = count
        count = 0
        for (i in 0 until sourceCount) {
            for (j in 0 until other.count) {
                add(
                    max(source[i * 4], other.rects[j * 4]),
                    max(source[i * 4 + 1], other.rects[j * 4 + 1]),
                    min(source[i * 4 + 2], other.rects[j * 4 + 2]),
                    min(source[i * 4 + 3], other.rects[j * 4 + 3]),
                )
            }
        }
        return count > 0
    }

    override fun difference(rect: IntRect): Boolean {
        val source = rects.copyOf(count * 4)
        val sourceCount = count
        count = 0
        for (i in 0 until sourceCount) {
            val left = source[i * 4]
            val top = source[i * 4 + 1]
            val right = source[i * 4 + 2]
            val bottom = source[i * 4 + 3]
            if (rect.left >= right || rect.right <= left || rect.top >= bottom ||
                rect.bottom <= top
            ) {
                add(left, top, right, bottom)
                continue
            }
            // The parts above and below the subtracted rectangle span the full width,
            // the parts to its left and right only the overlapping band.
            val bandTop = max(top, rect.top)
            val bandBottom = min(bottom, rect.bottom)
            add(left, top, right, bandTop)
            add(left, bandBottom, right, bottom)
            add(left, bandTop, min(right, rect.left), bandBottom)
            add(max(left, rect.right), bandTop, right, bandBottom)
        }
        return count > 0
    }

    private fun add(left: Int, top: Int, right: Int, bottom: Int) {
        if (left >= right || top >= bottom) return
        if ((count + 1) * 4 > rects.size) {
            rects = rects.copyOf(rects.size * 2)
        }
        rects[count * 4] = left
        rects[count * 4 + 1] = top
        rects[count * 4 + 2] = right
        rects[count * 4 + 3] = bottom
        count++
    }
}

internal actual fun SemanticsRegion(): SemanticsRegion = RectListSemanticsRegion()
