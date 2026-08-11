package it.agoldoni.bike

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Reverse geocoding tramite il servizio di sistema.
 *
 * È la sorgente primaria perché non costa richieste di rete all'app né è soggetta a
 * limiti d'uso. Non è però sempre disponibile: sulle immagini AOSP senza Google Play
 * services — fra cui l'emulatore usato da `demo-ride.sh` — non risponde affatto, ed è la
 * ragione per cui esiste il ripiego su Nominatim.
 */
class SystemGeocoder(context: Context) : PlaceResolver {

    // Locale italiano: i nomi devono essere gli stessi che si leggono sui cartelli.
    private val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.ITALY) else null

    override suspend fun placeAt(point: GeoPoint): String? {
        val geocoder = geocoder ?: return null
        // Il timeout non serve solo contro il servizio assente: capita che isPresent()
        // sia vero e la chiamata resti comunque appesa. Senza, il salvataggio di un giro
        // resterebbe in sospeso a tempo indeterminato.
        return withTimeoutOrNull(TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.firstAddressAsync(point)
            } else {
                withContext(Dispatchers.IO) { geocoder.firstAddressBlocking(point) }
            }
        }?.toPlaceName()
    }

    /** Da API 33 la variante bloccante è deprecata a favore di questa, a callback. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun Geocoder.firstAddressAsync(point: GeoPoint): Address? =
        suspendCancellableCoroutine { continuation ->
            getFromLocation(point.lat, point.lon, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    continuation.resume(addresses.firstOrNull())
                }

                override fun onError(errorMessage: String?) {
                    // Un errore del servizio di sistema non è un errore del giro: si
                    // risponde «non lo so» e tocca al ripiego provarci.
                    continuation.resume(null)
                }
            })
        }

    /**
     * Ramo per API 26-32, dove la variante a callback non esiste ancora.
     *
     * La chiamata è bloccante e non si interrompe: allo scadere del timeout la coroutine
     * prosegue, ma il thread di IO resta occupato finché il servizio non risponde. È il
     * prezzo dell'API vecchia, e vale solo per un thread del pool.
     */
    @Suppress("DEPRECATION")
    private fun Geocoder.firstAddressBlocking(point: GeoPoint): Address? = try {
        getFromLocation(point.lat, point.lon, 1)?.firstOrNull()
    } catch (_: IOException) {
        null
    }

    /**
     * Comune, altrimenti provincia, altrimenti regione.
     *
     * `locality` è nullo in aperta campagna: senza la scala di ripiego un giro fuori dai
     * centri abitati produrrebbe un sommario vuoto.
     */
    private fun Address.toPlaceName(): String? = firstNonBlank(locality, subAdminArea, adminArea)

    private companion object {
        // Generoso: al primo utilizzo dopo l'avvio il servizio può metterci qualche
        // secondo, e scadere troppo presto manderebbe su Nominatim richieste inutili.
        const val TIMEOUT_MS = 5_000L
    }
}
