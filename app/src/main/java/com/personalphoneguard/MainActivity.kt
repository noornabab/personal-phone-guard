
package com.personalphoneguard

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        private const val API =
            "https://personal-phone-guard.support446-ff6.workers.dev"

        private const val ORIGIN =
            "https://personal-phone-guard.support446-ff6.workers.dev"

        private const val MAX_RECORDING_MS = 20 * 60 * 1000L
        private const val MAX_UPLOAD_BYTES = 80L * 1024L * 1024L
    }

    private val prefs by lazy {
        getSharedPreferences("phone_guard", MODE_PRIVATE)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var fusedLocation: com.google.android.gms.location.FusedLocationProviderClient

    private var deviceId = ""
    private var accountPassword = ""

    private var previewView: PreviewView? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recordingFile: File? = null
    private var recordingStartedAt = 0L

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null

    private var recordingTimer: Runnable? = null
    private var recording = false

    private var statusText: TextView? = null
    private var passwordInput: EditText? = null
    private var deviceListBox: LinearLayout? = null
    private var videoListBox: LinearLayout? = null
    private var locationListBox: LinearLayout? = null

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->

            if (result[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                message("Camera permission প্রয়োজন")
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->

            if (
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                uploadCurrentLocation()
            } else {
                message("Location permission দেওয়া হয়নি")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        deviceId = prefs.getString("device_id", null)
            ?: "dev-" + UUID.randomUUID().toString()

        prefs.edit().putString("device_id", deviceId).apply()

        buildRoot()

        if (getCookie().isNotBlank()) {
            showDashboard()
        } else {
            showLogin()
        }
    }

    // ------------------------------------------------
    // UI
    // ------------------------------------------------

    private fun buildRoot() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 247, 250))
            setPadding(18, 18, 18, 18)
        }

        setContentView(root)
    }

    private fun clearScreen() {
        root.removeAllViews()
        root.scrollTo(0, 0)
    }

    private fun heading(text: String) {
        val view = TextView(this).apply {
            this.text = text
            textSize = 23f
            setTextColor(Color.rgb(18, 60, 53))
            setPadding(0, 10, 0, 16)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        root.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun text(value: String, size: Float = 15f): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.rgb(35, 45, 50))
            setPadding(4, 8, 4, 8)
        }
    }

    private fun input(
        hint: String,
        isPassword: Boolean = false
    ): EditText {

        return EditText(this).apply {
            this.hint = hint
            textSize = 15f
            setSingleLine(true)

            if (isPassword) {
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
    }

    private fun button(
        label: String,
        action: () -> Unit
    ): Button {

        return Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun addView(view: View) {
        root.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
        )
    }

    private fun message(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun setStatus(value: String) {
        statusText?.text = value
    }

    // ------------------------------------------------
    // Cookie and API
    // ------------------------------------------------

    private fun getCookie(): String {
        return prefs.getString("session_cookie", "") ?: ""
    }

    private fun saveCookie(value: String) {
        prefs.edit().putString("session_cookie", value).apply()
    }

    private fun clearCookie() {
        prefs.edit().remove("session_cookie").apply()
    }

    private suspend fun api(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): JSONObject = withContext(Dispatchers.IO) {

        val connection =
            URL(API + path).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Origin", ORIGIN)

            if (getCookie().isNotBlank()) {
                connection.setRequestProperty(
                    "Cookie",
                    getCookie()
                )
            }

            extraHeaders.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val setCookie =
                connection.getHeaderField("Set-Cookie")

            if (!setCookie.isNullOrBlank()) {
                val cookiePair = setCookie.substringBefore(";")
                if (cookiePair.contains("=")) {
                    saveCookie(cookiePair)
                }
            }

            val stream =
                if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val response = stream?.bufferedReader()?.use {
                it.readText()
            } ?: "{}"

            val json = try {
                JSONObject(response)
            } catch (_: Exception) {
                JSONObject().put("raw", response)
            }

            if (connection.responseCode !in 200..299) {
                throw Exception(
                    json.optString(
                        "error",
                        "Server error: ${connection.responseCode}"
                    )
                )
            }

            json

        } finally {
            connection.disconnect()
        }
    }

    private suspend fun uploadVideo(file: File): JSONObject =
        withContext(Dispatchers.IO) {

            if (file.length() > MAX_UPLOAD_BYTES) {
                throw Exception("Video 80 MB সীমা অতিক্রম করেছে")
            }

            val connection =
                URL("$API/api/video").openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 30000
                connection.readTimeout = 120000

                connection.setRequestProperty("Origin", ORIGIN)
                connection.setRequestProperty("Cookie", getCookie())
                connection.setRequestProperty("X-Device-ID", deviceId)
                connection.setRequestProperty(
                    "Content-Type",
                    "video/mp4"
                )

                file.inputStream().use { input ->
                    connection.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }

                val stream =
                    if (connection.responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val response =
                    stream?.bufferedReader()?.use { it.readText() } ?: "{}"

                if (connection.responseCode !in 200..299) {
                    throw Exception(response)
                }

                JSONObject(response)

            } finally {
                connection.disconnect()
            }
        }

    // ------------------------------------------------
    // Login / Register
    // ------------------------------------------------

    private fun showLogin() {
        clearScreen()
        heading("Personal Phone Guard")

        addView(text("Account Login / Register"))

        val username = input("Username")
        val password = input("Password", true)

        addView(username)
        addView(password)

        passwordInput = password

        addView(
            button("Login") {
                val user = username.text.toString().trim()
                val pass = password.text.toString()

                if (user.isBlank() || pass.isBlank()) {
                    message("Username ও Password দিন")
                    return@button
                }

                accountPassword = pass
                login(user, pass)
            }
        )

        addView(
            button("Create New Account") {
                val user = username.text.toString().trim()
                val pass = password.text.toString()

                if (user.isBlank() || pass.length < 8) {
                    message("Username দিন এবং কমপক্ষে ৮ অক্ষরের Password ব্যবহার করুন")
                    return@button
                }

                register(user, pass)
            }
        )

        addView(
            text(
                "নোট: Account-এর Password নিরাপদে রাখুন। " +
                "Video Delete ও Device Deactivate করতে এটি প্রয়োজন হবে।"
            )
        )
    }

    private fun register(username: String, password: String) {
        setStatus("Registering...")

        scope.launch {
            try {
                api(
                    "/api/register",
                    "POST",
                    JSONObject()
                        .put("username", username)
                        .put("password", password)
                )

                message("Account তৈরি হয়েছে। এখন Login করুন।")
                showLogin()

            } catch (e: Exception) {
                message("Register Error: ${e.message}")
            }
        }
    }

    private fun login(username: String, password: String) {
        scope.launch {
            try {
                clearCookie()

                api(
                    "/api/login",
                    "POST",
                    JSONObject()
                        .put("username", username)
                        .put("password", password)
                )

                prefs.edit()
                    .putString("username", username)
                    .apply()

                accountPassword = password

                message("Login successful")
                showDashboard()

            } catch (e: Exception) {
                message("Login Error: ${e.message}")
            }
        }
    }

    private fun logout() {
        scope.launch {
            try {
                api("/api/logout", "POST", JSONObject())
            } catch (_: Exception) {
            }

            stopRecording()
            stopCamera()
            clearCookie()
            showLogin()
        }
    }

    // ------------------------------------------------
    // Dashboard
    // ------------------------------------------------

    private fun showDashboard() {
        clearScreen()

        heading("Security Dashboard")

        statusText = text("Account: ${prefs.getString("username", "")}")
        addView(statusText!!)

        addView(text("This Device ID:\n$deviceId"))

        addView(
            button("Register This Phone") {
                registerThisDevice()
            }
        )

        addView(
            button("Refresh Devices") {
                loadDevices()
            }
        )

        deviceListBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(deviceListBox!!)

        addView(button("Start Location Sharing") {
            requestLocation()
        })

        addView(button("View Location History") {
            loadLocations()
        })

        locationListBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(locationListBox!!)

        addView(button("Open Camera / Recording") {
            showCameraScreen()
        })

        addView(button("View Uploaded Videos") {
            loadVideos()
        })

        videoListBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(videoListBox!!)

        addView(button("Export JSON") {
            exportData("json")
        })

        addView(button("Export CSV") {
            exportData("csv")
        })

        val newPassword = input("New Password", true)
        addView(text("Change Password"))
        addView(newPassword)

        addView(button("Change Password") {
            message(
                "বর্তমান Worker.js-এ /api/password/change API নেই। " +
                "এই ফিচার চালু করতে Worker আপডেট প্রয়োজন।"
            )
        })

        addView(button("Logout") {
            logout()
        })

        loadDevices()
    }

    // ------------------------------------------------
    // Device Registration
    // ------------------------------------------------

    private fun registerThisDevice() {
        val label = android.os.Build.MANUFACTURER +
            " " + android.os.Build.MODEL

        scope.launch {
            try {
                api(
                    "/api/device/register",
                    "POST",
                    JSONObject()
                        .put("deviceId", deviceId)
                        .put("label", label)
                )

                message("এই ফোন Device হিসেবে Register হয়েছে")
                loadDevices()

            } catch (e: Exception) {
                message("Device Register Error: ${e.message}")
            }
        }
    }

    private fun loadDevices() {
        scope.launch {
            try {
                val result = api("/api/devices")
                val devices = result.optJSONArray("devices")
                    ?: JSONArray()

                deviceListBox?.removeAllViews()

                if (devices.length() == 0) {
                    deviceListBox?.addView(
                        text("কোনো Device পাওয়া যায়নি। প্রথমে Register করুন।")
                    )
                }

                for (i in 0 until devices.length()) {
                    val item = devices.getJSONObject(i)

                    val id = item.optString("id")
                    val label = item.optString("label")
                    val active = item.optInt("active", 0) == 1

                    val card = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(10, 10, 10, 10)
                        setBackgroundColor(Color.WHITE)
                    }

                    card.addView(
                        text(
                            "$label\nID: $id\n" +
                            "Status: ${if (active) "Active" else "Inactive"}"
                        )
                    )

                    if (active) {
                        card.addView(
                            button("Deactivate Device") {
                                deactivateDevice(id)
                            }
                        )
                    }

                    deviceListBox?.addView(card)
                }

            } catch (e: Exception) {
                message("Device List Error: ${e.message}")
            }
        }
    }

    private fun deactivateDevice(id: String) {
        val pass = passwordInput?.text?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: accountPassword

        if (pass.isBlank()) {
            message("Account Password প্রয়োজন")
            return
        }

        scope.launch {
            try {
                api(
                    "/api/device/deactivate",
                    "POST",
                    JSONObject()
                        .put("deviceId", id)
                        .put("password", pass)
                )

                message("Device Deactivated")
                loadDevices()

            } catch (e: Exception) {
                message("Deactivate Error: ${e.message}")
            }
        }
    }

    // ------------------------------------------------
    // Location
    // ------------------------------------------------

    private fun requestLocation() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            uploadCurrentLocation()
        }
    }

    private fun uploadCurrentLocation() {
        if (deviceId.isBlank()) return

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        message("Location নেওয়া হচ্ছে...")

        try {
            fusedLocation.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token
            ).addOnSuccessListener { location: Location? ->

                if (location == null) {
                    message("Location পাওয়া যায়নি")
                    return@addOnSuccessListener
                }

                scope.launch {
                    try {
                        api(
                            "/api/location",
                            "POST",
                            JSONObject()
                                .put("deviceId", deviceId)
                                .put("latitude", location.latitude)
                                .put("longitude", location.longitude)
                                .put("accuracy", location.accuracy)
                        )

                        message("Location সফলভাবে Upload হয়েছে")

                    } catch (e: Exception) {
                        message("Location Error: ${e.message}")
                    }
                }
            }

        } catch (e: SecurityException) {
            message("Location Permission Error")
        }
    }

    private fun loadLocations() {
        scope.launch {
            try {
                val result = api("/api/locations")
                val locations = result.optJSONArray("locations")
                    ?: JSONArray()

                locationListBox?.removeAllViews()

                for (i in 0 until locations.length()) {
                    val item = locations.getJSONObject(i)

                    locationListBox?.addView(
                        text(
                            "Device: ${item.optString("device_id")}\n" +
                            "Latitude: ${item.optString("latitude")}\n" +
                            "Longitude: ${item.optString("longitude")}\n" +
                            "Accuracy: ${item.optString("accuracy")}\n" +
                            "Time: ${item.optString("created_at")}"
                        )
                    )
                }

                if (locations.length() == 0) {
                    locationListBox?.addView(text("Location History খালি"))
                }

            } catch (e: Exception) {
                message("Location History Error: ${e.message}")
            }
        }
    }

    // ------------------------------------------------
    // Camera
    // ------------------------------------------------

    private fun showCameraScreen() {
        clearScreen()

        heading("Video Recording")

        addView(
            text(
                "Recording শুধুমাত্র তোমার Start চাপার পর শুরু হবে। " +
                "সর্বোচ্চ সময় ২০ মিনিট।"
            )
        )

        previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                480
            )
        }

        addView(previewView!!)

        addView(button("Start Camera") {
            requestCamera()
        })

        addView(button("Start Recording") {
            startRecording()
        })

        addView(button("Stop Recording") {
            stopRecording()
        })

        addView(button("Back to Dashboard") {
            stopRecording()
            stopCamera()
            showDashboard()
        })
    }

    private fun requestCamera() {
        val needed = mutableListOf<String>()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.CAMERA)
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startCamera()
        }
    }

    private fun startCamera() {
        val preview = previewView ?: return

        val providerFuture =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({

            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val previewUseCase = Preview.Builder()
                    .build()
                    .also {
                        it.surfaceProvider = preview.surfaceProvider
                    }

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(Quality.SD)
                    )
                    .build()

                val capture = VideoCapture.withOutput(recorder)

                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    capture
                )

                videoCapture = capture
                message("Camera Ready")

            } catch (e: Exception) {
                message("Camera Error: ${e.message}")
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------
    // Recording
    // ------------------------------------------------

    private fun startRecording() {
        if (recording) {
            message("Recording ইতিমধ্যে চলছে")
            return
        }

        if (videoCapture == null) {
            message("আগে Start Camera চাপুন")
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestCamera()
            return
        }

        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: filesDir

        val file = File(
            dir,
            "PPG_${System.currentTimeMillis()}.mp4"
        )

        recordingFile = file

        val output = FileOutputOptions.Builder(file).build()

        try {
            var pending: PendingRecording =
                videoCapture!!.output.prepareRecording(
                    this,
                    output
                )

            pending = pending.withAudioEnabled()

            activeRecording = pending.start(
                ContextCompat.getMainExecutor(this)
            ) { event ->

                when (event) {

                    is VideoRecordEvent.Start -> {
                        recording = true
                        recordingStartedAt =
                            System.currentTimeMillis()

                        message("Recording Started")

                        recordingTimer = Runnable {
                            if (recording) {
                                message("২০ মিনিট শেষ। Recording বন্ধ হচ্ছে।")
                                stopRecording()
                            }
                        }

                        handler.postDelayed(
                            recordingTimer!!,
                            MAX_RECORDING_MS
                        )
                    }

                    is VideoRecordEvent.Finalize -> {
                        recording = false
                        handler.removeCallbacksAndMessages(null)
                        activeRecording = null

                        if (event.hasError()) {
                            message(
                                "Recording Error: ${event.error}"
                            )
                        } else {
                            message("Recording Saved")
                            fileExistsUpload(file)
                        }
                    }
                }
            }

        } catch (e: Exception) {
            recording = false
            message("Recording Start Error: ${e.message}")
        }
    }

    private fun stopRecording() {
        if (!recording && activeRecording == null) return

        handler.removeCallbacks(recordingTimer ?: return)

        try {
            activeRecording?.stop()
        } catch (_: Exception) {
        }

        recording = false
        message("Recording Stop Requested")
    }

    private fun fileExistsUpload(file: File) {
        if (!file.exists() || file.length() == 0L) {
            message("Video file পাওয়া যায়নি")
            return
        }

        if (file.length() > MAX_UPLOAD_BYTES) {
            message("Video 80 MB-এর বেশি। Upload করা যাবে না।")
            return
        }

        scope.launch {
            try {
                message("Video Upload হচ্ছে...")

                uploadVideo(file)

                message("Video Cloudinary-তে Upload হয়েছে")

            } catch (e: Exception) {
                message("Video Upload Error: ${e.message}")
            }
        }
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }

        videoCapture = null
        cameraProvider = null
    }

    // ------------------------------------------------
    // Video History / Download / Delete
    // ------------------------------------------------

    private fun loadVideos() {
        scope.launch {
            try {
                val result = api("/api/videos")
                val videos = result.optJSONArray("videos")
                    ?: JSONArray()

                videoListBox?.removeAllViews()

                for (i in 0 until videos.length()) {
                    val item = videos.getJSONObject(i)

                    val id = item.optString("id")

                    val card = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(10, 10, 10, 10)
                        setBackgroundColor(Color.WHITE)
                    }

                    card.addView(
                        text(
                            "Video ID: $id\n" +
                            "Device: ${item.optString("device_id")}\n" +
                            "Size: ${item.optString("size_bytes")} bytes\n" +
                            "Date: ${item.optString("created_at")}"
                        )
                    )

                    card.addView(
                        button("Download Video") {
                            downloadVideo(id)
                        }
                    )

                    card.addView(
                        button("Delete Video") {
                            deleteVideo(id)
                        }
                    )

                    videoListBox?.addView(card)
                }

                if (videos.length() == 0) {
                    videoListBox?.addView(text("কোনো Video পাওয়া যায়নি"))
                }

            } catch (e: Exception) {
                message("Video List Error: ${e.message}")
            }
        }
    }

    private fun downloadVideo(id: String) {
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val connection =
                        URL("$API/api/video/$id")
                            .openConnection() as HttpURLConnection

                    try {
                        connection.requestMethod = "GET"
                        connection.setRequestProperty(
                            "Cookie",
                            getCookie()
                        )

                        if (connection.responseCode !in 200..299) {
                            throw Exception("Download failed")
                        }

                        val dir =
                            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                                ?: filesDir

                        val output = File(
                            dir,
                            "PPG_DOWNLOAD_$id.mp4"
                        )

                        connection.inputStream.use { input ->
                            output.outputStream().use { stream ->
                                input.copyTo(stream)
                            }
                        }

                        output

                    } finally {
                        connection.disconnect()
                    }
                }

                message("Download হয়েছে:\n${file.absolutePath}")

            } catch (e: Exception) {
                message("Download Error: ${e.message}")
            }
        }
    }

    private fun deleteVideo(id: String) {
        val pass = passwordInput?.text?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: accountPassword

        if (pass.isBlank()) {
            message("Password প্রয়োজন")
            return
        }

        scope.launch {
            try {
                api(
                    "/api/video/delete",
                    "POST",
                    JSONObject()
                        .put("videoId", id)
                        .put("password", pass)
                )

                message("Video Deleted")
                loadVideos()

            } catch (e: Exception) {
                message("Delete Error: ${e.message}")
            }
        }
    }

    // ------------------------------------------------
    // Export
    // ------------------------------------------------

    private fun exportData(format: String) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val connection =
                        URL("$API/api/export?format=$format")
                            .openConnection() as HttpURLConnection

                    try {
                        connection.requestMethod = "GET"
                        connection.setRequestProperty(
                            "Cookie",
                            getCookie()
                        )

                        if (connection.responseCode !in 200..299) {
                            throw Exception("Export failed")
                        }

                        connection.inputStream.bufferedReader()
                            .use { it.readText() }

                    } finally {
                        connection.disconnect()
                    }
                }

                val file = withContext(Dispatchers.IO) {
                    val dir = getExternalFilesDir(
                        Environment.DIRECTORY_DOCUMENTS
                    ) ?: filesDir

                    val extension = if (format == "csv") "csv" else "json"

                    File(
                        dir,
                        "PPG_EXPORT_${System.currentTimeMillis()}.$extension"
                    ).apply {
                        writeText(result)
                    }
                }

                message("Export তৈরি হয়েছে:\n${file.absolutePath}")

            } catch (e: Exception) {
                message("Export Error: ${e.message}")
            }
        }
    }

    // ------------------------------------------------
    // Lifecycle
    // ------------------------------------------------

    override fun onStop() {
        super.onStop()

        // অ্যাপ দৃশ্যমান না থাকলে চলমান Recording বন্ধ।
        if (recording) {
            stopRecording()
        }

        stopCamera()
    }

    override fun onDestroy() {
        stopRecording()
        stopCamera()

        cameraExecutor?.shutdown()
        scope.cancel()

        super.onDestroy()
    }
}
