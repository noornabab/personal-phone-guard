package com.personalphoneguard

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast

class MainActivity : Activity() {

    private val bg = Color.rgb(12, 25, 38)
    private val card = Color.rgb(25, 43, 58)
    private val white = Color.WHITE
    private val green = Color.rgb(50, 200, 130)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setPadding(22, 35, 22, 30)
        main.setBackgroundColor(bg)

        val title = TextView(this)
        title.text = "PERSONAL PHONE GUARD"
        title.textSize = 24f
        title.setTextColor(white)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 15, 0, 8)

        val subtitle = TextView(this)
        subtitle.text = "Your Personal Security Dashboard"
        subtitle.textSize = 14f
        subtitle.setTextColor(Color.LTGRAY)
        subtitle.gravity = Gravity.CENTER

        main.addView(title)
        main.addView(subtitle)

        val status = TextView(this)
        status.text = "\n● DEVICE PROTECTION\n\nStatus: Initial Setup\nDevice: This Phone"
        status.textSize = 17f
        status.setTextColor(green)
        status.setPadding(18, 22, 18, 22)
        status.setBackgroundColor(card)

        val statusParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        statusParams.setMargins(0, 30, 0, 20)
        main.addView(status, statusParams)

        addFeature(main, "📱", "Device Registration")
        addFeature(main, "📍", "Location")
        addFeature(main, "📷", "Camera Capture")
        addFeature(main, "🔒", "Lost Mode")
        addFeature(main, "📦", "Data Export")
        addFeature(main, "🛡️", "Security Settings")

        val footer = TextView(this)
        footer.text = "\nPERSONAL PHONE GUARD\nOwner Controlled Security"
        footer.textSize = 12f
        footer.setTextColor(Color.LTGRAY)
        footer.gravity = Gravity.CENTER
        footer.setPadding(0, 25, 0, 10)

        main.addView(footer)

        scroll.addView(main)
        setContentView(scroll)
    }

    private fun addFeature(
        parent: LinearLayout,
        emoji: String,
        name: String
    ) {
        val item = TextView(this)
        item.text = "$emoji   $name\n      Not activated yet"
        item.textSize = 17f
        item.setTextColor(white)
        item.setPadding(20, 22, 20, 22)
        item.setBackgroundColor(card)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 8, 0, 8)

        parent.addView(item, params)
    }
}
