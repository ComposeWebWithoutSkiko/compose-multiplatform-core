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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The layout of rectangles on shelves ([Shelves]). */
class ShelvesTest {
    @Test
    fun aRectangleThatDoesNotFitLeavesTheLayoutAsItWas() {
        val shelves = Shelves()
        shelves.reset(maxSize = 10)
        assertTrue(shelves.place(4, 3))
        assertTrue(shelves.place(4, 3))
        // Neither on this shelf nor on the next one, below it.
        assertFalse(shelves.place(4, 8))
        assertEquals(3, shelves.height)
        assertEquals(9, shelves.width)
        // The next rectangle goes where it would have gone without it.
        assertTrue(shelves.place(4, 3))
        assertEquals(0, shelves.x)
        assertEquals(3 + LayerAtlas.Gutter, shelves.y)
    }

    @Test
    fun shelvesFillingTheSquareStayWithinIt() {
        val shelves = Shelves()
        shelves.reset(maxSize = 10)
        assertTrue(shelves.place(10, 6))
        assertTrue(shelves.place(10, 10 - 6 - LayerAtlas.Gutter))
        assertEquals(10, shelves.height)
        assertFalse(shelves.place(1, 1))
        assertEquals(10, shelves.height)
    }
}
