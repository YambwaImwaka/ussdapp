package com.techtonic.ussdapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

        // Check and request permissions
        checkAndRequestPermissions()

        // Initialize WebView
        setupWebView()

        // Initialize and register the SMS receiver
        smsReceiver = SmsReceiver { data ->
            // Handle SMS data and send to WebView
            runOnUiThread {
                webView?.evaluateJavascript("onSMSReceived('$data')", null)
            }
        }
        registerReceiver(smsReceiver, IntentFilter("android.provider.Telephony.SMS_RECEIVED"))
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
                runOnUiThread {
                    webView?.evaluateJavascript("populateSIMSlots('{}')", null)
                }
            }
        } catch (e: Exception) {
            Log.e("SIM Slots", "Error fetching SIM slots: ${e.message}")
        }
    }

    @JavascriptInterface
    fun fetchStoredSMS() {
        val smsList = smsReceiver.fetchSmsOnDemand(this)
        if (smsList != null) {
            val jsonResult = Gson().toJson(smsList)
            Log.d("SMS", "Fetched SMS: $jsonResult")
            runOnUiThread {
                webView?.evaluateJavascript("displayFetchedSMS('$jsonResult')", null)
            }
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

    @JavascriptInterface
    fun sendDebugData() {
        val debugData = mapOf(
            "sender" to "DebugSender",
            "message" to "This is a test debug message",
            "date" to System.currentTimeMillis()
        )

        firestore.collection("transactions")
            .add(debugData)
            .addOnSuccessListener {
                runOnUiThread {
                    Toast.makeText(this, "Debug data sent to Firestore.", Toast.LENGTH_SHORT).show()
                }
                Log.d("Firestore", "Debug data sent: $debugData")
            }
            .addOnFailureListener { e ->
                runOnUiThread {
                    Toast.makeText(this, "Failed to send debug data.", Toast.LENGTH_SHORT).show()
                }
                Log.e("Firestore", "Error sending debug data: ${e.message}")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(smsReceiver)
    }
}