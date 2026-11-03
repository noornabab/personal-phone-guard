
package com.personalphoneguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RecordingService : Service() {

    companion object {
        const val ACTION_START =
            "com.personalphoneguard.action.START"

        const val ACTION_STOP =
            "com.personalphoneguard.action.STOP"

        private const val CHANNEL_ID = "ppg_recording_channel"
        private const val NOTIFICATION_ID = 2001

        @Volatile
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> {
                startRecordingNotification()
            }

            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isRunning = false
                stopSelf()
            }

            else -> {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startRecordingNotification() {

        val notification = buildNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }

            isRunning = true

        } catch (e: Exception) {
            isRunning = false
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {

        val stopIntent = Intent(
            this,
            RecordingService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(
            this,
            MainActivity::class.java
        )

        val openPendingIntent = PendingIntent.getActivity(
            this,
            1001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Personal Phone Guard")
            .setContentText("Recording service active")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Shows when the recording service is active"
                setShowBadge(false)
            }

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }
}
