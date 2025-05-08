package com.techtonic.ussdapp

import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Firebase
        FirebaseApp.initializeApp(this)
        initializeFirebase()
        setupWebView()
    }

    private fun initializeFirebase() {
        try {
            auth = FirebaseAuth.getInstance()
            firestore = FirebaseFirestore.getInstance()
            storage = FirebaseStorage.getInstance()
            Log.d("Firebase", "Firebase Storage initialized successfully")

            // Enable offline persistence
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .build()
            firestore.firestoreSettings = settings
        } catch (e: Exception) {
            Log.e("Firebase", "Error initializing Firebase: ${e.message}", e)
        }
    }

    private fun setupWebView() {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            WebView.setWebContentsDebuggingEnabled(true)

            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()

            addJavascriptInterface(WebAppInterface(), "AndroidInterface")
            loadUrl("file:///android_asset/login.html")
        }
        setContentView(webView)
    }

    inner class WebAppInterface {
        // Authentication Methods
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
                            "showError('${e.message}')",
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
                                    "showError('Failed to save user data: ${e.message}')",
                                    null
                                )
                            }
                        }
                }
                .addOnFailureListener { e ->
                    Log.e("Auth", "Registration failed: ${e.message}", e)
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "showError('${e.message}')",
                            null
                        )
                    }
                }
        }

        @JavascriptInterface
        fun getCurrentUser() {
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
                    }
            }
        }

        // Product Methods
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
                }
        }

        @JavascriptInterface
        fun addProduct(productData: String) {
            try {
                Log.d("ProductCreation", "Received product data: $productData")
                
                val data = JSONObject(productData)
                val user = auth.currentUser
                
                if (user == null) {
                    Log.e("ProductCreation", "User not authenticated")
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "showError('Please log in to create products')",
                            null
                        )
                    }
                    return
                }

                if (!data.has("image")) {
                    Log.e("ProductCreation", "No image data provided")
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "showError('Please provide an image')",
                            null
                        )
                    }
                    return
                }

                val imageData = data.getString("image")
                    .split(",").last() // Get the base64 part after the comma
                    .trim() // Remove any whitespace

                Log.d("ProductCreation", "Processing image data of length: ${imageData.length}")

                val imageBytes = android.util.Base64.decode(imageData, android.util.Base64.DEFAULT)
                val imageRef = storage.reference.child("product_images/${UUID.randomUUID()}.jpg")

                imageRef.putBytes(imageBytes)
                    .addOnSuccessListener { taskSnapshot ->
                        Log.d("ProductCreation", "Image upload successful")
                        imageRef.downloadUrl.addOnSuccessListener { uri ->
                            val product = hashMapOf(
                                "name" to data.getString("name"),
                                "price" to data.getDouble("price"),
                                "description" to data.getString("description"),
                                "imageUrl" to uri.toString(),
                                "farmerId" to user.uid,
                                "farmerName" to user.displayName,
                                "createdAt" to Date()
                            )

                            firestore.collection("products").add(product)
                                .addOnSuccessListener {
                                    Log.d("ProductCreation", "Product created successfully")
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
                        Log.e("ProductCreation", "Failed to upload image: ${e.message}", e)
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "showError('Failed to upload image: ${e.message}')",
                                null
                            )
                        }
                    }
            } catch (e: Exception) {
                Log.e("ProductCreation", "Error processing product data: ${e.message}", e)
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "showError('${e.message}')",
                        null
                    )
                }
            }
        }

        // Order Methods
        @JavascriptInterface
        fun getOrders() {
            val user = auth.currentUser
            if (user == null) {
                Log.e("Orders", "User not authenticated")
                return
            }
            
            firestore.collection("users").document(user.uid)
                .get()
                .addOnSuccessListener { userDoc ->
                    val userType = userDoc.getString("userType")
                    val query = when (userType) {
                        "farmer" -> firestore.collection("orders")
                            .whereEqualTo("farmerId", user.uid)
                        else -> firestore.collection("orders")
                            .whereEqualTo("buyerId", user.uid)
                    }

                    query.get()
                        .addOnSuccessListener { documents ->
                            val orders = documents.map { doc ->
                                val data = doc.data
                                data["id"] = doc.id
                                data
                            }
                            Log.d("Orders", "Retrieved ${orders.size} orders")
                            
                            runOnUiThread {
                                webView?.evaluateJavascript(
                                    "renderOrders(${Gson().toJson(orders)})",
                                    null
                                )
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e("Orders", "Failed to fetch orders: ${e.message}", e)
                        }
                }
                .addOnFailureListener { e ->
                    Log.e("Orders", "Failed to get user type: ${e.message}", e)
                }
        }

        @JavascriptInterface
        fun placeOrder(productId: String) {
            val user = auth.currentUser
            if (user == null) {
                Log.e("Orders", "User not authenticated")
                return
            }

            firestore.collection("products").document(productId)
                .get()
                .addOnSuccessListener { document ->
                    val product = document.data
                    val order = hashMapOf(
                        "productId" to productId,
                        "productName" to product!!["name"],
                        "buyerId" to user.uid,
                        "farmerId" to product["farmerId"],
                        "price" to product["price"],
                        "status" to "PENDING",
                        "createdAt" to Date()
                    )

                    firestore.collection("orders").add(order)
                        .addOnSuccessListener {
                            Log.d("Orders", "Order placed successfully")
                            runOnUiThread {
                                webView?.loadUrl("file:///android_asset/orders.html")
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e("Orders", "Failed to place order: ${e.message}", e)
                            runOnUiThread {
                                webView?.evaluateJavascript(
                                    "showError('Failed to place order: ${e.message}')",
                                    null
                                )
                            }
                        }
                }
                .addOnFailureListener { e ->
                    Log.e("Orders", "Failed to get product details: ${e.message}", e)
                }
        }

        @JavascriptInterface
        fun updateOrderStatus(orderId: String, status: String) {
            Log.d("Orders", "Updating order $orderId to status: $status")
            firestore.collection("orders").document(orderId)
                .update("status", status)
                .addOnSuccessListener {
                    Log.d("Orders", "Order status updated successfully")
                    getOrders() // Refresh orders list
                }
                .addOnFailureListener { e ->
                    Log.e("Orders", "Failed to update order status: ${e.message}", e)
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "showError('Failed to update order status: ${e.message}')",
                            null
                        )
                    }
                }
        }

        // Chat Methods
        @JavascriptInterface
        fun initializeChat(otherUserId: String) {
            val user = auth.currentUser
            if (user == null) {
                Log.e("Chat", "User not authenticated")
                return
            }

            val chatId = listOf(user.uid, otherUserId).sorted().joinToString("-")
            
            firestore.collection("chats").document(chatId)
                .collection("messages")
                .orderBy("timestamp")
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        Log.e("Chat", "Failed to listen to messages: ${e.message}", e)
                        return@addSnapshotListener
                    }

                    snapshot?.documentChanges?.forEach { change ->
                        val message = change.document.data
                        message["isSent"] = message["senderId"] == user.uid
                        
                        runOnUiThread {
                            webView?.evaluateJavascript(
                                "renderMessage(${Gson().toJson(message)})",
                                null
                            )
                        }
                    }
                }
        }

        @JavascriptInterface
        fun sendMessage(chatId: String, message: String) {
            val user = auth.currentUser
            if (user == null) {
                Log.e("Chat", "User not authenticated")
                return
            }
            
            val messageData = hashMapOf(
                "text" to message,
                "senderId" to user.uid,
                "timestamp" to Date()
            )

            firestore.collection("chats").document(chatId)
                .collection("messages")
                .add(messageData)
                .addOnSuccessListener {
                    Log.d("Chat", "Message sent successfully")
                }
                .addOnFailureListener { e ->
                    Log.e("Chat", "Failed to send message: ${e.message}", e)
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "showError('Failed to send message: ${e.message}')",
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
                        "showError('Failed to logout: ${e.message}')",
                        null
                    )
                }
            }
        }
    }
}