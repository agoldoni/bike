package it.agoldoni.bike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il formato del file è l'unica parte della feature che non si può più cambiare
 * liberamente: dopo il primo salvataggio ci sono dati veri scritti così.
 */
class SavedRideTest {

    private val ride = SavedRide(
        endedAtMillis = 1_700_000_000_000L,
        elapsedMillis = 3_600_000L,
        distanceMeters = 25_000f,
        kcal = 620f,
        totalMassKg = 87f,
        places = listOf("Modena", "Formigine", "Modena"),
        placesResolved = true,
    )

    @Test
    fun `un giro serializzato si rilegge identico`() {
        val decoded = RideArchiveFormat.decode(RideArchiveFormat.encode(listOf(ride)))

        assertEquals(listOf(ride), decoded)
    }

    @Test
    fun `i punti ancora da risolvere sopravvivono al salvataggio`() {
        // Senza di loro il ritentativo dopo un giro fatto senza rete sarebbe impossibile:
        // il tracciato completo a quel punto non esiste più.
        val pending = ride.copy(
            places = emptyList(),
            placesResolved = false,
            pendingSamples = listOf(GeoPoint(44.6, 10.9), GeoPoint(44.5, 10.8)),
        )

        val decoded = RideArchiveFormat.decode(RideArchiveFormat.encode(listOf(pending)))

        assertEquals(pending.pendingSamples, decoded.single().pendingSamples)
    }

    @Test
    fun `i giri si rileggono dal piu recente al piu vecchio`() {
        val older = ride.copy(endedAtMillis = ride.endedAtMillis - 86_400_000L)
        val newer = ride.copy(endedAtMillis = ride.endedAtMillis + 86_400_000L)

        val decoded = RideArchiveFormat.decode(RideArchiveFormat.encode(listOf(older, ride, newer)))

        assertEquals(listOf(newer, ride, older), decoded)
    }

    @Test
    fun `un record con campi sconosciuti resta leggibile`() {
        // Un giro scritto da una versione futura dell'app non deve far sparire lo storico.
        val fromTheFuture = """
            {"version":2,"rides":[{
              "endedAtMillis":1700000000000,
              "elapsedMillis":3600000,
              "distanceMeters":25000.0,
              "kcal":620.0,
              "totalMassKg":87.0,
              "places":["Modena"],
              "placesResolved":true,
              "pendingSamples":[],
              "dislivelloMetri":412
            }]}
        """.trimIndent()

        val decoded = RideArchiveFormat.decode(fromTheFuture)

        assertEquals(listOf("Modena"), decoded.single().places)
    }

    @Test
    fun `un testo illeggibile produce uno storico vuoto invece di un crash`() {
        // Caso reale: file troncato da una morte del processo a metà scrittura.
        assertTrue(RideArchiveFormat.decode("""{"version":1,"rides":[{"endedAt""").isEmpty())
    }

    @Test
    fun `un file vuoto produce uno storico vuoto`() {
        assertTrue(RideArchiveFormat.decode("").isEmpty())
    }

    @Test
    fun `la velocita media si ricava da distanza e tempo`() {
        // 25 km in un'ora.
        assertEquals(25f, ride.avgSpeedKmh, 0.01f)
    }

    @Test
    fun `un giro di durata nulla non produce una media infinita`() {
        assertEquals(0f, ride.copy(elapsedMillis = 0L).avgSpeedKmh, 0f)
    }
}
