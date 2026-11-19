package com.ruan.uberareafilter.engine

import com.ruan.uberareafilter.model.FilterConfig
import java.util.Locale
import kotlin.math.max

/**
 * Ultima checagem antes de aceitar: o tempo da corrida COM O TRANSITO DE AGORA (Google Routes).
 * A 99 estima os minutos na hora do card; com a Av. Brasil travada a corrida demora muito mais e o
 * R$/h real cai. Aqui so ficam as contas (testaveis); a chamada de rede fica em TrafficRouteClient.
 */
object TrafficCheck {

    /** Minutos de espera no embarque que o app ja soma no R$/h (igual ao RideOfferParser). */
    private const val WAIT_MIN = 2.0

    /** Ruas onde vale consultar sempre, mesmo abaixo do km minimo. */
    private val alwaysCheckStreets = listOf(Regex("""\bav(enida|\.)?\s*brasil\b"""))

    /** Deve consultar? So corrida que ja passou em tudo; abaixo do km minimo so se tiver Av. Brasil. */
    fun shouldCheck(offer: ParsedRideOffer, config: FilterConfig): Boolean {
        if (!config.trafficCheckEnabled) return false
        val total = offer.totalDistanceKm ?: return true
        if (total + 1e-9 >= config.trafficMinTotalKm) return true
        val text = TextNormalizer.normalize("${offer.pickupAddress.orEmpty()} ${offer.dropoffAddress.orEmpty()}")
        return alwaysCheckStreets.any { it.containsMatchIn(text) }
    }

    /** Minutos que o app usou para o R$/h do card (99: busca + viagem). */
    fun estimatedMinutes(offer: ParsedRideOffer): Double? {
        val price = offer.ridePrice ?: return null
        val hourly = offer.hourlyRate?.takeIf { it > 0.0 } ?: return null
        return price / hourly * 60.0 - WAIT_MIN
    }

    /** Resposta da consulta: minutos com transito e, quando vem, os km reais de cada trecho. */
    data class Route(val minutes: Double, val pickupKm: Double? = null, val rideKm: Double? = null)

    /** Busca real maior que isso vezes a da 99 (e +0,8 km) = a 99 contou um atalho que o carro/moto nao faz (ex.: passarela). */
    private const val DETOUR_RATIO = 1.8
    private const val DETOUR_MIN_EXTRA_KM = 1.0
    /** Folga sobre o "embarque maximo" na rota real (GPS e ponto do endereco variam uns 200-400 m). */
    private const val PICKUP_REAL_TOLERANCE_KM = 0.5

    data class Verdict(
        val trafficMinutes: Double,
        val estimatedMinutes: Double?,
        /** R$/h bruto recalculado com o tempo do transito (null = nao deu para recalcular) */
        val hourlyNow: Double?,
        val passes: Boolean,
        val offerNow: ParsedRideOffer,
        val realPickupKm: Double? = null,
        val realRideKm: Double? = null,
        /** Motivos de nao passar ("busca real 3,1 km (a 99 dizia 1,1)"...). Vazio = passou. */
        val problems: List<String> = emptyList()
    ) {
        private val br = Locale("pt", "BR")
        private fun kmText(): String? = if (realPickupKm != null && realRideKm != null)
            String.format(br, "%.1f km reais", realPickupKm + realRideKm) else null

        /** "Trânsito ok: 12 min (a 99 dizia 11) · 4,2 km reais" */
        fun okSummary(): String = buildString {
            append(String.format(br, "Trânsito ok: %.0f min", trafficMinutes))
            estimatedMinutes?.let { append(String.format(br, " (a 99 dizia %.0f)", it)) }
            kmText()?.let { append(" · $it") }
        }

        fun summary(): String = buildString {
            if (problems.isNotEmpty()) {
                append(problems.joinToString(" • "))
            } else {
                append(String.format(br, "Trânsito agora: %.0f min", trafficMinutes))
                estimatedMinutes?.let { append(String.format(br, " (a 99 dizia %.0f)", it)) }
                kmText()?.let { append(" · $it") }
            }
            hourlyNow?.let { append(String.format(br, " → R$ %.0f/h", it)) }
            offerNow.pricePerKm?.takeIf { realPickupKm != null }?.let { append(String.format(br, " · R$ %.2f/km", it)) }
        }
    }

    /**
     * Refaz a corrida com a rota real: R$/h com o tempo do transito e R$/km com os km reais.
     * Nunca melhora a corrida alem do que a 99 disse (usa o MAIOR tempo e o MAIOR km): a consulta
     * so serve para pegar o que a 99 nao viu (transito, passarela, volta para atravessar a linha do trem).
     */
    fun judge(offer: ParsedRideOffer, trafficMinutes: Double, config: FilterConfig, pickupKm: Double? = null, rideKm: Double? = null): Verdict {
        val br = Locale("pt", "BR")
        val est = estimatedMinutes(offer)
        val price = offer.ridePrice
        val minutes = max(trafficMinutes, est ?: 0.0)
        val hourlyNow = if (price != null && price > 0.0 && minutes > 0.0) price / (minutes + WAIT_MIN) * 60.0 else null
        var offerNow = if (hourlyNow != null) offer.copy(hourlyRate = hourlyNow) else offer
        val problems = mutableListOf<String>()
        if (pickupKm != null && rideKm != null) {
            val pick = max(pickupKm, offer.pickupDistanceKm ?: 0.0)
            val total = max(pickupKm + rideKm, offer.totalDistanceKm ?: 0.0)
            val perKm = if (price != null && price > 0.0 && total > 0.0) price / total else offer.pricePerKm
            offerNow = offerNow.copy(pickupDistanceKm = pick, totalDistanceKm = total, pricePerKm = perKm)
            val said = offer.pickupDistanceKm
            if (said != null && pickupKm > said * DETOUR_RATIO && pickupKm - said > DETOUR_MIN_EXTRA_KM) {
                problems += String.format(br, "Busca real %.1f km (a 99 dizia %.1f): passarela ou volta", pickupKm, said)
            } else if (config.filterByPickupDistEnabled && pickupKm > config.maxPickupDistanceKm + PICKUP_REAL_TOLERANCE_KM) {
                problems += String.format(br, "Busca real %.1f km (máx %.1f)", pickupKm, config.maxPickupDistanceKm)
            }
        }
        if (!NegotiationAdvisor.passesPriceRules(offerNow, config)) {
            problems += buildString {
                append(String.format(br, "Trânsito agora: %.0f min", trafficMinutes))
                est?.let { append(String.format(br, " (a 99 dizia %.0f)", it)) }
                if (pickupKm != null && rideKm != null) append(String.format(br, " · %.1f km reais", pickupKm + rideKm))
            }
        }
        return Verdict(trafficMinutes, est, hourlyNow, problems.isEmpty(), offerNow, pickupKm, rideKm, problems)
    }

    /** km de cada trecho na resposta da AWS (com LegAdditionalFeatures=Summary): [ate o embarque, viagem]. */
    fun parseAwsLegKm(json: String): List<Double> =
        Regex(""""Overview"\s*:\s*\{[^{}]*?"Distance"\s*:\s*(\d+(?:\.\d+)?)""").findAll(json)
            .mapNotNull { it.groupValues[1].toDoubleOrNull()?.div(1000.0) }.toList()

    /** AWS (Amazon Location Routes v2): o resumo da ROTA e o ultimo "Summary" da resposta. */
    fun parseAwsDurationMinutes(json: String): Double? {
        val m = Regex(""""Summary"\s*:\s*\{[^{}]*?"Duration"\s*:\s*(\d+(?:\.\d+)?)""").findAll(json).lastOrNull() ?: return null
        return m.groupValues[1].toDoubleOrNull()?.div(60.0)
    }

    /** Chave da AWS (Amazon Location) comeca com "v1.public."; o resto e tratado como Google. */
    fun isAwsKey(key: String): Boolean = key.trim().startsWith("v1.public.")

    /** "1234s" (Google Routes) -> minutos. */
    fun parseDurationMinutes(json: String): Double? {
        val m = Regex(""""duration"\s*:\s*"(\d+(?:\.\d+)?)s"""").find(json) ?: return null
        return m.groupValues[1].toDoubleOrNull()?.div(60.0)
    }
}
