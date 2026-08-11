package it.agoldoni.bike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RouteDigest] decide quante richieste di geocoding costa un giro e che aspetto avrà il
 * sommario: sono le due cose che questi test tengono ferme.
 */
class RouteDigestTest {

    /** Un grado di latitudine è circa 111,195 km ovunque: comodo per costruire tracciati. */
    private fun trackNorth(points: Int, stepMeters: Double): List<GeoPoint> {
        val stepDegrees = stepMeters / 111_195.0
        return List(points) { GeoPoint(lat = 45.0 + it * stepDegrees, lon = 9.0) }
    }

    @Test
    fun `un tracciato vuoto non produce campioni`() {
        assertTrue(RouteDigest.sample(emptyList()).isEmpty())
    }

    @Test
    fun `un tracciato di un punto solo resta quel punto`() {
        val single = listOf(GeoPoint(45.0, 9.0))
        assertEquals(single, RouteDigest.sample(single))
    }

    @Test
    fun `partenza e arrivo sono sempre campionati`() {
        val track = trackNorth(points = 101, stepMeters = 100.0)

        val sampled = RouteDigest.sample(track)

        assertEquals(track.first(), sampled.first())
        assertEquals(track.last(), sampled.last())
    }

    @Test
    fun `fra un campione e l altro c e almeno un chilometro`() {
        // 10 km di tracciato con un vertice ogni 100 m.
        val track = trackNorth(points = 101, stepMeters = 100.0)

        val sampled = RouteDigest.sample(track)

        // L'ultimo salto è escluso: il punto d'arrivo si prende comunque, anche se cade
        // subito dopo il campione precedente.
        sampled.dropLast(1).zipWithNext().forEach { (a, b) ->
            assertTrue(
                "campioni troppo vicini: ${RouteDigest.distanceMeters(a, b)} m",
                RouteDigest.distanceMeters(a, b) >= RouteDigest.SAMPLE_STEP_M * 0.99,
            )
        }
    }

    @Test
    fun `dieci chilometri costano una decina di richieste`() {
        val track = trackNorth(points = 101, stepMeters = 100.0)

        val sampled = RouteDigest.sample(track)

        // Undici punti: partenza più uno ogni chilometro. Il valore esatto conta meno del
        // fatto che sia dell'ordine delle decine e non delle migliaia (i vertici sono 101).
        assertEquals(11, sampled.size)
    }

    @Test
    fun `un giro lunghissimo non supera il tetto di richieste`() {
        // 100 km: col passo fisso di 1 km sarebbero 100 richieste, cioè un blocco certo.
        val track = trackNorth(points = 1001, stepMeters = 100.0)

        val sampled = RouteDigest.sample(track)

        assertTrue("campioni: ${sampled.size}", sampled.size <= RouteDigest.MAX_SAMPLES)
        // Il tetto si rispetta allargando il passo, non tagliando la fine del giro.
        assertEquals(track.last(), sampled.last())
    }

    @Test
    fun `le localita consecutive uguali collassano in una`() {
        val places = listOf("Modena", "Modena", "Modena")

        assertEquals(listOf("Modena"), RouteDigest.dedupConsecutive(places))
    }

    @Test
    fun `una localita riattraversata dopo esserne usciti ricompare`() {
        val places = listOf("Modena", "Modena", "Formigine", "Modena")

        assertEquals(
            listOf("Modena", "Formigine", "Modena"),
            RouteDigest.dedupConsecutive(places),
        )
    }

    @Test
    fun `i punti non risolti non spezzano la sequenza`() {
        // Un buco di copertura in mezzo al giro non deve far sembrare che si sia usciti
        // dal comune e rientrati.
        val places = listOf("Modena", null, "Modena", null, "Formigine")

        assertEquals(listOf("Modena", "Formigine"), RouteDigest.dedupConsecutive(places))
    }

    @Test
    fun `nomi vuoti o di soli spazi si scartano`() {
        val places = listOf("", "   ", "Modena")

        assertEquals(listOf("Modena"), RouteDigest.dedupConsecutive(places))
    }

    @Test
    fun `il confronto fra localita ignora maiuscole e spazi`() {
        // Le due sorgenti possono restituire la stessa località scritta diversamente.
        val places = listOf("Modena", " modena ", "MODENA")

        assertEquals(listOf("Modena"), RouteDigest.dedupConsecutive(places))
    }

    @Test
    fun `la distanza fra due punti torna in metri`() {
        val from = GeoPoint(45.0, 9.0)
        val to = GeoPoint(45.0 + 1000.0 / 111_195.0, 9.0)

        assertEquals(1000.0, RouteDigest.distanceMeters(from, to), 1.0)
    }
}
