package it.agoldoni.bike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Reverse geocoding di ripiego tramite Nominatim (OpenStreetMap).
 *
 * Coerente con la mappa già in uso e funzionante anche dove Google Play services non
 * c'è, ma è un servizio pubblico gratuito con obblighi precisi, che questa classe
 * rispetta: uno [USER_AGENT] identificativo con un contatto, e **una sola richiesta al
 * secondo**. Il limite è serializzato da un mutex tenuto per tutta la durata della
 * chiamata, quindi vale anche se il chiamante lancia più risoluzioni in parallelo.
 *
 * Si usa `HttpURLConnection` invece di un client HTTP vero: qui c'è una sola GET, e con
 * `isMinifyEnabled = false` ogni dipendenza in più pesa per intero sull'APK.
 */
class NominatimGeocoder : PlaceResolver {

    private val mutex = Mutex()
    private var lastRequestAtMillis = 0L

    override suspend fun placeAt(point: GeoPoint): String? = mutex.withLock {
        val sinceLast = System.currentTimeMillis() - lastRequestAtMillis
        if (sinceLast < MIN_INTERVAL_MS) delay(MIN_INTERVAL_MS - sinceLast)
        lastRequestAtMillis = System.currentTimeMillis()
        withContext(Dispatchers.IO) { request(point) }
    }

    private fun request(point: GeoPoint): String? {
        // Locale.ROOT: con la virgola decimale italiana l'URL sarebbe malformato.
        val url = URL(
            String.format(
                Locale.ROOT,
                "%s?format=jsonv2&addressdetails=1&zoom=%d&lat=%.6f&lon=%.6f",
                ENDPOINT, ZOOM, point.lat, point.lon,
            )
        )
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", USER_AGENT)
                // I nomi devono essere quelli dei cartelli, non l'esonimo inglese.
                setRequestProperty("Accept-Language", "it")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            parsePlace(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: IOException) {
            // Nessuna rete, DNS muto, servizio giù: il giro è già salvato, qui si
            // risponde solo «non lo so».
            null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val ENDPOINT = "https://nominatim.openstreetmap.org/reverse"

        /**
         * La usage policy pretende un identificativo con un contatto raggiungibile: serve
         * a farsi avvertire in caso di uso anomalo invece che bloccare in silenzio.
         */
        private const val USER_AGENT = "Bike/1.0 (it.agoldoni.bike; alberto.goldoni@gmail.com)"

        /** Livello «comune». Più fine spezzerebbe i giri lunghi in decine di frazioni. */
        private const val ZOOM = 10

        private const val MIN_INTERVAL_MS = 1_000L
        private const val TIMEOUT_MS = 10_000

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Nome della località dentro una risposta di Nominatim.
         *
         * Pubblica e senza dipendenze Android perché è la parte fragile — lo schema è di
         * qualcun altro e può cambiare — ed è l'unica di questa classe che gli unit test
         * JVM possono esercitare davvero.
         */
        fun parsePlace(body: String): String? = try {
            json.decodeFromString<Response>(body).address?.let {
                firstNonBlank(it.city, it.town, it.village, it.municipality, it.county)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @Serializable
    private data class Response(val address: Address? = null)

    /**
     * Il campo che contiene il comune cambia con la dimensione del centro abitato:
     * `city` per le città, `town` per i paesi, `village` per i borghi. `municipality` e
     * `county` sono l'ultima spiaggia in zone poco mappate.
     */
    @Serializable
    private data class Address(
        val city: String? = null,
        val town: String? = null,
        val village: String? = null,
        val municipality: String? = null,
        val county: String? = null,
    )
}
