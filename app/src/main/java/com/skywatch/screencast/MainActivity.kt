package com.skywatch.screencast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var tvResolution: TextView
    private lateinit var sbFps: SeekBar
    private lateinit var tvFps: TextView
    private lateinit var sbBitrate: SeekBar
    private lateinit var tvBitrate: TextView
    private lateinit var cbDnd: CheckBox
    private lateinit var bStartStop: Button
    private lateinit var tvStatus: TextView

    private val prefs by lazy { getSharedPreferences(ScreenService.PREFS, MODE_PRIVATE) }

    // FPS 10..60  -> progress 0..50 ; Bitrate 500..12000 kbps passo 250 -> progress 0..46
    private val bitrateMinKbps = 500
    private val bitrateStepKbps = 250

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val service = ScreenService.INSTANCE
        if (result.resultCode == RESULT_OK && data != null && service != null) {
            if (service.prepareStream(result.resultCode, data)) {
                service.startStream(currentUrl())
            } else {
                toast("Falha ao preparar a transmissão")
            }
        } else {
            toast("Captura de tela negada")
        }
        refreshUi()
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.et_url)
        tvResolution = findViewById(R.id.tv_resolution)
        sbFps = findViewById(R.id.sb_fps)
        tvFps = findViewById(R.id.tv_fps)
        sbBitrate = findViewById(R.id.sb_bitrate)
        tvBitrate = findViewById(R.id.tv_bitrate)
        cbDnd = findViewById(R.id.cb_dnd)
        bStartStop = findViewById(R.id.b_start_stop)
        tvStatus = findViewById(R.id.tv_status)

        etUrl.setText(prefs.getString("url", getString(R.string.default_url)))

        val (w, h) = ScreenUtils.landscapeSize(this)
        tvResolution.text = "Resolução: ${w}×${h} (tela) — ajusta sozinho se o encoder não aguentar"

        // FPS
        sbFps.max = 50
        val fps = prefs.getInt(ScreenService.KEY_FPS, ScreenService.DEFAULT_FPS).coerceIn(10, 60)
        sbFps.progress = fps - 10
        renderFps(fps)
        sbFps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = renderFps(10 + p)
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putInt(ScreenService.KEY_FPS, 10 + sbFps.progress).apply()
            }
        })

        // Bitrate
        sbBitrate.max = (12000 - bitrateMinKbps) / bitrateStepKbps // 46
        val kbps = prefs.getInt(ScreenService.KEY_BITRATE_KBPS, ScreenService.DEFAULT_BITRATE_KBPS).coerceIn(500, 12000)
        sbBitrate.progress = (kbps - bitrateMinKbps) / bitrateStepKbps
        renderBitrate(kbps)
        sbBitrate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) =
                renderBitrate(bitrateMinKbps + p * bitrateStepKbps)
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putInt(ScreenService.KEY_BITRATE_KBPS, bitrateMinKbps + sbBitrate.progress * bitrateStepKbps).apply()
            }
        })

        // Não Perturbe
        cbDnd.isChecked = prefs.getBoolean(ScreenService.KEY_DND, false)
        cbDnd.setOnClickListener {
            if (cbDnd.isChecked) {
                prefs.edit().putBoolean(ScreenService.KEY_DND, true).apply()
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) {
                    toast("Permita o acesso \"Não Perturbe\" pro Skywatch Cast")
                    try {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
            } else {
                prefs.edit().putBoolean(ScreenService.KEY_DND, false).apply()
            }
        }

        ensureServiceRunning()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        bStartStop.setOnClickListener {
            val service = ScreenService.INSTANCE
            if (service == null) {
                ensureServiceRunning()
                toast("Iniciando serviço, toque de novo em 1 segundo")
                return@setOnClickListener
            }
            if (service.isStreaming()) {
                service.stopStream()
            } else {
                prefs.edit().putString("url", currentUrl()).apply()
                projectionLauncher.launch(service.sendIntent())
            }
            refreshUi()
        }
    }

    override fun onResume() {
        super.onResume()
        ScreenService.statusListener = { text -> runOnUiThread { setStatus(text); refreshUi() } }
        refreshUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenService.statusListener = null
    }

    private fun renderFps(fps: Int) {
        tvFps.text = "FPS: $fps"
    }

    private fun renderBitrate(kbps: Int) {
        tvBitrate.text = "Bitrate: %.1f Mbps".format(kbps / 1000.0)
    }

    private fun ensureServiceRunning() {
        if (ScreenService.INSTANCE == null) {
            startService(Intent(this, ScreenService::class.java))
        }
    }

    private fun currentUrl(): String = etUrl.text.toString().trim()

    private fun refreshUi() {
        val streaming = ScreenService.INSTANCE?.isStreaming() == true
        bStartStop.text = getString(if (streaming) R.string.stop else R.string.start)
        etUrl.isEnabled = !streaming
        sbFps.isEnabled = !streaming
        sbBitrate.isEnabled = !streaming
        cbDnd.isEnabled = !streaming
    }

    private fun setStatus(text: String) {
        tvStatus.text = text
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
