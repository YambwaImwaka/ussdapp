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
        FirebaseApp.initializeApp(this)
        firestore = FirebaseFirestore.getInstance()

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
        try {
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeSubscriptionInfoList = subscriptionManager.activeSubscriptionInfoList ?: emptyList()

            if (activeSubscriptionInfoList.isNotEmpty()) {
                val simSlots = activeSubscriptionInfoList.associate { it.simSlotIndex to it.displayName.toString() }
                val jsonResult = Gson().toJson(simSlots)
                runOnUiThread {
                    webView?.evaluateJavascript("populateSIMSlots('$jsonResult')", null)
                }
            } else {
                runOnUiThread {
                    webView?.evaluateJavascript("populateSIMSlots('{}')", null)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error fetching SIM slots: ${e.message}")
        }
    }

    @JavascriptInterface
    fun fetchStoredSMS() {
        try {
            val smsList = smsReceiver.fetchStoredSMS(this)
            val jsonResult = Gson().toJson(smsList)
            runOnUiThread {
                webView?.evaluateJavascript("displayFetchedSMS('$jsonResult')", null)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error fetching SMS: ${e.message}")
        }
    }

    @JavascriptInterface
    fun dialThis(payload: String) {
        try {
            val data = Gson().fromJson(payload, Map::class.java)
            val ussdCode = data["ussdCode"] as String
            val simSlot = (data["simSlot"] as String).toIntOrNull() ?: 0 // Default to SIM 0 if invalid

            val encodedUssdCode = ussdCode.replace("#", Uri.encode("#"))
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$encodedUssdCode"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
                if (simSlot < subscriptionInfoList.size) {
                    val subscriptionId = subscriptionInfoList[simSlot].subscriptionId
                    intent.putExtra("android.telecom.extra.PHONE_ACCOUNT_HANDLE", subscriptionId)
                }
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                startActivity(intent)
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQUEST_PERMISSIONS)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error dialing USSD: ${e.message}")
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val permissionsToRequest = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), REQUEST_PERMISSIONS)
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