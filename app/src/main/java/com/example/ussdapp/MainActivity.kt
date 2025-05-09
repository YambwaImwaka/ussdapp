package com.techtonic.ussdapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.storage.FirebaseStorage
import com.google.gson.Gson
import org.json.JSONObject
import java.util.*

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var imageSelectedCallback: ValueCallback<Uri>? = null

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d("Permissions", "All permissions granted")
            if (pendingImageSelection) {
                pendingImageSelection = false
                openImagePicker()
            }
        } else {
            Log.w("Permissions", "Some permissions were denied")
            webView?.evaluateJavascript(
                "showError('Some permissions were denied. App functionality may be limited.')",
                null
            )
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            val uri = data?.data
            if (uri != null) {
                handleSelectedImage(uri)
            } else {
                clearImageCallbacks()
            }
        } else {
            clearImageCallbacks()
        }
    }

    private var pendingImageSelection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            FirebaseApp.initializeApp(this)
            initializeFirebase()
            Log.d("Firebase", "Firebase initialized successfully")
        } catch (e: Exception) {
            Log.e("Firebase", "Failed to initialize Firebase: ${e.message}", e)
        }
        
        setupWebView()
        checkAndRequestPermissions()
    }

    private fun initializeFirebase() {
        try {
            auth = FirebaseAuth.getInstance()
            firestore = FirebaseFirestore.getInstance()
            storage = FirebaseStorage.getInstance()

            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .build()
            firestore.firestoreSettings = settings
            
            Log.d("Firebase", "Firebase components initialized successfully")
        } catch (e: Exception) {
            Log.e("Firebase", "Error initializing Firebase components: ${e.message}", e)
        }
    }

    private fun setupWebView() {
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                databaseEnabled = true
            }
            
            WebView.setWebContentsDebuggingEnabled(true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url?.endsWith("splash.html") == true) {
                        getCurrentUser()
                    }
                }
            }
            
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback = filePathCallback
                    startImageSelection()
                    return true
                }
            }

            addJavascriptInterface(WebAppInterface(), "AndroidInterface")
            loadUrl("file:///android_asset/splash.html")
        }
        setContentView(webView)
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startImageSelection() {
        if (checkStoragePermission()) {
            openImagePicker()
        } else {
            pendingImageSelection = true
            requestStoragePermission()
        }
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        requestPermissionLauncher.launch(arrayOf(permission))
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png"))
        }
        imagePickerLauncher.launch(intent)
    }

    private fun handleSelectedImage(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                val base64Image = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
                
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "handleSelectedImage('data:$mimeType;base64,$base64Image')",
                        null
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("Image", "Error processing image: ${e.message}", e)
            runOnUiThread {
                webView?.evaluateJavascript(
                    "showError('Error processing image: ${e.message?.replace("'", "")}')",
                    null
                )
            }
        } finally {
            clearImageCallbacks()
        }
    }

    private fun clearImageCallbacks() {
        filePathCallback?.onReceiveValue(null)
        imageSelectedCallback?.onReceiveValue(null)
        filePathCallback = null
        imageSelectedCallback = null
    }

    private fun getCurrentUser() {
        val user = auth.currentUser
        if (user != null) {
            firestore.collection("users").document(user.uid)
                .get()
                .addOnSuccessListener { document ->
                    val userData = document.data?.toMutableMap() ?: mutableMapOf()
                    userData["uid"] = user.uid
                    
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "updateUserInterface(${Gson().toJson(userData)})",
                            null
                        )
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("Auth", "Failed to get user data: ${e.message}", e)
                    runOnUiThread {
                        webView?.loadUrl("file:///android_asset/login.html")
                    }
                }
        } else {
            runOnUiThread {
                webView?.loadUrl("file:///android_asset/login.html")
            }
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun loginUser(email: String, password: String) {
            Log.d("Auth", "Attempting to login with email: $email")
            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener {
                    Log.d("Auth", "Login successful")
                    runOnUiThread {
                        webView?.loadUrl("file:///android_asset/home.html")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("Auth", "Login failed: ${e.message}", e)
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "showError('${e.message?.replace("'", "")}')",
                            null
                        )
                    }
                }
        }

        @JavascriptInterface
        fun registerUser(email: String, password: String, displayName: String, userType: String) {
            Log.d("Auth", "Attempting to register with email: $email")
            auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener { result ->
                    Log.d("Auth", "Registration successful")
                    val user = result.user
                    val userData = hashMapOf(
                        "displayName" to displayName,
                        "email" to email,
                        "userType" to userType,
                        "createdAt" to Date()
                    )

                    firestore.collection("users").document(user!!.uid)
                        .set(userData)
                        .addOnSuccessListener {
                            Log.d("Auth", "User data saved to Firestore")
                            runOnUiThread {
                                webView?.loadUrl("file:///android_asset/home.html")
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e("Auth", "Failed to save user data: ${e.message}", e)
                            runOnUiThread {
                                webView?.evaluateJavascript(
                                    "showError('Failed to save user data: ${e.message?.replace("'", "")}')",
                                    null
                                )
                            }
                        }
                }
                .addOnFailureListener { e ->
                    Log.e("Auth", "Registration failed: ${e.message}", e)
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "showError('${e.message?.replace("'", "")}')",
                            null
                        )
                    }
                }
        }

        @JavascriptInterface
        fun getCurrentUser(): String {
            val user = auth.currentUser
            return if (user != null) {
                firestore.collection("users").document(user.uid)
                    .get()
                    .addOnSuccessListener { document ->
                        val userData = document.data?.toMutableMap() ?: mutableMapOf()
                        userData["uid"] = user.uid
                        
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "updateUserInterface(${Gson().toJson(userData)})",
                                null
                            )
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("Auth", "Failed to get user data: ${e.message}", e)
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "showError('Failed to get user data')",
                                null
                            )
                        }
                    }
                "{}"
            } else {
                "{}"
            }
        }

        @JavascriptInterface
        fun getProducts(type: String = "all") {
            Log.d("Products", "Fetching products of type: $type")
            val query = when (type) {
                "myProducts" -> firestore.collection("products")
                    .whereEqualTo("farmerId", auth.currentUser?.uid)
                else -> firestore.collection("products")
            }

            query.get()
                .addOnSuccessListener { documents ->
                    val products = documents.map { doc ->
                        val data = doc.data
                        data["id"] = doc.id
                        data
                    }
                    Log.d("Products", "Retrieved ${products.size} products")
                    
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "renderProducts(${Gson().toJson(products)})",
                            null
                        )
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("Products", "Failed to fetch products: ${e.message}", e)
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "showError('Failed to fetch products')",
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
                        // Handle timestamps
                        (data["createdAt"] as? Timestamp)?.let {
                            data["createdAt"] = it.toDate().toString()
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

        private fun handleError(message: String, e: Exception) {
            val sanitizedMessage = message.replace("'", "")
            Log.e("ProductCreation", "$sanitizedMessage\n${e.stackTraceToString()}")
            runOnUiThread {
                webView?.evaluateJavascript(
                    "showError('$sanitizedMessage'); hideLoading();",
                    null
                )
            }
        }

        @JavascriptInterface
        fun logout() {
            try {
                auth.signOut()
                Log.d("Auth", "User logged out successfully")
                runOnUiThread {
                    webView?.loadUrl("file:///android_asset/login.html")
                }
            } catch (e: Exception) {
                Log.e("Auth", "Error during logout: ${e.message}", e)
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "showError('Failed to logout: ${e.message?.replace("'", "")}')",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun startImageSelection() {
            runOnUiThread {
                if (checkStoragePermission()) {
                    openImagePicker()
                } else {
                    pendingImageSelection = true
                    requestStoragePermission()
                }
            }
        }
    }
}