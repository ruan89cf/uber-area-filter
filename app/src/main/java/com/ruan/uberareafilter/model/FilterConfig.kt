package com.ruan.uberareafilter.model

import com.ruan.uberareafilter.engine.BlockedPlace

/** Botao flutuante: ON -> PAUSA -> OFF -> (religa) ON */
enum class RunState { ON, PAUSED, OFF }

enum class DualConflictPolicy {
    BLOCK_AUTO_ACCEPT_ONLY,
    AUTO_REJECT_OTHER_APP
}

/**
 * Configuracao completa do filtro de zonas demarcadas (multiplos poligonos/circulos,
 * zonas permitidas e proibidas), corrida entre zonas (card roxo), regra da Av. Brasil (impar/par),
 * KM total maximo e favoritos.
 */
data class FilterConfig(
    // isEnabled=false -> OFF (Acessibilidade desligada: apps de banco param de reclamar)
    val isEnabled: Boolean = true,
    // PAUSA: le a tela e mostra o card, mas nunca aceita nem recusa sozinho
    val actionsPaused: Boolean = false,
    val nineNineModuleEnabled: Boolean = true,
    val uberModuleEnabled: Boolean = true,
    val dualConflictGuardEnabled: Boolean = true,
    val dualConflictPolicy: DualConflictPolicy = DualConflictPolicy.BLOCK_AUTO_ACCEPT_ONLY,
    /**
     * Encaixe 99 <-> Uber: durante uma corrida num app, a oferta do OUTRO app e medida a partir do
     * DESTINO da corrida atual (onde voce vai ficar livre) e passa pelos mesmos filtros.
     */
    val chainAcrossAppsEnabled: Boolean = true,
    /**
     * Limite do encaixe, em minutos: [o que falta da corrida atual] + [ida ate o proximo passageiro].
     * Ex.: 3 -> faltando 2 min, o proximo embarque pode ficar a ate 1 min do destino atual.
     * So vale com o passageiro da corrida atual a bordo (corrida iniciada).
     */
    val chainMaxWaitMin: Int = 3,
    val rejectUberMultiStopRides: Boolean = false,
    val autoRejectEnabled: Boolean = true,
    val autoAcceptInsideArea: Boolean = true,
    val autoClickContinueEnabled: Boolean = true,
    val humanDelayEnabled: Boolean = true,
    val floatingButtonEnabled: Boolean = true,
    /** Minutos que o botao OFF fica na tela (contagem regressiva) depois de desligar. 0 = some na hora. */
    val offButtonMinutes: Int = 10,
    val batterySaverInRide: Boolean = false,
    val favoriteAlertsEnabled: Boolean = true,

    val requireBothPickupAndDropoff: Boolean = true,
    val allowInterZoneRides: Boolean = false,
    val autoAcceptInterZone: Boolean = false,
    val yellowCardOutsideAreaEnabled: Boolean = true,
    /** Embarque fora/incerto mas destino DENTRO da area (nao proibido): conta como bom (leva o motorista para a area). */
    val acceptRidesIntoArea: Boolean = true,
    val avBrasilOddSideOnly: Boolean = true,
    val reject99RiskAreaBadge: Boolean = true,
    // Local incerto (rua de divisa sem numero, so bairro, GPS sem resposta...): NUNCA aceita sozinho.
    // false = mostra card laranja e voce decide | true = recusa automaticamente ("na duvida, recusa")
    val rejectUncertainLocation: Boolean = false,

    val polygonPoints: List<Pair<Double, Double>> = emptyList(),
    val mapZones: List<MapZone> = emptyList(),
    val allowedKeywords: List<String> = emptyList(),
    val blockedKeywords: List<String> = emptyList(),
    // Endereco (rua + numero, e ponto no mapa quando achado) de nomes bloqueados: pega o mesmo lugar com outro nome
    val blockedPlaces: List<BlockedPlace> = emptyList(),
    // Ruas que VOCE marcou como confiaveis pelo historico ("Adicionar a Area Permitida"): aprovam so pelo nome
    val trustedStreets: List<String> = emptyList(),
    // Indice de ruas internas x de divisa por zona (gerado do mapa; salvo separado das configuracoes)
    val streetIndex: Map<Long, ZoneStreetIndex> = emptyMap(),

    // Filtros extras
    val filterByRatingEnabled: Boolean = false,
    val minRating: Double = 4.80,
    val rejectNewPassenger: Boolean = false,

    val filterByPickupDistEnabled: Boolean = true,
    val maxPickupDistanceKm: Double = 2.5,

    val filterByTotalDistEnabled: Boolean = false,
    val maxTotalDistanceKm: Double = 15.0,

    val filterByHourlyRateEnabled: Boolean = true,
    val minHourlyRate: Double = 30.0,
    val uberCustomHourlyEnabled: Boolean = false,
    val uberMinHourlyRate: Double = 30.0,
    // Negocia (99): R$/h minimo proprio. Ligado, vale NO LUGAR do R$/h padrao so nas corridas Negocia
    // (aceitar e calcular a contraproposta); as outras regras (R$/km, area, nota...) continuam as mesmas.
    val negociaHourlyEnabled: Boolean = false,
    val negociaMinHourlyRate: Double = 40.0,
    // Ultima checagem antes de aceitar: tempo com o transito de agora (Google Routes, chave do motorista).
    // Sem chave / sem resposta em 1,5 s = aceita pela estimativa da 99. 0 km = consulta todas.
    val trafficCheckEnabled: Boolean = true,
    val trafficApiKey: String = "",
    val trafficMinTotalKm: Double = 0.0,
    /** Veiculo usado na consulta de transito: "moto" ou "car" (o tempo muda bastante entre os dois). */
    val trafficVehicle: String = "moto",
    val useNetHourlyRate: Boolean = false,
    // R$ minimo por KM (ex.: 1,20): abaixo disso recusa. Desligado por padrao.
    val filterByPricePerKmEnabled: Boolean = false,
    val minPricePerKm: Double = 1.20,
    val roundDownNetHourlyRate: Boolean = false,

    // Personalizacao Visual (Tema, Tamanho, Tempo e Cores dos Cards)
    val darkThemeEnabled: Boolean = true,
    val bannerScale: Int = 1, // 0 = Compacto, 1 = Medio, 2 = Grande
    val showRideInfoBannerEnabled: Boolean = true,
    val bannerAutoHideSeconds: Int = 5,
    val colorInsideHex: String = "#0A2E1D",
    val colorOutsideHex: String = "#330A0A",
    val colorInterZoneHex: String = "#271252",
    val colorYellowReturnHex: String = "#3B2806",

    // Custo de Combustivel por corrida (exibido no card: "⛽ R$ 1,20 de gasolina" + Lucro liquido)
    // Cor propria por zona no card (ver MapZone.cardColorHex). Desligado por padrao.
    val zoneCardColorsEnabled: Boolean = false,

    val fuelCostEnabled: Boolean = true,
    val fuelType: String = FUEL_GASOLINA,
    val fuelPricePerUnit: Double = 6.29,
    val kmPerUnit: Double = 35.0,

    val rejectButtonHints: List<String> = DEFAULT_REJECT_HINTS,
    val debugMode: Boolean = true,
    val xrayDiagnosticEnabled: Boolean = true,
    val saveRideScreenshotsEnabled: Boolean = true,
    /** Opcao de desenvolvedor: quantas corridas (cada uma com a sua foto) o historico guarda (20 a 500). */
    val maxRideHistory: Int = 500
) {
    /** Valor hora minimo efetivo para o app da oferta (Uber pode usar valor separado da 99) */
    val runState: RunState
        get() = when {
            !isEnabled -> RunState.OFF
            actionsPaused -> RunState.PAUSED
            else -> RunState.ON
        }

    /** Resumo legivel das regras para os logs (sem listas grandes). Usado no inicio da sessao e no diff. */
    fun debugSummary(): Map<String, String> = linkedMapOf(
        "estado" to runState.name,
        "99" to nineNineModuleEnabled.toString(),
        "uber" to uberModuleEnabled.toString(),
        "autoRecusa" to autoRejectEnabled.toString(),
        "autoAceite" to autoAcceptInsideArea.toString(),
        "autoContinuar" to autoClickContinueEnabled.toString(),
        "atrasoHumano" to humanDelayEnabled.toString(),
        "embEDest" to requireBothPickupAndDropoff.toString(),
        "entreZonas" to allowInterZoneRides.toString(),
        "aceitaEntreZonas" to autoAcceptInterZone.toString(),
        "cardAmarelo" to yellowCardOutsideAreaEnabled.toString(),
        "levaParaArea" to acceptRidesIntoArea.toString(),
        "recusaIncerto" to rejectUncertainLocation.toString(),
        "areaRisco99" to reject99RiskAreaBadge.toString(),
        "paradasUber" to rejectUberMultiStopRides.toString(),
        "conflitoDuplo" to "$dualConflictGuardEnabled/${dualConflictPolicy.name}",
        "encaixe" to if (chainAcrossAppsEnabled) "falta+ida ate ${chainMaxWaitMin}min, so c/ passageiro a bordo" else "off",
        "nota" to if (filterByRatingEnabled) "min $minRating recusaNovo=$rejectNewPassenger" else "off",
        "embKm" to if (filterByPickupDistEnabled) "max $maxPickupDistanceKm" else "off",
        "totalKm" to if (filterByTotalDistEnabled) "max $maxTotalDistanceKm" else "off",
        "rsHora" to if (filterByHourlyRateEnabled) "min $minHourlyRate liq=$useNetHourlyRate" else "off",
        "rsHoraUber" to if (uberCustomHourlyEnabled) "min $uberMinHourlyRate" else "off",
        "rsHoraNegocia" to if (negociaHourlyEnabled) "min $negociaMinHourlyRate" else "off",
        "transito" to if (trafficCheckEnabled) "a partir de ${trafficMinTotalKm}km ${if (trafficVehicle == "car") "carro" else "moto"} via ${if (trafficApiKey.isBlank()) "servidor" else "chave própria"}" else "off",
        "rsKm" to if (filterByPricePerKmEnabled) "min $minPricePerKm" else "off",
        "combustivel" to if (fuelCostEnabled) "$fuelType R$$fuelPricePerUnit ${kmPerUnit}km" else "off",
        "zonas" to "${mapZones.count { it.mode == ZoneMode.ALLOWED }} perm/${mapZones.count { it.mode == ZoneMode.BLOCKED }} proib",
        "corPorZona" to zoneCardColorsEnabled.toString(),
        "ruasPermitidas" to allowedKeywords.size.toString(),
        "bloqueadas" to blockedKeywords.size.toString(),
        "locaisBloqueados" to blockedPlaces.size.toString(),
        "ruasConfiaveis" to trustedStreets.size.toString(),
        "card" to "tam=$bannerScale ${bannerAutoHideSeconds}s mostra=$showRideInfoBannerEnabled",
        "botaoFlutuante" to floatingButtonEnabled.toString(),
        "botaoOffMin" to offButtonMinutes.toString(),
        "raioX" to "$debugMode/$xrayDiagnosticEnabled",
        "prints" to "$saveRideScreenshotsEnabled/ate $maxRideHistory"
    )

    /** Regras para uma corrida Negocia: igual, so troca o R$/h minimo pelo da Negocia (se ligado). */
    fun forNegotiation(): FilterConfig =
        if (negociaHourlyEnabled) copy(minHourlyRate = negociaMinHourlyRate, filterByHourlyRateEnabled = true) else this

    fun effectiveMinHourlyRate(appSource: String): Double =
        if (appSource.equals("UBER", ignoreCase = true) && uberCustomHourlyEnabled) {
            uberMinHourlyRate
        } else {
            minHourlyRate
        }

    companion object {
        const val FUEL_GASOLINA = "Gasolina"
        const val FUEL_ETANOL = "Etanol"
        const val FUEL_DIESEL = "Diesel"
        const val FUEL_GNV = "GNV"
        const val FUEL_ELETRICO = "Elétrico"
        val FUEL_TYPES = listOf(FUEL_GASOLINA, FUEL_ETANOL, FUEL_DIESEL, FUEL_GNV, FUEL_ELETRICO)

        /** Unidade de abastecimento (litro, m³ ou kWh) */
        fun fuelUnit(type: String): String = when (type) {
            FUEL_GNV -> "m³"
            FUEL_ELETRICO -> "kWh"
            else -> "L"
        }

        /** Texto curto usado no card: "de gasolina", "de etanol", "de GNV", "de energia" */
        fun fuelNoun(type: String): String = when (type) {
            FUEL_ETANOL -> "etanol"
            FUEL_DIESEL -> "diesel"
            FUEL_GNV -> "GNV"
            FUEL_ELETRICO -> "energia"
            else -> "gasolina"
        }
        val DEFAULT_REJECT_HINTS = listOf(
            "recusar",
            "fechar",
            "pular",
            "ignorar",
            "nao afeta a ta",
            "nao afeta ta",
            "decline",
            "close",
            "dismiss",
            "rejeitar",
            "skip"
        )
    }
}
