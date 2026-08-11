package it.agoldoni.bike

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Riduzione del tracciato ai suoi punti salienti.
 *
 * Oggetto puro senza dipendenze Android, come [CalorieModel]: è la parte della feature
 * che decide *quanto* si va a chiedere al servizio di geocoding e *che aspetto* avrà il
 * sommario, quindi deve restare interamente coperta dagli unit test JVM.
 */
object RouteDigest {

    /**
     * Distanza fra due punti di cui si chiede il nome.
     *
     * Il tracciato ha un vertice ogni [TrackingService.TRACK_STEP_M] (10 m): un giro di
     * 30 km sono circa 3000 vertici, e chiedere il nome di ognuno significherebbe quasi
     * un'ora di richieste e il blocco certo dell'IP. A 1 km lo stesso giro costa 31
     * richieste, che è anche la granularità giusta per un sommario: sotto il chilometro
     * si resta quasi sempre nello stesso comune e il nome si ripeterebbe.
     */
    const val SAMPLE_STEP_M = 1000.0

    /**
     * Tetto di richieste per giro, qualunque sia la lunghezza.
     *
     * Oltre i 40 km il passo si allarga invece di troncare la coda: troncare taglierebbe
     * la fine del giro, cioè quasi sempre il rientro — la parte che chi rilegge il
     * sommario si aspetta di trovarci.
     */
    const val MAX_SAMPLES = 40

    /**
     * Punti di cui chiedere il nome, dal primo all'ultimo del tracciato.
     *
     * Partenza e arrivo ci sono sempre: sono i due estremi che identificano il giro anche
     * quando in mezzo non si è usciti dal comune.
     */
    fun sample(track: List<GeoPoint>): List<GeoPoint> {
        if (track.size <= 1) return track

        val total = track.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
        // Con un tracciato lungo il passo si allarga fino a far stare tutto nel tetto.
        // Il divisore è MAX_SAMPLES - 1 perché il primo punto è già occupato.
        val step = max(SAMPLE_STEP_M, total / (MAX_SAMPLES - 1))

        val sampled = mutableListOf(track.first())
        var sinceLast = 0.0
        for ((previous, current) in track.zipWithNext()) {
            sinceLast += distanceMeters(previous, current)
            // L'ultimo posto è riservato al punto d'arrivo, aggiunto qui sotto.
            if (sinceLast >= step && sampled.size < MAX_SAMPLES - 1) {
                sampled += current
                sinceLast = 0.0
            }
        }
        if (sampled.last() != track.last()) sampled += track.last()
        return sampled
    }

    /**
     * Sequenza di località leggibile, a partire dai nomi dei punti campionati.
     *
     * I punti non risolti (`null`) si scartano senza spezzare la sequenza: un buco di
     * copertura in mezzo al giro non deve far sembrare che si sia usciti e rientrati
     * nello stesso comune.
     *
     * Si eliminano solo le ripetizioni **consecutive**: un giro tutto dentro un comune
     * dà una voce sola, mentre andata e ritorno resta `A → B → A`, che è l'informazione
     * che distingue un percorso circolare da uno di sola andata.
     */
    fun dedupConsecutive(places: List<String?>): List<String> {
        val result = mutableListOf<String>()
        for (place in places) {
            val name = place?.trim().orEmpty()
            if (name.isEmpty()) continue
            if (result.lastOrNull()?.equals(name, ignoreCase = true) == true) continue
            result += name
        }
        return result
    }

    /**
     * Distanza in metri fra due coordinate (formula dell'emisenoverso).
     *
     * Scritta a mano invece di usare `Location.distanceTo`, che è Android e renderebbe
     * questo oggetto non testabile in JVM. Alle distanze in gioco — chilometri — la
     * differenza dal calcolo ellissoidico è ampiamente sotto la soglia che conta qui.
     */
    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
        val phi1 = Math.toRadians(from.lat)
        val phi2 = Math.toRadians(to.lat)
        val deltaPhi = Math.toRadians(to.lat - from.lat)
        val deltaLambda = Math.toRadians(to.lon - from.lon)
        val a = sin(deltaPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a).coerceAtMost(1.0))
    }

    private const val EARTH_RADIUS_M = 6_371_000.0
}
