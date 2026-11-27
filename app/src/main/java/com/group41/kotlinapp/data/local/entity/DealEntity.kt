package com.group41.kotlinapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un cupon comercial del catalogo de CampusDeals, guardado en el telefono.
 *
 * Que es un cupon aqui: un comercio cercano al campus ofrece un descuento a los
 * estudiantes. Ese es el modelo de ingresos de la app: los comercios pagan por aparecer.
 *
 * NOTA SOBRE LOS CAMPOS: el issue #44 se abrio sin descripcion, asi que este modelo es una
 * decision tomada al implementarlo, no un requisito heredado. Si el equipo tenia otra idea
 * de que es un cupon, esto es lo que hay que cambiar primero.
 *
 * POR QUE SE GUARDA EN LA BASE LOCAL
 *
 * El titulo del issue pide "offline persistence", y aqui tiene un motivo concreto que no
 * es solo "que se vea sin internet": un cupon se usa de pie en la caja de un local, con el
 * telefono en la mano y a menudo sin senal dentro del edificio. Es justo el momento en que
 * la red falla y justo el momento en que el dato tiene que estar. Un catalogo que solo
 * existe mientras hay conexion no sirve para lo que el usuario necesita.
 */
@Entity(
    tableName = "deals",
    indices = [
        // Se filtra por categoria en la pantalla, asi que el indice evita recorrer la
        // tabla entera cada vez que el usuario toca un chip de filtro.
        Index(value = ["category"]),
        // Y por vigencia, para esconder los vencidos sin mirarlos todos.
        Index(value = ["validUntil"])
    ]
)
data class DealEntity(

    /**
     * El id que da el servidor.
     *
     * Es `String` y no autogenerado, al contrario que el historial de busquedas, porque
     * este dato nace en el backend: el telefono guarda una copia, no el original. Si fuera
     * autogenerado, cada descarga crearia filas nuevas en vez de actualizar las que hay.
     */
    @PrimaryKey
    val id: String,

    /** El comercio que ofrece el cupon. */
    val merchant: String,

    /** Lo que ofrece, en una linea. */
    val title: String,

    val description: String?,

    /** El descuento, en porcentaje. */
    val discountPercent: Int,

    /** Comida, papeleria, deporte... Se usa para filtrar. */
    val category: String,

    /**
     * Cuantos minutos toma aprovechar el cupon, aproximadamente.
     *
     * ESTE ES EL CAMPO CLAVE DE LA BQ-11.
     *
     * La pregunta es si los cupones se redimen mas durante los huecos entre clases. Para
     * responderla no basta con saber que el estudiante esta libre: hay que saber si le
     * alcanza el tiempo. Un almuerzo con descuento no sirve en un hueco de quince
     * minutos, y ofrecerselo ahi es lo mismo que no ofrecerle nada.
     *
     * Cruzando este numero con los minutos que quedan del hueco (ver BreakDetector) la
     * pantalla puede ordenar primero lo que de verdad alcanza a usar, y la BQ puede
     * distinguir "no lo redimio porque no le interesaba" de "no lo redimio porque no le
     * daba el tiempo". Sin este campo, la BQ mide bastante menos de lo que promete.
     */
    val minutesNeeded: Int,

    /**
     * Hasta cuando vale, en ISO 8601 con offset. Null si no vence.
     *
     * Se guarda como texto y no como numero por lo mismo que en el resto del proyecto:
     * es lo que manda el backend, y convertirlo al guardar obligaria a volver a
     * convertirlo para mostrarlo.
     */
    val validUntil: String?,

    /** El codigo que el estudiante ensena en el local. Null hasta que lo redime. */
    val redemptionCode: String?,

    val imageUrl: String?,

    /** Si este usuario ya lo redimio. */
    val redeemed: Boolean = false,

    /**
     * La redencion se hizo sin conexion y el backend todavia no la conoce.
     *
     * Es el mismo campo y la misma idea que `pendingSync` en las actividades: la pantalla
     * puede mostrar el cupon como redimido de inmediato, y avisar en ambar que falta
     * confirmarlo. Reutiliza la cola de acciones pendientes que ya existe.
     */
    val pendingSync: Boolean = false,

    /**
     * Cuando se descargo esta copia.
     *
     * Sirve para avisarle al usuario de que esta viendo un catalogo de hace dos dias, en
     * vez de dejarle creer que es el de hoy. Un cupon vencido que se sigue mostrando es
     * peor que no mostrar nada, porque se entera en la caja del local.
     */
    val cachedAt: Long
)
