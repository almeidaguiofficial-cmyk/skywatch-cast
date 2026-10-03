package com.skywatch.screencast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.common.Throughput
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.QueueAwareBitrateAdapter

/**
 * Foreground service (tipo mediaProjection) que captura a tela e empurra por RTMP.
 * - Vídeo only (sem áudio). Resolução (preset, padrão 720p), FPS e bitrate MÁXIMO do usuário.
 * - Bitrate adaptativo: mede a fila de envio 1x/s e baixa/sobe o bitrate pro que a internet
 *   aguenta (antes era fixo — a fila de 400 quadros enchia, atrasava até ~13 s e a biblioteca
 *   descartava quadros, o que borrava a imagem até o próximo quadro-chave).
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
        const val KEY_QUALITY = "quality"
        const val DEFAULT_FPS = 30
        const val DEFAULT_BITRATE_KBPS = 2500

        private const val CHANNEL_ID = "skywatch_screencast"
        private const val NOTIFY_ID = 3210
        private const val RECONNECT_DELAY_MS = 5000L

        /** Piso do bitrate adaptativo: abaixo disso o 720p vira borrão de qualquer jeito. */
        private const val MIN_BITRATE = 300_000
        /** Atraso máximo na fila de envio antes de descartar o atraso e voltar ao vivo. */
        private const val MAX_BACKLOG_SECONDS = 3.0
        private const val BACKLOG_FLUSH_COOLDOWN_MS = 5000L
        private const val KEYFRAME_REQUEST_COOLDOWN_MS = 1000L

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

    private var screenSource: ScreenSource? = null
    private var encW = 0
    private var encH = 0
    private var manualStop = false
    private var reconnectAttempts = 0
    private var previousDndFilter = NotificationManager.INTERRUPTION_FILTER_ALL
    private var dndApplied = false

    private var bitrateAdapter: QueueAwareBitrateAdapter? = null
    private var targetBitrate = 0
    private var connected = false
    private var lastDroppedFrames = 0L
    private var lastKeyframeRequest = 0L
    private var lastBacklogFlush = 0L

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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Tela girou: redimensiona o display virtual pra orientação atual.
        // Sem isso o Android mantém o mapeamento antigo e a captura sai cortada.
        val src = screenSource ?: return
        if (!isStreaming() || encW == 0) return
        if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            src.resize(encH, encW)
        } else {
            src.resize(encW, encH)
        }
    }

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
        val quality = Resolution.Quality.fromKey(prefs.getString(KEY_QUALITY, null))

        genericStream.getGlInterface().setForceRender(true, fps)

        var ready = false
        for ((w, h) in ScreenUtils.captureCandidates(this, quality)) {
            ready = try {
                genericStream.prepareVideo(w, h, bitrate, fps, rotation = 0)
            } catch (_: IllegalArgumentException) {
                false
            }
            if (ready) {
                encW = w
                encH = h
                break
            }
        }
        if (!ready) return false

        // O bitrate do usuário vira TETO. A biblioteca entrega o estado da fila de envio 1x/s
        // (onStreamingStats) e o adaptador baixa rápido quando a fila cresce e sobe devagar
        // quando a rede folga. Piso de 300 kbps.
        targetBitrate = bitrate
        lastDroppedFrames = 0
        bitrateAdapter = QueueAwareBitrateAdapter(bitrate, maxOf(MIN_BITRATE, bitrate / 10)) { b ->
            targetBitrate = b
            genericStream.setVideoBitrateOnFly(b)
        }

        val mp = projectionManager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection nula")
        mediaProjection = mp
        return try {
            val source = ScreenSource(applicationContext, mp)
            screenSource = source
            genericStream.changeVideoSource(source)
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
        connected = false
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
        connected = true
        lastDroppedFrames = 0
        postStatus("● No ar · ${encW}×${encH}")
    }

    override fun onConnectionFailed(reason: String) {
        connected = false
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

    /** Chamado 1x/s pela biblioteca (thread principal) com o estado da fila de envio. */
    override fun onStreamingStats(report: StreamingStatsReport) {
        if (!connected || !isStreaming()) return
        bitrateAdapter?.onStreamingStats(report)
        val client = genericStream.getStreamClient()
        val now = System.currentTimeMillis()

        // Fila com mais de ~3 s acumulados: quem assiste estaria vendo o passado. Descarta o
        // atraso e pede quadro-chave pra imagem voltar ao vivo e limpa.
        val backlogSeconds = report.queueBytesOut * 8.0 / maxOf(targetBitrate, MIN_BITRATE)
        if (backlogSeconds > MAX_BACKLOG_SECONDS && now - lastBacklogFlush > BACKLOG_FLUSH_COOLDOWN_MS) {
            lastBacklogFlush = now
            client.clearCache()
            requestKeyframe(now, force = true)
        }

        // Cada quadro descartado pela biblioteca (fila cheia) borra a imagem até o próximo
        // quadro-chave (até 2 s). Pedir um quadro-chave na hora encurta isso pra ~1 quadro.
        val dropped = client.getDroppedVideoFrames()
        if (dropped < lastDroppedFrames) lastDroppedFrames = 0 // contador zera ao reconectar
        if (dropped > lastDroppedFrames) {
            lastDroppedFrames = dropped
            requestKeyframe(now)
        }

        val mbps = "%.1f".format(report.bitrate / 1_000_000.0).replace('.', ',')
        val weak = report.throughput == Throughput.INSUFFICIENT
        postStatus("● No ar · ${encW}×${encH} · $mbps Mbps" + if (weak) " · rede fraca, ajustando" else "")
    }

    private fun requestKeyframe(now: Long, force: Boolean = false) {
        if (!force && now - lastKeyframeRequest < KEYFRAME_REQUEST_COOLDOWN_MS) return
        lastKeyframeRequest = now
        genericStream.requestKeyframe()
    }

    override fun onDisconnect() {
        connected = false
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
