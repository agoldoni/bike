package it.agoldoni.bike

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Giro concluso e salvato dall'utente.
 *
 * Volutamente distinto da [RideState], che è lo stato *vivo* del giro: quello non ha né
 * data né identità e viene azzerato a ogni START, questo è un record immutabile su disco.
 *
 * Del percorso non si conserva il tracciato ma le sole [places], cioè le località
 * attraversate in ordine: un elenco di migliaia di coordinate non dice niente a chi lo
 * rilegge fra un mese, mentre «Modena → Formigine → Maranello» identifica il giro a
 * colpo d'occhio e sta in poche centinaia di byte.
 *
 * L'identità del giro è [endedAtMillis]: due STOP non cadono nello stesso millisecondo,
 * quindi un campo `id` a parte sarebbe solo una chiave in più da tenere coerente.
 */
@Serializable
data class SavedRide(
    /** Istante di fine giro, orologio a muro. Fa anche da identificativo. */
    val endedAtMillis: Long,
    val elapsedMillis: Long,
    val distanceMeters: Float,
    val kcal: Float,
    /** Massa con cui il giro ha contato le calorie, congelata all'avvio. */
    val totalMassKg: Float,
    /** Località attraversate, in ordine di percorrenza. Vuota finché non sono risolte. */
    val places: List<String> = emptyList(),
    /**
     * Il reverse geocoding è arrivato in fondo.
     *
     * Distingue «giro senza località perché non si è potuto risolverle» da «giro non
     * ancora elaborato»: il primo va ritentato all'apertura dello storico, il secondo
     * anche, ma all'utente vanno detti in modo diverso. Con [places] vuota e questo
     * flag a `true` la risposta è definitiva: là non c'era nessun nome da trovare.
     */
    val placesResolved: Boolean = false,
    /**
     * Punti di cui resta da chiedere il nome, già campionati dal tracciato.
     *
     * Senza di loro il ritentativo dopo un giro fatto in una valle senza rete sarebbe
     * impossibile: il tracciato completo non si conserva e a fine giro è già sparito.
     * Sono al massimo [RouteDigest.MAX_SAMPLES] coppie di coordinate, e si svuotano
     * appena il geocoding riesce — il costo su disco esiste solo per i giri in attesa.
     */
    val pendingSamples: List<GeoPoint> = emptyList(),
) {
    /** Derivata come in [RideState]: salvarla sarebbe un dato in più da tenere coerente. */
    val avgSpeedKmh: Float
        get() = if (elapsedMillis > 0) distanceMeters / (elapsedMillis / 1000f) * 3.6f else 0f
}

/**
 * Giro concluso pronto per lo storico.
 *
 * Il tracciato si riduce qui, una volta sola: da questo momento in poi del percorso
 * restano solo i punti di cui chiedere il nome, e appena i nomi arrivano spariscono
 * anche quelli.
 */
fun FinishedRide.toSavedRide(): SavedRide = SavedRide(
    endedAtMillis = endedAtMillis,
    elapsedMillis = state.elapsedMillis,
    distanceMeters = state.distanceMeters,
    kcal = state.kcal,
    totalMassKg = state.totalMassKg,
    pendingSamples = RouteDigest.sample(track),
)

/** Radice del file su disco. Il numero di versione serve a [RideArchiveFormat]. */
@Serializable
private data class RideArchive(
    val version: Int = RideArchiveFormat.CURRENT_VERSION,
    val rides: List<SavedRide> = emptyList(),
)

/**
 * Traduzione fra la lista dei giri e il testo scritto su disco.
 *
 * Sta fuori da [RideStore] perché non ha bisogno di un `Context`: così il formato del
 * file — l'unica parte della feature che non si può più cambiare liberamente dopo il
 * primo salvataggio — resta coperto dagli unit test JVM.
 */
object RideArchiveFormat {

    /**
     * Alzarlo solo per cambiamenti che la lettura tollerante qui sotto non regge da sola
     * (un campo rinominato, un'unità di misura diversa). Aggiungere campi non lo richiede.
     */
    const val CURRENT_VERSION = 1

    private val json = Json {
        // Un giro scritto da una versione futura dell'app, con campi che questa non
        // conosce, deve restare leggibile invece di far sparire tutto lo storico.
        ignoreUnknownKeys = true
        // I valori di default vanno scritti comunque: un file in cui `places` è assente
        // è indistinguibile da uno troncato quando lo si legge a occhio.
        encodeDefaults = true
    }

    fun encode(rides: List<SavedRide>): String =
        json.encodeToString(RideArchive(rides = rides))

    /**
     * Giri contenuti nel testo, dal più recente al più vecchio.
     *
     * L'ordinamento sta qui e non nella UI perché è la garanzia che tutti i lettori si
     * aspettano, e qui è l'unico punto in cui può essere verificata da un test.
     *
     * Un testo illeggibile — file troncato da una morte del processo, o scritto da
     * tutt'altro — produce una lista vuota: si perde lo storico, ma l'app parte. Andare
     * in crash all'avvio sarebbe il modo peggiore di comunicare lo stesso problema.
     */
    fun decode(text: String): List<SavedRide> {
        if (text.isBlank()) return emptyList()
        return try {
            json.decodeFromString<RideArchive>(text).rides.sortedByDescending { it.endedAtMillis }
        } catch (_: IllegalArgumentException) {
            // SerializationException eredita da IllegalArgumentException: il catch copre
            // sia il JSON malformato sia lo schema incompatibile.
            emptyList()
        }
    }
}
