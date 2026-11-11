package app.xraymirror

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The anatomy overlay, drawn on a Compose Canvas over the camera preview.
 * Everything is anchored to the MediaPipe landmarks (normalized 0..1 in the
 * oriented frame) and scaled around the face centre by the Fit slider.
 * [map] converts a normalized point to canvas pixels (FIT_CENTER letterbox).
 *
 * This is a stylized drawing, not a scan: the skull / brain / muscles are
 * simplified shapes fitted to YOUR landmarks, exactly like the example.
 */

enum class Look { XRAY, SOLID, SKIN }
enum class Reveal { WHOLE, LEFT, RIGHT, TOP, LOWER }

data class OverlaySettings(
    val showSkull: Boolean = true,
    val showBrain: Boolean = true,
    val showEyes: Boolean = true,
    val showMuscles: Boolean = false,
    val showHand: Boolean = false,
    val look: Look = Look.XRAY,
    val reveal: Reveal = Reveal.WHOLE,
    val fitPercent: Int = 117,
    val brightnessPercent: Int = 80
)

// MediaPipe FaceLandmarker indices (468 points).
private const val NOSE_TIP = 1
private const val NOSE_BRIDGE = 168
private const val EYE_L_OUTER = 33
private const val EYE_L_INNER = 133
private const val EYE_R_INNER = 362
private const val EYE_R_OUTER = 263
private const val IRIS_L = 468
private const val IRIS_R = 473
private const val MOUTH_L = 61
private const val MOUTH_R = 291
private const val MOUTH_TOP = 13
private const val MOUTH_BOTTOM = 14

private val JAWLINE = listOf(
    234, 132, 58, 172, 136, 150, 152, 377, 400, 378, 365, 397, 288, 361, 454
)

// MediaPipe HandLandmarker skeleton (21 points).
private val HAND_CONNECTIONS = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4,
    0 to 5, 5 to 6, 6 to 7, 7 to 8,
    5 to 9, 9 to 10, 10 to 11, 11 to 12,
    9 to 13, 13 to 14, 14 to 15, 15 to 16,
    13 to 17, 17 to 18, 18 to 19, 19 to 20,
    0 to 17
)

private val CYAN = Color(0xFF7EE7FF)
private val ACCENT = Color(0xFF00C2FF)
private val PINK = Color(0xFFFF8FC0)
private val RED = Color(0xFFFF6B6B)

fun DrawScope.drawXrayOverlay(
    facePoints: List<Offset>?,
    handPoints: List<Offset>?,
    settings: OverlaySettings,
    map: (Offset) -> Offset
) {
    // Skin only = no overlay at all.
    if (settings.look == Look.SKIN) return

    val b = (settings.brightnessPercent / 100f).coerceIn(0.3f, 1f)
    val strokeAlpha = if (settings.look == Look.SOLID) 0.95f * b else 0.9f * b
    val fillAlpha = if (settings.look == Look.SOLID) 0.45f * b else 0.10f * b

    if (!facePoints.isNullOrEmpty()) {
        // --- face geometry, in normalized space, scaled by Fit around centre
        var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
        for (p in facePoints) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val centre = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)
        val fit = settings.fitPercent / 100f
        fun fitPoint(p: Offset): Offset = centre + (p - centre) * fit
        fun px(p: Offset): Offset = map(fitPoint(p))

        // Pixel-space face box, for the Reveal clip.
        var boxL = Float.MAX_VALUE; var boxR = -Float.MAX_VALUE
        var boxT = Float.MAX_VALUE; var boxB = -Float.MAX_VALUE
        for (p in facePoints) {
            val q = px(p)
            if (q.x < boxL) boxL = q.x
            if (q.x > boxR) boxR = q.x
            if (q.y < boxT) boxT = q.y
            if (q.y > boxB) boxB = q.y
        }
        val faceW = (boxR - boxL).coerceAtLeast(1f)
        val faceH = (boxB - boxT).coerceAtLeast(1f)
        val strokeW = (faceW * 0.012f).coerceIn(2f, 5f)
        val midX = (boxL + boxR) / 2f
        val midY = (boxT + boxB) / 2f

        fun at(index: Int): Offset? = facePoints.getOrNull(index)?.let { px(it) }
        fun avg(a: Int, bIdx: Int): Offset? {
            val pa = facePoints.getOrNull(a) ?: return null
            val pb = facePoints.getOrNull(bIdx) ?: return null
            return px((pa + pb) / 2f)
        }

        val drawFace: DrawScope.() -> Unit = {
            if (settings.showSkull) {
                // Cranium ellipse.
                val craniumC = Offset(midX, midY - faceH * 0.04f)
                val rx = faceW * 0.52f
                val ry = faceH * 0.56f
                drawOval(
                    color = CYAN.copy(alpha = fillAlpha),
                    topLeft = Offset(craniumC.x - rx, craniumC.y - ry),
                    size = Size(rx * 2f, ry * 2f),
                    style = Fill
                )
                drawOval(
                    color = CYAN.copy(alpha = strokeAlpha),
                    topLeft = Offset(craniumC.x - rx, craniumC.y - ry),
                    size = Size(rx * 2f, ry * 2f),
                    style = Stroke(width = strokeW)
                )
                // Eye sockets.
                val eyeL = avg(EYE_L_OUTER, EYE_L_INNER)
                val eyeR = avg(EYE_R_INNER, EYE_R_OUTER)
                val socketR = faceW * 0.11f
                for (eye in listOfNotNull(eyeL, eyeR)) {
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.55f * b),
                        radius = socketR,
                        center = eye,
                        style = Fill
                    )
                    drawCircle(
                        color = CYAN.copy(alpha = strokeAlpha),
                        radius = socketR,
                        center = eye,
                        style = Stroke(width = strokeW)
                    )
                }
                // Nasal triangle.
                val bridge = at(NOSE_BRIDGE)
                val tip = at(NOSE_TIP)
                if (bridge != null && tip != null) {
                    val nosePath = Path().apply {
                        moveTo(bridge.x, bridge.y)
                        lineTo(tip.x - faceW * 0.05f, tip.y)
                        lineTo(tip.x + faceW * 0.05f, tip.y)
                        close()
                    }
                    drawPath(
                        path = nosePath,
                        color = Color.Black.copy(alpha = 0.55f * b),
                        style = Fill
                    )
                    drawPath(
                        path = nosePath,
                        color = CYAN.copy(alpha = strokeAlpha),
                        style = Stroke(width = strokeW)
                    )
                }
                // Teeth: mouth line + vertical ticks.
                val mouthTop = at(MOUTH_TOP)
                val mouthBottom = at(MOUTH_BOTTOM)
                val mouthL = at(MOUTH_L)
                val mouthR = at(MOUTH_R)
                if (mouthTop != null && mouthBottom != null && mouthL != null && mouthR != null) {
                    val midMouth = (mouthTop + mouthBottom) / 2f
                    drawLine(
                        color = CYAN.copy(alpha = strokeAlpha),
                        start = Offset(mouthL.x, midMouth.y),
                        end = Offset(mouthR.x, midMouth.y),
                        strokeWidth = strokeW
                    )
                    val ticks = 5
                    for (i in 1 until ticks) {
                        val x = mouthL.x + (mouthR.x - mouthL.x) * i / ticks
                        drawLine(
                            color = CYAN.copy(alpha = strokeAlpha * 0.8f),
                            start = Offset(x, mouthTop.y),
                            end = Offset(x, mouthBottom.y),
                            strokeWidth = strokeW * 0.6f
                        )
                    }
                }
                // Jawline.
                val jawPath = Path()
                var started = false
                for (index in JAWLINE) {
                    val p = at(index) ?: continue
                    if (!started) { jawPath.moveTo(p.x, p.y); started = true }
                    else jawPath.lineTo(p.x, p.y)
                }
                if (started) {
                    drawPath(
                        path = jawPath,
                        color = CYAN.copy(alpha = strokeAlpha),
                        style = Stroke(width = strokeW)
                    )
                }
            }

            if (settings.showBrain) {
                // Brain: ellipse in the forehead + squiggles.
                val eyeMid = avg(EYE_L_OUTER, EYE_R_OUTER)
                val brainC = (eyeMid ?: Offset(midX, midY)) + Offset(0f, -faceH * 0.17f)
                val brx = faceW * 0.30f
                val bry = faceH * 0.13f
                drawOval(
                    color = PINK.copy(alpha = fillAlpha * 1.6f),
                    topLeft = Offset(brainC.x - brx, brainC.y - bry),
                    size = Size(brx * 2f, bry * 2f),
                    style = Fill
                )
                drawOval(
                    color = PINK.copy(alpha = strokeAlpha),
                    topLeft = Offset(brainC.x - brx, brainC.y - bry),
                    size = Size(brx * 2f, bry * 2f),
                    style = Stroke(width = strokeW)
                )
                val squiggle = Path().apply {
                    moveTo(brainC.x - brx * 0.6f, brainC.y - bry * 0.3f)
                    cubicTo(
                        brainC.x - brx * 0.3f, brainC.y - bry * 0.8f,
                        brainC.x, brainC.y + bry * 0.2f,
                        brainC.x + brx * 0.3f, brainC.y - bry * 0.4f
                    )
                    cubicTo(
                        brainC.x + brx * 0.5f, brainC.y - bry * 0.7f,
                        brainC.x + brx * 0.7f, brainC.y,
                        brainC.x + brx * 0.6f, brainC.y + bry * 0.4f
                    )
                }
                drawPath(
                    path = squiggle,
                    color = PINK.copy(alpha = strokeAlpha),
                    style = Stroke(width = strokeW * 0.7f)
                )
                val squiggle2 = Path().apply {
                    moveTo(brainC.x - brx * 0.5f, brainC.y + bry * 0.5f)
                    cubicTo(
                        brainC.x - brx * 0.2f, brainC.y,
                        brainC.x + brx * 0.1f, brainC.y + bry * 0.8f,
                        brainC.x + brx * 0.5f, brainC.y + bry * 0.3f
                    )
                }
                drawPath(
                    path = squiggle2,
                    color = PINK.copy(alpha = strokeAlpha),
                    style = Stroke(width = strokeW * 0.7f)
                )
            }

            if (settings.showMuscles) {
                // Cheek + jaw muscles, red.
                val eyeL = avg(EYE_L_OUTER, EYE_L_INNER)
                val eyeR = avg(EYE_R_INNER, EYE_R_OUTER)
                val mouthL = at(MOUTH_L)
                val mouthR = at(MOUTH_R)
                if (eyeL != null && mouthL != null) {
                    drawLine(RED.copy(alpha = strokeAlpha), eyeL, mouthL, strokeW)
                }
                if (eyeR != null && mouthR != null) {
                    drawLine(RED.copy(alpha = strokeAlpha), eyeR, mouthR, strokeW)
                }
                val jawPath = Path()
                var started = false
                for (index in JAWLINE) {
                    val p = at(index) ?: continue
                    if (!started) { jawPath.moveTo(p.x, p.y); started = true }
                    else jawPath.lineTo(p.x, p.y)
                }
                if (started) {
                    drawPath(jawPath, RED.copy(alpha = strokeAlpha * 0.8f), Stroke(width = strokeW * 0.8f))
                }
            }

            if (settings.showEyes) {
                // Eyeballs on the iris landmarks.
                val irisL = at(IRIS_L)
                val irisR = at(IRIS_R)
                val eyeR = faceW * 0.05f
                for (iris in listOfNotNull(irisL, irisR)) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f * b),
                        radius = eyeR,
                        center = iris,
                        style = Fill
                    )
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.9f),
                        radius = eyeR * 0.45f,
                        center = iris,
                        style = Fill
                    )
                }
            }
        }

        when (settings.reveal) {
            Reveal.WHOLE -> drawFace()
            Reveal.LEFT -> clipRect(boxL, boxT, midX, boxB) { drawFace() }
            Reveal.RIGHT -> clipRect(midX, boxT, boxR, boxB) { drawFace() }
            Reveal.TOP -> clipRect(boxL, boxT, boxR, midY) { drawFace() }
            Reveal.LOWER -> clipRect(boxL, midY, boxR, boxB) { drawFace() }
        }
    }

    if (settings.showHand && !handPoints.isNullOrEmpty()) {
        for ((a, bIdx) in HAND_CONNECTIONS) {
            val pa = handPoints.getOrNull(a) ?: continue
            val pb = handPoints.getOrNull(bIdx) ?: continue
            drawLine(
                color = ACCENT.copy(alpha = strokeAlpha),
                start = map(pa),
                end = map(pb),
                strokeWidth = 4f
            )
        }
        for (p in handPoints) {
            val q = map(p)
            drawCircle(
                color = CYAN.copy(alpha = fillAlpha * 2f),
                radius = 7f,
                center = q,
                style = Fill
            )
            drawCircle(
                color = ACCENT.copy(alpha = strokeAlpha),
                radius = 7f,
                center = q,
                style = Stroke(width = 2f)
            )
        }
    }
}
