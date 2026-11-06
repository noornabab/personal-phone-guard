package com.personalphoneguard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
        const val ACTION_START   = "com.personalphoneguard.action.START"
        const val ACTION_STOP    = "com.personalphoneguard.action.STOP"
        const val ACTION_STATUS  = "com.personalphoneguard.action.STATUS"
        const val ACTION_FINISHED = "com.personalphoneguard.action.FINISHED"

        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_COOKIE     = "extra_cookie"
        const val EXTRA_DEVICE     = "extra_device"
        const val EXTRA_STATUS     = "extra_status"

        private const val CHANNEL_ID       = "ppg_recording_channel"
        private const val NOTIFICATION_ID  = 2001

        // ১৫ মিনিট পরপর নতুন chunk শুরু হবে
        private const val MAX_CHUNK_MS = 15 * 60 * 1000L

        // মোট ৮ ঘণ্টা পর সব বন্ধ হবে
        private const val MAX_TOTAL_MS = 8 * 60 * 60 * 1000L

        // ভিডিও ৩০০ kbps + অডিও ৬৪ kbps → প্রতি chunk ~৩৪ MB
        private const val VIDEO_BITRATE = 300_000
        private const val AUDIO_BITRATE = 64_000

        private const val API = "https://allinoneprotection.support446-ff6.workers.dev"
    }

    private val handler        = Handler(Looper.getMainLooper())
    private val cameraExecutor : ExecutorService = Executors.newSingleThreadExecutor()
    private val uploadExecutor : ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider : ProcessCameraProvider? = null
    private var videoCapture   : VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recordingFile  : File? = null

    private var sessionCookie = ""
    private var deviceId      = ""

    @Volatile private var isRecording    = false
    @Volatile private var stopRequested  = false
    @Volatile private var chunkCount     = 0
    @Volatile private var uploadsRunning = 0

    private var serviceStarted   = false
    private var sessionStartTime = 0L

    // ১৫ মিনিট পর বর্তমান chunk শেষ করে নতুনটা শুরু করে
    private val chunkTimer = Runnable {
        if (!stopRequested) {
            val c = chunkCount
            publishStatus("Chunk $c শেষ। পরবর্তী chunk শুরু হচ্ছে...")
            rotateChunk()
        }
    }

    // ৮ ঘণ্টা পর সব বন্ধ করে
    private val totalTimer = Runnable {
        publishStatus("৮ ঘণ্টা সম্পন্ন। Recording বন্ধ হচ্ছে...")
        stopRequested = true
        stopRecording()
    }

    // ------------------------------------------------
    // LIFECYCLE
    // ------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                if (serviceStarted || isRecording) return START_NOT_STICKY

                stopRequested  = false
                isRecording    = false
                chunkCount     = 0
                uploadsRunning = 0
                sessionStartTime = System.currentTimeMillis()

                sessionCookie = intent.getStringExtra(EXTRA_COOKIE) ?: ""
                deviceId      = intent.getStringExtra(EXTRA_DEVICE) ?: ""

                if (sessionCookie.isBlank() || deviceId.isBlank()) {
                    fail("Login session অথবা Device ID পাওয়া যায়নি")
                    return START_NOT_STICKY
                }

                startForegroundSafely()
            }
            ACTION_STOP -> stopRecording()
            else        -> stopServiceNow()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(chunkTimer)
        handler.removeCallbacks(totalTimer)
        cameraExecutor.shutdownNow()
        uploadExecutor.shutdownNow()
        releaseCamera()
        super.onDestroy()
    }

    // ------------------------------------------------
    // FOREGROUND START
    // ------------------------------------------------

    private fun startForegroundSafely() {
        try {
            val hasCamera = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            val hasAudio = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasCamera || !hasAudio) {
                fail("Camera অথবা Microphone permission নেই")
                return
            }

            val notification = buildNotification("Camera চালু হচ্ছে...")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            serviceStarted = true
            publishStatus("Camera চালু হচ্ছে...")
            startCameraAndRecord()

        } catch (e: Exception) {
            fail("Foreground Service Error: ${e.message}")
        }
    }

    // ------------------------------------------------
    // CAMERA SETUP
    // ------------------------------------------------

    private fun startCameraAndRecord() {
        try {
            val future = ProcessCameraProvider.getInstance(this)

            future.addListener({
                try {
                    if (stopRequested) { stopServiceNow(); return@addListener }

                    val provider = future.get()
                    cameraProvider = provider

                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.LOWEST))
                        .setTargetVideoEncodingBitRate(VIDEO_BITRATE)
                        .build()

                    videoCapture = VideoCapture.withOutput(recorder)

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        videoCapture!!
                    )

                    // ৮ ঘণ্টার মোট টাইমার শুরু
                    handler.removeCallbacks(totalTimer)
                    handler.postDelayed(totalTimer, MAX_TOTAL_MS)

                    publishStatus("Camera প্রস্তুত। Chunk 1 শুরু হচ্ছে...")
                    updateNotification("● Video + Audio Recording চলছে")

                    beginChunk()

                } catch (e: Exception) {
                    fail("Camera চালু হয়নি: ${e.message}")
                }

            }, ContextCompat.getMainExecutor(this))

        } catch (e: Exception) {
            fail("Camera Error: ${e.message}")
        }
    }

    // ------------------------------------------------
    // CHUNK RECORDING
    // ------------------------------------------------

    private fun beginChunk() {
        if (stopRequested) { stopServiceNow(); return }

        val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (directory == null || (!directory.exists() && !directory.mkdirs())) {
            fail("Video Storage পাওয়া যায়নি")
            return
        }

        val file = File(directory, "PPG_${System.currentTimeMillis()}.mp4")
        recordingFile = file

        try {
            val outputOptions = FileOutputOptions.Builder(file).build()

            val pending = videoCapture!!.output
                .prepareRecording(this, outputOptions)
                .withAudioEnabled()

            activeRecording = pending.start(cameraExecutor) { event ->

                when (event) {

                    is VideoRecordEvent.Start -> {
                        handler.post {
                            isRecording = true
                            chunkCount++

                            val elapsedMin = (System.currentTimeMillis() - sessionStartTime) / 60_000
                            val remainMin  = (MAX_TOTAL_MS / 60_000) - elapsedMin

                            publishStatus(
                                "● Chunk $chunkCount চলছে | মোট বাকি: ${remainMin} মিনিট"
                            )
                            updateNotification(
                                "● Recording চলছে — Chunk $chunkCount | বাকি: ${remainMin} মিনিট"
                            )

                            // ১৫ মিনিট পর পরের chunk শুরু
                            handler.removeCallbacks(chunkTimer)
                            handler.postDelayed(chunkTimer, MAX_CHUNK_MS)

                            if (stopRequested) stopRecording()
                        }
                    }

                    is VideoRecordEvent.Finalize -> {
                        handler.post {
                            isRecording = false
                            handler.removeCallbacks(chunkTimer)
                            activeRecording = null

                            val saved = recordingFile

                            if (!event.hasError() &&
                                saved != null &&
                                saved.exists() &&
                                saved.length() > 0
                            ) {
                                // ব্যাকগ্রাউন্ডে এই chunk আপলোড করো
                                uploadChunk(saved, chunkCount)

                                if (!stopRequested) {
                                    // পরের chunk শুরু
                                    beginChunk()
                                }
                                // stopRequested হলে uploadChunk শেষে stopServiceNow() ডাকবে
                            } else {
                                if (!stopRequested) {
                                    publishStatus("Chunk $chunkCount Error। পরেরটা শুরু হচ্ছে...")
                                    beginChunk()
                                } else {
                                    stopServiceIfIdle()
                                }
                            }
                        }
                    }

                    else -> { /* VideoRecordEvent.Status — progress, ignore */ }
                }
            }

        } catch (e: Exception) {
            handler.removeCallbacks(chunkTimer)
            fail("Chunk শুরু হয়নি: ${e.message}")
        }
    }

    // বর্তমান chunk শেষ করো (Finalize event আসবে → beginChunk() ডাকবে)
    private fun rotateChunk() {
        try {
            activeRecording?.stop()
        } catch (e: Exception) {
            fail("Rotate Error: ${e.message}")
        }
    }

    private fun stopRecording() {
        handler.removeCallbacks(chunkTimer)
        stopRequested = true

        try {
            if (activeRecording != null) {
                publishStatus("Recording বন্ধ হচ্ছে...")
                updateNotification("Recording বন্ধ হচ্ছে...")
                activeRecording!!.stop()
            } else if (!isRecording) {
                stopServiceIfIdle()
            }
        } catch (e: Exception) {
            fail("Stop Error: ${e.message}")
        }
    }

    // সব আপলোড শেষ হলেই সার্ভিস বন্ধ করো
    private fun stopServiceIfIdle() {
        if (uploadsRunning <= 0 && activeRecording == null && !isRecording) {
            stopServiceNow()
        } else {
            publishStatus("আপলোড শেষ হওয়ার পর সার্ভিস বন্ধ হবে...")
        }
    }

    // ------------------------------------------------
    // UPLOAD
    // ------------------------------------------------

    private fun uploadChunk(file: File, index: Int) {
        uploadsRunning++

        uploadExecutor.execute {
            var connection: HttpURLConnection? = null

            try {
                // Gallery-তেও সেভ করো
                try { saveToGallery(file) } catch (_: Exception) {}

                handler.post {
                    publishStatus("Chunk $index আপলোড হচ্ছে (${file.length() / 1_048_576} MB)...")
                }

                connection = URL("$API/api/video").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 30_000
                connection.readTimeout    = 300_000
                connection.doInput  = true
                connection.doOutput = true
                connection.useCaches = false

                connection.setRequestProperty("Cookie",       sessionCookie)
                connection.setRequestProperty("X-Device-ID",  deviceId)
                connection.setRequestProperty("Content-Type", "video/mp4")
                connection.setRequestProperty("Accept",       "application/json")
                connection.setFixedLengthStreamingMode(file.length())

                connection.outputStream.use { out ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                        }
                        out.flush()
                    }
                }

                val code = connection.responseCode
                if (code !in 200..299) {
                    val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    throw Exception("HTTP $code: $err")
                }

                handler.post {
                    uploadsRunning--
                    publishStatus("Chunk $index সার্ভারে সেভ হয়েছে ✓")
                    if (stopRequested) stopServiceIfIdle()
                }

            } catch (e: Exception) {
                handler.post {
                    uploadsRunning--
                    publishStatus("Chunk $index Upload Error: ${e.message}। Local copy সংরক্ষিত।")
                    if (stopRequested) stopServiceIfIdle()
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    // ------------------------------------------------
    // GALLERY SAVE
    // ------------------------------------------------

    private fun saveToGallery(file: File): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/PersonalPhoneGuard")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("Gallery URI তৈরি হয়নি")

            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: throw Exception("Output stream পাওয়া যায়নি")

                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null
                )
                return uri
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "PersonalPhoneGuard"
            )
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, file.name)
            file.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dest)))
            return Uri.fromFile(dest)
        }
    }

    // ------------------------------------------------
    // NOTIFICATION
    // ------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Personal Phone Guard recording notification"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Personal Phone Guard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_pause, "Recording বন্ধ করুন", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    // ------------------------------------------------
    // HELPERS
    // ------------------------------------------------

    private fun publishStatus(status: String, path: String? = null) {
        getSharedPreferences("phone_guard", MODE_PRIVATE)
            .edit().putString("recording_status", status).apply()

        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            if (path != null) putExtra(EXTRA_VIDEO_PATH, path)
        })
    }

    private fun fail(reason: String) {
        publishStatus("Error: $reason")
        stopServiceNow()
    }

    private fun releaseCamera() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
        videoCapture   = null
    }

    private fun stopServiceNow() {
        handler.removeCallbacks(chunkTimer)
        handler.removeCallbacks(totalTimer)
        releaseCamera()
        serviceStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
