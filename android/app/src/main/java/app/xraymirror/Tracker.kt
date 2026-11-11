package app.xraymirror

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.File

/**
 * Wraps Google MediaPipe Tasks Vision (on-device, no server, no account):
 * FaceLandmarker = 468 face points, HandLandmarker = 21 hand points.
 * LIVE_STREAM mode: detectAsync() is non-blocking, results arrive on the
 * listeners. Landmarks are normalized (0..1) in the oriented bitmap's
 * coordinate space — the caller rotates + mirrors the frame first, so the
 * points map 1:1 onto what the user sees in the preview.
 */
class FaceHandTracker(
    context: Context,
    faceModel: File,
    handModel: File,
    private val onFaceResult: (List<Offset>?) -> Unit,
    private val onHandResult: (List<Offset>?) -> Unit,
    private val onError: (String) -> Unit
) {
    private val faceLandmarker: FaceLandmarker? = try {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(faceModel.absolutePath)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ ->
                val points = result.faceLandmarks().firstOrNull()
                    ?.map { Offset(it.x(), it.y()) }
                onFaceResult(points)
            }
            .setErrorListener { e -> onError(e.message ?: "Face tracking error") }
            .build()
        FaceLandmarker.createFromOptions(context, options)
    } catch (e: Exception) {
        onError("Face model failed to load: ${e.message}")
        null
    }

    private val handLandmarker: HandLandmarker? = try {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(handModel.absolutePath)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ ->
                val points = result.landmarks().firstOrNull()
                    ?.map { Offset(it.x(), it.y()) }
                onHandResult(points)
            }
            .setErrorListener { e -> onError(e.message ?: "Hand tracking error") }
            .build()
        HandLandmarker.createFromOptions(context, options)
    } catch (e: Exception) {
        onError("Hand model failed to load: ${e.message}")
        null
    }

    val isReady: Boolean get() = faceLandmarker != null && handLandmarker != null

    /** timestampMs must increase monotonically — pass the camera frame timestamp. */
    fun detect(bitmap: Bitmap, timestampMs: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        try {
            faceLandmarker?.detectAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            onError(e.message ?: "Face detect error")
        }
        try {
            handLandmarker?.detectAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            onError(e.message ?: "Hand detect error")
        }
    }

    fun close() {
        try { faceLandmarker?.close() } catch (_: Exception) { }
        try { handLandmarker?.close() } catch (_: Exception) { }
    }
}
