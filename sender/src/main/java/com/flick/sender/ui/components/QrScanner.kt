package com.flick.sender.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flick.sender.R
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.Channel

/** What the camera permission leaves the scanner able to do. */
enum class CameraAccess { UNREQUESTED, GRANTED, DENIED, BLOCKED }

object CameraPermission {
    /**
     * The platform reports no rationale both *before* the first prompt and *after* a
     * second refusal, so [requested] is the only thing separating a scanner that can
     * still ask from one that can only point at Settings.
     */
    fun state(granted: Boolean, showRationale: Boolean, requested: Boolean): CameraAccess = when {
        granted -> CameraAccess.GRANTED
        showRationale -> CameraAccess.DENIED
        requested -> CameraAccess.BLOCKED
        else -> CameraAccess.UNREQUESTED
    }
}

/**
 * A held-up QR decodes on every analysed frame. The gate forwards a payload once and
 * swallows repeats of it for [quietMs]; a different payload is never delayed.
 */
class QrScanGate(private val quietMs: Long = 2_000L) {
    private var lastPayload: String? = null
    private var lastAtMs = 0L

    /** The payload to route, or null when this frame carries nothing new. */
    fun accept(payload: String, nowMs: Long): String? {
        // Only transport whitespace is dropped: what the parser sees is still exactly
        // the string the TV encoded, and it is still validated there, not here.
        val normalized = payload.trim()
        if (normalized.isEmpty()) return null
        if (normalized == lastPayload && nowMs - lastAtMs < quietMs) return null
        lastPayload = normalized
        lastAtMs = nowMs
        return normalized
    }
}

/**
 * The detector is configured for QR alone, and every result is re-checked against that
 * here: widening the detector's format set later must not quietly widen what pairing
 * will act on.
 */
internal object QrPayload {
    /** The string this result may contribute, or null when it is not one to route. */
    fun of(format: Int, rawValue: String?): String? =
        if (format == Barcode.FORMAT_QR_CODE) rawValue?.takeIf { it.isNotBlank() } else null
}

/**
 * Hands camera frames to ML Kit's bundled QR detector. [ImageProxy.close] runs on every
 * outcome and before anything else: KEEP_ONLY_LATEST holds exactly one image, so a
 * single frame left open ends analysis for the rest of the session.
 */
internal class QrCodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val callbacks: Executor,
    private val onPayload: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        val frame = image.image
        val task = frame?.let {
            runCatching {
                scanner.process(InputImage.fromMediaImage(it, image.imageInfo.rotationDegrees))
            }.getOrNull()
        }
        if (task == null) {
            image.close()
            return
        }
        task.addOnCompleteListener(callbacks) { done ->
            image.close()
            // Task.getResult() rethrows a failed detection, so it is only safe past here.
            if (!done.isSuccessful) return@addOnCompleteListener
            val found: List<Barcode> = done.result ?: emptyList()
            found.firstNotNullOfOrNull { QrPayload.of(it.format, it.rawValue) }?.let(onPayload)
        }
    }
}

/** 720p is the smallest frame that still resolves a TV-sized QR from across a room. */
private val ScanResolution: ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
    )
    .build()

private val ViewfinderShape = RoundedCornerShape(FlickCorners.qualityCard)

/**
 * The in-app scanner surface, including its own permission states. It produces the
 * raw string a QR carries and nothing else — parsing, the endpoint's trust level and
 * the typed code all stay exactly where they were.
 */
@Composable
fun QrScannerPanel(
    onPayload: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var requested by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var rationale by remember { mutableStateOf(activity.showsCameraRationale()) }
    var cameraFailed by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        requested = true
        granted = result
        rationale = activity.showsCameraRationale()
    }

    // Access can be granted from Settings and the user comes straight back to a sheet
    // that never left composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = context.hasCameraPermission()
                rationale = activity.showsCameraRationale()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (cameraFailed) {
        ScannerNotice(message = stringResource(R.string.scan_unavailable), modifier = modifier)
        return
    }
    when (CameraPermission.state(granted = granted, showRationale = rationale, requested = requested)) {
        CameraAccess.GRANTED -> Viewfinder(
            onPayload = onPayload,
            onFailure = { cameraFailed = true },
            modifier = modifier,
        )
        CameraAccess.UNREQUESTED -> ScannerNotice(
            title = stringResource(R.string.scan_permission_title),
            message = stringResource(R.string.scan_permission_body),
            actionText = stringResource(R.string.scan_permission_allow),
            onAction = { launcher.launch(Manifest.permission.CAMERA) },
            modifier = modifier,
        )
        CameraAccess.DENIED -> ScannerNotice(
            title = stringResource(R.string.scan_permission_title),
            message = stringResource(R.string.scan_permission_denied),
            actionText = stringResource(R.string.scan_permission_allow),
            onAction = { launcher.launch(Manifest.permission.CAMERA) },
            modifier = modifier,
        )
        CameraAccess.BLOCKED -> ScannerNotice(
            title = stringResource(R.string.scan_permission_title),
            message = stringResource(R.string.scan_permission_blocked),
            actionText = stringResource(R.string.scan_open_settings),
            onAction = { context.openAppSettings() },
            modifier = modifier,
        )
    }
}

@Composable
private fun Viewfinder(
    onPayload: (String) -> Unit,
    onFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    // The bundled model ships in the APK, so the very first scan works offline and with
    // no Play-services module to download first.
    val scanner = remember {
        runCatching {
            BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build(),
            )
        }.getOrNull()
    }
    if (scanner == null) {
        ScannerNotice(message = stringResource(R.string.scan_reader_unavailable), modifier = modifier)
        return
    }
    // A detector holds native state that no finalizer reclaims.
    DisposableEffect(scanner) { onDispose { scanner.close() } }

    val lifecycleOwner = LocalLifecycleOwner.current
    val description = stringResource(R.string.a11y_scan_viewfinder)
    val currentPayload by rememberUpdatedState(onPayload)
    val currentFailure by rememberUpdatedState(onFailure)
    val payloads = remember { Channel<String>(Channel.CONFLATED) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            // The sheet rises on a graphicsLayer, and a SurfaceView-backed preview
            // ignores that transform — it would sit unscaled and opaque over the sheet
            // it is supposed to be inside.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // A payload produced after the sheet closes dies here: this collector is the only
    // thing that ever reaches the composition or the pairing controller, and it is
    // cancelled with the viewfinder.
    LaunchedEffect(payloads) {
        for (payload in payloads) currentPayload(payload)
    }

    DisposableEffect(lifecycleOwner, previewView, scanner) {
        val executor = Executors.newSingleThreadExecutor()
        // Detection results come back on the main thread, not on the analysis executor:
        // that one is shut down below, and a listener handed to a dead executor is
        // rejected mid-flight.
        val callbacks = ContextCompat.getMainExecutor(context)
        // The gate is therefore only ever touched from one thread.
        val gate = QrScanGate()
        val analyzer = QrCodeAnalyzer(scanner, callbacks) { raw ->
            gate.accept(raw, SystemClock.elapsedRealtime())?.let { payloads.trySend(it) }
        }
        var released = false
        var bound: ProcessCameraProvider? = null
        val pending = ProcessCameraProvider.getInstance(context)
        pending.addListener(
            {
                if (released) return@addListener
                val provider = runCatching { pending.get() }.getOrNull()
                val selector = provider?.let(::firstAvailableCamera)
                if (provider == null || selector == null) {
                    currentFailure()
                    return@addListener
                }
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ScanResolution)
                    .build()
                analysis.setAnalyzer(executor, analyzer)
                val bindable = runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                }.isSuccess
                if (bindable) {
                    bound = provider
                } else {
                    analysis.clearAnalyzer()
                    currentFailure()
                }
            },
            callbacks,
        )
        onDispose {
            released = true
            bound?.unbindAll()
            executor.shutdown()
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(ViewfinderShape)
            .background(colors.inverseSurface)
            .semantics { contentDescription = description },
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ScannerNotice(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalFlickColors.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(ViewfinderShape)
            .background(colors.surfaceRaisedAlt)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) {
            Text(title, style = FlickText.titleMedium.copy(color = colors.onSurface))
        }
        Text(message, style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim))
        if (actionText != null && onAction != null) {
            FlickPrimaryButton(
                text = actionText,
                onClick = onAction,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun firstAvailableCamera(provider: ProcessCameraProvider): CameraSelector? =
    listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
        .firstOrNull { runCatching { provider.hasCamera(it) }.getOrDefault(false) }

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Activity?.showsCameraRationale(): Boolean =
    this != null && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
