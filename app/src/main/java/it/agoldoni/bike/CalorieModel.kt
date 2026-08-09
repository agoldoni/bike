package it.agoldoni.bike

/**
 * Stima delle calorie bruciate col metodo MET (Compendium of Physical Activities).
 *
 * Un MET è il consumo a riposo, convenzionalmente 1 kcal per kg di massa e per ora:
 * il consumo di un'attività è quindi `MET × massa × ore`. Il MET del ciclismo dipende
 * quasi solo dalla velocità, che è l'unico dato che il GPS dà senza sensori aggiuntivi.
 *
 * Il modello ignora la pendenza — è il suo limite principale: lo stesso percorso in
 * pianura e in salita produce la stessa stima, mentre il consumo reale può raddoppiare.
 * L'errore atteso è nell'ordine del 25-30%.
 *
 * Le calorie sono quelle *attive*: il metabolismo basale non è compreso, quindi il
 * valore è più basso di quello di app che invece lo sommano.
 */
object CalorieModel {

    /**
     * Soglia di velocità sotto la quale non si contano calorie.
     *
     * Serve a non far scorrere il contatore da fermi ai semafori: sotto i 3 km/h non si
     * sta pedalando, e il MET più basso della tabella applicato a tutte le soste
     * gonfierebbe il totale di un giro cittadino.
     */
    private const val MIN_SPEED_KMH = 3f

    /**
     * Soglie di velocità in km/h e MET corrispondente, dalla più veloce alla più lenta.
     *
     * Convertite dalle voci in mph del Compendium: 12-13.9 mph → 8.0 MET diventa
     * 19-22 km/h, e così via.
     */
    private val MET_BY_SPEED = listOf(
        30f to 15.8f, // gara, senza scia
        25f to 12.0f, // molto veloce
        22f to 10.0f, // veloce, sforzo intenso
        19f to 8.0f,  // sforzo moderato
        16f to 6.8f,  // sforzo leggero
        0f to 4.0f,   // passeggiata
    )

    fun metFor(speedKmh: Float): Float {
        if (speedKmh < MIN_SPEED_KMH) return 0f
        return MET_BY_SPEED.first { speedKmh >= it.first }.second
    }

    /**
     * Calorie bruciate in un intervallo percorso a [speedKmh] costante.
     *
     * [massKg] è ciclista più bici. Il MET sarebbe tarato sulla sola massa corporea:
     * sommarci la bici è un'approssimazione, che sovrastima di poco in pianura e
     * compensa in parte quello che il modello perde ignorando le salite.
     */
    fun kcalFor(speedKmh: Float, deltaMillis: Long, massKg: Float): Float {
        if (deltaMillis <= 0L) return 0f
        return metFor(speedKmh) * massKg * (deltaMillis / 3_600_000f)
    }
}
