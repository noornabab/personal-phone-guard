package com.personalphoneguard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

        private const val BG = 0xFF080E1B.toInt()
        private const val SURFACE = 0xFF111C2D.toInt()
        private const val SURFACE_2 = 0xFF18263B.toInt()
        private const val BLUE = 0xFF168BFF.toInt()
        private const val CYAN = 0xFF24D5E8.toInt()
