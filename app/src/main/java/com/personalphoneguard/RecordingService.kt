
package com.personalphoneguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
        const val EXTRA_COOKIE = "extra_cookie"
        const val EXTRA_DEVICE = "extra_device"

        private const val CHANNEL_ID = "ppg_recording_channel"
        private const val NOTIFICATION_ID = 2001
        private const val MAX_RECORDING_MS = 20 * 60 * 1000L

        private const val API =
            "https://allinoneprotection.support446-ff6.workers.dev"
    }

    private val handler = Handler(Looper.getMainLooper())

    private val cameraExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private val uploadExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeRecording: Recording? = null

    private var recordingFile: File? = null
    private var sessionCookie = ""
    private var deviceId = ""

    private var isRecording = false
    private var stopRequested = false
    private var uploadInProgress = false

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
                    sessionCookie =
                        intent.getStringExtra(EXTRA_COOKIE) ?: ""

                    deviceId =
                        intent.getStringExtra(EXTRA_DEVICE) ?: ""

                    if (sessionCookie.isBlank() || deviceId.isBlank()) {
                        stopServiceNow()
                        return START_NOT_STICKY
                    }

                    startForegroundSafely()
                }
            }

            ACTION_STOP -> {
                stopRecording()
            }

            else -> {
                stopServiceNow()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundSafely() {
        try {
            val notification = buildNotification(
                "Recording is starting..."
            )

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
            stopServiceNow()
        }
    }

    private fun startCameraRecording() {

        val cameraPermission =
            checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

        val audioPermission =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        if (!cameraPermission || !audioPermission) {
            stopServiceNow()
            return
        }

        try {
            val providerFuture =
                ProcessCameraProvider.getInstance(this)

            providerFuture.addListener({

                try {
                    if (stopRequested) {
                        stopServiceNow()
                        return@addListener
                    }

                    val provider = providerFuture.get()
                    cameraProvider = provider

                    val recorder = Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.from(Quality.SD)
                        )
                        .build()

                    val capture =
                        VideoCapture.withOutput(recorder)

                    provider.unbindAll()

                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        capture
                    )

                    beginRecording(capture)

                } catch (e: Exception) {
                    stopServiceNow()
                }

            }, cameraExecutor)

        } catch (e: Exception) {
            stopServiceNow()
        }
    }

    private fun beginRecording(
        capture: VideoCapture<Recorder>
    ) {

        if (stopRequested) {
            stopServiceNow()
            return
        }

        val directory = getExternalFilesDir(
            Environment.DIRECTORY_MOVIES
        ) ?: run {
            stopServiceNow()
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

        val outputOptions =
            FileOutputOptions.Builder(file).build()

        try {
            val pending =
                capture.output.prepareRecording(
                    this,
                    outputOptions
                ).withAudioEnabled()

            activeRecording = pending.start(
                cameraExecutor
            ) { event ->

                when (event) {

                    is VideoRecordEvent.Start -> {
                        isRecording = true

                        updateNotification(
                            "Video recording is active"
                        )

                        handler.postDelayed(
                            stopTimer,
                            MAX_RECORDING_MS
                        )

                        if (stopRequested) {
                            stopRecording()
                        }
                    }

                    is VideoRecordEvent.Finalize -> {

                        isRecording = false

                        handler.removeCallbacks(stopTimer)

                        activeRecording = null

                        releaseCamera()

                        val resultFile = recordingFile

                        if (
                            !event.hasError() &&
                            resultFile != null &&
                            resultFile.exists() &&
                            resultFile.length() > 0
                        ) {
                            uploadVideo(resultFile)
                        } else {
                            updateNotification(
                                "Recording failed"
                            )
                            stopServiceNow()
                        }
                    }
                }
            }

        } catch (e: Exception) {
            handler.removeCallbacks(stopTimer)
            releaseCamera()
            stopServiceNow()
        }
    }

    private fun stopRecording() {

        handler.removeCallbacks(stopTimer)
        stopRequested = true

        try {
            val recording = activeRecording

            if (recording != null) {
                recording.stop()
            } else if (!uploadInProgress) {
                stopServiceNow()
            }

        } catch (e: Exception) {
            releaseCamera()
            stopServiceNow()
        }
    }

    private fun uploadVideo(file: File) {

        uploadInProgress = true

        updateNotification(
            "Uploading recorded video..."
        )

        uploadExecutor.execute {

            var connection: HttpURLConnection? = null

            try {
                connection =
                    (URL("$API/api/video").openConnection()
                        as HttpURLConnection)

                connection.requestMethod = "POST"
                connection.connectTimeout = 30000
                connection.readTimeout = 180000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Cookie",
                    sessionCookie
                )

                connection.setRequestProperty(
                    "X-Device-ID",
                    deviceId
                )

                connection.setRequestProperty(
                    "Content-Type",
                    "video/mp4"
                )

                connection.setFixedLengthStreamingMode(
                    file.length()
                )

                connection.outputStream.use { output ->
                    file.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }

                val responseCode =
                    connection.responseCode

                if (responseCode !in 200..299) {
                    val error =
                        connection.errorStream
                            ?.bufferedReader()
                            ?.use { it.readText() }

                    throw Exception(
                        error ?: "Upload failed: $responseCode"
                    )
                }

                handler.post {
                    updateNotification(
                        "Video uploaded successfully"
                    )
                    stopServiceNow()
                }

            } catch (e: Exception) {

                handler.post {
                    updateNotification(
                        "Upload failed. Local video retained."
                    )
                    stopServiceNow()
                }

            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun releaseCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }

        cameraProvider = null
    }

    private fun buildNotification(
        status: String
    ): Notification {

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
            .setContentText(status)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Recording",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(status: String) {
        val manager =
            getSystemService(NotificationManager::class.java)

        manager.notify(
            NOTIFICATION_ID,
            buildNotification(status)
        )
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Shows recording and upload status"
                setShowBadge(false)
            }

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    private fun stopServiceNow() {
        handler.removeCallbacks(stopTimer)

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }

        stopSelf()
    }

    override fun onDestroy() {

        handler.removeCallbacks(stopTimer)

        releaseCamera()

        cameraExecutor.shutdown()

        uploadExecutor.shutdown()

        isRecording = false

        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
