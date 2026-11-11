package app.xraymirror

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the two MediaPipe model files once, from Google's official
 * model storage, and keeps them in the app's private filesDir.
 * The models are NOT bundled in the APK (they are ~3 MB + ~8 MB).
 * After the first download the app works fully offline.
 */
object ModelManager {

    const val FACE_MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
    const val HAND_MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"

    const val FACE_MODEL_FILE = "face_landmarker.task"
    const val HAND_MODEL_FILE = "hand_landmarker.task"

    fun faceModelFile(context: Context): File = File(context.filesDir, FACE_MODEL_FILE)
    fun handModelFile(context: Context): File = File(context.filesDir, HAND_MODEL_FILE)

    fun allDownloaded(context: Context): Boolean =
        faceModelFile(context).exists() && faceModelFile(context).length() > 1_000_000 &&
            handModelFile(context).exists() && handModelFile(context).length() > 1_000_000

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fileLabel: String, val progress: Float) : DownloadState
        data object Ready : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    /** Returns true when both models are present and usable. */
    suspend fun downloadIfNeeded(
        context: Context,
        onProgress: (label: String, progress: Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val faceFile = faceModelFile(context)
            if (!faceFile.exists() || faceFile.length() < 1_000_000) {
                downloadFile(FACE_MODEL_URL, faceFile) { p -> onProgress("Face model", p) }
            }
            val handFile = handModelFile(context)
            if (!handFile.exists() || handFile.length() < 1_000_000) {
                downloadFile(HAND_MODEL_URL, handFile) { p -> onProgress("Hand model", p) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Float) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("HTTP ${connection.responseCode} for $url")
        }
        val total = connection.contentLengthLong
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        connection.inputStream.use { input ->
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                var downloaded = 0L
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (total > 0) onProgress(downloaded.toFloat() / total.toFloat())
                }
            }
        }
        if (!tmp.renameTo(dest)) throw IllegalStateException("Could not save ${dest.name}")
    }
}
