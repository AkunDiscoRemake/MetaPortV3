package com.phonesteam.app.vr

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import com.phonesteam.app.input.ControllerState
import com.phonesteam.app.input.XrFrameState
import com.phonesteam.app.util.Quat
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renderizador estéreo para Cardboard.
 *
 * Por olho:
 *  1. Renderiza num FBO a imagem do SteamVR (textura de vídeo lado a lado) com "timewarp" rotacional
 *     — o quad é posicionado na orientação usada pelo PC e visto pela orientação atual da cabeça,
 *     escondendo a latência da rede. Sem stream, desenha uma sala de teste com os controles.
 *  2. Aplica a distorção de barril da lente do Cardboard ao copiar o FBO para a metade da tela.
 */
class CardboardRenderer(
    private val optics: CardboardOptics,
    private val ipd: Float,
    private val frameState: () -> XrFrameState,
    private val onVideoSurface: (Surface) -> Unit,
    private val renderPoseFor: (ptsUs: Long) -> Quat?,
    private val isStreaming: () -> Boolean,
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private var videoTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val frameAvailable = AtomicBoolean(false)
    private val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var videoPose = Quat()
    private var hasVideoFrame = false

    private lateinit var eyeFbo: Array<IntArray>
    private var eyeW = 0
    private var eyeH = 0
    private var progVideo = 0
    private var progScene = 0
    private var progDistort = 0

    private val proj = Array(2) { FloatArray(16) }
    private val tmpA = FloatArray(16)
    private val tmpB = FloatArray(16)
    private val mvp = FloatArray(16)
    private val fovs = arrayOf(optics.fov(0), optics.fov(1))

    /** Quad no plano z=-1 cobrindo o frustum de cada olho (posição xyz + uv). */
    private val videoQuads = Array(2) { eye ->
        val f = fovs[eye]
        GlUtil.floats(
            -f.left, -f.down, -1f, 0f, 0f,
            f.right, -f.down, -1f, 1f, 0f,
            -f.left, f.up, -1f, 0f, 1f,
            f.right, f.up, -1f, 1f, 1f,
        )
    }

    var fps = 0f
        private set
    private var frameCount = 0
    private var fpsTime = System.nanoTime()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        videoTex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture?.release()
        hasVideoFrame = false
        val st = SurfaceTexture(videoTex)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        onVideoSurface(Surface(st))

        progVideo = GlUtil.program(VS_VIDEO, FS_VIDEO)
        progScene = GlUtil.program(VS_SCENE, FS_SCENE)
        progDistort = GlUtil.program(VS_QUAD, FS_DISTORT)

        // Resolução do FBO: ~metade da tela por olho, com leve supersampling no centro.
        eyeW = (optics.screenWidthPx / 2 * 1.2f).toInt().coerceAtMost(2048)
        eyeH = (optics.screenHeightPx * 1.2f).toInt().coerceAtMost(2048)
        eyeFbo = arrayOf(GlUtil.framebuffer(eyeW, eyeH), GlUtil.framebuffer(eyeW, eyeH))

        for (eye in 0..1) {
            val f = fovs[eye]
            val n = 0.05f
            Matrix.frustumM(proj[eye], 0, -f.left * n, f.right * n, -f.down * n, f.up * n, n, 100f)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {}

    override fun onFrameAvailable(st: SurfaceTexture) { frameAvailable.set(true) }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture
        if (st != null && frameAvailable.getAndSet(false)) {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            renderPoseFor(st.timestamp / 1000)?.let { videoPose = it }
            hasVideoFrame = true
        }
        val state = frameState()
        val streaming = isStreaming() && hasVideoFrame

        for (eye in 0..1) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, eyeFbo[eye][0])
            GLES20.glViewport(0, 0, eyeW, eyeH)
            if (streaming) {
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                drawVideo(eye, state.headRotation)
            } else {
                GLES20.glClearColor(0.05f, 0.06f, 0.09f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                drawScene(eye, state)
            }
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, optics.screenWidthPx, optics.screenHeightPx)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        for (eye in 0..1) drawDistorted(eye)

        frameCount++
        val now = System.nanoTime()
        if (now - fpsTime > 1_000_000_000L) {
            fps = frameCount * 1e9f / (now - fpsTime)
            frameCount = 0; fpsTime = now
        }
    }

    // ---- vídeo com timewarp ----

    private fun drawVideo(eye: Int, current: Quat) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(progVideo)
        // Rotação delta: frame renderizado em videoPose, visto agora em `current`.
        (current.conjugate() * videoPose).normalized().toMatrix(tmpA)
        Matrix.multiplyMM(mvp, 0, proj[eye], 0, tmpA, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progVideo, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progVideo, "uTex"), 1, false, texMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progVideo, "uEyeOffset"), eye * 0.5f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progVideo, "uSampler"), 0)
        drawInterleaved(progVideo, videoQuads[eye], 3, 2)
    }

    // ---- cena de teste (sem conexão) ----

    private fun viewMatrix(eye: Int, state: XrFrameState, out: FloatArray) {
        state.headRotation.conjugate().toMatrix(tmpB)
        val eyeX = (if (eye == 0) -ipd / 2 else ipd / 2)
        val eyeWorld = state.headPosition + state.headRotation.rotate(com.phonesteam.app.util.Vec3(eyeX, 0f, 0f))
        Matrix.translateM(tmpB, 0, -eyeWorld.x, -eyeWorld.y, -eyeWorld.z)
        System.arraycopy(tmpB, 0, out, 0, 16)
    }

    private fun drawScene(eye: Int, state: XrFrameState) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(progScene)
        val view = FloatArray(16)
        viewMatrix(eye, state, view)
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, proj[eye], 0, view, 0)

        // Sala: cubo de 12 m x 4 m com grade.
        val model = FloatArray(16)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 2f, 0f)
        Matrix.scaleM(model, 0, 6f, 2f, 6f)
        drawCube(vp, model, floatArrayOf(0.4f, 0.75f, 0.96f, 1f), grid = true)

        drawController(vp, state.left, floatArrayOf(0.35f, 0.65f, 1f, 1f))
        drawController(vp, state.right, floatArrayOf(1f, 0.35f, 0.35f, 1f))
    }

    private fun drawController(vp: FloatArray, c: ControllerState, color: FloatArray) {
        if (!c.connected) return
        val model = FloatArray(16)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, c.position.x, c.position.y, c.position.z)
        val rot = c.rotation.toMatrix()
        val tmp = FloatArray(16)
        Matrix.multiplyMM(tmp, 0, model, 0, rot, 0)
        Matrix.scaleM(tmp, 0, 0.025f, 0.025f, 0.08f + 0.04f * c.trigger)
        val bright = if (c.trigger > 0.5f) 1.6f else 1f
        drawCube(vp, tmp, floatArrayOf(color[0] * bright, color[1] * bright, color[2] * bright, 1f), grid = false)
    }

    private fun drawCube(vp: FloatArray, model: FloatArray, color: FloatArray, grid: Boolean) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progScene, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progScene, "uModel"), 1, false, model, 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(progScene, "uColor"), 1, color, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progScene, "uGrid"), if (grid) 1f else 0f)
        val loc = GLES20.glGetAttribLocation(progScene, "aPos")
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, 3, GLES20.GL_FLOAT, false, 0, CUBE)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
        GLES20.glDisableVertexAttribArray(loc)
    }

    // ---- distorção da lente ----

    private fun drawDistorted(eye: Int) {
        val half = optics.screenWidthPx / 2
        GLES20.glViewport(eye * half, 0, half, optics.screenHeightPx)
        GLES20.glUseProgram(progDistort)
        val c = optics.lensCenterPx(eye)
        val f = fovs[eye]
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progDistort, "uLensCenterPx"), c[0], c[1])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progDistort, "uMpp"), optics.metersPerPxX, optics.metersPerPxY)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progDistort, "uScreenToLens"), optics.lens.screenToLens)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progDistort, "uK"), optics.lens.k1, optics.lens.k2)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(progDistort, "uTan"), f.left, f.right, f.up, f.down)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, eyeFbo[eye][1])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progDistort, "uTex"), 0)
        drawInterleaved(progDistort, FULLSCREEN, 2, 0)
    }

    private fun drawInterleaved(prog: Int, buf: FloatBuffer, posSize: Int, uvSize: Int) {
        val stride = (posSize + uvSize) * 4
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        buf.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, posSize, GLES20.GL_FLOAT, false, stride, buf)
        var aUv = -1
        if (uvSize > 0) {
            aUv = GLES20.glGetAttribLocation(prog, "aUv")
            buf.position(posSize)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, uvSize, GLES20.GL_FLOAT, false, stride, buf)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        if (aUv >= 0) GLES20.glDisableVertexAttribArray(aUv)
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
    }

    companion object {
        private val FULLSCREEN = GlUtil.floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        private val CUBE: FloatBuffer = run {
            val p = arrayOf(
                floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, -1f, -1f), floatArrayOf(1f, 1f, -1f), floatArrayOf(-1f, 1f, -1f),
                floatArrayOf(-1f, -1f, 1f), floatArrayOf(1f, -1f, 1f), floatArrayOf(1f, 1f, 1f), floatArrayOf(-1f, 1f, 1f),
            )
            val idx = intArrayOf(0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1, 3, 2, 6, 3, 6, 7, 0, 3, 7, 0, 7, 4, 1, 5, 6, 1, 6, 2)
            GlUtil.floats(*idx.flatMap { p[it].toList() }.toFloatArray())
        }

        private const val VS_VIDEO = """
            attribute vec3 aPos;
            attribute vec2 aUv;
            uniform mat4 uMvp;
            uniform mat4 uTex;
            uniform float uEyeOffset;
            varying vec2 vUv;
            void main() {
                vec2 sbs = vec2(aUv.x * 0.5 + uEyeOffset, aUv.y);
                vUv = (uTex * vec4(sbs, 0.0, 1.0)).xy;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        private const val FS_VIDEO = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uSampler;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uSampler, vUv); }
        """

        private const val VS_SCENE = """
            attribute vec3 aPos;
            uniform mat4 uMvp;
            uniform mat4 uModel;
            varying vec3 vWorld;
            void main() {
                vWorld = (uModel * vec4(aPos, 1.0)).xyz;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        private const val FS_SCENE = """
            precision mediump float;
            uniform vec4 uColor;
            uniform float uGrid;
            varying vec3 vWorld;
            void main() {
                if (uGrid < 0.5) { gl_FragColor = uColor; return; }
                vec3 g = abs(fract(vWorld * 2.0) - 0.5);
                float line = 1.0 - smoothstep(0.0, 0.03, min(min(g.x, g.y), g.z));
                float fade = clamp(1.2 - length(vWorld.xz) / 7.0, 0.25, 1.0);
                vec3 base = vec3(0.07, 0.09, 0.13);
                gl_FragColor = vec4(mix(base, uColor.rgb, line * fade), 1.0);
            }
        """

        private const val VS_QUAD = """
            attribute vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FS_DISTORT = """
            precision highp float;
            uniform sampler2D uTex;
            uniform vec2 uLensCenterPx;
            uniform vec2 uMpp;
            uniform float uScreenToLens;
            uniform vec2 uK;
            uniform vec4 uTan; // esquerda, direita, cima, baixo
            void main() {
                vec2 ts = (gl_FragCoord.xy - uLensCenterPx) * uMpp / uScreenToLens;
                float r2 = dot(ts, ts);
                vec2 te = ts * (1.0 + uK.x * r2 + uK.y * r2 * r2);
                vec2 uv = vec2((te.x + uTan.x) / (uTan.x + uTan.y), (te.y + uTan.w) / (uTan.z + uTan.w));
                if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                vec2 e = min(uv, 1.0 - uv);
                float vignette = clamp(min(e.x, e.y) * 40.0, 0.0, 1.0);
                gl_FragColor = vec4(texture2D(uTex, uv).rgb * vignette, 1.0);
            }
        """
    }
}
