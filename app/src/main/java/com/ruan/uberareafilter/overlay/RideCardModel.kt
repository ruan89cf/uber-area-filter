package com.ruan.uberareafilter.overlay

import com.ruan.uberareafilter.engine.Decision

/**
 * Modelo estruturado do card de notificacao profissional:
 *
 *  ▌ [UBER]  Dentro da área                ● ACEITANDO
 *  ▌ R$ 18,90                               R$ 42/h
 *  ▌ R$ 1,45/km  ·  6,2 km  ·  ★ 4,95
 *  ▌ ⛽ R$ 1,20 de gasolina      💰 Lucro R$ 17,70
 *  ▌ ───────────────────────────────────────────
 *  ▌ ✔ Nota 4,95   ✔ R$42/h   ✘ Emb 3,1km
 *  ▌ ✔ Embarque: Rua X
 *  ▌ ✘ Destino: Rua Y
 *  ▔▔▔▔▔▔▔▔▔▔▔▔▔ (barra de tempo restante)
 */
data class RideCardModel(
    val decision: Decision,
    val appSource: String? = null,
    val title: String,
    val statusLabel: String = "",
    val priceText: String? = null,
    val hourlyText: String? = null,
    val secondaryMetrics: List<String> = emptyList(),
    val fuelText: String? = null,
    val profitText: String? = null,
    val profitPositive: Boolean = true,
    val checklist: String = "",
    // Cor da zona de destino (pintura por zona); null = cor normal da decisao
    val zoneColorHex: String? = null,
    val autoHideMs: Long = 5000L,
    // Botao discreto grudado embaixo do card (ex.: "Aceitar se tocar de novo"); null = sem botao
    val quickActionLabel: String? = null
)
