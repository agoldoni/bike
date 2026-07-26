package it.agoldoni.bike

import android.content.Context

/**
 * Ultima posizione agganciata, conservata fra un avvio e l'altro.
 *
 * Senza, all'apertura la mappa resta centrata su 0,0 — in mezzo al Golfo di Guinea —
 * finché il GPS non aggancia, che all'aperto può richiedere anche mezzo minuto.
 */
object LastKnownPosition {

    private const val PREFS = "bike"
    private const val KEY_LAT = "last_lat"
    private const val KEY_LON = "last_lon"

    fun load(context: Context): GeoPoint? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) return null
        // SharedPreferences non tiene i Double: si conservano come bit pattern.
        return GeoPoint(
            lat = Double.fromBits(prefs.getLong(KEY_LAT, 0L)),
            lon = Double.fromBits(prefs.getLong(KEY_LON, 0L)),
        )
    }

    fun save(context: Context, position: GeoPoint) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAT, position.lat.toRawBits())
            .putLong(KEY_LON, position.lon.toRawBits())
            .apply()
    }
}
