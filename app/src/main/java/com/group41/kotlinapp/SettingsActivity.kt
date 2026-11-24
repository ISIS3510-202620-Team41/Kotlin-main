package com.group41.kotlinapp

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.storage.AppSettings
import com.group41.kotlinapp.storage.DataExporter
import com.group41.kotlinapp.ui.theme.KotlinAppTheme
import kotlinx.coroutines.launch

/**
 * Los ajustes de la app.
 *
 * Funciona igual con internet y sin el, porque todo lo que hace es leer y escribir en el
 * telefono. No es un caso de "pantalla que aguanta estar sin conexion": es una pantalla
 * que nunca necesito el servidor.
 *
 * Lo que la hace parte del modo sin conexion es lo que ofrece: cambiar el radio por
 * defecto de la busqueda, y exportar a un archivo lo que la app tiene guardado, que es
 * precisamente lo que un usuario sin red querria poder hacer.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = AppSettings(this)
        val database = (application as App).database

        setContent {
            KotlinAppTheme {
                SettingsScreen(
                    settings = settings,
                    onExport = {
                        val userId = ApiClient.tokens.userId
                        if (userId == null) {
                            toast("Inicia sesion para exportar tus datos")
                        } else {
                            // lifecycleScope y no un scope propio: si el usuario cierra la
                            // pantalla a mitad del export, la corrutina se cancela con ella
                            // en vez de seguir escribiendo para nadie.
                            lifecycleScope.launch {
                                try {
                                    val file = DataExporter.exportUserData(
                                        context = this@SettingsActivity,
                                        userId = userId,
                                        activityDao = database.activityDao(),
                                        searchHistoryDao = database.searchHistoryDao(),
                                        syncLogDao = database.syncLogDao()
                                    )
                                    toast("Guardado: ${file.name}")
                                } catch (error: Exception) {
                                    toast("No se pudo exportar")
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    onExport: () -> Unit
) {
    // El valor inicial se lee de SharedPreferences, que es una lectura inmediata. Con
    // DataStore habria que dibujar un valor inventado y corregirlo al llegar el real, y
    // el usuario veria el numero saltar.
    var radiusKm by remember { mutableStateOf(settings.defaultRadiusKm) }
    var useMiles by remember { mutableStateOf(settings.useMiles) }
    var notifyOnSync by remember { mutableStateOf(settings.notifyOnSync) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = "Ajustes",
            fontSize = 22.sp,
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(text = "Radio por defecto: ${"%.1f".format(radiusKm)} km", fontSize = 15.sp)

        Slider(
            value = radiusKm,
            onValueChange = { radiusKm = it },
            // Se guarda al soltar y no en cada movimiento: arrastrar el control dispara
            // decenas de valores, y escribirlos todos seria reescribir el XML decenas de
            // veces para un unico cambio del usuario.
            onValueChangeFinished = { settings.defaultRadiusKm = radiusKm },
            valueRange = 0.5f..10f
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingSwitch(
            label = "Mostrar distancias en millas",
            checked = useMiles,
            onCheckedChange = {
                useMiles = it
                settings.useMiles = it
            }
        )

        SettingSwitch(
            label = "Avisarme cuando se sincronice",
            checked = notifyOnSync,
            onCheckedChange = {
                notifyOnSync = it
                settings.notifyOnSync = it
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Text("Exportar mis datos")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Guarda un archivo con tus actividades y tu historial de busquedas.",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 15.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
