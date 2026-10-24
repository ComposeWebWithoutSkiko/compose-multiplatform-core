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

package androidx.compose.ui.graphics

/**
 * The web backend draws through the browser's Canvas 2D API, which has no single native object
 * standing behind a Compose [Canvas]. This type only exists to satisfy the common declaration.
 */
@Deprecated(
    message = "There is no native canvas type on the web",
    level = DeprecationLevel.ERROR,
)
actual class NativeCanvas private constructor()

/**
 * The web backend keeps paint state in Kotlin and applies it to the browser's Canvas 2D context
 * when drawing, so there is no native paint object. This type only exists to satisfy the common
 * declaration.
 */
@Deprecated(
    message = "There is no native paint type on the web",
    level = DeprecationLevel.ERROR,
)
actual class NativePaint private constructor()
