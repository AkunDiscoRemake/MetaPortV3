package com.phonesteam.app.openxr

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import org.json.JSONArray

/** Descoberta de apps e runtimes OpenXR instalados no celular + biblioteca SteamVR do PC. */
object OpenXrCatalog {

    const val CATEGORY_OPENXR = "org.khronos.openxr.intent.category.IMMERSIVE_HMD"
    const val CATEGORY_CARDBOARD = "com.google.intent.category.CARDBOARD"
    const val CATEGORY_OCULUS = "com.oculus.intent.category.VR"
    const val RUNTIME_SERVICE = "org.khronos.openxr.OpenXRRuntimeService"
    private val BROKERS = listOf("org.khronos.openxr.runtime_broker", "org.khronos.openxr.system_runtime_broker")

    enum class Kind(val label: String) { OPENXR("OpenXR"), CARDBOARD("Cardboard"), OCULUS("Mobile VR"), STEAMVR("SteamVR · PC") }

    data class Entry(
        val title: String,
        val subtitle: String,
        val kind: Kind,
        val icon: Drawable? = null,
        val packageName: String? = null,
        val activity: String? = null,
        val steamKey: String? = null,
    )

    data class Runtime(val label: String, val packageName: String, val active: Boolean)

    fun phoneApps(context: Context): List<Entry> {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        for ((category, kind) in listOf(CATEGORY_OPENXR to Kind.OPENXR, CATEGORY_CARDBOARD to Kind.CARDBOARD, CATEGORY_OCULUS to Kind.OCULUS)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            @Suppress("DEPRECATION")
            for (ri in pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)) {
                val ai = ri.activityInfo
                if (ai.packageName == context.packageName || !seen.add(ai.packageName)) continue
                out += Entry(
                    title = ri.loadLabel(pm).toString(),
                    subtitle = ai.packageName,
                    kind = kind,
                    icon = runCatching { ri.loadIcon(pm) }.getOrNull(),
                    packageName = ai.packageName,
                    activity = ai.name,
                )
            }
        }
        return out.sortedBy { it.title.lowercase() }
    }

    fun runtimes(context: Context): List<Runtime> {
        val pm = context.packageManager
        val active = activeRuntimePackage(context)
        @Suppress("DEPRECATION")
        return pm.queryIntentServices(Intent(RUNTIME_SERVICE), PackageManager.GET_META_DATA).map {
            val pkg = it.serviceInfo.packageName
            Runtime(it.loadLabel(pm).toString(), pkg, pkg == active)
        }.distinctBy { it.packageName }
    }

    /** Consulta o "runtime broker" da Khronos para saber qual runtime OpenXR está ativo. */
    fun activeRuntimePackage(context: Context): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        for (authority in BROKERS) {
            val uri = Uri.parse("content://$authority/openxr/1/abi/$abi/runtimes/active/0")
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex("package_name")
                        if (idx >= 0) return c.getString(idx)
                    }
                }
            } catch (_: Exception) {
                // broker ausente ou sem permissão
            }
        }
        return null
    }

    fun launch(context: Context, e: Entry): Boolean {
        val pkg = e.packageName ?: return false
        val intent = if (e.activity != null) {
            Intent(Intent.ACTION_MAIN).setClassName(pkg, e.activity)
        } else context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Converte a lista JSON enviada pelo driver (apps do steamapps.vrmanifest do PC). */
    fun parseSteamApps(json: String): List<Entry> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val key = o.optString("key")
            Entry(o.optString("name", key), key, Kind.STEAMVR, steamKey = key)
        }.sortedBy { it.title.lowercase() }
    }
}
