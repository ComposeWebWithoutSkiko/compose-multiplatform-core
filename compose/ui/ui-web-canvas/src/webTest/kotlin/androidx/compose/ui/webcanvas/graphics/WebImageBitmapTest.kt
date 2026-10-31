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

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class WebImageBitmapTest {
    private fun tint(index: Int) = WebColorFilter.Tint(Color(index, 0, 0), BlendMode.SrcIn)

    @Test
    fun anImageDrawnInSeveralTintsKeepsACopyOfEach() {
        val image = CanvasImageBitmap(96, 96)
        val tints = List(9) { tint(it) }
        val copies = tints.map { image.filteredSource(it) }
        tints.forEachIndexed { index, tint -> assertSame(copies[index], image.filteredSource(tint)) }
    }

    @Test
    fun aChangedImageMakesItsCopiesAgain() {
        val image = CanvasImageBitmap(96, 96)
        val copy = image.filteredSource(tint(1))
        image.markChanged()
        assertNotSame(copy, image.filteredSource(tint(1)))
    }

    @Test
    fun aLargeImageKeepsOneCopy() {
        val image = CanvasImageBitmap(1024, 1024)
        val first = image.filteredSource(tint(1))
        image.filteredSource(tint(2))
        assertNotSame(first, image.filteredSource(tint(1)))
    }
}
