package com.ruan.uberareafilter.map

import com.ruan.uberareafilter.engine.TrafficCheck
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Tempo da corrida com o transito de agora: Amazon Location (AWS, modo moto "Scooter") ou Google Routes,
 * conforme a chave colada (AWS comeca com "v1.public.").
 * Manda SO coordenadas (motorista -> embarque -> destino), nada de nome ou endereco.
 * Qualquer falha devolve null: o app segue com a estimativa da 99 (nunca perde a corrida por causa disto).
 */
object TrafficRouteClient {

    private const val URL_ROUTES = "https://routes.googleapis.com/directions/v2:computeRoutes"
    // A chave da AWS foi criada em sa-east-1 (Sao Paulo): o endereco precisa ser da mesma regiao
    private const val URL_AWS = "https://routes.geo.sa-east-1.amazonaws.com/v2/routes"

    data class Result(val minutes: Double, val travelMode: String, val pickupKm: Double? = null, val rideKm: Double? = null)

    /** Modo que deu certo na ultima vez (o Google pode nao ter "moto" para a regiao): evita 2 chamadas. */
    @Volatile
    private var workingMode: String? = null

    fun minutesWithTraffic(
        apiKey: String,
        driver: Pair<Double, Double>,
        pickup: Pair<Double, Double>,
        dropoff: Pair<Double, Double>,
        timeoutMs: Int,
        car: Boolean = false
    ): Result? {
        if (TrafficCheck.isAwsKey(apiKey)) {
            val mode = if (car) "Car" else "Scooter"
            val r = runCatching { callAws(apiKey.trim(), driver, pickup, dropoff, timeoutMs, mode) }.getOrNull() ?: return null
            val minutes = TrafficCheck.parseAwsDurationMinutes(r) ?: return null
            val legs = TrafficCheck.parseAwsLegKm(r)
            return Result(minutes, "AWS_" + mode.uppercase(), legs.getOrNull(0)?.takeIf { legs.size == 2 }, legs.getOrNull(1)?.takeIf { legs.size == 2 })
        }
        // Carro: DRIVE. Moto: TWO_WHEELER; se o Google nao tiver moto para a regiao, tenta carro
        val modes = if (car) listOf("DRIVE") else workingMode?.let { listOf(it) } ?: listOf("TWO_WHEELER", "DRIVE")
        for (mode in modes) {
            val minutes = runCatching { call(apiKey, driver, pickup, dropoff, mode, timeoutMs) }.getOrNull()
            if (minutes != null) {
                if (!car) workingMode = mode
                return Result(minutes, mode)
            }
        }
        return null
    }

    private fun lngLat(p: Pair<Double, Double>) = String.format(Locale.US, "[%.6f,%.6f]", p.second, p.first)

    private fun callAws(
        apiKey: String,
        driver: Pair<Double, Double>,
        pickup: Pair<Double, Double>,
        dropoff: Pair<Double, Double>,
        timeoutMs: Int,
        mode: String
    ): String? {
        val body = """{"Origin":${lngLat(driver)},"Destination":${lngLat(dropoff)},""" +
            """"Waypoints":[{"Position":${lngLat(pickup)}}],"TravelMode":"$mode","DepartNow":true,"LegAdditionalFeatures":["Summary"]}"""
        val url = URL(URL_AWS + "?key=" + java.net.URLEncoder.encode(apiKey, "UTF-8"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun ll(p: Pair<Double, Double>) =
        String.format(Locale.US, """{"location":{"latLng":{"latitude":%.6f,"longitude":%.6f}}}""", p.first, p.second)

    private fun call(
        apiKey: String,
        driver: Pair<Double, Double>,
        pickup: Pair<Double, Double>,
        dropoff: Pair<Double, Double>,
        mode: String,
        timeoutMs: Int
    ): Double? {
        val body = """{"origin":${ll(driver)},"destination":${ll(dropoff)},"intermediates":[${ll(pickup)}],""" +
            """"travelMode":"$mode","routingPreference":"TRAFFIC_AWARE"}"""
        val conn = (URL(URL_ROUTES).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Goog-Api-Key", apiKey)
            setRequestProperty("X-Goog-FieldMask", "routes.duration")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return TrafficCheck.parseDurationMinutes(text)
        } finally {
            conn.disconnect()
        }
    }
}
