
package com.personalphoneguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Environment
import androidx.lifecycle.LifecycleService
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RecordingService : LifecycleService() {

    companion object {
        const val ACTION_START =
            "com.personalphoneguard.action.START"

        const val ACTION_STOP =
            "com.personalphoneguard.action.STOP"

        const val ACTION_FINISHED =
            "com.personalphoneguard.action.FINISHED"

        const val EXTRA_VIDEO_PATH = "video_path"

        private const val CHANNEL_ID = "ppg_recording_channel"
        private const val NOTIFICATION_ID = 2001
        private const val MAX_RECORDING_MS = 20 * 60 * 1000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var recordingFile: File? = null
    private var isRecording = false

    private val cameraExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private val stopTimer = Runnable {
        stopRecording()
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

        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {

            ACTION_START -> {
                if (!isRecording && activeRecording == null) {
                    startForegroundSafely()
                }
            }

            ACTION_STOP -> {
                stopRecording()
            }

            else -> {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundSafely() {
        try {
            val notification = buildNotification()

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

            startCameraRecording()

        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun startCameraRecording() {

        val cameraPermission =
            checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        val audioPermission =
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!cameraPermission || !audioPermission) {
            stopSelf()
            return
        }

        val providerFuture =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({

            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(Quality.SD)
                    )
                    .build()

                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture

                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    capture
                )

                beginRecording(capture)

            } catch (e: Exception) {
                stopSelf()
            }

        }, cameraExecutor)
    }

    private fun beginRecording(
        capture: VideoCapture<Recorder>
    ) {

        val directory = getExternalFilesDir(
            Environment.DIRECTORY_MOVIES
        ) ?: run {
            stopSelf()
            return
        }

        if (!directory.exists()) {
            directory.mkdirs()
        }

        val file = File(
            directory,
            "PPG_${System.currentTimeMillis()}.mp4"
        )

        recordingFile = file

        val outputOptions = FileOutputOptions.Builder(file)
            .build()

        try {
            var pending: PendingRecording =
                capture.output.prepareRecording(
                    this,
                    outputOptions
                )

            pending = pending.withAudioEnabled()

            activeRecording = pending.start(
                cameraExecutor
            ) { event ->

                when (event) {

                    is VideoRecordEvent.Start -> {
                        isRecording = true

                        handler.postDelayed(
                            stopTimer,
                            MAX_RECORDING_MS
                        )
                    }

                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        handler.removeCallbacks(stopTimer)

                        val resultFile = recordingFile

                        if (!event.hasError() &&
                            resultFile != null &&
                            resultFile.exists() &&
                            resultFile.length() > 0
                        ) {
                            val finishedIntent =
                                Intent(ACTION_FINISHED).apply {
                                    setPackage(packageName)
                                    putExtra(
                                        EXTRA_VIDEO_PATH,
                                        resultFile.absolutePath
                                    )
                                }

                            sendBroadcast(finishedIntent)
                        }

                        activeRecording = null
                        releaseCamera()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }

        } catch (e: Exception) {
            handler.removeCallbacks(stopTimer)
            releaseCamera()
            stopSelf()
        }
    }

    private fun stopRecording() {
        handler.removeCallbacks(stopTimer)

        try {
            activeRecording?.stop()
        } catch (e: Exception) {
            releaseCamera()
            stopSelf()
        }

        if (activeRecording == null) {
            releaseCamera()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun releaseCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }

        cameraProvider = null
        videoCapture = null
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
            .setSmallIcon(
                android.R.drawable.presence_video_online
            )
            .setContentTitle("Personal Phone Guard")
            .setContentText("Video recording is active")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Recording",
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
                    "Shows when video recording is active"
                setShowBadge(false)
            }

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopTimer)
        releaseCamera()
        cameraExecutor.shutdown()
        isRecording = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
