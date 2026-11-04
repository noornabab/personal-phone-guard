
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
import androidx.core.content.ContextCompat
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

        const val ACTION_STATUS =
            "com.personalphoneguard.action.STATUS"

        const val ACTION_FINISHED =
            "com.personalphoneguard.action.FINISHED"

        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_COOKIE = "extra_cookie"
        const val EXTRA_DEVICE = "extra_device"
        const val EXTRA_STATUS = "extra_status"

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

    @Volatile
    private var isRecording = false

    @Volatile
    private var stopRequested = false

    @Volatile
    private var uploadInProgress = false

    private var serviceStarted = false

    private val stopTimer = Runnable {
        publishStatus("২০ মিনিট পূর্ণ হয়েছে। Recording বন্ধ হচ্ছে...")
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

                if (serviceStarted || isRecording || uploadInProgress) {
                    return START_NOT_STICKY
                }

                stopRequested = false
                isRecording = false
                uploadInProgress = false

                sessionCookie =
                    intent.getStringExtra(EXTRA_COOKIE) ?: ""

                deviceId =
                    intent.getStringExtra(EXTRA_DEVICE) ?: ""

                if (
                    sessionCookie.isBlank() ||
                    deviceId.isBlank()
                ) {
                    fail("Login session অথবা Device ID পাওয়া যায়নি")
                    return START_NOT_STICKY
                }

                startForegroundSafely()
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
                "Camera চালু হচ্ছে..."
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

            serviceStarted = true

            publishStatus("Camera চালু হচ্ছে...")
            startCameraRecording()

        } catch (e: Exception) {

            fail("Foreground Service Error: ${e.message}")
        }
    }

    private fun startCameraRecording() {

        val cameraPermission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

        val audioPermission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        if (!cameraPermission || !audioPermission) {
            fail("Camera অথবা Microphone permission নেই")
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

                    publishStatus("Camera প্রস্তুত। Recording শুরু হচ্ছে...")
                    updateNotification("Camera প্রস্তুত...")

                    beginRecording(capture)

                } catch (e: Exception) {

                    fail("Camera চালু হয়নি: ${e.message}")
                }

            }, ContextCompat.getMainExecutor(this))

        } catch (e: Exception) {

            fail("Camera Error: ${e.message}")
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
        )

        if (directory == null) {
            fail("ফোনে Video Storage পাওয়া যায়নি")
            return
        }

        if (!directory.exists() && !directory.mkdirs()) {
            fail("Video Folder তৈরি করা যায়নি")
            return
        }

        val file = File(
            directory,
            "PPG_${System.currentTimeMillis()}.mp4"
        )

        recordingFile = file

        try {

            val outputOptions =
                FileOutputOptions.Builder(file).build()

            val pending =
                capture.output
                    .prepareRecording(this, outputOptions)
                    .withAudioEnabled()

            activeRecording = pending.start(
                cameraExecutor
            ) { event ->

                when (event) {

                    is VideoRecordEvent.Start -> {

                        handler.post {

                            isRecording = true

                            publishStatus(
                                "Recording চলছে। সর্বোচ্চ ২০ মিনিট।"
                            )

                            updateNotification(
                                "Video + Audio Recording চলছে"
                            )

                            handler.removeCallbacks(stopTimer)

                            handler.postDelayed(
                                stopTimer,
                                MAX_RECORDING_MS
                            )

                            if (stopRequested) {
                                stopRecording()
                            }
                        }
                    }

                    is VideoRecordEvent.Status -> {
                        // CameraX recording progress event.
                    }

                    is VideoRecordEvent.Finalize -> {

                        handler.post {

                            isRecording = false

                            handler.removeCallbacks(stopTimer)

                            activeRecording = null

                            releaseCamera()

                            val savedFile = recordingFile

                            if (
                                !event.hasError() &&
                                savedFile != null &&
                                savedFile.exists() &&
                                savedFile.length() > 0
                            ) {

                                publishStatus(
                                    "ফোনে ভিডিও Save হয়েছে। Upload প্রস্তুত হচ্ছে...",
                                    savedFile.absolutePath
                                )

                                uploadVideo(savedFile)

                            } else {

                                val reason =
                                    "Recording Finalize Error: ${event.error}"

                                fail(reason)
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {

            handler.removeCallbacks(stopTimer)
            releaseCamera()

            fail("Recording শুরু হয়নি: ${e.message}")
        }
    }

    private fun stopRecording() {

        handler.removeCallbacks(stopTimer)

        stopRequested = true

        if (uploadInProgress) {
            publishStatus("ভিডিও Upload হচ্ছে। Local Copy নিরাপদ আছে।")
            return
        }

        try {

            val recording = activeRecording

            if (recording != null) {

                if (isRecording) {
                    publishStatus("Recording বন্ধ হচ্ছে...")
                    updateNotification("Recording বন্ধ হচ্ছে...")
                }

                recording.stop()

            } else if (!isRecording) {

                // Camera initialization এখনও চললে
                // callback stopRequested দেখে থামবে।
                publishStatus("Recording বন্ধের অনুরোধ গ্রহণ করা হয়েছে।")
            }

        } catch (e: Exception) {

            fail("Stop Error: ${e.message}")
        }
    }

    private fun uploadVideo(file: File) {

        if (uploadInProgress) return

        uploadInProgress = true

        publishStatus(
            "ফোনে Save হয়েছে। Server-এ Upload হচ্ছে...",
            file.absolutePath
        )

        updateNotification("Video Upload হচ্ছে...")

        uploadExecutor.execute {

            var connection: HttpURLConnection? = null

            try {

                connection =
                    (URL("$API/api/video").openConnection()
                        as HttpURLConnection)

                connection.requestMethod = "POST"
                connection.connectTimeout = 30000
                connection.readTimeout = 300000
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
                        error ?: "HTTP $responseCode"
                    )
                }

                handler.post {

                    uploadInProgress = false

                    val status =
                        "Upload সফল। ফোনেও Local Copy আছে।"

                    publishStatus(
                        status,
                        file.absolutePath
                    )

                    updateNotification(status)

                    sendFinishedBroadcast(
                        "uploaded",
                        file.absolutePath
                    )

                    stopServiceNow()
                }

            } catch (e: Exception) {

                handler.post {

                    uploadInProgress = false

                    val status =
                        "Upload ব্যর্থ। ফোনে Local Copy সংরক্ষিত আছে। কারণ: ${e.message}"

                    publishStatus(
                        status,
                        file.absolutePath
                    )

                    updateNotification(
                        "Upload ব্যর্থ। Local Video সংরক্ষিত আছে।"
                    )

                    sendFinishedBroadcast(
                        "upload_failed",
                        file.absolutePath
                    )

                    stopServiceNow()
                }

            } finally {

                connection?.disconnect()
            }
        }
    }

    private fun publishStatus(
        status: String,
        path: String = ""
    ) {

        val prefs = getSharedPreferences(
            "phone_guard",
            MODE_PRIVATE
        )

        prefs.edit()
            .putString("recording_status", status)
            .putString(
                "recording_video_path",
                path.ifBlank {
                    prefs.getString(
                        "recording_video_path",
                        ""
                    ) ?: ""
                }
            )
            .apply()

        val broadcast = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_VIDEO_PATH, path)
        }

        sendBroadcast(broadcast)
    }

    private fun sendFinishedBroadcast(
        result: String,
        path: String
    ) {

        val intent = Intent(ACTION_FINISHED).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, result)
            putExtra(EXTRA_VIDEO_PATH, path)
        }

        sendBroadcast(intent)
    }

    private fun fail(reason: String) {

        handler.removeCallbacks(stopTimer)

        isRecording = false
        uploadInProgress = false

        publishStatus("Failed: $reason")

        updateNotification("Recording ব্যর্থ হয়েছে")

        releaseCamera()

        stopServiceNow()
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
                    "Shows visible video recording and upload status"
                setShowBadge(false)
            }

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    private fun stopServiceNow() {

        handler.removeCallbacks(stopTimer)

        serviceStarted = false

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
        serviceStarted = false

        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
