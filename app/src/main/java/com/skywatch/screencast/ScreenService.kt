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
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream

/**
 * Serviço em foreground que captura a tela (MediaProjection) e empurra por RTMP.
 * Roda como foreground service tipo mediaProjection, então continua transmitindo
 * mesmo com o app do drone (Fimi Navi) na frente.
 */
class ScreenService : Service(), ConnectChecker {

    companion object {
        private const val CHANNEL_ID = "skywatch_screencast"
        private const val NOTIFY_ID = 3210
        var INSTANCE: ScreenService? = null

        // Perfil de vídeo (landscape). Segure o controle/celular deitado durante o voo.
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val V_BITRATE = 2_000_000 // ~2 Mbps: bom equilíbrio pra 4G
        private const val FPS = 30
        private const val ROTATION = 0 // 0 = landscape, 90 = portrait
        private const val SAMPLE_RATE = 32_000
        private const val A_BITRATE = 128_000
    }

    private lateinit var genericStream: GenericStream
    private var mediaProjection: MediaProjection? = null
    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private var callback: ConnectChecker? = null
    private var prepared = false

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Skywatch Screencast", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val useMic = getSharedPreferences("skywatch", MODE_PRIVATE).getBoolean("audio_mic", false)
        val audioSource = if (useMic) MicrophoneSource() else NoAudioSource()

        genericStream = GenericStream(baseContext, this, NoVideoSource(), audioSource).apply {
            // MediaProjection só gera frame quando a tela muda; força um fps mínimo constante.
            getGlInterface().setForceRender(true, FPS)
        }
        prepared = try {
            genericStream.prepareVideo(WIDTH, HEIGHT, V_BITRATE, FPS, rotation = ROTATION) &&
                genericStream.prepareAudio(SAMPLE_RATE, true, A_BITRATE)
        } catch (e: IllegalArgumentException) {
            false
        }
        if (prepared) INSTANCE = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    fun sendIntent(): Intent = projectionManager.createScreenCaptureIntent()

    fun isStreaming(): Boolean = ::genericStream.isInitialized && genericStream.isStreaming

    fun setCallback(cb: ConnectChecker?) {
        callback = cb
    }

    fun prepareStream(resultCode: Int, data: Intent): Boolean {
        startForegroundNotification()
        if (genericStream.isStreaming) genericStream.stopStream()
        mediaProjection?.stop()
        val mp = projectionManager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection nula")
        mediaProjection = mp
        return try {
            genericStream.changeVideoSource(ScreenSource(applicationContext, mp))
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    fun startStream(endpoint: String) {
        if (::genericStream.isInitialized && !genericStream.isStreaming) {
            genericStream.startStream(endpoint)
        }
    }

    fun stopStream() {
        if (::genericStream.isInitialized && genericStream.isStreaming) {
            genericStream.stopStream()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
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

    override fun onDestroy() {
        super.onDestroy()
        if (::genericStream.isInitialized) {
            if (genericStream.isStreaming) genericStream.stopStream()
            genericStream.release()
        }
        mediaProjection?.stop()
        mediaProjection = null
        INSTANCE = null
    }

    override fun onConnectionStarted(url: String) {
        callback?.onConnectionStarted(url)
    }

    override fun onConnectionSuccess() {
        callback?.onConnectionSuccess()
    }

    override fun onNewBitrate(bitrate: Long) {
        callback?.onNewBitrate(bitrate)
    }

    override fun onConnectionFailed(reason: String) {
        callback?.onConnectionFailed(reason)
    }

    override fun onDisconnect() {
        callback?.onDisconnect()
    }

    override fun onAuthError() {
        callback?.onAuthError()
    }

    override fun onAuthSuccess() {
        callback?.onAuthSuccess()
    }
}
