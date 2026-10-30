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

@file:OptIn(ExperimentalWasmJsInterop::class)

package androidx.compose.ui.platform

import androidx.compose.ui.InternalComposeUiApi
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

/**
 * The operating system the browser runs on, as far as the browser tells: what the key bindings of
 * the window and of text editing (foundation) depend on.
 */
@InternalComposeUiApi
enum class HostOs {
    MacOS,
    Ios,
    Windows,
    Linux,
    Android,
    Unknown;

    val isMacOS: Boolean get() = this == MacOS

    companion object {
        val current: HostOs by lazy {
            when (detectHostOs()) {
                1 -> MacOS
                2 -> Ios
                3 -> Windows
                4 -> Android
                5 -> Linux
                else -> Unknown
            }
        }
    }
}

// language=js
private fun detectHostOs(): Int = js(
    """{
        const platform = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '';
        const userAgent = navigator.userAgent || '';
        if (/iPhone|iPad|iPod/i.test(platform) || /iPhone|iPad|iPod/i.test(userAgent)) return 2;
        // iPadOS reports a Mac platform, but has a touch screen.
        if (/^Mac/i.test(platform)) return navigator.maxTouchPoints > 1 ? 2 : 1;
        if (/^Win/i.test(platform)) return 3;
        if (/Android/i.test(platform) || /Android/i.test(userAgent)) return 4;
        if (/Linux/i.test(platform)) return 5;
        return 0;
    }"""
)
