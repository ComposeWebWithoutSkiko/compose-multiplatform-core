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
import androidx.compose.material3.ExperimentalMaterial3Api

@ExperimentalMaterial3Api
internal actual fun createCalendarModel(locale: CalendarLocale): CalendarModel =
    WebCalendarModel(locale)

/** A [CalendarModel] that computes the dates with the `Date` of the browser. */
internal class WebCalendarModel(locale: CalendarLocale) : PlatformCalendarModel(locale) {
    override val today: CalendarDate
        get() {
            val now = Date()
            return calendarDate(now.getFullYear(), now.getMonth() + 1, now.getDate())
        }

    override fun getCanonicalDate(timeInMillis: Long): CalendarDate {
        val date = Date(timeInMillis.toDouble())
        return calendarDate(date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate())
    }

    override fun getMonth(timeInMillis: Long): CalendarMonth =
        getMonth(Date(timeInMillis.toDouble()))

    override fun getMonth(year: Int, month: Int): CalendarMonth {
        val start = utcDate(year, month, 1)
        return CalendarMonth(
            year = year,
            month = month,
            numberOfDays = utcDate(year, month + 1, 0).getUTCDate(),
            daysFromStartOfWeekToFirstOfMonth = daysFromStartOfWeek(start.isoDayOfWeek),
            startUtcTimeMillis = start.getTime().toLong(),
        )
    }

    override fun getDayOfWeek(date: CalendarDate): Int =
        utcDate(date.year, date.month, date.dayOfMonth).isoDayOfWeek

    override fun plusMonths(from: CalendarMonth, addedMonthsCount: Int): CalendarMonth =
        getMonth(utcDate(from.year, from.month + addedMonthsCount, 1))

    private fun getMonth(date: Date): CalendarMonth =
        getMonth(date.getUTCFullYear(), date.getUTCMonth() + 1)

    private fun calendarDate(year: Int, month: Int, day: Int) =
        CalendarDate(year, month, day, utcDate(year, month, day).getTime().toLong())

    private val Date.isoDayOfWeek: Int
        get() = getUTCDay().let { if (it == 0) 7 else it }
}

/**
 * The start of a day in UTC. A [month] or a [day] out of range carries over to the neighboring
 * ones, as `setUTCFullYear` does: the day 0 of a month is the last day of the month before.
 */
internal fun utcDate(year: Int, month: Int, day: Int): Date =
    Date(0.0).also { it.setUTCFullYear(year, month - 1, day) }
