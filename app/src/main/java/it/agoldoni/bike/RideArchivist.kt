package it.agoldoni.bike

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Messa in archivio di un giro concluso e risoluzione delle sue località.
 *
 * Gira su uno scope di processo e non nell'Activity né nel service: il primo muore a ogni
 * rotazione dello schermo, il secondo si autodistrugge proprio allo STOP. La scrittura su
 * disco dura un istante ma non deve poter essere interrotta a metà da una rotazione; la
 * risoluzione delle località dura decine di secondi — al massimo [RouteDigest.MAX_SAMPLES]
 * richieste a un servizio che ne accetta una al secondo.
 *
 * Non c'è nessuna garanzia che la risoluzione arrivi in fondo: se il sistema uccide il
 * processo, il giro resta salvato con le sole statistiche e i suoi punti campionati,
 * pronto per il tentativo successivo. È il motivo per cui non serve `WorkManager` e la
 * sua dipendenza: il ritentativo costa una riga all'apertura dello storico.
 */
object RideArchivist {

    // IO e non Default: qui si scrive su disco e si aspetta la rete, non si calcola.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Un solo giro alla volta, e soprattutto un solo lavoro alla volta: senza, riaprire
    // lo storico due volte di fila lancerebbe due volte le stesse richieste.
    private val running = AtomicBoolean(false)

    private val _resolving = MutableStateFlow(false)

    /**
     * C'è un lavoro di risoluzione in corso.
     *
     * Lo storico se ne serve per distinguere «le località stanno arrivando» da «non si
     * sono potute avere»: senza, un giro fatto senza rete resterebbe in eterno con la
     * scritta «in arrivo», che è una bugia.
     */
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    /**
     * Mette il giro nello storico e ne chiede subito le località.
     *
     * L'utente ha già avuto la sua risposta — il dialog si chiude all'istante — e da qui
     * in poi il lavoro non dipende più da nessuna schermata: è il motivo per cui non si
     * usa lo scope della composizione, che una rotazione dello schermo cancellerebbe
     * proprio mentre si scrive il giro appena salvato.
     */
    fun save(context: Context, ride: FinishedRide) {
        val appContext = context.applicationContext
        scope.launch {
            RideStore.add(appContext, ride.toSavedRide())
            resolvePending(appContext)
        }
    }

    /**
     * Chiede il nome dei luoghi di tutti i giri che ne sono ancora privi.
     *
     * Va chiamata dopo aver salvato un giro e all'apertura dello storico. Senza rete non
     * fa nulla: tentare significherebbe solo aspettare il timeout di ogni richiesta.
     */
    fun resolvePending(context: Context) {
        val appContext = context.applicationContext
        if (!appContext.hasNetwork()) return
        if (!running.compareAndSet(false, true)) return

        scope.launch {
            _resolving.value = true
            try {
                // La cache vive quanto il lavoro: ha senso fra i punti di uno stesso giro
                // e fra giri che ripassano dalle stesse strade, non oltre.
                val resolver = CachingPlaceResolver(
                    ChainedPlaceResolver(
                        primary = SystemGeocoder(appContext),
                        fallback = NominatimGeocoder(),
                    )
                )
                RideStore.load(appContext)
                    .filter { !it.placesResolved && it.pendingSamples.isNotEmpty() }
                    .forEach { ride -> resolve(appContext, ride, resolver) }
            } finally {
                _resolving.value = false
                running.set(false)
            }
        }
    }

    private suspend fun resolve(context: Context, ride: SavedRide, resolver: PlaceResolver) {
        val names = ride.pendingSamples.map { resolver.placeAt(it) }
        // Nessun nome su decine di punti lungo una strada non è «qui non c'è niente»: è
        // il servizio che non ha risposto. Si lascia il giro da ritentare invece di
        // dichiararlo risolto e senza località per sempre.
        if (names.all { it == null }) return
        RideStore.updatePlaces(
            context = context,
            endedAtMillis = ride.endedAtMillis,
            places = RouteDigest.dedupConsecutive(names),
        )
    }

    private fun Context.hasNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
