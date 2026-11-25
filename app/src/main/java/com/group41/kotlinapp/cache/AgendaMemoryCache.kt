package com.group41.kotlinapp.cache

import com.group41.kotlinapp.AgendaActivity
import com.group41.kotlinapp.AgendaEnrollmentStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/*
 * Keeps the recently viewed agenda months in memory. Each cached month groups
 * visible activities by their local calendar date and sorts each day by start
 * time. All entries share one account, enrollment snapshot, visibility setting,
 * and display time zone. Changing any of those inputs invalidates the cache.
 */

/** Bounded, access-ordered storage for processed agenda months. */
internal class AgendaMemoryCache(
    private val capacity: Int = 3,
    private val onEvent: (Event) -> Unit = {}
) {
    enum class Event { HIT, MISS, EVICTION, INVALIDATION }

    private data class Source(
        val accountId: String?,
        val activities: List<AgendaActivity>,
        val showPending: Boolean,
        val displayZone: ZoneId
    )

    init {
        require(capacity > 0) { "Agenda cache capacity must be positive" }
    }

    private var source: Source? = null
    private val months = LinkedHashMap<YearMonth, Map<LocalDate, List<AgendaActivity>>>(capacity, 0.75f, true)

    /**
     * Returns a month's prepared activities for the current source. A successful
     * lookup moves that month to the end of the map, away from the eviction end.
     */
    @Synchronized
    fun getMonth(
        month: YearMonth,
        accountId: String?,
        activities: List<AgendaActivity>,
        showPending: Boolean,
        displayZone: ZoneId
    ): Map<LocalDate, List<AgendaActivity>> {
        val inputs = Source(accountId, activities, showPending, displayZone)
        if (source != inputs) {
            invalidate()
            source = inputs.copy(activities = activities.toList())
        }
        months[month]?.let {
            onEvent(Event.HIT)
            return it
        }

        onEvent(Event.MISS)
        val grouped = mutableMapOf<LocalDate, MutableList<AgendaActivity>>()
        for (activity in activities) {
            if (!showPending && activity.enrollmentStatus != AgendaEnrollmentStatus.CONFIRMED) continue
            val date = activity.startsAt.withZoneSameInstant(displayZone).toLocalDate()
            if (YearMonth.from(date) == month) grouped.getOrPut(date) { mutableListOf() }.add(activity)
        }
        val prepared = grouped.mapValues { (_, day) -> day.sortedBy { it.startsAt.toInstant() } }
        months[month] = prepared
        if (months.size > capacity) {
            // The first key belongs to the month accessed least recently.
            months.remove(months.keys.first())
            onEvent(Event.EVICTION)
        }
        return prepared
    }

    /** Releases all processed data, including the last account's enrollment snapshot. */
    @Synchronized
    fun invalidate() {
        if (source != null) {
            source = null
            months.clear()
            onEvent(Event.INVALIDATION)
        }
    }
}
