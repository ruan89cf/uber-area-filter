package com.ruan.uberareafilter.engine

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.LruCache
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.model.ZoneMode
import com.ruan.uberareafilter.model.ZoneShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class Decision {
    INSIDE_AREA,    // 🟢 Card Verde (Embarque e Destino na mesma zona permitida)
    INTER_ZONE,     // 🟣 Card Roxo (Corrida entre zonas diferentes de aceitacao — sem auto-aceite)
    RETURN_TO_AREA, // 🟡 Card Amarelo (Motorista fora da area + embarque <= 1,0 km — sem auto-recusa p/ retornar a area)
    OUTSIDE_AREA,   // 🔴 Card Vermelho (Fora dos criterios — auto-recusa)
    UNKNOWN_KEEP
}

data class EvaluationResult(
    val decision: Decision,
    val reason: String,
    val checklistText: String = reason
)

/**
 * Avaliador inteligente de corridas:
 * - Gera checklist visual claro com ✔ (atendeu) e ✘ (nao atendeu) para cada requisito
 * - Suporta Corrida entre Zonas de Aceitacao (Card Roxo 🟣, respeitando KM Total Maximo)
 * - Verifica multiplos Poligonos e Circulos (Permitidos 🟢 e Proibidos 🔴)
 * - Regra da Av. Brasil (Lado Impar Bonsucesso/Ramos vs Lado Par Mare)
 */
class AreaEvaluator(private val context: Context) {

    private val geoCache = LruCache<String, List<Pair<Double, Double>>>(250)

    private val streetPrefixRegex = Regex(
        """^(rua|r\.|avenida|av\.|av\s|estrada|estr\.|travessa|tv\.|tv\s|praca|pca\.|via\s|viela|beco|largo|alameda|rodovia)\b.*""",
        RegexOption.IGNORE_CASE
    )
    private val streetTypeStripRegex = Regex(
        """^(rua|r\.?|avenida|av\.?|estrada|estr\.?|travessa|tv\.?|praca|pca\.?|alameda|al\.?|rodovia|rod\.?)\s+""",
        RegexOption.IGNORE_CASE
    )
    private val avBrasilNumberRegex = Regex(
        """(?:av\.?|avenida)\s+brasil\s*[,nº°:-]*\s*(\d{2,5})\b""",
        RegexOption.IGNORE_CASE
    )
    private val mareSideIndicators = listOf(
        "mare",
        "vila do joao",
        "vila do pinheiro",
        "nova holanda",
        "parque uniao",
        "baixa do sapateiro",
        "timbau",
        "rubens vaz",
        "conjunto esperanca",
        "salsa e merengue",
        "roquete pinto",
        "piscinao"
    )
    private val bonsucessoSideIndicators = listOf(
        "bonsucesso",
        "ramos",
        "olaria",
        "penha",
        "manguinhos",
        "higienopolis"
    )

    suspend fun evaluate(offer: ParsedRideOffer, config: FilterConfig): EvaluationResult = coroutineScope {
        val violations = mutableListOf<String>()
        val nonAreaViolations = mutableListOf<String>()
        val filterChecks = mutableListOf<String>()
        val ptBr = Locale("pt", "BR")

        // 1. Etiqueta "! Area de risco" da 99
        if (config.reject99RiskAreaBadge && offer.hasRiskAreaBadge) {
            violations.add("Área de risco (99)")
            nonAreaViolations.add("Área de risco (99)")
            filterChecks.add("✘ Área de risco (99)")
        }

        // 1b. Corrida com Paradas intermediarias (Uber "1 parada", "2 paradas")
        if (offer.hasMultipleStops) {
            val stopLabel = "${offer.stopsCount.coerceAtLeast(1)} parada(s)"
            if (config.rejectUberMultiStopRides) {
                violations.add("Corrida c/ $stopLabel")
                nonAreaViolations.add("Corrida c/ $stopLabel")
                filterChecks.add("✘ $stopLabel")
            } else {
                filterChecks.add("⚠️ $stopLabel")
            }
        }

        // 2. Filtro de Nota minima
        if (config.filterByRatingEnabled) {
            if (offer.passengerRating != null) {
                val fmt = String.format(ptBr, "%.2f", offer.passengerRating)
                val minFmt = String.format(ptBr, "%.2f", config.minRating)
                if (offer.passengerRating <= config.minRating) {
                    violations.add("Nota $fmt (mín $minFmt)")
                    nonAreaViolations.add("Nota $fmt")
                    filterChecks.add("✘ Nota $fmt (mín $minFmt)")
                } else {
                    filterChecks.add("✔ Nota $fmt")
                }
            } else if (offer.isNewPassenger) {
                if (config.rejectNewPassenger) {
                    violations.add("Passageiro novo")
                    nonAreaViolations.add("Passageiro novo")
                    filterChecks.add("✘ Passageiro novo")
                } else {
                    filterChecks.add("✔ Passageiro novo")
                }
            }
        }

        // 3. Filtro de Valor por Hora (R$/h ja com +2min de espera na conta)
        if (config.filterByHourlyRateEnabled && offer.hourlyRate != null) {
            val fmt = String.format(ptBr, "%.0f", offer.hourlyRate)
            val minFmt = String.format(ptBr, "%.0f", config.minHourlyRate)
            if (offer.hourlyRate < config.minHourlyRate) {
                violations.add("R$/h: R$$fmt (mín R$$minFmt)")
                nonAreaViolations.add("R$/h")
                filterChecks.add("✘ R$$fmt/h (mín $minFmt)")
            } else {
                filterChecks.add("✔ R$$fmt/h")
            }
        }

        // 4. Filtro de Distancia maxima ate o Embarque (km)
        if (config.filterByPickupDistEnabled && offer.pickupDistanceKm != null) {
            val fmt = String.format(ptBr, "%.1f", offer.pickupDistanceKm)
            val maxFmt = String.format(ptBr, "%.1f", config.maxPickupDistanceKm)
            if (offer.pickupDistanceKm > config.maxPickupDistanceKm) {
                violations.add("Emb. longe: ${fmt}km (máx ${maxFmt}km)")
                nonAreaViolations.add("Emb. longe")
                filterChecks.add("✘ Emb ${fmt}km (máx $maxFmt)")
            } else {
                filterChecks.add("✔ Emb ${fmt}km")
            }
        }

        // 5. Filtro de KM TOTAL Maximo (Embarque + Destino)
        if (config.filterByTotalDistEnabled && offer.totalDistanceKm != null) {
            val fmt = String.format(ptBr, "%.1f", offer.totalDistanceKm)
            val maxFmt = String.format(ptBr, "%.1f", config.maxTotalDistanceKm)
            if (offer.totalDistanceKm > config.maxTotalDistanceKm) {
                violations.add("KM Total: ${fmt}km (máx ${maxFmt}km)")
                nonAreaViolations.add("KM Total")
                filterChecks.add("✘ KM Total ${fmt}km (máx $maxFmt)")
            } else {
                filterChecks.add("✔ KM Total ${fmt}km")
            }
        }

        // 6. Lista de palavras/locais bloqueados manualmente
        val normalizedTexts = offer.allTexts.map { TextNormalizer.normalize(it) }
        for (blocked in config.blockedKeywords) {
            val normBlocked = TextNormalizer.normalize(blocked)
            if (normBlocked.isNotEmpty() && normalizedTexts.any { it.contains(normBlocked) }) {
                violations.add("Bloqueado: $blocked")
                nonAreaViolations.add("Bloqueado: $blocked")
                filterChecks.add("✘ Bloqueado: $blocked")
                break
            }
        }

        // 7. Verificacao das Zonas Demarcadas no Mapa (Permitidas 🟢 e Proibidas 🔴) + Lista de Ruas
        val allowedZones = config.mapZones.filter { it.mode == ZoneMode.ALLOWED }
        val blockedZones = config.mapZones.filter { it.mode == ZoneMode.BLOCKED }
        val hasAllowedZone = allowedZones.isNotEmpty() || config.polygonPoints.size >= 3
        val hasStreetList = config.allowedKeywords.isNotEmpty()

        val pickup = offer.pickupAddress
        val dropoff = offer.dropoffAddress

        if (!hasAllowedZone && !hasStreetList && blockedZones.isEmpty()) {
            if (violations.isNotEmpty()) {
                val summary = buildChecklistString(filterChecks, null, null)
                return@coroutineScope EvaluationResult(
                    Decision.OUTSIDE_AREA,
                    "✘ " + violations.joinToString(" • "),
                    summary
                )
            }
            return@coroutineScope EvaluationResult(
                Decision.UNKNOWN_KEEP,
                "Demarque sua área no mapa para ativar o filtro de ruas"
            )
        }

        if (config.requireBothPickupAndDropoff) {
            if (pickup.isNullOrBlank() || dropoff.isNullOrBlank()) {
                if (violations.isNotEmpty()) {
                    val summary = buildChecklistString(filterChecks, null, null)
                    return@coroutineScope EvaluationResult(
                        Decision.OUTSIDE_AREA,
                        "✘ " + violations.joinToString(" • "),
                        summary
                    )
                }
                return@coroutineScope EvaluationResult(Decision.UNKNOWN_KEEP, "Lendo endereços da corrida...")
            }

            val pickupDeferred = async { checkAddressInArea(pickup, config, allowedZones, blockedZones) }
            val dropoffDeferred = async { checkAddressInArea(dropoff, config, allowedZones, blockedZones) }
            val pickupRes = pickupDeferred.await()
            val dropoffRes = dropoffDeferred.await()

            val pickupLine = if (pickupRes.ok) {
                "✔ Embarque: ${pickup.take(34)}"
            } else {
                violations.add("Emb. fora (${pickupRes.reason})")
                "✘ Embarque: ${pickupRes.reason}"
            }

            val dropoffLine = if (dropoffRes.ok) {
                "✔ Destino: ${dropoff.take(34)}"
            } else {
                violations.add("Dest. fora (${dropoffRes.reason})")
                "✘ Destino: ${dropoffRes.reason}"
            }

            // Verifica se e uma corrida ENTRE duas zonas permitidas diferentes (Zona A -> Zona B)
            val isDifferentZones = pickupRes.ok &&
                dropoffRes.ok &&
                allowedZones.size > 1 &&
                pickupRes.matchedZoneId != null &&
                dropoffRes.matchedZoneId != null &&
                pickupRes.matchedZoneId != dropoffRes.matchedZoneId

            if (isDifferentZones && !config.allowInterZoneRides) {
                violations.add("Entre zonas (${pickupRes.zoneName} ➔ ${dropoffRes.zoneName})")
                filterChecks.add("✘ Entre zonas (${pickupRes.zoneName} ➔ ${dropoffRes.zoneName})")
            } else if (isDifferentZones && config.allowInterZoneRides) {
                filterChecks.add("🟣 ${pickupRes.zoneName} ➔ ${dropoffRes.zoneName}")
            }

            // Regra do Card Amarelo 🟡 (Item 6):
            // Quando o motorista NAO esta na area definida (Embarque fora da area: !pickupRes.ok),
            // se aparecer uma corrida a <= 1,0 km de distancia (e que nao caia em Zona Proibida/Mare e atenda Nota/R$/h/KM),
            // toca em AMARELO (sem auto-cancelar) para o motorista pegar e se encaminhar para a area definida!
            val isExplicitlyForbiddenArea = pickupRes.isExplicitlyBlocked || dropoffRes.isExplicitlyBlocked
            val isPickupWithin1Km = offer.pickupDistanceKm != null && offer.pickupDistanceKm <= 1.05
            if (config.yellowCardOutsideAreaEnabled &&
                !pickupRes.ok &&
                isPickupWithin1Km &&
                nonAreaViolations.isEmpty() &&
                !isExplicitlyForbiddenArea
            ) {
                val yellowPickupLine = "🟡 Emb ≤ 1km (fora da zona): ${pickup.take(28)}"
                val yellowDropLine = if (dropoffRes.ok) {
                    "✔ Destino na área: ${dropoff.take(30)}"
                } else {
                    "🟡 Destino: ${dropoff.take(32)}"
                }
                val yellowChecklist = buildChecklistString(filterChecks, yellowPickupLine, yellowDropLine)
                return@coroutineScope EvaluationResult(
                    Decision.RETURN_TO_AREA,
                    "🟡 Fora da área c/ embarque ≤ 1km (${offer.pickupDistanceKm}km) • Você decide",
                    yellowChecklist
                )
            }

            val checklist = buildChecklistString(filterChecks, pickupLine, dropoffLine)

            if (violations.isNotEmpty()) {
                return@coroutineScope EvaluationResult(
                    Decision.OUTSIDE_AREA,
                    "✘ " + violations.joinToString(" • "),
                    checklist
                )
            }

            // Se passou em todos os filtros (incluindo KM Total Maximo) e vai de uma Zona A para uma Zona B -> CARD ROXO!
            if (isDifferentZones && config.allowInterZoneRides) {
                return@coroutineScope EvaluationResult(
                    Decision.INTER_ZONE,
                    "🟣 Entre zonas: ${pickupRes.zoneName} ➔ ${dropoffRes.zoneName}",
                    checklist
                )
            }

            return@coroutineScope EvaluationResult(
                Decision.INSIDE_AREA,
                "✔ Embarque e Destino na área (${dropoffRes.reason})",
                checklist
            )
        } else {
            val target = dropoff ?: pickup
            if (target.isNullOrBlank()) {
                if (violations.isNotEmpty()) {
                    val summary = buildChecklistString(filterChecks, null, null)
                    return@coroutineScope EvaluationResult(
                        Decision.OUTSIDE_AREA,
                        "✘ " + violations.joinToString(" • "),
                        summary
                    )
                }
                return@coroutineScope EvaluationResult(Decision.UNKNOWN_KEEP, "Lendo endereço de destino...")
            }
            val res = checkAddressInArea(target, config, allowedZones, blockedZones)
            val dropLine = if (res.ok) {
                "✔ Destino: ${target.take(34)}"
            } else {
                violations.add("Dest. fora (${res.reason})")
                "✘ Destino: ${res.reason}"
            }

            val isPickupWithin1Km = offer.pickupDistanceKm != null && offer.pickupDistanceKm <= 1.05
            if (config.yellowCardOutsideAreaEnabled &&
                !res.ok &&
                isPickupWithin1Km &&
                nonAreaViolations.isEmpty() &&
                !res.isExplicitlyBlocked
            ) {
                val yellowChecklist = buildChecklistString(filterChecks, null, "🟡 Emb ≤ 1km • Dest: ${target.take(28)}")
                return@coroutineScope EvaluationResult(
                    Decision.RETURN_TO_AREA,
                    "🟡 Fora da área c/ embarque ≤ 1km • Você decide",
                    yellowChecklist
                )
            }

            val checklist = buildChecklistString(filterChecks, null, dropLine)

            return@coroutineScope if (violations.isEmpty()) {
                EvaluationResult(Decision.INSIDE_AREA, "✔ Destino na área (${res.reason})", checklist)
            } else {
                EvaluationResult(Decision.OUTSIDE_AREA, "✘ " + violations.joinToString(" • "), checklist)
            }
        }
    }

    private fun buildChecklistString(
        filterChecks: List<String>,
        pickupLine: String?,
        dropoffLine: String?
    ): String {
        val rows = mutableListOf<String>()
        if (filterChecks.isNotEmpty()) {
            rows.add(filterChecks.joinToString("  •  "))
        }
        if (!pickupLine.isNullOrBlank()) {
            rows.add(pickupLine)
        }
        if (!dropoffLine.isNullOrBlank()) {
            rows.add(dropoffLine)
        }
        return rows.joinToString("\n")
    }

    private data class ZoneCheckResult(
        val ok: Boolean,
        val reason: String,
        val matchedZoneId: Long? = null,
        val zoneName: String? = null,
        val isExplicitlyBlocked: Boolean = false
    )

    /**
     * Normaliza tanto "R. Pirangi" quanto "Rua Pirangi" para o mesmo nucleo ("pirangi"),
     * evitando que abreviacoes como "R.", "Av.", "Estr.", "Tv." falhem na comparacao!
     */
    private fun normalizeStreetCore(raw: String): String {
        val norm = TextNormalizer.normalize(raw).trim()
        return norm.replace(streetTypeStripRegex, "").trim()
    }

    private suspend fun checkAddressInArea(
        address: String,
        config: FilterConfig,
        allowedZones: List<MapZone>,
        blockedZones: List<MapZone>
    ): ZoneCheckResult {
        val normAddr = TextNormalizer.normalize(address)

        if (config.avBrasilOddSideOnly && isAvBrasilAddress(normAddr)) {
            val avBrasilViolation = evaluateAvBrasilSide(address, normAddr)
            if (avBrasilViolation != null) {
                return ZoneCheckResult(ok = false, reason = avBrasilViolation, isExplicitlyBlocked = true)
            }
        }

        val explicitStreet = extractExplicitStreetSegment(address)
        val normExplicitStreet = explicitStreet?.let { TextNormalizer.normalize(it) }
        val coreExplicitStreet = explicitStreet?.let { normalizeStreetCore(it) }
        val mentionsTrainStation = normAddr.contains("estacao")

        val matchedStreet = config.allowedKeywords.firstOrNull { kw ->
            val normKw = TextNormalizer.normalize(kw)
            val coreKw = normalizeStreetCore(kw)
            if (normKw.length < 3 || coreKw.length < 3) return@firstOrNull false
            if (normExplicitStreet != null && coreExplicitStreet != null && coreExplicitStreet.length >= 3) {
                if (normKw.startsWith("estacao ")) return@firstOrNull false
                normExplicitStreet.contains(normKw) ||
                    normKw.contains(normExplicitStreet) ||
                    coreExplicitStreet == coreKw ||
                    (coreKw.length >= 5 && coreExplicitStreet.contains(coreKw))
            } else {
                normAddr.contains(normKw) || (coreKw.length >= 5 && normAddr.contains(coreKw))
            }
        }

        if (mentionsTrainStation && normExplicitStreet != null && config.allowedKeywords.size >= 8 && matchedStreet == null) {
            return ZoneCheckResult(ok = false, reason = "$explicitStreet (fora da área)")
        }

        val effectiveAllowedZones = if (allowedZones.isNotEmpty()) {
            allowedZones
        } else if (config.polygonPoints.size >= 3) {
            listOf(
                MapZone(
                    id = 1L,
                    name = "Zona 1",
                    mode = ZoneMode.ALLOWED,
                    shape = ZoneShape.POLYGON,
                    points = config.polygonPoints
                )
            )
        } else {
            emptyList()
        }

        if (effectiveAllowedZones.isNotEmpty() || blockedZones.isNotEmpty()) {
            val geocodeTarget = buildStreetPriorityQuery(address, explicitStreet)
            val candidates = geocodeAddressCandidates("$geocodeTarget, Rio de Janeiro, RJ")

            if (candidates.isNotEmpty()) {
                for (coords in candidates) {
                    for (bZone in blockedZones) {
                        if (isPointInZone(coords.first, coords.second, bZone, toleranceMeters = 20.0)) {
                            return ZoneCheckResult(
                                ok = false,
                                reason = "Zona Proibida (${bZone.name})",
                                isExplicitlyBlocked = true
                            )
                        }
                    }
                }

                if (effectiveAllowedZones.isNotEmpty()) {
                    for (coords in candidates) {
                        val matchedZone = effectiveAllowedZones.firstOrNull { zone ->
                            isPointInZone(coords.first, coords.second, zone, toleranceMeters = 45.0)
                        }
                        if (matchedZone != null) {
                            return ZoneCheckResult(
                                ok = true,
                                reason = matchedStreet ?: explicitStreet ?: address.take(28),
                                matchedZoneId = matchedZone.id,
                                zoneName = matchedZone.name
                            )
                        }
                    }

                    // Se a rua consta explicitamente na lista de ruas permitidas gerada pelo mapa (ex: Rua Pirangi),
                    // aprova mesmo se o Geocoder do Android apontou para homonimo em outro municipio
                    if (matchedStreet != null) {
                        return ZoneCheckResult(
                            ok = true,
                            reason = matchedStreet,
                            matchedZoneId = effectiveAllowedZones.firstOrNull()?.id,
                            zoneName = effectiveAllowedZones.firstOrNull()?.name
                        )
                    }

                    return ZoneCheckResult(
                        ok = false,
                        reason = explicitStreet ?: address.take(30)
                    )
                }
            }
        }

        return if (matchedStreet != null) {
            ZoneCheckResult(
                ok = true,
                reason = matchedStreet,
                matchedZoneId = effectiveAllowedZones.firstOrNull()?.id,
                zoneName = effectiveAllowedZones.firstOrNull()?.name
            )
        } else {
            ZoneCheckResult(ok = false, reason = explicitStreet ?: address.take(30))
        }
    }

    private fun isAvBrasilAddress(normAddr: String): Boolean {
        return normAddr.contains("av brasil") ||
            normAddr.contains("av. brasil") ||
            normAddr.contains("avenida brasil")
    }

    private fun evaluateAvBrasilSide(rawAddress: String, normAddr: String): String? {
        if (mareSideIndicators.any { normAddr.contains(it) }) {
            return "Av. Brasil (Lado Maré)"
        }

        val match = avBrasilNumberRegex.find(normAddr)
        if (match != null) {
            val number = match.groupValues[1].toIntOrNull()
            if (number != null) {
                return if (number % 2 == 0) {
                    "Av. Brasil nº $number (Lado Par / Maré)"
                } else {
                    null
                }
            }
        }

        if (normAddr.contains("passarela")) {
            return "Av. Brasil (Passarela s/ nº ímpar)"
        }

        if (bonsucessoSideIndicators.none { normAddr.contains(it) }) {
            return "Av. Brasil s/ nº ímpar (${rawAddress.take(20)})"
        }

        return null
    }

    private fun extractExplicitStreetSegment(address: String): String? {
        val parts = address.split(",", " - ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (part in parts) {
            val norm = TextNormalizer.normalize(part)
            if (streetPrefixRegex.matches(norm) && !norm.startsWith("estacao")) {
                return part
            }
        }
        return null
    }

    private fun buildStreetPriorityQuery(fullAddress: String, explicitStreet: String?): String {
        if (explicitStreet == null) return fullAddress
        val idx = fullAddress.indexOf(explicitStreet, ignoreCase = true)
        return if (idx > 0) {
            fullAddress.substring(idx).trim()
        } else {
            fullAddress
        }
    }

    private suspend fun geocodeAddressCandidates(query: String): List<Pair<Double, Double>> {
        geoCache.get(query)?.let { return it }

        val results = withTimeoutOrNull(1200L) {
            val geocoder = Geocoder(context, Locale("pt", "BR"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<Address>> { cont ->
                    try {
                        geocoder.getFromLocationName(query, 5) { addresses ->
                            if (cont.isActive) cont.resume(addresses)
                        }
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocationName(query, 5).orEmpty()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
        }.orEmpty()

        val pairs = results.map { it.latitude to it.longitude }
        if (pairs.isNotEmpty()) {
            geoCache.put(query, pairs)
        }
        return pairs
    }

    companion object {
        /**
         * Calcula a menor distancia aproximada (borda a borda, em km) entre duas zonas permitidas diferentes,
         * para validar se o KM Total Maximo configurado permite viajar de uma zona para outra.
         */
        fun calculateMinDistanceBetweenAllowedZonesKm(zones: List<MapZone>): Double? {
            val allowed = zones.filter { it.mode == ZoneMode.ALLOWED }
            if (allowed.size < 2) return null

            var minDistKm = Double.MAX_VALUE
            for (i in 0 until allowed.size) {
                for (j in i + 1 until allowed.size) {
                    val d = distanceBetweenTwoZonesKm(allowed[i], allowed[j])
                    if (d < minDistKm) {
                        minDistKm = d
                    }
                }
            }
            return if (minDistKm < Double.MAX_VALUE) minDistKm else null
        }

        private fun distanceBetweenTwoZonesKm(z1: MapZone, z2: MapZone): Double {
            val (c1Lat, c1Lng, r1Km) = getZoneCenterAndRadiusKm(z1)
            val (c2Lat, c2Lng, r2Km) = getZoneCenterAndRadiusKm(z2)
            val centerDistKm = haversineKm(c1Lat, c1Lng, c2Lat, c2Lng)
            // Distancia minima borda-a-borda multiplicada por 1.25 (fator de rota de rua vs linha reta)
            return ((centerDistKm - r1Km - r2Km).coerceAtLeast(0.5)) * 1.25
        }

        private fun getZoneCenterAndRadiusKm(z: MapZone): Triple<Double, Double, Double> {
            return if (z.shape == ZoneShape.CIRCLE) {
                Triple(z.centerLat, z.centerLng, z.radiusMeters / 1000.0)
            } else if (z.points.isNotEmpty()) {
                val avgLat = z.points.map { it.first }.average()
                val avgLng = z.points.map { it.second }.average()
                val maxR = z.points.maxOfOrNull { haversineKm(avgLat, avgLng, it.first, it.second) } ?: 0.5
                Triple(avgLat, avgLng, maxR)
            } else {
                Triple(0.0, 0.0, 0.0)
            }
        }

        fun isPointInZone(
            lat: Double,
            lng: Double,
            zone: MapZone,
            toleranceMeters: Double = 25.0
        ): Boolean {
            return when (zone.shape) {
                ZoneShape.CIRCLE -> {
                    if (zone.centerLat == 0.0 && zone.centerLng == 0.0) return false
                    val distMeters = haversineKm(lat, lng, zone.centerLat, zone.centerLng) * 1000.0
                    distMeters <= (zone.radiusMeters + toleranceMeters)
                }

                ZoneShape.POLYGON -> {
                    isPointInOrNearPolygon(lat, lng, zone.points, toleranceMeters)
                }
            }
        }

        fun isPointInOrNearPolygon(
            lat: Double,
            lng: Double,
            polygon: List<Pair<Double, Double>>,
            toleranceMeters: Double = 25.0
        ): Boolean {
            if (polygon.size < 3) return false
            if (isPointInPolygon(lat, lng, polygon)) return true

            val toleranceKm = toleranceMeters / 1000.0
            for (pt in polygon) {
                if (haversineKm(lat, lng, pt.first, pt.second) <= toleranceKm) {
                    return true
                }
            }
            return false
        }

        private fun isPointInPolygon(
            lat: Double,
            lng: Double,
            polygon: List<Pair<Double, Double>>
        ): Boolean {
            var inside = false
            var j = polygon.size - 1
            for (i in polygon.indices) {
                val yi = polygon[i].first
                val xi = polygon[i].second
                val yj = polygon[j].first
                val xj = polygon[j].second

                val intersect = ((yi > lat) != (yj > lat)) &&
                    (lng < (xj - xi) * (lat - yi) / (yj - yi + 1e-12) + xi)
                if (intersect) inside = !inside
                j = i
            }
            return inside
        }

        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return r * c
        }
    }
}
