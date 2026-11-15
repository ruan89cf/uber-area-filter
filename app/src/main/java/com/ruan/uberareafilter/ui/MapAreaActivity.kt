package com.ruan.uberareafilter.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ruan.uberareafilter.engine.AreaEvaluator
import com.ruan.uberareafilter.engine.TextNormalizer
import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.model.ZoneMode
import com.ruan.uberareafilter.model.ZoneShape
import com.ruan.uberareafilter.storage.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.cos

class MapAreaActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var repository: ConfigRepository

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ConfigRepository(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(MapBridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val config = repository.load()
                    if (config.mapZones.isNotEmpty()) {
                        val zonesJson = repository.serializeMapZones(config.mapZones).toString()
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                        evaluateJavascript("loadExistingZones('$zonesJson');", null)
                    } else if (config.polygonPoints.isNotEmpty()) {
                        val arr = JSONArray()
                        config.polygonPoints.forEach { (lat, lng) ->
                            arr.put(JSONArray().put(lat).put(lng))
                        }
                        evaluateJavascript("loadExistingPolygon('${arr}');", null)
                    }
                }
            }
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/map_picker.html")
    }

    inner class MapBridge {
        @JavascriptInterface
        fun onError(msg: String) {
            runOnUiThread {
                Toast.makeText(this@MapAreaActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun onZonesSelected(zonesJson: String) {
            runOnUiThread {
                processSelectedZones(zonesJson)
            }
        }

        @JavascriptInterface
        fun onPolygonSelected(pointsJson: String) {
            runOnUiThread {
                val pts = parsePolygonJson(pointsJson)
                if (pts.size >= 3) {
                    val zone = MapZone(
                        id = System.currentTimeMillis(),
                        name = "Área Permitida 1",
                        mode = ZoneMode.ALLOWED,
                        shape = ZoneShape.POLYGON,
                        points = pts
                    )
                    processZonesList(listOf(zone))
                }
            }
        }
    }

    private fun processSelectedZones(zonesJson: String) {
        val zones = repository.parseMapZones(zonesJson)
        if (zones.isEmpty()) {
            Toast.makeText(this, "Adicione pelo menos uma zona no mapa", Toast.LENGTH_SHORT).show()
            return
        }
        processZonesList(zones)
    }

    private fun processZonesList(zones: List<MapZone>) {
        val primaryPolygon = zones
            .firstOrNull { it.mode == ZoneMode.ALLOWED && it.shape == ZoneShape.POLYGON && it.points.size >= 3 }
            ?.points
            .orEmpty()

        // 1. Salva as zonas imediatamente
        val initialConfig = repository.load().copy(
            mapZones = zones,
            polygonPoints = primaryPolygon
        )
        repository.save(initialConfig)

        val allowedZones = zones.filter { it.mode == ZoneMode.ALLOWED }
        val blockedZones = zones.filter { it.mode == ZoneMode.BLOCKED }

        Toast.makeText(
            this,
            "Varrendo ruas de ${allowedZones.size} zona(s) permitida(s) (~5s)...",
            Toast.LENGTH_LONG
        ).show()

        lifecycleScope.launch {
            val streets = if (allowedZones.isNotEmpty()) {
                withTimeoutOrNull(12000L) {
                    fetchStreetsForAllowedZones(allowedZones)
                }.orEmpty()
            } else {
                emptyList()
            }

            val latestConfig = repository.load()
            val cleanedExisting = latestConfig.allowedKeywords.filterNot {
                TextNormalizer.normalize(it).startsWith("estacao ")
            }
            val mergedStreets = if (streets.isNotEmpty()) {
                streets
            } else {
                cleanedExisting
            }

            repository.save(
                latestConfig.copy(
                    mapZones = zones,
                    polygonPoints = primaryPolygon,
                    allowedKeywords = mergedStreets
                )
            )

            val resultIntent = Intent().apply {
                putExtra(EXTRA_STREETS_COUNT, mergedStreets.size)
                putExtra(EXTRA_POINTS_COUNT, zones.size)
                putExtra(EXTRA_ALLOWED_ZONES_COUNT, allowedZones.size)
                putExtra(EXTRA_BLOCKED_ZONES_COUNT, blockedZones.size)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun parsePolygonJson(json: String): List<Pair<Double, Double>> {
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until arr.length()) {
                val pt = arr.getJSONArray(i)
                list.add(pt.getDouble(0) to pt.getDouble(1))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Varre TODAS as Zonas Permitidas (Poligonos e Circulos) combinando:
     * 1) API Vetorial OpenStreetMap Overpass (captura 100% de todas as ruas/travessas de cada quarteirao, inclusive ruas curtas como R. Pirangi)
     * 2) Malha distribuida por zona no Geocoder nativo do Android (garante que todas as 5+ zonas sejam varridas por igual)
     */
    private suspend fun fetchStreetsForAllowedZones(
        allowedZones: List<MapZone>
    ): List<String> = withContext(Dispatchers.IO) {
        if (allowedZones.isEmpty()) return@withContext emptyList()

        val names = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)

        // 1. Consulta vetorial completa no OpenStreetMap Overpass (pega 100% das vias dentro de todos os poligonos e circulos)
        val overpassStreets = fetchStreetsFromOverpass(allowedZones)
        overpassStreets.forEach { s ->
            if (s.length >= 3 && !TextNormalizer.normalize(s).startsWith("estacao ")) {
                names.add(s)
            }
        }

        // 2. Amostragem equilibrada por zona no Geocoder do Android (para complementar ou caso sem internet p/ Overpass)
        if (Geocoder.isPresent()) {
            val perZoneQuota = (180 / allowedZones.size.coerceAtLeast(1)).coerceIn(25, 80)
            val samplePoints = mutableListOf<Pair<Double, Double>>()

            for (zone in allowedZones) {
                val zonePoints = mutableListOf<Pair<Double, Double>>()
                when (zone.shape) {
                    ZoneShape.POLYGON -> {
                        val poly = zone.points
                        if (poly.size < 3) continue
                        zonePoints.addAll(poly)

                        for (idx in poly.indices) {
                            val a = poly[idx]
                            val b = poly[(idx + 1) % poly.size]
                            zonePoints.add(((a.first + b.first) / 2.0) to ((a.second + b.second) / 2.0))
                        }

                        val minLat = poly.minOf { it.first }
                        val maxLat = poly.maxOf { it.first }
                        val minLng = poly.minOf { it.second }
                        val maxLng = poly.maxOf { it.second }

                        val steps = 10
                        for (i in 0..steps) {
                            for (j in 0..steps) {
                                val lat = minLat + (maxLat - minLat) * (i.toDouble() / steps)
                                val lng = minLng + (maxLng - minLng) * (j.toDouble() / steps)
                                if (AreaEvaluator.isPointInOrNearPolygon(lat, lng, poly, toleranceMeters = 15.0)) {
                                    zonePoints.add(lat to lng)
                                }
                            }
                        }
                    }

                    ZoneShape.CIRCLE -> {
                        val cLat = zone.centerLat
                        val cLng = zone.centerLng
                        val rMeters = zone.radiusMeters.coerceIn(60.0, 15000.0)
                        zonePoints.add(cLat to cLng)

                        val dLat = rMeters / 111320.0
                        val dLng = rMeters / (111320.0 * cos(Math.toRadians(cLat)).coerceAtLeast(0.2))
                        val steps = 8
                        for (i in -steps..steps step 2) {
                            for (j in -steps..steps step 2) {
                                val lat = cLat + dLat * (i.toDouble() / steps)
                                val lng = cLng + dLng * (j.toDouble() / steps)
                                if (AreaEvaluator.haversineKm(cLat, cLng, lat, lng) * 1000.0 <= rMeters + 15.0) {
                                    zonePoints.add(lat to lng)
                                }
                            }
                        }
                    }
                }
                samplePoints.addAll(zonePoints.distinct().take(perZoneQuota))
            }

            val geocoder = Geocoder(this@MapAreaActivity, Locale("pt", "BR"))
            for (batch in samplePoints.distinct().chunked(25)) {
                coroutineScope {
                    batch.map { (lat, lng) ->
                        async {
                            reverseGeocodeStreetOnly(geocoder, lat, lng)
                        }
                    }.awaitAll().flatten().forEach { name ->
                        if (name.length >= 3) names.add(name)
                    }
                }
            }
        }

        names.toList()
    }

    /**
     * Busca 100% dos logradouros ("highway" com "name") que cruzam ou estao dentro dos poligonos/circulos
     * usando a base vetorial do OpenStreetMap (captura inclusive ruas de 1 unico quarteirao como Rua Pirangi).
     */
    private fun fetchStreetsFromOverpass(allowedZones: List<MapZone>): List<String> {
        val clauses = mutableListOf<String>()
        for (zone in allowedZones) {
            when (zone.shape) {
                ZoneShape.POLYGON -> {
                    if (zone.points.size >= 3) {
                        val polyStr = zone.points.joinToString(" ") { (lat, lng) ->
                            String.format(Locale.US, "%.6f %.6f", lat, lng)
                        }
                        clauses.add("""way(poly:"$polyStr")["highway"]["name"];""")
                    }
                }
                ZoneShape.CIRCLE -> {
                    if (zone.centerLat != 0.0 || zone.centerLng != 0.0) {
                        val r = (zone.radiusMeters + 25.0).coerceIn(50.0, 15000.0).toInt()
                        val lat = String.format(Locale.US, "%.6f", zone.centerLat)
                        val lng = String.format(Locale.US, "%.6f", zone.centerLng)
                        clauses.add("""way(around:$r,$lat,$lng)["highway"]["name"];""")
                    }
                }
            }
        }
        if (clauses.isEmpty()) return emptyList()

        val query = "[out:json][timeout:7];(${clauses.joinToString("")});out tags;"
        val endpoints = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )

        for (endpoint in endpoints) {
            try {
                val url = URL("$endpoint?data=${URLEncoder.encode(query, "UTF-8")}")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4500
                    readTimeout = 5500
                    setRequestProperty("User-Agent", "UberAreaFilterApp/2.0")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val elements = json.optJSONArray("elements") ?: continue
                    val result = mutableListOf<String>()
                    for (i in 0 until elements.length()) {
                        val el = elements.optJSONObject(i) ?: continue
                        val tags = el.optJSONObject("tags") ?: continue
                        val name = tags.optString("name", "").trim()
                        if (name.length >= 3) {
                            result.add(name)
                        }
                    }
                    if (result.isNotEmpty()) return result
                }
            } catch (_: Exception) {
                // Tenta o proximo espelho Overpass ou segue para o Geocoder
            }
        }
        return emptyList()
    }

    private suspend fun reverseGeocodeStreetOnly(
        geocoder: Geocoder,
        lat: Double,
        lng: Double
    ): List<String> {
        val addresses: List<Address> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                try {
                    geocoder.getFromLocation(lat, lng, 5) { list ->
                        if (cont.isActive) cont.resume(list)
                    }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lng, 5).orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }

        val found = mutableListOf<String>()
        for (addr in addresses) {
            // Apenas logradouros reais (Ruas, Avenidas, Estradas, Travessas, Pracas) — ignora Estacoes/POIs!
            val street = addr.thoroughfare?.trim()
            if (!street.isNullOrEmpty() && !TextNormalizer.normalize(street).startsWith("estacao ")) {
                found.add(street)
            }
        }
        return found
    }

    companion object {
        const val EXTRA_STREETS_COUNT = "extra_streets_count"
        const val EXTRA_POINTS_COUNT = "extra_points_count"
        const val EXTRA_ALLOWED_ZONES_COUNT = "extra_allowed_zones_count"
        const val EXTRA_BLOCKED_ZONES_COUNT = "extra_blocked_zones_count"
    }
}
