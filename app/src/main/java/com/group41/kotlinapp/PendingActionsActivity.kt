package com.group41.kotlinapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.group41.kotlinapp.model.PendingAction
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.repository.PendingActionRepository
import com.group41.kotlinapp.sync.SyncScheduler
import com.group41.kotlinapp.ui.theme.KotlinAppTheme

/**
 * Lo que el usuario hizo sin internet y todavia no ha llegado al servidor.
 *
 * Esta pantalla existe porque la cola de acciones pendientes era invisible. El usuario se
 * inscribia sin conexion, veia el aviso de "pendiente" en esa actividad concreta, y no
 * tenia ninguna forma de saber que mas estaba esperando ni si algo se habia quedado
 * atascado. Tenia que confiar en que la app lo estaba haciendo bien.
 *
 * Funciona enteramente sin internet, y de hecho es mas util sin el: lee la cola de la base
 * de datos local y, si hay red, le pide a WorkManager que la vacie.
 */
class PendingActionsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val queue = PendingActionRepository((application as App).database.pendingActionDao())

        setContent {
            KotlinAppTheme {
                PendingActionsScreen(
                    loadPending = {
                        // Sin sesion no hay cola que mirar: la cola es por usuario.
                        ApiClient.tokens.userId?.let { queue.pendingFor(it) } ?: emptyList()
                    },
                    onRetryNow = { SyncScheduler.schedule(this) }
                )
            }
        }
    }
}

@Composable
private fun PendingActionsScreen(
    loadPending: suspend () -> List<PendingAction>,
    onRetryNow: () -> Unit
) {
    var pending by remember { mutableStateOf<List<PendingAction>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    // Se usa para releer la lista despues de pedir un reintento.
    var reloadKey by remember { mutableStateOf(0) }

    // LaunchedEffect y no un simple llamado: leer la base es una operacion suspend, y
    // atarla a `reloadKey` hace que se repita cuando ese valor cambia.
    LaunchedEffect(reloadKey) {
        pending = loadPending()
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = "Pendientes de enviar",
            fontSize = 22.sp,
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Lo que hiciste sin conexion y todavia no ha llegado al servidor.",
            fontSize = 13.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))

        when {
            // Antes de la primera lectura no se dice "no hay nada", porque todavia no se
            // sabe: decirlo seria mentir durante un instante.
            !loaded -> Text("Cargando...", color = Color.Gray)

            pending.isEmpty() -> Text(
                text = "Todo esta sincronizado.",
                color = Color(0xFF2E7D32)
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pending) { action -> PendingActionCard(action) }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                onRetryNow()
                reloadKey++
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reintentar ahora")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Se avisa en vez de desactivar el boton: encolar el trabajo sin red tambien sirve,
        // porque WorkManager lo guarda y lo ejecuta solo cuando vuelva la conexion.
        Text(
            text = "Si no hay internet, se enviara solo cuando vuelva.",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}

@Composable
private fun PendingActionCard(action: PendingAction) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = when (action.type.name) {
                    "JOIN_ACTIVITY" -> "Inscripcion a una actividad"
                    "CREATE_ACTIVITY" -> "Actividad creada"
                    "UPLOAD_ACTIVITY_PHOTO" -> "Foto de una actividad"
                    else -> action.type.name
                },
                fontSize = 15.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            // El numero de reintentos se muestra porque es el dato que distingue "esta
            // esperando a que haya red" de "se esta intentando y algo falla".
            Text(
                text = if (action.retryCount == 0) {
                    "Esperando conexion"
                } else {
                    "Reintentos: ${action.retryCount}"
                },
                fontSize = 12.sp,
                color = Color(0xFFB26A00)
            )
        }
    }
}
