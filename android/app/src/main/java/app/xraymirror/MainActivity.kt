package app.xraymirror

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.xraymirror.ui.theme.XRayMirrorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * X-Ray Mirror v0.1 — front camera + MediaPipe face (468 pts) + hand tracking,
 * with a stylized anatomy overlay (skull / brain / eyes / muscles / hand bones).
 *
 * This is NOT a real X-ray and NOT a scan: the phone cannot see through skin.
 * The overlay is a simplified drawing fitted to your face landmarks.
 * No account, no server, no ads. Models are downloaded once, then it is offline.
 */
class MainActivity : ComponentActivity() {

    // --- colours (same as the approved example) ---
    private val bg = Color(0xFF050A12)
    private val panel = Color(0xFF0A1522)
    private val chipBg = Color(0xFF122838)
    private val cyan = Color(0xFF00C2FF)
    private val textPrimary = Color(0xFFE8F6FF)
    private val textMuted = Color(0xFF8FB8CC)

    // --- state ---
    private var permissionGranted by mutableStateOf(false)
    private var downloadState by mutableStateOf<ModelManager.DownloadState>(
        ModelManager.DownloadState.Idle
    )
    private var settings by mutableStateOf(OverlaySettings())
    private var facePoints by mutableStateOf<List<Offset>?>(null)
    private var handPoints by mutableStateOf<List<Offset>?>(null)
    private var faceLocked by mutableStateOf(false)
    private var handLocked by mutableStateOf(false)
    private var fps by mutableStateOf(0)
    private var frameWidth by mutableStateOf(0)
    private var frameHeight by mutableStateOf(0)
    private var trackerError by mutableStateOf<String?>(null)

    private var tracker: FaceHandTracker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraBound = false
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissionGranted = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            XRayMirrorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = bg
                ) {
                    when {
                        !permissionGranted -> PermissionScreen(
                            onRequest = {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        )
                        downloadState != ModelManager.DownloadState.Ready -> {
                            LaunchedEffect(permissionGranted) {
                                if (downloadState == ModelManager.DownloadState.Idle) {
                                    startModelDownload()
                                }
                            }
                            DownloadScreen(
                                state = downloadState,
                                onRetry = { startModelDownload() }
                            )
                        }
                        else -> MirrorScreen()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tracker?.close()
        tracker = null
        analysisExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    // ------------------------------------------------------------------
    // Models
    // ------------------------------------------------------------------

    private fun startModelDownload() {
        downloadState = ModelManager.DownloadState.Downloading("Face model", 0f)
        lifecycleScope.launch {
            val ok = ModelManager.downloadIfNeeded(this@MainActivity) { label, progress ->
                lifecycleScope.launch(Dispatchers.Main) {
                    downloadState = ModelManager.DownloadState.Downloading(label, progress)
                }
            }
            if (!ok) {
                downloadState = ModelManager.DownloadState.Failed(
                    "Download failed. Check your connection (Wi-Fi is best) and retry."
                )
                return@launch
            }
            // Create the trackers off the main thread (loads the TFLite models).
            val created = kotlinx.coroutines.withContext(Dispatchers.Default) {
                try {
                    FaceHandTracker(
                    context = this@MainActivity,
                    faceModel = ModelManager.faceModelFile(this@MainActivity),
                    handModel = ModelManager.handModelFile(this@MainActivity),
                    onFaceResult = { points ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            facePoints = points
                            faceLocked = points != null
                        }
                    },
                    onHandResult = { points ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            handPoints = points
                            handLocked = points != null
                        }
                    },
                    onError = { message ->
                        lifecycleScope.launch(Dispatchers.Main) { trackerError = message }
                    }
                )
                } catch (e: Exception) {
                    null
                }
            }
            if (created == null || !created.isReady) {
                downloadState = ModelManager.DownloadState.Failed(
                    "Models downloaded but could not start tracking. Retry."
                )
                return@launch
            }
            tracker = created
            downloadState = ModelManager.DownloadState.Ready
        }
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    private fun bindCamera(previewView: PreviewView) {
        if (cameraBound) return
        cameraBound = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor, ::analyzeFrame)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                trackerError = "Camera failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private var frameCount = 0
    private var fpsStartMs = SystemClock.uptimeMillis()

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val activeTracker = tracker ?: return
            val raw = imageProxyToBitmap(imageProxy) ?: return
            // Rotate to portrait + mirror (front camera), so the bitmap matches
            // what the user sees in the preview. Landmarks are in this space.
            // Do NOT recycle the bitmap: MediaPipe reads it asynchronously.
            val oriented = orientBitmap(
                raw, imageProxy.imageInfo.rotationDegrees, frontCamera = true
            )
            // The un-rotated frame is no longer needed (MediaPipe reads
            // `oriented`, not `raw`). Only recycle when they differ.
            if (oriented !== raw) raw.recycle()
            if (oriented.width != frameWidth || oriented.height != frameHeight) {
                val w = oriented.width
                val h = oriented.height
                lifecycleScope.launch(Dispatchers.Main) {
                    frameWidth = w
                    frameHeight = h
                }
            }
            val timestampMs = imageProxy.imageInfo.timestamp / 1_000_000
            activeTracker.detect(oriented, timestampMs)
            frameCount++
            val now = SystemClock.uptimeMillis()
            if (now - fpsStartMs >= 1000) {
                val measured = (frameCount * 1000 / (now - fpsStartMs)).toInt()
                frameCount = 0
                fpsStartMs = now
                lifecycleScope.launch(Dispatchers.Main) { fps = measured }
            }
        } catch (_: Exception) {
            // One bad frame must never kill the stream.
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val w = imageProxy.width
            val h = imageProxy.height
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (rowStride == w * 4) {
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                val bytes = ByteArray(rowStride * h)
                buffer.rewind()
                buffer.get(bytes)
                val compact = ByteBuffer.allocateDirect(w * 4 * h)
                for (y in 0 until h) {
                    compact.put(bytes, y * rowStride, w * 4)
                }
                compact.rewind()
                bitmap.copyPixelsFromBuffer(compact)
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun orientBitmap(src: Bitmap, rotationDegrees: Int, frontCamera: Boolean): Bitmap {
        // NOTE: Matrix.isIdentity() is API 29+ but minSdk is 26, so track
        // the transform explicitly instead of calling it (would crash on
        // API 26-28 phones at runtime).
        val matrix = Matrix()
        var transformed = false
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
            transformed = true
        }
        if (frontCamera) {
            matrix.postScale(-1f, 1f)
            transformed = true
        }
        return if (!transformed) src
        else Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    @Composable
    private fun PermissionScreen(onRequest: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "X-RAY MIRROR",
                color = textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "The app needs the front camera to track your face " +
                    "and draw the anatomy overlay on it. Nothing is " +
                    "recorded or sent anywhere — tracking runs on your phone.",
                color = textMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(
                    containerColor = cyan,
                    contentColor = Color(0xFF04222E)
                )
            ) {
                Text("Allow camera", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Not a scan, not a medical device. Anatomy is a generic " +
                    "model scaled to your head.",
                color = textMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }

    @Composable
    private fun DownloadScreen(
        state: ModelManager.DownloadState,
        onRetry: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "X-RAY MIRROR",
                color = textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            when (state) {
                is ModelManager.DownloadState.Downloading -> {
                    Text(
                        text = "Downloading ${state.fileLabel}…",
                        color = textMuted,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = cyan,
                        trackColor = chipBg
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${(state.progress * 100).toInt()}%",
                        color = textMuted,
                        fontSize = 12.sp
                    )
                }
                is ModelManager.DownloadState.Failed -> {
                    Text(
                        text = state.message,
                        color = Color(0xFFFF6B6B),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cyan,
                            contentColor = Color(0xFF04222E)
                        )
                    ) {
                        Text("Retry download", fontWeight = FontWeight.Bold)
                    }
                }
                else -> {
                    Text(
                        text = "Preparing face + hand models…",
                        color = textMuted,
                        fontSize = 14.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "First run only: two free Google MediaPipe models " +
                    "(face + hand) are downloaded once, then the app works " +
                    "fully offline. Use Wi-Fi if you can.",
                color = textMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }

    @Composable
    private fun MirrorScreen() {
        Column(modifier = Modifier.fillMaxSize()) {
            // --- top bar ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(panel)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "X-RAY MIRROR",
                    color = textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Box(
                    modifier = Modifier
                        .background(Color(0xFFFF2D55), RoundedCornerShape(20.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "● LIVE",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            // --- stats ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(panel)
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 10.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatChip("468 pts")
                StatChip("$fps fps")
                StatChip(if (faceLocked) "Face: locked" else "Face: searching")
                StatChip(if (handLocked) "Hand: locked" else "Hand: searching")
            }
            if (trackerError != null) {
                Text(
                    text = trackerError ?: "",
                    color = Color(0xFFFF6B6B),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(panel)
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                )
            }
            // --- camera + overlay ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF0A1522))
            ) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            bindCamera(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val fw = frameWidth
                    val fh = frameHeight
                    if (fw > 0 && fh > 0) {
                        val scale = min(size.width / fw, size.height / fh)
                        val ox = (size.width - fw * scale) / 2f
                        val oy = (size.height - fh * scale) / 2f
                        val map: (Offset) -> Offset = { p ->
                            Offset(ox + p.x * fw * scale, oy + p.y * fh * scale)
                        }
                        drawXrayOverlay(facePoints, handPoints, settings, map)
                    }
                }
                if (!faceLocked) {
                    Text(
                        text = "Point the front camera at your face",
                        color = textPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .background(Color(0xFF050A12).copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            // --- control panel ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(panel)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                SectionLabel("LAYERS")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ChipButton("Skull", settings.showSkull) {
                        settings = settings.copy(showSkull = !settings.showSkull)
                    }
                    ChipButton("Brain", settings.showBrain) {
                        settings = settings.copy(showBrain = !settings.showBrain)
                    }
                    ChipButton("Muscles", settings.showMuscles) {
                        settings = settings.copy(showMuscles = !settings.showMuscles)
                    }
                    ChipButton("Eyes", settings.showEyes) {
                        settings = settings.copy(showEyes = !settings.showEyes)
                    }
                    ChipButton("Hand bones", settings.showHand) {
                        settings = settings.copy(showHand = !settings.showHand)
                    }
                }
                SectionLabel("LOOK")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ChipButton("X-ray", settings.look == Look.XRAY) {
                        settings = settings.copy(look = Look.XRAY)
                    }
                    ChipButton("Solid", settings.look == Look.SOLID) {
                        settings = settings.copy(look = Look.SOLID)
                    }
                    ChipButton("Skin only", settings.look == Look.SKIN) {
                        settings = settings.copy(look = Look.SKIN)
                    }
                }
                SectionLabel("REVEAL")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ChipButton("Whole head", settings.reveal == Reveal.WHOLE) {
                        settings = settings.copy(reveal = Reveal.WHOLE)
                    }
                    ChipButton("Left half", settings.reveal == Reveal.LEFT) {
                        settings = settings.copy(reveal = Reveal.LEFT)
                    }
                    ChipButton("Right half", settings.reveal == Reveal.RIGHT) {
                        settings = settings.copy(reveal = Reveal.RIGHT)
                    }
                    ChipButton("Top only", settings.reveal == Reveal.TOP) {
                        settings = settings.copy(reveal = Reveal.TOP)
                    }
                    ChipButton("Lower face", settings.reveal == Reveal.LOWER) {
                        settings = settings.copy(reveal = Reveal.LOWER)
                    }
                }
                SectionLabel("FIT TO MY HEAD — ${settings.fitPercent}%")
                Slider(
                    value = settings.fitPercent.toFloat(),
                    onValueChange = { settings = settings.copy(fitPercent = it.toInt()) },
                    valueRange = 100f..130f,
                    colors = SliderDefaults.colors(
                        thumbColor = cyan,
                        activeTrackColor = cyan,
                        inactiveTrackColor = chipBg
                    )
                )
                SectionLabel("BRIGHTNESS — ${settings.brightnessPercent}%")
                Slider(
                    value = settings.brightnessPercent.toFloat(),
                    onValueChange = { settings = settings.copy(brightnessPercent = it.toInt()) },
                    valueRange = 30f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = cyan,
                        activeTrackColor = cyan,
                        inactiveTrackColor = chipBg
                    )
                )
                Text(
                    text = "Not a scan, not a medical device. Anatomy is a generic " +
                        "model scaled to your head — this phone cannot see " +
                        "through skin.",
                    color = textMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text = text,
            color = textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 7.dp)
        )
    }

    @Composable
    private fun StatChip(text: String) {
        Box(
            modifier = Modifier
                .background(chipBg, RoundedCornerShape(20.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(text = text, color = textMuted, fontSize = 11.sp)
        }
    }

    @Composable
    private fun ChipButton(label: String, selected: Boolean, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selected) cyan else chipBg,
                contentColor = if (selected) Color(0xFF04222E) else textPrimary
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
