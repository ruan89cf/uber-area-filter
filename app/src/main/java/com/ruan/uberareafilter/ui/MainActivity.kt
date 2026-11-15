package com.ruan.uberareafilter.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ruan.uberareafilter.databinding.ActivityMainBinding
import com.ruan.uberareafilter.engine.AreaEvaluator
import com.ruan.uberareafilter.model.FavoriteRideItem
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.model.RideHistoryItem
import com.ruan.uberareafilter.model.ZoneMode
import com.ruan.uberareafilter.service.UberAccessibilityService
import com.ruan.uberareafilter.storage.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ConfigRepository

    private var currentPolygon: List<Pair<Double, Double>> = emptyList()
    private var currentMapZones: List<MapZone> = emptyList()

    private val mapLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val streetsCount = result.data?.getIntExtra(MapAreaActivity.EXTRA_STREETS_COUNT, 0) ?: 0
            val allowedZones = result.data?.getIntExtra(MapAreaActivity.EXTRA_ALLOWED_ZONES_COUNT, 0) ?: 0
            val blockedZones = result.data?.getIntExtra(MapAreaActivity.EXTRA_BLOCKED_ZONES_COUNT, 0) ?: 0
            loadConfigIntoUi()
            validateInterZoneDistanceOrWarn()
            Toast.makeText(
                this,
                "Salvo: $allowedZones zona(s) permitida(s) 🟢, $blockedZones proibida(s) 🔴 e $streetsCount ruas!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ConfigRepository(this)
        loadConfigIntoUi()
        setupListeners()
        observeLogsAndHistory()
    }

    override fun onResume() {
        super.onResume()
        loadConfigIntoUi()
        renderRideHistory(repository.getRideHistory())
        updatePermissionStatus()
    }

    private var isUpdatingUi = false

    private fun loadConfigIntoUi() {
        isUpdatingUi = true
        val config = repository.load()
        currentPolygon = config.polygonPoints
        currentMapZones = config.mapZones

        binding.switchEnabled.isChecked = config.isEnabled
        binding.switchFloatingBtn.isChecked = config.floatingButtonEnabled
        binding.switchFavAlerts.isChecked = config.favoriteAlertsEnabled
        binding.switchAutoReject.isChecked = config.autoRejectEnabled
        binding.switchAutoAccept.isChecked = config.autoAcceptInsideArea
        binding.switchAutoContinue.isChecked = config.autoClickContinueEnabled
        binding.switchHumanDelay.isChecked = config.humanDelayEnabled

        binding.switchRequireBoth.isChecked = config.requireBothPickupAndDropoff
        binding.switchRequireSameZone.isChecked = config.allowInterZoneRides
        binding.switchYellowOutside.isChecked = config.yellowCardOutsideAreaEnabled
        binding.switchAvBrasilOdd.isChecked = config.avBrasilOddSideOnly
        binding.switchRejectRiskBadge.isChecked = config.reject99RiskAreaBadge

        binding.edtAllowed.setText(config.allowedKeywords.joinToString("\n"))
        binding.edtBlocked.setText(config.blockedKeywords.joinToString("\n"))

        binding.switchFilterTotalDist.isChecked = config.filterByTotalDistEnabled
        binding.edtMaxTotalDist.setText(config.maxTotalDistanceKm.toString())

        binding.switchFilterPickupDist.isChecked = config.filterByPickupDistEnabled
        binding.edtMaxPickupDist.setText(config.maxPickupDistanceKm.toString())

        binding.switchFilterRating.isChecked = config.filterByRatingEnabled
        binding.edtMinRating.setText(config.minRating.toString())
        binding.switchRejectNewPassenger.isChecked = config.rejectNewPassenger

        binding.switchFilterHourly.isChecked = config.filterByHourlyRateEnabled
        binding.edtMinHourly.setText(config.minHourlyRate.toString())

        binding.switchDebug.isChecked = config.debugMode

        val favCount = repository.getFavorites().size
        binding.btnOpenFavorites.text = "❤️ Passageiros e Horários Favoritos ($favCount salvos)"

        updateZoneStatusText(config.mapZones, config.allowedKeywords.size)
        isUpdatingUi = false
    }

    private fun updateZoneStatusText(zones: List<MapZone>, streetsCount: Int) {
        val allowedCount = zones.count { it.mode == ZoneMode.ALLOWED }
        val blockedCount = zones.count { it.mode == ZoneMode.BLOCKED }

        binding.txtPolygonStatus.text = if (zones.isNotEmpty()) {
            "✅ Mapa ativo: $allowedCount zona(s) permitida(s) 🟢 | $blockedCount proibida(s) 🔴 | $streetsCount ruas extraídas"
        } else if (streetsCount > 0) {
            "Lista manual ativa com $streetsCount ruas (toque acima para desenhar zonas no mapa)"
        } else {
            "Nenhuma zona demarcada ainda — toque no botão acima para desenhar Polígonos ou Círculos"
        }
    }

    private fun setupListeners() {
        binding.btnAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnOverlaySettings.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnOpenFavorites.setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }

        binding.btnOpenMap.setOnClickListener {
            saveConfigFromUi(silent = true)
            mapLauncher.launch(Intent(this, MapAreaActivity::class.java))
        }

        binding.btnSave.setOnClickListener {
            saveConfigFromUi(silent = false)
        }

        binding.btnClearLogs.setOnClickListener {
            ConfigRepository.clearLogs()
        }

        val allSwitches = listOf(
            binding.switchEnabled,
            binding.switchFloatingBtn,
            binding.switchFavAlerts,
            binding.switchAutoReject,
            binding.switchAutoAccept,
            binding.switchAutoContinue,
            binding.switchHumanDelay,
            binding.switchRequireBoth,
            binding.switchYellowOutside,
            binding.switchAvBrasilOdd,
            binding.switchRejectRiskBadge,
            binding.switchFilterTotalDist,
            binding.switchFilterPickupDist,
            binding.switchFilterRating,
            binding.switchRejectNewPassenger,
            binding.switchFilterHourly,
            binding.switchDebug
        )
        allSwitches.forEach { sw ->
            sw.setOnCheckedChangeListener { _, _ ->
                if (!isUpdatingUi) {
                    if (sw == binding.switchFilterTotalDist && binding.switchFilterTotalDist.isChecked) {
                        if (!validateInterZoneDistanceOrWarn()) return@setOnCheckedChangeListener
                    }
                    saveConfigFromUi(silent = true)
                }
            }
        }

        binding.switchRequireSameZone.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                if (isChecked && !validateInterZoneDistanceOrWarn()) {
                    return@setOnCheckedChangeListener
                }
                saveConfigFromUi(silent = true)
            }
        }
    }

    /**
     * Verifica se o KM Total Maximo ativo comporta a distancia entre as Zonas Permitidas.
     * Se as zonas forem mais distantes do que o KM Maximo ativo, bloqueia a ativacao e exibe alerta!
     */
    private fun validateInterZoneDistanceOrWarn(): Boolean {
        if (!binding.switchRequireSameZone.isChecked) return true
        if (!binding.switchFilterTotalDist.isChecked) return true

        val maxTotalKm = binding.edtMaxTotalDist.text?.toString()?.replace(",", ".")?.toDoubleOrNull() ?: 8.0
        val minZonesDistKm = AreaEvaluator.calculateMinDistanceBetweenAllowedZonesKm(currentMapZones) ?: return true

        // Considera margem minima de 0.5 km para rota viaria alem da linha reta entre as bordas das zonas
        if (maxTotalKm < minZonesDistKm + 0.5) {
            val suggestedKm = kotlin.math.ceil(minZonesDistKm + 4.0).toInt()
            val minFmt = String.format(Locale("pt", "BR"), "%.1f km", minZonesDistKm)
            val curFmt = String.format(Locale("pt", "BR"), "%.1f km", maxTotalKm)

            isUpdatingUi = true
            binding.switchRequireSameZone.isChecked = false
            isUpdatingUi = false
            saveConfigFromUi(silent = true)

            AlertDialog.Builder(this)
                .setTitle("⚠️ Não é possível ativar devido ao KM Máximo")
                .setMessage(
                    "Suas zonas de aceitação estão a cerca de $minFmt de distância entre si, " +
                        "mas o filtro 'KM Total máximo' está ativo em apenas $curFmt.\n\n" +
                        "Como as zonas são muito distantes em relação ao KM máximo ativo, nenhuma corrida entre elas seria permitida.\n\n" +
                        "Deseja aumentar o KM Total máximo para $suggestedKm km e ativar a Corrida entre Zonas (Card Roxo 🟣)?"
                )
                .setPositiveButton("Aumentar p/ $suggestedKm km e Ativar") { _, _ ->
                    isUpdatingUi = true
                    binding.edtMaxTotalDist.setText(suggestedKm.toString())
                    binding.switchRequireSameZone.isChecked = true
                    isUpdatingUi = false
                    saveConfigFromUi(silent = false)
                }
                .setNegativeButton("Manter Desativado", null)
                .show()
            return false
        }
        return true
    }

    private fun saveConfigFromUi(silent: Boolean) {
        if (!silent && binding.switchRequireSameZone.isChecked && !validateInterZoneDistanceOrWarn()) {
            return
        }

        val allowed = binding.edtAllowed.text?.toString().orEmpty()
            .split("\n", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val blocked = binding.edtBlocked.text?.toString().orEmpty()
            .split("\n", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val maxTotalKm = binding.edtMaxTotalDist.text?.toString()?.replace(",", ".")?.toDoubleOrNull() ?: 15.0
        val maxPickupKm = binding.edtMaxPickupDist.text?.toString()?.replace(",", ".")?.toDoubleOrNull() ?: 2.5
        val minRating = binding.edtMinRating.text?.toString()?.replace(",", ".")?.toDoubleOrNull() ?: 4.80
        val minHourly = binding.edtMinHourly.text?.toString()?.replace(",", ".")?.toDoubleOrNull() ?: 30.0

        val config = FilterConfig(
            isEnabled = binding.switchEnabled.isChecked,
            floatingButtonEnabled = binding.switchFloatingBtn.isChecked,
            favoriteAlertsEnabled = binding.switchFavAlerts.isChecked,
            autoRejectEnabled = binding.switchAutoReject.isChecked,
            autoAcceptInsideArea = binding.switchAutoAccept.isChecked,
            autoClickContinueEnabled = binding.switchAutoContinue.isChecked,
            humanDelayEnabled = binding.switchHumanDelay.isChecked,
            batterySaverInRide = false,
            requireBothPickupAndDropoff = binding.switchRequireBoth.isChecked,
            allowInterZoneRides = binding.switchRequireSameZone.isChecked,
            yellowCardOutsideAreaEnabled = binding.switchYellowOutside.isChecked,
            avBrasilOddSideOnly = binding.switchAvBrasilOdd.isChecked,
            reject99RiskAreaBadge = binding.switchRejectRiskBadge.isChecked,
            polygonPoints = currentPolygon,
            mapZones = currentMapZones,
            allowedKeywords = allowed,
            blockedKeywords = blocked,
            filterByTotalDistEnabled = binding.switchFilterTotalDist.isChecked,
            maxTotalDistanceKm = maxTotalKm,
            filterByPickupDistEnabled = binding.switchFilterPickupDist.isChecked,
            maxPickupDistanceKm = maxPickupKm,
            filterByRatingEnabled = binding.switchFilterRating.isChecked,
            minRating = minRating,
            rejectNewPassenger = binding.switchRejectNewPassenger.isChecked,
            filterByHourlyRateEnabled = binding.switchFilterHourly.isChecked,
            minHourlyRate = minHourly,
            debugMode = binding.switchDebug.isChecked
        )

        repository.save(config)
        updateZoneStatusText(currentMapZones, allowed.size)
        if (!silent) {
            Toast.makeText(this, "Configurações salvas!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderRideHistory(items: List<RideHistoryItem>) {
        val container = binding.historyContainer
        container.removeAllViews()

        if (items.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "Nenhuma corrida lida ainda. Assim que tocarem corridas na 99, elas aparecerão aqui com botões rápidos."
                textSize = 12f
                setTextColor(Color.GRAY)
            }
            container.addView(emptyView)
            return
        }

        items.take(8).forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val bg = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#1E1E1E"))
                    setStroke(
                        dp(1),
                        if (item.isInsideArea) Color.parseColor("#2E7D32") else Color.parseColor("#C62828")
                    )
                }
                background = bg
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
                layoutParams = lp
            }

            val statusIcon = if (item.isInsideArea) "✅" else "⚠️"
            val metrics = listOf(item.pricePerKmText, item.hourlyRateText, item.ratingText, item.priceText)
                .filter { it.isNotEmpty() }
                .joinToString(" • ")

            val header = TextView(this).apply {
                text = "$statusIcon [${item.timeFormatted}] $metrics"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }

            val route = TextView(this).apply {
                text = "🟢 Emb: ${item.pickupAddress.ifEmpty { "N/A" }}\n🏁 Dest: ${item.dropoffAddress.ifEmpty { "N/A" }}\n📋 ${item.reason}"
                setTextColor(Color.parseColor("#CCCCCC"))
                textSize = 12f
                setPadding(0, dp(4), 0, dp(6))
            }

            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val btnAllow = Button(this).apply {
                text = "+ Permitir"
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener {
                    promptPickAddressForList(item, isAllow = true)
                }
            }

            val btnBlock = Button(this).apply {
                text = "🚫 Bloquear"
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener {
                    promptPickAddressForList(item, isAllow = false)
                }
            }

            val btnFav = Button(this).apply {
                text = "❤️ Favoritar"
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    saveHistoryItemAsFavorite(item)
                }
            }

            actionsRow.addView(btnAllow)
            actionsRow.addView(btnBlock)
            actionsRow.addView(btnFav)

            card.addView(header)
            card.addView(route)
            card.addView(actionsRow)
            container.addView(card)
        }
    }

    private fun promptPickAddressForList(item: RideHistoryItem, isAllow: Boolean) {
        val options = buildList {
            if (item.pickupAddress.isNotBlank()) add(extractStreetOrPlaceName(item.pickupAddress))
            if (item.dropoffAddress.isNotBlank()) add(extractStreetOrPlaceName(item.dropoffAddress))
        }.distinct()

        if (options.isEmpty()) return

        val title = if (isAllow) "Adicionar à Área Permitida:" else "Adicionar à Lista Negra (Bloqueados):"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, which ->
                val chosen = options[which]
                if (isAllow) {
                    repository.addAllowedKeyword(chosen)
                    Toast.makeText(this, "Adicionado aos permitidos: $chosen", Toast.LENGTH_SHORT).show()
                } else {
                    repository.addBlockedKeyword(chosen)
                    Toast.makeText(this, "Bloqueado: $chosen", Toast.LENGTH_SHORT).show()
                }
                loadConfigIntoUi()
            }
            .show()
    }

    private fun extractStreetOrPlaceName(rawAddress: String): String {
        val firstPart = rawAddress.split(",").firstOrNull()?.trim().orEmpty()
        return if (firstPart.length >= 4) firstPart else rawAddress.take(35)
    }

    private fun saveHistoryItemAsFavorite(item: RideHistoryItem) {
        lifecycleScope.launch {
            val todayDate = SimpleDateFormat("EEE, dd/MM/yyyy", Locale("pt", "BR")).format(Date())
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("pt", "BR")) else it.toString() }
            val pCoords = geocodeQuick("${item.pickupAddress}, Rio de Janeiro, RJ")
            val dCoords = geocodeQuick("${item.dropoffAddress}, Rio de Janeiro, RJ")

            val fav = FavoriteRideItem(
                id = System.currentTimeMillis(),
                passengerName = "",
                rideTimeFormatted = item.timeFormatted,
                dateFormatted = todayDate,
                pickupAddress = item.pickupAddress.ifBlank { "Embarque" },
                dropoffAddress = item.dropoffAddress.ifBlank { "Destino" },
                pickupLat = pCoords?.first ?: -22.8425,
                pickupLng = pCoords?.second ?: -43.2785,
                dropoffLat = dCoords?.first ?: 0.0,
                dropoffLng = dCoords?.second ?: 0.0,
                priceText = item.priceText,
                ratingText = item.ratingText.ifBlank { "★ Favorito" }
            )
            repository.addFavorite(fav)
            loadConfigIntoUi()
            validateInterZoneDistanceOrWarn()
            Toast.makeText(
                this@MainActivity,
                "❤️ Salvo nos Favoritos (${item.timeFormatted})!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private suspend fun geocodeQuick(query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        try {
            val geocoder = Geocoder(this@MainActivity, Locale("pt", "BR"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<Pair<Double, Double>?> { cont ->
                    geocoder.getFromLocationName(query, 1) { list ->
                        val first: Address? = list.firstOrNull()
                        if (cont.isActive) {
                            cont.resume(first?.let { it.latitude to it.longitude })
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val first = geocoder.getFromLocationName(query, 1)?.firstOrNull()
                first?.let { it.latitude to it.longitude }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun updatePermissionStatus() {
        val accessibilityOk = isAccessibilityEnabled()
        val overlayOk = Settings.canDrawOverlays(this)
        val accText = if (accessibilityOk) "OK" else "DESATIVADA"
        val ovText = if (overlayOk) "OK" else "DESATIVADA"
        binding.txtPermissionsStatus.text = "Acessibilidade: $accText  |  Sobrepor Tela: $ovText"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == UberAccessibilityService::class.java.name
        }
    }

    private fun observeLogsAndHistory() {
        lifecycleScope.launch {
            ConfigRepository.debugLogs.collect { lines ->
                binding.txtLogs.text = if (lines.isEmpty()) {
                    "Sem registros ainda."
                } else {
                    lines.joinToString("\n")
                }
            }
        }
        lifecycleScope.launch {
            ConfigRepository.historyFlow.collect { list ->
                if (list.isNotEmpty()) {
                    renderRideHistory(list)
                }
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
