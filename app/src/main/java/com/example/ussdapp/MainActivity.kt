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
            smsReceiver = SmsReceiver { smsJson ->
                runOnUiThread {
                    webView?.evaluateJavascript("onSmsReceived('$smsJson')", null)
                }
            }
            registerReceiver(smsReceiver, IntentFilter("android.provider.Telephony.SMS_RECEIVED"))
        }
    }

    private fun setupWebView() {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            WebView.setWebContentsDebuggingEnabled(true)

            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()

            addJavascriptInterface(object {
                @JavascriptInterface
                fun fetchSIMSlots() {
                    fetchSimSlots()
                }

                @JavascriptInterface
                fun fetchStoredSMS() {
                    val smsList = smsReceiver.fetchStoredSMS(this@MainActivity)
                    smsList?.forEach { sms ->
                        webView?.evaluateJavascript("onSmsReceived('${Gson().toJson(sms)}')", null)
                    }
                }

                @JavascriptInterface
                fun sendDebugDataToFirestore() {
                    val debugData = mapOf(
                        "status" to "DEBUG",
                        "amount" to "100.00",
                        "message" to "Debugging Firestore",
                        "timestamp" to System.currentTimeMillis()
                    )
                    sendToFirestore(debugData)
                }

                @JavascriptInterface
                fun updateTrustedServicePatterns(patternsJson: String) {
                    val patterns = Gson().fromJson(patternsJson, Array<String>::class.java).toList()
                    smsReceiver.updateTrustedServicePatterns(patterns)
                }

                @JavascriptInterface
                fun dialThis(payload: String) {
                    dialUSSD(payload)
                }
            }, "AndroidInterface")

            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)
    }

    private fun fetchSimSlots() {
        try {
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList ?: emptyList()
            val simSlots = activeSubscriptions.associate { it.simSlotIndex to it.displayName.toString() }

            val jsonResult = Gson().toJson(simSlots)
            runOnUiThread {
                webView?.evaluateJavascript("populateSIMSlots('$jsonResult')", null)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error fetching SIM slots: ${e.message}")
            runOnUiThread {
                webView?.evaluateJavascript("populateSIMSlots('{}')", null)
            }
        }
    }

    private fun sendToFirestore(data: Map<String, Any>) {
        firestore.collection("transactions")
            .add(data)
            .addOnSuccessListener {
                Log.d("Firestore", "Data successfully sent: $data")
            }
            .addOnFailureListener { e ->
                Log.e("Firestore", "Failed to send data: ${e.message}")
            }
    }

private fun dialUSSD(payload: String) {
    try {
        // Parse payload into a Map<String, String>
        val data: Map<String, String> = Gson().fromJson(payload, object : TypeToken<Map<String, String>>() {}.type)
        val ussdCode = data["ussdCode"] ?: throw IllegalArgumentException("USSD code is missing")
        val simSlot = data["simSlot"]?.toIntOrNull() ?: throw IllegalArgumentException("Sim slot is invalid or missing")

        // Properly encode the USSD code
        val encodedUssdCode = ussdCode.replace("#", Uri.encode("#"))
        val ussdUri = Uri.parse("tel:$encodedUssdCode")

        // Create the intent with the USSD code and SIM slot
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = ussdUri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra("com.android.phone.extra.slot", simSlot) // Add SIM slot for dual SIM
            }
        }

        // Check permission and start the call
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            startActivity(intent)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQUEST_PERMISSIONS)
        }
    } catch (e: Exception) {
        // Handle errors and log
        Log.e("MainActivity", "Error dialing USSD: ${e.message}")
        Toast.makeText(this, "Failed to dial USSD: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
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
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
        }
    }
}