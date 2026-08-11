package it.agoldoni.bike

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * Storico dei giri salvati, su file privato dell'app.
 *
 * Non usa le `SharedPreferences` con nome `"bike"` degli altri store del progetto
 * ([RiderProfileStore], [LastKnownPosition]): quelle reggono bene poche chiavi scalari,
 * non una lista di record con liste annidate dentro.
 *
 * Le operazioni sono sincrone e riscrivono l'intero file. Con qualche centinaio di byte
 * per giro anche dieci anni di uscite quotidiane stanno in circa un megabyte, quindi
 * leggere tutto in un colpo solo è più semplice e più sicuro di un database — ma proprio
 * per questo i chiamanti devono invocarle fuori dal thread principale.
 */
object RideStore {

    private const val FILE_NAME = "rides.json"

    // Scrittura e lettura possono arrivare insieme dalla UI e dal lavoro di geocoding in
    // background: senza questo lock due riscritture concorrenti si sovrascriverebbero a
    // vicenda, e chi ha perso la corsa avrebbe già detto all'utente «salvato».
    private val lock = Any()

    private val _revision = MutableStateFlow(0L)

    /**
     * Cambia a ogni scrittura andata a buon fine.
     *
     * Serve alla schermata dello storico per rileggere il file quando le località di un
     * giro arrivano mentre l'utente lo sta già guardando. Si espone un contatore e non la
     * lista perché lo storico si legge da disco solo quando serve, non a ogni ricomposizione.
     */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Giri salvati, dal più recente al più vecchio. Storico assente o illeggibile → vuoto. */
    fun load(context: Context): List<SavedRide> = synchronized(lock) { read(context) }

    fun add(context: Context, ride: SavedRide) = synchronized(lock) {
        write(context, read(context) + ride)
    }

    /**
     * Registra l'esito del reverse geocoding su un giro già salvato.
     *
     * Il giro può nel frattempo essere stato cancellato dall'utente: in quel caso non c'è
     * niente da aggiornare e la chiamata non deve farlo ricomparire.
     */
    fun updatePlaces(
        context: Context,
        endedAtMillis: Long,
        places: List<String>,
    ) = synchronized(lock) {
        val rides = read(context)
        if (rides.none { it.endedAtMillis == endedAtMillis }) return@synchronized
        write(
            context,
            rides.map {
                if (it.endedAtMillis == endedAtMillis) {
                    // I punti campionati hanno esaurito il loro scopo: tenerli sarebbe
                    // solo peso su disco e coordinate conservate senza motivo.
                    it.copy(places = places, placesResolved = true, pendingSamples = emptyList())
                } else {
                    it
                }
            },
        )
    }

    fun delete(context: Context, endedAtMillis: Long) = synchronized(lock) {
        write(context, read(context).filterNot { it.endedAtMillis == endedAtMillis })
    }

    private fun read(context: Context): List<SavedRide> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            RideArchiveFormat.decode(file.readText())
        } catch (_: IOException) {
            emptyList()
        }
    }

    /**
     * Scrittura atomica: prima il file temporaneo, poi il rename.
     *
     * Riscrivendo lo storico per intero a ogni salvataggio, una morte del processo a metà
     * scrittura lascerebbe un file troncato — cioè la perdita di *tutti* i giri, non solo
     * dell'ultimo. Il rename sullo stesso filesystem è invece istantaneo: o c'è il file
     * vecchio o c'è quello nuovo.
     */
    private fun write(context: Context, rides: List<SavedRide>) {
        val target = File(context.filesDir, FILE_NAME)
        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        try {
            temp.writeText(RideArchiveFormat.encode(rides))
            if (!temp.renameTo(target)) {
                // Il rename fallisce se il file di destinazione esiste già su alcune
                // implementazioni: si riprova dopo averlo tolto di mezzo.
                target.delete()
                if (!temp.renameTo(target)) return
            }
            _revision.value++
        } catch (_: IOException) {
            // Disco pieno o permessi revocati: il giro non viene salvato, ma lo storico
            // già su disco resta intatto ed è il risultato meno dannoso possibile.
        } finally {
            temp.delete()
        }
    }
}
