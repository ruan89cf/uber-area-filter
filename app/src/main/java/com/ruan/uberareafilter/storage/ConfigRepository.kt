package com.ruan.uberareafilter.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ruan.uberareafilter.engine.AddressParser
import com.ruan.uberareafilter.engine.BlockedMatcher
import com.ruan.uberareafilter.engine.BlockedPlace
import com.ruan.uberareafilter.engine.ZoneShare
import com.ruan.uberareafilter.model.DualConflictPolicy
import com.ruan.uberareafilter.model.FavoriteRideItem
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.model.RideHistoryItem
import com.ruan.uberareafilter.model.RunState
import com.ruan.uberareafilter.model.ZoneMode
import com.ruan.uberareafilter.model.ZoneShape
import com.ruan.uberareafilter.model.ZoneStreetIndex
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

        // 2.1: o limite do encaixe passou a ser "falta da corrida + ida ate o proximo passageiro" (padrao 3 min)
        if (!prefs.getBoolean(KEY_CHAIN_RULE_V2, false)) {
            prefs.edit().putBoolean(KEY_CHAIN_RULE_V2, true).putInt(KEY_CHAIN_MAX_WAIT, 3).apply()
        }

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
            actionsPaused = prefs.getBoolean(KEY_ACTIONS_PAUSED, false),
            nineNineModuleEnabled = prefs.getBoolean(KEY_MOD_99, true),
            uberModuleEnabled = prefs.getBoolean(KEY_MOD_UBER, true),
            dualConflictGuardEnabled = prefs.getBoolean(KEY_DUAL_CONFLICT_GUARD, true),
            dualConflictPolicy = prefs.getString(KEY_DUAL_CONFLICT_POLICY, DualConflictPolicy.BLOCK_AUTO_ACCEPT_ONLY.name)
                ?.let { raw -> runCatching { DualConflictPolicy.valueOf(raw) }.getOrNull() }
                ?: DualConflictPolicy.BLOCK_AUTO_ACCEPT_ONLY,
            chainAcrossAppsEnabled = prefs.getBoolean(KEY_CHAIN_APPS, true),
            chainMaxWaitMin = prefs.getInt(KEY_CHAIN_MAX_WAIT, 3).coerceIn(1, 30),
            rejectUberMultiStopRides = prefs.getBoolean(KEY_REJECT_UBER_STOPS, false),
            autoRejectEnabled = prefs.getBoolean(KEY_AUTO_REJECT, true),
            autoAcceptInsideArea = prefs.getBoolean(KEY_AUTO_ACCEPT, true),
            autoClickContinueEnabled = prefs.getBoolean(KEY_AUTO_CONTINUE, true),
            humanDelayEnabled = prefs.getBoolean(KEY_HUMAN_DELAY, true),
            floatingButtonEnabled = prefs.getBoolean(KEY_FLOATING_BTN, true),
            offButtonMinutes = prefs.getInt(KEY_OFF_BUTTON_MIN, 10),
            batterySaverInRide = prefs.getBoolean(KEY_BATTERY_SAVER, false),
            favoriteAlertsEnabled = prefs.getBoolean(KEY_FAV_ALERTS, true),
            requireBothPickupAndDropoff = prefs.getBoolean(KEY_REQUIRE_BOTH, true),
            allowInterZoneRides = prefs.getBoolean(KEY_ALLOW_INTER_ZONE, false),
            autoAcceptInterZone = prefs.getBoolean(KEY_AUTO_ACCEPT_INTER_ZONE, false),
            yellowCardOutsideAreaEnabled = prefs.getBoolean(KEY_YELLOW_CARD_OUTSIDE, true),
            acceptRidesIntoArea = prefs.getBoolean(KEY_INTO_AREA, true),
            avBrasilOddSideOnly = prefs.getBoolean(KEY_AV_BRASIL_ODD, true),
            reject99RiskAreaBadge = prefs.getBoolean(KEY_REJECT_RISK_BADGE, true),
            rejectUncertainLocation = prefs.getBoolean(KEY_REJECT_UNCERTAIN, false),
            polygonPoints = primaryPoly,
            mapZones = finalZones,
            allowedKeywords = allowed,
            blockedKeywords = blocked,
            blockedPlaces = loadBlockedPlaces(blocked),
            trustedStreets = loadTrustedStreets(),
            streetIndex = loadStreetIndex(),
            filterByRatingEnabled = prefs.getBoolean(KEY_FILTER_RATING, false),
            minRating = getDouble(KEY_MIN_RATING, 4.80),
            rejectNewPassenger = prefs.getBoolean(KEY_REJECT_NEW_PASSENGER, false),
            filterByPickupDistEnabled = prefs.getBoolean(KEY_FILTER_PICKUP_DIST, true),
            maxPickupDistanceKm = getDouble(KEY_MAX_PICKUP_DIST, 2.5),
            filterByTotalDistEnabled = prefs.getBoolean(KEY_FILTER_TOTAL_DIST, false),
            maxTotalDistanceKm = getDouble(KEY_MAX_TOTAL_DIST, 15.0),
            filterByHourlyRateEnabled = prefs.getBoolean(KEY_FILTER_HOURLY, true),
            minHourlyRate = getDouble(KEY_MIN_HOURLY, 30.0),
            uberCustomHourlyEnabled = prefs.getBoolean(KEY_UBER_CUSTOM_HOURLY, false),
            uberMinHourlyRate = getDouble(KEY_UBER_MIN_HOURLY, 30.0),
            useNetHourlyRate = prefs.getBoolean(KEY_USE_NET_HOURLY, false),
            filterByPricePerKmEnabled = prefs.getBoolean(KEY_FILTER_PRICE_PER_KM, false),
            minPricePerKm = getDouble(KEY_MIN_PRICE_PER_KM, 1.20),
            zoneCardColorsEnabled = prefs.getBoolean(KEY_ZONE_CARD_COLORS, false),
            roundDownNetHourlyRate = prefs.getBoolean(KEY_ROUND_DOWN_NET_HOURLY, false),
            darkThemeEnabled = prefs.getBoolean(KEY_DARK_THEME, true),
            bannerScale = prefs.getInt(KEY_BANNER_SCALE, 1),
            showRideInfoBannerEnabled = prefs.getBoolean(KEY_SHOW_RIDE_BANNER, true),
            bannerAutoHideSeconds = prefs.getInt(KEY_BANNER_DURATION_SEC, 5).coerceIn(1, 30),
            colorInsideHex = prefs.getString(KEY_COLOR_INSIDE, "#0A2E1D") ?: "#0A2E1D",
            colorOutsideHex = prefs.getString(KEY_COLOR_OUTSIDE, "#330A0A") ?: "#330A0A",
            colorInterZoneHex = prefs.getString(KEY_COLOR_INTER_ZONE, "#271252") ?: "#271252",
            colorYellowReturnHex = prefs.getString(KEY_COLOR_YELLOW_RETURN, "#3B2806") ?: "#3B2806",
            fuelCostEnabled = prefs.getBoolean(KEY_FUEL_ENABLED, true),
            fuelType = prefs.getString(KEY_FUEL_TYPE, FilterConfig.FUEL_GASOLINA) ?: FilterConfig.FUEL_GASOLINA,
            fuelPricePerUnit = getDouble(KEY_FUEL_PRICE, 6.29),
            kmPerUnit = getDouble(KEY_KM_PER_UNIT, 35.0),
            rejectButtonHints = hints,
            debugMode = prefs.getBoolean(KEY_DEBUG, true),
            xrayDiagnosticEnabled = prefs.getBoolean(KEY_XRAY_DIAGNOSTIC, true),
            saveRideScreenshotsEnabled = prefs.getBoolean(KEY_SAVE_RIDE_SCREENSHOTS, true),
            maxRideHistory = maxRideHistory()
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
            .putBoolean(KEY_ACTIONS_PAUSED, config.actionsPaused)
            .putBoolean(KEY_MOD_99, config.nineNineModuleEnabled)
            .putBoolean(KEY_MOD_UBER, config.uberModuleEnabled)
            .putBoolean(KEY_DUAL_CONFLICT_GUARD, config.dualConflictGuardEnabled)
            .putString(KEY_DUAL_CONFLICT_POLICY, config.dualConflictPolicy.name)
            .putBoolean(KEY_CHAIN_APPS, config.chainAcrossAppsEnabled)
            .putInt(KEY_CHAIN_MAX_WAIT, config.chainMaxWaitMin.coerceIn(1, 30))
            .putBoolean(KEY_REJECT_UBER_STOPS, config.rejectUberMultiStopRides)
            .putBoolean(KEY_AUTO_REJECT, config.autoRejectEnabled)
            .putBoolean(KEY_AUTO_ACCEPT, config.autoAcceptInsideArea)
            .putBoolean(KEY_AUTO_CONTINUE, config.autoClickContinueEnabled)
            .putBoolean(KEY_HUMAN_DELAY, config.humanDelayEnabled)
            .putBoolean(KEY_FLOATING_BTN, config.floatingButtonEnabled)
            .putInt(KEY_OFF_BUTTON_MIN, config.offButtonMinutes)
            .putBoolean(KEY_BATTERY_SAVER, config.batterySaverInRide)
            .putBoolean(KEY_FAV_ALERTS, config.favoriteAlertsEnabled)
            .putBoolean(KEY_REQUIRE_BOTH, config.requireBothPickupAndDropoff)
            .putBoolean(KEY_ALLOW_INTER_ZONE, config.allowInterZoneRides)
            .putBoolean(KEY_AUTO_ACCEPT_INTER_ZONE, config.autoAcceptInterZone)
            .putBoolean(KEY_YELLOW_CARD_OUTSIDE, config.yellowCardOutsideAreaEnabled)
            .putBoolean(KEY_INTO_AREA, config.acceptRidesIntoArea)
            .putBoolean(KEY_AV_BRASIL_ODD, config.avBrasilOddSideOnly)
            .putBoolean(KEY_REJECT_RISK_BADGE, config.reject99RiskAreaBadge)
            .putBoolean(KEY_REJECT_UNCERTAIN, config.rejectUncertainLocation)
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
            .putBoolean(KEY_UBER_CUSTOM_HOURLY, config.uberCustomHourlyEnabled)
            .putLong(KEY_UBER_MIN_HOURLY, java.lang.Double.doubleToRawLongBits(config.uberMinHourlyRate))
            .putBoolean(KEY_USE_NET_HOURLY, config.useNetHourlyRate)
            .putBoolean(KEY_FILTER_PRICE_PER_KM, config.filterByPricePerKmEnabled)
            .putLong(KEY_MIN_PRICE_PER_KM, java.lang.Double.doubleToRawLongBits(config.minPricePerKm))
            .putBoolean(KEY_ZONE_CARD_COLORS, config.zoneCardColorsEnabled)
            .putBoolean(KEY_ROUND_DOWN_NET_HOURLY, config.roundDownNetHourlyRate)
            .putBoolean(KEY_DARK_THEME, config.darkThemeEnabled)
            .putInt(KEY_BANNER_SCALE, config.bannerScale)
            .putBoolean(KEY_SHOW_RIDE_BANNER, config.showRideInfoBannerEnabled)
            .putInt(KEY_BANNER_DURATION_SEC, config.bannerAutoHideSeconds.coerceIn(1, 30))
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
            .putBoolean(KEY_XRAY_DIAGNOSTIC, config.xrayDiagnosticEnabled)
            .putBoolean(KEY_SAVE_RIDE_SCREENSHOTS, config.saveRideScreenshotsEnabled)
            .putInt(KEY_MAX_HISTORY, config.maxRideHistory.coerceIn(MIN_HISTORY_ITEMS, MAX_HISTORY_ITEMS))
            .apply()
    }

    fun setEnabledQuick(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Grava ON / PAUSA / OFF de uma vez (commit: o servico pode ser desligado logo em seguida). */
    fun setRunStateQuick(state: RunState) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, state != RunState.OFF)
            .putBoolean(KEY_ACTIONS_PAUSED, state == RunState.PAUSED)
            .commit()
    }

    fun addAllowedKeyword(keyword: String) {
        val clean = keyword.trim()
        if (clean.isEmpty()) return
        val current = load()
        if (current.allowedKeywords.none { it.equals(clean, ignoreCase = true) }) {
            save(current.copy(allowedKeywords = current.allowedKeywords + clean))
        }
        // Marcada por voce a partir do historico = rua confiavel (aprova so pelo nome, mesmo sem numero)
        addTrustedStreet(clean)
    }

    // --- Ruas confiaveis (marcadas manualmente) e indice de ruas internas/divisa ---
    // Ficam em chaves proprias: o save() geral NAO as sobrescreve (evita perder o indice com uma copia antiga da config).

    fun loadTrustedStreets(): List<String> =
        prefs.getString(KEY_TRUSTED_STREETS, "")
            .orEmpty()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun addTrustedStreet(name: String) {
        // O historico guarda o embarque como "UBER: Rua X, Bairro" -> tira o prefixo e fica so com a rua
        val stripped = name.replace(Regex("""^\s*(uber|99)\s*:\s*""", RegexOption.IGNORE_CASE), "").trim()
        val clean = AddressParser.parse(stripped).streetRaw?.trim() ?: return // so logradouros viram "confiaveis"
        if (clean.length < 3) return
        val current = loadTrustedStreets()
        if (current.none { it.equals(clean, ignoreCase = true) }) {
            prefs.edit().putString(KEY_TRUSTED_STREETS, (current + clean).joinToString("\n")).apply()
        }
    }

    fun removeTrustedStreet(name: String) {
        val current = loadTrustedStreets()
        prefs.edit()
            .putString(KEY_TRUSTED_STREETS, current.filterNot { it.equals(name.trim(), ignoreCase = true) }.joinToString("\n"))
            .apply()
    }

    fun loadStreetIndex(): Map<Long, ZoneStreetIndex> {
        val raw = prefs.getString(KEY_STREET_INDEX, null)
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val arr = JSONArray(raw)
            val out = LinkedHashMap<Long, ZoneStreetIndex>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                fun names(key: String): List<String> {
                    val a = o.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
                }
                val idx = ZoneStreetIndex(
                    zoneId = o.getLong("zoneId"),
                    geometryHash = o.optString("geometryHash"),
                    complete = o.optBoolean("complete", false),
                    internalStreets = names("internal"),
                    touchingStreets = names("touching"),
                    builtAtMs = o.optLong("builtAtMs", 0L)
                )
                out[idx.zoneId] = idx
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveStreetIndex(index: Map<Long, ZoneStreetIndex>) {
        val arr = JSONArray()
        index.values.forEach { idx ->
            arr.put(
                JSONObject()
                    .put("zoneId", idx.zoneId)
                    .put("geometryHash", idx.geometryHash)
                    .put("complete", idx.complete)
                    .put("internal", JSONArray(idx.internalStreets))
                    .put("touching", JSONArray(idx.touchingStreets))
                    .put("builtAtMs", idx.builtAtMs)
            )
        }
        prefs.edit().putString(KEY_STREET_INDEX, arr.toString()).apply()
    }

    // --- Locais bloqueados pelo endereco (aprendidos das ofertas ou achados no mapa) ---
    // Chave propria: o save() geral nao mexe (a tela de ajustes nao conhece essa lista).

    /** "Local" que na verdade e so a rua com o mesmo nome do termo (sem numero): nao serve como local. */
    private fun isStreetOnlyPlace(p: BlockedPlace): Boolean {
        if (p.number.isNotBlank()) return false
        val st = com.ruan.uberareafilter.engine.TextNormalizer.normalize(p.street)
        val t = com.ruan.uberareafilter.engine.TextNormalizer.normalize(p.term).trim()
        return t.isNotEmpty() && st.contains(t)
    }

    /** So os locais cujo nome ainda esta na lista de bloqueados (tirou o nome da lista = esquece o local). */
    fun loadBlockedPlaces(activeTerms: List<String>): List<BlockedPlace> {
        val raw = prefs.getString(KEY_BLOCKED_PLACES, null)
        if (raw.isNullOrBlank() || activeTerms.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val term = o.optString("term").trim()
                if (term.isEmpty() || activeTerms.none { it.trim().equals(term, ignoreCase = true) }) return@mapNotNull null
                BlockedPlace(
                    term = term,
                    street = o.optString("street").trim(),
                    number = o.optString("number").trim(),
                    lat = if (o.has("lat")) o.optDouble("lat") else null,
                    lng = if (o.has("lng")) o.optDouble("lng") else null
                ).takeUnless { isStreetOnlyPlace(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Acrescenta locais (sem repetir). @return os que eram novos. */
    fun addBlockedPlaces(places: List<BlockedPlace>): List<BlockedPlace> {
        if (places.isEmpty()) return emptyList()
        val all = loadAllBlockedPlacesRaw().toMutableList()
        val added = mutableListOf<BlockedPlace>()
        for (p in places) {
            val idx = all.indexOfFirst { BlockedMatcher.samePlace(it, p) || (it.term.equals(p.term, true) && it.lat == null && p.lat != null && it.number == p.number) }
            if (idx < 0) {
                all.add(p)
                added.add(p)
            } else if (all[idx].lat == null && p.lat != null) {
                all[idx] = all[idx].copy(lat = p.lat, lng = p.lng)
                added.add(p)
            }
        }
        if (added.isNotEmpty()) {
            val arr = JSONArray()
            all.takeLast(200).forEach { p ->
                val o = JSONObject().put("term", p.term).put("street", p.street).put("number", p.number)
                p.lat?.let { o.put("lat", it) }
                p.lng?.let { o.put("lng", it) }
                arr.put(o)
            }
            prefs.edit().putString(KEY_BLOCKED_PLACES, arr.toString()).apply()
        }
        return added
    }

    /** Nome bloqueado ja procurado no mapa (nao procura de novo a cada religada do servico). */
    fun isBlockedTermChecked(term: String): Boolean =
        prefs.getStringSet(KEY_BLOCKED_CHECKED, emptySet()).orEmpty().contains(term.trim().lowercase())

    fun markBlockedTermChecked(term: String) {
        val cur = prefs.getStringSet(KEY_BLOCKED_CHECKED, emptySet()).orEmpty().toMutableSet()
        if (cur.add(term.trim().lowercase())) prefs.edit().putStringSet(KEY_BLOCKED_CHECKED, cur).apply()
    }

    private fun loadAllBlockedPlacesRaw(): List<BlockedPlace> {
        val raw = prefs.getString(KEY_BLOCKED_PLACES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                BlockedPlace(
                    term = o.optString("term").trim(),
                    street = o.optString("street").trim(),
                    number = o.optString("number").trim(),
                    lat = if (o.has("lat")) o.optDouble("lat") else null,
                    lng = if (o.has("lng")) o.optDouble("lng") else null
                ).takeUnless { isStreetOnlyPlace(it) }
            }
        } catch (_: Exception) {
            emptyList()
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
                        reason = obj.optString("reason"),
                        screenshotPath = obj.optString("screenshotPath", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }


    private fun serializeHistoryList(items: List<RideHistoryItem>): JSONArray {
        val arr = JSONArray()
        items.forEach { r ->
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
                    .put("screenshotPath", r.screenshotPath)
            )
        }
        return arr
    }

    /** Opcao de desenvolvedor: quantas corridas (com foto) o historico guarda. Padrao 500. */
    fun maxRideHistory(): Int = prefs.getInt(KEY_MAX_HISTORY, MAX_HISTORY_ITEMS).coerceIn(MIN_HISTORY_ITEMS, MAX_HISTORY_ITEMS)

    fun addRideHistory(item: RideHistoryItem) {
        val limit = maxRideHistory()
        val allItems = listOf(item) + getRideHistory()
        val updated = allItems.take(limit)
        val retainedPaths = updated.mapNotNull { it.screenshotPath.takeIf { p -> p.isNotBlank() } }.toSet()
        allItems.drop(limit).forEach { dropped ->
            val path = dropped.screenshotPath
            if (path.isNotBlank() && path !in retainedPaths) {
                try {
                    File(path).delete()
                } catch (_: Exception) {
                }
            }
        }
        val arr = serializeHistoryList(updated)
        prefs.edit()
            .putString(KEY_HISTORY, arr.toString())
            .apply()

        if (item.isInsideArea || getLastCandidateForFavorite() == null) {
            setLastCandidateForFavorite(item)
        }
        _historyFlow.value = updated
    }

    /**
     * Apaga todos os prints silenciosos do armazenamento interno privado do app
     * e limpa os caminhos salvos nos cards do historico.
     */
    fun clearAllRideScreenshots(): Int {
        val removed = clearRideScreenshotsOnDisk(appContext)
        val current = getRideHistory()
        if (current.any { it.screenshotPath.isNotBlank() }) {
            val cleaned = current.map { it.copy(screenshotPath = "") }
            prefs.edit()
                .putString(KEY_HISTORY, serializeHistoryList(cleaned).toString())
                .apply()
            _historyFlow.value = cleaned
        }
        return removed
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

    // --- Trocar zonas com outro motorista (arquivo .json pelo WhatsApp) ---

    /** Arquivo com as suas zonas (liberadas, proibidas, nomes, cores e ruas de cada uma). */
    fun exportZonesJson(): String {
        val cfg = load()
        return JSONObject()
            .put("tipo", ZoneShare.FILE_KIND)
            .put("versao", ZoneShare.FILE_VERSION)
            .put("app", "Filtro Inteligente")
            .put("criadoEm", SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date()))
            .put("zonas", serializeMapZones(cfg.mapZones))
            .toString()
    }

    /** Le um arquivo de zonas (o deste app ou so a lista de zonas). null = nao e um arquivo de zonas. */
    fun parseZonesFile(text: String): List<MapZone>? {
        val trimmed = text.trim().removePrefix("﻿")
        val arr: JSONArray = when {
            trimmed.startsWith("{") -> {
                val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
                if (obj.optString("tipo") != ZoneShare.FILE_KIND && !obj.has("zonas")) return null
                obj.optJSONArray("zonas") ?: return null
            }
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull() ?: return null
            else -> return null
        }
        return parseMapZones(arr.toString()).filter { ZoneShare.isValid(it) }.takeIf { it.isNotEmpty() }
    }

    /**
     * Grava as zonas recebidas (substituindo ou juntando com as suas). As ruas de cada zona vem junto,
     * e o indice de ruas internas/divisa e refeito sozinho na proxima vez que o app abrir.
     */
    fun importZones(incoming: List<MapZone>, replace: Boolean): ZoneShare.MergeResult {
        val cfg = load()
        val result = if (replace) ZoneShare.replace(incoming) else ZoneShare.merge(cfg.mapZones, incoming)
        val primary = result.zones
            .firstOrNull { it.mode == ZoneMode.ALLOWED && it.shape == ZoneShape.POLYGON && it.points.size >= 3 }
            ?.points.orEmpty()
        val importedStreets = incoming.flatMap { it.streetNames }
        val streets = if (replace) {
            importedStreets.distinctBy { it.lowercase() }
        } else {
            (cfg.allowedKeywords + importedStreets).distinctBy { it.lowercase() }
        }
        save(cfg.copy(mapZones = result.zones, polygonPoints = primary, allowedKeywords = streets))
        return result
    }

    fun serializeMapZones(zones: List<MapZone>): JSONArray {
        val arr = JSONArray()
        zones.forEach { z ->
            val ptsArr = JSONArray()
            z.points.forEach { (lat, lng) ->
                ptsArr.put(JSONArray().put(lat).put(lng))
            }
            val streetsArr = JSONArray()
            z.streetNames.forEach { streetsArr.put(it) }
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
                    .put("streetNames", streetsArr)
                    .put("cardColorHex", z.cardColorHex)
            )
        }
        return arr
    }

    fun parseMapZones(rawJson: String): List<MapZone> {
        if (rawJson.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            val list = mutableListOf<MapZone>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val mode = when (obj.optString("mode")) {
                    "BLOCKED" -> ZoneMode.BLOCKED
                    else -> ZoneMode.ALLOWED
                }
                val shape = when (obj.optString("shape")) {
                    "CIRCLE" -> ZoneShape.CIRCLE
                    else -> ZoneShape.POLYGON
                }

                val pts = mutableListOf<Pair<Double, Double>>()
                val ptsArr = obj.optJSONArray("points")
                if (ptsArr != null) {
                    for (j in 0 until ptsArr.length()) {
                        val pt = ptsArr.optJSONArray(j) ?: continue
                        pts.add(pt.optDouble(0) to pt.optDouble(1))
                    }
                }

                val streets = mutableListOf<String>()
                val streetsArr = obj.optJSONArray("streetNames")
                if (streetsArr != null) {
                    for (j in 0 until streetsArr.length()) {
                        val s = streetsArr.optString(j).trim()
                        if (s.isNotEmpty()) streets.add(s)
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
                        radiusMeters = obj.optDouble("radiusMeters", 1000.0),
                        streetNames = streets,
                        cardColorHex = obj.optString("cardColorHex", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isWhatsNewSeen(currentVersion: Int): Boolean {
        return prefs.getInt(KEY_WHATS_NEW_VERSION, 0) >= currentVersion
    }

    fun whatsNewSeenVersion(): Int = prefs.getInt(KEY_WHATS_NEW_VERSION, 0)

    fun markWhatsNewSeen(currentVersion: Int) {
        prefs.edit().putInt(KEY_WHATS_NEW_VERSION, currentVersion).apply()
    }

    companion object {
        private const val PREFS_NAME = "uber_area_filter_prefs"
        private const val LOG_FILE_NAME = "filtro_inteligente_logs.txt"
        private const val LOG_PREV_FILE_NAME = "filtro_inteligente_logs.1.txt"
        private const val LOG_ROTATE_BYTES = 2_000_000L
        private const val LOG_ARCHIVES = 10

        /** "filtro_inteligente_logs.1.txt" (mais recente) ... ".10.txt" (mais antigo). */
        private fun archiveLogName(i: Int) = "filtro_inteligente_logs.$i.txt"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ACTIONS_PAUSED = "actions_paused"
        private const val KEY_MOD_99 = "mod_99_enabled"
        private const val KEY_MOD_UBER = "mod_uber_enabled"
        private const val KEY_DUAL_CONFLICT_GUARD = "dual_conflict_guard_enabled"
        private const val KEY_DUAL_CONFLICT_POLICY = "dual_conflict_policy"
        private const val KEY_REJECT_UBER_STOPS = "reject_uber_multi_stops"
        private const val KEY_AUTO_REJECT = "auto_reject"
        private const val KEY_AUTO_ACCEPT = "auto_accept_inside"
        private const val KEY_AUTO_CONTINUE = "auto_click_continue"
        private const val KEY_HUMAN_DELAY = "human_delay"
        private const val KEY_FLOATING_BTN = "floating_btn_enabled"
        private const val KEY_OFF_BUTTON_MIN = "off_button_minutes"
        private const val KEY_BATTERY_SAVER = "battery_saver_in_ride"
        private const val KEY_FAV_ALERTS = "favorite_alerts_enabled"
        private const val KEY_REQUIRE_BOTH = "require_both_pickup_dropoff"
        private const val KEY_ALLOW_INTER_ZONE = "allow_inter_zone_rides"
        private const val KEY_YELLOW_CARD_OUTSIDE = "yellow_card_outside_area"
        private const val KEY_INTO_AREA = "accept_rides_into_area"
        private const val KEY_DEFAULTS_V2_APPLIED = "defaults_v2_applied"
        private const val KEY_AV_BRASIL_ODD = "av_brasil_odd_side_only"
        private const val KEY_REJECT_RISK_BADGE = "reject_99_risk_badge"
        private const val KEY_REJECT_UNCERTAIN = "reject_uncertain_location"
        private const val KEY_TRUSTED_STREETS = "trusted_streets"
        private const val KEY_STREET_INDEX = "zone_street_index_json"
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
        private const val KEY_FILTER_PRICE_PER_KM = "filter_price_per_km_enabled"
        private const val KEY_MIN_PRICE_PER_KM = "min_price_per_km"
        private const val KEY_ZONE_CARD_COLORS = "zone_card_colors_enabled"
        private const val KEY_UBER_CUSTOM_HOURLY = "uber_custom_hourly_enabled"
        private const val KEY_UBER_MIN_HOURLY = "uber_min_hourly_rate"
        private const val KEY_USE_NET_HOURLY = "use_net_hourly_rate"
        private const val KEY_ROUND_DOWN_NET_HOURLY = "round_down_net_hourly_rate"
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
        private const val KEY_AUTO_ACCEPT_INTER_ZONE = "auto_accept_inter_zone"
        private const val KEY_SHOW_RIDE_BANNER = "show_ride_info_banner"
        private const val KEY_BANNER_DURATION_SEC = "banner_auto_hide_seconds"
        private const val KEY_REJECT_HINTS = "reject_hints"
        private const val KEY_DEBUG = "debug_mode"
        private const val KEY_XRAY_DIAGNOSTIC = "xray_diagnostic_enabled"
        private const val KEY_SAVE_RIDE_SCREENSHOTS = "save_ride_screenshots_enabled"
        private const val KEY_WHATS_NEW_VERSION = "whats_new_last_seen_version"
        private const val KEY_BLOCKED_PLACES = "blocked_places_json"
        private const val KEY_BLOCKED_CHECKED = "blocked_terms_checked"
        private const val KEY_CHAIN_APPS = "chain_across_apps"
        private const val KEY_CHAIN_MAX_WAIT = "chain_max_wait_min"
        private const val KEY_CHAIN_RULE_V2 = "chain_rule_v2_total_wait"
        private const val KEY_HISTORY = "ride_history_json"
        private const val KEY_FAVORITES = "favorite_rides_json"
        private const val KEY_LAST_CANDIDATE = "last_candidate_ride_json"
        // Historico de corridas (cada uma com a sua foto): 20 a 500, padrao 500 (opcao de desenvolvedor)
        const val MAX_HISTORY_ITEMS = 500
        const val MIN_HISTORY_ITEMS = 20
        private const val KEY_MAX_HISTORY = "max_ride_history"
        private const val RIDE_SHOTS_DIR = "ride_shots"

        private fun getRideShotsDir(context: Context): File {
            // Armazenamento interno privado do app (UID sandbox): invisivel para Galeria, Uber e 99
            return File(context.applicationContext.filesDir, RIDE_SHOTS_DIR)
        }

        fun buildRideScreenshotPath(context: Context, rideId: Long): String {
            val dir = getRideShotsDir(context)
            return File(dir, "ride_$rideId.jpg").absolutePath
        }

        fun saveRideScreenshotBytesToPath(path: String, jpegBytes: ByteArray): Boolean {
            return try {
                val target = File(path)
                target.parentFile?.mkdirs()
                target.writeBytes(jpegBytes)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun getRideScreenshotsStats(context: Context): Pair<Int, Long> {
            return try {
                val dir = getRideShotsDir(context)
                val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }.orEmpty()
                files.size to files.sumOf { it.length() }
            } catch (_: Exception) {
                0 to 0L
            }
        }

        fun clearRideScreenshotsOnDisk(context: Context): Int {
            return try {
                val dir = getRideShotsDir(context)
                val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
                var count = 0
                files.forEach { file ->
                    if (file.delete()) count++
                }
                count
            } catch (_: Exception) {
                0
            }
        }

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

        @Volatile
        private var lastLoggedDay: String = ""

        @Synchronized
        fun appendLog(line: String, context: Context? = null) {
            val updated = (_debugLogs.value + line).takeLast(120)
            _debugLogs.value = updated

            try {
                val file = if (context != null) resolveLogFile(context.applicationContext) else cachedLogFile
                if (file != null) {
                    // Rotacao: ao passar de 2 MB o arquivo vira ".1", o ".1" vira ".2"... ate ".10"
                    // (~20 MB, varios dias de rodagem). Um dia inteiro passa de 4 MB: com so 2 arquivos
                    // a manha se perdia antes de dar para analisar.
                    if (file.exists() && file.length() > LOG_ROTATE_BYTES) {
                        val dir = file.parentFile
                        File(dir, archiveLogName(LOG_ARCHIVES)).delete()
                        for (i in LOG_ARCHIVES - 1 downTo 1) {
                            val f = File(dir, archiveLogName(i))
                            if (f.exists()) f.renameTo(File(dir, archiveLogName(i + 1)))
                        }
                        file.renameTo(File(dir, archiveLogName(1)))
                        lastLoggedDay = "" // arquivo novo comeca com o cabecalho de data
                    }
                    // Cabecalho de data (as linhas so tem HH:mm:ss)
                    val day = SimpleDateFormat("dd/MM/yyyy (EEE)", Locale("pt", "BR")).format(Date())
                    if (day != lastLoggedDay) {
                        lastLoggedDay = day
                        file.appendText("===== $day =====\n")
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
                if (file != null) (1..LOG_ARCHIVES).forEach { File(file.parentFile, archiveLogName(it)).delete() }
                lastLoggedDay = ""
            } catch (_: Exception) {}
        }

        fun getFullLogsText(context: Context): String {
            return try {
                val file = resolveLogFile(context.applicationContext)
                if (file.exists() && file.length() > 0L) {
                    val prev = File(file.parentFile, LOG_PREV_FILE_NAME)
                    val older = if (prev.exists()) prev.readText() + "\n----- (continua no arquivo atual) -----\n" else ""
                    older + file.readText()
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
