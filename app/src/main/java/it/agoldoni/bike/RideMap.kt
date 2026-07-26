package it.agoldoni.bike

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import org.osmdroid.util.GeoPoint as OsmGeoPoint

private const val DEFAULT_ZOOM = 16.0
private const val FALLBACK_ZOOM = 5.0
private val FALLBACK_CENTER = GeoPoint(42.5, 12.5)

/**
 * Sfondo cartografico volutamente scarno: rispetto al layer standard di OSM non
 * disegna punti d'interesse e riduce le etichette, così il tracciato del giro resta
 * l'elemento in evidenza. Le tile pesano anche una frazione (circa 1 KB contro 40 KB),
 * il che si sente sul consumo dati di un giro lungo.
 */
private val CLEAN_TILE_SOURCE = XYTileSource(
    "CartoDB.Positron",
    0,
    20,
    256,
    ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/light_all/",
        "https://b.basemaps.cartocdn.com/light_all/",
        "https://c.basemaps.cartocdn.com/light_all/",
    ),
    "© OpenStreetMap contributors © CARTO",
)

/**
 * Mappa OpenStreetMap con la posizione corrente e il tracciato del giro.
 *
 * Unico punto del progetto che conosce osmdroid: il resto del codice parla di
 * [GeoPoint] di dominio.
 */
@Composable
fun RideMap(
    position: GeoPoint?,
    track: List<GeoPoint>,
    followPosition: Boolean,
    onUserPan: () -> Unit,
    onMapTap: () -> Unit,
    modifier: Modifier = Modifier,
    topInsetPx: Int = 0,
    initialCenter: GeoPoint? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Le callback cambiano a ogni ricomposizione, gli overlay vengono creati una volta
    // sola: senza questo wrapper catturerebbero per sempre la prima versione.
    val currentOnUserPan by rememberUpdatedState(onUserPan)
    val currentOnMapTap by rememberUpdatedState(onMapTap)

    val mapView = remember {
        configureOsmdroid(context)
        createMapView(context, initialCenter)
    }
    // Il primo fix va inquadrato da vicino anche se si partiva dalla vista larga del
    // primo avvio; dopo, lo zoom resta quello scelto dall'utente.
    val firstFixHandled = remember { booleanArrayOf(false) }
    val marker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_position)
            setInfoWindow(null) // niente popup al tocco: il tap sulla mappa chiude il pannello
        }
    }
    val polyline = remember {
        Polyline(mapView).apply {
            outlinePaint.strokeWidth = 12f
            outlinePaint.color = TRACK_COLOR.toArgb()
        }
    }
    // In alto: in basso finirebbe sotto il pannello dei dati, e l'attribuzione
    // OpenStreetMap deve restare visibile.
    val copyright = remember { CopyrightOverlay(context).apply { setAlignBottom(false) } }
    // Quanti vertici sono già disegnati: i nuovi si accodano con addPoint invece di
    // ricostruire tutta la geometria a ogni aggiornamento.
    val drawnPoints = remember { intArrayOf(0) }

    DisposableEffect(mapView, lifecycleOwner) {
        mapView.overlays.add(polyline)
        mapView.overlays.add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: OsmGeoPoint?): Boolean {
                    currentOnMapTap()
                    return true
                }

                override fun longPressHelper(p: OsmGeoPoint?) = false
            })
        )
        mapView.overlays.add(copyright)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    LaunchedEffect(mapView) {
        attachUserPanDetector(mapView) { currentOnUserPan() }
    }

    // La mappa sta sotto la status bar: senza questo scarto l'attribuzione finirebbe
    // sopra l'orologio di sistema.
    LaunchedEffect(topInsetPx) {
        copyright.setOffset(10, topInsetPx + 10)
        mapView.invalidate()
    }

    LaunchedEffect(position, followPosition) {
        val point = position?.toOsm() ?: return@LaunchedEffect
        marker.position = point
        if (marker !in mapView.overlays) mapView.overlays.add(marker)
        if (followPosition) {
            if (!firstFixHandled[0] && mapView.zoomLevelDouble < DEFAULT_ZOOM) {
                mapView.controller.setZoom(DEFAULT_ZOOM)
            }
            mapView.controller.animateTo(point)
        }
        firstFixHandled[0] = true
        mapView.invalidate()
    }

    LaunchedEffect(track) {
        if (track.size < drawnPoints[0]) {
            // Nuovo giro: il tracciato è stato azzerato.
            polyline.setPoints(track.map { it.toOsm() })
        } else {
            for (i in drawnPoints[0] until track.size) polyline.addPoint(track[i].toOsm())
        }
        drawnPoints[0] = track.size
        mapView.invalidate()
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private val TRACK_COLOR = Color(0xFFFF6D00)

private fun GeoPoint.toOsm() = OsmGeoPoint(lat, lon)

private fun createMapView(context: Context, initialCenter: GeoPoint?) = MapView(context).apply {
    setTileSource(CLEAN_TILE_SOURCE)
    setMultiTouchControls(true)
    setTilesScaledToDpi(true)
    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
    if (initialCenter != null) {
        controller.setZoom(DEFAULT_ZOOM)
        controller.setCenter(initialCenter.toOsm())
    } else {
        // Primo avvio in assoluto: vista larga sull'Italia, meglio del mare aperto
        // che si vedrebbe restando sul punto 0,0.
        controller.setZoom(FALLBACK_ZOOM)
        controller.setCenter(FALLBACK_CENTER.toOsm())
    }
}

/**
 * Disattiva l'inseguimento della posizione appena l'utente trascina la mappa.
 * Non si può usare `MapListener.onScroll`: scatta anche per le centrature
 * programmatiche, che si disattiverebbero da sole al primo aggiornamento GPS.
 */
@SuppressLint("ClickableViewAccessibility")
private fun attachUserPanDetector(mapView: MapView, onUserPan: () -> Unit) {
    mapView.setOnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_MOVE) onUserPan()
        false // l'evento deve comunque arrivare alla mappa per pan e zoom
    }
}

private fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    // La tile usage policy di OpenStreetMap richiede uno User-Agent identificativo:
    // con quello di default le richieste vengono rifiutate.
    config.userAgentValue = context.packageName
    val basePath = File(context.getExternalFilesDir(null) ?: context.filesDir, "osmdroid")
    config.osmdroidBasePath = basePath
    config.osmdroidTileCache = File(basePath, "tiles")
}
