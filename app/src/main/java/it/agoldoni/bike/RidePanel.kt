package it.agoldoni.bike

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// Con la barra di navigazione a tre pulsanti il compatto deve restare abbastanza alto
// da non tagliare il pulsante START/STOP.
private const val COMPACT_FRACTION = 0.34f
private const val EXPANDED_FRACTION = 0.5f

/** Oltre questa accuratezza il fix non entra nel conteggio: va detto all'utente. */
private const val WEAK_SIGNAL_M = 35f

/**
 * Pannello dei dati del giro, ancorato in basso sopra la mappa.
 *
 * Un tocco lo espande fino a metà schermo; a riportarlo compatto è il tocco sulla
 * mappa, gestito dal chiamante.
 */
@Composable
fun RidePanel(
    state: RideState,
    expanded: Boolean,
    waitingForFix: Boolean,
    onExpand: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction by animateFloatAsState(
        targetValue = if (expanded) EXPANDED_FRACTION else COMPACT_FRACTION,
        animationSpec = tween(durationMillis = 300),
        label = "panelHeight",
    )
    // 0 = compatto, 1 = espanso: pilota l'ingrandimento dei caratteri in parallelo
    // all'altezza, così testo e pannello crescono insieme.
    val growth = ((fraction - COMPACT_FRACTION) / (EXPANDED_FRACTION - COMPACT_FRACTION))
        .coerceIn(0f, 1f)
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(fraction)
            .clickable(
                interactionSource = interactionSource,
                indication = null, // il pannello è già l'intera superficie: il ripple distrae
                onClick = onExpand,
            ),
        color = Color(0xFA000000),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // L'app è edge-to-edge: senza questo la barra di navigazione di sistema
                // copre il pulsante START/STOP.
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = String.format(Locale.ITALY, "%.1f", state.speedKmh),
                    fontSize = (48 + 42 * growth).sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                // La riga sotto la velocità fa anche da diagnostica: senza, un GPS che
                // non aggancia o che dà fix inutilizzabili è indistinguibile da un
                // tachimetro fermo a zero.
                val accuracy = state.accuracyMeters
                Text(
                    text = when {
                        waitingForFix -> stringResource(R.string.waiting_gps)
                        state.isTracking && accuracy != null && accuracy > WEAK_SIGNAL_M ->
                            stringResource(R.string.weak_gps, accuracy.toInt())
                        else -> "km/h"
                    },
                    fontSize = (14 + 8 * growth).sp,
                    color = Color.Gray,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Metric(
                    value = String.format(Locale.ITALY, "%.2f", state.distanceMeters / 1000f),
                    label = "km",
                    growth = growth,
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    value = formatElapsed(state.elapsedMillis),
                    label = "tempo",
                    growth = growth,
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    value = String.format(Locale.ITALY, "%.1f", state.avgSpeedKmh),
                    label = "media km/h",
                    growth = growth,
                    modifier = Modifier.weight(1f),
                )
            }

            Button(
                onClick = if (state.isTracking) onStop else onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height((56 + 16 * growth).dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isTracking) Color(0xFFC62828) else Color(0xFF2E7D32),
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = if (state.isTracking) "STOP" else "START",
                    fontSize = (22 + 8 * growth).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Metric(value: String, label: String, growth: Float, modifier: Modifier = Modifier) {
    // Le tre colonne hanno la stessa larghezza: il tempo è il valore più largo e
    // senza vincolo finirebbe addosso agli altri due quando il pannello è espanso.
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = (24 + 6 * growth).sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
        )
        Text(text = label, fontSize = (13 + 3 * growth).sp, color = Color.Gray, maxLines = 1)
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    return String.format(
        Locale.ITALY,
        "%d:%02d:%02d",
        totalSeconds / 3600,
        totalSeconds % 3600 / 60,
        totalSeconds % 60,
    )
}
