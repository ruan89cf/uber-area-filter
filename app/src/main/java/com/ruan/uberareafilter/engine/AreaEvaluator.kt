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
import com.ruan.uberareafilter.model.ZoneStreetIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    INSIDE_AREA,    // 🟢 Card Verde (Embarque e Destino comprovadamente na zona permitida)
    INTER_ZONE,     // 🟣 Card Roxo (Corrida entre zonas diferentes de aceitacao)
    RETURN_TO_AREA, // 🟡 Card Amarelo (Motorista fora da area + embarque <= 1,0 km — sem auto-recusa p/ retornar a area)
    UNCERTAIN,      // 🟠 Card Laranja (Local incerto: nunca aceita sozinho; recusa so se voce ativar)
    OUTSIDE_AREA,   // 🔴 Card Vermelho (Fora dos criterios — auto-recusa)
    UNKNOWN_KEEP
}

data class EvaluationResult(
    val decision: Decision,
    val reason: String,
    val checklistText: String = reason,
    // Zona permitida onde cai o destino (para pintar o card com a cor da zona)
    val dropoffZoneId: Long? = null,
    // Enderecos de locais bloqueados aprendidos nesta oferta (o servico grava para as proximas)
    val learnedBlockedPlaces: List<BlockedPlace> = emptyList()
)

/** Candidato bruto devolvido pelo Geocoder (copiado de android.location.Address para ser testavel). */
data class GeoCandidate(
    val lat: Double,
    val lng: Double,
    val thoroughfare: String?,
    val subThoroughfare: String?,
    val featureName: String?,
    val subLocality: String?,
    val locality: String?,
    val subAdminArea: String?,
    val adminArea: String?,
    val addressLine: String?
)

/**
 * Avaliador de corridas:
 * - Checklist visual ✔ / ✘ / ⚠ para cada requisito
 * - Motor de localizacao com NIVEL DE CONFIANCA (ver LocationRules):
 *   so aprova quando ha prova de que embarque/destino estao dentro da zona.
 * - Corrida entre zonas (card roxo), zonas proibidas, regra da Av. Brasil.
 */
class AreaEvaluator(private val context: Context? = null) {

    private val geoCache = LruCache<String, List<GeoCandidate>>(400)
    private val streetKeyCache = HashMap<String, List<Pair<StreetKey, String>>>()

    private val avBrasilNumberRegex = Regex(
        """(?:av\.?|avenida)\s+brasil\s*[,nº°:-]*\s*(\d{1,5})\b""",
        RegexOption.IGNORE_CASE
    )
    // Indicadores do lado Mare: SEMPRE palavra inteira ("mare" NAO pode casar com "Marechal Hermes")
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
    ).map { wordRegex(it) }
    private val bonsucessoSideIndicators = listOf(
        "bonsucesso",
        "ramos",
        "olaria",
        "penha",
        "manguinhos",
        "higienopolis"
    ).map { wordRegex(it) }

    suspend fun evaluate(
        offer: ParsedRideOffer,
        config: FilterConfig,
        driverFix: DriverFix? = null
    ): EvaluationResult {
        val learned = learnBlockedPlaces(offer, config)
        val result = evaluateCore(offer, config, driverFix)
        return if (learned.isEmpty()) result else result.copy(learnedBlockedPlaces = learned)
    }

    /**
     * Nome bloqueado que aparece como NOME DO LOCAL de um endereco com rua+numero
     * ("Caxias Shopping, Rodovia Washington Luiz, 2895") -> guarda o endereco para bloquear
     * as outras entradas/nomes do mesmo lugar ("Entrada norte, Rod. Washington Luiz, 2895").
     */
    internal fun learnBlockedPlaces(offer: ParsedRideOffer, config: FilterConfig): List<BlockedPlace> {
        if (config.blockedKeywords.isEmpty()) return emptyList()
        val out = mutableListOf<BlockedPlace>()
        for (term in config.blockedKeywords) {
            if (BlockedMatcher.addressKeyword(term) != null) continue
            for (addr in listOf(offer.pickupAddress, offer.dropoffAddress)) {
                val p = BlockedMatcher.learn(term, addr) ?: continue
                if (config.blockedPlaces.none { BlockedMatcher.samePlace(it, p) } && out.none { BlockedMatcher.samePlace(it, p) }) {
                    out.add(p)
                }
            }
        }
        return out
    }

    private suspend fun evaluateCore(
        offer: ParsedRideOffer,
        config: FilterConfig,
        driverFix: DriverFix?
    ): EvaluationResult = coroutineScope {
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

        // 3b. Filtro de R$ minimo por KM (valor da corrida / KM total)
        if (config.filterByPricePerKmEnabled && offer.pricePerKm != null) {
            val fmt = String.format(ptBr, "%.2f", offer.pricePerKm)
            val minFmt = String.format(ptBr, "%.2f", config.minPricePerKm)
            if (offer.pricePerKm + 0.0001 < config.minPricePerKm) {
                violations.add("R$/km: R$$fmt (mín R$$minFmt)")
                nonAreaViolations.add("R$/km")
                filterChecks.add("✘ R$$fmt/km (mín $minFmt)")
            } else {
                filterChecks.add("✔ R$$fmt/km")
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

        // 6. Lista de palavras/locais bloqueados manualmente — PALAVRA INTEIRA
        //    ("Penha" bloqueia "Rua X - Penha", mas "Luz" nao bloqueia mais "Luzia"/"Santa Luzia")
        val normalizedTexts = offer.allTexts.map { TextNormalizer.normalize(it) }
        var blockedHit: String? = null
        for (blocked in config.blockedKeywords) {
            if (BlockedMatcher.addressKeyword(blocked) != null) continue // endereco com numero: conferido abaixo
            val normBlocked = TextNormalizer.normalize(blocked)
            if (normBlocked.length < 2) continue
            val rx = wordRegex(normBlocked)
            if (normalizedTexts.any { rx.containsMatchIn(it) }) {
                blockedHit = blocked
                break
            }
        }
        // 6b. Mesmo ENDERECO de um local bloqueado (outro nome do mesmo lugar) ou "Rua X nº 123" da lista
        if (blockedHit == null) {
            blockedHit = BlockedMatcher.matchAddress(
                listOf(offer.pickupAddress, offer.dropoffAddress),
                config.blockedKeywords,
                config.blockedPlaces
            )
        }
        if (blockedHit != null) {
            violations.add("Bloqueado: $blockedHit")
            nonAreaViolations.add("Bloqueado: $blockedHit")
            filterChecks.add("✘ Bloqueado: $blockedHit")
        }

        // 7. Zonas Demarcadas no Mapa (Permitidas 🟢 e Proibidas 🔴)
        val allowedZones = config.mapZones.filter { it.mode == ZoneMode.ALLOWED }
        val blockedZones = config.mapZones.filter { it.mode == ZoneMode.BLOCKED }
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
        val hasStreetList = config.allowedKeywords.isNotEmpty() || config.trustedStreets.isNotEmpty()

        val pickup = offer.pickupAddress
        val dropoff = offer.dropoffAddress

        if (effectiveAllowedZones.isEmpty() && !hasStreetList && blockedZones.isEmpty()) {
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

        val uncertainties = mutableListOf<String>()

        fun lineFor(label: String, address: String, res: AreaVerdict, shortTag: String): String {
            return when (res.kind) {
                AreaVerdictKind.INSIDE -> "✔ $label: ${address.take(34)}"
                AreaVerdictKind.OUTSIDE -> {
                    violations.add("$shortTag fora (${res.reason})")
                    "✘ $label: ${res.reason}"
                }
                AreaVerdictKind.UNCERTAIN -> {
                    uncertainties.add("$shortTag incerto (${res.reason})")
                    "⚠ $label: ${res.reason}"
                }
            }
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

            val driverEvidence = LocationRules.driverPickupEvidence(
                driverFix, offer.pickupDistanceKm, effectiveAllowedZones, blockedZones
            )
            val pickupDeferred = async { checkAddressInArea(pickup, config, effectiveAllowedZones, blockedZones, driverEvidence) }
            val dropoffDeferred = async { checkAddressInArea(dropoff, config, effectiveAllowedZones, blockedZones, null) }
            val pickupRes = pickupDeferred.await()
            val dropoffRes = dropoffDeferred.await()

            // Leva o motorista PARA a area: embarque fora/incerto, destino dentro (e nada proibido)
            val headsIntoArea = config.acceptRidesIntoArea &&
                !pickupRes.ok && dropoffRes.ok &&
                !pickupRes.explicitBlocked && !dropoffRes.explicitBlocked
            val pickupLine = if (headsIntoArea) {
                "✔ Embarque fora, leva p/ área: ${pickup.take(28)}"
            } else {
                lineFor("Embarque", pickup, pickupRes, "Emb.")
            }
            val dropoffLine = lineFor("Destino", dropoff, dropoffRes, "Dest.")

            // Corrida ENTRE duas zonas permitidas diferentes (Zona A -> Zona B).
            // Voltar para a Zona 1 (principal, a primeira criada) nunca conta como entre zonas.
            val mainZoneId = effectiveAllowedZones.firstOrNull()?.id
            val returnsToMainZone = pickupRes.ok && dropoffRes.ok && effectiveAllowedZones.size > 1 &&
                pickupRes.zoneId != null && dropoffRes.zoneId == mainZoneId && pickupRes.zoneId != mainZoneId
            val isDifferentZones = pickupRes.ok &&
                dropoffRes.ok &&
                effectiveAllowedZones.size > 1 &&
                pickupRes.zoneId != null &&
                dropoffRes.zoneId != null &&
                pickupRes.zoneId != dropoffRes.zoneId &&
                !returnsToMainZone
            if (returnsToMainZone) filterChecks.add("✔ Volta p/ ${dropoffRes.zoneName ?: "zona principal"}")

            if (isDifferentZones && !config.allowInterZoneRides) {
                violations.add("Entre zonas (${pickupRes.zoneName} ➔ ${dropoffRes.zoneName})")
                filterChecks.add("✘ Entre zonas (${pickupRes.zoneName} ➔ ${dropoffRes.zoneName})")
            } else if (isDifferentZones && config.allowInterZoneRides) {
                filterChecks.add("🟣 ${pickupRes.zoneName} ➔ ${dropoffRes.zoneName}")
            }

            // Card Amarelo 🟡 (retorno para a zona): embarque fora/incerto a <= 1 km e DESTINO comprovadamente dentro.
            val isExplicitlyForbiddenArea = pickupRes.explicitBlocked || dropoffRes.explicitBlocked
            val isPickupWithin1Km = offer.pickupDistanceKm != null && offer.pickupDistanceKm <= 1.05
            if (config.yellowCardOutsideAreaEnabled &&
                !headsIntoArea &&
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

            if (uncertainties.isNotEmpty()) {
                return@coroutineScope EvaluationResult(
                    Decision.UNCERTAIN,
                    "⚠ " + uncertainties.joinToString(" • "),
                    checklist
                )
            }

            if (isDifferentZones && config.allowInterZoneRides) {
                return@coroutineScope EvaluationResult(
                    Decision.INTER_ZONE,
                    "🟣 Corrida entre zonas: ${pickupRes.zoneName} ➔ ${dropoffRes.zoneName}",
                    checklist,
                    dropoffZoneId = dropoffRes.zoneId
                )
            }

            return@coroutineScope EvaluationResult(
                Decision.INSIDE_AREA,
                if (headsIntoArea) "✔ Leva você para a área (embarque fora, destino: ${dropoffRes.reason})"
                else "✔ Embarque e Destino na área (${dropoffRes.reason})",
                checklist,
                dropoffZoneId = dropoffRes.zoneId
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

            val driverEvidence = LocationRules.driverPickupEvidence(
                driverFix, offer.pickupDistanceKm, effectiveAllowedZones, blockedZones
            )
            val targetDeferred = async {
                checkAddressInArea(
                    target, config, effectiveAllowedZones, blockedZones,
                    if (isDropoffTarget) null else driverEvidence
                )
            }
            val pickupInfoDeferred = if (isDropoffTarget && !pickup.isNullOrBlank()) {
                async { checkAddressInArea(pickup, config, effectiveAllowedZones, blockedZones, driverEvidence) }
            } else {
                null
            }
            val res = targetDeferred.await()
            val pickupRes = pickupInfoDeferred?.await()

            val addrCheckLine = lineFor(addrLabel, target, res, if (isDropoffTarget) "Dest." else "Emb.")

            val pickupDisplayLine = if (isDropoffTarget && !pickup.isNullOrBlank()) {
                "📍 Embarque: ${pickup.take(34)}"
            } else {
                null
            }

            // Tambem bloqueia caso o embarque caia explicitamente em Zona Proibida 🔴 / Lado Par Mare
            if (pickupRes?.explicitBlocked == true && isDropoffTarget) {
                violations.add("Emb. bloqueado (${pickupRes.reason})")
            }

            val mainZoneId = effectiveAllowedZones.firstOrNull()?.id
            val returnsToMainZone = isDropoffTarget && pickupRes != null && pickupRes.ok && res.ok &&
                effectiveAllowedZones.size > 1 && pickupRes.zoneId != null &&
                res.zoneId == mainZoneId && pickupRes.zoneId != mainZoneId
            val isDifferentZones = isDropoffTarget &&
                pickupRes != null &&
                pickupRes.ok &&
                res.ok &&
                effectiveAllowedZones.size > 1 &&
                pickupRes.zoneId != null &&
                res.zoneId != null &&
                pickupRes.zoneId != res.zoneId &&
                !returnsToMainZone
            if (returnsToMainZone) filterChecks.add("✔ Volta p/ ${res.zoneName ?: "zona principal"}")

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

            if (uncertainties.isNotEmpty()) {
                return@coroutineScope EvaluationResult(
                    Decision.UNCERTAIN,
                    "⚠ " + uncertainties.joinToString(" • "),
                    checklist
                )
            }

            if (isDifferentZones && config.allowInterZoneRides) {
                return@coroutineScope EvaluationResult(
                    Decision.INTER_ZONE,
                    "🟣 Corrida entre zonas: ${pickupRes?.zoneName} ➔ ${res.zoneName}",
                    checklist,
                    dropoffZoneId = res.zoneId
                )
            }

            return@coroutineScope EvaluationResult(
                Decision.INSIDE_AREA,
                "✔ $addrLabel na área (${res.reason})",
                checklist,
                dropoffZoneId = if (isDropoffTarget) res.zoneId else null
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

    /**
     * Mantido por compatibilidade: nucleo normalizado de uma rua ("R. Pirangi" == "Rua Pirangi").
     */
    internal fun normalizeStreetCore(raw: String): String =
        AddressParser.streetKeyOf(raw)?.core ?: TextNormalizer.normalize(raw)

    // ------------------------------------------------------------------
    // Localizacao de um endereco
    // ------------------------------------------------------------------

    private suspend fun checkAddressInArea(
        address: String,
        config: FilterConfig,
        allowedZones: List<MapZone>,
        blockedZones: List<MapZone>,
        driverEvidence: AreaVerdict?
    ): AreaVerdict {
        val normAddr = TextNormalizer.normalize(address)

        if (config.avBrasilOddSideOnly && isAvBrasilAddress(normAddr)) {
            val avBrasilViolation = evaluateAvBrasilSide(address, normAddr)
            if (avBrasilViolation != null) {
                return AreaVerdict(AreaVerdictKind.OUTSIDE, avBrasilViolation, explicitBlocked = true)
            }
        }

        val parts = AddressParser.parse(address)
        val street = streetEvidence(parts, config, allowedZones)

        // Atalho (sem rede = decisao instantanea): rua que nao encosta em nenhuma zona.
        // Rua INTERNA continua consultando o mapa: o Rio tem muitas ruas homonimas
        // ("Rua Sao Jorge" em varios bairros) e o bairro do card e a unica forma de pegar a errada.
        val skipNetwork = blockedZones.isEmpty() && driverEvidence == null &&
            street.knowledge == StreetKnowledge.NOT_IN_ZONE
        val needsGeocode = !skipNetwork
        val located = if (needsGeocode && (allowedZones.isNotEmpty() || blockedZones.isNotEmpty())) {
            locate(parts, allowedZones, blockedZones)
        } else {
            LocateResult(emptyList(), false)
        }

        // Local bloqueado com coordenada: endereco com numero (ou ponto de referencia) a ate 150 m dele
        if (config.blockedPlaces.any { it.lat != null }) {
            val near = located.fixes
                .filter { it.precision == FixPrecision.NUMBER || (it.precision == FixPrecision.AREA && parts.poi != null && !parts.hasStreet) }
                .firstNotNullOfOrNull { f -> BlockedMatcher.nearPlace(f.lat, f.lng, config.blockedPlaces) }
            if (near != null) {
                return AreaVerdict(AreaVerdictKind.OUTSIDE, "Perto de ${near.term} (bloqueado)", explicitBlocked = true)
            }
        }

        return LocationRules.decide(
            parts, street, located.fixes, allowedZones, blockedZones, driverEvidence,
            homonymSuspected = located.homonymSuspected
        )
    }

    private fun keysFor(cacheKey: String, names: List<String>): List<Pair<StreetKey, String>> {
        return synchronized(streetKeyCache) {
            streetKeyCache.getOrPut(cacheKey) {
                names.mapNotNull { n -> AddressParser.streetKeyOf(n)?.let { it to n } }
            }
        }
    }

    private fun streetEvidence(parts: AddressParts, config: FilterConfig, allowedZones: List<MapZone>): StreetEvidence {
        val key = parts.street ?: return StreetEvidence(StreetKnowledge.UNKNOWN)

        // Indices validos (geometria da zona nao mudou desde a varredura)
        val validIndex = HashMap<Long, ZoneStreetIndex>()
        for (z in allowedZones) {
            val idx = config.streetIndex[z.id] ?: continue
            if (idx.complete && idx.geometryHash == GeoMath.indexKeyFor(z, config.mapZones)) {
                validIndex[z.id] = idx
            }
        }

        // a) Rua INTERNA de alguma zona -> pode aprovar so pelo nome
        for (z in allowedZones) {
            val idx = validIndex[z.id] ?: continue
            val hit = keysFor("i:${z.id}:${idx.builtAtMs}", idx.internalStreets)
                .firstOrNull { AddressParser.strictMatch(it.first, key) }
            if (hit != null) return StreetEvidence(StreetKnowledge.INTERNAL, z, hit.second)
        }

        // b) Ruas que voce marcou como confiaveis pelo historico
        val trustedHit = keysFor("t:${config.trustedStreets.hashCode()}", config.trustedStreets)
            .firstOrNull { AddressParser.strictMatch(it.first, key) }

        // c) Ruas que encostam na zona (inclui as de divisa)
        var touchingZone: MapZone? = null
        var touchingName: String? = null
        for (z in allowedZones) {
            val idx = validIndex[z.id]
            val names = idx?.touchingStreets ?: z.streetNames
            val cacheKey = if (idx != null) "x:${z.id}:${idx.builtAtMs}" else "z:${z.id}:${z.streetNames.hashCode()}"
            val hit = keysFor(cacheKey, names).firstOrNull { AddressParser.looseMatch(it.first, key) }
            if (hit != null) {
                touchingZone = z
                touchingName = hit.second
                break
            }
        }
        if (touchingZone == null) {
            val hit = keysFor("g:${config.allowedKeywords.hashCode()}", config.allowedKeywords)
                .firstOrNull { AddressParser.looseMatch(it.first, key) }
            if (hit != null) {
                touchingZone = allowedZones.firstOrNull()
                touchingName = hit.second
            }
        }

        if (trustedHit != null) {
            return StreetEvidence(StreetKnowledge.TRUSTED, touchingZone ?: allowedZones.firstOrNull(), trustedHit.second)
        }
        if (touchingName != null) {
            return StreetEvidence(StreetKnowledge.TOUCHES, touchingZone, touchingName)
        }

        // d) Nenhuma lista citou a rua: so e "fora" se TODAS as zonas tem lista confiavel
        //    (ou, sem zona no mapa, se existe a lista manual de ruas)
        val everyZoneHasList = if (allowedZones.isEmpty()) {
            config.allowedKeywords.isNotEmpty()
        } else {
            allowedZones.all { validIndex.containsKey(it.id) || it.streetNames.isNotEmpty() }
        }
        return if (everyZoneHasList) {
            StreetEvidence(StreetKnowledge.NOT_IN_ZONE)
        } else {
            StreetEvidence(StreetKnowledge.UNKNOWN)
        }
    }

    private data class LocateResult(val fixes: List<GeoFix>, val homonymSuspected: Boolean)

    /**
     * Consulta o Geocoder em PARALELO (rua+numero e rua sem numero, ou POI/bairro; cada uma com e sem
     * caixa de busca), com orcamento total de ~2,5 s, e valida cada candidato contra o card.
     * A busca SEM caixa mostra onde a rua realmente fica (ex.: a "Rua Sao Jorge" do card e a de
     * Campo Grande, nao a da sua zona). Um resultado longe nunca aprova: ele cai fora da area.
     */
    private suspend fun locate(
        parts: AddressParts,
        allowedZones: List<MapZone>,
        blockedZones: List<MapZone>
    ): LocateResult {
        val zones = allowedZones + blockedZones
        val ctx = context ?: return LocateResult(emptyList(), false)
        if (!Geocoder.isPresent()) return LocateResult(emptyList(), false)

        val queries = buildList {
            if (parts.hasStreet) {
                parts.canonicalQuery(withNumber = true)?.let { add(it) }
                if (parts.hasNumber) parts.canonicalQuery(withNumber = false)?.let { add(it) }
            } else {
                add(parts.placeQuery())
            }
        }.distinct()
        if (queries.isEmpty()) return LocateResult(emptyList(), false)

        val bounds = computeZoneBoundingBox(zones)
        // Bairro do card sozinho (resultado vai pro cache: os bairros se repetem o turno inteiro)
        val bairroQuery = if (parts.hasStreet && parts.bairroRaw != null) {
            "${parts.bairroRaw}, ${parts.cityRaw ?: "Rio de Janeiro"}, RJ"
        } else {
            null
        }
        var bairroCandidates: List<GeoCandidate> = emptyList()
        val candidateLists = coroutineScope {
            val bairroDeferred = bairroQuery?.let { bq -> async { runCachedGeocode(ctx, bq, null) } }
            val lists = queries.flatMap { q ->
                listOfNotNull(
                    async { runCachedGeocode(ctx, q, bounds) },
                    if (bounds != null) async { runCachedGeocode(ctx, q, null) } else null
                )
            }.awaitAll()
            bairroCandidates = bairroDeferred?.await().orEmpty()
            lists
        }

        fun depthToAllowed(lat: Double, lng: Double): Double =
            GeoMath.bestZoneDepth(lat, lng, allowedZones)?.second ?: Double.NEGATIVE_INFINITY

        val fixes = mutableListOf<GeoFix>()
        var streetInOtherBairroFar = false
        for (list in candidateLists) {
            for (c in list) {
                if (allowedZones.isNotEmpty() &&
                    GeoCandidateRules.isSameStreetInOtherBairro(c, parts) &&
                    depthToAllowed(c.lat, c.lng) < LocationRules.STREET_CONTRADICTION_M
                ) {
                    streetInOtherBairroFar = true
                }
                val precision = GeoCandidateRules.classify(c, parts) ?: continue
                if (fixes.none { it.precision == precision && haversineKm(it.lat, it.lng, c.lat, c.lng) < 0.01 }) {
                    fixes.add(GeoFix(c.lat, c.lng, precision))
                }
            }
        }
        // O bairro do card fica longe da area? Entao a "rua interna" de mesmo nome e outra rua (homonimo).
        val bairroHits = bairroCandidates.filter { GeoCandidateRules.isBairroResult(it, parts) }
        val bairroFar = allowedZones.isNotEmpty() && bairroHits.isNotEmpty() &&
            bairroHits.all { depthToAllowed(it.lat, it.lng) < -LocationRules.BAIRRO_FAR_M }

        return LocateResult(
            GeoCandidateRules.applyConsistency(fixes, parts),
            homonymSuspected = streetInOtherBairroFar || bairroFar
        )
    }

    private fun zonesFor(config: FilterConfig): Pair<List<MapZone>, List<MapZone>> {
        val allowed = config.mapZones.filter { it.mode == ZoneMode.ALLOWED }.ifEmpty {
            if (config.polygonPoints.size >= 3) {
                listOf(MapZone(1L, "Zona 1", ZoneMode.ALLOWED, ZoneShape.POLYGON, config.polygonPoints))
            } else {
                emptyList()
            }
        }
        return allowed to config.mapZones.filter { it.mode == ZoneMode.BLOCKED }
    }

    /**
     * Coordenada de um endereco do card (rua+numero, rua ou ponto de referencia), validada contra o texto.
     * Usa o mesmo cache do avaliador: o destino da corrida atual normalmente ja foi consultado.
     */
    suspend fun pointOf(address: String, config: FilterConfig): Pair<Double, Double>? {
        if (address.isBlank()) return null
        val (allowed, blocked) = zonesFor(config)
        val parts = AddressParser.parse(address)
        val located = locate(parts, allowed, blocked)
        val best = located.fixes.minByOrNull { it.precision.ordinal } ?: return null
        return best.lat to best.lng
    }

    /**
     * Acha no mapa um NOME bloqueado (ex.: "caxias shopping") perto das suas zonas: devolve rua, numero e
     * coordenada para bloquear tambem as outras entradas/nomes do mesmo lugar. Bairro (sem rua) nao conta.
     */
    suspend fun resolveBlockedTerm(term: String, config: FilterConfig): BlockedPlace? {
        val ctx = context ?: return null
        if (!Geocoder.isPresent()) return null
        val nt = TextNormalizer.normalize(term).trim()
        if (nt.length < 4) return null
        if (AddressParser.parse(term).street != null) return null // nome de rua: nao e um local
        val (allowed, blocked) = zonesFor(config)
        val bounds = computeZoneBoundingBox(allowed + blocked) ?: return null
        val rx = wordRegex(nt)
        val hit = runCachedGeocode(ctx, term, bounds).firstOrNull { c ->
            val feature = c.featureName?.trim().orEmpty()
            val street = c.thoroughfare?.trim().orEmpty()
            if (feature.isEmpty() || street.isEmpty()) return@firstOrNull false
            val nf = TextNormalizer.normalize(feature)
            // "Avenida Beira Mar" / "Rua Doutor Noguchi" sao RUAS, nao locais: so vale nome proprio (shopping, hospital...)
            val featureIsStreet = AddressParser.streetKeyOf(feature)?.type != null ||
                nf == TextNormalizer.normalize(street) ||
                feature.all { it.isDigit() || it == '-' || it == ' ' }
            !featureIsStreet && rx.containsMatchIn(nf) &&
                c.lat in bounds.minLat..bounds.maxLat && c.lng in bounds.minLng..bounds.maxLng
        } ?: return null
        val street = hit.thoroughfare?.trim().orEmpty()
        val number = hit.subThoroughfare?.let { Regex("[0-9]{1,5}").find(it)?.value }.orEmpty()
        return BlockedPlace(term.trim(), street, BlockedMatcher.normNumber(number), hit.lat, hit.lng)
    }

    private suspend fun runCachedGeocode(ctx: Context, query: String, bounds: GeoBounds?): List<GeoCandidate> {
        val cacheKey = "$query|${bounds?.hashCode() ?: 0}"
        geoCache.get(cacheKey)?.let { return it }
        val result = runSingleGeocode(ctx, query, bounds) ?: return emptyList()
        geoCache.put(cacheKey, result)
        return result
    }

    /** @return null quando estourou o tempo/deu erro (nao entra no cache). */
    private suspend fun runSingleGeocode(
        ctx: Context,
        query: String,
        bounds: GeoBounds?
    ): List<GeoCandidate>? {
        val results: List<Address>? = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
            val geocoder = Geocoder(ctx, Locale("pt", "BR"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<Address>?> { cont ->
                    val listener = object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses)
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    try {
                        if (bounds != null) {
                            geocoder.getFromLocationName(
                                query, 5,
                                bounds.minLat, bounds.minLng, bounds.maxLat, bounds.maxLng,
                                listener
                            )
                        } else {
                            geocoder.getFromLocationName(query, 5, listener)
                        }
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null)
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
                        null
                    }
                }
            }
        }
        return results?.map { a ->
            GeoCandidate(
                lat = a.latitude,
                lng = a.longitude,
                thoroughfare = a.thoroughfare,
                subThoroughfare = a.subThoroughfare,
                featureName = a.featureName,
                subLocality = a.subLocality,
                locality = a.locality,
                subAdminArea = a.subAdminArea,
                adminArea = a.adminArea,
                addressLine = runCatching { a.getAddressLine(0) }.getOrNull()
            )
        }
    }

    private fun isAvBrasilAddress(normAddr: String): Boolean {
        return normAddr.contains("av brasil") ||
            normAddr.contains("av. brasil") ||
            normAddr.contains("avenida brasil")
    }

    private fun evaluateAvBrasilSide(rawAddress: String, normAddr: String): String? {
        if (mareSideIndicators.any { it.containsMatchIn(normAddr) }) {
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

        if (bonsucessoSideIndicators.none { it.containsMatchIn(normAddr) }) {
            return "Av. Brasil s/ nº ímpar (${rawAddress.take(20)})"
        }

        return null
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
        // ~9 km ao redor: grande o bastante para o Geocoder devolver o lugar REAL (e nao um homonimo
        // "forcado" para dentro da caixa); o que cair fora da zona e tratado como fora.
        val pad = 0.08
        return GeoBounds(minLat - pad, minLng - pad, maxLat + pad, maxLng + pad)
    }

    companion object {
        private const val GEOCODE_TIMEOUT_MS = 2400L

        internal fun wordRegex(normalizedTerm: String): Regex =
            Regex("""(^|[^a-z0-9])""" + Regex.escape(normalizedTerm) + """($|[^a-z0-9])""")

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
            return GeoMath.depthMeters(lat, lng, zone) >= -toleranceMeters
        }

        fun isPointInOrNearPolygon(
            lat: Double,
            lng: Double,
            polygon: List<Pair<Double, Double>>,
            toleranceMeters: Double = 25.0
        ): Boolean {
            if (polygon.size < 3) return false
            return GeoMath.polygonDepthMeters(lat, lng, polygon) >= -toleranceMeters
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
