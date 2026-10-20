package com.phonesteam.app.util

import android.content.Context
import android.content.SharedPreferences

/** Configurações persistentes do PhoneSteam. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("phonesteam", Context.MODE_PRIVATE)

    /** Endereço do host (PC local ou PC na nuvem do GameHub), ex.: "192.168.0.10" ou "meupc.gamehub.cloud:9945". */
    var hostAddress: String
        get() = prefs.getString("host", "") ?: ""
        set(v) = prefs.edit().putString("host", v.trim()).apply()

    /** Modo nuvem: envia pose também por TCP e usa bitrate menor. */
    var cloudMode: Boolean
        get() = prefs.getBoolean("cloud", true)
        set(v) = prefs.edit().putBoolean("cloud", v).apply()

    var handTracking: Boolean
        get() = prefs.getBoolean("hands", true)
        set(v) = prefs.edit().putBoolean("hands", v).apply()

    var joyCons: Boolean
        get() = prefs.getBoolean("joycons", true)
        set(v) = prefs.edit().putBoolean("joycons", v).apply()

    var bitrateMbps: Int
        get() = prefs.getInt("bitrate", 30)
        set(v) = prefs.edit().putInt("bitrate", v.coerceIn(5, 150)).apply()

    var fps: Int
        get() = prefs.getInt("fps", 60)
        set(v) = prefs.edit().putInt("fps", v.coerceIn(30, 120)).apply()

    /** Altura do usuário em pé (altura dos olhos), em metros. */
    var eyeHeight: Float
        get() = prefs.getFloat("eyeHeight", 1.65f)
        set(v) = prefs.edit().putFloat("eyeHeight", v.coerceIn(0.8f, 2.2f)).apply()

    var lensProfile: String
        get() = prefs.getString("lens", LensProfile.CARDBOARD_V2.id) ?: LensProfile.CARDBOARD_V2.id
        set(v) = prefs.edit().putString("lens", v).apply()

    /** Pacote do app GameHub (detectado automaticamente ou definido pelo usuário). */
    var gameHubPackage: String
        get() = prefs.getString("gamehubPkg", "") ?: ""
        set(v) = prefs.edit().putString("gamehubPkg", v).apply()

    /** FOV horizontal da câmera traseira, usado para estimar a posição das mãos. */
    var cameraHFovDeg: Float
        get() = prefs.getFloat("camFov", 66f)
        set(v) = prefs.edit().putFloat("camFov", v.coerceIn(40f, 120f)).apply()

    fun lens(): LensProfile = LensProfile.byId(lensProfile)
}

/** Parâmetros óticos do visor Cardboard. */
data class LensProfile(
    val id: String,
    val label: String,
    val screenToLens: Float,      // m
    val interLens: Float,         // m
    val trayToLensCenter: Float,  // m (da borda inferior da tela até o centro da lente)
    val k1: Float,
    val k2: Float,
    val maxFovDeg: Float,
) {
    companion object {
        val CARDBOARD_V1 = LensProfile("v1", "Cardboard v1 (2014)", 0.042f, 0.060f, 0.035f, 0.441f, 0.156f, 40f)
        val CARDBOARD_V2 = LensProfile("v2", "Cardboard v2 / genérico", 0.039f, 0.0639f, 0.035f, 0.34f, 0.55f, 50f)
        val WIDE = LensProfile("wide", "Visor plástico 100° (VR Box)", 0.040f, 0.064f, 0.037f, 0.40f, 0.25f, 55f)
        val ALL = listOf(CARDBOARD_V2, CARDBOARD_V1, WIDE)
        fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: CARDBOARD_V2
    }
}
