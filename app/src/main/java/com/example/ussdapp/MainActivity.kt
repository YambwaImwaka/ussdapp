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
import com.google.gson.reflect.TypeToken

class MainActivity : ComponentActivity() {

    private val REQUEST_PERMISSIONS = 101
    private lateinit var firestore: FirebaseFirestore
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Firebase
        Log.d("MainActivity", "Initializing Firebase")
        FirebaseApp.initializeApp(this)
        firestore = FirebaseFirestore.getInstance()

        // Check and request necessary permissions
        checkAndRequestPermissions()

        // Initialize WebView
        setupWebView()

        // Register SMS Receiver
        registerReceiver(
            SmsReceiver(webView, firestore),
            IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        )
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
        Log.d("MainActivity", "fetchSIMSlots called")
        try {
            // Check for permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                Log.e("MainActivity", "Permission READ_PHONE_STATE not granted")
                webView?.evaluateJavascript("populateSIMSlots('{}')", null)
                return
            }

            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList ?: emptyList()

            if (activeSubscriptions.isNotEmpty()) {
                val simSlots = activeSubscriptions.associate {
                    it.subscriptionId to it.displayName.toString()
                }
                val jsonResult = Gson().toJson(simSlots)
                Log.d("SIM Slots", "Fetched SIM Slots: $jsonResult")
                webView?.evaluateJavascript("populateSIMSlots('$jsonResult')", null)
            } else {
                Log.d("SIM Slots", "No active SIMs found.")
                webView?.evaluateJavascript("populateSIMSlots('{}')", null)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error fetching SIM slots: ${e.message}")
            webView?.evaluateJavascript("populateSIMSlots('{}')", null)
        }
    }

    @JavascriptInterface
    fun fetchStoredSMS() {
        Log.d("MainActivity", "fetchStoredSMS called")
        try {
            val smsList = SmsReceiver.fetchAllSMS(this)
            val jsonResult = Gson().toJson(smsList)
            Log.d("SMS Fetch", "Fetched SMS: $jsonResult")
            webView?.evaluateJavascript("displayFetchedSMS('$jsonResult')", null)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error fetching SMS: ${e.message}")
        }
    }

    @JavascriptInterface
    fun dialThis(payload: String) {
        try {
            // Deserialize the JSON payload to a Map
            val data: Map<String, Any> = Gson().fromJson(payload, object : TypeToken<Map<String, Any>>() {}.type)

            // Extract USSD code and SIM slot
            val ussdCode = data["ussdCode"] as String
            val subscriptionId = (data["simSlot"] as Double).toInt() // Convert to Int

            // Encode the USSD code properly
            val encodedUssdCode = Uri.encode(ussdCode)

            // Create the intent for dialing the USSD code
            val ussdUri = Uri.parse("tel:$encodedUssdCode") // Use encoded USSD code
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = ussdUri // Assign the encoded URI
                putExtra("android.telecom.extra.SUBSCRIPTION_ID", subscriptionId) // Use the subscription ID
            }

            // Check CALL_PHONE permission
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                startActivity(intent)
            } else {
                ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.CALL_PHONE), REQUEST_PERMISSIONS)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error dialing USSD: ${e.message}")
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsNeeded = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d("MainActivity", "All permissions granted")
            } else {
                Toast.makeText(this, "Permissions are required for app functionality", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(SmsReceiver(webView, firestore))
    }
}