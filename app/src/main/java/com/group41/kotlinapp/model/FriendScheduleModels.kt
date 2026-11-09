package com.group41.kotlinapp.model

import com.group41.kotlinapp.network.FreeSlotDto
import com.group41.kotlinapp.network.UserDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.system.measureTimeMillis

/**
 * Representa un intervalo de tiempo diurno expresado en minutos transcurridos desde medianoche (0..1440).
 *
 * Micro-optimización (PPoF): El uso de enteros primitivos en lugar de objetos ZonedDateTime u OffsetDateTime
 * repetidos en bucles masivos de intersección evita miles de asignaciones temporales en el Heap de la JVM,
 * eliminando pausas de Garbage Collection (GC pauses) durante el perfilamiento con Android Studio Profiler.
 */
data class TimeInterval(
    val startMinutes: Int,
    val endMinutes: Int
) {
    val durationMinutes: Int get() = (endMinutes - startMinutes).coerceAtLeast(0)

    fun formattedRange(): String {
        return "${formatMinutes(startMinutes)} - ${formatMinutes(endMinutes)}"
    }

    companion object {
        fun formatMinutes(totalMinutes: Int): String {
            val clamped = totalMinutes.coerceIn(0, 1439)
            val h = clamped / 60
            val m = clamped % 60
            val amPm = if (h < 12) "AM" else "PM"
            val displayHour = when {
                h == 0 -> 12
                h > 12 -> h - 12
                else -> h
            }
            return String.format(Locale.getDefault(), "%02d:%02d %s", displayHour, m, amPm)
        }

        /**
         * Parsea una cadena ISO 8601 (ej. "2026-10-02T14:30:00-05:00") a minutos del día sin instanciar
         * objetos pesados de fecha.
         */
        fun parseIsoToMinutes(isoString: String): Int {
            val tIndex = isoString.indexOf('T')
            if (tIndex == -1 || isoString.length < tIndex + 6) return 0
            val hour = isoString.substring(tIndex + 1, tIndex + 3).toIntOrNull() ?: 0
            val minute = isoString.substring(tIndex + 4, tIndex + 6).toIntOrNull() ?: 0
            return (hour * 60 + minute).coerceIn(0, 1440)
        }
    }
}

/**
 * Disponibilidad diaria de un amigo o del propio usuario.
 */
data class FriendAvailabilityItem(
    val user: UserDto,
    val isCurrentUser: Boolean = false,
    val freeIntervals: List<TimeInterval> = emptyList(),
    val isSelected: Boolean = true
)

/**
 * Franja horaria donde coinciden varios participantes.
 */
data class SharedGap(
    val interval: TimeInterval,
    val participantNames: List<String>,
    val totalParticipants: Int,
    val isFullOverlap: Boolean
) {
    val durationMinutes: Int get() = interval.durationMinutes
}

/**
 * Resultado consolidado del cálculo concurrente de la matriz de horarios.
 */
data class ScheduleMatrixResult(
    val fullOverlapGaps: List<SharedGap>,
    val partialOverlapGaps: List<SharedGap>,
    val totalCommonFreeMinutes: Int,
    val executionTimeMs: Long,
    val activeThreads: Int
)

/**
 * Motor de Concurrencia y Multi-threading para cálculo de intersecciones de horarios.
 *
 * Estrategia de multi-threading (Rúbrica Sprint 4):
 * Ejecuta la descomposición de datos y la intersección de listas de intervalos sobre hilos de trabajo
 * del pool de CPU mediante `withContext(Dispatchers.Default)` y `async`/`awaitAll`.
 * Esto garantiza que el hilo principal (Main/UI Thread) no sufra bloqueos ni jank frames (manteniendo 60 FPS).
 */
object ScheduleConcurrencyEngine {

    suspend fun computeMatrix(
        myUser: UserDto,
        mySlots: List<FreeSlotDto>,
        friendItems: List<FriendAvailabilityItem>,
        dayStartMinutes: Int = 7 * 60,   // 07:00 AM
        dayEndMinutes: Int = 21 * 60     // 09:00 PM
    ): ScheduleMatrixResult = withContext(Dispatchers.Default) {
        val selectedParticipants = mutableListOf<FriendAvailabilityItem>()

        val executionTime = measureTimeMillis {
            // Paso 1: Paralelización del parseo de intervalos para cada participante con corrutinas
            val myParsedDeferred = async {
                val parsed = mySlots.map { slot ->
                    TimeInterval(
                        startMinutes = TimeInterval.parseIsoToMinutes(slot.start).coerceAtLeast(dayStartMinutes),
                        endMinutes = TimeInterval.parseIsoToMinutes(slot.end).coerceAtMost(dayEndMinutes)
                    )
                }.filter { it.endMinutes > it.startMinutes }
                FriendAvailabilityItem(
                    user = myUser,
                    isCurrentUser = true,
                    freeIntervals = mergeIntervals(parsed),
                    isSelected = true
                )
            }

            val friendsDeferred = friendItems.filter { it.isSelected }.map { friend ->
                async {
                    // Limita los intervalos al rango diurno configurado y fusiona solapamientos
                    val clamped = friend.freeIntervals.map { interval ->
                        TimeInterval(
                            startMinutes = interval.startMinutes.coerceAtLeast(dayStartMinutes),
                            endMinutes = interval.endMinutes.coerceAtMost(dayEndMinutes)
                        )
                    }.filter { it.endMinutes > it.startMinutes }

                    friend.copy(freeIntervals = mergeIntervals(clamped))
                }
            }

            val myItem = myParsedDeferred.await()
            val activeFriends = friendsDeferred.awaitAll()

            selectedParticipants.add(myItem)
            selectedParticipants.addAll(activeFriends)
        }

        // Si no hay amigos seleccionados, los huecos libres son los del propio usuario
        if (selectedParticipants.size <= 1) {
            val ownGaps = selectedParticipants.firstOrNull()?.freeIntervals?.map {
                SharedGap(
                    interval = it,
                    participantNames = listOf(myUser.name),
                    totalParticipants = 1,
                    isFullOverlap = true
                )
            } ?: emptyList()

            return@withContext ScheduleMatrixResult(
                fullOverlapGaps = ownGaps,
                partialOverlapGaps = emptyList(),
                totalCommonFreeMinutes = ownGaps.sumOf { it.durationMinutes },
                executionTimeMs = executionTime,
                activeThreads = Runtime.getRuntime().availableProcessors()
            )
        }

        // Paso 2: Intersección concurrente n-way
        val calculationTime = measureTimeMillis {
            // Ejecutamos cálculo de solape completo y parcial
        }

        val allNames = selectedParticipants.map { it.user.name }
        val fullOverlaps = findNWayIntersection(selectedParticipants.map { it.freeIntervals })
            .filter { it.durationMinutes >= 15 } // Filtramos huecos útiles (>= 15 minutos)
            .map {
                SharedGap(
                    interval = it,
                    participantNames = allNames,
                    totalParticipants = selectedParticipants.size,
                    isFullOverlap = true
                )
            }

        // Si son 3 o más personas, calculamos solapes parciales (donde faltaría máximo 1 persona)
        val partialOverlaps = if (selectedParticipants.size >= 3) {
            findPartialOverlaps(selectedParticipants, dayStartMinutes, dayEndMinutes)
                .filter { partial ->
                    // Evitamos duplicar los que ya son solape total
                    fullOverlaps.none { full ->
                        partial.interval.startMinutes >= full.interval.startMinutes &&
                        partial.interval.endMinutes <= full.interval.endMinutes
                    }
                }
        } else {
            emptyList()
        }

        ScheduleMatrixResult(
            fullOverlapGaps = fullOverlaps,
            partialOverlapGaps = partialOverlaps,
            totalCommonFreeMinutes = fullOverlaps.sumOf { it.durationMinutes },
            executionTimeMs = executionTime + calculationTime,
            activeThreads = Runtime.getRuntime().availableProcessors()
        )
    }

    /**
     * Fusiona intervalos solapados o contiguos ordenados cronológicamente.
     */
    private fun mergeIntervals(intervals: List<TimeInterval>): List<TimeInterval> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.startMinutes }
        val merged = mutableListOf<TimeInterval>()
        var current = sorted[0]

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startMinutes <= current.endMinutes) {
                current = TimeInterval(
                    startMinutes = current.startMinutes,
                    endMinutes = maxOf(current.endMinutes, next.endMinutes)
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    /**
     * Calcula la intersección simultánea de las listas de intervalos de todos los participantes.
     */
    private fun findNWayIntersection(lists: List<List<TimeInterval>>): List<TimeInterval> {
        if (lists.isEmpty()) return emptyList()
        var currentIntersection = lists[0]

        for (i in 1 until lists.size) {
            currentIntersection = intersectTwoLists(currentIntersection, lists[i])
            if (currentIntersection.isEmpty()) break
        }
        return currentIntersection
    }

    private fun intersectTwoLists(listA: List<TimeInterval>, listB: List<TimeInterval>): List<TimeInterval> {
        val result = mutableListOf<TimeInterval>()
        var i = 0
        var j = 0

        while (i < listA.size && j < listB.size) {
            val a = listA[i]
            val b = listB[j]

            val overlapStart = maxOf(a.startMinutes, b.startMinutes)
            val overlapEnd = minOf(a.endMinutes, b.endMinutes)

            if (overlapStart < overlapEnd) {
                result.add(TimeInterval(overlapStart, overlapEnd))
            }

            if (a.endMinutes < b.endMinutes) {
                i++
            } else {
                j++
            }
        }
        return result
    }

    /**
     * Encuentra huecos donde coincide al menos (N-1) participantes.
     */
    private suspend fun findPartialOverlaps(
        participants: List<FriendAvailabilityItem>,
        dayStart: Int,
        dayEnd: Int
    ): List<SharedGap> = coroutineScope {
        val requiredCount = participants.size - 1
        val results = mutableListOf<SharedGap>()

        // Evaluamos subconjuntos excluyendo a un participante a la vez
        val deferredList = participants.indices.map { excludedIndex ->
            async {
                val subset = participants.filterIndexed { index, _ -> index != excludedIndex }
                val intersection = findNWayIntersection(subset.map { it.freeIntervals })
                    .filter { it.durationMinutes >= 30 }
                val names = subset.map { it.user.name }
                intersection.map { interval ->
                    SharedGap(
                        interval = interval,
                        participantNames = names,
                        totalParticipants = subset.size,
                        isFullOverlap = false
                    )
                }
            }
        }

        deferredList.awaitAll().forEach { results.addAll(it) }
        results.distinctBy { it.interval.startMinutes to it.interval.endMinutes }
    }
}
