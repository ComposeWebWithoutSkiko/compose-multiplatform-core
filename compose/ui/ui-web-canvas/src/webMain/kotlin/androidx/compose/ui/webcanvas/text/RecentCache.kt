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

package androidx.compose.ui.webcanvas.text

/**
 * A cache of the values used lately: those put since it last filled up with [capacity] values, and
 * those of the time before, which come back among the recent ones when found. Older values are
 * forgotten, so the cache holds twice [capacity] values at most, and the values in use stay: a
 * least-recently-used cache without bookkeeping on every lookup.
 *
 * A value knows its key ([keyOf]), so that a value found again is kept under its own key, not under
 * the one looked up with (which may be a key reused for every lookup).
 */
internal class RecentCache<K : Any, V : Any>(
    private val capacity: Int,
    private val keyOf: (V) -> K,
) {
    private var recent = HashMap<K, V>()
    private var older = HashMap<K, V>()

    operator fun get(key: K): V? = recent[key] ?: older.remove(key)?.also { put(it) }

    fun put(value: V) {
        if (recent.size >= capacity) {
            // The older values haven't been used since the recent ones filled up: forgotten.
            val forgotten = older
            older = recent
            recent = forgotten
            recent.clear()
        }
        recent[keyOf(value)] = value
    }

    fun clear() {
        recent.clear()
        older.clear()
    }
}
