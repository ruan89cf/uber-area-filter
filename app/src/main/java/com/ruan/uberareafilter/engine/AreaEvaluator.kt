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
class AreaEvaluator(private val context: Context? = null) {

    private val geoCache = LruCache<String, List<Pair<Double, Double>>>(250)

    private val streetPrefixRegex = Regex(
        """^(rua|r\.|avenida|av\.|av\s|estrada|estr\.|travessa|tv\.|tv\s|praca|pca\.|pca\s|pc\.|pc\s|via\s|viela|beco|largo|alameda|rodovia)\b.*""",
        RegexOption.IGNORE_CASE
    )
    private val streetTypeStripRegex = Regex(
        """^(rua|r\.?|avenida|av\.?|estrada|estr\.?|travessa|tv\.?|praca|pca\.?|pc\.?|alameda|al\.?|rodovia|rod\.?)\s+""",
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
                if (offer.passengerRating + 0.0001 < config.minRating) {
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

        // 3. Filtro de Valor por Hora (bruto ou liquido apos descontar combustivel da corrida; Uber pode usar valor proprio)
        val isUberOffer = offer.appSource.equals("UBER", ignoreCase = true)
        val checkHourlyRate = config.filterByHourlyRateEnabled || (isUberOffer && config.uberCustomHourlyEnabled)
        val filterHourly = FuelCalculator.estimateForFilter(offer, config)
        val effectiveHourly = filterHourly.effectiveHourlyRate
        if (checkHourlyRate && effectiveHourly != null) {
            val targetMinHourly = config.effectiveMinHourlyRate(offer.appSource)
            val fmt = String.format(ptBr, "%.0f", effectiveHourly)
            val minFmt = String.format(ptBr, "%.0f", targetMinHourly)
            val suffix = if (filterHourly.isNet) "/h líq." else "/h"
            val tag = if (filterHourly.isNet) "R$/h líq." else "R$/h"
            if (effectiveHourly < targetMinHourly) {
                violations.add("$tag: R$$fmt (mín R$$minFmt)")
                nonAreaViolations.add(tag)
                filterChecks.add("✘ R$$fmt$suffix (mín $minFmt)")
            } else {
                filterChecks.add("✔ R$$fmt$suffix")
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

            // Regra do Card Amarelo 🟡 (Retorno para zona permitida):
            // So dispara quando o motorista esta FORA (!pickupRes.ok), o embarque e <= 1 km
            // e o DESTINO leva PARA DENTRO de alguma zona permitida (dropoffRes.ok == true)!
            // NUNCA dispara se a corrida estiver levando para fora da area demarcada.
            val isExplicitlyForbiddenArea = pickupRes.isExplicitlyBlocked || dropoffRes.isExplicitlyBlocked
            val isPickupWithin1Km = offer.pickupDistanceKm != null && offer.pickupDistanceKm <= 1.05
            if (config.yellowCardOutsideAreaEnabled &&
                !pickupRes.ok &&
                dropoffRes.ok &&
                isPickupWithin1Km &&
                nonAreaViolations.isEmpty() &&
                !isExplicitlyForbiddenArea
            ) {
                val yellowPickupLine = "🟡 Emb ≤ 1km (fora da zona): ${pickup.take(30)}"
                val yellowDropLine = "✔ Destino (na área): ${dropoff.take(32)}"
                val yellowChecklist = buildChecklistString(filterChecks, yellowPickupLine, yellowDropLine)
                return@coroutineScope EvaluationResult(
                    Decision.RETURN_TO_AREA,
                    "🟡 Retornar p/ área c/ emb. ≤ 1km (${offer.pickupDistanceKm}km) • Você decide",
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
                    "🟣 Corrida entre zonas: ${pickupRes.zoneName} ➔ ${dropoffRes.zoneName}",
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
            val isDropoffTarget = !dropoff.isNullOrBlank()
            val addrLabel = if (isDropoffTarget) "Destino" else "Embarque"
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

            // Verifica o destino e tambem identifica a zona de embarque (para exibir Embarque na notificacao e detectar Card Roxo)
            val targetDeferred = async { checkAddressInArea(target, config, allowedZones, blockedZones) }
            val pickupInfoDeferred = if (isDropoffTarget && !pickup.isNullOrBlank()) {
                async { checkAddressInArea(pickup, config, allowedZones, blockedZones) }
            } else {
                null
            }
            val res = targetDeferred.await()
            val pickupRes = pickupInfoDeferred?.await()

            val addrCheckLine = if (res.ok) {
                "✔ $addrLabel: ${target.take(34)}"
            } else {
                val shortTag = if (isDropoffTarget) "Dest. fora" else "Emb. fora"
                violations.add("$shortTag (${res.reason})")
                "✘ $addrLabel: ${res.reason}"
            }

            val pickupDisplayLine = if (isDropoffTarget && !pickup.isNullOrBlank()) {
                "📍 Embarque: ${pickup.take(34)}"
            } else {
                null
            }

            // Tambem bloqueia caso o embarque caia explicitamente em Zona Proibida 🔴 / Lado Par Mare
            if (pickupRes?.isExplicitlyBlocked == true && isDropoffTarget) {
                violations.add("Emb. bloqueado (${pickupRes.reason})")
            }

            val isDifferentZones = isDropoffTarget &&
                pickupRes != null &&
                pickupRes.ok &&
                res.ok &&
                allowedZones.size > 1 &&
                pickupRes.matchedZoneId != null &&
                res.matchedZoneId != null &&
                pickupRes.matchedZoneId != res.matchedZoneId

            if (isDifferentZones && !config.allowInterZoneRides) {
                violations.add("Entre zonas (${pickupRes?.zoneName} ➔ ${res.zoneName})")
                filterChecks.add("✘ Entre zonas (${pickupRes?.zoneName} ➔ ${res.zoneName})")
            } else if (isDifferentZones && config.allowInterZoneRides) {
                filterChecks.add("🟣 ${pickupRes?.zoneName} ➔ ${res.zoneName}")
            }

            val checklist = buildChecklistString(
                filterChecks,
                if (isDropoffTarget) pickupDisplayLine else addrCheckLine,
                if (isDropoffTarget) addrCheckLine else null
            )

            if (violations.isNotEmpty()) {
                return@coroutineScope EvaluationResult(
                    Decision.OUTSIDE_AREA,
                    "✘ " + violations.joinToString(" • "),
                    checklist
                )
            }

            if (isDifferentZones && config.allowInterZoneRides) {
                return@coroutineScope EvaluationResult(
                    Decision.INTER_ZONE,
                    "🟣 Corrida entre zonas: ${pickupRes?.zoneName} ➔ ${res.zoneName}",
                    checklist
                )
            }

            return@coroutineScope EvaluationResult(
                Decision.INSIDE_AREA,
                "✔ $addrLabel na área (${res.reason})",
                checklist
            )
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
     * Normaliza tanto "R. Pirangi" quanto "Rua Pirangi" e pequenas variacoes ortograficas
     * ("Rua Souza Lôbo" vs "Rua Sousa Lobo") para o mesmo nucleo!
     */
    internal fun normalizeStreetCore(raw: String): String {
        val norm = TextNormalizer.normalize(raw)
            .replace(".", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return norm.replace(streetTypeStripRegex, "")
            .replace("souza", "sousa")
            .replace("luiz", "luis")
            .replace("braz", "bras")
            .replace("teresa", "tereza")
            .replace("walter", "valter")
            .replace("waldir", "valdir")
            .trim()
    }

    private fun findMatchingStreet(
        normAddr: String,
        normExplicitStreet: String?,
        coreExplicitStreet: String?,
        streetList: List<String>
    ): String? {
        return streetList.firstOrNull { kw ->
            val normKw = TextNormalizer.normalize(kw)
            val coreKw = normalizeStreetCore(kw)
            if (normKw.length < 3 || coreKw.length < 3) return@firstOrNull false
            if (normExplicitStreet != null && coreExplicitStreet != null && coreExplicitStreet.length >= 3) {
                if (normKw.startsWith("estacao ")) return@firstOrNull false
                val phoneticExplicit = normalizeStreetCore(normExplicitStreet)
                normExplicitStreet.contains(normKw) ||
                    normKw.contains(normExplicitStreet) ||
                    coreExplicitStreet == coreKw ||
                    phoneticExplicit == coreKw ||
                    (coreKw.length >= 5 && (coreExplicitStreet.contains(coreKw) || phoneticExplicit.contains(coreKw)))
            } else {
                val phoneticAddr = normalizeStreetCore(normAddr)
                normAddr.contains(normKw) || (coreKw.length >= 5 && (normAddr.contains(coreKw) || phoneticAddr.contains(coreKw)))
            }
        }
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

        // Procura primeiro na lista propria de ruas de cada caixa ("MapZone") para preservar qual caixa e dona da rua
        var matchedZoneByStreet: MapZone? = null
        var matchedStreet: String? = null
        for (zone in effectiveAllowedZones) {
            if (zone.streetNames.isNotEmpty()) {
                val found = findMatchingStreet(normAddr, normExplicitStreet, coreExplicitStreet, zone.streetNames)
                if (found != null) {
                    matchedZoneByStreet = zone
                    matchedStreet = found
                    break
                }
            }
        }
        if (matchedStreet == null) {
            matchedStreet = findMatchingStreet(normAddr, normExplicitStreet, coreExplicitStreet, config.allowedKeywords)
        }

        if (mentionsTrainStation && normExplicitStreet != null && config.allowedKeywords.size >= 8 && matchedStreet == null) {
            return ZoneCheckResult(ok = false, reason = "$explicitStreet (fora da área)")
        }

        if (effectiveAllowedZones.isNotEmpty() || blockedZones.isNotEmpty()) {
            val geocodeTarget = buildStreetPriorityQuery(address, explicitStreet)
            val queryVariants = buildGeocodeQueryVariants(address, geocodeTarget, explicitStreet)
            val bounds = computeZoneBoundingBox(effectiveAllowedZones + blockedZones)
            val candidates = geocodeAddressCandidates(queryVariants, bounds)

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
                    // aprova preservando a zona dona da rua mesmo se o Geocoder apontou para homonimo distante
                    if (matchedStreet != null) {
                        val ownerZone = matchedZoneByStreet ?: effectiveAllowedZones.firstOrNull()
                        return ZoneCheckResult(
                            ok = true,
                            reason = matchedStreet,
                            matchedZoneId = ownerZone?.id,
                            zoneName = ownerZone?.name
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
            val ownerZone = matchedZoneByStreet ?: effectiveAllowedZones.firstOrNull()
            ZoneCheckResult(
                ok = true,
                reason = matchedStreet,
                matchedZoneId = ownerZone?.id,
                zoneName = ownerZone?.name
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

    private fun expandStreetAbbreviations(raw: String): String {
        return raw
            .replace(Regex("""\bR\.\s*""", RegexOption.IGNORE_CASE), "Rua ")
            .replace(Regex("""\bAv\.\s*""", RegexOption.IGNORE_CASE), "Avenida ")
            .replace(Regex("""\bEstr\.\s*""", RegexOption.IGNORE_CASE), "Estrada ")
            .replace(Regex("""\bTv\.\s*""", RegexOption.IGNORE_CASE), "Travessa ")
            .replace(Regex("""\bP[çc]a?\.\s*""", RegexOption.IGNORE_CASE), "Praça ")
            .replace(Regex("""\bDr\.\s*""", RegexOption.IGNORE_CASE), "Doutor ")
            .replace(Regex("""\bDra\.\s*""", RegexOption.IGNORE_CASE), "Doutora ")
            .replace(Regex("""\bSrg\.\s*""", RegexOption.IGNORE_CASE), "Sargento ")
            .replace(Regex("""\bCel\.\s*""", RegexOption.IGNORE_CASE), "Coronel ")
            .replace(Regex("""\bBrg\.\s*""", RegexOption.IGNORE_CASE), "Brigadeiro ")
            .replace(Regex("""\bGen\.\s*""", RegexOption.IGNORE_CASE), "General ")
            .replace(Regex("""\bCmte\.\s*""", RegexOption.IGNORE_CASE), "Comandante ")
            .replace(Regex("""\bPres\.\s*""", RegexOption.IGNORE_CASE), "Presidente ")
            .replace(" - ", ", ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun buildGeocodeQueryVariants(
        fullAddress: String,
        geocodeTarget: String,
        explicitStreet: String?
    ): List<String> {
        val list = LinkedHashSet<String>()
        val expandedTarget = expandStreetAbbreviations(geocodeTarget)
        val expandedFull = expandStreetAbbreviations(fullAddress)
        val suffixRj = if (TextNormalizer.normalize(expandedTarget).contains("rio de janeiro") ||
            TextNormalizer.normalize(expandedTarget).contains("duque de caxias")
        ) "" else ", Rio de Janeiro, RJ"

        if (expandedTarget.isNotBlank()) {
            list.add("$expandedTarget$suffixRj")
            // Variante sem numero da casa (util p/ pracas e POIs: "Praca Doutor Waldir da Mota, Olaria, Rio de Janeiro")
            val withoutHouseNum = expandedTarget.replace(Regex(""",\s*\d+[A-Za-z]?\s*(?:,|$)"""), ", ").replace(Regex(""",\s*,"""), ",").trim().trimEnd(',')
            if (withoutHouseNum.isNotBlank() && withoutHouseNum != expandedTarget) {
                list.add("$withoutHouseNum$suffixRj")
            }
        }
        if (explicitStreet != null && suffixRj.isNotEmpty()) {
            list.add("${expandStreetAbbreviations(explicitStreet)}$suffixRj")
        }
        if (expandedFull.isNotBlank() && expandedFull != expandedTarget) {
            list.add("$expandedFull$suffixRj")
        }
        return list.toList()
    }

    private data class GeoBounds(val minLat: Double, val minLng: Double, val maxLat: Double, val maxLng: Double)

    private fun computeZoneBoundingBox(zones: List<MapZone>): GeoBounds? {
        if (zones.isEmpty()) return null
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLng = Double.POSITIVE_INFINITY
        var maxLng = Double.NEGATIVE_INFINITY
        for (z in zones) {
            when (z.shape) {
                ZoneShape.POLYGON -> z.points.forEach { (la, ln) ->
                    if (la < minLat) minLat = la
                    if (la > maxLat) maxLat = la
                    if (ln < minLng) minLng = ln
                    if (ln > maxLng) maxLng = ln
                }
                ZoneShape.CIRCLE -> {
                    val dLat = (z.radiusMeters / 111320.0)
                    val dLng = (z.radiusMeters / (111320.0 * cos(Math.toRadians(z.centerLat)).coerceAtLeast(0.2)))
                    if (z.centerLat - dLat < minLat) minLat = z.centerLat - dLat
                    if (z.centerLat + dLat > maxLat) maxLat = z.centerLat + dLat
                    if (z.centerLng - dLng < minLng) minLng = z.centerLng - dLng
                    if (z.centerLng + dLng > maxLng) maxLng = z.centerLng + dLng
                }
            }
        }
        if (!minLat.isFinite() || !maxLat.isFinite()) return null
        val pad = 0.025 // ~2.8 km ao redor da zona desenhada
        return GeoBounds(minLat - pad, minLng - pad, maxLat + pad, maxLng + pad)
    }

    private suspend fun geocodeAddressCandidates(
        queries: List<String>,
        bounds: GeoBounds?
    ): List<Pair<Double, Double>> {
        for (query in queries) {
            geoCache.get(query)?.let { if (it.isNotEmpty()) return it }

            val boundedPairs = runSingleGeocode(query, bounds)
            if (boundedPairs.isNotEmpty()) {
                geoCache.put(query, boundedPairs)
                return boundedPairs
            }
            if (bounds != null) {
                val unboundedPairs = runSingleGeocode(query, null)
                if (unboundedPairs.isNotEmpty()) {
                    geoCache.put(query, unboundedPairs)
                    return unboundedPairs
                }
            }
        }
        return emptyList()
    }

    private suspend fun runSingleGeocode(
        query: String,
        bounds: GeoBounds?
    ): List<Pair<Double, Double>> {
        val results = withTimeoutOrNull(1800L) {
            val safeContext = context ?: return@withTimeoutOrNull emptyList()
            val geocoder = Geocoder(safeContext, Locale("pt", "BR"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<Address>> { cont ->
                    try {
                        if (bounds != null) {
                            geocoder.getFromLocationName(
                                query, 5,
                                bounds.minLat, bounds.minLng, bounds.maxLat, bounds.maxLng
                            ) { addresses ->
                                if (cont.isActive) cont.resume(addresses)
                            }
                        } else {
                            geocoder.getFromLocationName(query, 5) { addresses ->
                                if (cont.isActive) cont.resume(addresses)
                            }
                        }
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        @Suppress("DEPRECATION")
                        if (bounds != null) {
                            geocoder.getFromLocationName(
                                query, 5,
                                bounds.minLat, bounds.minLng, bounds.maxLat, bounds.maxLng
                            ).orEmpty()
                        } else {
                            geocoder.getFromLocationName(query, 5).orEmpty()
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
        }.orEmpty()
        return results.map { it.latitude to it.longitude }
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
