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
        val user = auth.currentUser
        if (user == null) throw Exception("User not authenticated")
        
        // First get the user's data from Firestore
        firestore.collection("users").document(user.uid)
            .get()
            .addOnSuccessListener { userDoc ->
                val displayName = userDoc.getString("displayName") ?: "Unknown Farmer"
                
                // Continue with product creation
                val data = JSONObject(productData)
                if (!data.has("image")) throw Exception("Missing image data")
                if (!data.has("name") || data.getString("name").isBlank()) throw Exception("Invalid product name")
                if (!data.has("price") || data.getDouble("price") <= 0) throw Exception("Invalid price")
                if (!data.has("description") || data.getString("description").isBlank()) throw Exception("Invalid description")

                val imageParts = data.getString("image").split(",")
                if (imageParts.size < 2) throw Exception("Invalid image format")
                val imageData = imageParts.last().trim()

                val imageBytes = try {
                    android.util.Base64.decode(imageData, android.util.Base64.NO_WRAP)
                } catch (e: Exception) {
                    throw Exception("Invalid image encoding: ${e.message}")
                }

                val storageRef = storage.reference
                val imageRef = storageRef.child("product_images/${UUID.randomUUID()}.jpg")
                val uploadTask = imageRef.putBytes(imageBytes)
                
                uploadTask.addOnSuccessListener { _ ->
                    imageRef.downloadUrl.addOnSuccessListener { uri ->
                        val product = hashMapOf(
                            "name" to data.getString("name"),
                            "price" to data.getDouble("price"),
                            "description" to data.getString("description"),
                            "imageUrl" to uri.toString(),
                            "farmerId" to user.uid,
                            "farmerName" to displayName,  // Use the name from Firestore
                            "createdAt" to Date(),
                            "status" to "active"
                        )

                        firestore.collection("products")
                            .add(product)
                            .addOnSuccessListener {
                                Log.d("ProductCreation", "Product created successfully")
                                runOnUiThread {
                                    webView?.evaluateJavascript("hideLoading()", null)
                                    webView?.loadUrl("file:///android_asset/home.html")
                                }
                            }
                            .addOnFailureListener { e ->
                                handleError("Firestore save failed: ${e.message}", e)
                            }
                    }
                }.addOnFailureListener { e ->
                    handleError("Image upload failed: ${e.message}", e)
                }
            }
            .addOnFailureListener { e ->
                handleError("Failed to get user data: ${e.message}", e)
            }

    } catch (e: Exception) {
        handleError("Product creation error: ${e.message}", e)
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
fun getProductDetails(productId: String) {
    Log.d("ProductDetails", "Fetching product: $productId")
    firestore.collection("products").document(productId)
        .get()
        .addOnSuccessListener { document ->
            if (document.exists()) {
                val productData = document.data?.toMutableMap() ?: mutableMapOf()
                productData["id"] = document.id
                runOnUiThread {
                    webView?.evaluateJavascript(
                        // Use different render functions based on the page
                        """
                        if (window.location.href.includes('checkout.html')) {
                            renderCheckoutSummary(${Gson().toJson(productData)});
                        } else {
                            renderProductDetails(${Gson().toJson(productData)});
                        }
                        """.trimIndent(),
                        null
                    )
                }
            } else {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "showError('Product not found')",
                        null
                    )
                }
            }
        }
        .addOnFailureListener { e ->
            Log.e("ProductDetails", "Error fetching product: ${e.message}", e)
            runOnUiThread {
                webView?.evaluateJavascript(
                    "showError('Failed to load product: ${e.message?.replace("'", "\\'")}')",
                    null
                )
            }
        }
}



      @JavascriptInterface
fun getOrders() {
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

    firestore.collection("users").document(user.uid).get()
        .addOnSuccessListener { userDoc ->
            val userType = userDoc.getString("userType")
            
            val ordersQuery = when (userType) {
                "farmer" -> firestore.collection("orders")
                    .whereEqualTo("farmerId", user.uid)
                "consumer" -> firestore.collection("orders")
                    .whereEqualTo("userId", user.uid)
                else -> null
            }

            ordersQuery?.get()
                ?.addOnSuccessListener { documents ->
                    val orders = documents.map { doc ->
                        val data = doc.data.toMutableMap()
                        data["orderId"] = doc.id
                        data
                    }
                    
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "renderOrders(${Gson().toJson(orders)})",
                            null
                        )
                    }
                }
                ?.addOnFailureListener { e ->
                    Log.e("Orders", "Failed to fetch orders: ${e.message}")
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "showError('Failed to fetch orders: ${e.message?.replace("'", "\\'")}')",
                            null
                        )
                    }
                }
        }
        .addOnFailureListener { e ->
            Log.e("Orders", "Failed to fetch user type: ${e.message}")
            runOnUiThread {
                webView?.evaluateJavascript(
                    "showError('Failed to fetch user data: ${e.message?.replace("'", "\\'")}')",
                    null
                )
            }
        }
}

@JavascriptInterface
fun updateOrderStatus(orderId: String, status: String) {
    val user = auth.currentUser
    if (user == null) {
        runOnUiThread {
            webView?.evaluateJavascript(
                """
                if (typeof showError === 'function') {
                    showError('User not authenticated');
                } else {
                    alert('User not authenticated');
                }
                """.trimIndent(),
                null
            )
        }
        return
    }

    firestore.collection("orders").document(orderId)
        .get()
        .addOnSuccessListener { document ->
            val order = document.data
            if (order == null) {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        """
                        if (typeof showError === 'function') {
                            showError('Order not found');
                        } else {
                            alert('Order not found');
                        }
                        """.trimIndent(),
                        null
                    )
                }
                return@addOnSuccessListener
            }

            if (order["farmerId"] != user.uid) {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        """
                        if (typeof showError === 'function') {
                            showError('Not authorized to update this order');
                        } else {
                            alert('Not authorized to update this order');
                        }
                        """.trimIndent(),
                        null
                    )
                }
                return@addOnSuccessListener
            }

          
            firestore.collection("orders").document(orderId)
                .update(
                    mapOf(
                        "status" to status,
                        "updatedAt" to Date(),
                        "updatedBy" to user.uid
                    )
                )
                .addOnSuccessListener {
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            """
                            if (typeof showSuccess === 'function') {
                                showSuccess('Order status updated successfully');
                            } else {
                                alert('Order status updated successfully');
                            }
                            loadOrders();
                            """.trimIndent(),
                            null
                        )
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("Orders", "Failed to update order: ${e.message}")
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            """
                            if (typeof showError === 'function') {
                                showError('Failed to update order: ${e.message?.replace("'", "\\'")}');
                            } else {
                                alert('Failed to update order: ${e.message?.replace("'", "\\'")}');
                            }
                            """.trimIndent(),
                            null
                        )
                    }
                }
        }
        .addOnFailureListener { e ->
            Log.e("Orders", "Failed to fetch order: ${e.message}")
            runOnUiThread {
                webView?.evaluateJavascript(
                    """
                    if (typeof showError === 'function') {
                        showError('Failed to fetch order: ${e.message?.replace("'", "\\'")}');
                    } else {
                        alert('Failed to fetch order: ${e.message?.replace("'", "\\'")}');
                    }
                    """.trimIndent(),
                    null
                )
            }
        }
}


@JavascriptInterface
fun placeOrder(productId: String) {
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

    // First get the current user's data
    firestore.collection("users").document(user.uid)
        .get()
        .addOnSuccessListener { userDoc ->
            val buyerName = userDoc.getString("displayName") ?: "Unknown User"
            
            // Then get the product
            firestore.collection("products").document(productId)
                .get()
                .addOnSuccessListener { productDoc ->
                    val product = productDoc.data
                    if (product == null) {
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "showError('Product not found')",
                                null
                            )
                        }
                        return@addOnSuccessListener
                    }

                    // Now get the farmer's data
                    val farmerId = product["farmerId"] as String
                    firestore.collection("users").document(farmerId)
                        .get()
                        .addOnSuccessListener { farmerDoc ->
                            val farmerName = farmerDoc.getString("displayName") ?: "Unknown Farmer"

                            val order = hashMapOf(
                                "productId" to productId,
                                "productName" to product["name"],
                                "productImage" to product["imageUrl"],
                                "price" to product["price"],
                                "userId" to user.uid,
                                "userName" to buyerName,  // Use buyer's name from Firestore
                                "farmerId" to farmerId,
                                "farmerName" to farmerName,  // Use farmer's name from Firestore
                                "status" to "PENDING",
                                "createdAt" to Date(),
                                "updatedAt" to Date()
                            )

                            firestore.collection("orders")
                                .add(order)
                                .addOnSuccessListener { docRef ->
                                    runOnUiThread {
                                        webView?.evaluateJavascript(
                                            """
                                            if (typeof showSuccess === 'function') {
                                                showSuccess('Order placed successfully');
                                                setTimeout(() => { window.location.href = 'orders.html'; }, 1500);
                                            } else {
                                                alert('Order placed successfully');
                                                window.location.href = 'orders.html';
                                            }
                                            """.trimIndent(),
                                            null
                                        )
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Log.e("Orders", "Failed to create order: ${e.message}")
                                    showError("Failed to place order: ${e.message}")
                                }
                        }
                        .addOnFailureListener { e ->
                            Log.e("Orders", "Failed to get farmer data: ${e.message}")
                            showError("Failed to get farmer data: ${e.message}")
                        }
                }
                .addOnFailureListener { e ->
                    Log.e("Orders", "Failed to fetch product: ${e.message}")
                    showError("Failed to fetch product: ${e.message}")
                }
        }
        .addOnFailureListener { e ->
            Log.e("Orders", "Failed to get user data: ${e.message}")
            showError("Failed to get user data: ${e.message}")
        }
}

private fun showError(message: String) {
    runOnUiThread {
        webView?.evaluateJavascript(
            """
            if (typeof showError === 'function') {
                showError('${message.replace("'", "\\'")}');
            } else {
                alert('${message.replace("'", "\\'")}');
            }
            """.trimIndent(),
            null
        )
    }
}

@JavascriptInterface
fun updateProfile(profileData: String) {
    try {
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

        val data = JSONObject(profileData)
        val updates = hashMapOf<String, Any>(
            "displayName" to (data.getString("displayName")),
            "updatedAt" to Date()
        )

        
        firestore.collection("users").document(user.uid)
            .update(updates)
            .addOnSuccessListener {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "showSuccess('Profile updated successfully')",
                        null
                    )
                }
            }
            .addOnFailureListener { e ->
                Log.e("Profile", "Failed to update profile: ${e.message}")
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "showError('Failed to update profile: ${e.message?.replace("'", "\\'")}')",
                        null
                    )
                }
            }

    } catch (e: Exception) {
        Log.e("Profile", "Error updating profile: ${e.message}")
        runOnUiThread {
            webView?.evaluateJavascript(
                "showError('Error updating profile: ${e.message?.replace("'", "\\'")}')",
                null
            )
        }
    }
}

@JavascriptInterface
fun updateProfileImage(imageData: String) {
    try {
        val user = auth.currentUser
        if (user == null) throw Exception("User not authenticated")

        val imageParts = imageData.split(",")
        if (imageParts.size < 2) throw Exception("Invalid image format")
        val imageBytes = android.util.Base64.decode(imageParts[1], android.util.Base64.DEFAULT)

        val storageRef = storage.reference
        val imageRef = storageRef.child("profile_images/${user.uid}.jpg")

        val uploadTask = imageRef.putBytes(imageBytes)
        uploadTask.addOnProgressListener { taskSnapshot ->
            val progress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount)
            runOnUiThread {
                webView?.evaluateJavascript(
                    "updateImageProgress($progress)",
                    null
                )
            }
        }

        uploadTask.continueWithTask { task ->
            if (!task.isSuccessful) {
                throw task.exception ?: Exception("Unknown error")
            }
            imageRef.downloadUrl
        }.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val downloadUrl = task.result.toString()
                
                
                firestore.collection("users").document(user.uid)
                    .update("profileImage", downloadUrl)
                    .addOnSuccessListener {
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                """
                                updateProfileImage('$downloadUrl');
                                showSuccess('Profile image updated successfully');
                                """.trimIndent(),
                                null
                            )
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("Profile", "Failed to update profile image URL: ${e.message}")
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "showError('Failed to update profile image: ${e.message?.replace("'", "\\'")}')",
                                null
                            )
                        }
                    }
            } else {
                val exception = task.exception
                Log.e("Profile", "Failed to get download URL: ${exception?.message}")
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "showError('Failed to upload image: ${exception?.message?.replace("'", "\\'")}')",
                        null
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.e("Profile", "Error updating profile image: ${e.message}")
        runOnUiThread {
            webView?.evaluateJavascript(
                "showError('Error updating profile image: ${e.message?.replace("'", "\\'")}')",
                null
            )
        }
    }
}

@JavascriptInterface
fun initializeChat(otherUserId: String) {
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

    val chatParticipants = listOf(user.uid, otherUserId).sorted()
    val chatId = "chat_${chatParticipants[0]}_${chatParticipants[1]}"

    // Set up the message listener immediately
    setupMessageListener(chatId)

    firestore.collection("chats").document(chatId)
        .get()
        .addOnSuccessListener { document ->
            if (!document.exists()) {
                val chatData = hashMapOf(
                    "participants" to mapOf(
                        user.uid to true,
                        otherUserId to true
                    ),
                    "createdAt" to Date(),
                    "lastMessage" to null
                )
                
                firestore.collection("chats").document(chatId)
                    .set(chatData)
                    .addOnSuccessListener {
                        // Initialize chat UI after successful creation
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "chatId = '$chatId'",
                                null
                            )
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("Chat", "Failed to create chat document", e)
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "showError('Failed to initialize chat')",
                                null
                            )
                        }
                    }
            } else {
                // Chat already exists, just initialize the UI
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "chatId = '$chatId'",
                        null
                    )
                }
            }

            // Get other user's details
            firestore.collection("users").document(otherUserId)
                .get()
                .addOnSuccessListener { userDoc ->
                    val userData = userDoc.data
                    if (userData != null) {
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "updateChatHeader(${Gson().toJson(userData)})",
                                null
                            )
                        }
                    }
                }
        }
}


@JavascriptInterface
fun confirmOrder(orderDataString: String) {
    try {
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

        val orderData = JSONObject(orderDataString)
        
        // Validate required fields
        if (!orderData.has("productId") || !orderData.has("quantity") || 
            !orderData.has("deliveryAddress") || !orderData.has("phoneNumber") ||
            !orderData.has("deliveryMethod") || !orderData.has("paymentMethod")) {
            throw Exception("Missing required order details")
        }

        // Get product details to calculate final price
        firestore.collection("products").document(orderData.getString("productId"))
            .get()
            .addOnSuccessListener { document ->
                if (!document.exists()) {
                    throw Exception("Product not found")
                }

                val product = document.data!!
                val deliveryFee = when(orderData.getString("deliveryMethod")) {
                    "express" -> 100.0
                    "standard" -> 50.0
                    else -> 0.0
                }

                val order = hashMapOf(
                    "productId" to orderData.getString("productId"),
                    "productName" to product["name"],
                    "productImage" to product["imageUrl"],
                    "quantity" to orderData.getInt("quantity"),
                    "price" to (product["price"] as Double),
                    "deliveryFee" to deliveryFee,
                    "totalAmount" to ((product["price"] as Double) * orderData.getInt("quantity") + deliveryFee),
                    "deliveryAddress" to orderData.getString("deliveryAddress"),
                    "phoneNumber" to orderData.getString("phoneNumber"),
                    "deliveryMethod" to orderData.getString("deliveryMethod"),
                    "paymentMethod" to orderData.getString("paymentMethod"),
                    "userId" to user.uid,
                    "userName" to (user.displayName ?: "Unknown User"),
                    "farmerId" to product["farmerId"],
                    "farmerName" to product["farmerName"],
                    "status" to "PENDING",
                    "paymentStatus" to "PENDING",
                    "createdAt" to Date(),
                    "updatedAt" to Date()
                )

                firestore.collection("orders")
                    .add(order)
                    .addOnSuccessListener { docRef ->
                        runOnUiThread {
                            webView?.evaluateJavascript(
                        """
                        if (typeof showSuccess === 'function') {
                            showSuccess('Order placed successfully');
                            setTimeout(() => {
                                window.location.href = 'order-complete.html';
                            }, 1000);
                        } else {
                            alert('Order placed successfully');
                            window.location.href = 'order-complete.html';
                        }
                        """.trimIndent(),
                        null
                    )
                        }
                    }

                    .addOnFailureListener { e ->
                        throw Exception("Failed to create order: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                throw Exception("Failed to fetch product details: ${e.message}")
            }

    } catch (e: Exception) {
        Log.e("Order", "Error creating order: ${e.message}", e)
        runOnUiThread {
            webView?.evaluateJavascript(
                "showError('${e.message?.replace("'", "\\'")}')",
                null
            )
        }
    }
}


@JavascriptInterface
fun verifyPayment(orderId: String, reference: String) {
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

    // First verify the order exists and belongs to the user
    firestore.collection("orders").document(orderId)
        .get()
        .addOnSuccessListener { document ->
            if (!document.exists()) {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "showError('Order not found')",
                        null
                    )
                }
                return@addOnSuccessListener
            }

            val order = document.data
            if (order?.get("userId") != user.uid) {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "showError('Unauthorized access to this order')",
                        null
                    )
                }
                return@addOnSuccessListener
            }

            // Update order with payment reference and status
            firestore.collection("orders").document(orderId)
                .update(
                    mapOf(
                        "paymentReference" to reference,
                        "paymentStatus" to "VERIFIED",
                        "status" to "PROCESSING",
                        "updatedAt" to Date()
                    )
                )
                .addOnSuccessListener {
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "handlePaymentSuccess()",
                            null
                        )
                    }
                }
                .addOnFailureListener { e ->
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "handlePaymentError('${e.message?.replace("'", "\\'")}')",
                            null
                        )
                    }
                }
        }
        .addOnFailureListener { e ->
            runOnUiThread {
                webView?.evaluateJavascript(
                    "handlePaymentError('${e.message?.replace("'", "\\'")}')",
                    null
                )
            }
        }
}

@JavascriptInterface
fun getOrderDetails(orderId: String) {
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

    firestore.collection("orders").document(orderId)
        .get()
        .addOnSuccessListener { document ->
            if (!document.exists()) {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "showError('Order not found')",
                        null
                    )
                }
                return@addOnSuccessListener
            }

            val order = document.data
            if (order?.get("userId") != user.uid) {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "showError('Unauthorized access to this order')",
                        null
                    )
                }
                return@addOnSuccessListener
            }

            val orderDetails = mapOf(
                "orderAmount" to (order["price"] as Double),
                "deliveryFee" to (order["deliveryFee"] as? Double ?: 0.0),
                "totalAmount" to ((order["price"] as Double) + (order["deliveryFee"] as? Double ?: 0.0)),
                "merchantNumber" to "0976000000" // To be Replaced with actual merchant number in production
            )

            runOnUiThread {
                webView?.evaluateJavascript(
                    "updateOrderDetails(${Gson().toJson(orderDetails)})",
                    null
                )
            }
        }
        .addOnFailureListener { e ->
            runOnUiThread {
                webView?.evaluateJavascript(
                    "showError('Failed to load order details: ${e.message?.replace("'", "\\'")}')",
                    null
                )
            }
        }
}

@JavascriptInterface
fun confirmCashOrder(orderId: String) {
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

    firestore.collection("orders").document(orderId)
        .update(
            mapOf(
                "paymentMethod" to "CASH",
                "paymentStatus" to "PENDING",
                "status" to "PROCESSING",
                "updatedAt" to Date()
            )
        )
        .addOnSuccessListener {
            runOnUiThread {
                webView?.evaluateJavascript(
                    """
                    showSuccess('Order confirmed successfully');
                    setTimeout(() => { window.location.href = 'orders.html'; }, 1500);
                    """.trimIndent(),
                    null
                )
            }
        }
        .addOnFailureListener { e ->
            runOnUiThread {
                webView?.evaluateJavascript(
                    "showError('Failed to confirm order: ${e.message?.replace("'", "\\'")}')",
                    null
                )
            }
        }
}


@JavascriptInterface
fun sendMessage(chatId: String, message: String) {
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

    // Get user's display name first
    firestore.collection("users").document(user.uid)
        .get()
        .addOnSuccessListener { userDoc ->
            val senderName = userDoc.getString("displayName") ?: "Unknown User"
            
            val messageData = hashMapOf(
                "senderId" to user.uid,
                "senderName" to senderName,
                "text" to message,
                "timestamp" to Date()
            )

            firestore.collection("chats").document(chatId)
                .collection("messages")
                .add(messageData)
                .addOnSuccessListener { 
                    // Update last message in chat document
                    firestore.collection("chats").document(chatId)
                        .update(
                            mapOf(
                                "lastMessage" to message,
                                "lastMessageTimestamp" to Date()
                            )
                        )
                }
                .addOnFailureListener { e ->
                    Log.e("Chat", "Failed to send message", e)
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "showError('Failed to send message: ${e.message?.replace("'", "\\'")}')",
                            null
                        )
                    }
                }
        }
}

private fun setupMessageListener(chatId: String) {
    firestore.collection("chats").document(chatId)
        .collection("messages")
        .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.ASCENDING)
        .addSnapshotListener { snapshots, e ->
            if (e != null) {
                Log.e("Chat", "Listen failed", e)
                return@addSnapshotListener
            }

            snapshots?.documentChanges?.forEach { dc ->
                val messageData = dc.document.data
                val currentUser = auth.currentUser
                
                val messageJson = JSONObject().apply {
                    put("text", messageData["text"])
                    put("timestamp", (messageData["timestamp"] as Date).time)
                    put("isSent", messageData["senderId"] == currentUser?.uid)
                }

                runOnUiThread {
                    webView?.evaluateJavascript(
                        "renderMessage(${messageJson})",
                        null
                    )
                }
            }
        }
}

private fun clearMessageListener() {
    // Implement this when you add support for cleaning up listeners
    // when the chat is closed
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