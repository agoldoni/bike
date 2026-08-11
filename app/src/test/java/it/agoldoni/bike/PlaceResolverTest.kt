package it.agoldoni.bike

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Catena, cache e lettura della risposta di Nominatim: le parti del geocoding che non
 * hanno bisogno di un dispositivo, e quindi le uniche verificabili qui.
 *
 * Restano fuori dai test — e vanno provate a mano — il `Geocoder` di sistema e il
 * throttling a una richiesta al secondo, che dipende dall'orologio reale.
 */
class PlaceResolverTest {

    private val point = GeoPoint(44.6471, 10.9252)

    /** Risponde sempre lo stesso e conta quante volte gliel'hanno chiesto. */
    private class FakeResolver(private val answer: String?) : PlaceResolver {
        var calls = 0
            private set

        override suspend fun placeAt(point: GeoPoint): String? {
            calls++
            return answer
        }
    }

    @Test
    fun `firstNonBlank sceglie il primo valore utile`() {
        assertEquals("Modena", firstNonBlank(null, "Modena", "Emilia-Romagna"))
    }

    @Test
    fun `firstNonBlank scarta vuoti e spazi`() {
        assertEquals("Modena", firstNonBlank(null, "", "   ", " Modena "))
    }

    @Test
    fun `firstNonBlank senza candidati utili risponde null`() {
        assertNull(firstNonBlank(null, "", "  "))
    }

    @Test
    fun `la catena ripiega quando la primaria non sa rispondere`() = runBlocking {
        val primary = FakeResolver(null)
        val fallback = FakeResolver("Modena")

        val place = ChainedPlaceResolver(primary, fallback).placeAt(point)

        assertEquals("Modena", place)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `la catena non disturba il ripiego se la primaria ha risposto`() = runBlocking {
        val primary = FakeResolver("Modena")
        val fallback = FakeResolver("Sbagliata")

        val place = ChainedPlaceResolver(primary, fallback).placeAt(point)

        assertEquals("Modena", place)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `la cache evita la seconda richiesta per lo stesso punto`() = runBlocking {
        val delegate = FakeResolver("Modena")
        val cached = CachingPlaceResolver(delegate)

        cached.placeAt(point)
        val second = cached.placeAt(point)

        assertEquals("Modena", second)
        assertEquals(1, delegate.calls)
    }

    @Test
    fun `la cache ricorda anche le risposte negative`() = runBlocking {
        // Senza, un punto senza nome verrebbe richiesto a ogni passaggio.
        val delegate = FakeResolver(null)
        val cached = CachingPlaceResolver(delegate)

        cached.placeAt(point)
        cached.placeAt(point)

        assertEquals(1, delegate.calls)
    }

    @Test
    fun `la cache distingue punti lontani`() = runBlocking {
        val delegate = FakeResolver("Modena")
        val cached = CachingPlaceResolver(delegate)

        cached.placeAt(point)
        cached.placeAt(GeoPoint(44.5, 10.8))

        assertEquals(2, delegate.calls)
    }

    @Test
    fun `parsePlace legge la citta dalla risposta di Nominatim`() {
        val body = """{"address":{"city":"Modena","county":"Modena","state":"Emilia-Romagna"}}"""

        assertEquals("Modena", NominatimGeocoder.parsePlace(body))
    }

    @Test
    fun `parsePlace ripiega sui campi dei centri piu piccoli`() {
        // Il campo che contiene il comune cambia con la dimensione del centro abitato.
        val town = """{"address":{"town":"Formigine","county":"Modena"}}"""
        val village = """{"address":{"village":"Magreta","county":"Modena"}}"""
        val county = """{"address":{"county":"Modena"}}"""

        assertEquals("Formigine", NominatimGeocoder.parsePlace(town))
        assertEquals("Magreta", NominatimGeocoder.parsePlace(village))
        assertEquals("Modena", NominatimGeocoder.parsePlace(county))
    }

    @Test
    fun `parsePlace tollera una risposta senza indirizzo`() {
        assertNull(NominatimGeocoder.parsePlace("""{"error":"Unable to geocode"}"""))
    }

    @Test
    fun `parsePlace non esplode su un corpo malformato`() {
        assertNull(NominatimGeocoder.parsePlace("<html>429 Too Many Requests</html>"))
    }
}
