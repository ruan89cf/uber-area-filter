package com.ruan.uberareafilter.model

enum class ZoneMode {
    ALLOWED, // 🟢 Area Permitida
    BLOCKED  // 🔴 Area Proibida (Nao ir de forma alguma)
}

enum class ZoneShape {
    POLYGON, // Poligono de N pontos
    CIRCLE   // Circulo expansivel (centro + raio em metros)
}

data class MapZone(
    val id: Long,
    val name: String,
    val mode: ZoneMode,
    val shape: ZoneShape,
    val points: List<Pair<Double, Double>> = emptyList(),
    val centerLat: Double = 0.0,
    val centerLng: Double = 0.0,
    val radiusMeters: Double = 1000.0,
    val streetNames: List<String> = emptyList()
)

data class RideHistoryItem(
    val id: Long,
    val timeFormatted: String,
    val pickupAddress: String,
    val dropoffAddress: String,
    val priceText: String,
    val pricePerKmText: String,
    val hourlyRateText: String,
    val ratingText: String,
    val isInsideArea: Boolean,
    val reason: String,
    val screenshotPath: String = ""
)

data class FavoriteRideItem(
    val id: Long,
    val passengerName: String = "",
    val rideTimeFormatted: String,
    val dateFormatted: String,
    val pickupAddress: String,
    val dropoffAddress: String,
    val pickupLat: Double,
    val pickupLng: Double,
    val dropoffLat: Double,
    val dropoffLng: Double,
    val priceText: String,
    val ratingText: String
)
