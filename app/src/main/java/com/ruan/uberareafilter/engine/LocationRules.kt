package com.ruan.uberareafilter.engine

import com.ruan.uberareafilter.model.MapZone

/**
 * Regras de decisao geografica (Kotlin puro, testavel no JVM).
 *
 * Principio: so aprova (INSIDE) quando ha PROVA de que o endereco esta dentro da zona.
 * Quando as evidencias nao bastam, devolve UNCERTAIN — que NUNCA e aceito automaticamente.
 *
 * Evidencias, da mais forte para a mais fraca:
 *  1. Zona proibida (qualquer coordenada validada caindo nela ou a ate 25 m dela)  -> OUTSIDE
 *  2. Coordenada do NUMERO (rua + numero conferidos com o card, bairro sem conflito)
 *       >= 40 m dentro da divisa -> INSIDE | >= 40 m fora -> OUTSIDE | faixa da divisa -> depende da rua
 *  3. Rua INTERNA (traçado inteiro dentro da zona, calculado do mapa) ou rua confiavel (marcada por voce) -> INSIDE
 *  4. Rua de DIVISA (entra e sai da zona) sem numero -> UNCERTAIN
 *  5. Rua que nao encosta em nenhuma zona -> OUTSIDE
 *  6. So POI/bairro: so decide se o ponto estiver bem longe da divisa
 */
enum class AreaVerdictKind { INSIDE, OUTSIDE, UNCERTAIN }

data class AreaVerdict(
    val kind: AreaVerdictKind,
    val reason: String,
    val zoneId: Long? = null,
    val zoneName: String? = null,
    val explicitBlocked: Boolean = false
) {
    val ok: Boolean get() = kind == AreaVerdictKind.INSIDE
}

enum class FixPrecision { NUMBER, STREET, AREA }

data class GeoFix(
    val lat: Double,
    val lng: Double,
    val precision: FixPrecision
)

enum class StreetKnowledge { INTERNAL, TRUSTED, TOUCHES, NOT_IN_ZONE, UNKNOWN }

data class StreetEvidence(
    val knowledge: StreetKnowledge,
    val zone: MapZone? = null,
    val matchedName: String? = null
)

/** Posicao atual do motorista (GPS do aparelho). */
data class DriverFix(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Double,
    val ageMs: Long
)

object LocationRules {

    const val BLOCKED_BUFFER_M = 25.0
    const val NUMBER_MIN_DEPTH_M = 40.0
    const val NUMBER_OUTSIDE_DEPTH_M = -40.0
    const val PLACE_MIN_DEPTH_M = 250.0
    const val PLACE_OUTSIDE_DEPTH_M = -250.0
    const val STREET_FAR_OUTSIDE_M = -600.0
    const val STREET_CONTRADICTION_M = -400.0
    const val BAIRRO_ONLY_OUTSIDE_M = -1500.0
    const val BAIRRO_FAR_M = 2500.0
    const val DRIVER_MAX_AGE_MS = 20_000L
    const val DRIVER_MAX_ACCURACY_M = 60.0

    fun decide(
        parts: AddressParts,
        street: StreetEvidence,
        fixes: List<GeoFix>,
        allowedZones: List<MapZone>,
        blockedZones: List<MapZone>,
        driverEvidence: AreaVerdict? = null,
        homonymSuspected: Boolean = false
    ): AreaVerdict {
        val label = (parts.streetRaw ?: parts.poi ?: parts.bairroRaw ?: parts.raw).take(32)

        val topPrecision = fixes.minByOrNull { it.precision.ordinal }?.precision
        val topFixes = fixes.filter { it.precision == topPrecision }

        // 1. Zona proibida vence tudo
        for (f in topFixes) {
            for (b in blockedZones) {
                if (GeoMath.depthMeters(f.lat, f.lng, b) >= -BLOCKED_BUFFER_M) {
                    return AreaVerdict(
                        AreaVerdictKind.OUTSIDE,
                        "Zona Proibida (${b.name})",
                        explicitBlocked = true
                    )
                }
            }
        }

        if (allowedZones.isEmpty()) {
            // Sem zona verde: vale a lista manual de ruas e/ou so as zonas proibidas
            return when (street.knowledge) {
                StreetKnowledge.INTERNAL, StreetKnowledge.TRUSTED, StreetKnowledge.TOUCHES ->
                    AreaVerdict(AreaVerdictKind.INSIDE, street.matchedName ?: label)
                StreetKnowledge.NOT_IN_ZONE ->
                    AreaVerdict(AreaVerdictKind.OUTSIDE, "$label (fora da lista)")
                StreetKnowledge.UNKNOWN -> if (topFixes.isNotEmpty() && blockedZones.isNotEmpty()) {
                    AreaVerdict(AreaVerdictKind.INSIDE, label)
                } else {
                    AreaVerdict(AreaVerdictKind.UNCERTAIN, "$label (sem localização)")
                }
            }
        }

        fun depthOf(f: GeoFix): Pair<MapZone, Double> =
            GeoMath.bestZoneDepth(f.lat, f.lng, allowedZones) ?: (allowedZones.first() to Double.NEGATIVE_INFINITY)

        val streetZone = street.zone
        val streetIsSafe = street.knowledge == StreetKnowledge.INTERNAL || street.knowledge == StreetKnowledge.TRUSTED

        // 2. Evidencia pelo GPS do motorista (so para EMBARQUE; calculada fora daqui)
        if (driverEvidence != null) {
            if (driverEvidence.kind == AreaVerdictKind.OUTSIDE) return driverEvidence
            if (driverEvidence.kind == AreaVerdictKind.INSIDE) {
                val strongContradiction = topPrecision == FixPrecision.NUMBER &&
                    topFixes.any { depthOf(it).second <= NUMBER_OUTSIDE_DEPTH_M }
                return if (strongContradiction) {
                    AreaVerdict(AreaVerdictKind.UNCERTAIN, "$label (GPS x mapa divergem)")
                } else {
                    driverEvidence
                }
            }
        }

        // 3. Coordenada do numero
        if (topPrecision == FixPrecision.NUMBER) {
            val depths = topFixes.map { depthOf(it) }
            val primary = depths.first()
            val minD = depths.minOf { it.second }
            val maxD = depths.maxOf { it.second }
            val fmt = String.format(java.util.Locale("pt", "BR"), "%.0f m", primary.second)

            val allDeepInside = topFixes.all {
                GeoMath.unionDepthAtLeast(it.lat, it.lng, allowedZones, NUMBER_MIN_DEPTH_M)
            }
            if (allDeepInside) {
                return AreaVerdict(AreaVerdictKind.INSIDE, label, primary.first.id, primary.first.name)
            }
            if (maxD <= NUMBER_OUTSIDE_DEPTH_M) {
                return if (streetIsSafe) {
                    AreaVerdict(AreaVerdictKind.UNCERTAIN, "$label (mapa: fora $fmt, rua interna)")
                } else {
                    AreaVerdict(AreaVerdictKind.OUTSIDE, "$label (${outsideBy(maxD)} fora da área)")
                }
            }
            if (primary.second >= 0.0 && minD > NUMBER_OUTSIDE_DEPTH_M && streetIsSafe) {
                val z = streetZone ?: primary.first
                return AreaVerdict(AreaVerdictKind.INSIDE, label, z.id, z.name)
            }
            // Profundidade positiva = dentro da zona; negativa = fora (antes aparecia "-28 m", confuso)
            val side = if (primary.second >= 0.0) "por dentro" else "por fora"
            val absFmt = String.format(java.util.Locale("pt", "BR"), "%.0f m", kotlin.math.abs(primary.second))
            return AreaVerdict(AreaVerdictKind.UNCERTAIN, "$label (na divisa: $absFmt $side)")
        }

        // 4. Sem coordenada do numero: decide pela rua
        return when (street.knowledge) {
            StreetKnowledge.INTERNAL, StreetKnowledge.TRUSTED -> {
                val farAway = topPrecision == FixPrecision.STREET &&
                    topFixes.isNotEmpty() &&
                    topFixes.all { depthOf(it).second < STREET_CONTRADICTION_M }
                // Sem confirmacao da rua no bairro do card E sinal de homonimo
                // (mesma rua achada em outro bairro longe da area, ou bairro do card longe da area)
                val homonym = topPrecision != FixPrecision.STREET && homonymSuspected
                if (farAway || homonym) {
                    AreaVerdict(AreaVerdictKind.UNCERTAIN, "$label (rua homônima?)")
                } else {
                    val z = streetZone ?: allowedZones.first()
                    val tag = if (street.knowledge == StreetKnowledge.TRUSTED) " ★" else ""
                    AreaVerdict(AreaVerdictKind.INSIDE, "${street.matchedName ?: label}$tag", z.id, z.name)
                }
            }

            StreetKnowledge.TOUCHES -> {
                if (topPrecision == FixPrecision.STREET && topFixes.isNotEmpty() &&
                    topFixes.all { depthOf(it).second <= STREET_FAR_OUTSIDE_M }
                ) {
                    // O trecho dessa rua no bairro informado fica longe da zona
                    AreaVerdict(AreaVerdictKind.OUTSIDE, "$label (trecho fora da área)")
                } else {
                    AreaVerdict(AreaVerdictKind.UNCERTAIN, "$label (rua de divisa sem nº)")
                }
            }

            StreetKnowledge.NOT_IN_ZONE -> {
                val mapSaysInside = topPrecision == FixPrecision.STREET &&
                    topFixes.any { GeoMath.unionDepthAtLeast(it.lat, it.lng, allowedZones, NUMBER_MIN_DEPTH_M) }
                if (mapSaysInside) {
                    AreaVerdict(AreaVerdictKind.UNCERTAIN, "$label (fora da lista de ruas)")
                } else {
                    AreaVerdict(AreaVerdictKind.OUTSIDE, "$label (fora da área)")
                }
            }

            StreetKnowledge.UNKNOWN -> decideWithoutStreetKnowledge(parts, label, topPrecision, topFixes, allowedZones, ::depthOf)
        }
    }

    private fun decideWithoutStreetKnowledge(
        parts: AddressParts,
        label: String,
        topPrecision: FixPrecision?,
        topFixes: List<GeoFix>,
        allowedZones: List<MapZone>,
        depthOf: (GeoFix) -> Pair<MapZone, Double>
    ): AreaVerdict {
        if (topFixes.isEmpty() || topPrecision == null) {
            return AreaVerdict(AreaVerdictKind.UNCERTAIN, "$label (sem localização)")
        }
        val depths = topFixes.map { depthOf(it) }
        val maxD = depths.maxOf { it.second }
        val primary = depths.first()

        return when {
            // Tem rua, mas nao ha lista de ruas da zona: so o centro da rua no bairro
            parts.hasStreet -> when {
                maxD <= STREET_FAR_OUTSIDE_M -> AreaVerdict(AreaVerdictKind.OUTSIDE, "$label (fora da área)")
                else -> AreaVerdict(AreaVerdictKind.UNCERTAIN, "$label (sem nº e sem lista de ruas)")
            }
            // Ponto de referencia (POI) + bairro
            parts.poi != null -> when {
                topFixes.all { GeoMath.unionDepthAtLeast(it.lat, it.lng, allowedZones, PLACE_MIN_DEPTH_M) } ->
                    AreaVerdict(AreaVerdictKind.INSIDE, label, primary.first.id, primary.first.name)
                maxD <= PLACE_OUTSIDE_DEPTH_M -> AreaVerdict(AreaVerdictKind.OUTSIDE, "$label (${outsideBy(maxD)} fora da área)")
                else -> AreaVerdict(AreaVerdictKind.UNCERTAIN, "$label (local perto da divisa)")
            }
            // So o bairro: o bairro inteiro pode passar da divisa, entao nunca aprova so por ele
            else -> when {
                maxD <= BAIRRO_ONLY_OUTSIDE_M -> AreaVerdict(AreaVerdictKind.OUTSIDE, "$label (bairro fora da área)")
                else -> AreaVerdict(AreaVerdictKind.UNCERTAIN, "$label (só bairro, sem rua)")
            }
        }
    }

    /**
     * Prova geometrica para o EMBARQUE usando o GPS do motorista:
     * a distancia de rota ate o embarque (lida no card) e sempre >= a distancia em linha reta.
     * Se o motorista esta mais fundo dentro da zona do que essa distancia (com folga de 50% para erro de OCR,
     * + precisao do GPS + 100 m), o embarque esta garantidamente dentro. O raciocinio inverso prova "fora".
     */
    fun driverPickupEvidence(
        driver: DriverFix?,
        pickupDistanceKm: Double?,
        allowedZones: List<MapZone>,
        blockedZones: List<MapZone>
    ): AreaVerdict? {
        if (driver == null || pickupDistanceKm == null || pickupDistanceKm <= 0.0) return null
        if (driver.ageMs > DRIVER_MAX_AGE_MS || driver.accuracyMeters > DRIVER_MAX_ACCURACY_M) return null
        if (allowedZones.isEmpty()) return null

        val radius = pickupDistanceKm * 1000.0 * 1.5 + driver.accuracyMeters + 100.0
        val best = GeoMath.bestZoneDepth(driver.lat, driver.lng, allowedZones) ?: return null
        val blockedNear = blockedZones.any { GeoMath.depthMeters(driver.lat, driver.lng, it) >= -radius }

        if (!blockedNear && GeoMath.unionDepthAtLeast(driver.lat, driver.lng, allowedZones, radius)) {
            return AreaVerdict(
                AreaVerdictKind.INSIDE,
                "Emb. a ${fmtKm(pickupDistanceKm)} de você (dentro)",
                best.first.id,
                best.first.name
            )
        }
        if (best.second <= -radius) {
            return AreaVerdict(
                AreaVerdictKind.OUTSIDE,
                "Emb. a ${fmtKm(pickupDistanceKm)} de você, fora da área"
            )
        }
        return null
    }

    private fun fmtKm(km: Double): String = String.format(java.util.Locale("pt", "BR"), "%.1f km", km)

    /** Profundidade negativa (metros fora) -> "350 m" / "1,8 km": quanto falta para voltar a area. */
    internal fun outsideBy(depthMeters: Double): String {
        val m = kotlin.math.abs(depthMeters)
        return if (m < 1000.0) String.format(java.util.Locale("pt", "BR"), "%.0f m", (m / 10.0).toInt() * 10.0)
        else String.format(java.util.Locale("pt", "BR"), "%.1f km", m / 1000.0)
    }
}

/**
 * Validacao dos candidatos do Geocoder contra o texto do card.
 * Um candidato so vira evidencia se for o MESMO logradouro (e, quando houver, o mesmo bairro/cidade).
 * Isso elimina o erro antigo de aceitar o 4º/5º palpite do Geocoder ou um homonimo em outro bairro.
 */
object GeoCandidateRules {

    fun classify(c: GeoCandidate, parts: AddressParts): FixPrecision? {
        // Estado: tem que ser RJ quando o Geocoder informar
        c.adminArea?.let { st ->
            val n = AddressParser.normalizeLoose(st)
            if (n.isNotEmpty() && n != "rj" && !n.contains("rio de janeiro")) return null
        }
        // Cidade do card x cidade do resultado (ex.: "Belford Roxo" no card nao aceita resultado no Rio)
        if (parts.city != null) {
            val geoCity = c.locality ?: c.subAdminArea
            if (geoCity != null && AddressParser.bairroCompatible(parts.city, geoCity) == false) return null
        }
        // Bairro do card x bairro do resultado
        val cardBairro: String? = parts.bairro
        val geoBairro: String? = c.subLocality
        val geoLine: String? = c.addressLine
        val bairroStatus: Boolean? = when {
            cardBairro == null -> null
            geoBairro != null -> AddressParser.bairroCompatible(cardBairro, geoBairro)
            geoLine != null -> if (AddressParser.normalizeLoose(geoLine).contains(cardBairro)) true else null
            else -> null
        }
        if (bairroStatus == false) return null

        val cardStreet = parts.street ?: return FixPrecision.AREA

        val geoStreet = c.thoroughfare?.let { AddressParser.streetKeyOf(it) } ?: return null
        if (!AddressParser.looseMatch(geoStreet, cardStreet)) return null

        val number = parts.number
        val numberOk = number != null && numberMatches(number, c.subThoroughfare ?: c.featureName)
        return if (numberOk) FixPrecision.NUMBER else FixPrecision.STREET
    }

    /** Resultado do Geocoder que descreve o BAIRRO do card (para saber se o bairro fica longe da area). */
    fun isBairroResult(c: GeoCandidate, parts: AddressParts): Boolean {
        val cardBairro = parts.bairro ?: return false
        c.adminArea?.let { st ->
            val n = AddressParser.normalizeLoose(st)
            if (n.isNotEmpty() && n != "rj" && !n.contains("rio de janeiro")) return false
        }
        val label = c.subLocality ?: c.featureName ?: return false
        return AddressParser.bairroCompatible(cardBairro, label) == true
    }

    /** Mesma rua do card, mas o Geocoder diz que ela fica em OUTRO bairro (indicio de homonimo). */
    fun isSameStreetInOtherBairro(c: GeoCandidate, parts: AddressParts): Boolean {
        val cardStreet = parts.street ?: return false
        val cardBairro = parts.bairro ?: return false
        val geoBairro = c.subLocality ?: return false
        c.adminArea?.let { st ->
            val n = AddressParser.normalizeLoose(st)
            if (n.isNotEmpty() && n != "rj" && !n.contains("rio de janeiro")) return false
        }
        val geoStreet = c.thoroughfare?.let { AddressParser.streetKeyOf(it) } ?: return false
        if (!AddressParser.strictMatch(geoStreet, cardStreet)) return false
        return AddressParser.bairroCompatible(cardBairro, geoBairro) == false
    }

    /**
     * Sem bairro no card, a coordenada do numero so vale se todos os candidatos concordarem (<= 300 m):
     * evita homonimo dentro da caixa de busca.
     */
    fun applyConsistency(fixes: List<GeoFix>, parts: AddressParts): List<GeoFix> {
        if (parts.bairro != null) return fixes
        val numberFixes = fixes.filter { it.precision == FixPrecision.NUMBER }
        if (numberFixes.size < 2) return fixes
        var spread = 0.0
        for (i in numberFixes.indices) {
            for (j in i + 1 until numberFixes.size) {
                val d = AreaEvaluator.haversineKm(numberFixes[i].lat, numberFixes[i].lng, numberFixes[j].lat, numberFixes[j].lng)
                if (d > spread) spread = d
            }
        }
        if (spread <= 0.3) return fixes
        return fixes.map { if (it.precision == FixPrecision.NUMBER) it.copy(precision = FixPrecision.STREET) else it }
    }

    internal fun numberMatches(cardNumber: String, geoText: String?): Boolean {
        if (geoText.isNullOrBlank()) return false
        val target = cardNumber.trimStart('0').ifEmpty { "0" }
        val nums = Regex("""\d+""").findAll(geoText).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()
        if (nums.any { it == target }) return true
        // Faixa "210-220" (mesma paridade = mesmo lado da rua)
        val range = Regex("""(\d+)\s*-\s*(\d+)""").find(geoText)
        if (range != null) {
            val a = range.groupValues[1].toIntOrNull()
            val b = range.groupValues[2].toIntOrNull()
            val n = target.toIntOrNull()
            if (a != null && b != null && n != null && n in minOf(a, b)..maxOf(a, b) && (n - a) % 2 == 0) return true
        }
        return false
    }
}
