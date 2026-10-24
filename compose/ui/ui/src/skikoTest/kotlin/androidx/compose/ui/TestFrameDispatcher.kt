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

package androidx.compose.ui

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Runs [onFrame] in [context] once after one or more [scheduleFrame] calls, like the frame
 * dispatcher of a real platform window does.
 */
internal class TestFrameDispatcher(
    context: CoroutineContext,
    private val onFrame: suspend () -> Unit,
) {
    private var frameScheduled = false
    private val frameChannel = Channel<Unit>(Channel.CONFLATED)

    private val job = CoroutineScope(context).launch {
        while (true) {
            frameChannel.receive()
            frameScheduled = false
            onFrame()
            yield()
        }
    }

    fun scheduleFrame() {
        if (!frameScheduled) {
            frameScheduled = true
            frameChannel.trySend(Unit)
        }
    }

    fun cancel() {
        job.cancel()
    }
}
