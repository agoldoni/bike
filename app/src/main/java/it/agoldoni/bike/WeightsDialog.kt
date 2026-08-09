package it.agoldoni.bike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

// Estremi oltre i quali il valore è quasi certamente un errore di battitura: un peso
// sbagliato di un fattore dieci falserebbe le calorie senza che nulla lo segnali.
private val RIDER_RANGE = 30f..250f
private val BIKE_RANGE = 3f..60f

/**
 * Immissione dei pesi usati da [CalorieModel]. Ciclista e bici sono campi distinti
 * perché cambiano in momenti diversi (vedi [RiderProfile]).
 */
@Composable
fun WeightsDialog(
    profile: RiderProfile,
    onDismiss: () -> Unit,
    onConfirm: (RiderProfile) -> Unit,
) {
    var rider by remember { mutableStateOf(formatKg(profile.riderKg)) }
    var bike by remember { mutableStateOf(formatKg(profile.bikeKg)) }

    val riderKg = rider.toKgOrNull()
    val bikeKg = bike.toKgOrNull()
    val valid = riderKg != null && bikeKg != null &&
        riderKg in RIDER_RANGE && bikeKg in BIKE_RANGE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.weights_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(R.string.weights_message))
                OutlinedTextField(
                    value = rider,
                    onValueChange = { rider = it },
                    label = { Text(text = stringResource(R.string.weights_rider)) },
                    isError = riderKg == null || riderKg !in RIDER_RANGE,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = bike,
                    onValueChange = { bike = it },
                    label = { Text(text = stringResource(R.string.weights_bike)) },
                    isError = bikeKg == null || bikeKg !in BIKE_RANGE,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(RiderProfile(riderKg!!, bikeKg!!)) },
                enabled = valid,
            ) {
                Text(text = stringResource(R.string.weights_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.weights_cancel))
            }
        },
    )
}

/** La tastiera decimale italiana produce la virgola, che `toFloat` non accetta. */
private fun String.toKgOrNull(): Float? = trim().replace(',', '.').toFloatOrNull()

private fun formatKg(kg: Float): String =
    String.format(Locale.ITALY, "%.1f", kg).removeSuffix(",0")
