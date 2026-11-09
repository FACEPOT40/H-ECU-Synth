package com.pai.hecusynth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Connected to the bike: reads the ECU over the K-line (read-only, [Protocol]) and feeds the
 * synth, with the screen off too. A foreground service (notification while it runs) with a
 * partial wake lock, like H-ECU Logger's LoggerService.
 */
class SynthService : Service() {
    @Volatile private var poller: EcuPoller? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var count = 0
    private var countSince = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
        }
        return START_NOT_STICKY
    }

    private fun connect() {
        startForeground(NOTIFICATION_ID, notification("Connecting"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (poller != null) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HECUSynth:bike").apply { acquire() }
        Core.connected = true
        Core.sample = null
        Core.startAudio("bike")
        // a stopped poller's thread can still report once more: ignore anything not from the current one
        lateinit var p: EcuPoller
        p = EcuPoller(
            openLink = { KLine(this) },
            onStatus = { if (poller === p) { Core.status = it; updateNotification(it) } },
            onSample = { s -> if (poller === p) onSample(s) },
        )
        poller = p
        countSince = System.currentTimeMillis()
        count = 0
        Thread(p::run, "kline-poller").start()
    }

    private fun onSample(s: EcuSample) {
        Core.synth.set(s.rpm, s.tpsPct, s.fuelCut)
        Core.sample = s
        val now = System.currentTimeMillis()
        Core.sampleAt = now
        count++
        if (now - countSince >= 1000) {
            Core.readingsPerSecond = count * 1000.0 / (now - countSince)
            count = 0
            countSince = now
        }
    }

    private fun disconnect() {
        stopPolling()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopPolling() {
        poller?.stop()
        poller = null
        if (Core.connected) Core.stopAudio("bike")
        Core.synth.set(0.0, 0.0, true)                     // engine "off": the sound stops
        Core.connected = false
        Core.status = "Disconnected"
        Core.sample = null
        Core.readingsPerSecond = 0.0
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopPolling()
        super.onDestroy()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bike connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("H-ECU Synth")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_CONNECT = "connect"
        const val ACTION_DISCONNECT = "disconnect"
        private const val CHANNEL = "bike"
        private const val NOTIFICATION_ID = 1
    }
}
