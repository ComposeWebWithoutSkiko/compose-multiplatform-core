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

package androidx.compose.ui.platform.webgl

import kotlin.test.assertNotNull

/**
 * Asserts that a render target was created, and returns it: a render target needs WebGL 2, which
 * every browser the tests run in supports.
 */
internal fun assertCreated(target: WebGLRenderTarget?): WebGLRenderTarget =
    assertNotNull(target, "no render target: WebGL 2 isn't available")
