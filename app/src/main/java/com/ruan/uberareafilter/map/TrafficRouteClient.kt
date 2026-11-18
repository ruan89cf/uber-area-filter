package com.ruan.uberareafilter.map

import com.ruan.uberareafilter.engine.TrafficCheck
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Tempo da corrida com o transito de agora pela Google Routes API (chave do proprio motorista).
 * Manda SO coordenadas (motorista -> embarque -> destino), nada de nome ou endereco.
 * Qualquer falha devolve null: o app segue com a estimativa da 99 (nunca perde a corrida por causa disto).
 */
object TrafficRouteClient {

    private const val URL_ROUTES = "https://routes.googleapis.com/directions/v2:computeRoutes"

    data class Result(val minutes: Double, val travelMode: String)

    /** Modo que deu certo na ultima vez (o Google pode nao ter "moto" para a regiao): evita 2 chamadas. */
    @Volatile
    private var workingMode: String? = null

    fun minutesWithTraffic(
        apiKey: String,
        driver: Pair<Double, Double>,
        pickup: Pair<Double, Double>,
        dropoff: Pair<Double, Double>,
        timeoutMs: Int
    ): Result? {
        // Moto primeiro (TWO_WHEELER); se o Google nao tiver moto para a regiao, tenta carro
        val modes = workingMode?.let { listOf(it) } ?: listOf("TWO_WHEELER", "DRIVE")
        for (mode in modes) {
            val minutes = runCatching { call(apiKey, driver, pickup, dropoff, mode, timeoutMs) }.getOrNull()
            if (minutes != null) {
                workingMode = mode
                return Result(minutes, mode)
            }
        }
        return null
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
