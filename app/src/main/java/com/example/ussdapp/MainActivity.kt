package com.techtonic.ussdapp

import android.os.Bundle
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
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()

        // Enable offline persistence
        val settings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)
            .build()
        firestore.firestoreSettings = settings
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
            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener {
                    runOnUiThread {
                        webView?.loadUrl("file:///android_asset/home.html")
                    }
                }
                .addOnFailureListener { e ->
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
            auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener { result ->
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
                            runOnUiThread {
                                webView?.loadUrl("file:///android_asset/home.html")
                            }
                        }
                }
                .addOnFailureListener { e ->
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
            }
        }

        // Product Methods
        @JavascriptInterface
        fun getProducts(type: String = "all") {
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
                    
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "renderProducts(${Gson().toJson(products)})",
                            null
                        )
                    }
                }
        }

        @JavascriptInterface
        fun addProduct(productData: String) {
            try {
                val data = JSONObject(productData)
                val user = auth.currentUser

                if (data.has("image")) {
                    val imageData = data.getString("image")
                        .replace("data:image/jpeg;base64,", "")
                        .replace("data:image/png;base64,", "")

                    val imageBytes = android.util.Base64.decode(imageData, android.util.Base64.DEFAULT)
                    val imageRef = storage.reference.child("product_images/${UUID.randomUUID()}.jpg")

                    imageRef.putBytes(imageBytes)
                        .continueWithTask { task ->
                            if (!task.isSuccessful) throw task.exception!!
                            imageRef.downloadUrl
                        }
                        .addOnSuccessListener { uri ->
                            val product = hashMapOf(
                                "name" to data.getString("name"),
                                "price" to data.getDouble("price"),
                                "description" to data.getString("description"),
                                "imageUrl" to uri.toString(),
                                "farmerId" to user!!.uid,
                                "createdAt" to Date()
                            )

                            firestore.collection("products").add(product)
                                .addOnSuccessListener {
                                    runOnUiThread {
                                        webView?.loadUrl("file:///android_asset/home.html")
                                    }
                                }
                        }
                }
            } catch (e: Exception) {
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
            
            firestore.collection("users").document(user!!.uid)
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
                            
                            runOnUiThread {
                                webView?.evaluateJavascript(
                                    "renderOrders(${Gson().toJson(orders)})",
                                    null
                                )
                            }
                        }
                }
        }

        @JavascriptInterface
        fun placeOrder(productId: String) {
            val user = auth.currentUser

            firestore.collection("products").document(productId)
                .get()
                .addOnSuccessListener { document ->
                    val product = document.data
                    val order = hashMapOf(
                        "productId" to productId,
                        "productName" to product!!["name"],
                        "buyerId" to user!!.uid,
                        "farmerId" to product["farmerId"],
                        "price" to product["price"],
                        "status" to "PENDING",
                        "createdAt" to Date()
                    )

                    firestore.collection("orders").add(order)
                        .addOnSuccessListener {
                            runOnUiThread {
                                webView?.loadUrl("file:///android_asset/orders.html")
                            }
                        }
                }
        }

        @JavascriptInterface
        fun updateOrderStatus(orderId: String, status: String) {
            firestore.collection("orders").document(orderId)
                .update("status", status)
                .addOnSuccessListener {
                    getOrders() // Refresh orders list
                }
        }

        // Chat Methods
        @JavascriptInterface
        fun initializeChat(otherUserId: String) {
            val user = auth.currentUser
            val chatId = listOf(user!!.uid, otherUserId).sorted().joinToString("-")
            
            firestore.collection("chats").document(chatId)
                .collection("messages")
                .orderBy("timestamp")
                .addSnapshotListener { snapshot, e ->
                    if (e != null) return@addSnapshotListener

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
            
            val messageData = hashMapOf(
                "text" to message,
                "senderId" to user!!.uid,
                "timestamp" to Date()
            )

            firestore.collection("chats").document(chatId)
                .collection("messages")
                .add(messageData)
        }
    }
}