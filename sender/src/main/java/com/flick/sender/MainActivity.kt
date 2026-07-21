package com.flick.sender

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flick.sender.media.MediaAccess
import com.flick.sender.net.IncomingPairEvent
import com.flick.sender.net.PairLaunch
import com.flick.sender.ui.screens.FlickApp
import com.flick.sender.ui.theme.FlickTheme
import com.flick.sender.util.FlickLog

class MainActivity : ComponentActivity() {
    private val pairEvents = kotlinx.coroutines.flow.MutableStateFlow<IncomingPairEvent?>(null)
    private var nextPairEventId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Each screen owns the content insets it needs; the activity only owns the
        // edge-to-edge window contract so those insets are never applied twice.
        enableEdgeToEdge()
        acceptPairIntent(intent)
        setContent {
            FlickTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FlickRoot((application as FlickApplication).coordinator, pairEvents) { pairEvents.value = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptPairIntent(intent)
    }

    /** Intent data is erased before composition has a chance to observe task state. */
    private fun acceptPairIntent(incoming: Intent?) {
        val raw = incoming?.data
        // Validate into an in-memory result FIRST: only the parsed value is carried
        // forward, never the Intent or the URI itself.
        val parsed = raw?.let(PairLaunch::parse)
        val sanitized = incoming?.let { Intent(it).apply { data = null } }
        if (incoming != null) incoming.data = null
        setIntent(sanitized)
        // Scheme and host only. A v3 payload is not secret, but logging raw URIs is
        // a habit that eventually leaks one.
        if (raw != null) FlickLog.i("pair", "launch intent scheme=${raw.scheme} host=${raw.host}")
        if (parsed != null) pairEvents.value = IncomingPairEvent(++nextPairEventId, parsed)
    }
}

@Composable
private fun FlickRoot(controller: com.flick.sender.net.CastCoordinator, events: kotlinx.coroutines.flow.StateFlow<IncomingPairEvent?>, acknowledge: () -> Unit) {
    val context = LocalContext.current
    val event by events.collectAsState()

    // POST_NOTIFICATIONS (API 33+) so the foreground-service notification shows.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* serving still works without it; the notification just stays hidden */ }

    // Video access for the MediaStore gallery.
    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Query the authoritative permission state. On Android 14+, the callback
        // can represent either full-library or user-selected access.
        controller.onMediaAccess(currentVideoAccess(context))
    }

    LaunchedEffect(Unit) {
        controller.onStart()
        controller.onMediaAccess(currentVideoAccess(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(event?.eventId) {
        event?.let { controller.acceptPairLaunch(it); acknowledge() }
    }

    // Battery-exemption state, re-checked on resume so the advisory clears once granted.
    var batteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = isIgnoringBatteryOptimizations(context)
                // Selected Photos Access can change while Flick is backgrounded.
                // Re-query MediaStore even when access remains granted.
                controller.onMediaAccess(currentVideoAccess(context))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    FlickApp(
        controller = controller,
        batteryExempt = batteryExempt,
        onRequestVideoPermission = { videoLauncher.launch(videoPermissions()) },
        onOpenWifiSettings = {
            runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        },
        onRequestBatteryExemption = {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        },
    )
}

private fun videoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun currentVideoAccess(context: Context): MediaAccess {
    val fullPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val full = ContextCompat.checkSelfPermission(context, fullPermission) == PackageManager.PERMISSION_GRANTED
    val partial = Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    ) == PackageManager.PERMISSION_GRANTED
    return MediaAccess.fromGrants(fullGranted = full, partialGranted = partial)
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
