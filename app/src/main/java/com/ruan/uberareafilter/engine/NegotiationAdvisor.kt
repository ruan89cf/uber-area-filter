package com.ruan.uberareafilter.engine

import com.ruan.uberareafilter.model.FilterConfig
import java.util.Locale
import kotlin.math.ceil

/**
 * Corrida "Negocia" da 99: o passageiro propoe o valor e o motorista aceita ou pede mais
 * (3 botoes sugeridos pela 99 ou o lapis para digitar). O app nunca aceita sozinho; aqui ele calcula
 * o MENOR valor que passa nas regras de preco do motorista (R$/km e R$/h, bruto ou liquido, iguais as do
 * AreaEvaluator) e qual botao sugerido alcanca esse valor.
 */
object NegotiationAdvisor {

    /** Acima de +50% do valor do passageiro nao compensa negociar: segue a recusa normal. */
    const val MAX_RAISE = 1.5

    data class Advice(
        val passengerPrice: Double,
        /** Menor valor que passa nas regras de preco (igual ao do passageiro quando ja passa). */
        val minPrice: Double,
        val passesAtPassengerPrice: Boolean,
        /** Menor botao sugerido pela 99 que alcanca o minimo; null = nenhum alcanca (use o lapis). */
        val button: Double?,
        /** Valor para digitar no lapis: o minimo arredondado para cima em R$ 0,10. */
        val typedValue: Double
    ) {
        val raisePercent: Int get() = ceil((minPrice / passengerPrice - 1.0) * 100.0 - 1e-9).toInt().coerceAtLeast(0)
        val withinReach: Boolean get() = minPrice <= passengerPrice * MAX_RAISE + 0.001
    }

    /** A mesma oferta com outro valor: o tempo e os km nao mudam, so R$/km e R$/h. */
    fun withPrice(offer: ParsedRideOffer, price: Double): ParsedRideOffer {
        val base = offer.ridePrice
        val km = offer.totalDistanceKm
        return offer.copy(
            ridePrice = price,
            pricePerKm = if (km != null && km > 0.1) price / km else offer.pricePerKm,
            hourlyRate = if (base != null && base > 0.0) offer.hourlyRate?.let { it * price / base } else offer.hourlyRate
        )
    }

    /** As duas regras de preco do AreaEvaluator (mesmas contas e mesmas tolerancias). */
    fun passesPriceRules(offer: ParsedRideOffer, config: FilterConfig): Boolean {
        val isUber = offer.appSource.equals("UBER", ignoreCase = true)
        val checkHourly = config.filterByHourlyRateEnabled || (isUber && config.uberCustomHourlyEnabled)
        if (checkHourly) {
            val effective = FuelCalculator.estimateForFilter(offer, config).effectiveHourlyRate
            if (effective != null && effective < config.effectiveMinHourlyRate(offer.appSource)) return false
        }
        if (config.filterByPricePerKmEnabled && offer.pricePerKm != null &&
            offer.pricePerKm + 0.0001 < config.minPricePerKm
        ) {
            return false
        }
        return true
    }

    /** null = sem valor lido ou nem 4x o valor passaria (ex.: falta o tempo da corrida). */
    fun advise(offer: ParsedRideOffer, config: FilterConfig): Advice? {
        val price = offer.ridePrice?.takeIf { it > 0.0 } ?: return null
        if (passesPriceRules(offer, config)) {
            return Advice(price, price, passesAtPassengerPrice = true, button = null, typedValue = price)
        }
        // As regras so melhoram com o valor maior: busca binaria em centavos entre o valor e 4x
        var lo = Math.round(price * 100.0)
        var hi = Math.round(price * 400.0)
        if (!passesPriceRules(withPrice(offer, hi / 100.0), config)) return null
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (passesPriceRules(withPrice(offer, mid / 100.0), config)) hi = mid else lo = mid
        }
        val min = hi / 100.0
        val button = offer.counterOfferPrices.sorted().firstOrNull { it + 0.0001 >= min }
        val typed = ceil(min * 10.0 - 1e-6) / 10.0
        return Advice(price, min, passesAtPassengerPrice = false, button = button, typedValue = typed)
    }

    private val br = Locale("pt", "BR")
    private fun money(v: Double) = String.format(br, "%.2f", v)

    /** Titulo do card (sem "R$": o texto do nosso card nunca pode ser lido como valor da 99). */
    fun title(a: Advice): String = when {
        a.passesAtPassengerPrice -> "Negocie: valor já passa"
        a.button != null -> "Negocie: peça ${money(a.button)}"
        else -> "Negocie: peça ${money(a.typedValue)}"
    }

    /** Linhas curtas para o checklist do card (cada uma comeca com "Negocie:" e cabe sem quebrar). */
    fun cardLines(a: Advice): List<String> = when {
        a.passesAtPassengerPrice -> listOf("💬 Negocie: valor do passageiro já passa")
        !a.withinReach -> listOf("💬 Negocie: precisaria +${a.raisePercent}% (${money(a.minPrice)})")
        a.button != null -> listOf(
            "💬 Negocie: suas regras pedem ${money(a.minPrice)} (+${a.raisePercent}%)",
            "💬 Negocie: toque no botão ${money(a.button)}"
        )
        else -> listOf(
            "💬 Negocie: suas regras pedem ${money(a.minPrice)} (+${a.raisePercent}%)",
            "💬 Negocie: no lápis, digite ${money(a.typedValue)}"
        )
    }

    /** Card enquanto o app pede sozinho (ou depois de pedir). */
    fun sendingLines(a: Advice, ask: Double, sent: Boolean): List<String> = listOf(
        "💬 Negocie: suas regras pedem ${money(a.minPrice)} (+${a.raisePercent}%)",
        if (sent) "💬 Negocie: pedido de ${money(ask)} enviado" else "💬 Negocie: o app está pedindo ${money(ask)}"
    )

    /** Frase para o log. */
    fun logText(a: Advice): String = when {
        a.passesAtPassengerPrice -> "o valor do passageiro (R$ ${money(a.passengerPrice)}) já passa nas regras de preço"
        a.button != null -> "regras de preço pedem R$ ${money(a.minPrice)} (+${a.raisePercent}%) -> botão R$ ${money(a.button)}"
        else -> "regras de preço pedem R$ ${money(a.minPrice)} (+${a.raisePercent}%) -> digitar R$ ${money(a.typedValue)} no lápis"
    }
}
