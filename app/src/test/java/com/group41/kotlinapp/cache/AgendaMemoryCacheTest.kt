package com.group41.kotlinapp.cache

import com.group41.kotlinapp.AgendaActivity
import com.group41.kotlinapp.AgendaEnrollmentStatus
import com.group41.kotlinapp.cache.AgendaMemoryCache.Event
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Checks the cache's access ordering and invalidation without Android or Room.
 * The same fixtures exercise date grouping, sorting, and pending visibility.
 */

class AgendaMemoryCacheTest {
    private val january = YearMonth.of(2026, 1)
    private val early = activity("early", "2026-01-01T00:30:00Z")
    private val late = activity("late", "2026-01-01T22:00:00Z")
    private val pending = activity("pending", "2026-01-01T12:00:00Z", AgendaEnrollmentStatus.PENDING_JOIN)

    @Test
    fun revisitingNovemberMakesDecemberTheFirstMonthEvicted() {
        val events = mutableListOf<Event>()
        val cache = AgendaMemoryCache { events.add(it) }
        val november = january.minusMonths(2)
        val december = january.minusMonths(1)
        val february = january.plusMonths(1)
        fun month(month: YearMonth) = cache.getMonth(month, "a", listOf(early), true, ZoneOffset.UTC)

        val cachedNovember = month(november)
        val cachedDecember = month(december)
        val cachedJanuary = month(january)
        assertEquals(listOf(Event.MISS, Event.MISS, Event.MISS), events)

        // November was inserted first, but touching it again makes December the LRU entry.
        assertSame(cachedNovember, month(november))
        val cachedFebruary = month(february)
        assertEquals(
            listOf(Event.MISS, Event.MISS, Event.MISS, Event.HIT, Event.MISS, Event.EVICTION),
            events
        )
        assertSame(cachedNovember, month(november))
        assertSame(cachedJanuary, month(january))
        assertSame(cachedFebruary, month(february))
        assertEquals(listOf(Event.HIT, Event.HIT, Event.HIT), events.takeLast(3))

        assertNotSame(cachedDecember, month(december))
        assertEquals(listOf(Event.MISS, Event.EVICTION), events.takeLast(2))
    }

    @Test
    fun customCapacityBoundsEvenEmptyMonths() {
        val events = mutableListOf<Event>()
        val cache = AgendaMemoryCache(capacity = 1) { events.add(it) }
        fun month(offset: Long) = cache.getMonth(january.plusMonths(offset), "a", emptyList(), true, ZoneOffset.UTC)
        assertTrue(month(0).isEmpty())
        month(0)
        month(1)
        month(0)
        assertEquals(listOf(Event.MISS, Event.HIT, Event.MISS, Event.EVICTION, Event.MISS, Event.EVICTION), events)
    }

    @Test
    fun changedEnrollmentsVisibilityAndZoneInvalidateAllMonths() {
        val events = mutableListOf<Event>()
        val cache = AgendaMemoryCache { events.add(it) }
        val activities = listOf(late, pending, early)
        val day = january.atDay(1)
        assertEquals(listOf(early, pending, late), cache.getMonth(january, "a", activities, true, ZoneOffset.UTC)[day])
        val december = january.minusMonths(1)
        cache.getMonth(december, "a", activities, true, ZoneOffset.UTC)

        val filtered = cache.getMonth(january, "a", activities, false, ZoneOffset.UTC)
        assertEquals(listOf(early, late), filtered[day])
        assertSame(filtered, cache.getMonth(january, "a", activities, false, ZoneOffset.UTC))
        val updated = cache.getMonth(january, "a", listOf(early), false, ZoneOffset.UTC)
        assertEquals(listOf(early), updated[day])
        assertSame(updated, cache.getMonth(january, "a", listOf(early), false, ZoneOffset.UTC))

        val changedZone = ZoneOffset.ofHours(-5)
        assertTrue(cache.getMonth(january, "a", listOf(early), false, changedZone).isEmpty())
        val rebucketed = cache.getMonth(december, "a", listOf(early), false, changedZone)
        assertEquals(listOf(early), rebucketed[december.atEndOfMonth()])
        assertSame(rebucketed, cache.getMonth(december, "a", listOf(early), false, changedZone))
        assertEquals(
            listOf(
                Event.MISS, Event.MISS,
                Event.INVALIDATION, Event.MISS, Event.HIT,
                Event.INVALIDATION, Event.MISS, Event.HIT,
                Event.INVALIDATION, Event.MISS, Event.MISS, Event.HIT
            ),
            events
        )
    }

    @Test
    fun switchingAccountsDiscardsMonthsEvenWhenTheInputsOtherwiseMatch() {
        val events = mutableListOf<Event>()
        val cache = AgendaMemoryCache { events.add(it) }
        val first = cache.getMonth(january, "a", listOf(early), true, ZoneOffset.UTC)
        val second = cache.getMonth(january, "b", listOf(early), true, ZoneOffset.UTC)
        assertNotSame(first, second)
        val secondUpdated = cache.getMonth(january, "b", listOf(late), true, ZoneOffset.UTC)
        assertEquals(listOf(late), secondUpdated.values.single())
        assertSame(secondUpdated, cache.getMonth(january, "b", listOf(late), true, ZoneOffset.UTC))
        val firstAgain = cache.getMonth(january, "a", listOf(early), true, ZoneOffset.UTC)
        assertEquals(listOf(early), firstAgain.values.single())
        assertNotSame(first, firstAgain)
        cache.invalidate()
        assertTrue(cache.getMonth(january, null, emptyList(), true, ZoneOffset.UTC).isEmpty())
        assertEquals(
            listOf(
                Event.MISS, Event.INVALIDATION, Event.MISS, Event.INVALIDATION, Event.MISS, Event.HIT,
                Event.INVALIDATION, Event.MISS, Event.INVALIDATION, Event.MISS
            ),
            events
        )
    }

    private fun activity(
        id: String,
        start: String,
        status: AgendaEnrollmentStatus = AgendaEnrollmentStatus.CONFIRMED
    ) = AgendaActivity(id, id, ZonedDateTime.parse(start), null, status)
}
