package com.example.ussdapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.storage.StorageMetadata
import org.json.JSONObject
import java.util.UUID
import com.google.gson.Gson
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedImage(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWebView()
        checkAndRequestPermissions()
    }

    private fun setupWebView() {
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
            }
            
            WebView.setWebContentsDebuggingEnabled(true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    webView?.evaluateJavascript("""
                        console.log = function(message) {
                            AndroidInterface.logToConsole(message);
                        };
                        console.error = function(message) {
                            AndroidInterface.logError(message);
                        };
                    """.trimIndent(), null)
                }
            }

            addJavascriptInterface(WebAppInterface(), "AndroidInterface")
            loadUrl("file:///android_asset/splash.html")
        }
        setContentView(webView)
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, 123)
        }
    }

    private fun handleSelectedImage(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                Log.d("ImageSelection", "Original image size: ${bytes.size/1024}KB")
                
                val compressedBytes = compressImage(bytes)
                val base64Image = android.util.Base64.encodeToString(compressedBytes, android.util.Base64.DEFAULT)
                val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
                
                runOnUiThread {
                    webView?.evaluateJavascript(
                        """
                        (function() {
                            try {
                                handleSelectedImage('data:$mimeType;base64,$base64Image');
                                return true;
                            } catch(e) {
                                console.error('Error handling image:', e);
                                return e.toString();
                            }
                        })();
                        """.trimIndent(),
                        { result ->
                            if (result != "true") {
                                Log.e("ImageSelection", "JavaScript handler returned: $result")
                                webView?.evaluateJavascript(
                                    "showError('Failed to process image')",
                                    null
                                )
                            }
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("ImageSelection", "Error processing image: ${e.message}", e)
            runOnUiThread {
                webView?.evaluateJavascript(
                    "showError('Error processing image: ${e.message}')",
                    null
                )
            }
        }
    }

    private fun compressImage(imageBytes: ByteArray, maxSizeKB: Int = 500): ByteArray {
        var quality = 100
        var compressedBytes = imageBytes
        
        try {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, stream)
            compressedBytes = stream.toByteArray()
            
            while (compressedBytes.size > maxSizeKB * 1024 && quality > 20) {
                stream.reset()
                quality -= 10
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, stream)
                compressedBytes = stream.toByteArray()
            }
            
            Log.d("ImageCompression", 
                "Original: ${imageBytes.size/1024}KB, " +
                "Compressed: ${compressedBytes.size/1024}KB, " +
                "Quality: $quality%"
            )
            
            return compressedBytes
        } catch (e: Exception) {
            Log.e("ImageCompression", "Compression failed: ${e.message}", e)
            return imageBytes
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun logToConsole(message: String) {
            Log.d("WebView", message)
        }

        @JavascriptInterface
        fun logError(message: String) {
            Log.e("WebView", message)
        }

        @JavascriptInterface
        fun dialThis(data: String) {
            try {
                val payloadObj = JSONObject(data)
                val ussdCode = payloadObj.getString("ussdCode")
                val encodedUssdCode = ussdCode.replace("#", Uri.encode("#"))
                val ussdUri = Uri.parse("tel:$encodedUssdCode")
                val intent = Intent(Intent.ACTION_CALL, ussdUri)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("USSD", "Error dialing USSD: ${e.message}", e)
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "showError('Failed to dial USSD code')",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun loginUser(userData: String) {
            try {
                val data = JSONObject(userData)
                val email = data.getString("email")
                val password = data.getString("password")

                auth.signInWithEmailAndPassword(email, password)
                    .addOnSuccessListener {
                        val user = it.user
                        if (user != null) {
                            firestore.collection("users").document(user.uid).get()
                                .addOnSuccessListener { document ->
                                    val userData = document.data
                                    userData?.put("uid", user.uid)
                                    userData?.put("email", user.email ?: "")
                                    
                                    runOnUiThread {
                                        webView?.evaluateJavascript(
                                            "handleLoginSuccess(${Gson().toJson(userData)})",
                                            null
                                        )
                                    }
                                }
                                .addOnFailureListener { e ->
                                    throw e
                                }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("Auth", "Login failed: ${e.message}", e)
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "handleLoginError('${e.message?.replace("'", "\\'")}')",
                                null
                            )
                        }
                    }
            } catch (e: Exception) {
                Log.e("Auth", "Login error: ${e.message}", e)
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "handleLoginError('${e.message?.replace("'", "\\'")}')",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun registerUser(userData: String) {
            try {
                val data = JSONObject(userData)
                val email = data.getString("email")
                val password = data.getString("password")
                val userType = data.getString("userType")
                val displayName = data.getString("displayName")

                auth.createUserWithEmailAndPassword(email, password)
                    .addOnSuccessListener {
                        val user = it.user
                        if (user != null) {
                            val userDoc = hashMapOf(
                                "email" to email,
                                "displayName" to displayName,
                                "userType" to userType,
                                "createdAt" to FieldValue.serverTimestamp()
                            )

                            firestore.collection("users").document(user.uid)
                                .set(userDoc)
                                .addOnSuccessListener {
                                    runOnUiThread {
                                        webView?.evaluateJavascript(
                                            "handleRegistrationSuccess()",
                                            null
                                        )
                                    }
                                }
                                .addOnFailureListener { e ->
                                    throw e
                                }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("Auth", "Registration failed: ${e.message}", e)
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "handleRegistrationError('${e.message?.replace("'", "\\'")}')",
                                null
                            )
                        }
                    }
            } catch (e: Exception) {
                Log.e("Auth", "Registration error: ${e.message}", e)
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "handleRegistrationError('${e.message?.replace("'", "\\'")}')",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun logout() {
            auth.signOut()
            runOnUiThread {
                webView?.loadUrl("file:///android_asset/index.html")
            }
        }

        @JavascriptInterface
        fun getCurrentUser() {
            val user = auth.currentUser
            if (user != null) {
                firestore.collection("users").document(user.uid).get()
                    .addOnSuccessListener { document ->
                        val userData = document.data?.toMutableMap() ?: mutableMapOf()
                        userData["uid"] = user.uid
                        userData["email"] = user.email ?: ""
                        
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "updateUserInterface(${Gson().toJson(userData)})",
                                null
                            )
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("Auth", "Error getting user data: ${e.message}", e)
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "showError('Failed to get user data')",
                                null
                            )
                        }
                    }
            }
        }

        @JavascriptInterface
        fun startImageSelection() {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png"))
                    }
                    imagePickerLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e("ImageSelection", "Error launching picker: ${e.message}", e)
                    webView?.evaluateJavascript(
                        "showError('Failed to launch image selector')",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun getProducts(type: String = "all") {
            Log.d("Products", "Fetching products of type: $type")
            
            try {
                val query = when (type) {
                    "myProducts" -> firestore.collection("products")
                        .whereEqualTo("farmerId", auth.currentUser?.uid)
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                    else -> firestore.collection("products")
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                }

                query.get()
                    .addOnSuccessListener { documents ->
                        val products = documents.mapNotNull { doc ->
                            try {
                                val data = doc.data.toMutableMap()
                                data["id"] = doc.id
                                val timestamp = data["createdAt"] as? Timestamp
                                if (timestamp != null) {
                                    data["createdAt"] = timestamp.toDate().toString()
                                }
                                data
                            } catch (e: Exception) {
                                Log.e("Products", "Error processing document ${doc.id}: ${e.message}")
                                null
                            }
                        }
                        
                        Log.d("Products", "Retrieved ${products.size} products")
                        
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                """
                                (function() {
                                    try {
                                        console.log('Products data:', ${Gson().toJson(products)});
                                        renderProducts(${Gson().toJson(products)});
                                        return true;
                                    } catch(e) {
                                        console.error('Error rendering products:', e);
                                        return e.toString();
                                    }
                                })();
                                """.trimIndent(),
                                { result -> 
                                    if (result != "true") {
                                        Log.e("Products", "Error in JavaScript: $result")
                                    }
                                }
                            )
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("Products", "Failed to fetch products: ${e.message}", e)
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "showError('Failed to fetch products: ${e.message}')",
                                null
                            )
                        }
                    }
            } catch (e: Exception) {
                Log.e("Products", "Error in getProducts: ${e.message}", e)
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "showError('Error fetching products: ${e.message}')",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun addProduct(productData: String) {
            try {
                Log.d("ProductCreation", "Starting product creation")
                val