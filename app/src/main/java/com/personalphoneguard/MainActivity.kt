
package com.personalphoneguard

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import android.text.InputType
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private val api =
        "https://personal-phone-guard.support446-ff6.workers.dev"

    private val prefs by lazy {
        getSharedPreferences("phone_guard", Context.MODE_PRIVATE)
    }

    private val handler = Handler(Looper.getMainLooper())

    private val bg = Color.rgb(12, 25, 38)
    private val card = Color.rgb(25, 43, 58)
    private val green = Color.rgb(50, 200, 130)
    private val white = Color.WHITE

    private lateinit var main: LinearLayout
    private var ownerToken = ""
    private var deviceToken = ""
    private var pendingCommandId = ""

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (deviceToken.isNotEmpty()) {
                deviceHeartbeat()
                getDeviceCommands()
                handler.postDelayed(this, 15000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ownerToken = prefs.getString("owner_token", "") ?: ""
        deviceToken = prefs.getString("device_token", "") ?: ""

        showHome()
    }

    override fun onResume() {
        super.onResume()

        if (deviceToken.isNotEmpty()) {
            startDevicePolling()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    // ---------------- UI HELPERS ----------------

    private fun createScreen(titleText: String) {
        val scroll = ScrollView(this)

        main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setPadding(22, 25, 22, 30)
        main.setBackgroundColor(bg)

        val title = TextView(this)
        title.text = titleText
        title.textSize = 23f
        title.setTextColor(white)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 15, 0, 25)

        main.addView(title)
        scroll.addView(main)
        setContentView(scroll)
    }

    private fun addText(
        value: String,
        size: Float = 15f,
        color: Int = white
    ) {
        val text = TextView(this)
        text.text = value
        text.textSize = size
        text.setTextColor(color)
        text.setPadding(8, 10, 8, 10)
        main.addView(text)
    }

    private fun addButton(
        label: String,
        action: () -> Unit
    ) {
        val button = Button(this)
        button.text = label
        button.setOnClickListener { action() }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 6, 0, 6)

        main.addView(button, params)
    }

    private fun addInput(
        hintText: String,
        password: Boolean = false
    ): EditText {
        val input = EditText(this)
        input.hint = hintText
        input.setTextColor(white)
        input.setHintTextColor(Color.LTGRAY)
        input.setPadding(15, 12, 15, 12)
        input.setBackgroundColor(card)

        if (password) {
            input.inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 7, 0, 7)

        main.addView(input, params)
        return input
    }

    private fun showMessage(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun deviceName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}".take(80)
    }

    // ---------------- NETWORK ----------------

    private fun apiRequest(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        token: String? = null
    ): JSONObject {

        val connection =
            URL(api + path).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            if (!token.isNullOrEmpty()) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )
            }

            if (method == "POST") {
                connection.doOutput = true

                connection.outputStream.use {
                    it.write(
                        (body ?: JSONObject())
                            .toString()
                            .toByteArray(Charsets.UTF_8)
                    )
                }
            }

            val code = connection.responseCode

            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val result = stream?.bufferedReader()?.use {
                it.readText()
            } ?: "{}"

            val json = JSONObject(result)

            if (code !in 200..299) {
                throw Exception(
                    json.optString("error", "HTTP $code")
                )
            }

            return json

        } finally {
            connection.disconnect()
        }
    }

    private fun runNetwork(
        action: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                action()
            } catch (e: Exception) {
                runOnUiThread {
                    onError(e.message ?: "Network error")
                }
            }
        }.start()
    }

    // ---------------- HOME ----------------

    private fun showHome() {
        createScreen("PERSONAL PHONE GUARD")

        addText(
            "Owner Controlled Security",
            14f,
            Color.LTGRAY
        )

        addButton("OWNER MODE") {
            showOwnerLogin()
        }

        addButton("PROTECTED DEVICE MODE") {
            showProtectedMode()
        }

        if (ownerToken.isNotEmpty()) {
            addButton("OPEN OWNER DASHBOARD") {
                loadDashboard()
            }
        }

        if (deviceToken.isNotEmpty()) {
            addButton("OPEN PROTECTED DEVICE") {
                showProtectedDashboard()
            }
        }
    }

    // ---------------- OWNER LOGIN ----------------

    private fun showOwnerLogin() {
        createScreen("OWNER LOGIN")

        val username = addInput("Username")
        val password = addInput("Password", true)

        addButton("LOGIN") {
            val user = username.text.toString().trim()
            val pass = password.text.toString()

            if (user.isEmpty() || pass.isEmpty()) {
                showMessage("Enter username and password")
                return@addButton
            }

            runNetwork({
                val result = apiRequest(
                    "/api/login",
                    "POST",
                    JSONObject()
                        .put("username", user)
                        .put("password", pass)
                )

                val token = result.optString("token")

                if (token.isEmpty()) {
                    throw Exception("Login token missing")
                }

                prefs.edit()
                    .putString("owner_token", token)
                    .apply()

                ownerToken = token

                runOnUiThread {
                    loadDashboard()
                }

            }, {
                showMessage(it)
            })
        }

        addButton("BACK") {
            showHome()
        }
    }

    // ---------------- OWNER DASHBOARD ----------------

    private fun loadDashboard() {
        if (ownerToken.isEmpty()) {
            showOwnerLogin()
            return
        }

        createScreen("OWNER DASHBOARD")

        addText("Loading devices...", 15f, green)

        runNetwork({
            val result = apiRequest(
                "/api/devices",
                "GET",
                null,
                ownerToken
            )

            runOnUiThread {
                renderDashboard(result)
            }

        }, {
            runOnUiThread {
                showMessage(it)
                showOwnerLogin()
            }
        })
    }

    private fun renderDashboard(result: JSONObject) {
        createScreen("OWNER DASHBOARD")

        addText(
            "Your Registered Devices",
            17f,
            green
        )

        val devices = result.optJSONArray("devices")
            ?: JSONArray()

        if (devices.length() == 0) {
            addText("No devices registered yet.")
        }

        for (i in 0 until devices.length()) {
            val device = devices.getJSONObject(i)

            val id = device.optString("id")
            val name = device.optString("device_name")
            val status = device.optString("status")
            val online = device.optBoolean("online")

            addText(
                "$name\n" +
                "ID: $id\n" +
                "Status: $status\n" +
                "Connection: " +
                if (online) "ONLINE" else "OFFLINE",
                14f,
                if (online) green else Color.LTGRAY
            )

            addButton("LOST MODE ON") {
                sendOwnerCommand(id, "LOST_MODE_ON")
            }

            addButton("LOST MODE OFF") {
                sendOwnerCommand(id, "LOST_MODE_OFF")
            }

            addButton("REQUEST LOCATION PERMISSION") {
                sendOwnerCommand(
                    id,
                    "LOCATION_PERMISSION_REQUEST"
                )
            }

            addButton("REQUEST CAMERA PERMISSION") {
                sendOwnerCommand(
                    id,
                    "CAMERA_PERMISSION_REQUEST"
                )
            }

            addButton("PING DEVICE") {
                sendOwnerCommand(id, "PING")
            }

            addButton("DEACTIVATE DEVICE") {
                confirmDeactivate(id)
            }

            addButton("DELETE DEVICE SERVER RECORD") {
                confirmDeleteDevice(id)
            }

            addText("------------------------------", 12f)
        }

        addButton("CREATE PAIRING CODE") {
            createPairingCode()
        }

        addButton("COMMAND HISTORY") {
            showCommandHistory()
        }

        addButton("EXPORT DEVICE DATA (JSON)") {
            exportDevices()
        }

        addButton("REFRESH") {
            loadDashboard()
        }

        addButton("CHANGE PASSWORD") {
            changePassword()
        }

        addButton("LOGOUT") {
            ownerToken = ""
            prefs.edit().remove("owner_token").apply()
            showHome()
        }
    }

    // ---------------- PAIRING CODE ----------------

    private fun createPairingCode() {
        runNetwork({
            val result = apiRequest(
                "/api/pairing/create",
                "POST",
                JSONObject(),
                ownerToken
            )

            val code = result.optString("pairing_code")

            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("DEVICE PAIRING CODE")
                    .setMessage(
                        "Code: $code\n\n" +
                        "Expires in 10 minutes.\n" +
                        "Enter this code on the protected phone."
                    )
                    .setPositiveButton("COPY") { _, _ ->
                        val clipboard =
                            getSystemService(CLIPBOARD_SERVICE)
                                as ClipboardManager

                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Pairing Code", code)
                        )

                        showMessage("Code copied")
                    }
                    .setNegativeButton("CLOSE", null)
                    .show()
            }

        }, {
            showMessage(it)
        })
    }

    // ---------------- OWNER COMMANDS ----------------

    private fun sendOwnerCommand(
        deviceId: String,
        command: String
    ) {
        runNetwork({
            val result = apiRequest(
                "/api/commands",
                "POST",
                JSONObject()
                    .put("device_id", deviceId)
                    .put("command", command),
                ownerToken
            )

            runOnUiThread {
                showMessage(
                    "Request created.\n" +
                    "Status: " +
                    result.optString("status", "pending")
                )
                loadDashboard()
            }

        }, {
            showMessage(it)
        })
    }

    private fun confirmDeactivate(deviceId: String) {
        AlertDialog.Builder(this)
            .setTitle("Deactivate device?")
            .setMessage(
                "This disables its device authentication. " +
                "It does not erase local phone data."
            )
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("DEACTIVATE") { _, _ ->
                runNetwork({
                    apiRequest(
                        "/api/devices/deactivate",
                        "POST",
                        JSONObject().put("device_id", deviceId),
                        ownerToken
                    )

                    runOnUiThread {
                        showMessage("Device deactivated")
                        loadDashboard()
                    }

                }, {
                    showMessage(it)
                })
            }
            .show()
    }

    private fun confirmDeleteDevice(deviceId: String) {
        val password = EditText(this)
        password.hint = "Owner password"
        password.inputType =
            InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD

        AlertDialog.Builder(this)
            .setTitle("Delete server record?")
            .setMessage(
                "This permanently deletes the selected device's " +
                "server record and command history."
            )
            .setView(password)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("DELETE") { _, _ ->
                val pass = password.text.toString()

                runNetwork({
                    apiRequest(
                        "/api/devices/delete",
                        "POST",
                        JSONObject()
                            .put("device_id", deviceId)
                            .put("password", pass),
                        ownerToken
                    )

                    runOnUiThread {
                        showMessage("Server record deleted")
                        loadDashboard()
                    }

                }, {
                    showMessage(it)
                })
            }
            .show()
    }

    // ---------------- COMMAND HISTORY ----------------

    private fun showCommandHistory() {
        runNetwork({
            val result = apiRequest(
                "/api/commands",
                "GET",
                null,
                ownerToken
            )

            val commands = result.optJSONArray("commands")
                ?: JSONArray()

            val text = StringBuilder()

            for (i in 0 until commands.length()) {
                val item = commands.getJSONObject(i)

                text.append(
                    item.optString("command")
                )
                text.append(" | ")
                text.append(
                    item.optString("status")
                )
                text.append("\n")
                text.append(
                    item.optString("created_at")
                )
                text.append("\n\n")
            }

            runOnUiThread {
                showMessage(
                    if (text.isEmpty()) {
                        "No command history"
                    } else {
                        text.toString()
                    }
                )
            }

        }, {
            showMessage(it)
        })
    }

    // ---------------- EXPORT ----------------

    private fun exportDevices() {
        runNetwork({
            val result = apiRequest(
                "/api/devices/export?format=json",
                "GET",
                null,
                ownerToken
            )

            val share = Intent(Intent.ACTION_SEND)
            share.type = "application/json"
            share.putExtra(
                Intent.EXTRA_TEXT,
                result.toString(2)
            )

            runOnUiThread {
                startActivity(
                    Intent.createChooser(
                        share,
                        "Export Device Data"
                    )
                )
            }

        }, {
            showMessage(it)
        })
    }

    // ---------------- PASSWORD CHANGE ----------------

    private fun changePassword() {
        val oldPassword = EditText(this)
        oldPassword.hint = "Current password"
        oldPassword.inputType =
            InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD

        val newPassword = EditText(this)
        newPassword.hint = "New password (minimum 10 characters)"
        newPassword.inputType =
            InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(20, 10, 20, 10)
        layout.addView(oldPassword)
        layout.addView(newPassword)

        AlertDialog.Builder(this)
            .setTitle("Change Owner Password")
            .setView(layout)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CHANGE") { _, _ ->

                runNetwork({
                    apiRequest(
                        "/api/password/change",
                        "POST",
                        JSONObject()
                            .put(
                                "old_password",
                                oldPassword.text.toString()
                            )
                            .put(
                                "new_password",
                                newPassword.text.toString()
                            ),
                        ownerToken
                    )

                    runOnUiThread {
                        showMessage("Password changed")
                        ownerToken = ""
                        prefs.edit()
                            .remove("owner_token")
                            .apply()
                        showOwnerLogin()
                    }

                }, {
                    showMessage(it)
                })
            }
            .show()
    }

    // ---------------- PROTECTED DEVICE MODE ----------------

    private fun showProtectedMode() {
        createScreen("PROTECTED DEVICE MODE")

        if (deviceToken.isNotEmpty()) {
            addText(
                "This device is already paired.",
                15f,
                green
            )

            addButton("OPEN DEVICE STATUS") {
                showProtectedDashboard()
            }

            addButton("UNPAIR THIS DEVICE") {
                AlertDialog.Builder(this)
                    .setMessage("Remove saved device access from this app?")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("UNPAIR") { _, _ ->
                        handler.removeCallbacks(pollRunnable)
                        deviceToken = ""
                        prefs.edit()
                            .remove("device_token")
                            .remove("device_id")
                            .apply()
                        showHome()
                    }
                    .show()
            }

            return
        }

        addText(
            "Enter the 6-digit code generated by Owner Mode."
        )

        val code = addInput("6-digit pairing code")

        addButton("PAIR THIS DEVICE") {
            val pairingCode = code.text.toString().trim()

            if (!pairingCode.matches(Regex("\\d{6}"))) {
                showMessage("Enter a valid 6-digit code")
                return@addButton
            }

            runNetwork({
                val result = apiRequest(
                    "/api/device/pair",
                    "POST",
                    JSONObject()
                        .put("code", pairingCode)
                        .put("device_name", deviceName())
                )

                val newToken = result.optString("device_token")
                val id = result.optString("device_id")

                if (newToken.isEmpty() || id.isEmpty()) {
                    throw Exception("Pairing response incomplete")
                }

                prefs.edit()
                    .putString("device_token", newToken)
                    .putString("device_id", id)
                    .apply()

                deviceToken = newToken

                runOnUiThread {
                    showProtectedDashboard()
                    startDevicePolling()
                }

            }, {
                showMessage(it)
            })
        }

        addButton("BACK") {
            showHome()
        }
    }

    private fun showProtectedDashboard() {
        createScreen("PROTECTED DEVICE")

        addText("Device: ${deviceName()}", 15f)
        addText(
            "Device ID: " +
            (prefs.getString("device_id", "") ?: ""),
            12f,
            Color.LTGRAY
        )

        addText(
            "Pairing: CONNECTED",
            16f,
            green
        )

        addText(
            "This screen must remain active for command checking. " +
            "Android may stop network activity when the app is closed.",
            13f,
            Color.LTGRAY
        )

        addButton("START REQUEST CHECKING") {
            startDevicePolling()
            showMessage("Request checking started")
        }

        addButton("STOP REQUEST CHECKING") {
            handler.removeCallbacks(pollRunnable)
            showMessage("Request checking stopped")
        }

        addButton("UNPAIR THIS DEVICE") {
            AlertDialog.Builder(this)
                .setMessage("Remove saved device access?")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("UNPAIR") { _, _ ->
                    handler.removeCallbacks(pollRunnable)
                    deviceToken = ""
                    prefs.edit()
                        .remove("device_token")
                        .remove("device_id")
                        .apply()
                    showHome()
                }
                .show()
        }

        addButton("BACK") {
            showHome()
        }
    }

    // ---------------- DEVICE NETWORK ----------------

    private fun startDevicePolling() {
        handler.removeCallbacks(pollRunnable)

        if (deviceToken.isNotEmpty()) {
            handler.post(pollRunnable)
        }
    }

    private fun deviceHeartbeat() {
        Thread {
            try {
                apiRequest(
                    "/api/device/heartbeat",
                    "POST",
                    JSONObject(),
                    deviceToken
                )
            } catch (_: Exception) {
                // Retry on next polling cycle
            }
        }.start()
    }

    private fun getDeviceCommands() {
        Thread {
            try {
                val result = apiRequest(
                    "/api/device/commands",
                    "GET",
                    null,
                    deviceToken
                )

                val commands = result.optJSONArray("commands")
                    ?: JSONArray()

                for (i in 0 until commands.length()) {
                    val item = commands.getJSONObject(i)

                    val id = item.optString("id")
                    val command = item.optString("command")

                    if (id.isNotEmpty()) {
                        runOnUiThread {
                            handleDeviceCommand(id, command)
                        }
                    }
                }

            } catch (_: Exception) {
                // Retry on next polling cycle
            }
        }.start()
    }

    // ---------------- VISIBLE COMMAND APPROVAL ----------------

    private fun handleDeviceCommand(
        commandId: String,
        command: String
    ) {
        if (pendingCommandId.isNotEmpty()) return

        pendingCommandId = commandId

        when (command) {

            "PING" -> {
                showMessage("Owner checked device connection.")
                acknowledgeCommand(commandId, "completed")
            }

            "LOST_MODE_ON" -> {
                AlertDialog.Builder(this)
                    .setTitle("Lost Mode Request")
                    .setMessage(
                        "Owner requested Lost Mode. " +
                        "Do you want to activate the visible Lost Mode screen?"
                    )
                    .setNegativeButton("REJECT") { _, _ ->
                        acknowledgeCommand(commandId, "rejected")
                    }
                    .setPositiveButton("ACCEPT") { _, _ ->
                        showLostMode()
                        acknowledgeCommand(commandId, "completed")
                    }
                    .setCancelable(false)
                    .show()
            }

            "LOST_MODE_OFF" -> {
                AlertDialog.Builder(this)
                    .setTitle("Lost Mode Request")
                    .setMessage("Owner requested to turn Lost Mode off.")
                    .setNegativeButton("REJECT") { _, _ ->
                        acknowledgeCommand(commandId, "rejected")
                    }
                    .setPositiveButton("ACCEPT") { _, _ ->
                        showProtectedDashboard()
                        acknowledgeCommand(commandId, "completed")
                    }
                    .setCancelable(false)
                    .show()
            }

            "LOCATION_PERMISSION_REQUEST" -> {
                askPermission(
                    commandId,
                    "Location",
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                )
            }

            "CAMERA_PERMISSION_REQUEST" -> {
                askPermission(
                    commandId,
                    "Camera",
                    android.Manifest.permission.CAMERA
                )
            }

            else -> {
                acknowledgeCommand(commandId, "rejected")
            }
        }
    }

    private fun showLostMode() {
        createScreen("LOST MODE")

        addText(
            "This device has entered Lost Mode.",
            20f,
            green
        )

        addText(
            "Contact the device owner if this device is not yours."
        )

        addButton("RETURN TO DEVICE") {
            showProtectedDashboard()
        }
    }

    private fun askPermission(
        commandId: String,
        label: String,
        permission: String
    ) {
        AlertDialog.Builder(this)
            .setTitle("$label Permission Request")
            .setMessage(
                "The owner has requested $label permission. " +
                "You can accept or reject. Android will show its own permission prompt."
            )
            .setNegativeButton("REJECT") { _, _ ->
                acknowledgeCommand(commandId, "rejected")
            }
            .setPositiveButton("CONTINUE") { _, _ ->

                if (
                    checkSelfPermission(permission) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    acknowledgeCommand(commandId, "completed")
                } else {
                    pendingCommandId = commandId

                    requestPermissions(
                        arrayOf(permission),
                        if (label == "Camera") 2001 else 2002
                    )
                }
            }
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 2001 || requestCode == 2002) {
            val granted =
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED

            val commandId = pendingCommandId

            if (commandId.isNotEmpty()) {
                acknowledgeCommand(
                    commandId,
                    if (granted) "completed" else "rejected"
                )
            }

            showMessage(
                if (granted) {
                    "Permission granted by device user."
                } else {
                    "Permission not granted."
                }
            )
        }
    }

    private fun acknowledgeCommand(
        commandId: String,
        status: String
    ) {
        Thread {
            try {
                apiRequest(
                    "/api/device/commands/ack",
                    "POST",
                    JSONObject()
                        .put("command_id", commandId)
                        .put("status", status),
                    deviceToken
                )
            } catch (_: Exception) {
                // The request remains pending if acknowledgement fails
            }

            runOnUiThread {
                if (pendingCommandId == commandId) {
                    pendingCommandId = ""
                }
            }
        }.start()
    }
}
