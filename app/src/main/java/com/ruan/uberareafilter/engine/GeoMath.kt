package com.ruan.uberareafilter.engine

import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.model.ZoneShape
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Geometria pura (sem Android) usada pelo motor de localizacao.
 *
 * O conceito central e a PROFUNDIDADE (em metros) de um ponto dentro de uma zona:
 *  - positiva  = dentro da zona, a X metros da borda mais proxima
 *  - negativa  = fora da zona, a X metros da borda mais proxima
 *
 * Isso substitui o antigo "dentro OU ate 45 m fora" (tolerancia para FORA), que aprovava
 * pontos ja do lado de fora da divisa.
 */
object GeoMath {

    private const val M_PER_DEG_LAT = 110_574.0
    private const val M_PER_DEG_LNG_EQUATOR = 111_320.0

    /** Profundidade em metros do ponto na zona (positivo = dentro). */
    fun depthMeters(lat: Double, lng: Double, zone: MapZone): Double {
        return when (zone.shape) {
            ZoneShape.CIRCLE -> {
                if (zone.centerLat == 0.0 && zone.centerLng == 0.0) return Double.NEGATIVE_INFINITY
                zone.radiusMeters - AreaEvaluator.haversineKm(lat, lng, zone.centerLat, zone.centerLng) * 1000.0
            }
            ZoneShape.POLYGON -> polygonDepthMeters(lat, lng, zone.points)
        }
    }

    fun polygonDepthMeters(lat: Double, lng: Double, polygon: List<Pair<Double, Double>>): Double {
        if (polygon.size < 3) return Double.NEGATIVE_INFINITY
        val mLng = M_PER_DEG_LNG_EQUATOR * cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        // Projeta tudo em metros com origem no proprio ponto (erro desprezivel na escala de bairros)
        var inside = false
        var minDist2 = Double.MAX_VALUE
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = (polygon[i].second - lng) * mLng
            val yi = (polygon[i].first - lat) * M_PER_DEG_LAT
            val xj = (polygon[j].second - lng) * mLng
            val yj = (polygon[j].first - lat) * M_PER_DEG_LAT

            // Ray casting com o ponto na origem
            if ((yi > 0.0) != (yj > 0.0)) {
                val xCross = xi + (0.0 - yi) * (xj - xi) / (yj - yi)
                if (xCross > 0.0) inside = !inside
            }

            // Distancia da origem ao segmento (xi,yi)-(xj,yj)
            val dx = xj - xi
            val dy = yj - yi
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 1e-9) 0.0 else ((-xi) * dx + (-yi) * dy) / len2
            val tc = min(1.0, max(0.0, t))
            val px = xi + tc * dx
            val py = yi + tc * dy
            val d2 = px * px + py * py
            if (d2 < minDist2) minDist2 = d2
            j = i
        }
        val dist = sqrt(minDist2)
        return if (inside) dist else -dist
    }

    /** Melhor (maior) profundidade entre as zonas e a zona correspondente. */
    fun bestZoneDepth(lat: Double, lng: Double, zones: List<MapZone>): Pair<MapZone, Double>? {
        var best: Pair<MapZone, Double>? = null
        for (z in zones) {
            val d = depthMeters(lat, lng, z)
            if (best == null || d > best.second) best = z to d
        }
        return best
    }

    /**
     * O ponto esta pelo menos [radiusMeters] dentro da UNIAO das zonas?
     * (zonas vizinhas desenhadas lado a lado contam como uma area so: um ponto na fronteira
     * entre duas zonas verdes esta dentro, nao "na divisa").
     * Testa o centro e dois aneis (32 + 16 pontos) — pega tambem frestas entre poligonos desenhados a mao.
     */
    fun unionDepthAtLeast(lat: Double, lng: Double, zones: List<MapZone>, radiusMeters: Double): Boolean {
        if (zones.isEmpty()) return false
        var best = Double.NEGATIVE_INFINITY
        for (z in zones) {
            val d = depthMeters(lat, lng, z)
            if (d > best) best = d
        }
        if (best < 0.0) return false
        if (best >= radiusMeters || radiusMeters <= 0.0) return true
        val mLng = M_PER_DEG_LNG_EQUATOR * cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        for ((ring, count) in listOf(radiusMeters to 32, radiusMeters * 0.5 to 16)) {
            for (k in 0 until count) {
                val ang = 2.0 * Math.PI * k / count
                val pLat = lat + (ring * kotlin.math.cos(ang)) / M_PER_DEG_LAT
                val pLng = lng + (ring * kotlin.math.sin(ang)) / mLng
                if (zones.none { depthMeters(pLat, pLng, it) >= 0.0 }) return false
            }
        }
        return true
    }

    /** Distancia de Levenshtein limitada (retorna limit+1 quando passar do limite). */
    fun boundedLevenshtein(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (k in 1..b.length) {
                val cost = if (a[i - 1] == b[k - 1]) 0 else 1
                cur[k] = min(min(cur[k - 1] + 1, prev[k] + 1), prev[k - 1] + cost)
                if (cur[k] < rowMin) rowMin = cur[k]
            }
            if (rowMin > limit) return limit + 1
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    /**
     * Chave de validade do indice de ruas de uma zona: muda se ESSA zona mudar ou se qualquer outra
     * zona (verde ou vermelha) for criada/apagada/redesenhada — a classificacao "interna" depende de todas.
     */
    fun indexKeyFor(zone: MapZone, allZones: List<MapZone>): String {
        val setHash = allZones
            .sortedBy { it.id }
            .joinToString(";") { "${it.id}:${it.mode.name}:${zoneGeometryHash(it)}" }
            .hashCode()
            .toString(16)
        return zoneGeometryHash(zone) + "|" + setHash
    }

    /** Hash estavel da geometria da zona (para invalidar o indice de ruas quando a zona for redesenhada). */
    fun zoneGeometryHash(zone: MapZone): String {
        val sb = StringBuilder()
        sb.append(zone.shape.name).append('|')
        if (zone.shape == ZoneShape.CIRCLE) {
            sb.append(String.format(java.util.Locale.US, "%.5f,%.5f,%.0f", zone.centerLat, zone.centerLng, zone.radiusMeters))
        } else {
            zone.points.forEach { (la, ln) ->
                sb.append(String.format(java.util.Locale.US, "%.5f,%.5f;", la, ln))
            }
        }
        return sb.toString().hashCode().toString(16)
    }
}
