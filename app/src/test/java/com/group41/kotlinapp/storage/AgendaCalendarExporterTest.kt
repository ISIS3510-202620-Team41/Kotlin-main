package com.group41.kotlinapp.storage

import com.group41.kotlinapp.AgendaActivity
import com.group41.kotlinapp.AgendaEnrollmentStatus
import java.time.Instant
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaCalendarExporterTest {
    private val start = ZonedDateTime.parse("2026-11-25T09:00:00-05:00")
    private val confirmed = AgendaActivity(
        activityId = "confirmed",
        title = "Taller, práctica; A\\B\n📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚",
        startsAt = start,
        endsAt = start.plusHours(1),
        enrollmentStatus = AgendaEnrollmentStatus.CONFIRMED,
        location = "Salón, 204"
    )

    @Test
    fun exportsOnlyConfirmedEventsWithEscapedTextAndUtcTimes() {
        val calendar = checkNotNull(
            AgendaCalendarExporter.serialize(
                listOf(
                    confirmed,
                    confirmed.copy(title = "Pending join", enrollmentStatus = AgendaEnrollmentStatus.PENDING_JOIN),
                    confirmed.copy(
                        title = "Pending cancellation",
                        enrollmentStatus = AgendaEnrollmentStatus.PENDING_CANCELLATION
                    )
                ),
                Instant.parse("2026-11-24T12:00:00Z")
            )
        )
        assertTrue(calendar.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"))
        assertTrue(calendar.endsWith("END:VEVENT\r\nEND:VCALENDAR\r\n"))
        assertTrue(calendar.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        val lines = calendar.replace("\r\n ", "").split("\r\n")
        assertEquals(1, lines.count { it == "BEGIN:VEVENT" })
        assertTrue("DTSTAMP:20261124T120000Z" in lines)
        assertTrue("DTSTART:20261125T140000Z" in lines)
        assertTrue("DTEND:20261125T150000Z" in lines)
        assertTrue("SUMMARY:Taller\\, práctica\\; A\\\\B\\n📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚📚" in lines)
        assertTrue("LOCATION:Salón\\, 204" in lines)
    }

    @Test
    fun omitsInvalidOrMissingEndTimesAndReturnsNullWithoutConfirmedEvents() {
        for (end in listOf(null, start.minusHours(1))) {
            val calendar = checkNotNull(AgendaCalendarExporter.serialize(listOf(confirmed.copy(endsAt = end))))
            assertFalse(calendar.contains("DTEND:"))
        }
        assertNull(
            AgendaCalendarExporter.serialize(
                listOf(confirmed.copy(enrollmentStatus = AgendaEnrollmentStatus.PENDING_JOIN))
            )
        )
    }
}
