package com.github.ilife798.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskScheduleTest {
    @Test
    fun defaultsToDisabledWithSixOClock() {
        val schedule = TaskSchedule()
        assertFalse(schedule.enabled)
        assertEquals(6, schedule.hour)
        assertEquals(0, schedule.minute)
        assertTrue(schedule.weekdays.isEmpty())
    }

    @Test
    fun normalizedClampsTimeAndSortsWeekdays() {
        val schedule = TaskSchedule(hour = 25, minute = -3, weekdays = listOf(7, 1, 1, 9, 0)).normalized
        assertEquals(23, schedule.hour)
        assertEquals(0, schedule.minute)
        assertEquals(listOf(1, 7), schedule.weekdays)
    }

    @Test
    fun timeTextPadsBothFields() {
        assertEquals("06:05", TaskSchedule(hour = 6, minute = 5).timeText)
        assertEquals("23:59", TaskSchedule(hour = 23, minute = 59).timeText)
    }

    @Test
    fun weekdayTextFallsBackToEveryDay() {
        assertEquals("每天", TaskSchedule().weekdayText)
        assertEquals("周一、周五", TaskSchedule(weekdays = listOf(1, 5)).weekdayText)
    }

    @Test
    fun emptyWeekdaysMeansEveryDay() {
        val schedule = TaskSchedule()
        (1..7).forEach { day -> assertTrue(schedule.isActiveOn(day)) }
    }

    @Test
    fun selectedWeekdaysOnlyMatchThemselves() {
        val schedule = TaskSchedule(weekdays = listOf(1, 3))
        assertTrue(schedule.isActiveOn(1))
        assertFalse(schedule.isActiveOn(2))
        assertTrue(schedule.isActiveOn(3))
        assertFalse(schedule.isActiveOn(7))
    }

    @Test
    fun codecRoundTripsAndNormalizes() {
        val schedule = TaskSchedule(enabled = true, hour = 8, minute = 30, weekdays = listOf(5, 2))
        val decoded = decodeTaskSchedule(encodeTaskSchedule(schedule))
        assertEquals(TaskSchedule(enabled = true, hour = 8, minute = 30, weekdays = listOf(2, 5)), decoded)
    }

    @Test
    fun codecFallsBackOnMissingOrBrokenData() {
        assertEquals(TaskSchedule(), decodeTaskSchedule(null))
        assertEquals(TaskSchedule(), decodeTaskSchedule(""))
        assertEquals(TaskSchedule(), decodeTaskSchedule("not json"))
    }

    @Test
    fun codecNormalizesOutOfRangeFieldsFromDisk() {
        assertEquals(TaskSchedule(hour = 23), decodeTaskSchedule("""{"hour":999}"""))
    }
}
