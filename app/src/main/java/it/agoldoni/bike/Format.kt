package it.agoldoni.bike

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formattazioni condivise fra pannello del giro, dialog di salvataggio e storico.
 *
 * Stanno insieme perché gli stessi numeri compaiono in tutti e tre: se il pannello
 * mostrasse `12,34 km` e lo storico `12.3 km` per lo stesso giro, sembrerebbero due
 * misure diverse.
 */

fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    return String.format(
        Locale.ITALY,
        "%d:%02d:%02d",
        totalSeconds / 3600,
        totalSeconds % 3600 / 60,
        totalSeconds % 60,
    )
}

fun formatKm(meters: Float): String = String.format(Locale.ITALY, "%.2f", meters / 1000f)

fun formatSpeed(kmh: Float): String = String.format(Locale.ITALY, "%.1f", kmh)

fun formatKcal(kcal: Float): String = String.format(Locale.ITALY, "%.0f", kcal)

// java.time è disponibile senza desugaring da API 26, che è il minSdk del progetto.
private val DATE_TIME = DateTimeFormatter.ofPattern("d MMMM yyyy · HH:mm", Locale.ITALY)

fun formatDateTime(millis: Long): String =
    DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
