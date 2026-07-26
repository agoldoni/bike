package it.agoldoni.bike

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            sendServiceAction(TrackingService.ACTION_START)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by RideTracker.state.collectAsStateWithLifecycle()
                val position by RideTracker.position.collectAsStateWithLifecycle()
                val track by RideTracker.track.collectAsStateWithLifecycle()

                // Schermo sempre acceso durante il giro.
                LaunchedEffect(state.isTracking) {
                    if (state.isTracking) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                RideScreen(
                    state = state,
                    position = position,
                    track = track,
                    onStart = ::startTracking,
                    onStop = { sendServiceAction(TrackingService.ACTION_STOP) },
                )
            }
        }
    }

    private fun startTracking() {
        // Con la geolocalizzazione di sistema spenta il provider non emette nulla e
        // l'app resterebbe in attesa per sempre senza spiegare perché.
        val locationManager = getSystemService(LocationManager::class.java)
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
            Toast.makeText(this, R.string.location_disabled, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            sendServiceAction(TrackingService.ACTION_START)
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, TrackingService::class.java).setAction(action)
        if (action == TrackingService.ACTION_START) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
private fun RideScreen(
    state: RideState,
    position: GeoPoint?,
    track: List<GeoPoint>,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    var panelExpanded by rememberSaveable { mutableStateOf(false) }
    var followPosition by rememberSaveable { mutableStateOf(true) }
    val topInsetPx = WindowInsets.statusBars.getTop(LocalDensity.current)
    val context = LocalContext.current
    // Letta una volta sola: dopo il primo fix comanda la posizione corrente.
    val lastKnown = remember { LastKnownPosition.load(context) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            RideMap(
                position = position,
                track = track,
                followPosition = followPosition,
                onUserPan = { followPosition = false },
                onMapTap = { panelExpanded = false },
                modifier = Modifier.fillMaxSize(),
                topInsetPx = topInsetPx,
                initialCenter = lastKnown,
            )

            if (!followPosition) {
                Button(
                    onClick = { followPosition = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xF2000000),
                        contentColor = Color.White,
                    ),
                ) {
                    Text(text = stringResource(R.string.recenter))
                }
            }

            RidePanel(
                state = state,
                expanded = panelExpanded,
                waitingForFix = state.isTracking && position == null,
                onExpand = { panelExpanded = true },
                onStart = onStart,
                onStop = onStop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
