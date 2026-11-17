package com.ruan.uberareafilter.engine

import com.ruan.uberareafilter.model.FilterConfig
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/**
 * Calcula o custo de combustivel da corrida (KM ate o embarque + KM da viagem)
 * e o lucro liquido (valor da corrida - combustivel), incluindo R$/h liquido
 * com arredondamento opcional para baixo.
 */
object FuelCalculator {

    data class FuelEstimate(
        val distanceKm: Double,
        val unitsUsed: Double,
        val cost: Double,
        val netProfit: Double?,
        val netHourly: Double?,
        val fuelNoun: String,
        val unit: String
    ) {
        private val br = Locale("pt", "BR")

        /** "R$ 1,20 de gasolina" */
        val costText: String get() = String.format(br, "R$ %.2f de %s", cost, fuelNoun)

        /** "Lucro R$ 17,70" */
        val profitText: String? get() = netProfit?.let { String.format(br, "Lucro R$ %.2f", it) }

        /** "R$ 39/h líq." */
        val netHourlyText: String? get() = netHourly?.let { String.format(br, "R$ %.0f/h líq.", it) }

        /** "0,29 L" */
        val unitsText: String get() = String.format(br, "%.2f %s", unitsUsed, unit)
    }

    data class FilterHourlyResult(
        val effectiveHourlyRate: Double?,
        val isNet: Boolean
    )

    fun estimate(offer: ParsedRideOffer, config: FilterConfig): FuelEstimate? {
        val km = offer.totalDistanceKm ?: return null
        return estimate(km, offer.ridePrice, offer.hourlyRate, config)
    }

    fun estimate(
        distanceKm: Double,
        ridePrice: Double?,
        hourlyRate: Double?,
        config: FilterConfig,
        requireFuelDisplayEnabled: Boolean = true
    ): FuelEstimate? {
        if (requireFuelDisplayEnabled && !config.fuelCostEnabled) return null
        if (distanceKm <= 0.0 || config.kmPerUnit <= 0.0 || config.fuelPricePerUnit <= 0.0) return null

        val units = distanceKm / config.kmPerUnit
        val cost = units * config.fuelPricePerUnit
        val net = ridePrice?.let { it - cost }
        val rawNetHourly = if (ridePrice != null && ridePrice > 0.0 && hourlyRate != null && net != null) {
            max(0.0, hourlyRate * (net / ridePrice))
        } else {
            null
        }
        val netHourly = rawNetHourly?.let {
            if (config.roundDownNetHourlyRate) floor(it) else it
        }

        return FuelEstimate(
            distanceKm = distanceKm,
            unitsUsed = units,
            cost = cost,
            netProfit = net,
            netHourly = netHourly,
            fuelNoun = FilterConfig.fuelNoun(config.fuelType),
            unit = FilterConfig.fuelUnit(config.fuelType)
        )
    }

    /**
     * Retorna o R$/h usado pelo avaliador de filtro: bruto ou liquido (descontando combustivel da corrida).
     */
    fun estimateForFilter(offer: ParsedRideOffer, config: FilterConfig): FilterHourlyResult {
        if (!config.useNetHourlyRate) {
            return FilterHourlyResult(effectiveHourlyRate = offer.hourlyRate, isNet = false)
        }
        val km = offer.totalDistanceKm
        if (km != null && km > 0.0 && offer.ridePrice != null && offer.ridePrice > 0.0 && offer.hourlyRate != null) {
            val est = estimate(
                distanceKm = km,
                ridePrice = offer.ridePrice,
                hourlyRate = offer.hourlyRate,
                config = config,
                requireFuelDisplayEnabled = false
            )
            if (est?.netHourly != null) {
                return FilterHourlyResult(effectiveHourlyRate = est.netHourly, isNet = true)
            }
        }
        return FilterHourlyResult(effectiveHourlyRate = offer.hourlyRate, isNet = false)
    }
}
