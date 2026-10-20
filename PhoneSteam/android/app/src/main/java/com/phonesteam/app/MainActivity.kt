package com.phonesteam.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.phonesteam.app.cloud.GameHubCloud
import com.phonesteam.app.openxr.OpenXrLauncherActivity
import com.phonesteam.app.util.LensProfile
import com.phonesteam.app.vr.VrActivity

class MainActivity : AppCompatActivity() {

    private val app get() = application as PhoneSteamApp
    private lateinit var hostInput: TextInputEditText
    private lateinit var joyStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val s = app.settings

        hostInput = findViewById(R.id.hostInput)
        hostInput.setText(s.hostAddress)
        handleDeepLink(intent)

        findViewById<MaterialSwitch>(R.id.cloudSwitch).apply {
            isChecked = s.cloudMode
            setOnCheckedChangeListener { _, v -> s.cloudMode = v }
        }
        findViewById<MaterialSwitch>(R.id.handSwitch).apply {
            isChecked = s.handTracking
            setOnCheckedChangeListener { _, v -> s.handTracking = v }
        }
        findViewById<MaterialSwitch>(R.id.joySwitch).apply {
            isChecked = s.joyCons
            setOnCheckedChangeListener { _, v -> s.joyCons = v }
        }

        val spinner = findViewById<Spinner>(R.id.lensSpinner)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LensProfile.ALL.map { it.label })
        spinner.setSelection(LensProfile.ALL.indexOfFirst { it.id == s.lensProfile }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { s.lensProfile = LensProfile.ALL[pos].id }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val bitrateLabel = findViewById<TextView>(R.id.bitrateLabel)
        findViewById<SeekBar>(R.id.bitrateSeek).apply {
            progress = s.bitrateMbps - 5
            bitrateLabel.text = "Bitrate: ${s.bitrateMbps} Mbps"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    s.bitrateMbps = p + 5
                    bitrateLabel.text = "Bitrate: ${s.bitrateMbps} Mbps"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        findViewById<RadioGroup>(R.id.fpsGroup).apply {
            check(when (s.fps) { 72 -> R.id.fps72; 90 -> R.id.fps90; else -> R.id.fps60 })
            setOnCheckedChangeListener { _, id -> s.fps = when (id) { R.id.fps72 -> 72; R.id.fps90 -> 90; else -> 60 } }
        }

        val gameHubStatus = findViewById<TextView>(R.id.gameHubStatus)
        val found = GameHubCloud.findInstalled(this)
        if (s.gameHubPackage.isBlank()) found.firstOrNull()?.let { s.gameHubPackage = it.packageName }
        gameHubStatus.text = if (found.isEmpty()) "App GameHub não encontrado — instale-o ou use um PC local."
        else "Encontrado: " + found.joinToString { it.label }
        findViewById<Button>(R.id.gameHubButton).setOnClickListener {
            if (!GameHubCloud.open(this, s.gameHubPackage)) {
                Toast.makeText(this, "GameHub não está instalado", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.startVrButton).setOnClickListener {
            saveHost()
            if (s.hostAddress.isBlank()) {
                Toast.makeText(this, "Digite o endereço do PC (o IP do PC do GameHub)", Toast.LENGTH_LONG).show()
                hostInput.requestFocus()
            } else startActivity(Intent(this, VrActivity::class.java))
        }
        findViewById<Button>(R.id.openXrButton).setOnClickListener {
            saveHost()
            startActivity(Intent(this, OpenXrLauncherActivity::class.java))
        }

        joyStatus = findViewById(R.id.joyStatus)
        app.joyCons.onChanged = { runOnUiThread { joyStatus.text = app.joyCons.describe() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "phonesteam") return
        val addr = uri.host + (if (uri.port > 0) ":${uri.port}" else "")
        hostInput.setText(addr)
        saveHost()
        Toast.makeText(this, "Host configurado: $addr", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        joyStatus.text = app.joyCons.describe()
    }

    override fun onPause() {
        saveHost()
        super.onPause()
    }

    private fun saveHost() {
        app.settings.hostAddress = hostInput.text?.toString().orEmpty()
    }
}
