/*
 * Copyright 2024 The Android Open Source Project
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

@file:OptIn(ExperimentalTime::class)

package androidx.compose.material3.internal

import androidx.compose.material3.CalendarLocale
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

@ExperimentalMaterial3Api
internal actual fun createCalendarModel(locale: CalendarLocale): CalendarModel =
    KotlinxDatetimeCalendarModel(locale)

internal class KotlinxDatetimeCalendarModel(
    locale: CalendarLocale
) : PlatformCalendarModel(locale) {
    override val today: CalendarDate
        get() {
            val localDate = Clock.System.now().toLocalDateTime(systemTZ)
            return CalendarDate(
                year = localDate.year,
                month = localDate.month.number,
                dayOfMonth = localDate.day,
                utcTimeMillis = localDate.date
                    .atTime(Midnight)
                    .toInstant(TimeZone.UTC)
                    .toEpochMilliseconds()
            )
        }

    private val systemTZ
        get() = TimeZone.currentSystemDefault()

    override fun getCanonicalDate(timeInMillis: Long): CalendarDate {
        return Instant
            .fromEpochMilliseconds(timeInMillis)
            .toLocalDateTime(TimeZone.UTC)
            .date
            .atStartOfDayIn(TimeZone.UTC)
            .toCalendarDate(TimeZone.UTC)
    }

    override fun getMonth(timeInMillis: Long): CalendarMonth {
        return Instant
            .fromEpochMilliseconds(timeInMillis)
            .toCalendarMonth(TimeZone.UTC)
    }

    override fun getMonth(year: Int, month: Int): CalendarMonth {
        val instant = LocalDate(
            year = year,
            month = month,
            day = 1
        ).atTime(Midnight)
            .toInstant(TimeZone.UTC)

        return getMonth(instant.toEpochMilliseconds())
    }

    override fun getDayOfWeek(date: CalendarDate): Int {
        return LocalDate(
            year = date.year,
            month = date.month,
            day = date.dayOfMonth
        ).dayOfWeek.isoDayNumber
    }

    override fun plusMonths(from: CalendarMonth, addedMonthsCount: Int): CalendarMonth {
        return Instant
            .fromEpochMilliseconds(from.startUtcTimeMillis)
            .toLocalDateTime(TimeZone.UTC)
            .date
            .plus(DatePeriod(months = addedMonthsCount))
            .atTime(Midnight)
            .toInstant(TimeZone.UTC)
            .toCalendarMonth(TimeZone.UTC)
    }

    private fun Instant.toCalendarMonth(
        timeZone : TimeZone
    ) : CalendarMonth {

        val dateTime = toLocalDateTime(timeZone)

        val monthStart = LocalDate(
            year = dateTime.year,
            month = dateTime.month,
            day = 1
        )

        return CalendarMonth(
            year = dateTime.year,
            month = dateTime.month.number,
            numberOfDays = dateTime.month
                .numberOfDays(dateTime.year.isLeapYear()),
            daysFromStartOfWeekToFirstOfMonth =
                daysFromStartOfWeek(monthStart.dayOfWeek.isoDayNumber),
            startUtcTimeMillis = monthStart
                .atTime(Midnight)
                .toInstant(TimeZone.UTC)
                .toEpochMilliseconds()
        )
    }
}

internal fun Instant.toCalendarDate(
    timeZone : TimeZone
) : CalendarDate {

    val dateTime = toLocalDateTime(timeZone)

    return CalendarDate(
        year = dateTime.year,
        month = dateTime.month.number,
        dayOfMonth = dateTime.day,
        utcTimeMillis = toEpochMilliseconds()
    )
}

internal val Midnight = LocalTime(0,0)

private fun Int.isLeapYear() = this % 4 == 0 && (this % 100 != 0 || this % 400 == 0)

private fun Month.numberOfDays(isLeap : Boolean) : Int {
    return when(this){
        Month.FEBRUARY -> if (isLeap) 29 else 28
        Month.JANUARY,
        Month.MARCH,
        Month.MAY,
        Month.JULY,
        Month.AUGUST,
        Month.OCTOBER,
        Month.DECEMBER -> 31

        else -> 30
    }
}
