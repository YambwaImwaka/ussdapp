// Updated SMS Receiver with fetch on-demand and foreground handling
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
import com.google.gson.Gson

class SmsReceiver(private val webViewCallback: ((String) -> Unit)? = null) : BroadcastReceiver() {

    private val TAG = "SmsReceiver"
    private var trustedServicePatterns: List<String> = emptyList()

    fun updateTrustedServicePatterns(patterns: List<String>) {
        trustedServicePatterns = patterns
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!hasPermission(context, android.Manifest.permission.READ_SMS)) {
            requestPermission(context, android.Manifest.permission.READ_SMS)
            return
        }

        val smsList = extractMessages(context, intent) ?: return
        for (sms in smsList) {
            if (isValidSender(sms["address"] ?: "", sms["serviceCenter"] ?: "")) {
                sendToWebView(sms)
            }
        }
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission(context: Context, permission: String) {
        Toast.makeText(context, "SMS permission is required to receive messages.", Toast.LENGTH_SHORT).show()
        Log.e(TAG, "SMS permission not granted.")
    }

    fun fetchSmsOnDemand(context: Context): List<Map<String, Any>>? {
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
                Telephony.Sms.PROTOCOL,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.READ,
                Telephony.Sms.STATUS
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
                val protocol = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.PROTOCOL)) ?: "Unknown"
                val threadId = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)) ?: "Unknown"
                val read = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1
                val status = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.STATUS)) ?: "Unknown"

                val smsData = mapOf(
                    "address" to address,
                    "body" to body,
                    "date" to date,
                    "protocolIdentifier" to protocol,
                    "threadId" to threadId,
                    "read" to read,
                    "status" to status
                )

                if (isValidSender(address, "")) { // Service center unavailable in this query
                    smsList.add(smsData)
                    sendToWebView(smsData) // Send each valid SMS to WebView
                }
            }
        }
        return smsList
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
                "protocolIdentifier" to (message.protocolIdentifier?.toString() ?: "Unknown"),
                "indexOnIcc" to (message.indexOnIcc?.takeIf { it >= 0 }?.toString() ?: "Not on SIM"),
                "statusOnIcc" to (message.statusOnIcc.takeIf { it >= 0 }?.toString() ?: "Not applicable"),
                "locked" to false,
                "messageDirection" to getMessageDirection(message),
                "messageType" to "SMS",
                "replyPathPresent" to message.isReplyPathPresent,
                "seen" to true,
                "serviceCenter" to (message.serviceCenterAddress ?: "Unknown"),
                "status" to message.status.toString(),
                "threadId" to getThreadId(context, message)
            )
            smsList.add(smsData)
        }
        return smsList
    }

    private fun getMessageDirection(message: SmsMessage): String {
        return if (message.originatingAddress.isNullOrEmpty()) "OUTGOING" else "INCOMING"
    }

    private fun getThreadId(context: Context, message: SmsMessage): String {
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(Telephony.Sms.THREAD_ID)
        val selection = "${Telephony.Sms.ADDRESS} = ?"
        val selectionArgs = arrayOf(message.originatingAddress)

        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
            }
        }
        return "Unknown"
    }

    private fun isValidSender(sender: String, serviceCenter: String): Boolean {
        val trustedSenders = listOf("AirtelMoney")
        val shortCodePattern = Regex("^[0-9]{3,6}$") // Short codes like 12345 or 6789
        val systemNamePattern = Regex("^[A-Za-z]+$") // Alphanumeric system names like AirtelMoney

        val isFromShortCode = shortCodePattern.matches(sender)
        val isFromSystemName = systemNamePattern.matches(sender)
        val isTrustedServiceCenter = trustedServicePatterns.any { serviceCenter.startsWith(it) }

        return (isFromShortCode || isFromSystemName) && (serviceCenter.isEmpty() || isTrustedServiceCenter)
    }

    private fun sendToWebView(smsData: Map<String, Any>) {
        val jsonData = Gson().toJson(smsData)
        Log.d(TAG, "Sending SMS data to WebView: $jsonData")
        webViewCallback?.invoke(jsonData)
    }
}