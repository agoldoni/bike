package it.agoldoni.bike

import android.content.Context

/**
 * Pesi di ciclista e bici, gli unici dati che [CalorieModel] non può ricavare dal GPS.
 *
 * Restano separati anche se il calcolo usa solo la somma: la bici si cambia senza che
 * cambi il ciclista, e viceversa, quindi chiedere ogni volta il totale costringerebbe a
 * rifare la somma a mente.
 */
data class RiderProfile(val riderKg: Float, val bikeKg: Float) {
    val totalKg: Float get() = riderKg + bikeKg
}

object RiderProfileStore {

    private const val PREFS = "bike"
    private const val KEY_RIDER_KG = "rider_kg"
    private const val KEY_BIKE_KG = "bike_kg"

    /** Valori di partenza plausibili: senza, al primo giro le calorie resterebbero a zero. */
    val DEFAULT = RiderProfile(riderKg = 75f, bikeKg = 12f)

    fun load(context: Context): RiderProfile {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RiderProfile(
            riderKg = prefs.getFloat(KEY_RIDER_KG, DEFAULT.riderKg),
            bikeKg = prefs.getFloat(KEY_BIKE_KG, DEFAULT.bikeKg),
        )
    }

    fun save(context: Context, profile: RiderProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_RIDER_KG, profile.riderKg)
            .putFloat(KEY_BIKE_KG, profile.bikeKg)
            .apply()
    }
}
