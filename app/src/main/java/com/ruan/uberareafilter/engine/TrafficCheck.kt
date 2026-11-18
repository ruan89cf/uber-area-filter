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
        if (!config.trafficCheckEnabled || config.trafficApiKey.isBlank()) return false
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

    data class Verdict(
        val trafficMinutes: Double,
        val estimatedMinutes: Double?,
        /** R$/h bruto recalculado com o tempo do transito (null = nao deu para recalcular) */
        val hourlyNow: Double?,
        val passes: Boolean,
        val offerNow: ParsedRideOffer
    ) {
        private val br = Locale("pt", "BR")
        fun summary(): String = buildString {
            append(String.format(br, "Trânsito agora: %.0f min", trafficMinutes))
            estimatedMinutes?.let { append(String.format(br, " (a 99 dizia %.0f)", it)) }
            hourlyNow?.let { append(String.format(br, " → R$ %.0f/h", it)) }
        }
    }

    /**
     * Refaz o R$/h com o tempo do transito. Nunca melhora a corrida alem da estimativa da 99
     * (usa o MAIOR dos dois tempos): a consulta so serve para pegar o transito que a 99 nao viu.
     */
    fun judge(offer: ParsedRideOffer, trafficMinutes: Double, config: FilterConfig): Verdict {
        val est = estimatedMinutes(offer)
        val price = offer.ridePrice
        val minutes = max(trafficMinutes, est ?: 0.0)
        val hourlyNow = if (price != null && price > 0.0 && minutes > 0.0) price / (minutes + WAIT_MIN) * 60.0 else null
        val offerNow = if (hourlyNow != null) offer.copy(hourlyRate = hourlyNow) else offer
        val passes = NegotiationAdvisor.passesPriceRules(offerNow, config)
        return Verdict(trafficMinutes, est, hourlyNow, passes, offerNow)
    }

    /** "1234s" (Google Routes) -> minutos. */
    fun parseDurationMinutes(json: String): Double? {
        val m = Regex(""""duration"\s*:\s*"(\d+(?:\.\d+)?)s"""").find(json) ?: return null
        return m.groupValues[1].toDoubleOrNull()?.div(60.0)
    }
}
