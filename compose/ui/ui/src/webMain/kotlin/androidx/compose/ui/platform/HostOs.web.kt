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

package androidx.compose.ui.platform

import kotlin.js.js

/** Whether the browser runs on macOS (but not on an iPad presenting itself as a Mac). */
internal val isMacOsHost: Boolean by lazy { detectMacOs() }

// language=js
private fun detectMacOs(): Boolean = js(
    """{
        const platform = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '';
        if (!/^Mac/i.test(platform)) return false;
        // iPadOS reports a Mac platform, but has a touch screen.
        return !(navigator.maxTouchPoints > 1);
    }"""
)
