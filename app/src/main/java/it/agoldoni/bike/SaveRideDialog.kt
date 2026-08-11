package it.agoldoni.bike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Domanda di fine giro: salvare o buttare.
 *
 * È volutamente **non chiudibile** per sbaglio — né toccando fuori né col tasto indietro:
 * il giro esiste solo in memoria e una chiusura accidentale lo perderebbe senza appello.
 * Per lo stesso motivo copre il pulsante START, che appena il tracciamento si ferma
 * tornerebbe premibile e azzererebbe il giro da salvare.
 *
 * «Non salvare» non chiede una seconda conferma: la difesa è già la scelta esplicita fra
 * due pulsanti distinti.
 */
@Composable
fun SaveRideDialog(
    ride: FinishedRide,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        // Vuoto di proposito: le uniche uscite sono i due pulsanti.
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(text = stringResource(R.string.save_ride_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatRow(
                    label = stringResource(R.string.stat_distance),
                    value = "${formatKm(ride.state.distanceMeters)} km",
                )
                StatRow(
                    label = stringResource(R.string.stat_time),
                    value = formatElapsed(ride.state.elapsedMillis),
                )
                StatRow(
                    label = stringResource(R.string.stat_avg_speed),
                    value = "${formatSpeed(ride.state.avgSpeedKmh)} km/h",
                )
                StatRow(
                    label = stringResource(R.string.stat_kcal),
                    value = formatKcal(ride.state.kcal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(text = stringResource(R.string.save_ride_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(text = stringResource(R.string.save_ride_discard))
            }
        },
    )
}

/**
 * Riga «etichetta a sinistra, valore a destra».
 *
 * Condivisa con il dettaglio dello storico: le stesse grandezze devono presentarsi allo
 * stesso modo nei due punti in cui l'utente le confronta.
 */
@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = Color.Gray)
        Text(text = value, fontWeight = FontWeight.SemiBold)
    }
}
