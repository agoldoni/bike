package it.agoldoni.bike

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Storico dei giri salvati: lista, dettaglio e cancellazione.
 *
 * La navigazione fra lista e dettaglio è uno stato locale invece di una libreria di
 * navigazione: due schermate non giustificano una dipendenza in più, e il progetto non
 * usa ViewModel.
 */
@Composable
fun HistoryScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val revision by RideStore.revision.collectAsStateWithLifecycle()
    // Rilettura legata alla revisione dello store: le località di un giro possono
    // arrivare mentre l'utente sta già guardando la lista. Il file è di pochi kilobyte,
    // quindi leggerlo in composizione costa meno della macchina per evitarlo.
    val rides = remember(revision) { RideStore.load(context) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }

    // Ritentativo automatico: i giri salvati senza rete trovano qui la loro occasione.
    // Una volta per apertura — RideArchivist ignora le chiamate mentre è già al lavoro.
    LaunchedEffect(Unit) { RideArchivist.resolvePending(context) }

    BackHandler {
        if (selected != null) selected = null else onBack()
    }

    val detail = rides.firstOrNull { it.endedAtMillis == selected }

    Surface(modifier = modifier.fillMaxSize(), color = Color.Black) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Header(
                title = stringResource(R.string.history_title),
                onBack = { if (detail == null) onBack() else selected = null },
            )

            when {
                detail != null -> RideDetail(
                    ride = detail,
                    onDeleted = { selected = null },
                )

                rides.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.history_empty),
                        color = Color.Gray,
                        modifier = Modifier.padding(32.dp),
                    )
                }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rides, key = { it.endedAtMillis }) { ride ->
                        RideRow(ride = ride, onClick = { selected = ride.endedAtMillis })
                        HorizontalDivider(color = Color(0xFF222222))
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = "‹ ${stringResource(R.string.history_back)}")
        }
        Text(text = title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RideRow(ride: SavedRide, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = formatDateTime(ride.endedAtMillis),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${formatKm(ride.distanceMeters)} km · ${formatElapsed(ride.elapsedMillis)}",
            color = Color.Gray,
            fontSize = 14.sp,
        )
        PlacesSummary(ride)
    }
}

@Composable
private fun RideDetail(ride: SavedRide, onDeleted: () -> Unit) {
    val context = LocalContext.current
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = formatDateTime(ride.endedAtMillis),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        StatRow(stringResource(R.string.stat_distance), "${formatKm(ride.distanceMeters)} km")
        StatRow(stringResource(R.string.stat_time), formatElapsed(ride.elapsedMillis))
        StatRow(stringResource(R.string.stat_avg_speed), "${formatSpeed(ride.avgSpeedKmh)} km/h")
        StatRow(stringResource(R.string.stat_kcal), formatKcal(ride.kcal))
        StatRow(stringResource(R.string.stat_mass), "${ride.totalMassKg.toInt()} kg")

        HorizontalDivider(color = Color(0xFF222222))
        Text(text = stringResource(R.string.stat_places), color = Color.Gray)
        PlacesSummary(ride)

        TextButton(onClick = { confirmingDelete = true }) {
            Text(text = stringResource(R.string.history_delete), color = Color(0xFFEF5350))
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(text = stringResource(R.string.history_delete_title)) },
            text = { Text(text = stringResource(R.string.history_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        RideStore.delete(context, ride.endedAtMillis)
                        confirmingDelete = false
                        onDeleted()
                    }
                ) {
                    Text(text = stringResource(R.string.history_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(text = stringResource(R.string.history_delete_cancel))
                }
            },
        )
    }
}

/**
 * Le località attraversate, oppure il motivo per cui non ci sono.
 *
 * Un giro senza sommario non deve presentarsi come una riga vuota: la differenza fra
 * «sto ancora chiedendo» e «non si è potuto sapere» è l'unica cosa che dice all'utente
 * se ha senso riaprire lo storico più tardi.
 */
@Composable
private fun PlacesSummary(ride: SavedRide) {
    val resolving by RideArchivist.resolving.collectAsStateWithLifecycle()
    when {
        ride.places.isNotEmpty() -> Text(
            text = ride.places.joinToString(" → "),
            color = Color(0xFF9CCC65),
            fontSize = 14.sp,
        )

        resolving -> Text(
            text = stringResource(R.string.history_places_pending),
            color = Color.Gray,
            fontSize = 14.sp,
        )

        else -> Text(
            text = stringResource(R.string.history_places_missing),
            color = Color.Gray,
            fontSize = 14.sp,
        )
    }
}
