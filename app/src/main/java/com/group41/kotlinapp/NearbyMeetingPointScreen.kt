package com.group41.kotlinapp

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.ui.theme.LlamallaBlack
import com.group41.kotlinapp.ui.theme.LlamallaBlue
import com.group41.kotlinapp.viewmodel.NearbyActivitiesViewModel

/**
 * Vista Protegida 3 - Busqueda de actividades por cercania.
 *
 * Que cambio respecto a la version anterior: antes esta pantalla tenia dos casillas donde
 * el usuario debia escribir su latitud y su longitud. Funcionaba para probar, pero nadie
 * se sabe sus coordenadas de memoria, asi que en la practica la pantalla no se podia usar.
 *
 * Ahora la posicion la da el sensor del telefono y al usuario solo le queda elegir a que
 * distancia quiere buscar.
 */
@Composable
fun NearbyMeetingPointScreen(
    viewModel: NearbyActivitiesViewModel
) {
    // collectAsState hace que Compose vuelva a dibujar la pantalla sola cada vez que el
    // ViewModel publica un estado nuevo. No hay que avisarle a mano.
    val state by viewModel.state.collectAsState()

    var radiusText by remember { mutableStateOf("1.0") }
    val radiusKm = radiusText.toDoubleOrNull()

    /**
     * El dialogo del sistema que le pregunta al usuario si nos deja ver su ubicacion.
     *
     * Se piden los dos permisos juntos y basta con que conceda cualquiera: desde Android 12
     * el dialogo deja elegir entre "precisa" y "aproximada", y para buscar en un radio de
     * kilometros la aproximada sobra.
     */
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        viewModel.onPermissionResult(granted, radiusKm ?: 1.0)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Actividades cerca de ti",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Usamos la ubicacion de tu telefono para buscar a tu alrededor.",
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(18.dp))

            OutlinedTextField(
                value = radiusText,
                onValueChange = { radiusText = it },
                label = { Text("Radio (km)") },
                // El aviso aparece mientras escribe, no al tocar el boton: es mas util
                // corregir en el momento que descubrir el error despues.
                isError = radiusText.isNotBlank() && radiusKm == null,
                supportingText = {
                    if (radiusText.isNotBlank() && radiusKm == null) {
                        Text("Escribe un numero, por ejemplo 1.5")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    val radius = radiusKm ?: return@Button
                    // Se comprueba el permiso antes de buscar. Si ya lo tenemos no se le
                    // vuelve a preguntar al usuario, que es lo que haria el dialogo mas
                    // molesto de la app.
                    if (viewModel.hasLocationPermission()) {
                        viewModel.search(radius)
                    } else {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                // Se bloquea mientras busca y si el radio no es un numero valido.
                enabled = !state.searching && radiusKm != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LlamallaBlue,
                    contentColor = LlamallaBlack
                )
            ) {
                Text(
                    // "Ubicandote" y no un "Cargando" generico: el usuario entiende por que
                    // tarda, porque leer el sensor puede tomar un par de segundos.
                    if (state.searching) "Ubicandote..." else "Buscar cerca de mi"
                )
            }

            // El permiso se pide con un boton y no con un dialogo que salte solo: el usuario
            // entiende para que sirve despues de haber intentado buscar, no antes.
            if (state.needsPermission) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Para buscar a tu alrededor necesitamos ver tu ubicacion.",
                    color = Color.DarkGray
                )
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LlamallaBlue,
                        contentColor = LlamallaBlack
                    )
                ) {
                    Text("Permitir ubicacion")
                }
            }

            state.error?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // El aviso de datos guardados va en ambar y no en rojo: no es un fallo, es
            // informacion. La busqueda funciono, solo que con lo que habia en el telefono.
            if (state.fromLocalData && state.activities.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = buildString {
                        append("Sin conexion: resultados calculados con lo guardado en tu telefono")
                        // El tiempo se muestra porque es la prueba de que el filtrado corre
                        // fuera del hilo de la pantalla y aun asi termina al instante.
                        state.elapsedMillis?.let { append(" (${it} ms)") }
                    },
                    color = Color(0xFFB26A00),
                    fontSize = 13.sp
                )
            }

            if (state.hasSearched && state.activities.isEmpty() && state.error == null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No hay actividades dentro de ese radio. Prueba con uno mas amplio.",
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        items(state.activities) { activity ->
            NearbyActivityCard(activity)
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun NearbyActivityCard(
    activity: Activity
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFDDF4F8)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = activity.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = activity.category,
                color = Color(0xFFD50057),
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                // Por debajo de un kilometro se muestran metros, que es como la gente piensa
                // las distancias cortas: "a 300 m" se entiende mejor que "a 0.3 km".
                text = "${activity.durationMinutes} min · ${formatDistance(activity.distanceMeters)}",
                color = Color.DarkGray
            )

            if (activity.joined) {
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Ya estás unido",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 450 -> "450 m", 2300 -> "2.3 km". */
private fun formatDistance(meters: Int): String =
    if (meters < 1000) "$meters m" else "%.1f km".format(meters / 1000.0)
