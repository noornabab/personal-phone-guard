
package com.personalphoneguard

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Build
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
import androidx.camera.core.Preview
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
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : ComponentActivity() {

    companion object {
        private const val API =
            "https://allinoneprotection.support446-ff6.workers.dev"

        private const val ORIGIN =
            "https://allinoneprotection.support446-ff6.workers.dev"

        private const val MAX_RECORDING_MS = 20 * 60 * 1000L
        private const val MAX_UPLOAD_BYTES = 80L * 1024L * 1024L
    }

    private val prefs by lazy {
        getSharedPreferences("phone_guard", MODE_PRIVATE)
    }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var fusedLocation:
        com.google.android.gms.location.FusedLocationProviderClient

    private var deviceId = ""
    private var accountPassword = ""

    private var statusText: TextView? = null
    private var deviceListBox: LinearLayout? = null
    private var videoListBox: LinearLayout? = null
    private var locationListBox: LinearLayout? = null
    private var locationStatus: TextView? = null
    private var recordingStatus: TextView? = null

    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recordingFile: File? = null
    private var recordingTimer: Runnable? = null
    private var recording = false

    private var locationSharing = false
    private var deviceRegistered = false

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->

            val cameraGranted =
                result[Manifest.permission.CAMERA] == true

            val audioGranted =
                result[Manifest.permission.RECORD_AUDIO] == true

            if (cameraGranted && audioGranted) {
                startCamera()
            } else {
                message("Camera ও Microphone permission প্রয়োজন")
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->

            val granted =
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (granted) {
                startLocationUpdates()
            } else {
                message("Location permission দেওয়া হয়নি")
            }
        }

    private val locationCallback = object : LocationCallback() {

        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                sendLocation(location)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fusedLocation =
            LocationServices.getFusedLocationProviderClient(this)

        deviceId = prefs.getString("device_id", null)
            ?: "dev-" + UUID.randomUUID().toString()

        prefs.edit()
            .putString("device_id", deviceId)
            .apply()

        buildRoot()

        if (getCookie().isBlank()) {
            showLogin()
        } else {
            restoreSession()
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

        val scroll = ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        setContentView(scroll)
    }

    private fun clearScreen() {
        root.removeAllViews()
        root.scrollTo(0, 0)
    }

    private fun heading(value: String) {
        val view = TextView(this).apply {
            text = value
            textSize = 23f
            setTextColor(Color.rgb(18, 60, 53))
            setPadding(0, 12, 0, 18)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        addView(view)
    }

    private fun text(
        value: String,
        size: Float = 15f
    ): TextView {

        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.rgb(35, 45, 50))
            setPadding(4, 8, 4, 8)
        }
    }

    private fun input(
        hint: String,
        password: Boolean = false
    ): EditText {

        return EditText(this).apply {
            this.hint = hint
            textSize = 15f
            setSingleLine(true)

            if (password) {
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

    private fun message(value: String) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    }

    private fun setStatus(value: String) {
        statusText?.text = value
    }

    // ------------------------------------------------
    // Cookie
    // ------------------------------------------------

    private fun getCookie(): String {
        return prefs.getString("session_cookie", "") ?: ""
    }

    private fun saveCookie(value: String) {
        prefs.edit()
            .putString("session_cookie", value)
            .apply()
    }

    private fun clearCookie() {
        prefs.edit()
            .remove("session_cookie")
            .apply()
    }

    // ------------------------------------------------
    // API
    // ------------------------------------------------

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

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.setRequestProperty(
                "Origin",
                ORIGIN
            )

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
                    it.write(
                        body.toString().toByteArray(Charsets.UTF_8)
                    )
                }
            }

            val setCookie =
                connection.getHeaderField("Set-Cookie")

            if (!setCookie.isNullOrBlank()) {
                val pair = setCookie.substringBefore(";")

                if (pair.contains("=")) {
                    saveCookie(pair)
                }
            }

            val code = connection.responseCode

            val stream =
                if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val response =
                stream?.bufferedReader()?.use { it.readText() }
                    ?: "{}"

            val json = try {
                JSONObject(response)
            } catch (_: Exception) {
                JSONObject().put("raw", response)
            }

            if (code !in 200..299) {
                throw Exception(
                    json.optString(
                        "error",
                        "Server Error: $code"
                    )
                )
            }

            json

        } finally {
            connection.disconnect()
        }
    }

    // ------------------------------------------------
    // Login / Register / Restore
    // ------------------------------------------------

    private fun showLogin() {
        clearScreen()

        heading("Personal Phone Guard")

        addView(text("Login / Register"))

        val username = input("Username")
        val password = input("Password", true)

        addView(username)
        addView(password)

        addView(button("Login") {

            val user = username.text.toString().trim()
            val pass = password.text.toString()

            if (user.isBlank() || pass.isBlank()) {
                message("Username ও Password দিন")
                return@button
            }

            login(user, pass)
        })

        addView(button("Create New Account") {

            val user = username.text.toString().trim()
            val pass = password.text.toString()

            if (user.isBlank() || pass.length < 10) {
                message("Username দিন এবং Password কমপক্ষে ১০ অক্ষরের করুন")
                return@button
            }

            register(user, pass)
        })

        addView(
            text(
                "Account Password কমপক্ষে ১০ অক্ষরের হতে হবে।",
                13f
            )
        )
    }

    private fun register(username: String, password: String) {

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

                if (getCookie().isBlank()) {
                    throw Exception("Server Session Cookie পাওয়া যায়নি")
                }

                message("Login Successful")
                showDashboard()

            } catch (e: Exception) {
                clearCookie()
                message("Login Error: ${e.message}")
            }
        }
    }

    private fun restoreSession() {

        scope.launch {
            try {
                val result = api("/api/me")
                val username =
                    result.optJSONObject("user")
                        ?.optString("username")
                        ?: ""

                prefs.edit()
                    .putString("username", username)
                    .apply()

                showDashboard()

            } catch (_: Exception) {
                clearCookie()
                showLogin()
            }
        }
    }

    private fun logout() {

        scope.launch {
            stopLocationSharing()
            stopRecording()
            stopCamera()

            try {
                api("/api/logout", "POST", JSONObject())
            } catch (_: Exception) {
            }

            clearCookie()
            accountPassword = ""
            deviceRegistered = false

            showLogin()
        }
    }

    // ------------------------------------------------
    // Dashboard
    // ------------------------------------------------

    private fun showDashboard() {

        clearScreen()

        heading("Security Dashboard")

        statusText = text(
            "Account: ${prefs.getString("username", "")}"
        )
        addView(statusText!!)

        addView(text("This Device ID:\n$deviceId", 12f))

        addView(button("Register / Refresh This Device") {
            registerThisDevice()
        })

        addView(button("Refresh Devices") {
            loadDevices()
        })

        deviceListBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(deviceListBox!!)

        addView(text("LOCATION SHARING"))

        locationStatus = text("Location sharing OFF")
        addView(locationStatus!!)

        addView(button("Start Location Sharing") {
            requestLocation()
        })

        addView(button("Stop Location Sharing") {
            stopLocationSharing()
        })

        addView(button("View Location History") {
            loadLocations()
        })

        locationListBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(locationListBox!!)

        addView(text("VIDEO + AUDIO"))

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

        addView(text("EXPORT DATA"))

        addView(button("Export JSON") {
            exportData("json")
        })

        addView(button("Export CSV") {
            exportData("csv")
        })

        addView(button("Logout") {
            logout()
        })

        // Website-এর মতো Login-এর পর Device Register।
        registerThisDevice()
        loadDevices()
    }

    // ------------------------------------------------
    // Device
    // ------------------------------------------------

    private fun registerThisDevice() {

        val label =
            "${Build.MANUFACTURER} ${Build.MODEL}"

        scope.launch {
            try {
                api(
                    "/api/device/register",
                    "POST",
                    JSONObject()
                        .put("deviceId", deviceId)
                        .put("label", label)
                )

                deviceRegistered = true
                setStatus("This device is active.")
                message("Device Registered")

                loadDevices()

            } catch (e: Exception) {
                deviceRegistered = false
                setStatus("Device: ${e.message}")
                message("Device Register Error: ${e.message}")
            }
        }
    }

    private fun loadDevices() {

        scope.launch {
            try {
                val result = api("/api/devices")

                val devices =
                    result.optJSONArray("devices") ?: JSONArray()

                deviceListBox?.removeAllViews()

                if (devices.length() == 0) {
                    deviceListBox?.addView(
                        text("কোনো Device পাওয়া যায়নি")
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
                            "Status: ${if (active) "Active" else "Deactivated"}\n" +
                            "Last Seen: ${item.optString("last_seen")}"
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

        val pass = accountPassword

        if (pass.isBlank()) {
            message("আবার Login করে চেষ্টা করুন")
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

                if (id == deviceId) {
                    deviceRegistered = false
                    stopLocationSharing()
                    stopRecording()
                }

                message("Device Deactivated")
                loadDevices()

            } catch (e: Exception) {
                message("Deactivate Error: ${e.message}")
            }
        }
    }

    // ------------------------------------------------
    // Location Start / Stop
    // ------------------------------------------------

    private fun requestLocation() {

        if (!deviceRegistered) {
            message("আগে Device Register সফল করুন")
            return
        }

        val fineGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {

            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )

        } else {
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {

        if (locationSharing) {
            message("Location Sharing ইতিমধ্যে চালু আছে")
            return
        }

        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            message("Location Permission প্রয়োজন")
            return
        }

        try {
            val request =
                LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    30000L
                )
                    .setMinUpdateIntervalMillis(10000L)
                    .build()

            fusedLocation.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )

            locationSharing = true

            locationStatus?.text =
                "Location Sharing ON"

            message("Location Sharing Started")

        } catch (e: SecurityException) {
            message("Location Error: ${e.message}")
        }
    }

    private fun sendLocation(location: Location) {

        if (!locationSharing || !deviceRegistered) return

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

                locationStatus?.text =
                    "Location Sharing ON\n" +
                    "Last Update: ${java.text.SimpleDateFormat(
                        "HH:mm:ss",
                        java.util.Locale.getDefault()
                    ).format(java.util.Date())}"

            } catch (e: Exception) {
                locationStatus?.text =
                    "Location Upload Error: ${e.message}"

                stopLocationSharing()
            }
        }
    }

    private fun stopLocationSharing() {

        try {
            fusedLocation.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {
        }

        locationSharing = false

        locationStatus?.text =
            "Location Sharing OFF"
    }

    private fun loadLocations() {

        scope.launch {
            try {
                val result = api("/api/locations")

                val locations =
                    result.optJSONArray("locations") ?: JSONArray()

                locationListBox?.removeAllViews()

                if (locations.length() == 0) {
                    locationListBox?.addView(
                        text("Location History খালি")
                    )
                }

                for (i in 0 until locations.length()) {

                    val item = locations.getJSONObject(i)

                    val latitude =
                        item.optDouble("latitude")

                    val longitude =
                        item.optDouble("longitude")

                    val card = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(10, 10, 10, 10)
                        setBackgroundColor(Color.WHITE)
                    }

                    card.addView(
                        text(
                            "Device: ${item.optString("device_id")}\n" +
                            "Latitude: $latitude\n" +
                            "Longitude: $longitude\n" +
                            "Accuracy: ${item.optString("accuracy")}\n" +
                            "Time: ${item.optString("created_at")}"
                        )
                    )

                    card.addView(
                        button("Open in Google Maps") {
                            val uri = Uri.parse(
                                "https://www.google.com/maps?q=$latitude,$longitude"
                            )

                            startActivity(
                                Intent(Intent.ACTION_VIEW, uri)
                            )
                        }
                    )

                    locationListBox?.addView(card)
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

        heading("Video + Audio Recording")

        addView(
            text(
                "Recording দৃশ্যমানভাবে চালু হবে। " +
                "সর্বোচ্চ ২০ মিনিট। অ্যাপ ব্যাকগ্রাউন্ডে গেলে বন্ধ হবে।"
            )
        )

        recordingStatus = text("Not Recording")
        addView(recordingStatus!!)

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

        addView(button("Stop & Save") {
            stopRecording()
        })

        addView(button("Back to Dashboard") {
            stopRecording()
            stopCamera()
            showDashboard()
        })
    }

    private fun requestCamera() {

        if (!deviceRegistered) {
            message("আগে Device Register করুন")
            return
        }

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

                val previewUseCase =
                    Preview.Builder().build().also {
                        it.surfaceProvider = preview.surfaceProvider
                    }

                val recorder =
                    Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.from(Quality.SD)
                        )
                        .build()

                val capture =
                    VideoCapture.withOutput(recorder)

                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    capture
                )

                videoCapture = capture

                recordingStatus?.text = "Camera Ready"
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

        if (recording || activeRecording != null) {
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

        val directory =
            getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: filesDir

        val file = File(
            directory,
            "PPG_${System.currentTimeMillis()}.mp4"
        )

        recordingFile = file

        try {
            var pending: PendingRecording =
                videoCapture!!.output.prepareRecording(
                    this,
                    FileOutputOptions.Builder(file).build()
                )

            pending = pending.withAudioEnabled()

            activeRecording =
                pending.start(
                    ContextCompat.getMainExecutor(this)
                ) { event ->

                    when (event) {

                        is VideoRecordEvent.Start -> {

                            recording = true

                            recordingStatus?.text =
                                "● RECORDING VIDEO + AUDIO"

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

                            recordingTimer?.let {
                                handler.removeCallbacks(it)
                            }
                            recordingTimer = null

                            activeRecording = null

                            if (event.hasError()) {

                                recordingStatus?.text =
                                    "Recording Error: ${event.error}"

                                message(
                                    "Recording Error: ${event.error}"
                                )

                            } else {

                                recordingStatus?.text =
                                    "Recording Saved. Uploading..."

                                message("Recording Saved")

                                fileExistsUpload(file)
                            }
                        }
                    }
                }

        } catch (e: Exception) {
            recording = false
            activeRecording = null
            message("Recording Start Error: ${e.message}")
        }
    }

    private fun stopRecording() {

        recordingTimer?.let {
            handler.removeCallbacks(it)
        }
        recordingTimer = null

        val current = activeRecording

        if (current == null) {
            recording = false
            return
        }

        try {
            current.stop()
            recording = false

            recordingStatus?.text =
                "Recording Stop Requested"

        } catch (e: Exception) {
            message("Stop Recording Error: ${e.message}")
        }
    }

    private fun fileExistsUpload(file: File) {

        if (!file.exists() || file.length() == 0L) {
            message("Video File পাওয়া যায়নি")
            return
        }

        if (file.length() > MAX_UPLOAD_BYTES) {
            message("Video 80 MB-এর বেশি। Upload করা যাবে না।")
            return
        }

        scope.launch {
            try {
                recordingStatus?.text = "Uploading Video..."

                uploadVideo(file)

                recordingStatus?.text = "Video Uploaded Successfully"

                message("Video Cloudinary-তে Upload হয়েছে")

                file.delete()

                loadVideos()

            } catch (e: Exception) {

                recordingStatus?.text =
                    "Upload Failed: ${e.message}"

                message("Video Upload Error: ${e.message}")
            }
        }
    }

    private suspend fun uploadVideo(file: File): JSONObject =
        withContext(Dispatchers.IO) {

            if (file.length() > MAX_UPLOAD_BYTES) {
                throw Exception("Video 80 MB সীমা অতিক্রম করেছে")
            }

            val connection =
                URL("$API/api/video")
                    .openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 30000
                connection.readTimeout = 180000

                connection.setRequestProperty("Origin", ORIGIN)
                connection.setRequestProperty("Cookie", getCookie())
                connection.setRequestProperty("X-Device-ID", deviceId)
                connection.setRequestProperty("Content-Type", "video/mp4")

                connection.setFixedLengthStreamingMode(file.length())

                file.inputStream().use { input ->
                    connection.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }

                val code = connection.responseCode

                val stream =
                    if (code in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val response =
                    stream?.bufferedReader()?.use { it.readText() }
                        ?: "{}"

                val json = JSONObject(response)

                if (code !in 200..299) {
                    throw Exception(
                        json.optString("error", "Upload failed: $code")
                    )
                }

                json

            } finally {
                connection.disconnect()
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
    // Video List / Download / Delete
    // ------------------------------------------------

    private fun loadVideos() {

        scope.launch {
            try {
                val result = api("/api/videos")

                val videos =
                    result.optJSONArray("videos") ?: JSONArray()

                videoListBox?.removeAllViews()

                if (videos.length() == 0) {
                    videoListBox?.addView(
                        text("কোনো Video পাওয়া যায়নি")
                    )
                }

                for (i in 0 until videos.length()) {

                    val item = videos.getJSONObject(i)

                    val id = item.optString("id")
                    val size = item.optLong("size_bytes")

                    val card = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(10, 10, 10, 10)
                        setBackgroundColor(Color.WHITE)
                    }

                    card.addView(
                        text(
                            "Video ID: $id\n" +
                            "Device: ${item.optString("device_id")}\n" +
                            "Size: ${"%.2f".format(size / 1048576.0)} MB\n" +
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

            } catch (e: Exception) {
                message("Video List Error: ${e.message}")
            }
        }
    }

    private suspend fun saveToDownloads(
        name: String,
        mime: String,
        writeFile: (OutputStream) -> Unit
    ): String = withContext(Dispatchers.IO) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw Exception("Downloads folder-এ ফাইল তৈরি হয়নি")

            try {
                val output = contentResolver.openOutputStream(uri)
                    ?: throw Exception("Output Stream পাওয়া যায়নি")

                output.use {
                    writeFile(it)
                }

                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }

                contentResolver.update(uri, done, null, null)

                uri.toString()

            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }

        } else {

            val directory =
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: filesDir

            val file = File(directory, name)

            file.outputStream().use {
                writeFile(it)
            }

            file.absolutePath
        }
    }

    private fun downloadVideo(id: String) {

        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {

                    val connection =
                        URL("$API/api/video/$id")
                            .openConnection() as HttpURLConnection

                    try {
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 20000
                        connection.readTimeout = 120000

                        connection.setRequestProperty(
                            "Cookie",
                            getCookie()
                        )

                        if (connection.responseCode !in 200..299) {
                            val error =
                                connection.errorStream
                                    ?.bufferedReader()
                                    ?.use { it.readText() }

                            throw Exception(error ?: "Download failed")
                        }

                        saveToDownloads(
                            "PPG_VIDEO_$id.mp4",
                            "video/mp4"
                        ) { output ->

                            connection.inputStream.use { input ->
                                input.copyTo(output)
                            }
                        }

                    } finally {
                        connection.disconnect()
                    }
                }

                message("Video Downloads-এ Save হয়েছে:\n$saved")

            } catch (e: Exception) {
                message("Download Error: ${e.message}")
            }
        }
    }

    private fun deleteVideo(id: String) {

        val password = accountPassword

        if (password.isBlank()) {
            message("আবার Login করুন")
            return
        }

        scope.launch {
            try {
                api(
                    "/api/video/delete",
                    "POST",
                    JSONObject()
                        .put("videoId", id)
                        .put("password", password)
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
                val saved = withContext(Dispatchers.IO) {

                    val connection =
                        URL("$API/api/export?format=$format")
                            .openConnection() as HttpURLConnection

                    try {
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 20000
                        connection.readTimeout = 60000

                        connection.setRequestProperty(
                            "Cookie",
                            getCookie()
                        )

                        if (connection.responseCode !in 200..299) {
                            val error =
                                connection.errorStream
                                    ?.bufferedReader()
                                    ?.use { it.readText() }

                            throw Exception(error ?: "Export failed")
                        }

                        val extension =
                            if (format == "csv") "csv" else "json"

                        val mime =
                            if (format == "csv") {
                                "text/csv"
                            } else {
                                "application/json"
                            }

                        saveToDownloads(
                            "PPG_EXPORT_${System.currentTimeMillis()}.$extension",
                            mime
                        ) { output ->

                            connection.inputStream.use { input ->
                                input.copyTo(output)
                            }
                        }

                    } finally {
                        connection.disconnect()
                    }
                }

                message("Export Downloads-এ Save হয়েছে:\n$saved")

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

        // Website-এর মতো অ্যাপ দৃশ্যমান না থাকলে
        // চলমান Location ও Recording বন্ধ হবে।
        if (locationSharing) {
            stopLocationSharing()
        }

        if (recording || activeRecording != null) {
            stopRecording()
        }

        stopCamera()
    }

    override fun onDestroy() {

        stopLocationSharing()
        stopRecording()
        stopCamera()

        handler.removeCallbacksAndMessages(null)

        scope.cancel()

        super.onDestroy()
    }
}
