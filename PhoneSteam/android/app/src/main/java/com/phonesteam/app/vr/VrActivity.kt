package com.phonesteam.app.vr

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.phonesteam.app.PhoneSteamApp
import com.phonesteam.app.hand.HandTracker
import com.phonesteam.app.stream.Protocol
import com.phonesteam.app.stream.StreamClient

/** Tela VR: estéreo para Cardboard + streaming do SteamVR. */
class VrActivity : AppCompatActivity(), StreamClient.Listener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: CardboardRenderer
    private lateinit var head: HeadTracker
    private lateinit var fusion: InputFusion
    private var hands: HandTracker? = null
    private var client: StreamClient? = null
    private val statusViews = ArrayList<TextView>()
    private val ui = Handler(Looper.getMainLooper())
    private var statusText = "Iniciando…"
    private var handStatus = ""

    private val app get() = application as PhoneSteamApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val settings = app.settings

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val w = maxOf(metrics.widthPixels, metrics.heightPixels)
        val h = minOf(metrics.widthPixels, metrics.heightPixels)
        val xdpi = if (metrics.widthPixels >= metrics.heightPixels) metrics.xdpi else metrics.ydpi
        val ydpi = if (metrics.widthPixels >= metrics.heightPixels) metrics.ydpi else metrics.xdpi
        val optics = CardboardOptics(w, h, xdpi, ydpi, settings.lens())

        head = HeadTracker(this, settings.eyeHeight)
        @Suppress("DEPRECATION")
        head.displayRotation = windowManager.defaultDisplay.rotation
        app.joyCons.enabled = settings.joyCons

        if (settings.handTracking) {
            hands = HandTracker(this, settings.cameraHFovDeg) { msg -> handStatus = msg; ui.post(::refreshStatus) }
        }
        fusion = InputFusion(head, app.joyCons.takeIf { settings.joyCons }, hands)

        val ipd = settings.lens().interLens
        val fov = optics.leftEyeFov()
        val scale = if (settings.cloudMode) 1.0f else 1.3f
        val eyeW = ((w / 2) * scale).toInt() / 16 * 16
        val eyeH = (h * scale).toInt() / 16 * 16
        val hello = Protocol.Hello(
            eyeW, eyeH, settings.fps, Protocol.MODE_STREAM, settings.bitrateMbps,
            ipd, fov.left, fov.right, fov.up, fov.down,
        )
        val c = StreamClient(settings.hostAddress, hello, settings.cloudMode, fusion::frame, this)
        client = c

        renderer = CardboardRenderer(optics, ipd, fusion::frame, c::setSurface, c::renderPoseFor) { c.streaming }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(buildLayout())
        hideSystemUi()

        if (settings.hostAddress.isNotBlank()) c.start() else onStatus("Configure o endereço do host na tela inicial", false)
        ui.postDelayed(object : Runnable {
            override fun run() { refreshStatus(); ui.postDelayed(this, 1000) }
        }, 1000)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildLayout(): View {
        val root = FrameLayout(this)
        root.addView(glView)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(2) {
            val tv = TextView(this).apply {
                setTextColor(0xCCE6EDF3.toInt())
                textSize = 11f
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                setPadding(24, 0, 24, 48)
            }
            statusViews.add(tv)
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        root.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Botão do Cardboard: segurar = gatilho direito; toque duplo = recentralizar.
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                head.recenter(); app.joyCons.recenter(); return true
            }
        })
        root.setOnTouchListener { _, e ->
            gestures.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> fusion.screenTouch = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fusion.screenTouch = false
            }
            true
        }
        return root
    }

    private fun refreshStatus() {
        val c = client
        val stats = if (c != null && c.streaming) {
            " · ${"%.0f".format(renderer.fps)} fps · ${c.framesReceived} frames"
        } else " · ${"%.0f".format(renderer.fps)} fps"
        val joy = if (app.settings.joyCons) "\n${app.joyCons.describe()}" else ""
        val hand = if (handStatus.isNotEmpty()) "\n$handStatus" else ""
        val text = statusText + stats + joy + hand
        // Esconde o texto quando o stream está rodando, para não atrapalhar o jogo.
        val show = c?.streaming != true
        statusViews.forEach { it.text = text; it.visibility = if (show) View.VISIBLE else View.GONE }
    }

    override fun onStatus(text: String, connected: Boolean) {
        statusText = text
        ui.post(::refreshStatus)
    }

    override fun onHaptic(controller: Int, duration: Float, frequency: Float, amplitude: Float) {
        app.joyCons.vibrate(controller, duration, amplitude)
    }

    override fun onResume() {
        super.onResume()
        head.start()
        glView.onResume()
        hideSystemUi()
        startHandsIfAllowed()
    }

    override fun onPause() {
        head.stop()
        glView.onPause()
        hands?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        client?.stop()
        renderer.release()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startHandsIfAllowed() {
        val h = hands ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            @Suppress("DEPRECATION")
            h.start(this, windowManager.defaultDisplay.rotation)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startHandsIfAllowed()
            else { handStatus = "Hand tracking desligado (sem permissão de câmera)"; hands = null }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (app.joyCons.handleKey(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_UP) {
            head.recenter(); return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        app.joyCons.handleMotion(event) || super.dispatchGenericMotionEvent(event)

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    companion object { private const val REQ_CAMERA = 10 }
}
