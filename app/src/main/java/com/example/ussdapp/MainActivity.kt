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
    private var pendingImageSelection = false

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

    private fun startImageSelection() {
        if (checkStoragePermission()) {
            openImagePicker()
        } else {
            pendingImageSelection = true
            requestStoragePermission()
        }
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
                    "showError('Error processing image: ${e.message?.replace("'", "\\'")}')",
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
                    userData["email"] = user.email
                    userData["displayName"] = user.displayName
                    
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
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun login(email: String, password: String) {
            Log.d("Auth", "Attempting login with email: $email")
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
                            "showError('${e.message?.replace("'", "\\'")}')",
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
                                    "showError('Failed to save user data: ${e.message?.replace("'", "\\'")}')",
                                    null
                                )
                            }
                        }
                }
                .addOnFailureListener { e ->
                    Log.e("Auth", "Registration failed: ${e.message}", e)
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
        fun startImageSelection() {
            Log.d("ImageSelection", "Starting image selection")
            runOnUiThread {
                if (checkStoragePermission()) {
                    openImagePicker()
                } else {
                    pendingImageSelection = true
                    requestStoragePermission()
                }
            }
        }

        @JavascriptInterface
        fun addProduct(productData: String) {
            try {
                Log.d("ProductCreation", "Received product data: ${productData.take(200)}...")
                
                val data = JSONObject(productData)
                val user = auth.currentUser
                
                if (user == null) {
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "showError('User not authenticated')",
                            null
                        )
                    }
                    return
                }

                if (!data.has("image") || !data.getString("image").startsWith("data:image/")) {
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "showError('Invalid image format')",
                            null
                        )
                    }
                    return
                }

                val imageParts = data.getString("image").split(",")
                val imageData = imageParts[1].trim()

                Log.d("ProductCreation", "Processing image data")

                val imageBytes = try {
                    android.util.Base64.decode(imageData, android.util.Base64.DEFAULT)
                } catch (e: Exception) {
                    Log.e("ProductCreation", "Failed to decode image: ${e.message}", e)
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "showError('Invalid image format')",
                            null
                        )
                    }
                    return
                }

                val storageRef = storage.reference
                val imageRef = storageRef.child("product_images/${UUID.randomUUID()}.jpg")

                val uploadTask = imageRef.putBytes(imageBytes)
                
                uploadTask.addOnProgressListener { taskSnapshot ->
                    val progress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount)
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "updateUploadProgress($progress)",
                            null
                        )
                    }
                }

                uploadTask.addOnSuccessListener { taskSnapshot ->
                    imageRef.downloadUrl.addOnSuccessListener { uri ->
                        val product = hashMapOf(
                            "name" to data.getString("name"),
                            "price" to data.getDouble("price"),
                            "description" to data.getString("description"),
                            "imageUrl" to uri.toString(),
                            "farmerId" to user.uid,
                            "farmerName" to (user.displayName ?: "Unknown Farmer"),
                            "createdAt" to Date(),
                            "status" to "active"
                        )

                        firestore.collection("products")
                            .add(product)
                            .addOnSuccessListener {
                                Log.d("ProductCreation", "Product created successfully")
                                runOnUiThread {
                                    webView?.evaluateJavascript(
                                        "hideLoading()",
                                        null
                                    )
                                    webView?.loadUrl("file:///android_asset/home.html")
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e("ProductCreation", "Failed to create product: ${e.message}", e)
                                runOnUiThread {
                                    webView?.evaluateJavascript(
                                        "showError('Failed to save product: ${e.message?.replace("'", "\\'")}')",
                                        null
                                    )
                                }
                            }
                    }
                }.addOnFailureListener { e ->
                    Log.e("ProductCreation", "Image upload failed: ${e.message}", e)
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "showError('Image upload failed: ${e.message?.replace("'", "\\'")}')",
                            null
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e("ProductCreation", "Error creating product: ${e.message}", e)
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "showError('Error creating product: ${e.message?.replace("'", "\\'")}')",
                        null
                    )
                }
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
                        "showError('Failed to logout: ${e.message?.replace("'", "\\'")}')",
                        null
                    )
                }
            }
        }
    }
}