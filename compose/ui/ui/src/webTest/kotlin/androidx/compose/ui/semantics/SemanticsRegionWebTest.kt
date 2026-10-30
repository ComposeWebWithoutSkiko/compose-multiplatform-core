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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemanticsRegionWebTest {
    private fun region(rect: IntRect) = SemanticsRegion().apply { set(rect) }

    @Test
    fun aRegionIntersectedKeepsTheCommonPart() {
        val region = region(IntRect(0, 0, 10, 10))
        assertTrue(region.intersect(region(IntRect(5, 5, 15, 15))))
        assertEquals(IntRect(5, 5, 10, 10), region.bounds)
    }

    @Test
    fun aRectangleSubtractedLeavesAHole() {
        val region = region(IntRect(0, 0, 10, 10))
        assertTrue(region.difference(IntRect(2, 2, 4, 4)))
        assertEquals(IntRect(0, 0, 10, 10), region.bounds)
        // Nothing is left of the hole, only of what surrounds it.
        assertFalse(region(IntRect(2, 2, 4, 4)).intersect(region))
        assertTrue(region(IntRect(0, 0, 3, 3)).intersect(region))
    }

    @Test
    fun aRegionSubtractedEntirelyIsEmpty() {
        val region = region(IntRect(0, 0, 10, 10))
        assertTrue(region.difference(IntRect(0, 0, 5, 10)))
        assertFalse(region.difference(IntRect(5, 0, 10, 10)))
        assertTrue(region.isEmpty)
        // Set again, after being empty.
        region.set(IntRect(1, 1, 2, 2))
        assertEquals(IntRect(1, 1, 2, 2), region.bounds)
    }
}
