package com.phonesteam.app.hand

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.phonesteam.app.util.Quat
import com.phonesteam.app.util.Vec3
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.tan

/**
 * Hand tracking pela câmera traseira (que fica livre na maioria dos Cardboards) usando o
 * MediaPipe Hand Landmarker. Cada mão vira um controle do SteamVR:
 *
 *  - posição/orientação: palma estimada a partir dos 21 pontos (profundidade pelo tamanho da mão)
 *  - gatilho: pinça polegar + indicador
 *  - grip: mão fechada
 *  - A: pinça polegar + médio · B: polegar + anelar · Menu: polegar + mindinho
 */
class HandTracker(
    private val context: Context,
    private val cameraHFovDeg: Float,
    private val onStatus: (String) -> Unit,
) {
    /** Pose de uma mão no espaço do visor (relativa à cabeça, metros, frame OpenVR). */
    data class Hand(
        val position: Vec3,
        val rotation: Quat,
        val trigger: Float,
        val grip: Float,
        val a: Boolean,
        val b: Boolean,
        val menu: Boolean,
        val timeMs: Long,
    )

    @Volatile var left: Hand? = null
        private set
    @Volatile var right: Hand? = null
        private set

    private var landmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var imageAspect = 0.75f
    @Volatile private var busy = false

    fun start(owner: LifecycleOwner, displayRotation: Int) {
        executor.execute {
            try {
                val model = ensureModel()
                landmarker = createLandmarker(model)
                ContextCompat.getMainExecutor(context).execute { bindCamera(owner, displayRotation) }
                onStatus("Hand tracking ativo")
            } catch (e: Exception) {
                Log.e(TAG, "falha ao iniciar hand tracking", e)
                onStatus("Hand tracking indisponível: ${e.message}")
            }
        }
    }

    fun stop() {
        cameraProvider?.unbindAll()
        executor.execute { landmarker?.close(); landmarker = null }
    }

    /** Uma mão é considerada perdida após 250 ms sem detecção. */
    fun fresh(h: Hand?): Hand? = h?.takeIf { SystemClock.uptimeMillis() - it.timeMs < 250 }

    // ---- modelo ----

    private fun ensureModel(): ByteBuffer {
        val file = File(context.filesDir, "hand_landmarker.task")
        if (!file.exists() || file.length() < 1_000_000) {
            onStatus("Baixando modelo de hand tracking…")
            val tmp = File(file.path + ".part")
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(file)
        }
        val bytes = file.readBytes()
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
    }

    private fun createLandmarker(model: ByteBuffer): HandLandmarker {
        fun build(delegate: Delegate): HandLandmarker {
            val base = BaseOptions.builder().setModelAssetBuffer(model).setDelegate(delegate).build()
            val opts = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result: HandLandmarkerResult, _ -> onResult(result) }
                .setErrorListener { e -> busy = false; Log.w(TAG, "mediapipe", e) }
                .build()
            return HandLandmarker.createFromOptions(context, opts)
        }
        return try { build(Delegate.GPU) } catch (e: Exception) {
            Log.w(TAG, "GPU indisponível, usando CPU", e)
            build(Delegate.CPU)
        }
    }

    // ---- câmera ----

    private fun bindCamera(owner: LifecycleOwner, displayRotation: Int) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setTargetRotation(displayRotation.takeIf { it in 0..3 } ?: Surface.ROTATION_90)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor, ::analyze)
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(image: ImageProxy) {
        val lm = landmarker
        if (lm == null || busy) { image.close(); return }
        val rotation = image.imageInfo.rotationDegrees
        val bitmap = image.toBitmap()
        image.close()
        val w = if (rotation % 180 == 0) bitmap.width else bitmap.height
        val h = if (rotation % 180 == 0) bitmap.height else bitmap.width
        imageAspect = h.toFloat() / w
        val opts = ImageProcessingOptions.builder().setRotationDegrees(rotation).build()
        busy = true
        try {
            lm.detectAsync(BitmapImageBuilder(bitmap).build(), opts, SystemClock.uptimeMillis())
        } catch (e: Exception) {
            busy = false
            Log.w(TAG, "detectAsync", e)
        }
    }

    // ---- landmarks → pose ----

    private fun onResult(result: HandLandmarkerResult) {
        busy = false
        val now = SystemClock.uptimeMillis()
        for (i in result.landmarks().indices) {
            val label = result.handedness()[i].firstOrNull()?.categoryName() ?: continue
            // O MediaPipe assume imagem espelhada (selfie); a câmera traseira não espelha → inverte.
            val isRight = label.equals("Left", ignoreCase = true)
            val hand = solve(result.landmarks()[i], result.worldLandmarks()[i], isRight, now) ?: continue
            if (isRight) right = smooth(right, hand) else left = smooth(left, hand)
        }
    }

    private fun Landmark.v() = Vec3(x(), -y(), -z()) // mundo MediaPipe → frame OpenVR

    private fun solve(n: List<NormalizedLandmark>, w: List<Landmark>, isRight: Boolean, now: Long): Hand? {
        if (n.size < 21 || w.size < 21) return null
        val tanX = tan(Math.toRadians(cameraHFovDeg / 2.0)).toFloat()
        val tanY = tanX * imageAspect

        // Profundidade: tamanho real (pulso → base do médio) / tamanho aparente na imagem.
        val dx = (n[9].x() - n[0].x()) * 2 * tanX
        val dy = (n[9].y() - n[0].y()) * 2 * tanY
        val apparent = kotlin.math.sqrt(dx * dx + dy * dy)
        if (apparent < 1e-3f) return null
        val real = (w[9].v() - w[0].v()).length().coerceIn(0.06f, 0.12f)
        val depth = (real / apparent).coerceIn(0.15f, 1.2f)

        val palmU = (n[0].x() + n[5].x() + n[9].x() + n[17].x()) / 4
        val palmV = (n[0].y() + n[5].y() + n[9].y() + n[17].y()) / 4
        val pos = Vec3((palmU - 0.5f) * 2 * tanX * depth, -(palmV - 0.5f) * 2 * tanY * depth, -depth)

        val fwd = (w[9].v() - w[0].v()).normalized()
        val side = (w[5].v() - w[17].v()).normalized()
        val zc = fwd * -1f
        var xc = if (isRight) side * -1f else side
        val yc = zc.cross(xc).normalized()
        xc = yc.cross(zc).normalized()
        val rot = Quat.fromBasis(xc, yc, zc)

        fun dist(a: Int, b: Int) = (w[a].v() - w[b].v()).length()
        val pinch = ((0.06f - dist(4, 8)) / 0.04f).coerceIn(0f, 1f)
        val curl = (dist(12, 0) + dist(16, 0) + dist(20, 0)) / 3
        val grip = ((0.14f - curl) / 0.06f).coerceIn(0f, 1f)
        val indexFree = dist(4, 8) > 0.05f
        return Hand(
            position = pos,
            rotation = rot,
            trigger = pinch,
            grip = grip,
            a = indexFree && dist(4, 12) < 0.025f,
            b = indexFree && dist(4, 16) < 0.025f,
            menu = indexFree && dist(4, 20) < 0.025f,
            timeMs = now,
        )
    }

    private fun smooth(prev: Hand?, cur: Hand): Hand {
        val p = fresh(prev) ?: return cur
        return cur.copy(position = p.position.lerp(cur.position, 0.55f), rotation = p.rotation.slerp(cur.rotation, 0.5f))
    }

    companion object {
        private const val TAG = "HandTracker"
        const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
    }
}
