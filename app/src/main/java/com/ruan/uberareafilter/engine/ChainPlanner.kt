package com.ruan.uberareafilter.engine

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Encaixe de corrida entre 99 e Uber (usar os dois apps ao mesmo tempo para ganhar mais).
 *
 * Durante uma corrida no app A, a oferta do app B mostra a busca a partir de ONDE O CARRO ESTA AGORA.
 * So que voce so fica livre no DESTINO da corrida atual. Aqui a oferta do app B e refeita como se
 * voce partisse desse destino:
 *   - busca = distancia (destino atual -> embarque novo) x fator de rua;
 *   - R$/h e R$/km recalculados com essa busca;
 *   - a oferta ajustada passa pelos MESMOS filtros (area, busca maxima, R$/h...).
 *
 * Regra de tempo (definida pelo motorista em 18/11): o proximo passageiro espera
 *   [o que falta da corrida atual] + [ida do destino atual ate ele]
 * e isso tem que caber no limite (padrao 3 min). Faltando 2 min, a ida pode ser de ate 1 min;
 * quanto mais perto do destino, mais longe pode ser o proximo embarque.
 * So vale com o passageiro da corrida atual A BORDO (o servico confere a etapa antes de chamar).
 */
class ChainPlanner(
    private val pointOf: suspend (String) -> Pair<Double, Double>?
) {

    data class Plan(
        /** Busca de verdade: do destino da corrida atual ate o embarque da nova (km de rua estimados). */
        val chainKm: Double,
        /** Quantos minutos faltam para a corrida atual terminar (null = nao deu para ler). */
        val remainingMin: Double?,
        /** Minutos para ir do destino atual ate o embarque da nova. */
        val pickupMin: Double,
        /** Limite: falta + ida ate o proximo passageiro. */
        val maxTotalMin: Int,
        /** Oferta refeita com a busca a partir do destino atual. */
        val adjusted: ParsedRideOffer,
        /** App da corrida em andamento ("99"/"UBER"). */
        val activeApp: String
    ) {
        /** Quanto o proximo passageiro espera: o que falta da corrida atual + ir ate ele. */
        val totalMin: Double? get() = remainingMin?.let { it + pickupMin }

        /** Cabe no limite de tempo. */
        val waitOk: Boolean get() = totalMin?.let { it <= maxTotalMin + 0.05 } ?: false

        val appLabel: String get() = if (activeApp.equals("UBER", true)) "Uber" else activeApp

        /** "falta 2 min + 0,8 min até o embarque = 2,8 min (máx 3)" */
        fun timeText(): String {
            val r = remainingMin ?: return "não li quanto falta da $appLabel"
            return "falta ${fmtMin(r)} + ${fmtMin(pickupMin)} até o embarque = ${fmtMin(r + pickupMin)} (máx $maxTotalMin min)"
        }

        fun summary(): String {
            val km = String.format(Locale("pt", "BR"), "%.1f", chainKm)
            return "⛓ Depois da $appLabel: busca a $km km do destino dela · ${timeText()}"
        }
    }

    suspend fun plan(
        activeApp: String,
        activeDropoff: String,
        offer: ParsedRideOffer,
        remainingMin: Double?,
        maxTotalMin: Int
    ): Plan? {
        if (activeDropoff.isBlank()) return null
        val pickup = offer.pickupAddress?.takeIf { it.isNotBlank() } ?: return null
        val from = pointOf(activeDropoff) ?: return null
        val to = pointOf(pickup) ?: return null
        val straightKm = AreaEvaluator.haversineKm(from.first, from.second, to.first, to.second)
        val chainKm = roundTenth(straightKm * ROAD_FACTOR)
        return Plan(
            chainKm = chainKm,
            remainingMin = remainingMin,
            pickupMin = chainKm * MIN_PER_KM,
            maxTotalMin = maxTotalMin,
            adjusted = adjustOffer(offer, chainKm),
            activeApp = activeApp
        )
    }

    companion object {
        /** Rua nao e linha reta: 1 km no mapa costuma dar ~1,35 km de carro na cidade. */
        const val ROAD_FACTOR = 1.35
        /** ~25 km/h na cidade. */
        const val MIN_PER_KM = 2.4
        private const val WAIT_MIN = 2.0

        internal fun roundTenth(v: Double): Double = (v * 10.0).roundToInt() / 10.0

        /** "3 min" quando inteiro, "0,8 min" quando quebrado. */
        internal fun fmtMin(v: Double): String {
            val r = roundTenth(v)
            return if (r == r.roundToInt().toDouble()) "${r.roundToInt()} min"
            else String.format(Locale("pt", "BR"), "%.1f min", r)
        }

        /** A mesma oferta, mas com a busca partindo do destino da corrida atual. */
        fun adjustOffer(offer: ParsedRideOffer, chainKm: Double): ParsedRideOffer {
            val origPickupKm = offer.pickupDistanceKm ?: 0.0
            val tripKm = offer.dropoffDistanceKm
                ?: ((offer.totalDistanceKm ?: 0.0) - origPickupKm).coerceAtLeast(0.0)
            val total = roundTenth(chainKm + tripKm)
            val price = offer.ridePrice ?: offer.estimatedPrice
            // Minutos da viagem em si (sem a busca original): sai do R$/h do card quando da
            val tripMin = run {
                val hourly = offer.hourlyRate
                if (price != null && hourly != null && hourly > 0.0) {
                    val totalMinOrig = price / hourly * 60.0
                    (totalMinOrig - (origPickupKm * MIN_PER_KM + WAIT_MIN)).coerceAtLeast(tripKm * 1.6)
                } else {
                    tripKm * MIN_PER_KM
                }
            }
            val newMinutes = chainKm * MIN_PER_KM + WAIT_MIN + tripMin
            val hourly = if (price != null && newMinutes > 0.5) price / (newMinutes / 60.0) else offer.hourlyRate
            val perKm = if (offer.ridePrice != null && total > 0.1) offer.ridePrice / total else null
            return offer.copy(
                pickupDistanceKm = chainKm,
                totalDistanceKm = total,
                hourlyRate = hourly,
                pricePerKm = perKm ?: offer.pricePerKm
            )
        }
    }
}
