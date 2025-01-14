package com.techtonic.ussdapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson

class SmsReceiver(
    private val webViewCallback: ((String) -> Unit)? = null
) : BroadcastReceiver() {

    private val TAG = "SmsReceiver"
    private val firestore = FirebaseFirestore.getInstance()
    private var trustedServicePatterns: List<String> = emptyList()

    fun updateTrustedServicePatterns(patterns: List<String>) {
        trustedServicePatterns = patterns
        Log.d(TAG, "Updated trusted service patterns: $patterns")
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!hasPermission(context, android.Manifest.permission.READ_SMS)) {
            requestPermission(context, android.Manifest.permission.READ_SMS)
            return
        }

        val smsList = extractMessages(context, intent) ?: return
        for (sms in smsList) {
            if (isValidSender(sms["address"] ?: "", sms["serviceCenter"] ?: "")) {
                sendToFirestore(context, sms)
                sendToWebView(sms)
            }
        }
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission(context: Context, permission: String) {
        Toast.makeText(context, "Permission is required to access SMS.", Toast.LENGTH_SHORT).show()
        Log.e(TAG, "Permission not granted for $permission.")
    }

    private fun extractMessages(context: Context, intent: Intent): List<Map<String, Any>>? {
        val bundle = intent.extras ?: return null
        val pdus = bundle.get("pdus") as? Array<*> ?: return null

        val smsList = mutableListOf<Map<String, Any>>()
        for (pdu in pdus) {
            val message = SmsMessage.createFromPdu(pdu as ByteArray, bundle.getString("format"))
            val smsData = mapOf(
                "address" to (message.originatingAddress ?: "Unknown"),
                "body" to message.messageBody,
                "date" to message.timestampMillis,
                "serviceCenter" to (message.serviceCenterAddress ?: "Unknown"),
                "status" to message.status.toString()
            )
            smsList.add(smsData)
        }
        return smsList
    }

    private fun isValidSender(sender: String, serviceCenter: String): Boolean {
        return trustedServicePatterns.any { pattern -> sender.contains(pattern, ignoreCase = true) }
    }

    private fun sendToFirestore(context: Context, smsData: Map<String, Any>) {
        firestore.collection("transactions")
            .add(smsData)
            .addOnSuccessListener {
                Toast.makeText(context, "SMS saved to Firestore.", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "SMS saved to Firestore: $smsData")
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Failed to save SMS to Firestore.", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Failed to save SMS: $e")
            }
    }

    private fun sendToWebView(smsData: Map<String, Any>) {
        val jsonData = Gson().toJson(smsData)
        Log.d(TAG, "Sending SMS data to WebView: $jsonData")
        webViewCallback?.invoke(jsonData)
    }
}