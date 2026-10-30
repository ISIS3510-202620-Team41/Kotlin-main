package com.group41.kotlinapp.repository

import com.group41.kotlinapp.data.local.dao.PendingActionDao
import com.group41.kotlinapp.data.local.entity.PendingActionEntity
import com.group41.kotlinapp.model.JoinPayload
import com.group41.kotlinapp.model.PendingActionType
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba las reglas del buzon de acciones pendientes.
 *
 * Estos tests corren en el computador, sin emulador ni telefono. Eso es posible porque el
 * repositorio no depende de Android: solo habla con la interfaz PendingActionDao. Aqui se
 * le pasa una version falsa de esa interfaz, que guarda las filas en un mapa en memoria en
 * vez de en SQLite.
 *
 * Esa es la ventaja de que el repositorio reciba el DAO desde fuera en vez de crearselo:
 * permite cambiarlo por uno de mentira para las pruebas. Si el repositorio abriera la base
 * de datos el mismo, estos tests necesitarian un dispositivo.
 */
class PendingActionRepositoryTest {

    /**
     * Un DAO de mentira que guarda todo en memoria.
     *
     * Imita lo unico que importa del de verdad: rechazar los ids repetidos y devolver las
     * acciones en el orden en que se crearon.
     */
    private class FakeDao : PendingActionDao {
        val rows = linkedMapOf<String, PendingActionEntity>()

        override suspend fun getPendingActions(userId: String): List<PendingActionEntity> =
            rows.values
                .filter { it.userId == userId }
                .sortedWith(compareBy({ it.createdAt }, { it.id }))

        override suspend fun insertAction(action: PendingActionEntity) {
            rows[action.id] = action
        }

        override suspend fun insertIfAbsent(action: PendingActionEntity): Long {
            // -1 es lo que devuelve Room cuando la fila ya existia y no inserto nada.
            if (rows.containsKey(action.id)) return -1L
            rows[action.id] = action
            return rows.size.toLong()
        }

        override suspend fun updateAction(action: PendingActionEntity) {
            rows[action.id] = action
        }

        override suspend fun deleteAction(id: String, userId: String) {
            rows.remove(id)
        }

        override suspend fun clearUserActions(userId: String) {
            rows.values.removeAll { it.userId == userId }
        }
    }

    private val dao = FakeDao()
    private val repo = PendingActionRepository(dao)

    private fun payload(activityId: String) = JoinPayload(activityId, "rec-1", 30)

    @Test
    fun `una inscripcion sin internet queda guardada`() = runTest {
        assertTrue(repo.enqueueJoin("user-1", payload("act-1")))

        val pendientes = repo.pendingFor("user-1")
        assertEquals(1, pendientes.size)
        assertEquals(PendingActionType.JOIN_ACTIVITY, pendientes.first().type)
    }

    @Test
    fun `tocar dos veces el boton no encola la inscripcion dos veces`() = runTest {
        // El caso real: sin internet el usuario toca "Unirme", no ve respuesta inmediata y
        // vuelve a tocar. Son dos toques pero una sola intencion. Si se encolaran las dos,
        // al volver la conexion se mandarian dos inscripciones a la misma actividad.
        assertTrue(repo.enqueueJoin("user-1", payload("act-1")))
        assertFalse(repo.enqueueJoin("user-1", payload("act-1")))

        assertEquals(1, repo.pendingFor("user-1").size)
    }

    @Test
    fun `dos usuarios del mismo telefono no se pisan`() = runTest {
        // Pasa cuando alguien presta el telefono, o cuando se cierra sesion y entra otro.
        // Las inscripciones de cada uno son acciones distintas aunque sean a la misma
        // actividad, y cada quien solo debe ver las suyas.
        repo.enqueueJoin("user-1", payload("act-1"))
        repo.enqueueJoin("user-2", payload("act-1"))

        assertEquals(1, repo.pendingFor("user-1").size)
        assertEquals(1, repo.pendingFor("user-2").size)
    }

    @Test
    fun `lo que ya se mando sale de la cola`() = runTest {
        repo.enqueueJoin("user-1", payload("act-1"))
        val accion = repo.pendingFor("user-1").first()

        repo.markSynced(accion)

        assertTrue(repo.pendingFor("user-1").isEmpty())
    }

    @Test
    fun `un fallo pasajero deja la accion en la cola y cuenta el intento`() = runTest {
        repo.enqueueJoin("user-1", payload("act-1"))
        val accion = repo.pendingFor("user-1").first()

        assertTrue(repo.recordFailure(accion))

        val despues = repo.pendingFor("user-1").first()
        assertEquals(1, despues.retryCount)
    }

    @Test
    fun `despues de demasiados intentos la accion se descarta`() = runTest {
        // Sin este limite, una accion que nunca va a funcionar se quedaria reintentando
        // para siempre: gastando bateria y datos del usuario, y bloqueando la cola para
        // las que vienen detras.
        repo.enqueueJoin("user-1", payload("act-1"))

        var sigueEnLaCola = true
        var intentos = 0
        while (sigueEnLaCola && intentos < 20) {
            val accion = repo.pendingFor("user-1").firstOrNull() ?: break
            sigueEnLaCola = repo.recordFailure(accion)
            intentos++
        }

        assertEquals(PendingActionRepository.MAX_RETRIES, intentos)
        assertTrue(repo.pendingFor("user-1").isEmpty())
    }

    @Test
    fun `lo que el backend rechaza se descarta sin gastar reintentos`() = runTest {
        // La actividad se lleno, o el usuario ya estaba inscrito. Mandarlo otra vez daria
        // exactamente la misma respuesta, asi que insistir cinco veces no tiene sentido.
        repo.enqueueJoin("user-1", payload("act-1"))
        val accion = repo.pendingFor("user-1").first()

        repo.discard(accion)

        assertTrue(repo.pendingFor("user-1").isEmpty())
    }

    @Test
    fun `las acciones salen en el orden en que el usuario las hizo`() = runTest {
        // El orden importa de cara al futuro: si se encolan "crear actividad" y luego
        // "unirse a esa actividad", mandarlas al reves fallaria porque la actividad
        // todavia no existiria en el servidor.
        dao.insertAction(fila(id = "b", userId = "user-1", createdAt = 200))
        dao.insertAction(fila(id = "a", userId = "user-1", createdAt = 100))

        val ids = repo.pendingFor("user-1").map { it.id }

        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun `una fila corrupta se salta sin romper el resto de la cola`() = runTest {
        // Si una fila mal guardada tumbara la lectura entera, una sola accion estropeada
        // impediria que se enviara ninguna otra. Es preferible perder esa y salvar el resto.
        dao.insertAction(fila(id = "rota", userId = "user-1", createdAt = 100, payload = "esto no es json"))
        repo.enqueueJoin("user-1", payload("act-1"))

        val pendientes = repo.pendingFor("user-1")

        assertEquals(1, pendientes.size)
        assertEquals(
            "act-1",
            JoinPayload.fromJson(pendientes.first().payload)?.activityId
        )
    }

    @Test
    fun `el identificador de una inscripcion depende del usuario y la actividad`() = runTest {
        // Esta es la pieza que hace imposible el duplicado: el id no es aleatorio, se
        // calcula. Dos toques iguales producen el mismo id y la base de datos rechaza
        // el segundo ella sola.
        val primero = PendingActionRepository.joinActionId("user-1", "act-1")
        val segundo = PendingActionRepository.joinActionId("user-1", "act-1")
        val otroUsuario = PendingActionRepository.joinActionId("user-2", "act-1")

        assertEquals(primero, segundo)
        assertFalse(primero == otroUsuario)
    }

    private fun fila(
        id: String,
        userId: String,
        createdAt: Long,
        payload: String = JSONObject().put("activityId", "x").toString()
    ) = PendingActionEntity(
        id = id,
        userId = userId,
        actionType = PendingActionType.JOIN_ACTIVITY.name,
        payload = payload,
        createdAt = createdAt,
        retryCount = 0
    )
}
