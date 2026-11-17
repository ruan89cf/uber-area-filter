package com.ruan.uberareafilter.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ruan.uberareafilter.adb.AdbPairingReceiver
import com.ruan.uberareafilter.adb.StandaloneAdbBridge
import com.ruan.uberareafilter.databinding.ActivityMainBinding
import com.ruan.uberareafilter.engine.AreaEvaluator
import com.ruan.uberareafilter.engine.FuelCalculator
import com.ruan.uberareafilter.overlay.OverlayManager
import com.ruan.uberareafilter.overlay.RideCardModel
import com.ruan.uberareafilter.model.DualConflictPolicy
import com.ruan.uberareafilter.model.FavoriteRideItem
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.model.RideHistoryItem
import com.ruan.uberareafilter.model.ZoneMode
import com.ruan.uberareafilter.service.UberAccessibilityService
import com.ruan.uberareafilter.shizuku.ShizukuTapBridge
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
    private var currentFuelType: String = FilterConfig.FUEL_GASOLINA

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
        repository = ConfigRepository(this)
        applyThemeMode(repository.load().darkThemeEnabled)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ConfigRepository.ensureDiskLogsLoaded(this)
        ShizukuTapBridge.init(this) {
            runOnUiThread { updatePermissionStatus() }
        }
        loadConfigIntoUi()
        setupListeners()
        setupVisualCustomization()
        observeLogsAndHistory()

        if (WhatsNewActivity.shouldShowWhatsNew(this)) {
            startActivity(Intent(this, WhatsNewActivity::class.java))
        }
    }

    override fun onPause() {
        super.onPause()
        previewOverlay?.hideBanner()
        StandaloneAdbBridge.stopConnectAutoMonitor()
    }

    private var previewOverlay: OverlayManager? = null

    private fun applyThemeMode(dark: Boolean) {
        val mode = if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    override fun onResume() {
        super.onResume()
        loadConfigIntoUi()
        renderRideHistory(repository.getRideHistory())
        updatePermissionStatus()
        maybeStartAdbAutoConnectOnResume()
    }

    private fun maybeStartAdbAutoConnectOnResume() {
        val cfg = repository.load()
        if (!cfg.uberModuleEnabled || !StandaloneAdbBridge.isPaired(this)) return
        lifecycleScope.launch(Dispatchers.IO) {
            if (!StandaloneAdbBridge.isDaemonAlive(this@MainActivity)) {
                StandaloneAdbBridge.startConnectAutoMonitor(this@MainActivity) { res ->
                    runOnUiThread {
                        updatePermissionStatus()
                        if (res.outcome == StandaloneAdbBridge.ConnectOutcome.CONNECTED) {
                            Toast.makeText(this@MainActivity, res.detail, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
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
        binding.switchAutoAcceptInterZone.isChecked = config.autoAcceptInterZone
        binding.switchAutoAcceptInterZone.isEnabled = config.allowInterZoneRides
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
        binding.switchUberCustomHourly.isChecked = config.uberCustomHourlyEnabled
        binding.edtUberMinHourly.setText(config.uberMinHourlyRate.toString())
        binding.switchUseNetHourlyRate.isChecked = config.useNetHourlyRate
        binding.switchRoundDownNetHourly.isChecked = config.roundDownNetHourlyRate
        binding.switchRoundDownNetHourly.isEnabled = config.useNetHourlyRate || config.fuelCostEnabled

        currentFuelType = config.fuelType
        binding.switchFuelEnabled.isChecked = config.fuelCostEnabled
        binding.edtFuelPrice.setText(String.format(Locale.US, "%.2f", config.fuelPricePerUnit))
        binding.edtKmPerUnit.setText(String.format(Locale.US, "%.1f", config.kmPerUnit))
        updateFuelUiLabels(config)

        binding.switchDebug.isChecked = config.debugMode
        binding.switchXrayDiagnostic.isChecked = config.xrayDiagnosticEnabled
        binding.switchXrayDiagnostic.isEnabled = config.debugMode
        binding.switchSaveRideScreenshots.isChecked = config.saveRideScreenshotsEnabled
        updateRideShotsStatsUi()

        binding.switchModule99.isChecked = config.nineNineModuleEnabled
        binding.switchModuleUber.isChecked = config.uberModuleEnabled
        binding.switchDualConflictGuard.isChecked = config.dualConflictGuardEnabled
        if (config.dualConflictPolicy == DualConflictPolicy.AUTO_REJECT_OTHER_APP) {
            binding.rbDualAutoRejectOther.isChecked = true
        } else {
            binding.rbDualBlockAutoAccept.isChecked = true
        }
        refreshDualConflictUiState()
        binding.switchRejectUberStops.isChecked = config.rejectUberMultiStopRides
        binding.switchShowRideBanner.isChecked = config.showRideInfoBannerEnabled
        binding.edtBannerDurationSec.setText(config.bannerAutoHideSeconds.toString())
        binding.switchDarkTheme.isChecked = config.darkThemeEnabled
        refreshVisualControls(config)
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
        binding.btnWhatsNew.setOnClickListener {
            val intent = Intent(this, WhatsNewActivity::class.java).apply {
                putExtra(WhatsNewActivity.EXTRA_FROM_MAIN, true)
            }
            startActivity(intent)
        }

        binding.headerStreetLists.setOnClickListener {
            val isVisible = binding.layoutStreetListsContent.visibility == View.VISIBLE
            binding.layoutStreetListsContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.txtStreetListsToggleArrow.text = if (isVisible) "▾" else "▴"
        }

        binding.headerVisualCustomization.setOnClickListener {
            val isVisible = binding.layoutVisualCustomizationContent.visibility == View.VISIBLE
            binding.layoutVisualCustomizationContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.txtVisualToggleArrow.text = if (isVisible) "▾" else "▴"
        }

        binding.headerLogsDiagnostic.setOnClickListener {
            val isVisible = binding.layoutLogsDiagnosticContent.visibility == View.VISIBLE
            binding.layoutLogsDiagnosticContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.txtLogsDiagnosticToggleArrow.text = if (isVisible) "▾" else "▴"
        }

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

        binding.btnStandaloneAdb.setOnClickListener {
            handleStandaloneAdbClick()
        }

        binding.btnShizukuSettings.setOnClickListener {
            when {
                !ShizukuTapBridge.isInstalled(this) -> {
                    val marketIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=${ShizukuTapBridge.SHIZUKU_PACKAGE}")
                    )
                    runCatching { startActivity(marketIntent) }.onFailure {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=${ShizukuTapBridge.SHIZUKU_PACKAGE}")
                            )
                        )
                    }
                }
                !ShizukuTapBridge.isBinderAlive() -> {
                    val launchIntent = packageManager.getLaunchIntentForPackage(ShizukuTapBridge.SHIZUKU_PACKAGE)
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                        Toast.makeText(
                            this,
                            "Toque em 'Iniciar' no Shizuku (Depuração por Wi-Fi) e volte aqui.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                !ShizukuTapBridge.hasPermission() -> {
                    ShizukuTapBridge.requestPermission()
                }
                else -> {
                    ShizukuTapBridge.ensureBound(this)
                    updatePermissionStatus()
                    Toast.makeText(this, "Shizuku ativo e autorizado para a Uber!", Toast.LENGTH_SHORT).show()
                }
            }
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

        binding.btnClearRideScreenshots.setOnClickListener {
            val (count, _) = ConfigRepository.getRideScreenshotsStats(this)
            AlertDialog.Builder(this)
                .setTitle("🗑️ Limpar prints salvos?")
                .setMessage(
                    if (count > 0) {
                        "Apaga as $count imagem(ns) salvas no armazenamento interno privado do app."
                    } else {
                        "Não há prints novos na pasta privada. Deseja limpar os vínculos do histórico?"
                    }
                )
                .setPositiveButton("Limpar") { _, _ ->
                    val removed = repository.clearAllRideScreenshots()
                    renderRideHistory(repository.getRideHistory())
                    updateRideShotsStatsUi()
                    Toast.makeText(
                        this,
                        if (removed > 0) "$removed imagem(ns) removida(s)." else "Prints limpos.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        binding.btnClearLogs.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("🗑️ Limpar logs?")
                .setMessage("Apaga os logs da tela e o arquivo salvo no celular.")
                .setPositiveButton("Limpar") { _, _ ->
                    ConfigRepository.clearLogs(this)
                    Toast.makeText(this, "Logs apagados.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        binding.btnExportLogs.setOnClickListener {
            exportAndShareLogs()
        }

        val allSwitches = listOf(
            binding.switchEnabled,
            binding.switchFloatingBtn,
            binding.switchFavAlerts,
            binding.switchAutoReject,
            binding.switchAutoAccept,
            binding.switchAutoContinue,
            binding.switchHumanDelay,
            binding.switchModule99,
            binding.switchModuleUber,
            binding.switchDualConflictGuard,
            binding.switchRejectUberStops,
            binding.switchUberCustomHourly,
            binding.switchShowRideBanner,
            binding.switchRequireBoth,
            binding.switchAutoAcceptInterZone,
            binding.switchYellowOutside,
            binding.switchAvBrasilOdd,
            binding.switchRejectRiskBadge,
            binding.switchFilterTotalDist,
            binding.switchFilterPickupDist,
            binding.switchFilterRating,
            binding.switchRejectNewPassenger,
            binding.switchFilterHourly,
            binding.switchUseNetHourlyRate,
            binding.switchRoundDownNetHourly,
            binding.switchFuelEnabled,
            binding.switchSaveRideScreenshots,
            binding.switchDebug,
            binding.switchXrayDiagnostic
        )
        allSwitches.forEach { sw ->
            sw.setOnCheckedChangeListener { _, _ ->
                if (!isUpdatingUi) {
                    if (sw == binding.switchFilterTotalDist && binding.switchFilterTotalDist.isChecked) {
                        if (!validateInterZoneDistanceOrWarn()) return@setOnCheckedChangeListener
                    }
                    binding.switchRoundDownNetHourly.isEnabled =
                        binding.switchUseNetHourlyRate.isChecked || binding.switchFuelEnabled.isChecked
                    if (sw == binding.switchModule99 ||
                        sw == binding.switchModuleUber ||
                        sw == binding.switchDualConflictGuard
                    ) {
                        refreshDualConflictUiState()
                    }
                    if (sw == binding.switchDebug) {
                        binding.switchXrayDiagnostic.isEnabled = binding.switchDebug.isChecked
                    }
                    saveConfigFromUi(silent = true)
                    if (sw == binding.switchFuelEnabled ||
                        sw == binding.switchUseNetHourlyRate ||
                        sw == binding.switchRoundDownNetHourly
                    ) {
                        updateFuelUiLabels(repository.load())
                    }
                }
            }
        }

        binding.btnFuelType.setOnClickListener {
            val types = FilterConfig.FUEL_TYPES.toTypedArray()
            val curIdx = types.indexOf(currentFuelType).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("⛽ Tipo de Combustível")
                .setSingleChoiceItems(types, curIdx) { dialog, which ->
                    currentFuelType = types[which]
                    saveConfigFromUi(silent = true)
                    updateFuelUiLabels(repository.load())
                    dialog.dismiss()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        binding.switchRequireSameZone.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                binding.switchAutoAcceptInterZone.isEnabled = isChecked
                if (isChecked && !validateInterZoneDistanceOrWarn()) {
                    binding.switchAutoAcceptInterZone.isEnabled = false
                    return@setOnCheckedChangeListener
                }
                saveConfigFromUi(silent = true)
            }
        }

        binding.rgDualConflictPolicy.setOnCheckedChangeListener { _, _ ->
            if (!isUpdatingUi) {
                saveConfigFromUi(silent = true)
            }
        }
    }

    private fun refreshDualConflictUiState() {
        val dualActive = binding.switchModule99.isChecked && binding.switchModuleUber.isChecked
        binding.switchDualConflictGuard.isEnabled = dualActive
        val policyEnabled = dualActive && binding.switchDualConflictGuard.isChecked
        binding.rbDualBlockAutoAccept.isEnabled = policyEnabled
        binding.rbDualAutoRejectOther.isEnabled = policyEnabled
        binding.txtDualConflictHint.alpha = if (dualActive) 1.0f else 0.5f
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
        val uberMinHourly = binding.edtUberMinHourly.text?.toString()?.replace(",", ".")?.toDoubleOrNull() ?: minHourly
        val fuelPrice = binding.edtFuelPrice.text?.toString()?.replace(",", ".")?.toDoubleOrNull() ?: 6.29
        val kmPerUnit = binding.edtKmPerUnit.text?.toString()?.replace(",", ".")?.toDoubleOrNull() ?: 35.0
        val bannerDurationSec = (binding.edtBannerDurationSec.text?.toString()?.toIntOrNull() ?: 5).coerceIn(1, 30)

        val config = repository.load().copy(
            isEnabled = binding.switchEnabled.isChecked,
            floatingButtonEnabled = binding.switchFloatingBtn.isChecked,
            favoriteAlertsEnabled = binding.switchFavAlerts.isChecked,
            autoRejectEnabled = binding.switchAutoReject.isChecked,
            autoAcceptInsideArea = binding.switchAutoAccept.isChecked,
            autoClickContinueEnabled = binding.switchAutoContinue.isChecked,
            humanDelayEnabled = binding.switchHumanDelay.isChecked,
            nineNineModuleEnabled = binding.switchModule99.isChecked,
            uberModuleEnabled = binding.switchModuleUber.isChecked,
            dualConflictGuardEnabled = binding.switchDualConflictGuard.isChecked,
            dualConflictPolicy = if (binding.rbDualAutoRejectOther.isChecked) {
                DualConflictPolicy.AUTO_REJECT_OTHER_APP
            } else {
                DualConflictPolicy.BLOCK_AUTO_ACCEPT_ONLY
            },
            rejectUberMultiStopRides = binding.switchRejectUberStops.isChecked,
            batterySaverInRide = false,
            requireBothPickupAndDropoff = binding.switchRequireBoth.isChecked,
            allowInterZoneRides = binding.switchRequireSameZone.isChecked,
            autoAcceptInterZone = binding.switchAutoAcceptInterZone.isChecked,
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
            uberCustomHourlyEnabled = binding.switchUberCustomHourly.isChecked,
            uberMinHourlyRate = uberMinHourly,
            useNetHourlyRate = binding.switchUseNetHourlyRate.isChecked,
            roundDownNetHourlyRate = binding.switchRoundDownNetHourly.isChecked,
            showRideInfoBannerEnabled = binding.switchShowRideBanner.isChecked,
            bannerAutoHideSeconds = bannerDurationSec,
            fuelCostEnabled = binding.switchFuelEnabled.isChecked,
            fuelType = currentFuelType,
            fuelPricePerUnit = fuelPrice,
            kmPerUnit = kmPerUnit,
            debugMode = binding.switchDebug.isChecked,
            xrayDiagnosticEnabled = binding.switchXrayDiagnostic.isChecked,
            saveRideScreenshotsEnabled = binding.switchSaveRideScreenshots.isChecked
        )

        repository.save(config)
        updateFuelUiLabels(config)
        updateZoneStatusText(currentMapZones, allowed.size)
        if (!silent) {
            Toast.makeText(this, "Configurações salvas!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRideShotsStatsUi() {
        val (count, totalBytes) = ConfigRepository.getRideScreenshotsStats(this)
        val sizeText = when {
            totalBytes >= 1024L * 1024L -> String.format(Locale("pt", "BR"), "%.1f MB", totalBytes / (1024f * 1024f))
            totalBytes >= 1024L -> "${totalBytes / 1024L} KB"
            else -> "$totalBytes B"
        }
        binding.txtRideShotsInfo.text = if (count > 0) {
            "📸 $count print(s) salvo(s) ($sizeText) • privado"
        } else {
            "0 prints salvos (armazenamento privado)"
        }
        binding.btnClearRideScreenshots.isEnabled = count > 0 ||
            repository.getRideHistory().any { it.screenshotPath.isNotBlank() }
    }

    private fun renderRideHistory(items: List<RideHistoryItem>) {
        updateRideShotsStatsUi()
        val container = binding.historyContainer
        container.removeAllViews()

        if (items.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "Nenhuma corrida lida ainda. Assim que tocarem corridas na 99 ou Uber, elas aparecerão aqui com botões rápidos."
                textSize = 12f
                setTextColor(Color.GRAY)
            }
            container.addView(emptyView)
            return
        }

        items.take(15).forEach { item ->
            val shotFile = item.screenshotPath
                .takeIf { it.isNotBlank() }
                ?.let { File(it) }
                ?.takeIf { it.exists() }

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
                isClickable = true
                setOnClickListener {
                    showRideScreenshotDialog(item, shotFile)
                }
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
                setPadding(0, dp(4), 0, dp(4))
            }

            val shotBadge = TextView(this).apply {
                if (shotFile != null) {
                    text = "📸 Toque no card para ver o print da corrida"
                    setTextColor(Color.parseColor("#93C5FD"))
                } else {
                    text = "📷 Sem print salvo para esta corrida"
                    setTextColor(Color.parseColor("#757575"))
                }
                textSize = 11.5f
                setPadding(0, 0, 0, dp(6))
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
            card.addView(shotBadge)
            card.addView(actionsRow)
            container.addView(card)
        }
    }

    private fun showRideScreenshotDialog(item: RideHistoryItem, shotFile: File?) {
        if (shotFile == null || !shotFile.exists()) {
            Toast.makeText(
                this,
                "Sem print salvo para esta corrida (verifique se a captura está ativada).",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val bitmap = BitmapFactory.decodeFile(shotFile.absolutePath)
        if (bitmap == null) {
            Toast.makeText(this, "Não foi possível abrir o arquivo da imagem.", Toast.LENGTH_SHORT).show()
            return
        }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val infoView = TextView(this).apply {
            val metrics = listOf(item.priceText, item.pricePerKmText, item.hourlyRateText)
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            text = buildString {
                append("⏰ ${item.timeFormatted}")
                if (metrics.isNotEmpty()) append(" • $metrics")
                append("\n🟢 ${item.pickupAddress.ifBlank { "N/A" }}")
                append("\n🏁 ${item.dropoffAddress.ifBlank { "N/A" }}")
                append("\n📋 ${item.reason}")
            }
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }

        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        wrapper.addView(infoView)
        wrapper.addView(imageView)

        val scroll = ScrollView(this).apply {
            addView(wrapper)
        }

        AlertDialog.Builder(this)
            .setTitle("📸 Print ao aparecer a corrida")
            .setView(scroll)
            .setPositiveButton("Fechar", null)
            .setOnDismissListener {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            .show()
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

        val shizukuInstalled = ShizukuTapBridge.isInstalled(this)
        binding.btnShizukuSettings.visibility = if (shizukuInstalled) View.VISIBLE else View.GONE
        if (shizukuInstalled) {
            binding.btnShizukuSettings.text = when {
                !ShizukuTapBridge.isBinderAlive() -> "⚡ Shizuku (opcional): Abrir e Iniciar"
                !ShizukuTapBridge.hasPermission() -> "⚡ Shizuku (opcional): Autorizar"
                else -> "⚡ Shizuku Ativo ✅"
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val daemonAlive = StandaloneAdbBridge.isDaemonAlive(this@MainActivity)
            val paired = StandaloneAdbBridge.isPaired(this@MainActivity)
            val adbStatus = when {
                daemonAlive -> "ATIVO ✅ (100% independente no 4G/5G)"
                paired -> "Pareado (toque p/ iniciar no Wi-Fi)"
                else -> "Não pareado (toque p/ ativar sem Shizuku)"
            }
            withContext(Dispatchers.Main) {
                binding.txtPermissionsStatus.text =
                    "Acessibilidade: $accText  |  Sobrepor Tela: $ovText\nUber Independente: $adbStatus"
                binding.btnStandaloneAdb.text = when {
                    daemonAlive -> "⚡ Uber Independente ATIVO ✅ (4G/5G)"
                    paired -> "⚡ Iniciar Uber Independente (já pareado)"
                    else -> "⚡ Ativar Uber Independente (Sem Shizuku)"
                }
            }
        }
    }

    private fun handleStandaloneAdbClick() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = StandaloneAdbBridge.getSetupEnvironmentStatus(this@MainActivity)
            if (status.isDaemonAlive) {
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("⚡ Uber Independente ATIVO")
                        .setMessage(
                            "O serviço interno já está rodando como shell.\n\n" +
                                "• Pode desligar o Wi-Fi agora e rodar normalmente no 4G/5G.\n" +
                                "• Se reiniciar o celular no futuro, não precisa mais digitar código: basta ligar o Wi-Fi e 'Depuração por Wi-Fi' (ou o atalho rápido 'Depuração sem fio' na barra do Android) que inicia sozinho."
                        )
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Desligar") { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                StandaloneAdbBridge.stopDaemon(this@MainActivity)
                                withContext(Dispatchers.Main) { updatePermissionStatus() }
                            }
                        }
                        .show()
                }
                return@launch
            }

            if (status.isPaired && status.wifiConnected) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Já pareado! Conectando sem código...", Toast.LENGTH_SHORT).show()
                }
                val result = StandaloneAdbBridge.connectAndStartDaemon(this@MainActivity)
                val updatedStatus = StandaloneAdbBridge.getSetupEnvironmentStatus(this@MainActivity)
                withContext(Dispatchers.Main) {
                    updatePermissionStatus()
                    if (result.outcome == StandaloneAdbBridge.ConnectOutcome.CONNECTED) {
                        Toast.makeText(this@MainActivity, result.detail, Toast.LENGTH_LONG).show()
                    } else {
                        showStandaloneAdbDialog(result.detail, updatedStatus)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    val hint = when {
                        !status.wifiConnected -> "Conecte em qualquer Wi-Fi para ligar o motor (depois pode desligar o Wi-Fi)."
                        !status.isPaired -> "Falta apenas o pareamento único da 1ª vez."
                        else -> "Ligue 'Depuração por Wi-Fi' para iniciar automaticamente (sem código)."
                    }
                    showStandaloneAdbDialog(hint, status)
                }
            }
        }
    }

    private fun showStandaloneAdbDialog(
        statusDetail: String,
        envStatus: StandaloneAdbBridge.SetupEnvironmentStatus = StandaloneAdbBridge.SetupEnvironmentStatus(
            wifiConnected = StandaloneAdbBridge.isWifiConnected(this),
            devOptionsEnabled = StandaloneAdbBridge.isDeveloperOptionsEnabled(this),
            wirelessDebugPort = null,
            isPaired = StandaloneAdbBridge.isPaired(this),
            isDaemonAlive = false
        )
    ) {
        val wifiLine = if (envStatus.wifiConnected) "✅ 1. Wi-Fi conectado" else "❌ 1. Wi-Fi desligado (conecte num Wi-Fi agora)"
        val devLine = if (envStatus.devOptionsEnabled) "✅ 2. Opções do Desenvolvedor ativas" else "⚠️ 2. Ative 'Opções do Desenvolvedor' (toque 7x em 'Número da versão' em Sobre o telefone)"
        val wirelessLine = if (envStatus.wirelessDebugPort != null) {
            "✅ 3. Depuração por Wi-Fi detectada (porta ${envStatus.wirelessDebugPort})"
        } else {
            "⏳ 3. Ligue a chave 'Depuração por Wi-Fi'"
        }
        val pairLine = if (envStatus.isPaired) {
            "✅ 4. Pareamento único concluído (NÃO precisa mais de código!)"
        } else {
            "🔑 4. Parear código de 6 dígitos (só na 1ª vez)"
        }

        val checklist = "$wifiLine\n$devLine\n$wirelessLine\n$pairLine"

        if (envStatus.isPaired) {
            StandaloneAdbBridge.startConnectAutoMonitor(this) { res ->
                runOnUiThread {
                    updatePermissionStatus()
                    if (res.outcome == StandaloneAdbBridge.ConnectOutcome.CONNECTED) {
                        Toast.makeText(this, res.detail, Toast.LENGTH_LONG).show()
                    }
                }
            }
            AlertDialog.Builder(this)
                .setTitle("⚡ Iniciar Uber Independente (Sem Código)")
                .setMessage(
                    "$checklist\n\n" +
                        "Seu celular já está pareado!\n" +
                        "Não precisa digitar código nenhum: toque no botão abaixo, ative 'Depuração por Wi-Fi' (ou use o atalho 'Depuração sem fio' na barra rápida do Android) e o app inicia sozinho em segundos.\n\n" +
                        "Detalhe: $statusDetail"
                )
                .setPositiveButton(if (envStatus.devOptionsEnabled) "📶 Abrir Opções Desenv." else "📱 Abrir Sobre o telefone") { _, _ ->
                    if (envStatus.devOptionsEnabled) openDeveloperOptions() else openAboutPhoneSettings()
                }
                .setNeutralButton("▶ Conectar agora") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val res = StandaloneAdbBridge.connectAndStartDaemon(this@MainActivity)
                        withContext(Dispatchers.Main) {
                            updatePermissionStatus()
                            Toast.makeText(this@MainActivity, res.detail, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("🔑 Re-parear") { _, _ ->
                    StandaloneAdbBridge.clearPairing(this)
                    showStandaloneAdbDialog("Pareamento reiniciado. Siga os passos da 1ª vez.")
                }
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("⚡ Ativar Uber Independente (1ª vez)")
            .setMessage(
                "$checklist\n\n" +
                    "Como funciona (bem simples):\n" +
                    "• Você só digita os 6 dígitos ESTA ÚNICA VEZ.\n" +
                    "• Nas próximas vezes (mesmo se reiniciar), nunca mais pede código: basta ligar Wi-Fi + 'Depuração por Wi-Fi' que inicia sozinho!\n\n" +
                    "Passo atual: tocaremos em 'Parear 1ª vez', enviaremos uma notificação para digitar os 6 dígitos sem sair da tela de Depuração por Wi-Fi."
            )
            .setPositiveButton(if (envStatus.devOptionsEnabled) "🔑 Parear (1ª vez)" else "📱 Ativar Opções Desenv.") { _, _ ->
                if (!envStatus.devOptionsEnabled) {
                    Toast.makeText(
                        this,
                        "Toque 7 vezes em 'Número da versão' / 'Número de compilação' para liberar o modo desenvolvedor.",
                        Toast.LENGTH_LONG
                    ).show()
                    openAboutPhoneSettings()
                    return@setPositiveButton
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9902)
                    Toast.makeText(this, "Permita notificações e toque em Parear novamente.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                StandaloneAdbBridge.startPairingDiscovery(this)
                AdbPairingReceiver.showPairingPromptNotification(this)
                openDeveloperOptions()
            }
            .setNeutralButton("⌨️ Digitar código") { _, _ ->
                showManualPairingCodeDialog()
            }
            .setNegativeButton("▶ Já pareei") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val res = StandaloneAdbBridge.connectAndStartDaemon(this@MainActivity)
                    withContext(Dispatchers.Main) {
                        updatePermissionStatus()
                        Toast.makeText(this@MainActivity, res.detail, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun showManualPairingCodeDialog() {
        StandaloneAdbBridge.startPairingDiscovery(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }
        val edtCode = EditText(this).apply {
            hint = "Código de 6 dígitos (ex: 482910)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val edtPort = EditText(this).apply {
            hint = "Porta (opcional — detectada automático se vazio)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        container.addView(edtCode)
        container.addView(edtPort)

        AlertDialog.Builder(this)
            .setTitle("Parear com Código Wi-Fi")
            .setView(container)
            .setPositiveButton("Parear e Iniciar") { _, _ ->
                val code = edtCode.text.toString().trim()
                val port = edtPort.text.toString().trim().toIntOrNull()
                lifecycleScope.launch(Dispatchers.IO) {
                    val res = StandaloneAdbBridge.pairAndStart(this@MainActivity, code, port)
                    withContext(Dispatchers.Main) {
                        updatePermissionStatus()
                        Toast.makeText(this@MainActivity, res.detail, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openDeveloperOptions() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun openAboutPhoneSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        }.onFailure {
            openDeveloperOptions()
        }
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

    // ============================================================
    // 4. Personalizacao Visual (Tema, Tamanho e Cores dos Cards)
    // ============================================================

    private enum class CardColorSlot(val label: String, val defaultHex: String) {
        INSIDE("✅ Card Dentro da Área (Aceita)", "#0A2E1D"),
        OUTSIDE("⚠️ Card Fora dos Critérios (Recusa)", "#330A0A"),
        INTER_ZONE("🟣 Card Corrida Entre Zonas", "#271252"),
        YELLOW("🟡 Card Retorno à Área (≤ 1 km)", "#3B2806")
    }

    private val colorPresets = listOf(
        "Verde Escuro" to "#0A2E1D",
        "Verde Vivo" to "#1B5E20",
        "Vermelho Escuro" to "#330A0A",
        "Vermelho Vivo" to "#B71C1C",
        "Roxo" to "#271252",
        "Roxo Vivo" to "#6A1B9A",
        "Amarelo/Âmbar" to "#3B2806",
        "Laranja" to "#E65100",
        "Azul Escuro" to "#0D2A4A",
        "Azul Vivo" to "#1565C0",
        "Rosa" to "#880E4F",
        "Ciano" to "#006064",
        "Cinza Grafite" to "#263238",
        "Preto" to "#111111"
    )

    private fun setupVisualCustomization() {
        binding.switchDarkTheme.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            repository.save(repository.load().copy(darkThemeEnabled = isChecked))
            applyThemeMode(isChecked)
        }

        binding.btnSizeCompact.setOnClickListener { saveBannerScale(0) }
        binding.btnSizeNormal.setOnClickListener { saveBannerScale(1) }
        binding.btnSizeLarge.setOnClickListener { saveBannerScale(2) }

        binding.btnColorGreen.setOnClickListener { showColorPicker(CardColorSlot.INSIDE) }
        binding.btnColorRed.setOnClickListener { showColorPicker(CardColorSlot.OUTSIDE) }
        binding.btnColorPurple.setOnClickListener { showColorPicker(CardColorSlot.INTER_ZONE) }
        binding.btnColorYellow.setOnClickListener { showColorPicker(CardColorSlot.YELLOW) }

        binding.btnPreviewBanner.setOnClickListener { showPreviewSequence() }

        binding.btnResetColors.setOnClickListener {
            val updated = repository.load().copy(
                colorInsideHex = CardColorSlot.INSIDE.defaultHex,
                colorOutsideHex = CardColorSlot.OUTSIDE.defaultHex,
                colorInterZoneHex = CardColorSlot.INTER_ZONE.defaultHex,
                colorYellowReturnHex = CardColorSlot.YELLOW.defaultHex,
                bannerScale = 1
            )
            repository.save(updated)
            refreshVisualControls(updated)
            Toast.makeText(this, "Cores e tamanho restaurados ao padrão.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBannerScale(scale: Int) {
        val updated = repository.load().copy(bannerScale = scale)
        repository.save(updated)
        refreshVisualControls(updated)
        showPreview(com.ruan.uberareafilter.engine.Decision.INSIDE_AREA)
    }

    private fun refreshVisualControls(config: FilterConfig) {
        val sizeButtons = listOf(binding.btnSizeCompact, binding.btnSizeNormal, binding.btnSizeLarge)
        sizeButtons.forEachIndexed { index, btn ->
            val selected = index == config.bannerScale
            btn.strokeWidth = if (selected) dp(3) else dp(1)
            btn.alpha = if (selected) 1f else 0.6f
            val base = btn.text.toString().removePrefix("✓ ")
            btn.text = if (selected) "✓ $base" else base
        }

        tintColorButton(binding.btnColorGreen, config.colorInsideHex, CardColorSlot.INSIDE.defaultHex)
        tintColorButton(binding.btnColorRed, config.colorOutsideHex, CardColorSlot.OUTSIDE.defaultHex)
        tintColorButton(binding.btnColorPurple, config.colorInterZoneHex, CardColorSlot.INTER_ZONE.defaultHex)
        tintColorButton(binding.btnColorYellow, config.colorYellowReturnHex, CardColorSlot.YELLOW.defaultHex)
    }

    private fun tintColorButton(btn: com.google.android.material.button.MaterialButton, hex: String, fallback: String) {
        val color = safeColor(hex, fallback)
        btn.backgroundTintList = ColorStateList.valueOf(color)
        btn.setTextColor(Color.WHITE)
    }

    private fun safeColor(hex: String?, fallback: String): Int {
        return try {
            Color.parseColor(normalizeHex(hex ?: fallback) ?: fallback)
        } catch (_: Exception) {
            Color.parseColor(fallback)
        }
    }

    private fun normalizeHex(raw: String): String? {
        var s = raw.trim().uppercase(Locale.US)
        if (!s.startsWith("#")) s = "#$s"
        val ok = Regex("^#([0-9A-F]{6}|[0-9A-F]{8})$").matches(s)
        return if (ok) s else null
    }

    private fun currentHexFor(slot: CardColorSlot, config: FilterConfig): String = when (slot) {
        CardColorSlot.INSIDE -> config.colorInsideHex
        CardColorSlot.OUTSIDE -> config.colorOutsideHex
        CardColorSlot.INTER_ZONE -> config.colorInterZoneHex
        CardColorSlot.YELLOW -> config.colorYellowReturnHex
    }

    private fun showColorPicker(slot: CardColorSlot) {
        val config = repository.load()
        val current = currentHexFor(slot, config)

        val grid = android.widget.GridLayout(this).apply {
            columnCount = 4
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        var dialog: AlertDialog? = null
        colorPresets.forEach { (name, hex) ->
            val swatch = TextView(this).apply {
                text = if (hex.equals(current, ignoreCase = true)) "✓" else ""
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                contentDescription = name
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor(hex))
                    setStroke(dp(2), Color.parseColor("#66FFFFFF"))
                }
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = dp(56)
                    height = dp(48)
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                }
                setOnClickListener {
                    applySlotColor(slot, hex)
                    dialog?.dismiss()
                }
            }
            grid.addView(swatch)
        }

        val hexInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "HEX personalizado (ex: #1565C0)"
            setText(current)
            setSingleLine(true)
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(grid)
            addView(hexInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(20), dp(4), dp(20), 0) })
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(slot.label)
            .setView(android.widget.ScrollView(this).apply { addView(wrapper) })
            .setPositiveButton("Usar HEX") { _, _ ->
                val hex = normalizeHex(hexInput.text?.toString().orEmpty())
                if (hex == null) {
                    Toast.makeText(this, "HEX inválido. Use o formato #RRGGBB.", Toast.LENGTH_LONG).show()
                } else {
                    applySlotColor(slot, hex)
                }
            }
            .setNeutralButton("Padrão") { _, _ -> applySlotColor(slot, slot.defaultHex) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applySlotColor(slot: CardColorSlot, hex: String) {
        val base = repository.load()
        val updated = when (slot) {
            CardColorSlot.INSIDE -> base.copy(colorInsideHex = hex)
            CardColorSlot.OUTSIDE -> base.copy(colorOutsideHex = hex)
            CardColorSlot.INTER_ZONE -> base.copy(colorInterZoneHex = hex)
            CardColorSlot.YELLOW -> base.copy(colorYellowReturnHex = hex)
        }
        repository.save(updated)
        refreshVisualControls(updated)
        showPreview(
            when (slot) {
                CardColorSlot.INSIDE -> com.ruan.uberareafilter.engine.Decision.INSIDE_AREA
                CardColorSlot.OUTSIDE -> com.ruan.uberareafilter.engine.Decision.OUTSIDE_AREA
                CardColorSlot.INTER_ZONE -> com.ruan.uberareafilter.engine.Decision.INTER_ZONE
                CardColorSlot.YELLOW -> com.ruan.uberareafilter.engine.Decision.RETURN_TO_AREA
            }
        )
    }

    private fun updateFuelUiLabels(config: FilterConfig) {
        val unit = FilterConfig.fuelUnit(config.fuelType)
        binding.btnFuelType.text = "${config.fuelType} ▾"
        binding.tilFuelPrice.hint = "Preço R$/$unit"
        binding.tilKmPerUnit.hint = "Faz km/$unit"

        val est = FuelCalculator.estimate(6.7, 18.90, 42.0, config, requireFuelDisplayEnabled = false)
        binding.txtFuelSummaryHint.text = if (est != null && (config.fuelCostEnabled || config.useNetHourlyRate)) {
            val baseStr = "Exemplo 6,7 km (R$ 18,90 • R$ 42/h): ${est.costText} • ${est.profitText}"
            val netStr = est.netHourlyText?.let { " • $it" }.orEmpty()
            val roundNote = if (config.roundDownNetHourlyRate) " (arred. p/ baixo)" else ""
            "$baseStr$netStr$roundNote"
        } else {
            "Cálculo de combustível desativado."
        }
    }

    private fun showPreview(decision: com.ruan.uberareafilter.engine.Decision) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Ative a permissão 'Sobrepor Tela' para ver a prévia do card.", Toast.LENGTH_LONG).show()
            return
        }
        val overlay = previewOverlay ?: OverlayManager(applicationContext).also { previewOverlay = it }
        val cfg = repository.load()
        val est = FuelCalculator.estimate(6.7, 18.90, 42.0, cfg)
        val profitLine = est?.let { listOfNotNull(it.profitText, it.netHourlyText).joinToString(" · ") }

        val (title, status, app, checklist) = when (decision) {
            com.ruan.uberareafilter.engine.Decision.INSIDE_AREA -> listOf(
                "Dentro da área",
                "ACEITANDO",
                "UBER",
                "✔ Nota 4,95  •  ✔ R$42/h  •  ✔ Emb 1,2km\n✔ Embarque: Rua Pirangi\n✔ Destino: Av. Lobo Júnior"
            )
            com.ruan.uberareafilter.engine.Decision.OUTSIDE_AREA -> listOf(
                "Fora dos critérios",
                "RECUSANDO",
                "99",
                "✔ Nota 4,90  •  ✘ R$24/h (mín 30)\n✔ Embarque: Estrada do Laranjal\n✘ Destino fora: Belford Roxo"
            )
            com.ruan.uberareafilter.engine.Decision.INTER_ZONE -> listOf(
                "Corrida entre zonas",
                "VOCÊ DECIDE",
                "UBER",
                "✔ Embarque na Zona 1  •  ✔ Destino na Zona 2  •  ✔ 6,7 km"
            )
            com.ruan.uberareafilter.engine.Decision.RETURN_TO_AREA -> listOf(
                "Fora da área • Emb ≤ 1 km",
                "RETORNO",
                "99",
                "✔ Embarque a 0,8 km\n✘ Você está fora da área definida"
            )
            else -> listOf("Prévia", "INFO", "99", "Exemplo")
        }

        overlay.showRideCard(
            RideCardModel(
                decision = decision,
                appSource = app,
                title = title,
                statusLabel = status,
                priceText = "R$ 18,90",
                hourlyText = "R$ 42/h",
                secondaryMetrics = listOf("R$ 2,82/km", "6,7 km", "★ 4,95"),
                fuelText = est?.costText,
                profitText = profitLine,
                profitPositive = (est?.netProfit ?: 1.0) > 0.0,
                checklist = checklist,
                autoHideMs = 3500L
            )
        )
    }

    private fun showPreviewSequence() {
        val order = listOf(
            com.ruan.uberareafilter.engine.Decision.INSIDE_AREA,
            com.ruan.uberareafilter.engine.Decision.OUTSIDE_AREA,
            com.ruan.uberareafilter.engine.Decision.INTER_ZONE,
            com.ruan.uberareafilter.engine.Decision.RETURN_TO_AREA
        )
        lifecycleScope.launch {
            for (d in order) {
                showPreview(d)
                kotlinx.coroutines.delay(2600L)
            }
        }
    }

    // ============================================================
    // Logs: Exportar para Downloads + Compartilhar
    // ============================================================

    private fun exportAndShareLogs() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ConfigRepository.exportLogsToDownloadsWithUri(this@MainActivity)
            }
            if (result == null) {
                Toast.makeText(this@MainActivity, "Falha ao exportar logs.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val (path, uri) = result
            Toast.makeText(this@MainActivity, "📁 Logs salvos em $path", Toast.LENGTH_LONG).show()

            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Logs Filtro Inteligente 99/Uber")
                if (uri != null) {
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    val text = withContext(Dispatchers.IO) {
                        ConfigRepository.getFullLogsText(this@MainActivity)
                    }.takeLast(90_000)
                    putExtra(Intent.EXTRA_TEXT, text)
                }
            }
            try {
                startActivity(Intent.createChooser(share, "Enviar logs via..."))
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Nenhum app disponível para compartilhar.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
