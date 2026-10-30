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

import androidx.compose.ui.graphics.Color
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.js
import kotlin.js.toJsReference
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The snapshots of paints, shared by the equal paints ([WebPaint.snapshot]). */
class SharedPaintsTest {
    @Test
    fun equalPaintsShareTheirSnapshot() {
        val snapshot = WebPaint().apply { color = Color(0xFF123456) }.snapshot()
        assertSame(snapshot, WebPaint().apply { color = Color(0xFF123456) }.snapshot())
        assertNotSame(snapshot, WebPaint().apply { color = Color(0xFF123457) }.snapshot())
    }

    @Test
    fun aSnapshotThatNothingUsesIsNotKeptAlive(): Promise<JsAny?> {
        val snapshot = weakSnapshotOfAPaintSnapshottedOnce()
        // A weak reference keeps its target until the task that made it ends.
        return nextTask().then {
            // Only Chrome's test launcher exposes gc(), not Safari.
            if (collectGarbage()) assertNull(deref(snapshot), "The snapshot is kept alive")
            null
        }
    }

    /** A weak reference to the snapshot of a paint that nothing uses any more. */
    private fun weakSnapshotOfAPaintSnapshottedOnce(): JsAny =
        newWeakRef(WebPaint().apply { color = Color(0xFF654321) }.snapshot().toJsReference())
}

// language=js
private fun newWeakRef(target: JsAny): JsAny = js("new WeakRef(target)")

// language=js
private fun deref(ref: JsAny): JsAny? = js("ref.deref()")

// language=js
private fun nextTask(): Promise<JsAny?> = js("new Promise((resolve) => setTimeout(resolve, 0))")

/** Collects the garbage and returns `true`, or returns `false` where `gc()` isn't exposed. */
// language=js
private fun collectGarbage(): Boolean = js("{ if (typeof gc !== 'function') return false; gc(); return true; }")
