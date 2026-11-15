package com.ruan.uberareafilter.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ruan.uberareafilter.model.FavoriteRideItem
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.model.RideHistoryItem
import com.ruan.uberareafilter.model.ZoneMode
import com.ruan.uberareafilter.model.ZoneShape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persiste configuracoes, zonas do mapa (poligonos e circulos permitidos/proibidos),
 * historico das ultimas corridas, lista de passageiros/horarios favoritos e logs persistentes em arquivo.
 */
class ConfigRepository(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        ensureDiskLogsLoaded(appContext)
    }

    fun load(): FilterConfig {
        val allowed = prefs.getString(KEY_ALLOWED, "")
            .orEmpty()
            .split("\n", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val blocked = prefs.getString(KEY_BLOCKED, "")
            .orEmpty()
            .split("\n", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val polygonPoints = parsePolygon(prefs.getString(KEY_POLYGON, "[]").orEmpty())
        val rawZonesJson = prefs.getString(KEY_MAP_ZONES, null)
        val parsedZones = if (!rawZonesJson.isNullOrBlank()) {
            parseMapZones(rawZonesJson)
        } else {
            emptyList()
        }

        // Migracao automatica: se o usuario ja tinha um poligono salvo na versao anterior,
        // transforma em Zona Permitida #1 para nao perder nada
        val finalZones = if (parsedZones.isNotEmpty()) {
            parsedZones
        } else if (polygonPoints.size >= 3) {
            listOf(
                MapZone(
                    id = 1L,
                    name = "Área Permitida 1",
                    mode = ZoneMode.ALLOWED,
                    shape = ZoneShape.POLYGON,
                    points = polygonPoints
                )
            )
        } else {
            emptyList()
        }

        val primaryPoly = finalZones
            .firstOrNull { it.mode == ZoneMode.ALLOWED && it.shape == ZoneShape.POLYGON && it.points.size >= 3 }
            ?.points
            ?: polygonPoints

        val hints = prefs.getString(KEY_REJECT_HINTS, null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.ifEmpty { FilterConfig.DEFAULT_REJECT_HINTS }
            ?: FilterConfig.DEFAULT_REJECT_HINTS

        // Aplica os novos padroes solicitados pelo usuario (Aceitar e Rejeitar ativos por padrao,
        // KM Busca 2.5km, Valor minimo R$ 30/h com +2min de espera, e Card Amarelo <= 1km fora da area)
        if (!prefs.getBoolean(KEY_DEFAULTS_V2_APPLIED, false)) {
            prefs.edit()
                .putBoolean(KEY_DEFAULTS_V2_APPLIED, true)
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_AUTO_REJECT, true)
                .putBoolean(KEY_AUTO_ACCEPT, true)
                .putBoolean(KEY_YELLOW_CARD_OUTSIDE, true)
                .putBoolean(KEY_FILTER_PICKUP_DIST, true)
                .putLong(KEY_MAX_PICKUP_DIST, java.lang.Double.doubleToRawLongBits(2.5))
                .putBoolean(KEY_FILTER_HOURLY, true)
                .putLong(KEY_MIN_HOURLY, java.lang.Double.doubleToRawLongBits(30.0))
                .apply()
        }

        return FilterConfig(
            isEnabled = prefs.getBoolean(KEY_ENABLED, true),
            nineNineModuleEnabled = prefs.getBoolean(KEY_MOD_99, true),
            uberModuleEnabled = prefs.getBoolean(KEY_MOD_UBER, true),
            rejectUberMultiStopRides = prefs.getBoolean(KEY_REJECT_UBER_STOPS, false),
            autoRejectEnabled = prefs.getBoolean(KEY_AUTO_REJECT, true),
            autoAcceptInsideArea = prefs.getBoolean(KEY_AUTO_ACCEPT, true),
            autoClickContinueEnabled = prefs.getBoolean(KEY_AUTO_CONTINUE, true),
            humanDelayEnabled = prefs.getBoolean(KEY_HUMAN_DELAY, true),
            floatingButtonEnabled = prefs.getBoolean(KEY_FLOATING_BTN, true),
            batterySaverInRide = prefs.getBoolean(KEY_BATTERY_SAVER, false),
            favoriteAlertsEnabled = prefs.getBoolean(KEY_FAV_ALERTS, true),
            requireBothPickupAndDropoff = prefs.getBoolean(KEY_REQUIRE_BOTH, true),
            allowInterZoneRides = prefs.getBoolean(KEY_ALLOW_INTER_ZONE, false),
            yellowCardOutsideAreaEnabled = prefs.getBoolean(KEY_YELLOW_CARD_OUTSIDE, true),
            avBrasilOddSideOnly = prefs.getBoolean(KEY_AV_BRASIL_ODD, true),
            reject99RiskAreaBadge = prefs.getBoolean(KEY_REJECT_RISK_BADGE, true),
            polygonPoints = primaryPoly,
            mapZones = finalZones,
            allowedKeywords = allowed,
            blockedKeywords = blocked,
            filterByRatingEnabled = prefs.getBoolean(KEY_FILTER_RATING, false),
            minRating = getDouble(KEY_MIN_RATING, 4.80),
            rejectNewPassenger = prefs.getBoolean(KEY_REJECT_NEW_PASSENGER, false),
            filterByPickupDistEnabled = prefs.getBoolean(KEY_FILTER_PICKUP_DIST, true),
            maxPickupDistanceKm = getDouble(KEY_MAX_PICKUP_DIST, 2.5),
            filterByTotalDistEnabled = prefs.getBoolean(KEY_FILTER_TOTAL_DIST, false),
            maxTotalDistanceKm = getDouble(KEY_MAX_TOTAL_DIST, 15.0),
            filterByHourlyRateEnabled = prefs.getBoolean(KEY_FILTER_HOURLY, true),
            minHourlyRate = getDouble(KEY_MIN_HOURLY, 30.0),
            darkThemeEnabled = prefs.getBoolean(KEY_DARK_THEME, true),
            bannerScale = prefs.getInt(KEY_BANNER_SCALE, 1),
            colorInsideHex = prefs.getString(KEY_COLOR_INSIDE, "#0A2E1D") ?: "#0A2E1D",
            colorOutsideHex = prefs.getString(KEY_COLOR_OUTSIDE, "#330A0A") ?: "#330A0A",
            colorInterZoneHex = prefs.getString(KEY_COLOR_INTER_ZONE, "#271252") ?: "#271252",
            colorYellowReturnHex = prefs.getString(KEY_COLOR_YELLOW_RETURN, "#3B2806") ?: "#3B2806",
            fuelCostEnabled = prefs.getBoolean(KEY_FUEL_ENABLED, true),
            fuelType = prefs.getString(KEY_FUEL_TYPE, FilterConfig.FUEL_GASOLINA) ?: FilterConfig.FUEL_GASOLINA,
            fuelPricePerUnit = getDouble(KEY_FUEL_PRICE, 6.29),
            kmPerUnit = getDouble(KEY_KM_PER_UNIT, 35.0),
            rejectButtonHints = hints,
            debugMode = prefs.getBoolean(KEY_DEBUG, true)
        )
    }

    fun save(config: FilterConfig) {
        val polyArray = JSONArray()
        config.polygonPoints.forEach { (lat, lng) ->
            polyArray.put(JSONArray().put(lat).put(lng))
        }

        val zonesArray = serializeMapZones(config.mapZones)

        prefs.edit()
            .putBoolean(KEY_ENABLED, config.isEnabled)
            .putBoolean(KEY_MOD_99, config.nineNineModuleEnabled)
            .putBoolean(KEY_MOD_UBER, config.uberModuleEnabled)
            .putBoolean(KEY_REJECT_UBER_STOPS, config.rejectUberMultiStopRides)
            .putBoolean(KEY_AUTO_REJECT, config.autoRejectEnabled)
            .putBoolean(KEY_AUTO_ACCEPT, config.autoAcceptInsideArea)
            .putBoolean(KEY_AUTO_CONTINUE, config.autoClickContinueEnabled)
            .putBoolean(KEY_HUMAN_DELAY, config.humanDelayEnabled)
            .putBoolean(KEY_FLOATING_BTN, config.floatingButtonEnabled)
            .putBoolean(KEY_BATTERY_SAVER, config.batterySaverInRide)
            .putBoolean(KEY_FAV_ALERTS, config.favoriteAlertsEnabled)
            .putBoolean(KEY_REQUIRE_BOTH, config.requireBothPickupAndDropoff)
            .putBoolean(KEY_ALLOW_INTER_ZONE, config.allowInterZoneRides)
            .putBoolean(KEY_YELLOW_CARD_OUTSIDE, config.yellowCardOutsideAreaEnabled)
            .putBoolean(KEY_AV_BRASIL_ODD, config.avBrasilOddSideOnly)
            .putBoolean(KEY_REJECT_RISK_BADGE, config.reject99RiskAreaBadge)
            .putString(KEY_POLYGON, polyArray.toString())
            .putString(KEY_MAP_ZONES, zonesArray.toString())
            .putString(KEY_ALLOWED, config.allowedKeywords.joinToString("\n"))
            .putString(KEY_BLOCKED, config.blockedKeywords.joinToString("\n"))
            .putBoolean(KEY_FILTER_RATING, config.filterByRatingEnabled)
            .putLong(KEY_MIN_RATING, java.lang.Double.doubleToRawLongBits(config.minRating))
            .putBoolean(KEY_REJECT_NEW_PASSENGER, config.rejectNewPassenger)
            .putBoolean(KEY_FILTER_PICKUP_DIST, config.filterByPickupDistEnabled)
            .putLong(KEY_MAX_PICKUP_DIST, java.lang.Double.doubleToRawLongBits(config.maxPickupDistanceKm))
            .putBoolean(KEY_FILTER_TOTAL_DIST, config.filterByTotalDistEnabled)
            .putLong(KEY_MAX_TOTAL_DIST, java.lang.Double.doubleToRawLongBits(config.maxTotalDistanceKm))
            .putBoolean(KEY_FILTER_HOURLY, config.filterByHourlyRateEnabled)
            .putLong(KEY_MIN_HOURLY, java.lang.Double.doubleToRawLongBits(config.minHourlyRate))
            .putBoolean(KEY_DARK_THEME, config.darkThemeEnabled)
            .putInt(KEY_BANNER_SCALE, config.bannerScale)
            .putString(KEY_COLOR_INSIDE, config.colorInsideHex)
            .putString(KEY_COLOR_OUTSIDE, config.colorOutsideHex)
            .putString(KEY_COLOR_INTER_ZONE, config.colorInterZoneHex)
            .putString(KEY_COLOR_YELLOW_RETURN, config.colorYellowReturnHex)
            .putBoolean(KEY_FUEL_ENABLED, config.fuelCostEnabled)
            .putString(KEY_FUEL_TYPE, config.fuelType)
            .putLong(KEY_FUEL_PRICE, java.lang.Double.doubleToRawLongBits(config.fuelPricePerUnit))
            .putLong(KEY_KM_PER_UNIT, java.lang.Double.doubleToRawLongBits(config.kmPerUnit))
            .putString(KEY_REJECT_HINTS, config.rejectButtonHints.joinToString(","))
            .putBoolean(KEY_DEBUG, config.debugMode)
            .apply()
    }

    fun setEnabledQuick(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun addAllowedKeyword(keyword: String) {
        val clean = keyword.trim()
        if (clean.isEmpty()) return
        val current = load()
        if (current.allowedKeywords.none { it.equals(clean, ignoreCase = true) }) {
            save(current.copy(allowedKeywords = current.allowedKeywords + clean))
        }
    }

    fun addBlockedKeyword(keyword: String) {
        val clean = keyword.trim()
        if (clean.isEmpty()) return
        val current = load()
        if (current.blockedKeywords.none { it.equals(clean, ignoreCase = true) }) {
            save(current.copy(blockedKeywords = current.blockedKeywords + clean))
        }
    }

    // --- Historico de corridas ---

    fun getRideHistory(): List<RideHistoryItem> {
        val raw = prefs.getString(KEY_HISTORY, "[]").orEmpty()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<RideHistoryItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    RideHistoryItem(
                        id = obj.optLong("id"),
                        timeFormatted = obj.optString("time"),
                        pickupAddress = obj.optString("pickup"),
                        dropoffAddress = obj.optString("dropoff"),
                        priceText = obj.optString("price"),
                        pricePerKmText = obj.optString("pricePerKm"),
                        hourlyRateText = obj.optString("hourly"),
                        ratingText = obj.optString("rating"),
                        isInsideArea = obj.optBoolean("inside"),
                        reason = obj.optString("reason")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRideHistory(item: RideHistoryItem) {
        val updated = (listOf(item) + getRideHistory()).take(15)
        val arr = JSONArray()
        updated.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("time", r.timeFormatted)
                    .put("pickup", r.pickupAddress)
                    .put("dropoff", r.dropoffAddress)
                    .put("price", r.priceText)
                    .put("pricePerKm", r.pricePerKmText)
                    .put("hourly", r.hourlyRateText)
                    .put("rating", r.ratingText)
                    .put("inside", r.isInsideArea)
                    .put("reason", r.reason)
            )
        }
        prefs.edit()
            .putString(KEY_HISTORY, arr.toString())
            .apply()

        if (item.isInsideArea || getLastCandidateForFavorite() == null) {
            setLastCandidateForFavorite(item)
        }
        _historyFlow.value = updated
    }

    fun setLastCandidateForFavorite(item: RideHistoryItem) {
        val obj = JSONObject()
            .put("id", item.id)
            .put("time", item.timeFormatted)
            .put("pickup", item.pickupAddress)
            .put("dropoff", item.dropoffAddress)
            .put("price", item.priceText)
            .put("pricePerKm", item.pricePerKmText)
            .put("hourly", item.hourlyRateText)
            .put("rating", item.ratingText)
            .put("inside", item.isInsideArea)
            .put("reason", item.reason)
        prefs.edit().putString(KEY_LAST_CANDIDATE, obj.toString()).apply()
    }

    fun getLastCandidateForFavorite(): RideHistoryItem? {
        val raw = prefs.getString(KEY_LAST_CANDIDATE, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            RideHistoryItem(
                id = obj.optLong("id"),
                timeFormatted = obj.optString("time"),
                pickupAddress = obj.optString("pickup"),
                dropoffAddress = obj.optString("dropoff"),
                priceText = obj.optString("price"),
                pricePerKmText = obj.optString("pricePerKm"),
                hourlyRateText = obj.optString("hourly"),
                ratingText = obj.optString("rating"),
                isInsideArea = obj.optBoolean("inside"),
                reason = obj.optString("reason")
            )
        } catch (_: Exception) {
            null
        }
    }

    // --- Passageiros / Horarios Favoritos ---

    fun getFavorites(): List<FavoriteRideItem> {
        val raw = prefs.getString(KEY_FAVORITES, "[]").orEmpty()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<FavoriteRideItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    FavoriteRideItem(
                        id = obj.optLong("id"),
                        passengerName = obj.optString("passengerName", ""),
                        rideTimeFormatted = obj.optString("rideTime"),
                        dateFormatted = obj.optString("date"),
                        pickupAddress = obj.optString("pickup"),
                        dropoffAddress = obj.optString("dropoff"),
                        pickupLat = obj.optDouble("pLat", 0.0),
                        pickupLng = obj.optDouble("pLng", 0.0),
                        dropoffLat = obj.optDouble("dLat", 0.0),
                        dropoffLng = obj.optDouble("dLng", 0.0),
                        priceText = obj.optString("price"),
                        ratingText = obj.optString("rating")
                    )
                )
            }
            list.sortedBy { it.rideTimeFormatted }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addFavorite(item: FavoriteRideItem) {
        val current = getFavorites().filterNot {
            it.pickupAddress.equals(item.pickupAddress, ignoreCase = true) &&
                it.rideTimeFormatted == item.rideTimeFormatted
        }
        val updated = (current + item).sortedBy { it.rideTimeFormatted }
        saveFavoritesList(updated)
    }

    fun deleteFavorite(id: Long) {
        val updated = getFavorites().filterNot { it.id == id }
        saveFavoritesList(updated)
    }

    private fun saveFavoritesList(list: List<FavoriteRideItem>) {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("passengerName", f.passengerName)
                    .put("rideTime", f.rideTimeFormatted)
                    .put("date", f.dateFormatted)
                    .put("pickup", f.pickupAddress)
                    .put("dropoff", f.dropoffAddress)
                    .put("pLat", f.pickupLat)
                    .put("pLng", f.pickupLng)
                    .put("dLat", f.dropoffLat)
                    .put("dLng", f.dropoffLng)
                    .put("price", f.priceText)
                    .put("rating", f.ratingText)
            )
        }
        prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }

    private fun getDouble(key: String, defaultVal: Double): Double {
        return java.lang.Double.longBitsToDouble(
            prefs.getLong(key, java.lang.Double.doubleToRawLongBits(defaultVal))
        )
    }

    private fun parsePolygon(json: String): List<Pair<Double, Double>> {
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until arr.length()) {
                val pt = arr.getJSONArray(i)
                list.add(pt.getDouble(0) to pt.getDouble(1))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun serializeMapZones(zones: List<MapZone>): JSONArray {
        val arr = JSONArray()
        zones.forEach { z ->
            val ptsArr = JSONArray()
            z.points.forEach { (lat, lng) ->
                ptsArr.put(JSONArray().put(lat).put(lng))
            }
            arr.put(
                JSONObject()
                    .put("id", z.id)
                    .put("name", z.name)
                    .put("mode", z.mode.name)
                    .put("shape", z.shape.name)
                    .put("points", ptsArr)
                    .put("centerLat", z.centerLat)
                    .put("centerLng", z.centerLng)
                    .put("radiusMeters", z.radiusMeters)
            )
        }
        return arr
    }

    fun parseMapZones(json: String): List<MapZone> {
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<MapZone>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val modeStr = obj.optString("mode", "ALLOWED")
                val shapeStr = obj.optString("shape", "POLYGON")
                val mode = runCatching { ZoneMode.valueOf(modeStr) }.getOrDefault(ZoneMode.ALLOWED)
                val shape = runCatching { ZoneShape.valueOf(shapeStr) }.getOrDefault(ZoneShape.POLYGON)

                val pts = mutableListOf<Pair<Double, Double>>()
                val ptsArr = obj.optJSONArray("points")
                if (ptsArr != null) {
                    for (j in 0 until ptsArr.length()) {
                        val pt = ptsArr.optJSONArray(j) ?: continue
                        pts.add(pt.optDouble(0) to pt.optDouble(1))
                    }
                }

                list.add(
                    MapZone(
                        id = obj.optLong("id", System.currentTimeMillis() + i),
                        name = obj.optString("name", "Zona ${i + 1}"),
                        mode = mode,
                        shape = shape,
                        points = pts,
                        centerLat = obj.optDouble("centerLat", 0.0),
                        centerLng = obj.optDouble("centerLng", 0.0),
                        radiusMeters = obj.optDouble("radiusMeters", 1000.0)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "uber_area_filter_prefs"
        private const val LOG_FILE_NAME = "filtro_inteligente_logs.txt"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MOD_99 = "mod_99_enabled"
        private const val KEY_MOD_UBER = "mod_uber_enabled"
        private const val KEY_REJECT_UBER_STOPS = "reject_uber_multi_stops"
        private const val KEY_AUTO_REJECT = "auto_reject"
        private const val KEY_AUTO_ACCEPT = "auto_accept_inside"
        private const val KEY_AUTO_CONTINUE = "auto_click_continue"
        private const val KEY_HUMAN_DELAY = "human_delay"
        private const val KEY_FLOATING_BTN = "floating_btn_enabled"
        private const val KEY_BATTERY_SAVER = "battery_saver_in_ride"
        private const val KEY_FAV_ALERTS = "favorite_alerts_enabled"
        private const val KEY_REQUIRE_BOTH = "require_both_pickup_dropoff"
        private const val KEY_ALLOW_INTER_ZONE = "allow_inter_zone_rides"
        private const val KEY_YELLOW_CARD_OUTSIDE = "yellow_card_outside_area"
        private const val KEY_DEFAULTS_V2_APPLIED = "defaults_v2_applied"
        private const val KEY_AV_BRASIL_ODD = "av_brasil_odd_side_only"
        private const val KEY_REJECT_RISK_BADGE = "reject_99_risk_badge"
        private const val KEY_POLYGON = "polygon_points"
        private const val KEY_MAP_ZONES = "map_zones_json"
        private const val KEY_ALLOWED = "allowed_keywords"
        private const val KEY_BLOCKED = "blocked_keywords"
        private const val KEY_FILTER_RATING = "filter_rating_enabled"
        private const val KEY_MIN_RATING = "min_rating"
        private const val KEY_REJECT_NEW_PASSENGER = "reject_new_passenger"
        private const val KEY_FILTER_PICKUP_DIST = "filter_pickup_dist_enabled"
        private const val KEY_MAX_PICKUP_DIST = "max_pickup_dist"
        private const val KEY_FILTER_TOTAL_DIST = "filter_total_dist_enabled"
        private const val KEY_MAX_TOTAL_DIST = "max_total_dist"
        private const val KEY_FILTER_HOURLY = "filter_hourly_enabled"
        private const val KEY_MIN_HOURLY = "min_hourly_rate"
        private const val KEY_DARK_THEME = "dark_theme_enabled"
        private const val KEY_BANNER_SCALE = "banner_scale"
        private const val KEY_COLOR_INSIDE = "color_inside_hex"
        private const val KEY_COLOR_OUTSIDE = "color_outside_hex"
        private const val KEY_COLOR_INTER_ZONE = "color_inter_zone_hex"
        private const val KEY_COLOR_YELLOW_RETURN = "color_yellow_return_hex"
        private const val KEY_FUEL_ENABLED = "fuel_cost_enabled"
        private const val KEY_FUEL_TYPE = "fuel_type"
        private const val KEY_FUEL_PRICE = "fuel_price_per_unit"
        private const val KEY_KM_PER_UNIT = "km_per_unit"
        private const val KEY_REJECT_HINTS = "reject_hints"
        private const val KEY_DEBUG = "debug_mode"
        private const val KEY_HISTORY = "ride_history_json"
        private const val KEY_FAVORITES = "favorite_rides_json"
        private const val KEY_LAST_CANDIDATE = "last_candidate_ride_json"

        @Volatile
        private var cachedLogFile: File? = null

        private val _debugLogs = MutableStateFlow<List<String>>(emptyList())
        val debugLogs: StateFlow<List<String>> = _debugLogs.asStateFlow()

        private val _historyFlow = MutableStateFlow<List<RideHistoryItem>>(emptyList())
        val historyFlow: StateFlow<List<RideHistoryItem>> = _historyFlow.asStateFlow()

        private fun resolveLogFile(context: Context): File {
            cachedLogFile?.let { return it }
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, LOG_FILE_NAME)
            cachedLogFile = file
            return file
        }

        fun ensureDiskLogsLoaded(context: Context) {
            try {
                val file = resolveLogFile(context.applicationContext)
                if (file.exists() && _debugLogs.value.isEmpty()) {
                    val lines = file.readLines()
                    _debugLogs.value = lines.takeLast(120)
                }
            } catch (_: Exception) {}
        }

        fun appendLog(line: String, context: Context? = null) {
            val updated = (_debugLogs.value + line).takeLast(120)
            _debugLogs.value = updated

            try {
                val file = if (context != null) resolveLogFile(context.applicationContext) else cachedLogFile
                if (file != null) {
                    // Se o arquivo passar de 600 KB (~5.000 linhas), mantem as ultimas 1.500 linhas
                    if (file.exists() && file.length() > 600_000L) {
                        val recent = file.readLines().takeLast(1500)
                        file.writeText(recent.joinToString("\n") + "\n")
                    }
                    file.appendText(line + "\n")
                }
            } catch (_: Exception) {}
        }

        fun clearLogs(context: Context? = null) {
            _debugLogs.value = emptyList()
            try {
                val file = if (context != null) resolveLogFile(context.applicationContext) else cachedLogFile
                if (file != null && file.exists()) {
                    file.writeText("")
                }
            } catch (_: Exception) {}
        }

        fun getFullLogsText(context: Context): String {
            return try {
                val file = resolveLogFile(context.applicationContext)
                if (file.exists() && file.length() > 0L) {
                    file.readText()
                } else {
                    _debugLogs.value.joinToString("\n")
                }
            } catch (_: Exception) {
                _debugLogs.value.joinToString("\n")
            }
        }

        /**
         * Salva uma copia completa dos logs na pasta publica Downloads do celular e retorna o nome do arquivo salvo.
         */
        fun exportLogsToDownloads(context: Context): String? {
            return exportLogsToDownloadsWithUri(context)?.first
        }

        /**
         * Salva os logs em Downloads e retorna (caminho legivel, Uri compartilhavel ou null).
         */
        fun exportLogsToDownloadsWithUri(context: Context): Pair<String, android.net.Uri?>? {
            val header = "=== Filtro Inteligente 99/Uber • Logs exportados em " +
                SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date()) +
                " • Android ${Build.VERSION.RELEASE} • ${Build.MANUFACTURER} ${Build.MODEL} ===\n"
            val content = header + getFullLogsText(context).ifBlank { "Sem registros de log até o momento." }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "Filtro_Logs_$stamp.txt"

            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    }
                    "Download/$fileName" to uri
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    val outFile = File(dir, fileName)
                    outFile.writeText(content)
                    outFile.absolutePath to null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
