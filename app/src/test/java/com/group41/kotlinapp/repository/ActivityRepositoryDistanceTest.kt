package com.group41.kotlinapp.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityRepositoryDistanceTest {

    private val repository = ActivityRepository()

    @Test
    fun testSameCoordinatesReturnZeroMeters() {
        val distance = repository.calculateHaversineDistanceMeters(
            lat1 = 4.6018,
            lon1 = -74.0661,
            lat2 = 4.6018,
            lon2 = -74.0661
        )
        assertEquals(0, distance)
    }

    @Test
    fun testZeroCoordinatesReturnZero() {
        val distance = repository.calculateHaversineDistanceMeters(0.0, 0.0, 4.60, -74.06)
        assertEquals(0, distance)
    }

    @Test
    fun testRealWorldDistanceUniAndesToPlazaBolivar() {
        // Universidad de los Andes (Mario Laserna): ~ 4.6018, -74.0661
        // Plaza de Bolívar (Bogotá): ~ 4.5981, -74.0758
        // Distancia geográfica en línea recta: aprox 1100 - 1300 metros
        val meters = repository.calculateHaversineDistanceMeters(
            lat1 = 4.6018,
            lon1 = -74.0661,
            lat2 = 4.5981,
            lon2 = -74.0758
        )

        assertTrue("Distancia esperada entre 1000m y 1300m, pero fue $meters", meters in 1000..1300)
    }
}
