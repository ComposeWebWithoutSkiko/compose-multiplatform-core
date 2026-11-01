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

import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.js

/** The next task: a weak reference keeps its target until the task that made it ends. */
// language=js
internal fun nextTask(): Promise<JsAny?> = js("new Promise((resolve) => setTimeout(resolve, 0))")

/**
 * Collects the garbage and returns `true`, or returns `false` where `gc()` isn't exposed: only
 * Chrome's test launcher exposes it, not Safari.
 */
// language=js
internal fun collectGarbage(): Boolean = js("{ if (typeof gc !== 'function') return false; gc(); return true; }")
