package com.ruan.uberareafilter.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ruan.uberareafilter.storage.ConfigRepository
import org.json.JSONArray
import org.json.JSONObject

class FavoritesActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var repository: ConfigRepository

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ConfigRepository(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(FavBridge(), "AndroidFavBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pushFavoritesToWeb()
                }
            }
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/favorites_map.html")
    }

    private fun pushFavoritesToWeb() {
        val list = repository.getFavorites()
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("passengerName", f.passengerName)
                    .put("rideTime", f.rideTimeFormatted)
                    .put("date", f.dateFormatted)
                    .put("pickup", f.pickupAddress)
                    .put("dropoff", f.dropoffAddress)
                    .put("pLat", f.pickupLat)
                    .put("pLng", f.pickupLng)
                    .put("dLat", f.dropoffLat)
                    .put("dLng", f.dropoffLng)
                    .put("price", f.priceText)
                    .put("rating", f.ratingText)
            )
        }
        val escaped = arr.toString().replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript("renderFavorites('$escaped');", null)
    }

    inner class FavBridge {
        @JavascriptInterface
        fun onDeleteFavorite(idStr: String) {
            val id = idStr.toLongOrNull() ?: return
            runOnUiThread {
                repository.deleteFavorite(id)
                pushFavoritesToWeb()
            }
        }

        @JavascriptInterface
        fun onNavigateTo(lat: Double, lng: Double, address: String) {
            runOnUiThread {
                try {
                    val query = if (address.isNotBlank()) {
                        Uri.encode("$address, Rio de Janeiro, RJ")
                    } else {
                        "$lat,$lng"
                    }
                    val navUri = Uri.parse("google.navigation:q=$query")
                    val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                        setPackage("com.google.android.apps.maps")
                    }
                    if (mapIntent.resolveActivity(packageManager) != null) {
                        startActivity(mapIntent)
                    } else {
                        val fallback = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$query")
                        )
                        startActivity(fallback)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@FavoritesActivity, "Erro ao abrir GPS", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
