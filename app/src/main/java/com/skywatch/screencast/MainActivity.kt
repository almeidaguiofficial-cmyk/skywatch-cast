package com.skywatch.screencast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker

class MainActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var etUrl: EditText
    private lateinit var cbAudio: CheckBox
    private lateinit var bStartStop: Button
    private lateinit var tvStatus: TextView

    private val prefs by lazy { getSharedPreferences("skywatch", MODE_PRIVATE) }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val service = ScreenService.INSTANCE
        if (result.resultCode == RESULT_OK && data != null && service != null) {
            if (service.prepareStream(result.resultCode, data)) {
                service.startStream(currentUrl())
                setUiStreaming(true)
                setStatus("Conectando…")
            } else {
                toast("Falha ao preparar a transmissão")
                setUiStreaming(false)
            }
        } else {
            toast("Captura de tela negada")
            setUiStreaming(false)
        }
    }

    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            prefs.edit().putBoolean("audio_mic", true).apply()
            restartService()
        } else {
            cbAudio.isChecked = false
            toast("Sem permissão de microfone — seguindo só com vídeo")
        }
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.et_url)
        cbAudio = findViewById(R.id.cb_audio)
        bStartStop = findViewById(R.id.b_start_stop)
        tvStatus = findViewById(R.id.tv_status)

        etUrl.setText(prefs.getString("url", getString(R.string.default_url)))
        cbAudio.isChecked = prefs.getBoolean("audio_mic", false)

        ensureServiceRunning()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setUiStreaming(ScreenService.INSTANCE?.isStreaming() == true)

        cbAudio.setOnClickListener {
            if (ScreenService.INSTANCE?.isStreaming() == true) {
                cbAudio.isChecked = !cbAudio.isChecked
                toast("Pare a transmissão antes de mudar o áudio")
                return@setOnClickListener
            }
            if (cbAudio.isChecked) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    prefs.edit().putBoolean("audio_mic", true).apply()
                    restartService()
                } else {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            } else {
                prefs.edit().putBoolean("audio_mic", false).apply()
                restartService()
            }
        }

        bStartStop.setOnClickListener {
            val service = ScreenService.INSTANCE
            if (service == null) {
                ensureServiceRunning()
                toast("Iniciando serviço, toque de novo em 1 segundo")
                return@setOnClickListener
            }
            service.setCallback(this)
            if (service.isStreaming()) {
                service.stopStream()
                setUiStreaming(false)
                setStatus("Parado")
            } else {
                prefs.edit().putString("url", currentUrl()).apply()
                projectionLauncher.launch(service.sendIntent())
            }
        }
    }

    private fun ensureServiceRunning() {
        if (ScreenService.INSTANCE == null) {
            startService(Intent(this, ScreenService::class.java))
        }
    }

    /** Recria o serviço para aplicar a troca de modo de áudio (vídeo-only <-> microfone). */
    private fun restartService() {
        stopService(Intent(this, ScreenService::class.java))
        bStartStop.postDelayed({ ensureServiceRunning() }, 400)
        val mode = if (prefs.getBoolean("audio_mic", false)) "microfone" else "só vídeo"
        setStatus("Áudio: $mode")
    }

    private fun currentUrl(): String = etUrl.text.toString().trim()

    private fun setUiStreaming(streaming: Boolean) {
        bStartStop.text = getString(if (streaming) R.string.stop else R.string.start)
        etUrl.isEnabled = !streaming
        cbAudio.isEnabled = !streaming
    }

    private fun setStatus(text: String) {
        tvStatus.text = text
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() = runOnUiThread { setStatus("● No ar") }

    override fun onConnectionFailed(reason: String) = runOnUiThread {
        ScreenService.INSTANCE?.stopStream()
        setUiStreaming(false)
        setStatus("Falhou: $reason")
        toast("Falhou: $reason")
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() = runOnUiThread { setStatus("Desconectado") }

    override fun onAuthError() = runOnUiThread { setStatus("Erro de autenticação") }

    override fun onAuthSuccess() {}
}
