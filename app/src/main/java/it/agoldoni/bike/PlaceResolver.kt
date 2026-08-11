package it.agoldoni.bike

import java.util.Locale

/**
 * Nome della località in cui cade un punto: il comune, o quanto di più simile la
 * sorgente sa dire.
 *
 * È l'unica frontiera verso il mondo esterno della feature, come [RideMap] lo è verso
 * osmdroid: dietro questa interfaccia si può cambiare servizio senza che il resto del
 * codice se ne accorga, e nei test si può metterci un finto.
 */
interface PlaceResolver {
    /** `null` quando il nome non si è potuto ricavare — nessuna eccezione verso l'alto. */
    suspend fun placeAt(point: GeoPoint): String?
}

/**
 * Primo valore utile fra quelli passati, in ordine di preferenza.
 *
 * Le due sorgenti restituiscono campi diversi e non sempre popolati — in aperta campagna
 * il comune è spesso assente e resta solo la provincia. Senza una regola comune lo stesso
 * giro produrrebbe sommari diversi a seconda di chi ha risposto.
 */
fun firstNonBlank(vararg candidates: String?): String? =
    candidates.firstOrNull { !it.isNullOrBlank() }?.trim()

/**
 * Prova la sorgente primaria e ripiega sulla seconda quando questa non sa rispondere.
 *
 * Il ripiego scatta su `null`, che comprende sia il servizio assente sia quello che non
 * risponde entro il tempo massimo: dal punto di vista del giro sono lo stesso caso.
 */
class ChainedPlaceResolver(
    private val primary: PlaceResolver,
    private val fallback: PlaceResolver,
) : PlaceResolver {
    override suspend fun placeAt(point: GeoPoint): String? =
        primary.placeAt(point) ?: fallback.placeAt(point)
}

/**
 * Ricorda le risposte già ottenute, per cella di circa un chilometro.
 *
 * Serve soprattutto ai percorsi di andata e ritorno, dove i punti campionati al rientro
 * ricadono sulla stessa strada dell'andata: senza cache si pagherebbe due volte la stessa
 * risposta, e su Nominatim ogni richiesta risparmiata è un secondo di attesa in meno.
 *
 * La cella è larga quanto il passo di campionamento, quindi non collassa punti che il
 * campionamento ha voluto distinti.
 */
class CachingPlaceResolver(private val delegate: PlaceResolver) : PlaceResolver {

    private val cache = mutableMapOf<String, String?>()

    override suspend fun placeAt(point: GeoPoint): String? {
        val key = cell(point)
        // containsKey e non il valore: anche «qui non c'è nome» è una risposta da
        // ricordare, altrimenti i punti muti si richiederebbero all'infinito.
        if (cache.containsKey(key)) return cache[key]
        return delegate.placeAt(point).also { cache[key] = it }
    }

    /** 0,01° di latitudine sono circa 1,1 km: la stessa scala del passo di campionamento. */
    private fun cell(point: GeoPoint) =
        String.format(Locale.ROOT, "%.2f,%.2f", point.lat, point.lon)
}
