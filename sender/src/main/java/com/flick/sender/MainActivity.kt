package com.flick.sender

import android.Manifest
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flick.sender.media.MediaAccess
import com.flick.sender.net.IncomingPairEvent
import com.flick.sender.net.PairLaunch
import com.flick.sender.support.SupportCatalog
import com.flick.sender.ui.screens.FlickApp
import com.flick.sender.ui.LocalSimplifiedVideoNames
import com.flick.sender.ui.theme.AppearanceController
import com.flick.sender.ui.theme.DarkFlickColors
import com.flick.sender.ui.theme.FlickTheme
import com.flick.sender.ui.theme.LightFlickColors
import com.flick.sender.ui.theme.LocalThemePreference
import com.flick.sender.ui.theme.ThemePreference
import com.flick.sender.ui.theme.ThemeStore
import com.flick.sender.util.FlickLog
import java.util.concurrent.atomic.AtomicLong

/**
 * One counter for the whole process. The QR deep link and the in-app scanner both mint
 * launch events, and a repeated id would let a stale sheet's dismiss or submit act on a
 * newer launch. Never returns 0 — the controller reads 0 as "typed by hand".
 */
object PairLaunchEventIds {
    private val counter = AtomicLong(0L)

    fun next(): Long = counter.incrementAndGet()
}

class MainActivity : ComponentActivity() {
    private val pairEvents = kotlinx.coroutines.flow.MutableStateFlow<IncomingPairEvent?>(null)
    private lateinit var appearance: AppearanceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Loaded synchronously, before setContent: the opening frame is already a
        // palette, and a read deferred to a background thread would paint the system's
        // answer and then flip to the user's — the cold-start flash this preference
        // exists to prevent. It is one string out of a one-key SharedPreferences file.
        val themes = ThemeStore(this)
        val preference = themes.preference()
        // Reasserted on every launch and not only when the row is tapped: the override
        // lives in the system, the choice lives in this app's preference file, and a file
        // restored from a backup arrives on a phone that has never been told about it.
        applyApplicationNightMode(preference)
        appearance = AppearanceController(preference) {
            themes.save(it)
            applyApplicationNightMode(it)
        }
        val darkPalette = preference.resolvesDark(systemInDarkMode())
        // themes.xml picks the cold-start plate out of the -night bucket, and the SYSTEM
        // is what resolves it: from API 31 out of the night mode set above, but on 26-30 —
        // and everywhere on the first launch after a choice — out of the phone's own,
        // which is no longer this app's answer. A Dark user on a light phone would watch a
        // pale plate flash ahead of a near-black app, and a Light user on a dark one the
        // reverse. Repainted here from the palette that is about to be composed, which is
        // why the preference has to be in hand this early: that plate and the API 31+
        // splash behind it are both read before this process exists, so this is the first
        // frame the choice can own.
        window.setBackgroundDrawable(
            ColorDrawable((if (darkPalette) DarkFlickColors else LightFlickColors).canvas.toArgb()),
        )
        // Each screen owns the content insets it needs; the activity only owns the
        // edge-to-edge window contract so those insets are never applied twice. The
        // manifest's adjustResize is the other half of that contract — inherit the
        // default there and the platform pans the window for the keyboard on top of
        // whatever the content already padded for it.
        //
        // Both scrims are declared transparent rather than left to the default, which
        // derives a near-opaque band from the platform's night mode: on Dark under a
        // light system that band arrives white, beneath a cinematic screen. Flick paints
        // its own surface under both bars anyway, and API 26 — this app's minimum — is
        // the first release with a light-icon flag for the navigation bar as well as the
        // status bar, so contrast never has to come from a scrim. The detector is kept
        // only because it also seeds the icon appearance for the frames before FlickApp's
        // own per-route inversion takes over.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkPalette },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkPalette },
        )
        // Without this the platform paints its own translucent band behind the
        // navigation bar, which cuts across the cinematic gradient the remote and the
        // connecting overlay draw to the bottom edge. Icon legibility is handled by
        // FlickApp, which inverts both bars per route.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        acceptPairIntent(intent)
        setContent {
            // Provided outside the theme because the theme is what reads it. Nothing
            // else may: FlickCinematicTheme is reached directly by the screens that are
            // dark by design, and it never consults this.
            CompositionLocalProvider(LocalThemePreference provides appearance.preference) {
                FlickTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        FlickRoot(
                            (application as FlickApplication).coordinator,
                            pairEvents,
                            appearance,
                        ) { pairEvents.value = null }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptPairIntent(intent)
    }

    /**
     * The night mode this app's resources are resolving against, from outside a
     * composition. `isSystemInDarkTheme()` asks the same question and feeds the same rule,
     * but the window contract above is settled before there is a composition to ask it in.
     *
     * From API 31 that answer carries [applyApplicationNightMode]'s override, so it is the
     * platform's own only where it is actually consulted: [ThemePreference.SYSTEM] is the
     * one choice that both stores no override and reads this at all.
     */
    private fun systemInDarkMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * Hand the choice to the platform's per-app night mode, so the SYSTEM resolves this
     * app's resources from it — the API 31+ splash plate and the `-night` window
     * background behind it included, both of which are read before this process exists and
     * are otherwise beyond anything the app can do at runtime. It is persisted per package
     * until this app changes it, so it governs every launch after the one it was set on.
     *
     * `MODE_NIGHT_AUTO` is the value that stores NO override — the service maps it, and
     * `MODE_NIGHT_CUSTOM`, to `UI_MODE_NIGHT_UNDEFINED` — which is what returning to Match
     * system has to mean: an app that left `MODE_NIGHT_NO` behind would go on ignoring the
     * phone's night mode forever.
     *
     * Below API 31 there is no per-app equivalent that does not go through AppCompat,
     * which this app deliberately does not depend on (its window theme parents on the
     * platform `Theme.Material`). On 26-30 the starting window therefore keeps coming out
     * of the phone's own night mode and `onCreate`'s repaint above is the earliest frame
     * the preference can own — the same bound that applies to every release on the launch
     * a choice is made.
     *
     * Setting this is a configuration change, which the manifest's `uiMode` in
     * `configChanges` keeps from recreating the activity: nothing here depends on a
     * recreate, because the palette is driven by [AppearanceController]'s snapshot state
     * and the two explicit choices resolve without consulting the configuration at all.
     */
    private fun applyApplicationNightMode(preference: ThemePreference) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val modes = getSystemService(UiModeManager::class.java) ?: return
        modes.setApplicationNightMode(
            when (preference) {
                ThemePreference.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                ThemePreference.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemePreference.DARK -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }

    /**
     * Intent data is erased before composition has a chance to observe task state.
     *
     * This is the UNTRUSTED half of the pairing ingress and takes `PairLaunch.parse`,
     * whose result type has nowhere to put a code: a v4 payload delivered as an Intent is
     * demoted to a prefill and the user still types the digits off the TV. Only the
     * in-app scanner may keep them, because only the camera proves the QR was in the
     * room. A `flick://` filter cannot be autoVerify'd, so any installed app can claim
     * the scheme, appear in the chooser, and fire a URI it composed itself.
     */
    private fun acceptPairIntent(incoming: Intent?) {
        val raw = incoming?.data
        // Validate into an in-memory result FIRST: only the parsed value is carried
        // forward, never the Intent or the URI itself.
        val parsed = raw?.let(PairLaunch::parse)
        val sanitized = incoming?.let { Intent(it).apply { data = null } }
        if (incoming != null) incoming.data = null
        setIntent(sanitized)
        // Scheme and host only, and now for a second reason: a v4 URI carries the TV's
        // live pairing code in its query, so the raw URI is a secret even though the
        // parse above has already dropped it.
        if (raw != null) FlickLog.i("pair", "launch intent scheme=${raw.scheme} host=${raw.host}")
        if (parsed != null) pairEvents.value = IncomingPairEvent(PairLaunchEventIds.next(), parsed)
    }
}

@Composable
private fun FlickRoot(
    controller: com.flick.sender.net.CastCoordinator,
    events: kotlinx.coroutines.flow.StateFlow<IncomingPairEvent?>,
    appearance: AppearanceController,
    acknowledge: () -> Unit,
) {
    val context = LocalContext.current
    val event by events.collectAsState()
    val simplifiedVideoNames by controller.simplifiedVideoNames.collectAsState()
    val supportCatalog = remember { SupportCatalog.configured() }

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

    CompositionLocalProvider(LocalSimplifiedVideoNames provides simplifiedVideoNames) {
        FlickApp(
            controller = controller,
            supportCatalog = supportCatalog,
            batteryExempt = batteryExempt,
            themePreference = appearance.preference,
            onSelectTheme = appearance::select,
            onRequestVideoPermission = { videoLauncher.launch(videoPermissions()) },
            onOpenWifiSettings = {
                runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            },
            // The OS list, NOT ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS. That intent
            // is the one-tap dialog, and it requires the REQUEST_IGNORE_BATTERY_-
            // OPTIMIZATIONS permission, which Play grants only to alarms/timers, VoIP,
            // companion-device pairing and task automation. This screen needs no
            // permission and no Console declaration; it costs the user one extra tap to
            // pick Flick out of the list. The resume observer above re-reads the real
            // exemption state either way, so the advisory still clears on its own.
            onRequestBatteryExemption = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
            onOpenCheckout = { checkoutUrl ->
                supportCatalog?.let { catalog ->
                    launchSupportCheckout(context, catalog, checkoutUrl)
                }
            },
        )
    }
}

/** Checkout is a browser hand-off only; callers may pass a URL only from the given catalog. */
internal fun launchSupportCheckout(
    context: Context,
    catalog: SupportCatalog,
    checkoutUrl: String,
) {
    if (catalog.options.none { it.checkoutUrl == checkoutUrl }) return
    val uri = android.net.Uri.parse(checkoutUrl)
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
        return
    } catch (_: ActivityNotFoundException) {
        // Fall through to the platform browser intent.
    } catch (_: RuntimeException) {
        // A misbehaving browser provider must not strand this one-time hand-off.
    }
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE),
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.support_checkout_unavailable, Toast.LENGTH_SHORT).show()
    } catch (_: RuntimeException) {
        Toast.makeText(context, R.string.support_checkout_unavailable, Toast.LENGTH_SHORT).show()
    }
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
