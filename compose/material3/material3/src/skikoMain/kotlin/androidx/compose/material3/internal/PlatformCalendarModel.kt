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

package androidx.compose.material3.internal

import androidx.compose.material3.CalendarLocale

/** The part of a [CalendarModel] that [PlatformDateFormat] provides. */
internal abstract class PlatformCalendarModel(locale: CalendarLocale) : CalendarModel(locale) {
    private val platformDateFormat = PlatformDateFormat(locale)

    override val firstDayOfWeek: Int
        get() = platformDateFormat.firstDayOfWeek

    override val weekdayNames: List<Pair<String, String>>
        get() = platformDateFormat.weekdayNames

    override fun getDateInputFormat(locale: CalendarLocale): DateInputFormat {
        return platformDateFormat
            .getDateInputFormat()
    }

    override fun getMonth(date: CalendarDate): CalendarMonth {
        return getMonth(date.utcTimeMillis)
    }

    override fun minusMonths(from: CalendarMonth, subtractedMonthsCount: Int): CalendarMonth {
        return plusMonths(from, -subtractedMonthsCount)
    }

    override fun formatWithPattern(
        utcTimeMillis: Long,
        pattern: String,
        locale: CalendarLocale
    ): String {
        return platformDateFormat.formatWithPattern(utcTimeMillis, pattern, formatterCache)
    }

    override fun parse(date: String, pattern: String, locale: CalendarLocale): CalendarDate? {
        return platformDateFormat.parse(date, pattern, locale, formatterCache)
    }

    /** The days from the start of the week to a day of the week, given as in ISO 8601. */
    protected fun daysFromStartOfWeek(isoDayOfWeek: Int): Int =
        (isoDayOfWeek - firstDayOfWeek).let { if (it >= 0) it else 7 + it }
}
