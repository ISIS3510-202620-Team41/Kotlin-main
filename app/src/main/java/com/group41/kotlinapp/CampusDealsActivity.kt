package com.group41.kotlinapp

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.group41.kotlinapp.analytics.Analytics
import com.group41.kotlinapp.analytics.BreakState
import com.group41.kotlinapp.data.local.entity.DealEntity
import com.group41.kotlinapp.repository.CatalogSource
import com.group41.kotlinapp.ui.theme.KotlinAppTheme
import com.group41.kotlinapp.viewmodel.DealsUiState
import com.group41.kotlinapp.viewmodel.DealsViewModel

/**
 * CampusDeals: el catalogo de cupones de comercios cercanos al campus.
 *
 * Funciona igual con internet y sin el, porque siempre lee de la base local. Lo que
 * cambia sin conexion es que avisa de que los datos son del telefono, y que una redencion
 * queda pendiente de confirmar en vez de darte el codigo al instante.
 */
class CampusDealsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val viewModel = ViewModelProvider(this)[DealsViewModel::class.java]

        setContent {
            KotlinAppTheme {
                CampusDealsScreen(
                    viewModel = viewModel,
                    onMessage = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Analytics.screenView("CampusDeals")
    }
}

@Composable
private fun CampusDealsScreen(
    viewModel: DealsViewModel,
    onMessage: (String) -> Unit
) {
    val state by viewModel.state.collectAsState()

    // Se carga una sola vez al entrar. Al rotar la pantalla el ViewModel sobrevive, asi
    // que no se vuelve a pedir el catalogo ni el horario.
    LaunchedEffect(Unit) { viewModel.load() }

    // Los mensajes de una sola vez salen por Toast y se limpian, para que no reaparezcan
    // al rotar.
    LaunchedEffect(state.message) {
        state.message?.let {
            onMessage(it)
            viewModel.messageShown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = "CampusDeals",
            fontSize = 24.sp,
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            text = "Descuentos de comercios cerca del campus.",
            fontSize = 13.sp,
            color = Color.Gray
        )

        BreakBanner(state.breakState)

        // El aviso de datos locales va en ambar y no en rojo: no es un fallo. El catalogo
        // funciono, solo que con lo que habia guardado en el telefono.
        if (state.fromLocalData && state.deals.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Sin conexion: catalogo guardado en tu telefono.",
                color = Color(0xFFB26A00),
                fontSize = 13.sp
            )
        }

        // El origen y el tiempo se muestran a proposito: son la prueba visible de que el
        // cache en memoria sirve. La primera carga dice "disco" y unos milisegundos, la
        // segunda dice "memoria" y cero. Es la medicion del antes y el despues, en vivo.
        state.catalogSource?.let { source ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = when (source) {
                    CatalogSource.MEMORY -> "Desde memoria (${state.catalogElapsedMillis} ms)"
                    CatalogSource.DISK -> "Desde la base local (${state.catalogElapsedMillis} ms)"
                },
                fontSize = 12.sp,
                color = Color.Gray
            )
        }

        if (state.categories.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            CategoryFilters(
                categories = state.categories,
                selected = state.selectedCategory,
                onSelect = viewModel::selectCategory
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        when {
            state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "  Cargando cupones...", color = Color.Gray)
            }

            state.deals.isEmpty() -> Text(
                text = "No hay cupones disponibles por ahora.",
                color = Color.Gray
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.deals, key = { it.id }) { deal ->
                    DealCard(
                        deal = deal,
                        fits = state.breakState.fits(deal.minutesNeeded),
                        onOpen = { viewModel.onDealOpened(deal) },
                        onRedeem = { viewModel.redeem(deal) }
                    )
                }
            }
        }
    }
}

/**
 * El aviso de cuanto tiempo libre le queda al estudiante.
 *
 * Solo aparece si esta en un hueco. Sin horario importado no se dice nada: inventar un
 * mensaje sobre un dato que no se tiene seria peor que callar.
 */
@Composable
private fun BreakBanner(breakState: BreakState) {
    if (breakState.duringBreak != true) return

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Tienes ${breakState.minutesLeft} min libres antes de tu proxima clase.",
        color = Color(0xFF2E7D32),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun CategoryFilters(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("Todos") }
        )
        categories.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(category) },
                label = { Text(category.lowercase().replaceFirstChar { it.uppercase() }) }
            )
        }
    }
}

@Composable
private fun DealCard(
    deal: DealEntity,
    fits: Boolean,
    onOpen: () -> Unit,
    onRedeem: () -> Unit
) {
    // Abrir la tarjeta cuenta como interaccion para la BQ-11, asi que se reporta al
    // componerse y no al tocarla: la tarjeta ya esta delante del usuario.
    LaunchedEffect(deal.id) { onOpen() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {

            Text(text = deal.merchant, fontSize = 13.sp, color = Color.Gray)

            Text(
                text = deal.title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            deal.description?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = it, fontSize = 13.sp, color = Color.DarkGray)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${deal.discountPercent}% de descuento · ${deal.minutesNeeded} min",
                fontSize = 13.sp,
                // Si no cabe en el tiempo que queda, se dice en gris en vez de esconder
                // el cupon: puede que quiera guardarlo para despues.
                color = if (fits) Color(0xFF2E7D32) else Color.Gray
            )

            if (!fits) {
                Text(
                    text = "No te alcanza el tiempo antes de tu proxima clase.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            when {
                // Tres estados y no dos, igual que el boton de inscripcion: redimido y
                // confirmado, redimido pero sin confirmar, y disponible. El ambar es lo
                // que mantiene la honestidad sin bloquear al usuario.
                deal.redeemed && deal.pendingSync -> Text(
                    text = "Redimido (pendiente de confirmar)",
                    color = Color(0xFFB26A00),
                    fontWeight = FontWeight.Bold
                )

                deal.redeemed -> Text(
                    text = "Redimido · codigo ${deal.redemptionCode ?: "-"}",
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Bold
                )

                else -> Button(onClick = onRedeem, modifier = Modifier.fillMaxWidth()) {
                    Text("Redimir")
                }
            }
        }
    }
}
