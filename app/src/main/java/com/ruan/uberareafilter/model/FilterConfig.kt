package com.ruan.uberareafilter.model

/**
 * Configuracao completa do filtro de zonas demarcadas (multiplos poligonos/circulos,
 * zonas permitidas e proibidas), corrida entre zonas (card roxo), regra da Av. Brasil (impar/par),
 * KM total maximo e favoritos.
 */
data class FilterConfig(
    val isEnabled: Boolean = true,
    val nineNineModuleEnabled: Boolean = true,
    val uberModuleEnabled: Boolean = true,
    val rejectUberMultiStopRides: Boolean = false,
    val autoRejectEnabled: Boolean = true,
    val autoAcceptInsideArea: Boolean = true,
    val autoClickContinueEnabled: Boolean = true,
    val humanDelayEnabled: Boolean = true,
    val floatingButtonEnabled: Boolean = true,
    val batterySaverInRide: Boolean = false,
    val favoriteAlertsEnabled: Boolean = true,

    val requireBothPickupAndDropoff: Boolean = true,
    val allowInterZoneRides: Boolean = false,
    val yellowCardOutsideAreaEnabled: Boolean = true,
    val avBrasilOddSideOnly: Boolean = true,
    val reject99RiskAreaBadge: Boolean = true,

    val polygonPoints: List<Pair<Double, Double>> = emptyList(),
    val mapZones: List<MapZone> = emptyList(),
    val allowedKeywords: List<String> = emptyList(),
    val blockedKeywords: List<String> = emptyList(),

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

    // Personalizacao Visual (Tema, Tamanho e Cores dos Cards)
    val darkThemeEnabled: Boolean = true,
    val bannerScale: Int = 1, // 0 = Compacto, 1 = Medio, 2 = Grande
    val colorInsideHex: String = "#0A2E1D",
    val colorOutsideHex: String = "#330A0A",
    val colorInterZoneHex: String = "#271252",
    val colorYellowReturnHex: String = "#3B2806",

    // Custo de Combustivel por corrida (exibido no card: "⛽ R$ 1,20 de gasolina" + Lucro liquido)
    val fuelCostEnabled: Boolean = true,
    val fuelType: String = FUEL_GASOLINA,
    val fuelPricePerUnit: Double = 6.29,
    val kmPerUnit: Double = 35.0,

    val rejectButtonHints: List<String> = DEFAULT_REJECT_HINTS,
    val debugMode: Boolean = true
) {
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
