package com.techtonic.ussdapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson

class MainActivity : ComponentActivity() {
    private val REQUEST_PERMISSIONS = 101
    private lateinit var firestore: FirebaseFirestore
    private lateinit var smsReceiver: SmsReceiver
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Firebase
        Log.d("MainActivity", "Initializing Firebase")
        FirebaseApp.initializeApp(this)
        firestore = FirebaseFirestore.getInstance()

        if (firestore == null) {
            Log.e("MainActivity", "Failed to initialize Firestore")
        } else {
            Log.d("MainActivity", "Firestore initialized successfully")
        }

        // Check and request permissions
        checkAndRequestPermissions()

        // Initialize WebView
        setupWebView()

        // Register SMS Receiver if permissions are granted
        if (hasSMSPermissions()) {
            smsReceiver = SmsReceiver(webView)
            registerReceiver(smsReceiver, IntentFilter("android.provider.Telephony.SMS_RECEIVED"))
        }
    }

    private fun setupWebView() {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            WebView.setWebContentsDebuggingEnabled(true)

            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()

            addJavascriptInterface(this@MainActivity, "android")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)
    }

    @JavascriptInterface
    fun fetchSIMSlots() {
        Log.d("WebView", "fetchSIMSlots called")
        try {
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeSubscriptionInfoList = subscriptionManager.activeSubscriptionInfoList ?: emptyList()

            if (activeSubscriptionInfoList.isNotEmpty()) {
                val simSlots = activeSubscriptionInfoList.associate { it.simSlotIndex to it.displayName.toString() }
                val jsonResult = Gson().toJson(simSlots)
                Log.d("SIM Slots", "Fetched SIM Slots: $jsonResult")
                runOnUiThread {
                    webView?.evaluateJavascript("populateSIMSlots('$jsonResult')", null)
                }
            } else {
                Log.d("SIM Slots", "No active SIMs found.")
                runOnUiThread {
                    webView?.evaluateJavascript("populateSIMSlots('{}')", null)
                }
            }
        } catch (e: Exception) {
            Log.e("SIM Slots", "Error fetching SIM slots: ${e.message}")
            runOnUiThread {
                webView?.evaluateJavascript("populateSIMSlots('{}')", null)
            }
        }
    }

    @JavascriptInterface
    fun fetchStoredSMS() {
        try {
            val smsList = smsReceiver.fetchStoredSMS(this)
            val jsonResult = Gson().toJson(smsList)
            Log.d("SMS", "Fetched SMS: $jsonResult")
            runOnUiThread {
                webView?.evaluateJavascript("displayFetchedSMS('$jsonResult')", null)
            }
        } catch (e: Exception) {
            Log.e("SMS", "Error fetching SMS: ${e.message}")
        }
    }

    @JavascriptInterface
    fun dialThis(payload: String) {
        try {
            val data = Gson().fromJson(payload, Map::class.java)
            val ussdCode = data["ussdCode"] as String
            val encodedUssdCode = ussdCode.replace("#", Uri.encode("#"))
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$encodedUssdCode"))
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                startActivity(intent)
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQUEST_PERMISSIONS)
            }
        } catch (e: Exception) {
            Log.e("USSD", "Error dialing USSD: ${e.message}")
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CALL_PHONE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun hasSMSPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::smsReceiver.isInitialized) {
            unregisterReceiver(smsReceiver)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
                smsReceiver = SmsReceiver(webView)
                registerReceiver(smsReceiver, IntentFilter("android.provider.Telephony.SMS_RECEIVED"))
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}