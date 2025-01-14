package com.techtonic.ussdapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson

class SmsReceiver(private val webView: WebView? = null) : BroadcastReceiver() {

    private val TAG = "SmsReceiver"
    private val firestore = FirebaseFirestore.getInstance()

    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission to read SMS not granted.")
            return
        }

        val bundle = intent.extras
        if (bundle != null) {
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            for (pdu in pdus) {
                val message = SmsMessage.createFromPdu(pdu as ByteArray, bundle.getString("format"))
                val messageBody = message.messageBody
                val sender = message.originatingAddress

                Log.d(TAG, "SMS received from: $sender, Message: $messageBody")
                if (sender != null && sender.contains("Airtel Money", true)) {
                    val transactionDetails = parseTransactionMessage(messageBody)
                    if (transactionDetails.isNotEmpty()) {
                        sendToFirestore(context, transactionDetails)
                        sendToJavaScript(transactionDetails)
                    } else {
                        Log.w(TAG, "Unrecognized transaction format: $messageBody")
                    }
                }
            }
        }
    }

    private fun parseTransactionMessage(messageBody: String): Map<String, String> {
        return when {
            messageBody.startsWith("Payment of") -> parseSuccessMessage(messageBody)
            messageBody.startsWith("FAILED.TID") -> parseFailedMessage(messageBody)
            else -> emptyMap()
        }
    }

    private fun parseSuccessMessage(messageBody: String): Map<String, String> {
        val regex = Regex(
            "Payment of ZMW (\\d+\\.\\d{2}) Till Number (\\d+) ([A-Za-z\\s]+)\\. Airtel Money bal is ZMW (\\d+\\.\\d{2})\\. TID : ([A-Z0-9\\.]+)\\."
        )
        val match = regex.find(messageBody)
        return match?.groupValues?.let {
            mapOf(
                "status" to "SUCCESS",
                "amount" to it[1],
                "tillNumber" to it[2],
                "recipientName" to it[3],
                "balance" to it[4],
                "transactionId" to it[5]
            )
        } ?: emptyMap()
    }

    private fun parseFailedMessage(messageBody: String): Map<String, String> {
        val regex = Regex(
            "FAILED\\.TID: ([A-Z0-9\\.]+), Dear Customer, you have insufficient funds to complete this transaction\\..*"
        )
        val match = regex.find(messageBody)
        return match?.groupValues?.let {
            mapOf(
                "status" to "FAILED",
                "transactionId" to it[1],
                "reason" to "Insufficient funds"
            )
        } ?: emptyMap()
    }

    private fun sendToFirestore(context: Context, transactionDetails: Map<String, String>) {
        Log.d(TAG, "Attempting to send to Firestore: $transactionDetails")
        firestore.collection("transactions")
            .add(transactionDetails)
            .addOnSuccessListener {
                Toast.makeText(context, "Transaction saved online!", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Transaction saved to Firestore successfully: $transactionDetails")
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Failed to save transaction online.", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Failed to save transaction: ${e.message}")
            }
    }

    private fun sendToJavaScript(transactionDetails: Map<String, String>) {
        Log.d(TAG, "Sending transaction details to WebView: $transactionDetails")
        webView?.evaluateJavascript(
            "onSMSReceived(${Gson().toJson(transactionDetails)})",
            null
        )
    }

    fun fetchStoredSMS(context: Context): List<Map<String, String>> {
        val smsList = mutableListOf<Map<String, String>>()
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.Inbox.ADDRESS, Telephony.Sms.Inbox.BODY),
            null,
            null,
            Telephony.Sms.Inbox.DEFAULT_SORT_ORDER
        )

        while (cursor?.moveToNext() == true) {
            val address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.ADDRESS))
            val body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY))

            if (address.contains("Airtel Money", true)) {
                val transactionDetails = parseTransactionMessage(body)
                if (transactionDetails.isNotEmpty()) {
                    smsList.add(transactionDetails)
                }
            }
        }
        cursor?.close()
        return smsList
    }
}