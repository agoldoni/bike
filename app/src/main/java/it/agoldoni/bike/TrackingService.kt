package it.agoldoni.bike

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service che campiona la posizione (fused provider, ~1 Hz) e
 * aggiorna [RideTracker] con velocità istantanea e distanza cumulata.
 */
class TrackingService : Service() {

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null
    private var startElapsedRealtime = 0L
    private var lastStepElapsedRealtime = 0L
    private var lastLocation: Location? = null
    private var lastTrackLocation: Location? = null
    // L'ultima posizione si salva una volta per giro: basta a far ripartire la mappa
    // da lì, senza scrivere su disco a ogni fix.
    private val lastKnownSaved = AtomicBoolean(false)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // il permesso è verificato dalla Activity prima dell'avvio
    private fun start() {
        if (RideTracker.state.value.isTracking) return

        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )

        RideTracker.onStart()
        lastLocation = null
        lastTrackLocation = null
        lastKnownSaved.set(false)
        startElapsedRealtime = SystemClock.elapsedRealtime()
        lastStepElapsedRealtime = 0L
        tickerJob = scope.launch {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                RideTracker.onElapsed(now - startElapsedRealtime)
                // Nessuno spostamento significativo da un po': siamo fermi.
                if (lastStepElapsedRealtime > 0 && now - lastStepElapsedRealtime > STALE_SPEED_MS) {
                    RideTracker.onSpeedLost()
                }
                delay(1000)
            }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, SAMPLING_INTERVAL_MS)
            .setMinUpdateIntervalMillis(SAMPLING_INTERVAL_MS / 2)
            .build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun onLocation(location: Location) {
        val point = location.toGeoPoint()
        val accuracy = if (location.hasAccuracy()) location.accuracy else null

        // La posizione si mostra sempre, anche imprecisa: serve a inquadrare la mappa e
        // a far capire che il segnale sta arrivando. Il filtro di accuratezza qui sotto
        // protegge solo il conteggio di distanza e velocità.
        RideTracker.onPosition(point, accuracy)
        if (lastKnownSaved.compareAndSet(false, true)) LastKnownPosition.save(this, point)

        if (accuracy != null && accuracy > MAX_ACCURACY_M) return

        val previous = lastLocation
        if (previous == null) {
            lastLocation = location
            lastTrackLocation = location
            lastStepElapsedRealtime = SystemClock.elapsedRealtime()
            RideTracker.onTrackPoint(point)
            return
        }

        // Soglia minima di spostamento: da fermo il jitter GPS gonfierebbe la
        // distanza, e il fused provider ripete la stessa posizione fra un fix e
        // l'altro. Questi campioni non aggiornano nulla — la velocità resta
        // l'ultima misurata finché il ticker non la dichiara scaduta.
        val deltaMeters = previous.distanceTo(location)
        if (deltaMeters < MIN_STEP_M) return

        // La velocità del provider è preferibile (doppler), ma alcune sorgenti —
        // fra cui i fix simulati dell'emulatore — la riportano sempre a zero:
        // in quel caso si ricade sul rapporto spazio/tempo fra due fix.
        val providerSpeedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        val dtSec = (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1e9f
        val derivedSpeedKmh = if (dtSec > 0) deltaMeters / dtSec * 3.6f else 0f

        lastLocation = location
        lastStepElapsedRealtime = SystemClock.elapsedRealtime()
        RideTracker.onSample(maxOf(providerSpeedKmh, derivedSpeedKmh), deltaMeters)

        // Il tracciato è più rado dei campioni: un vertice ogni TRACK_STEP_M disegna
        // la stessa strada con un quarto dei punti, e ogni punto in meno è una copia
        // della lista in meno (vedi RideTracker.onTrackPoint).
        val trackAnchor = lastTrackLocation
        if (trackAnchor == null || trackAnchor.distanceTo(location) >= TRACK_STEP_M) {
            lastTrackLocation = location
            RideTracker.onTrackPoint(point)
        }
    }

    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)

    private fun stop() {
        fusedClient.removeLocationUpdates(locationCallback)
        tickerJob?.cancel()
        rememberLastPosition()
        RideTracker.onStop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onDestroy() {
        fusedClient.removeLocationUpdates(locationCallback)
        tickerJob?.cancel()
        // Anche quando il sistema uccide il service: alla riapertura la mappa parte
        // da dove eravamo, non da capo.
        rememberLastPosition()
        super.onDestroy()
    }

    private fun rememberLastPosition() {
        lastLocation?.let { LastKnownPosition.save(this, it.toGeoPoint()) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "it.agoldoni.bike.action.START"
        const val ACTION_STOP = "it.agoldoni.bike.action.STOP"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLING_INTERVAL_MS = 1000L
        // Soglia per accettare un fix nel conteggio di distanza e velocità. Larga:
        // in città fra i palazzi l'accuratezza dichiarata sta spesso sopra i 20 m e
        // una soglia stretta lascerebbe il contachilometri fermo a zero.
        private const val MAX_ACCURACY_M = 35f
        private const val MIN_STEP_M = 2f
        private const val STALE_SPEED_MS = 4000L
        const val TRACK_STEP_M = 10f
    }
}
