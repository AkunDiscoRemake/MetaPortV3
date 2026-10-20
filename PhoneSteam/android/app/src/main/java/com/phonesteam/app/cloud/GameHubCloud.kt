package com.phonesteam.app.cloud

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Integração com o app de nuvem GameHub.
 *
 * O fluxo é: o GameHub liga/conecta o PC na nuvem → nesse PC roda o SteamVR com o driver
 * PhoneSteam → o PhoneSteam conecta ao endereço desse PC e recebe o vídeo do SteamVR.
 *
 * Aqui só descobrimos e abrimos o app GameHub instalado; o endereço do PC na nuvem é configurado
 * na tela inicial (ou recebido por link `phonesteam://host:porta`).
 */
object GameHubCloud {

    data class App(val packageName: String, val label: String)

    fun findInstalled(context: Context): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return activities.mapNotNull {
            val pkg = it.activityInfo.packageName
            val label = it.loadLabel(pm).toString()
            val key = (label + " " + pkg).lowercase().replace(" ", "").replace("-", "")
            if ("gamehub" in key) App(pkg, label) else null
        }.distinctBy { it.packageName }
    }

    /** Abre o app GameHub. Retorna false se não estiver instalado. */
    fun open(context: Context, preferredPackage: String): Boolean {
        val pkg = preferredPackage.ifBlank { findInstalled(context).firstOrNull()?.packageName ?: return false }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
