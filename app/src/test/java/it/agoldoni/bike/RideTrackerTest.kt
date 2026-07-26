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
