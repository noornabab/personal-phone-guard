
package com.personalphoneguard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
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

        private const val MAX_UPLOAD_BYTES =
            80L * 1024L * 1024L
    }

    private val prefs by lazy {
        getSharedPreferences("phone_guard", MODE_PRIVATE)
    }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var root: LinearLayout

    private lateinit var fusedLocation:
        FusedLocationProviderClient

    private var deviceId = ""
    private var accountPassword = ""

    private var statusText: TextView? = null
    private var deviceListBox: LinearLayout? = null
    private var videoListBox: LinearLayout? = null
    private var locationListBox: LinearLayout? = null
    private var locationStatus: TextView? = null
    private var recordingStatus: TextView? = null

    private var locationSharing = false
    private var deviceRegistered = false

    private var recordingReceiverRegistered = false

    // ------------------------------------------------
    // RECORDING STATUS RECEIVER
    // ------------------------------------------------

    private val recordingStatusReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (intent?.action != RecordingService.ACTION_STATUS) {
                    return
                }

                val status =
                    intent.getStringExtra(
                        RecordingService.EXTRA_STATUS
                    ) ?: return

                recordingStatus?.text = status
            }
        }

    private fun registerRecordingReceiver() {

        if (recordingReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(RecordingService.ACTION_STATUS)
        }

        ContextCompat.registerReceiver(
            this,
            recordingStatusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        recordingReceiverRegistered = true
    }

    private fun unregisterRecordingReceiver() {

        if (!recordingReceiverRegistered) return

        try {
            unregisterReceiver(recordingStatusReceiver)
        } catch (_: Exception) {
        }

        recordingReceiverRegistered = false
    }

    private fun getSavedRecordingStatus(): String {

        return prefs.getString(
            "recording_status",
            "Not Recording"
        ) ?: "Not Recording"
    }

    // ------------------------------------------------
    // PERMISSIONS
    // ------------------------------------------------

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {

            if (
                hasPermission(Manifest.permission.CAMERA) &&
                hasPermission(Manifest.permission.RECORD_AUDIO)
            ) {
                requestNotificationPermission()
            } else {
                message("Camera ও Microphone permission প্রয়োজন")
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            startRecordingService()
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

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ------------------------------------------------
    // LOCATION CALLBACK
    // ------------------------------------------------

    private val locationCallback = object : LocationCallback() {

        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                sendLocation(location)
            }
        }
    }

    // ------------------------------------------------
    // ACTIVITY
    // ------------------------------------------------

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

    override fun onStart() {
        super.onStart()
        registerRecordingReceiver()
    }

    // ------------------------------------------------
    // UI HELPERS
    // ------------------------------------------------

    private fun buildRoot() {

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 247, 250))
            setPadding(18, 18, 18, 18)
        }

        val scroll = ScrollView(this)

        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

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
            setTypeface(
                null,
                android.graphics.Typeface.BOLD
            )
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
            setOnClickListener {
                action()
            }
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
        Toast.makeText(
            this,
            value,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun setStatus(value: String) {
        statusText?.text = value
    }

    // ------------------------------------------------
    // COOKIE
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
                stream?.bufferedReader()?.use {
                    it.readText()
                } ?: "{}"

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
    // LOGIN / REGISTER / RESTORE
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
                message(
                    "Username দিন এবং Password কমপক্ষে ১০ অক্ষরের করুন"
                )
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

    private fun register(
        username: String,
        password: String
    ) {

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

    private fun login(
        username: String,
        password: String
    ) {

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
                    throw Exception(
                        "Server Session Cookie পাওয়া যায়নি"
                    )
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
                        ?.optString("username") ?: ""

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

        stopLocationSharing()

        scope.launch {
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
    // DASHBOARD
    // ------------------------------------------------

    private fun showDashboard() {

        clearScreen()

        heading("Security Dashboard")

        statusText = text(
            "Account: ${prefs.getString("username", "")}"
        )
        addView(statusText!!)

        addView(
            text(
                "This Device ID:\n$deviceId",
                12f
            )
        )

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

        addView(button("Open Recording Screen") {
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

        registerThisDevice()
        loadDevices()
    }

    // ------------------------------------------------
    // DEVICE
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

                message(
                    "Device Register Error: ${e.message}"
                )
            }
        }
    }

    private fun loadDevices() {

        scope.launch {
            try {

                val result = api("/api/devices")

                val devices =
                    result.optJSONArray("devices")
                        ?: JSONArray()

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

                    val active =
                        item.optInt("active", 0) == 1

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

        if (accountPassword.isBlank()) {
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
                        .put("password", accountPassword)
                )

                if (id == deviceId) {
                    deviceRegistered = false
                    stopLocationSharing()
                }

                message("Device Deactivated")
                loadDevices()

            } catch (e: Exception) {
                message("Deactivate Error: ${e.message}")
            }
        }
    }

    // ------------------------------------------------
    // LOCATION
    // ------------------------------------------------

    private fun requestLocation() {

        if (!deviceRegistered) {
            message("আগে Device Register করুন")
            return
        }

        val fine =
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

        val coarse =
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (!fine && !coarse) {

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
                    "Location Sharing ON\nLast Update: " +
                    java.text.SimpleDateFormat(
                        "HH:mm:ss",
                        java.util.Locale.getDefault()
                    ).format(java.util.Date())

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
                    result.optJSONArray("locations")
                        ?: JSONArray()

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
    // RECORDING SCREEN
    // ------------------------------------------------

    private fun showCameraScreen() {

        clearScreen()

        heading("Video + Audio Recording")

        addView(
            text(
                "রেকর্ডিং ব্যবহারকারীর Start চাপার পর চালু হবে। " +
                "সর্বোচ্চ ২০ মিনিট। অ্যাপ মিনিমাইজ করলে " +
                "দৃশ্যমান Notification থাকবে।"
            )
        )

        recordingStatus = text(
            getSavedRecordingStatus()
        )

        addView(recordingStatus!!)

        addView(button("Start Recording") {
            requestCamera()
        })

        addView(button("Stop Recording") {
            stopRecordingService()
        })

        addView(button("Back to Dashboard") {
            showDashboard()
        })
    }

    private fun requestCamera() {

        if (!deviceRegistered) {
            message("আগে Device Register করুন")
            return
        }

        val needed = mutableListOf<String>()

        if (!hasPermission(Manifest.permission.CAMERA)) {
            needed.add(Manifest.permission.CAMERA)
        }

        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
            return
        }

        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {

        if (
            Build.VERSION.SDK_INT >= 33 &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            startRecordingService()
        }
    }

    private fun startRecordingService() {

        if (!deviceRegistered) {
            message("Device Register সফল হয়নি")
            return
        }

        if (getCookie().isBlank()) {
            message("Login session পাওয়া যায়নি")
            return
        }

        try {

            val serviceIntent = Intent(
                this,
                RecordingService::class.java
            ).apply {

                action = RecordingService.ACTION_START

                putExtra(
                    RecordingService.EXTRA_COOKIE,
                    getCookie()
                )

                putExtra(
                    RecordingService.EXTRA_DEVICE,
                    deviceId
                )
            }

            prefs.edit()
                .putString(
                    "recording_status",
                    "Recording Service Starting..."
                )
                .apply()

            recordingStatus?.text =
                "Recording Service Starting..."

            ContextCompat.startForegroundService(
                this,
                serviceIntent
            )

            message(
                "Recording Service চালু করার অনুরোধ পাঠানো হয়েছে"
            )

        } catch (e: Exception) {

            val error = "Service Error: ${e.message}"

            recordingStatus?.text = error

            prefs.edit()
                .putString("recording_status", error)
                .apply()

            message(error)
        }
    }

    private fun stopRecordingService() {

        try {

            val intent = Intent(
                this,
                RecordingService::class.java
            ).apply {
                action = RecordingService.ACTION_STOP
            }

            recordingStatus?.text =
                "Recording বন্ধ করার অনুরোধ পাঠানো হয়েছে..."

            startService(intent)

        } catch (e: Exception) {

            message("Stop Error: ${e.message}")
        }
    }

    // ------------------------------------------------
    // VIDEO LIST
    // ------------------------------------------------

    private fun loadVideos() {

        scope.launch {
            try {

                val result = api("/api/videos")

                val videos =
                    result.optJSONArray("videos")
                        ?: JSONArray()

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

    // ------------------------------------------------
    // DOWNLOAD / SAVE FILE
    // ------------------------------------------------

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
            ) ?: throw Exception(
                "Downloads folder-এ ফাইল তৈরি হয়নি"
            )

            try {

                val output =
                    contentResolver.openOutputStream(uri)
                        ?: throw Exception(
                            "Output Stream পাওয়া যায়নি"
                        )

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

            val file = java.io.File(directory, name)

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

                            throw Exception(
                                error ?: "Download failed"
                            )
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

                message(
                    "Video Downloads-এ Save হয়েছে:\n$saved"
                )

            } catch (e: Exception) {
                message("Download Error: ${e.message}")
            }
        }
    }

    // ------------------------------------------------
    // DELETE VIDEO
    // ------------------------------------------------

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
    // EXPORT JSON / CSV
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

                            throw Exception(
                                error ?: "Export failed"
                            )
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

                message(
                    "Export Downloads-এ Save হয়েছে:\n$saved"
                )

            } catch (e: Exception) {
                message("Export Error: ${e.message}")
            }
        }
    }

    // ------------------------------------------------
    // LIFECYCLE
    // ------------------------------------------------

    override fun onStop() {
        super.onStop()

        unregisterRecordingReceiver()

        // Location sharing Activity-নির্ভর।
        // RecordingService এখানে বন্ধ করা হচ্ছে না।
        if (locationSharing) {
            stopLocationSharing()
        }
    }

    override fun onDestroy() {

        unregisterRecordingReceiver()

        stopLocationSharing()

        // চলমান RecordingService বন্ধ করা হচ্ছে না।
        scope.cancel()

        super.onDestroy()
    }
}
