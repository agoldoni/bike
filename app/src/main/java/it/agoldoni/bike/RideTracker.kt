package it.agoldoni.bike

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/**
 * Coordinata geografica del dominio. Volutamente distinta dal `GeoPoint` di osmdroid:
 * tiene la libreria di mappa confinata in [RideMap], così è sostituibile.
 *
 * Serializzabile perché i punti campionati di un giro salvato restano su disco finché il
 * loro nome non è stato risolto (vedi [SavedRide.pendingSamples]).
 */
@Serializable
data class GeoPoint(val lat: Double, val lon: Double)

data class RideState(
    val isTracking: Boolean = false,
    val speedKmh: Float = 0f,
    val distanceMeters: Float = 0f,
    val elapsedMillis: Long = 0L,
    /** Accuratezza dichiarata dell'ultimo fix, in metri; null finché non ne arriva uno. */
    val accuracyMeters: Float? = null,
    /**
     * Calorie attive stimate, accumulate secondo per secondo. Non è una grandezza
     * derivabile a fine giro dalla velocità media: una sosta lunga abbasserebbe la media
     * e con essa il MET di tutto il percorso, quindi va integrata mentre si pedala.
     */
    val kcal: Float = 0f,
    /** Ciclista più bici: il moltiplicatore del MET, fissato all'avvio del giro. */
    val totalMassKg: Float = RiderProfileStore.DEFAULT.totalKg,
) {
    val avgSpeedKmh: Float
        get() = if (elapsedMillis > 0) distanceMeters / (elapsedMillis / 1000f) * 3.6f else 0f
}

/**
 * Copia congelata di un giro appena concluso, in attesa che l'utente decida se salvarlo.
 *
 * Serve perché [RideTracker.onStart] azzera stato, posizione e tracciato: senza una copia,
 * un nuovo START mentre la domanda è ancora aperta farebbe sparire sotto le mani della UI
 * proprio il giro che sta chiedendo di salvare.
 */
data class FinishedRide(
    /** Istante di fine, orologio a muro: il cronometro del giro non basta a datarlo. */
    val endedAtMillis: Long,
    val state: RideState,
    val track: List<GeoPoint>,
)

/**
 * Stato del giro condiviso tra [TrackingService] (che lo aggiorna) e la UI
 * (che lo osserva). Singleton di processo: sopravvive alla ricreazione
 * dell'Activity finché il processo è vivo.
 *
 * Posizione e tracciato stanno su flow separati da [state] perché cambiano con ritmi
 * diversi: il cronometro emette ogni secondo anche da fermi, il tracciato solo quando
 * ci si sposta davvero. Tenerli insieme farebbe ridisegnare la mappa a ogni tick e
 * ricomporre il pannello dei numeri a ogni punto.
 */
object RideTracker {

    private val _state = MutableStateFlow(RideState())
    val state: StateFlow<RideState> = _state.asStateFlow()

    private val _position = MutableStateFlow<GeoPoint?>(null)
    val position: StateFlow<GeoPoint?> = _position.asStateFlow()

    private val _track = MutableStateFlow<List<GeoPoint>>(emptyList())
    val track: StateFlow<List<GeoPoint>> = _track.asStateFlow()

    /**
     * Giro appena concluso in attesa di risposta, oppure `null` se non c'è niente da
     * chiedere. Sta su un flow a sé perché cambia una volta per giro, mentre [state]
     * emette a ogni secondo.
     */
    private val _pendingRide = MutableStateFlow<FinishedRide?>(null)
    val pendingRide: StateFlow<FinishedRide?> = _pendingRide.asStateFlow()

    fun onStart(totalMassKg: Float = RiderProfileStore.DEFAULT.totalKg) {
        _state.value = RideState(isTracking = true, totalMassKg = totalMassKg)
        _position.value = null
        _track.value = emptyList()
        _pendingRide.value = null
    }

    /**
     * Fine del giro: le statistiche restano leggibili (il pannello continua a mostrarle)
     * e in più se ne congela una copia in [pendingRide], perché l'utente decida se
     * salvarla.
     *
     * L'istante è un parametro con valore di default invece di una lettura interna
     * dell'orologio, così i test possono datare un giro senza dipendere da quando girano.
     */
    fun onStop(endedAtMillis: Long = System.currentTimeMillis()) {
        // Uno STOP a giro già fermo non deve rimettere in coda un giro a cui l'utente ha
        // appena risposto: il service può ricevere ACTION_STOP più di una volta.
        if (!_state.value.isTracking) return
        _state.update { it.copy(isTracking = false, speedKmh = 0f) }
        _pendingRide.value = FinishedRide(
            endedAtMillis = endedAtMillis,
            state = _state.value,
            track = _track.value,
        )
    }

    /** L'utente ha risposto — salvato o scartato: la domanda non va più posta. */
    fun onRideHandled() {
        _pendingRide.value = null
    }

    /**
     * Avanza il cronometro e, con esso, le calorie: il tempo trascorso dall'ultimo tick
     * si considera percorso alla velocità corrente. Il ticker batte ogni secondo, ma il
     * delta si ricava dai due valori di [RideState.elapsedMillis] invece di darlo per
     * scontato, così un tick in ritardo non perde né inventa energia.
     */
    fun onElapsed(elapsedMillis: Long) {
        _state.update {
            it.copy(
                elapsedMillis = elapsedMillis,
                kcal = it.kcal + CalorieModel.kcalFor(
                    speedKmh = it.speedKmh,
                    deltaMillis = elapsedMillis - it.elapsedMillis,
                    massKg = it.totalMassKg,
                ),
            )
        }
    }

    /** Nessuno spostamento significativo di recente: la velocità mostrata torna a zero. */
    fun onSpeedLost() {
        _state.update { if (it.speedKmh == 0f) it else it.copy(speedKmh = 0f) }
    }

    /**
     * Nuova posizione dal GPS, qualunque sia la sua qualità.
     *
     * Va tenuta distinta da [onSample]: un fix impreciso serve comunque a inquadrare la
     * mappa e a far capire che il segnale c'è, mentre sarebbe dannoso sommarlo alla
     * distanza percorsa.
     */
    fun onPosition(position: GeoPoint, accuracyMeters: Float?) {
        _position.value = position
        _state.update { it.copy(accuracyMeters = accuracyMeters) }
    }

    fun onSample(speedKmh: Float, deltaMeters: Float) {
        _state.update {
            it.copy(speedKmh = speedKmh, distanceMeters = it.distanceMeters + deltaMeters)
        }
    }

    /**
     * Aggiunge un vertice al tracciato. L'accodamento copia la lista, quindi il
     * chiamante deve invocarlo di rado (un punto ogni [TrackingService.TRACK_STEP_M]
     * metri) e non a ogni campione GPS.
     */
    fun onTrackPoint(position: GeoPoint) {
        _track.update { it + position }
    }
}
