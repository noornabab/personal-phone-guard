
package com.personalphoneguard

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import android.text.InputType
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private val api = "https://personal-phone-guard.support446-ff6.workers.dev"

    private val bg = Color.rgb(12, 25, 38)
    private val card = Color.rgb(25, 43, 58)
    private val green = Color.rgb(50, 200, 130)

    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var loginButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setPadding(24, 35, 24, 30)
        main.setBackgroundColor(bg)

        val title = TextView(this)
        title.text = "PERSONAL PHONE GUARD"
        title.textSize = 24f
        title.setTextColor(Color.WHITE)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 20, 0, 10)

        val subtitle = TextView(this)
        subtitle.text = "Owner Controlled Security"
        subtitle.textSize = 14f
        subtitle.setTextColor(Color.LTGRAY)
        subtitle.gravity = Gravity.CENTER

        main.addView(title)
        main.addView(subtitle)

        val heading = TextView(this)
        heading.text = "\nOWNER LOGIN"
        heading.textSize = 19f
        heading.setTextColor(green)
        heading.setPadding(0, 30, 0, 15)
        main.addView(heading)

        username = EditText(this)
        username.hint = "Username"
        username.singleLine = true
        username.setTextColor(Color.WHITE)
        username.setHintTextColor(Color.LTGRAY)
        username.setPadding(16, 12, 16, 12)
        username.setBackgroundColor(card)
        main.addView(username)

        password = EditText(this)
        password.hint = "Password"
        password.inputType =
            InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        password.setTextColor(Color.WHITE)
        password.setHintTextColor(Color.LTGRAY)
        password.setPadding(16, 12, 16, 12)
        password.setBackgroundColor(card)

        val passwordParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        passwordParams.setMargins(0, 15, 0, 15)
        main.addView(password, passwordParams)

        loginButton = Button(this)
        loginButton.text = "LOGIN & REGISTER THIS DEVICE"
        main.addView(loginButton)

        status = TextView(this)
        status.text = "\nStatus: Not Connected"
        status.textSize = 15f
        status.setTextColor(green)
        status.setPadding(10, 25, 10, 25)
        main.addView(status)

        val footer = TextView(this)
        footer.text = "PERSONAL PHONE GUARD\nSecure Owner Access"
        footer.gravity = Gravity.CENTER
        footer.textSize = 12f
        footer.setTextColor(Color.LTGRAY)
        footer.setPadding(0, 35, 0, 10)
        main.addView(footer)

        loginButton.setOnClickListener {
            loginAndRegister()
        }

        scroll.addView(main)
        setContentView(scroll)
    }

    private fun loginAndRegister() {

        val user = username.text.toString().trim()
        val pass = password.text.toString()

        if (user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(
                this,
                "Enter username and password",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        loginButton.isEnabled = false
        status.text = "Connecting to Cloudflare..."

        Thread {
            try {
                // Owner Login
                val loginBody = JSONObject()
                    .put("username", user)
                    .put("password", pass)

                val loginResult = apiRequest(
                    "/api/login",
                    loginBody,
                    null
                )

                if (!loginResult.optBoolean("success")) {
                    throw Exception(
                        loginResult.optString("error", "Login failed")
                    )
                }

                val token = loginResult.optString("token")

                if (token.isEmpty()) {
                    throw Exception("Login token missing")
                }

                // Register this Android device
                val deviceName =
                    "${Build.MANUFACTURER} ${Build.MODEL}".take(80)

                val deviceBody = JSONObject()
                    .put("device_name", deviceName)

                val deviceResult = apiRequest(
                    "/api/devices/register",
                    deviceBody,
                    token
                )

                if (!deviceResult.optBoolean("success")) {
                    throw Exception(
                        deviceResult.optString(
                            "error",
                            "Device registration failed"
                        )
                    )
                }

                val deviceId =
                    deviceResult.optString("device_id")

                runOnUiThread {
                    status.text =
                        "● CONNECTED SUCCESSFULLY\n\n" +
                        "Owner: $user\n" +
                        "Device: $deviceName\n\n" +
                        "Device ID:\n$deviceId\n\n" +
                        "Status: Registered"

                    status.setTextColor(green)
                    password.text.clear()
                    loginButton.text = "REGISTERED"
                    loginButton.isEnabled = false
                }

            } catch (e: Exception) {

                runOnUiThread {
                    status.text =
                        "Connection Error:\n${e.message}"

                    status.setTextColor(Color.RED)
                    loginButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun apiRequest(
        path: String,
        body: JSONObject,
        token: String?
    ): JSONObject {

        val connection = URL(api + path)
            .openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            if (token != null) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )
            }

            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode

            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()?.use {
                it.readText()
            } ?: "{}"

            val json = JSONObject(response)

            if (code !in 200..299) {
                throw Exception(
                    json.optString(
                        "error",
                        "HTTP Error $code"
                    )
                )
            }

            return json

        } finally {
            connection.disconnect()
        }
    }
}
