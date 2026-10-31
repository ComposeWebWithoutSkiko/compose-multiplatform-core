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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 2024-02-29T00:00:00Z. */
private const val LeapDayMillis = 1709164800000L

/** The cases of [CalendarModelTest] that only the model of the browser can get wrong. */
class WebCalendarModelTest {
    private val locale = calendarLocale("en", "US")
    private val model = WebCalendarModel(locale)

    private fun date(year: Int, month: Int, day: Int, utcTimeMillis: Long) =
        CalendarDate(year, month, day, utcTimeMillis)

    @Test
    fun monthsHaveTheirNumberOfDays() {
        assertEquals(29, model.getMonth(2024, 2).numberOfDays)
        assertEquals(29, model.getMonth(2000, 2).numberOfDays)
        assertEquals(28, model.getMonth(1900, 2).numberOfDays)
        assertEquals(28, model.getMonth(2023, 2).numberOfDays)
        assertEquals(30, model.getMonth(2023, 4).numberOfDays)
        assertEquals(31, model.getMonth(2023, 12).numberOfDays)
    }

    @Test
    fun datesAreOnesOfUtcOnBothSidesOfMidnight() {
        // Local getters would give another day in time zones ahead of or behind UTC.
        assertEquals(date(1970, 1, 1, 0), model.getCanonicalDate(0))
        assertEquals(date(1969, 12, 31, -MillisecondsIn24Hours), model.getCanonicalDate(-1))
        assertEquals(date(2022, 1, 1, January2022Millis), model.getCanonicalDate(January2022Millis))
        assertEquals(
            date(2024, 2, 29, LeapDayMillis),
            model.getCanonicalDate(LeapDayMillis + MillisecondsIn24Hours - 1),
        )
    }

    @Test
    fun monthsAreOnesOfUtcOnBothSidesOfMidnight() {
        val january = model.getMonth(January2022Millis)
        assertEquals(2022, january.year)
        assertEquals(1, january.month)
        assertEquals(January2022Millis, january.startUtcTimeMillis)
        assertEquals(2, model.getMonth(LeapDayMillis + MillisecondsIn24Hours - 1).month)
    }

    @Test
    fun yearsOfTwoDigitsAreNotOnesOfThe1900s() {
        val month = model.getMonth(50, 1)
        val date = model.getCanonicalDate(month.startUtcTimeMillis)
        assertEquals(50, date.year)
        assertEquals(1, date.month)
        assertEquals(1, date.dayOfMonth)
    }

    @Test
    fun monthsAreAddedAndSubtractedAcrossYears() {
        val november = model.getMonth(2023, 11)
        val february = model.plusMonths(november, 3)
        assertEquals(2024, february.year)
        assertEquals(2, february.month)
        assertEquals(november, model.minusMonths(february, 3))
    }

    @Test
    fun daysOfTheWeekAreOnesOfIso8601() {
        assertEquals(4, model.getDayOfWeek(date(2024, 2, 29, LeapDayMillis)))
        assertEquals(7, model.getDayOfWeek(date(2023, 1, 1, 1672531200000L)))
    }

    @Test
    fun aMonthStartsAtItsDayOfTheWeek() {
        // 2022-01-01 was a Saturday. The first day of the week depends on the browser.
        val expected = (6 - model.firstDayOfWeek).mod(DaysInWeek)
        assertEquals(expected, model.getMonth(2022, 1).daysFromStartOfWeekToFirstOfMonth)
    }

    @Test
    fun todayIsTheLocalDateAtMidnightOfUtc() {
        val now = Date()
        val today = model.today
        assertEquals(now.getFullYear(), today.year)
        assertEquals(now.getMonth() + 1, today.month)
        assertEquals(now.getDate(), today.dayOfMonth)
        assertEquals(0, today.utcTimeMillis % MillisecondsIn24Hours)
    }

    @Test
    fun datesAreFormattedWithNumericPatterns() {
        assertEquals("20240229", model.formatWithPattern(LeapDayMillis, "yyyyMMdd", locale))
        assertEquals("29022024", model.formatWithPattern(LeapDayMillis, "ddMMyyyy", locale))
        assertEquals("02/29/2024", model.formatWithPattern(LeapDayMillis, "MM/dd/yyyy", locale))
        assertEquals("01012022", model.formatWithPattern(January2022Millis, "MMddyyyy", locale))
        assertEquals("1/1/2022", model.formatWithPattern(January2022Millis, "M/d/yyyy", locale))
    }

    @Test
    fun datesAreParsedWithNumericPatterns() {
        assertEquals(date(2024, 2, 29, LeapDayMillis), model.parse("20240229", "yyyyMMdd", locale))
        assertEquals(
            date(2024, 2, 29, LeapDayMillis),
            model.parse("02.29.2024", "MM.dd.yyyy", locale),
        )
        assertEquals(
            date(2022, 1, 1, January2022Millis),
            model.parse("1/1/2022", "M/d/yyyy", locale),
        )
    }

    @Test
    fun datesThatDoNotExistAreNotParsed() {
        assertNull(model.parse("20230229", "yyyyMMdd", locale))
        assertNull(model.parse("20220431", "yyyyMMdd", locale))
        assertNull(model.parse("20221301", "yyyyMMdd", locale))
        // Both would carry over to a date of the month or the year before.
        assertNull(model.parse("20220001", "yyyyMMdd", locale))
        assertNull(model.parse("20220100", "yyyyMMdd", locale))
    }

    @Test
    fun textThatDoesNotMatchThePatternIsNotParsed() {
        assertNull(model.parse("2022010", "yyyyMMdd", locale))
        assertNull(model.parse("202201011", "yyyyMMdd", locale))
        assertNull(model.parse("2022a101", "yyyyMMdd", locale))
        assertNull(model.parse("2022/01-01", "yyyy/MM/dd", locale))
        assertNull(model.parse("0101", "MMdd", locale))
        assertNull(model.parse("99999999999/1/1", "y/M/d", locale))
    }

    @Test
    fun onlyAsciiDigitsAreParsed() {
        val fullWidth = "20220101".map { Char(it.code - '0'.code + 0xFF10) }.joinToString("")
        val arabicIndic = "20220101".map { Char(it.code - '0'.code + 0x0660) }.joinToString("")
        assertNull(model.parse(fullWidth, "yyyyMMdd", locale))
        assertNull(model.parse(arabicIndic, "yyyyMMdd", locale))
    }
}
