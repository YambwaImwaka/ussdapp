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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Timestamp
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.gson.Gson
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance() }
    private val gson: Gson by lazy { Gson() }

    // Image picker launcher
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
                    // Enable console logging
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
            ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSIONS_REQUEST_CODE)
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
            val stream = ByteArrayOutputStream()
            
            do {
                stream.reset()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, stream)
                compressedBytes = stream.toByteArray()
                quality -= 10
            } while (compressedBytes.size > maxSizeKB * 1024 && quality > 20)
            
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
                    .addOnSuccessListener { authResult ->
                        val user = authResult.user
                        if (user != null) {
                            getUserData(user)
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

        private fun getUserData(user: FirebaseUser) {
            firestore.collection("users").document(user.uid).get()
                .addOnSuccessListener { document ->
                    val userData = document.data?.toMutableMap() ?: mutableMapOf()
                    userData["uid"] = user.uid
                    userData["email"] = user.email ?: ""
                    
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "handleLoginSuccess(${gson.toJson(userData)})",
                            null
                        )
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("Auth", "Error getting user data: ${e.message}", e)
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "handleLoginError('Failed to get user data')",
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
                    .addOnSuccessListener { authResult ->
                        val user = authResult.user
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
                getUserData(user)
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
                                        console.log('Products data:', ${gson.toJson(products)});
                                        renderProducts(${gson.toJson(products)});
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
                val data = JSONObject(productData)
                val user = auth.currentUser
                
                if (user == null) {
                    throw Exception("Please log in to create products")
                }

                // Validate required fields
                if (!data.has("name") || data.getString("name").isBlank()) {
                    throw Exception("Product name is required")
                }
                if (!data.has("price") || data.getDouble("price") <= 0) {
                    throw Exception("Valid price is required")
                }
                if (!data.has("description") || data.getString("description").isBlank()) {
                    throw Exception("Product description is required")
                }
                if (!data.has("image") || data.getString("image").isBlank()) {
                    throw Exception("Product image is required")
                }

                // Extract base64 image data
                val imageData = data.getString("image").let {
                    if (it.contains("base64,")) {
                        it.split("base64,")[1]
                    } else {
                        it
                    }
                }.trim()

                Log.d("ProductCreation", "Processing image...")
                val imageBytes = android.util.Base64.decode(imageData, android.util.Base64.DEFAULT)
                val compressedBytes = compressImage(imageBytes)
                val filename = "product_images/${UUID.randomUUID()}.jpg"
                
                // Create storage reference
                val imageRef = storage.reference.child(filename)
                val metadata = StorageMetadata.Builder()
                    .setContentType("image/jpeg")
                    .build()

                // Upload image
                imageRef.putBytes(compressedBytes, metadata)
                    .addOnProgressListener { taskSnapshot ->
                        val progress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount)
                        Log.d("Upload", "Progress: $progress%")
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "updateProgress($progress)",
                                null
                            )
                        }
                    }
                    .addOnSuccessListener { taskSnapshot ->
                        Log.d("Upload", "Image upload successful")
                        imageRef.downloadUrl.addOnSuccessListener { uri ->
                            // Create product document
                            val product = hashMapOf(
                                "name" to data.getString("name"),
                                "price" to data.getDouble("price"),
                                "description" to data.getString("description"),
                                "imageUrl" to uri.toString(),
                                "farmerId" to user.uid,
                                "farmerName" to (user.displayName ?: "Unknown Farmer"),
                                "createdAt" to FieldValue.serverTimestamp()
                            )

                            // Add to Firestore
                            firestore.collection("products")
                                .add(product)
                                .addOnSuccessListener { documentRef ->
                                    Log.d("ProductCreation", "Product created with ID: ${documentRef.id}")
                                    runOnUiThread {
                                        webView?.loadUrl("file:///android_asset/home.html")
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Log.e("ProductCreation", "Failed to create product: ${e.message}", e)
                                    runOnUiThread {
                                        webView?.evaluateJavascript(
                                            "showError('Failed to create product: ${e.message}')",
                                            null
                                        )
                                    }
                                }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("Upload", "Image upload failed: ${e.message}", e)
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "showError('Failed to upload image: ${e.message}')",
                                null
                            )
                        }
                    }

            } catch (e: Exception) {
                Log.e("ProductCreation", "Error: ${e.message}", e)
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "showError('${e.message?.replace("'", "\\'")}')",
                        null
                    )
                }
            }
        }
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 123
    }
}