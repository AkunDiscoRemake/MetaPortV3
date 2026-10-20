package com.phonesteam.app.openxr

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.phonesteam.app.PhoneSteamApp
import com.phonesteam.app.stream.StreamClient
import com.phonesteam.app.vr.VrActivity

/**
 * Launcher OpenXR:
 *  - aba "Celular": apps OpenXR / Cardboard instalados no Android (rodam com o runtime OpenXR ativo)
 *  - aba "SteamVR (PC)": apps VR da biblioteca Steam do PC/nuvem; abrir = iniciar no PC e entrar no stream.
 *    O SteamVR é um runtime OpenXR, então jogos OpenXR do PC rodam por ele no Cardboard.
 */
class OpenXrLauncherActivity : AppCompatActivity() {

    private val app get() = application as PhoneSteamApp
    private lateinit var info: TextView
    private lateinit var list: RecyclerView
    private val adapter = Adapter()
    private var phoneApps = emptyList<OpenXrCatalog.Entry>()
    private var pcApps = emptyList<OpenXrCatalog.Entry>()
    private var tab = 0
    private var control: StreamClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), 0)
        }
        root.addView(TextView(this).apply {
            text = "Launcher OpenXR"
            textSize = 26f
            setTextColor(Color.WHITE)
        })
        info = TextView(this).apply { setTextColor(0xFF8B98A9.toInt()); textSize = 13f; setPadding(0, dp(4), 0, dp(8)) }
        root.addView(info)
        val tabs = TabLayout(this).apply {
            addTab(newTab().setText("Celular"))
            addTab(newTab().setText("SteamVR (PC)"))
            setBackgroundColor(Color.TRANSPARENT)
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(t: TabLayout.Tab) { tab = t.position; refresh() }
                override fun onTabUnselected(t: TabLayout.Tab) {}
                override fun onTabReselected(t: TabLayout.Tab) { if (t.position == 1) loadPcApps() }
            })
        }
        root.addView(tabs)
        list = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@OpenXrLauncherActivity, 3)
            adapter = this@OpenXrLauncherActivity.adapter
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        loadPcApps()
    }

    override fun onResume() {
        super.onResume()
        phoneApps = OpenXrCatalog.phoneApps(this)
        refresh()
    }

    override fun onDestroy() {
        control?.stop()
        super.onDestroy()
    }

    private fun refresh() {
        if (tab == 0) {
            val runtimes = OpenXrCatalog.runtimes(this)
            val active = runtimes.firstOrNull { it.active }
            info.text = when {
                active != null -> "Runtime OpenXR ativo: ${active.label}"
                runtimes.isNotEmpty() -> "Runtimes instalados: ${runtimes.joinToString { it.label }} (nenhum ativo)"
                else -> "Nenhum runtime OpenXR no celular. Apps OpenXR nativos precisam de um runtime " +
                    "(ex.: Monado). Apps do PC funcionam pela aba SteamVR."
            } + " · ${phoneApps.size} apps"
            adapter.items = phoneApps
        } else {
            val host = app.settings.hostAddress
            info.text = if (host.isBlank()) "Configure o endereço do PC na tela inicial"
            else "PC: $host · ${pcApps.size} apps VR (toque na aba de novo para atualizar)"
            adapter.items = pcApps
        }
    }

    private fun loadPcApps() {
        val host = app.settings.hostAddress
        if (host.isBlank()) return
        control?.stop()
        control = StreamClient.control(host, { it.requestAppList() }, object : StreamClient.Listener {
            override fun onStatus(text: String, connected: Boolean) {
                runOnUiThread { if (tab == 1 && pcApps.isEmpty()) info.text = text }
            }
            override fun onAppList(json: String) {
                val apps = OpenXrCatalog.parseSteamApps(json)
                runOnUiThread { pcApps = apps; refresh() }
                control?.stop(); control = null
            }
        })
    }

    private fun open(e: OpenXrCatalog.Entry) {
        if (e.kind == OpenXrCatalog.Kind.STEAMVR) {
            val key = e.steamKey ?: return
            val c = StreamClient.control(app.settings.hostAddress, {
                it.launchApp(key)
                Thread.sleep(300)
                it.stop()
            }, object : StreamClient.Listener {
                override fun onStatus(text: String, connected: Boolean) {}
            })
            control = c
            Toast.makeText(this, "Abrindo ${e.title} no PC…", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, VrActivity::class.java))
            return
        }
        if (OpenXrCatalog.activeRuntimePackage(this) == null && e.kind == OpenXrCatalog.Kind.OPENXR) {
            AlertDialog.Builder(this)
                .setTitle("Sem runtime OpenXR ativo")
                .setMessage("${e.title} é um app OpenXR nativo e precisa de um runtime OpenXR para celular (ex.: Monado) instalado e ativo. Abrir mesmo assim?")
                .setPositiveButton("Abrir") { _, _ -> OpenXrCatalog.launch(this, e) }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        if (!OpenXrCatalog.launch(this, e)) Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<OpenXrCatalog.Entry> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val cell = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(10), dp(6), dp(10))
                isClickable = true
                isFocusable = true
                val tv = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
            }
            val icon = ImageView(parent.context)
            cell.addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)))
            val title = TextView(parent.context).apply {
                setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; maxLines = 2
            }
            cell.addView(title)
            val badge = TextView(parent.context).apply { setTextColor(0xFF66C0F4.toInt()); textSize = 10f; gravity = Gravity.CENTER }
            cell.addView(badge)
            return Holder(cell, icon, title, badge)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val e = items[position]
            h.title.text = e.title
            h.badge.text = e.kind.label
            if (e.icon != null) h.icon.setImageDrawable(e.icon)
            else h.icon.setImageResource(com.phonesteam.app.R.drawable.ic_launcher)
            h.itemView.setOnClickListener { open(e) }
        }
    }

    private class Holder(v: View, val icon: ImageView, val title: TextView, val badge: TextView) : RecyclerView.ViewHolder(v)
}
