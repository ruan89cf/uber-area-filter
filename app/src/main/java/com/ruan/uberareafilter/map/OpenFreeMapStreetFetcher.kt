package com.ruan.uberareafilter.map

import com.ruan.uberareafilter.engine.AddressParser
import com.ruan.uberareafilter.engine.AreaEvaluator
import com.ruan.uberareafilter.engine.GeoMath
import com.ruan.uberareafilter.engine.TextNormalizer
import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.model.ZoneShape
import com.ruan.uberareafilter.model.ZoneStreetIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Extrai 100% dos nomes de ruas/avenidas/travessas dentro das zonas demarcadas
 * baixando apenas os tiles vetoriais z14 (Mapbox Vector Tile .pbf) do OpenFreeMap.
 *
 * Em testes reais no RJ (5 zonas em Olaria/Ramos/Bonsucesso/Penha/Cordovil):
 * - Overpass publico: timeout / HTTP 502 / 504 (0 ruas)
 * - OpenFreeMap PBF z14: ~950ms total, 261 ruas unicas (incluindo Rua Pirangi)
 */
object OpenFreeMapStreetFetcher {

    private const val TILE_JSON_URL = "https://tiles.openfreemap.org/planet"
    private const val USER_AGENT = "UberAreaFilter/2.0 (Android)"
    private const val ZOOM = 14
    private const val MAX_TILES = 28

    private val excludedClasses = setOf("rail", "ferry", "transit", "busway")

    @Volatile
    private var cachedTemplateUrl: String? = null
    @Volatile
    private var cachedTemplateAtMs: Long = 0L

    data class ZoneBounds(
        val zone: MapZone,
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double
    )

    suspend fun fetchStreets(allowedZones: List<MapZone>): List<String> {
        val perZone = fetchStreetsPerZone(allowedZones)
        val merged = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        perZone.values.forEach { merged.addAll(it) }
        return merged.toList()
    }

    /**
     * Extrai as ruas e pracas de cada Zona Permitida ("caixa") separadamente e retorna Mapa zoneId -> Lista de Ruas,
     * permitindo tambem identificar corridas entre zonas mesmo na listagem offline.
     */
    suspend fun fetchStreetsPerZone(allowedZones: List<MapZone>): Map<Long, List<String>> = withContext(Dispatchers.IO) {
        if (allowedZones.isEmpty()) return@withContext emptyMap()

        val template = resolveTileTemplate() ?: return@withContext emptyMap()
        val zoneBounds = allowedZones.mapNotNull { buildZoneBounds(it) }
        if (zoneBounds.isEmpty()) return@withContext emptyMap()

        val tiles = collectNeededTiles(zoneBounds).take(MAX_TILES)
        if (tiles.isEmpty()) return@withContext emptyMap()

        val rawTiles = coroutineScope {
            tiles.chunked(6).flatMap { batch ->
                batch.map { (tx, ty) ->
                    async {
                        val url = template
                            .replace("{z}", ZOOM.toString())
                            .replace("{x}", tx.toString())
                            .replace("{y}", ty.toString())
                        downloadBytes(url)?.let { bytes -> Triple(tx, ty, bytes) }
                    }
                }.awaitAll().filterNotNull()
            }
        }

        val perZoneSet = zoneBounds.associate { it.zone.id to sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER) }
        for ((tx, ty, pbfBytes) in rawTiles) {
            extractStreetsFromTilePerZone(pbfBytes, ZOOM, tx, ty, zoneBounds, perZoneSet)
        }
        perZoneSet.mapValues { (_, set) -> set.toList() }
    }

    // ------------------------------------------------------------------
    // Indice de ruas INTERNAS x de DIVISA (base da aprovacao so pelo nome)
    // ------------------------------------------------------------------

    private const val INDEX_MARGIN_DEG = 0.0045 // ~500 m ao redor da zona: enxerga a rua saindo da divisa
    private const val INDEX_MAX_TILES = 72
    private const val SAMPLE_STEP_M = 12.0
    private const val TOUCH_TOLERANCE_M = 20.0     // encosta na zona (mesmo criterio da lista antiga)
    private const val INTERNAL_MIN_DEPTH_M = -10.0 // pode terminar NA divisa (rua sem saida / esquina)...
    private const val BORDER_BAND_M = 25.0         // ...mas nao pode CORRER ao longo da divisa
    private const val MAX_BAND_LENGTH_M = 120.0
    private const val BLOCKED_TOUCH_M = 25.0

    private class StreetStat(val name: String) {
        var touches = false
        var minDepth = Double.POSITIVE_INFINITY
        var bandLength = 0.0
        var hitsBlocked = false
    }

    /**
     * Para cada Zona Permitida, classifica as ruas em:
     *  - INTERNA: todo o traçado (inclusive homonimos ate ~500 m da zona) fica dentro da zona,
     *    sem correr pela divisa e sem passar por Zona Proibida -> pode ser aprovada so pelo nome;
     *  - DE DIVISA/TOCA: encosta na zona mas tambem sai dela -> precisa do numero geocodificado.
     * Se algum tile da regiao nao baixar, a zona fica marcada como incompleta (complete=false)
     * e o motor NUNCA aprova so pelo nome nessa zona.
     */
    suspend fun buildStreetIndex(
        allowedZones: List<MapZone>,
        blockedZones: List<MapZone>
    ): Map<Long, ZoneStreetIndex> = withContext(Dispatchers.IO) {
        if (allowedZones.isEmpty()) return@withContext emptyMap()
        val template = resolveTileTemplate() ?: return@withContext emptyMap()

        val expanded = allowedZones.mapNotNull { z -> buildZoneBounds(z, INDEX_MARGIN_DEG) }
        if (expanded.isEmpty()) return@withContext emptyMap()

        val tilesPerZone = expanded.associate { zb -> zb.zone.id to collectNeededTiles(listOf(zb)).toSet() }
        val allTiles = LinkedHashSet<Pair<Int, Int>>()
        tilesPerZone.values.forEach { allTiles.addAll(it) }
        val toDownload = allTiles.take(INDEX_MAX_TILES)

        val downloaded = HashMap<Pair<Int, Int>, ByteArray>()
        coroutineScope {
            toDownload.chunked(6).forEach { batch ->
                batch.map { (tx, ty) ->
                    async {
                        val url = template
                            .replace("{z}", ZOOM.toString())
                            .replace("{x}", tx.toString())
                            .replace("{y}", ty.toString())
                        downloadBytes(url)?.let { bytes -> (tx to ty) to bytes }
                    }
                }.awaitAll().filterNotNull().forEach { (k, v) -> downloaded[k] = v }
            }
        }
        if (downloaded.isEmpty()) return@withContext emptyMap()

        val features = withContext(Dispatchers.Default) {
            downloaded.flatMap { (tile, bytes) -> decodeNamedFeatures(bytes, ZOOM, tile.first, tile.second) }
        }

        val now = System.currentTimeMillis()
        val result = LinkedHashMap<Long, ZoneStreetIndex>()
        withContext(Dispatchers.Default) {
            for (zb in expanded) {
                val zone = zb.zone
                val complete = tilesPerZone[zone.id].orEmpty().all { downloaded.containsKey(it) }
                val stats = LinkedHashMap<String, StreetStat>()

                for ((name, lines) in features) {
                    val key = AddressParser.streetKeyOf(name) ?: continue
                    val statKey = "${key.type ?: "-"}|${key.core}"
                    for (line in lines) {
                        if (!lineBoxIntersects(line, zb)) continue
                        val stat = stats.getOrPut(statKey) { StreetStat(name) }
                        sampleLine(line) { lat, lng, stepM ->
                            val dZone = GeoMath.depthMeters(lat, lng, zone)
                            if (dZone >= -TOUCH_TOLERANCE_M) stat.touches = true
                            // "Fora" = fora da UNIAO das zonas verdes: rua que passa de Olaria para Penha
                            // (duas zonas vizinhas) continua interna.
                            var d = dZone
                            for (other in allowedZones) {
                                if (other.id == zone.id) continue
                                val d2 = GeoMath.depthMeters(lat, lng, other)
                                if (d2 > d) d = d2
                            }
                            if (d < stat.minDepth) stat.minDepth = d
                            if (abs(d) < BORDER_BAND_M) stat.bandLength += stepM
                            if (!stat.hitsBlocked && blockedZones.any { b -> GeoMath.depthMeters(lat, lng, b) >= -BLOCKED_TOUCH_M }) {
                                stat.hitsBlocked = true
                            }
                        }
                    }
                }

                val touching = stats.values.filter { it.touches }
                val internal = touching.filter {
                    it.minDepth >= INTERNAL_MIN_DEPTH_M && it.bandLength <= MAX_BAND_LENGTH_M && !it.hitsBlocked
                }
                result[zone.id] = ZoneStreetIndex(
                    zoneId = zone.id,
                    geometryHash = GeoMath.indexKeyFor(zone, allowedZones + blockedZones),
                    complete = complete,
                    internalStreets = internal.map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER),
                    touchingStreets = touching.map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER),
                    builtAtMs = now
                )
            }
        }
        result
    }

    private fun lineBoxIntersects(line: List<Pair<Double, Double>>, zb: ZoneBounds): Boolean {
        var lMinLat = Double.POSITIVE_INFINITY
        var lMaxLat = Double.NEGATIVE_INFINITY
        var lMinLng = Double.POSITIVE_INFINITY
        var lMaxLng = Double.NEGATIVE_INFINITY
        for ((lat, lng) in line) {
            if (lat < lMinLat) lMinLat = lat
            if (lat > lMaxLat) lMaxLat = lat
            if (lng < lMinLng) lMinLng = lng
            if (lng > lMaxLng) lMaxLng = lng
        }
        return !(lMaxLat < zb.minLat || lMinLat > zb.maxLat || lMaxLng < zb.minLng || lMinLng > zb.maxLng)
    }

    /** Percorre a linha a cada ~12 m (inclui vertices). */
    private inline fun sampleLine(
        line: List<Pair<Double, Double>>,
        onSample: (lat: Double, lng: Double, stepM: Double) -> Unit
    ) {
        if (line.size == 1) {
            onSample(line[0].first, line[0].second, 0.0)
            return
        }
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            val segM = AreaEvaluator.haversineKm(a.first, a.second, b.first, b.second) * 1000.0
            val n = ceil(segM / SAMPLE_STEP_M).toInt().coerceIn(1, 400)
            val step = segM / n
            val startS = if (i == 0) 0 else 1
            for (s in startS..n) {
                val t = s.toDouble() / n
                onSample(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t, step)
            }
        }
    }

    internal fun extractStreetsFromTile(
        pbfBytes: ByteArray,
        zoom: Int,
        tx: Int,
        ty: Int,
        zoneBounds: List<ZoneBounds>,
        out: MutableSet<String>
    ) {
        val perZone = zoneBounds.associate { it.zone.id to sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER) }
        extractStreetsFromTilePerZone(pbfBytes, zoom, tx, ty, zoneBounds, perZone)
        perZone.values.forEach { out.addAll(it) }
    }

    private fun extractStreetsFromTilePerZone(
        pbfBytes: ByteArray,
        zoom: Int,
        tx: Int,
        ty: Int,
        zoneBounds: List<ZoneBounds>,
        outPerZone: Map<Long, MutableSet<String>>
    ) {
        for ((streetName, lines) in decodeNamedFeatures(pbfBytes, zoom, tx, ty)) {
            for (zb in zoneBounds) {
                if (anyLineIntersectsSingleBounds(lines, zb)) {
                    outPerZone[zb.zone.id]?.add(streetName)
                }
            }
        }
    }

    /**
     * Decodifica um tile MVT e devolve (nome do logradouro/praca, linhas em lat/lng).
     * Mesmo criterio de sempre: camada transportation_name + pracas/largos das camadas park/poi.
     */
    internal fun decodeNamedFeatures(
        pbfBytes: ByteArray,
        zoom: Int,
        tx: Int,
        ty: Int
    ): List<Pair<String, List<List<Pair<Double, Double>>>>> {
        val out = mutableListOf<Pair<String, List<List<Pair<Double, Double>>>>>()
        for ((fieldNo, _, layerBytes) in parseProtoMessages(pbfBytes)) {
            if (fieldNo != 3 || layerBytes !is ByteArray) continue

            var layerName = ""
            val keys = mutableListOf<String>()
            val values = mutableListOf<String>()
            val features = mutableListOf<ByteArray>()
            var extent = 4096

            for ((lf, lw, lv) in parseProtoMessages(layerBytes)) {
                when (lf) {
                    1 -> if (lv is ByteArray) layerName = lv.toString(Charsets.UTF_8)
                    2 -> if (lv is ByteArray) features.add(lv)
                    3 -> if (lv is ByteArray) keys.add(lv.toString(Charsets.UTF_8))
                    4 -> if (lv is ByteArray) values.add(decodeValueString(lv))
                    5 -> if (lw == 0 && lv is Long) extent = lv.toInt().coerceAtLeast(256)
                }
            }

            val isStreetLayer = layerName == "transportation_name"
            val isPlazaPoiLayer = layerName == "park" || layerName == "poi"
            if (!isStreetLayer && !isPlazaPoiLayer) continue

            val nameKeyIdx = keys.indexOf("name")
            val namePtKeyIdx = keys.indexOf("name:pt")
            val classKeyIdx = keys.indexOf("class")
            if (nameKeyIdx < 0 && namePtKeyIdx < 0) continue

            for (featBytes in features) {
                var streetName = ""
                var roadClass = ""
                var geomBytes: ByteArray? = null

                for ((ff, _, fv) in parseProtoMessages(featBytes)) {
                    when (ff) {
                        2 -> if (fv is ByteArray) {
                            val tags = readPackedVarints(fv)
                            var k = 0
                            while (k + 1 < tags.size) {
                                val ki = tags[k]
                                val vi = tags[k + 1]
                                if (vi in values.indices) {
                                    if (ki == namePtKeyIdx && values[vi].isNotBlank() && streetName.isBlank()) {
                                        streetName = values[vi].trim()
                                    } else if (ki == nameKeyIdx && values[vi].isNotBlank()) {
                                        streetName = values[vi].trim()
                                    } else if (ki == classKeyIdx) {
                                        roadClass = values[vi].trim().lowercase()
                                    }
                                }
                                k += 2
                            }
                        }
                        4 -> if (fv is ByteArray) geomBytes = fv
                    }
                }

                if (streetName.length < 3) continue
                if (roadClass in excludedClasses) continue
                val normName = TextNormalizer.normalize(streetName)
                if (normName.startsWith("estacao ")) continue
                if (isPlazaPoiLayer && !normName.startsWith("praca ") && !normName.startsWith("largo ")) continue
                val g = geomBytes ?: continue

                val lines = decodeGeometryLines(g, extent, zoom, tx, ty)
                if (lines.isEmpty()) continue
                out.add(streetName to lines)
            }
        }
        return out
    }

    private fun anyLineIntersectsSingleBounds(
        lines: List<List<Pair<Double, Double>>>,
        zb: ZoneBounds
    ): Boolean {
        for (line in lines) {
            if (line.isEmpty()) continue

            // Bounding-box da propria rua no tile para descartar rapido zonas distantes
            var lMinLat = Double.POSITIVE_INFINITY
            var lMaxLat = Double.NEGATIVE_INFINITY
            var lMinLng = Double.POSITIVE_INFINITY
            var lMaxLng = Double.NEGATIVE_INFINITY
            for ((lat, lng) in line) {
                if (lat < lMinLat) lMinLat = lat
                if (lat > lMaxLat) lMaxLat = lat
                if (lng < lMinLng) lMinLng = lng
                if (lng > lMaxLng) lMaxLng = lng
            }

            if (lMaxLat < zb.minLat || lMinLat > zb.maxLat || lMaxLng < zb.minLng || lMinLng > zb.maxLng) {
                continue
            }
            if (lineIntersectsSingleZone(line, zb.zone)) {
                return true
            }
        }
        return false
    }

    private fun lineIntersectsSingleZone(line: List<Pair<Double, Double>>, zone: MapZone): Boolean {
        for (i in line.indices) {
            val a = line[i]
            if (AreaEvaluator.isPointInZone(a.first, a.second, zone, toleranceMeters = 20.0)) {
                return true
            }
            if (i + 1 < line.size) {
                val b = line[i + 1]
                val segMeters = AreaEvaluator.haversineKm(a.first, a.second, b.first, b.second) * 1000.0
                val steps = (segMeters / 25.0).toInt().coerceIn(1, 40)
                for (s in 1 until steps) {
                    val t = s.toDouble() / steps
                    val lat = a.first + (b.first - a.first) * t
                    val lng = a.second + (b.second - a.second) * t
                    if (AreaEvaluator.isPointInZone(lat, lng, zone, toleranceMeters = 20.0)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun buildZoneBounds(zone: MapZone, margin: Double = 0.0004 /* ~45m */): ZoneBounds? {
        return when (zone.shape) {
            ZoneShape.POLYGON -> {
                if (zone.points.size < 3) return null
                ZoneBounds(
                    zone = zone,
                    minLat = zone.points.minOf { it.first } - margin,
                    maxLat = zone.points.maxOf { it.first } + margin,
                    minLng = zone.points.minOf { it.second } - margin,
                    maxLng = zone.points.maxOf { it.second } + margin
                )
            }
            ZoneShape.CIRCLE -> {
                if (zone.centerLat == 0.0 && zone.centerLng == 0.0) return null
                val r = zone.radiusMeters.coerceIn(50.0, 15000.0)
                val dLat = r / 111320.0 + margin
                val dLng = r / (111320.0 * cos(Math.toRadians(zone.centerLat)).coerceAtLeast(0.2)) + margin
                ZoneBounds(
                    zone = zone,
                    minLat = zone.centerLat - dLat,
                    maxLat = zone.centerLat + dLat,
                    minLng = zone.centerLng - dLng,
                    maxLng = zone.centerLng + dLng
                )
            }
        }
    }

    internal fun collectNeededTiles(zoneBounds: List<ZoneBounds>): List<Pair<Int, Int>> {
        val set = LinkedHashSet<Pair<Int, Int>>()
        for (zb in zoneBounds) {
            val (x0, y1) = latLonToTile(zb.minLat, zb.minLng, ZOOM)
            val (x1, y0) = latLonToTile(zb.maxLat, zb.maxLng, ZOOM)
            for (tx in min(x0, x1)..max(x0, x1)) {
                for (ty in min(y0, y1)..max(y0, y1)) {
                    set.add(tx to ty)
                }
            }
        }
        return set.toList()
    }

    internal fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n = 2.0.pow(zoom)
        val x = floor((lon + 180.0) / 360.0 * n).toInt()
        val rad = Math.toRadians(lat)
        val y = floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n).toInt()
        return x to y
    }

    private fun resolveTileTemplate(): String? {
        val now = System.currentTimeMillis()
        cachedTemplateUrl?.let { cached ->
            if (now - cachedTemplateAtMs < 3_600_000L) return cached
        }
        val bytes = downloadBytes(TILE_JSON_URL) ?: return null
        val text = bytes.toString(Charsets.UTF_8)
        val match = Regex(""""tiles"\s*:\s*\[\s*"([^"]+)"""").find(text) ?: return null
        val tpl = match.groupValues[1].trim()
        return if (tpl.startsWith("http")) {
            cachedTemplateUrl = tpl
            cachedTemplateAtMs = now
            tpl
        } else {
            null
        }
    }

    private fun downloadBytes(urlStr: String): ByteArray? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 6000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "gzip")
            }
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val raw = conn.inputStream.use { it.readBytes() }
            val isGzip = conn.contentEncoding?.equals("gzip", ignoreCase = true) == true ||
                (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
            conn.disconnect()
            if (isGzip) {
                GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
            } else {
                raw
            }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Decodificador leve de Protobuf / Mapbox Vector Tile (MVT)
    // ------------------------------------------------------------------

    private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int> {
        var res = 0L
        var shift = 0
        var i = start
        while (i < buf.size) {
            val b = buf[i++].toInt() and 0xFF
            res = res or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return res to i
            shift += 7
            if (shift >= 64) break
        }
        return res to i
    }

    private fun readPackedVarints(buf: ByteArray): List<Int> {
        val list = mutableListOf<Int>()
        var i = 0
        while (i < buf.size) {
            val (v, next) = readVarint(buf, i)
            list.add(v.toInt())
            i = next
        }
        return list
    }

    private fun parseProtoMessages(buf: ByteArray): List<Triple<Int, Int, Any>> {
        val out = mutableListOf<Triple<Int, Int, Any>>()
        var i = 0
        while (i < buf.size) {
            val (tag, next) = readVarint(buf, i)
            i = next
            val fieldNo = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            when (wireType) {
                0 -> {
                    val (v, n) = readVarint(buf, i)
                    out.add(Triple(fieldNo, wireType, v))
                    i = n
                }
                1 -> {
                    if (i + 8 > buf.size) break
                    i += 8
                }
                2 -> {
                    val (len, n) = readVarint(buf, i)
                    val byteLen = len.toInt()
                    if (byteLen < 0 || n + byteLen > buf.size) break
                    out.add(Triple(fieldNo, wireType, buf.copyOfRange(n, n + byteLen)))
                    i = n + byteLen
                }
                5 -> {
                    if (i + 4 > buf.size) break
                    i += 4
                }
                else -> break
            }
        }
        return out
    }

    private fun decodeValueString(buf: ByteArray): String {
        for ((fn, _, v) in parseProtoMessages(buf)) {
            if (fn == 1 && v is ByteArray) return v.toString(Charsets.UTF_8)
        }
        return ""
    }

    private fun decodeGeometryLines(
        buf: ByteArray,
        extent: Int,
        zoom: Int,
        tx: Int,
        ty: Int
    ): List<List<Pair<Double, Double>>> {
        val ints = readPackedVarints(buf)
        val lines = mutableListOf<List<Pair<Double, Double>>>()
        var current = mutableListOf<Pair<Double, Double>>()
        var idx = 0
        var cx = 0
        var cy = 0
        val n = 2.0.pow(zoom)

        while (idx < ints.size) {
            val cmdInt = ints[idx++]
            val cmd = cmdInt and 0x07
            val count = cmdInt ushr 3
            if (cmd == 1 || cmd == 2) {
                for (c in 0 until count) {
                    if (idx + 1 >= ints.size) break
                    val rx = ints[idx++]
                    val ry = ints[idx++]
                    val dx = (rx ushr 1) xor -(rx and 1)
                    val dy = (ry ushr 1) xor -(ry and 1)
                    cx += dx
                    cy += dy
                    val lon = (tx + cx.toDouble() / extent) / n * 360.0 - 180.0
                    val yFrac = (ty + cy.toDouble() / extent) / n
                    val lat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * yFrac))))
                    if (cmd == 1) {
                        if (current.isNotEmpty()) {
                            lines.add(current)
                            current = mutableListOf()
                        }
                        current.add(lat to lon)
                    } else {
                        current.add(lat to lon)
                    }
                }
            } else if (cmd == 7) {
                // ClosePath (ignorado para linhas)
            } else {
                break
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }
}
