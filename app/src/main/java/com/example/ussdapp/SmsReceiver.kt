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

    /**
     * Updates the list of trusted service patterns for validation.
     */
    fun updateTrustedServicePatterns(patterns: List<String>) {
        trustedServicePatterns = patterns
        Log.d(TAG, "Updated trusted service patterns: $patterns")
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!hasPermission(context, android.Manifest.permission.READ_SMS)) {
            requestPermission(context, android.Manifest.permission.READ_SMS)
            return
        }

        val smsList = extractMessages(intent) ?: return
        for (sms in smsList) {
            if (isValidSender(sms["address"] as String, sms["serviceCenter"] as String)) {
                sendToFirestore(context, sms)
                sendToWebView(sms)
            }
        }
    }

    /**
     * Checks if the app has the required permission.
     */
    private fun hasPermission(context: Context, permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Requests the required permission with a toast message.
     */
    private fun requestPermission(context: Context, permission: String) {
        Toast.makeText(context, "Permission is required to access SMS.", Toast.LENGTH_SHORT).show()
        Log.e(TAG, "Permission not granted for $permission.")
    }

    /**
     * Extracts SMS messages from the received intent.
     */
    private fun extractMessages(intent: Intent): List<Map<String, Any>>? {
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

    /**
     * Validates the sender based on trusted service patterns.
     */
    private fun isValidSender(sender: String, serviceCenter: String): Boolean {
        return trustedServicePatterns.any { pattern -> sender.contains(pattern, ignoreCase = true) }
    }

    /**
     * Sends SMS data to Firestore.
     */
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

    /**
     * Sends SMS data to the WebView.
     */
    private fun sendToWebView(smsData: Map<String, Any>) {
        val jsonData = Gson().toJson(smsData)
        Log.d(TAG, "Sending SMS data to WebView: $jsonData")
        webViewCallback?.invoke(jsonData)
    }

    /**
     * Fetches all SMS messages on demand.
     */
    fun fetchStoredSMS(context: Context): List<Map<String, Any>>? {
        if (!hasPermission(context, android.Manifest.permission.READ_SMS)) {
            requestPermission(context, android.Manifest.permission.READ_SMS)
            return null
        }

        val cursor: Cursor? = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.SERVICE_CENTER
            ),
            null,
            null,
            Telephony.Sms.DEFAULT_SORT_ORDER
        )

        val smsList = mutableListOf<Map<String, Any>>()
        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "Unknown"
                val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                val serviceCenter = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.SERVICE_CENTER)) ?: "Unknown"

                val smsData = mapOf(
                    "address" to address,
                    "body" to body,
                    "date" to date,
                    "serviceCenter" to serviceCenter
                )

                if (isValidSender(address, serviceCenter)) {
                    smsList.add(smsData)
                }
            }
        }
        return smsList
    }
}