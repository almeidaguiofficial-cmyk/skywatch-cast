package com.skywatch.screencast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream

/**
 * Foreground service (tipo mediaProjection) que captura a tela e empurra por RTMP.
 * - Vídeo only (sem áudio). Resolução da tela, FPS e bitrate escolhidos pelo usuário.
 * - Continua em segundo plano com o app do drone na frente.
 * - Reconecta sozinho se a conexão cair.
 * - Liga "Não Perturbe" enquanto transmite (se autorizado) pra segurar pop-ups.
 */
class ScreenService : Service(), ConnectChecker {

    companion object {
        const val PREFS = "skywatch"
        const val KEY_FPS = "fps"
        const val KEY_BITRATE_KBPS = "bitrate_kbps"
        const val KEY_DND = "dnd"
        const val DEFAULT_FPS = 30
        const val DEFAULT_BITRATE_KBPS = 2500

        private const val CHANNEL_ID = "skywatch_screencast"
        private const val NOTIFY_ID = 3210
        private const val RECONNECT_DELAY_MS = 5000L

        var INSTANCE: ScreenService? = null

        /** Callback de status pra UI (setado pela MainActivity). Estático pra sobreviver ao ciclo do serviço. */
        var statusListener: ((String) -> Unit)? = null
    }

    private lateinit var genericStream: GenericStream
    private var mediaProjection: MediaProjection? = null
    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private var manualStop = false
    private var reconnectAttempts = 0
    private var previousDndFilter = NotificationManager.INTERRUPTION_FILTER_ALL
    private var dndApplied = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Skywatch Screencast", NotificationManager.IMPORTANCE_LOW)
            )
        }
        // Sem vídeo/áudio ainda: o vídeo vira ScreenSource ao transmitir; áudio desativado.
        genericStream = GenericStream(baseContext, this, NoVideoSource(), NoAudioSource())
        // O pipeline exige preparar o áudio mesmo desativado (NoAudioSource não usa microfone).
        try {
            genericStream.prepareAudio(32000, true, 128_000)
        } catch (_: IllegalArgumentException) {
        }
        INSTANCE = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    fun sendIntent(): Intent = projectionManager.createScreenCaptureIntent()

    fun isStreaming(): Boolean = ::genericStream.isInitialized && genericStream.isStreaming

    /** Prepara vídeo com resolução da tela + FPS/bitrate do usuário e injeta a captura de tela. */
    fun prepareStream(resultCode: Int, data: Intent): Boolean {
        startForegroundNotification()
        if (genericStream.isStreaming) genericStream.stopStream()
        mediaProjection?.stop()

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val fps = prefs.getInt(KEY_FPS, DEFAULT_FPS).coerceIn(10, 60)
        val bitrate = prefs.getInt(KEY_BITRATE_KBPS, DEFAULT_BITRATE_KBPS).coerceIn(300, 20000) * 1000

        genericStream.getGlInterface().setForceRender(true, fps)

        var ready = false
        for ((w, h) in ScreenUtils.captureCandidates(this)) {
            ready = try {
                genericStream.prepareVideo(w, h, bitrate, fps, rotation = 0)
            } catch (_: IllegalArgumentException) {
                false
            }
            if (ready) break
        }
        if (!ready) return false

        val mp = projectionManager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection nula")
        mediaProjection = mp
        return try {
            genericStream.changeVideoSource(ScreenSource(applicationContext, mp))
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun startStream(endpoint: String) {
        if (::genericStream.isInitialized && !genericStream.isStreaming) {
            manualStop = false
            reconnectAttempts = 0
            applyDnd()
            genericStream.startStream(endpoint)
        }
    }

    fun stopStream() {
        manualStop = true
        stopStreamInternal()
        postStatus("Parado")
    }

    private fun stopStreamInternal() {
        if (::genericStream.isInitialized && genericStream.isStreaming) {
            genericStream.stopStream()
        }
        restoreDnd()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ---- Não Perturbe (segura pop-ups/chamadas durante a transmissão) ----

    private fun applyDnd() {
        val on = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DND, false)
        if (!on || !notificationManager.isNotificationPolicyAccessGranted) return
        previousDndFilter = notificationManager.currentInterruptionFilter
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        dndApplied = true
    }

    private fun restoreDnd() {
        if (dndApplied && notificationManager.isNotificationPolicyAccessGranted) {
            notificationManager.setInterruptionFilter(previousDndFilter)
        }
        dndApplied = false
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Skywatch Screencast")
            .setContentText("Transmitindo a tela ao vivo")
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun postStatus(text: String) {
        statusListener?.invoke(text)
    }

    override fun onDestroy() {
        super.onDestroy()
        manualStop = true
        if (::genericStream.isInitialized) {
            if (genericStream.isStreaming) genericStream.stopStream()
            genericStream.release()
        }
        restoreDnd()
        mediaProjection?.stop()
        mediaProjection = null
        INSTANCE = null
    }

    // ---- ConnectChecker: status + reconexão automática ----

    override fun onConnectionStarted(url: String) {
        postStatus("Conectando…")
    }

    override fun onConnectionSuccess() {
        reconnectAttempts = 0
        postStatus("● No ar")
    }

    override fun onConnectionFailed(reason: String) {
        if (manualStop) return
        reconnectAttempts++
        val client = genericStream.getStreamClient()
        client.setReTries(999_999)
        val retrying = client.reTry(RECONNECT_DELAY_MS, reason)
        if (retrying) {
            val hint = if (reconnectAttempts >= 12) " — confira a internet do celular" else ""
            postStatus("Reconectando… (tentativa $reconnectAttempts)$hint")
        } else {
            stopStreamInternal()
            postStatus("Falhou: $reason. Toque em Transmitir pra tentar de novo.")
        }
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        postStatus("Desconectado")
    }

    override fun onAuthError() {
        // Key errada: reconectar não resolve — pare e avise pra corrigir a URL.
        manualStop = true
        stopStreamInternal()
        postStatus("Erro de key/autenticação. Confira a URL (o ?key=...).")
    }

    override fun onAuthSuccess() {}
}
