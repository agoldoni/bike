package it.agoldoni.bike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [RideTracker] è un singleton di processo: ogni test riparte da [RideTracker.onStart],
 * che è anche il reset del giro.
 */
class RideTrackerTest {

    private val milan = GeoPoint(45.4642, 9.1900)
    private val milanNorth = GeoPoint(45.4652, 9.1900)

    @Before
    fun reset() {
        RideTracker.onStart()
    }

    @Test
    fun `onStart azzera il giro precedente`() {
        RideTracker.onPosition(milan, 5f)
        RideTracker.onSample(speedKmh = 20f, deltaMeters = 100f)
        RideTracker.onTrackPoint(milanNorth)

        RideTracker.onStart()

        assertNull(RideTracker.position.value)
        assertTrue(RideTracker.track.value.isEmpty())
        assertEquals(0f, RideTracker.state.value.distanceMeters, 0f)
        assertEquals(0f, RideTracker.state.value.speedKmh, 0f)
        assertNull(RideTracker.state.value.accuracyMeters)
        assertTrue(RideTracker.state.value.isTracking)
        assertEquals(0f, RideTracker.state.value.kcal, 0f)
        assertNull(RideTracker.pendingRide.value)
    }

    @Test
    fun `onStop congela il giro appena concluso`() {
        RideTracker.onSample(speedKmh = 25f, deltaMeters = 400f)
        RideTracker.onElapsed(60_000L)
        RideTracker.onTrackPoint(milan)
        RideTracker.onTrackPoint(milanNorth)

        RideTracker.onStop(endedAtMillis = 1_700_000_000_000L)

        val pending = RideTracker.pendingRide.value!!
        assertEquals(1_700_000_000_000L, pending.endedAtMillis)
        assertEquals(400f, pending.state.distanceMeters, 0.001f)
        assertEquals(60_000L, pending.state.elapsedMillis)
        assertEquals(listOf(milan, milanNorth), pending.track)
    }

    @Test
    fun `il giro congelato sopravvive a un nuovo punto arrivato in ritardo`() {
        // Il congelamento è una copia, non una vista: un fix che arriva dopo lo STOP non
        // deve cambiare il giro che l'utente sta decidendo se salvare.
        RideTracker.onTrackPoint(milan)
        RideTracker.onStop()

        RideTracker.onTrackPoint(milanNorth)

        assertEquals(listOf(milan), RideTracker.pendingRide.value!!.track)
    }

    @Test
    fun `onRideHandled toglie il giro dalla coda`() {
        RideTracker.onSample(speedKmh = 20f, deltaMeters = 500f)
        RideTracker.onStop()

        RideTracker.onRideHandled()

        assertNull(RideTracker.pendingRide.value)
    }

    @Test
    fun `un secondo onStop non rimette in coda un giro gia archiviato`() {
        // Il service può ricevere ACTION_STOP più di una volta.
        RideTracker.onStop()
        RideTracker.onRideHandled()

        RideTracker.onStop()

        assertNull(RideTracker.pendingRide.value)
    }

    @Test
    fun `onElapsed accumula le calorie alla velocita corrente`() {
        RideTracker.onStart(totalMassKg = 90f)
        RideTracker.onSample(speedKmh = 20f, deltaMeters = 100f)

        // Mezz'ora a 20 km/h: 8.0 MET × 90 kg × 0,5 h
        RideTracker.onElapsed(1_800_000L)

        assertEquals(360f, RideTracker.state.value.kcal, 0.01f)
    }

    @Test
    fun `le calorie si sommano un tick alla volta`() {
        RideTracker.onStart(totalMassKg = 90f)
        RideTracker.onSample(speedKmh = 20f, deltaMeters = 100f)
        RideTracker.onElapsed(1_800_000L)

        // Il secondo tratto conta solo il tempo trascorso dal tick precedente, alla
        // nuova velocità: 10.0 MET × 90 kg × 0,25 h
        RideTracker.onSample(speedKmh = 24f, deltaMeters = 100f)
        RideTracker.onElapsed(2_700_000L)

        assertEquals(360f + 225f, RideTracker.state.value.kcal, 0.01f)
    }

    @Test
    fun `da fermi il cronometro avanza ma le calorie no`() {
        RideTracker.onStart(totalMassKg = 90f)
        RideTracker.onSample(speedKmh = 20f, deltaMeters = 100f)
        RideTracker.onSpeedLost()

        RideTracker.onElapsed(600_000L)

        assertEquals(600_000L, RideTracker.state.value.elapsedMillis)
        assertEquals(0f, RideTracker.state.value.kcal, 0f)
    }

    @Test
    fun `onPosition mostra la posizione senza produrre distanza`() {
        RideTracker.onPosition(milan, 8f)

        assertEquals(milan, RideTracker.position.value)
        assertEquals(8f, RideTracker.state.value.accuracyMeters)
        assertEquals(0f, RideTracker.state.value.distanceMeters, 0f)
        assertEquals(0f, RideTracker.state.value.speedKmh, 0f)
    }

    @Test
    fun `un fix impreciso aggiorna comunque la posizione mostrata`() {
        // Il caso che teneva la mappa ferma: fix troppo imprecisi per il conteggio
        // devono comunque far vedere dove siamo.
        RideTracker.onPosition(milan, 120f)

        assertEquals(milan, RideTracker.position.value)
        assertEquals(120f, RideTracker.state.value.accuracyMeters)
        assertTrue(RideTracker.track.value.isEmpty())
    }

    @Test
    fun `onPosition non tocca il tracciato`() {
        RideTracker.onPosition(milan, 5f)
        RideTracker.onPosition(milanNorth, 5f)

        // I vertici li aggiunge solo onTrackPoint, più di rado dei fix.
        assertTrue(RideTracker.track.value.isEmpty())
    }

    @Test
    fun `onSample accumula la distanza`() {
        RideTracker.onSample(speedKmh = 18f, deltaMeters = 30f)
        RideTracker.onSample(speedKmh = 22f, deltaMeters = 20f)

        assertEquals(50f, RideTracker.state.value.distanceMeters, 0.001f)
        assertEquals(22f, RideTracker.state.value.speedKmh, 0.001f)
    }

    @Test
    fun `onTrackPoint accoda i vertici nell ordine di arrivo`() {
        RideTracker.onTrackPoint(milan)
        RideTracker.onTrackPoint(milanNorth)

        assertEquals(listOf(milan, milanNorth), RideTracker.track.value)
    }

    @Test
    fun `onSpeedLost azzera la velocita senza toccare distanza e tracciato`() {
        RideTracker.onPosition(milan, 5f)
        RideTracker.onSample(speedKmh = 25f, deltaMeters = 40f)
        RideTracker.onTrackPoint(milanNorth)

        RideTracker.onSpeedLost()

        assertEquals(0f, RideTracker.state.value.speedKmh, 0f)
        assertEquals(40f, RideTracker.state.value.distanceMeters, 0.001f)
        assertEquals(listOf(milanNorth), RideTracker.track.value)
    }

    @Test
    fun `onStop conserva le statistiche del giro`() {
        RideTracker.onPosition(milan, 5f)
        RideTracker.onSample(speedKmh = 25f, deltaMeters = 40f)
        RideTracker.onElapsed(10_000L)

        RideTracker.onStop()

        val state = RideTracker.state.value
        assertEquals(false, state.isTracking)
        assertEquals(0f, state.speedKmh, 0f)
        assertEquals(40f, state.distanceMeters, 0.001f)
        assertEquals(10_000L, state.elapsedMillis)
    }

    @Test
    fun `la velocita media usa distanza e tempo trascorso`() {
        RideTracker.onSample(speedKmh = 0f, deltaMeters = 100f)
        RideTracker.onElapsed(20_000L)

        // 100 m in 20 s = 5 m/s = 18 km/h
        assertEquals(18f, RideTracker.state.value.avgSpeedKmh, 0.001f)
    }
}
