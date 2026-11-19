package com.ruan.uberareafilter.ui

import com.ruan.uberareafilter.account.AccountGate

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ruan.uberareafilter.adb.AdbPairingReceiver
import com.ruan.uberareafilter.adb.StandaloneAdbBridge
import com.ruan.uberareafilter.R
import com.ruan.uberareafilter.databinding.ActivityMainBinding
import com.ruan.uberareafilter.engine.AddressParser
import com.ruan.uberareafilter.engine.AreaEvaluator
import com.ruan.uberareafilter.engine.BlockedMatcher
import com.ruan.uberareafilter.engine.BlockedPlace
import com.ruan.uberareafilter.engine.FuelCalculator
import com.ruan.uberareafilter.overlay.CardPalette
import com.ruan.uberareafilter.overlay.OverlayManager
import com.ruan.uberareafilter.overlay.RideCardModel
import com.ruan.uberareafilter.model.DualConflictPolicy
import com.ruan.uberareafilter.model.FavoriteRideItem
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.model.RideHistoryItem
import com.ruan.uberareafilter.model.ZoneMode
import com.ruan.uberareafilter.map.StreetIndexRefresher
import com.ruan.uberareafilter.service.DriverLocationProvider
import com.ruan.uberareafilter.service.UberAccessibilityService
import com.ruan.uberareafilter.shizuku.ShizukuTapBridge
import com.ruan.uberareafilter.setup.SetupChecks
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
                "Área salva: $allowedZones zona(s) liberada(s), $blockedZones proibida(s) e $streetsCount ruas.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        repository = ConfigRepository(this)
        applyThemeMode(repository.load().darkThemeEnabled)
        super.onCreate(savedInstanceState)
        // Sem login/plano valido o app nao abre: vai para a tela da conta
        if (!AccountGate.isLicensed(this)) {
            AccountActivity.open(this, locked = true)
            finish()
            return
        }
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
        setupPolish()

        if (AccountDialogs.maybeShowWelcome(this)) {
            // Acabou de entrar na conta: aviso dos dias gratis primeiro
        } else if (WhatsNewActivity.shouldShowWhatsNew(this)) {
            startActivity(Intent(this, WhatsNewActivity::class.java))
        } else if (SetupChecks.shouldAutoOpenWizard(this) &&
            SetupChecks.a11yState(this) != SetupChecks.A11y.RUNNING
        ) {
            // Primeira vez: abre o assistente em vez de deixar a pessoa procurar o que ligar
            SetupChecks.markWizardAutoOpened(this)
            SetupWizardActivity.open(this)
        }
    }

    override fun onStop() {
        super.onStop()
        // Saiu da tela: manda para a conta o que mudou (zonas, ganhos, registros)
        com.ruan.uberareafilter.account.SyncManager.syncInBackground(this)
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
        if (!::binding.isInitialized) return
        // Confere a licenca no servidor; se a conta foi bloqueada/vencida/outro celular, trava o app
        if (AccountGate.needsRefresh(this)) {
            lifecycleScope.launch {
                AccountGate.refresh(this@MainActivity)
                val st = AccountGate.state(this@MainActivity)
                if (!AccountGate.isLicensed(this@MainActivity) || st?.mustUpdate == true) {
                    AccountActivity.open(this@MainActivity, locked = true)
                    finish()
                } else {
                    AccountDialogs.maybeRemindUpdate(this@MainActivity)
                    AccountDialogs.maybeShowNotice(this@MainActivity)
                }
            }
        }
        statusRechecks = 0
        loadConfigIntoUi()
        renderRideHistory(repository.getRideHistory())
        updatePermissionStatus()
        maybeStartAdbAutoConnectOnResume()
        maybeRefreshStreetIndex()
        maybeAskDriverLocationPermission()
    }

    /** Zonas antigas (ou redesenhadas) ganham o indice de ruas internas/divisa em segundo plano. */
    private fun maybeRefreshStreetIndex() {
        if (!StreetIndexRefresher.isStale(repository)) return
        lifecycleScope.launch {
            val outcome = StreetIndexRefresher.refresh(repository) ?: return@launch
            val borderCount = (outcome.touchingStreets - outcome.internalStreets).coerceAtLeast(0)
            Toast.makeText(
                this@MainActivity,
                "Ruas da área conferidas: ${outcome.internalStreets} dentro e $borderCount na divisa",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Pede (uma unica vez) a localizacao "o tempo todo", usada so como prova extra do embarque. */
    private fun maybeAskDriverLocationPermission() {
        if (DriverLocationProvider.hasPermission(this)) return
        val prefs = getPreferences(Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_DRIVER_LOC_ASKED, false)) return
        prefs.edit().putBoolean(PREF_DRIVER_LOC_ASKED, true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle("Sua localização (opcional)")
            .setIcon(UiPolish.icon(this, R.drawable.ic_place, R.color.acc_blue_on))
            .setMessage(
                "Com a sua posição, o filtro consegue PROVAR que um embarque sem número " +
                    "(ex.: só \"Olaria, Rio de Janeiro\") está dentro da sua área quando você está bem dentro dela.\n\n" +
                    "O app não liga o GPS: só lê a última posição que a Uber/99 já calcularam.\n\n" +
                    "Permita a localização e, na tela seguinte, escolha \"Permitir o tempo todo\"."
            )
            .setPositiveButton("Permitir") { _, _ -> requestDriverLocationPermission() }
            .setNegativeButton("Agora não", null)
            .show()
    }

    /** "Receber zonas": escolhe o arquivo .json (ex.: baixado do WhatsApp) e pergunta se junta ou substitui. */
    private val zoneFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        ZoneShareUi.importFromUri(this, repository, uri) { imported ->
            if (imported) {
                loadConfigIntoUi()
                maybeRefreshStreetIndex()
            }
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* resultado lido na hora de avaliar a corrida (DriverLocationProvider) */ }

    private val foregroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val backgroundGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fineGranted && !backgroundGranted) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Último passo")
                .setMessage("Na próxima tela, marque \"Permitir o tempo todo\" — o filtro trabalha com a Uber/99 aberta por cima.")
                .setPositiveButton("Abrir") { _, _ ->
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton("Agora não", null)
                .show()
        }
    }

    private fun requestDriverLocationPermission() {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            foregroundLocationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } else {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
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
    // Quantas corridas do historico aparecem na tela (abre de 30 em 30 no "Mostrar mais")
    private var historyShown = 15
    private val HISTORY_PAGE = 30

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
        binding.switchIntoArea.isChecked = config.acceptRidesIntoArea
        binding.switchAvBrasilOdd.isChecked = config.avBrasilOddSideOnly
        binding.switchRejectRiskBadge.isChecked = config.reject99RiskAreaBadge
        binding.switchRejectUncertain.isChecked = config.rejectUncertainLocation

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
        binding.switchFilterPricePerKm.isChecked = config.filterByPricePerKmEnabled
        binding.edtMinPricePerKm.setText(String.format(java.util.Locale.US, "%.2f", config.minPricePerKm))
        binding.switchZoneCardColors.isChecked = config.zoneCardColorsEnabled
        binding.edtOffButtonMinutes.setText(config.offButtonMinutes.toString())
        binding.switchUberCustomHourly.isChecked = config.uberCustomHourlyEnabled
        binding.edtUberMinHourly.setText(config.uberMinHourlyRate.toString())
        binding.switchNegociaHourly.isChecked = config.negociaHourlyEnabled
        binding.edtNegociaMinHourly.setText(config.negociaMinHourlyRate.toString())
        binding.switchTrafficCheck.isChecked = config.trafficCheckEnabled
        binding.edtTrafficMinKm.setText(config.trafficMinTotalKm.toString())
        binding.edtTrafficApiKey.setText(config.trafficApiKey)
        binding.groupTrafficVehicle.check(if (config.trafficVehicle == "car") R.id.btnVehicleCar else R.id.btnVehicleMoto)
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
        binding.edtMaxRideHistory.setText(config.maxRideHistory.toString())
        updateRideShotsStatsUi()

        binding.switchModule99.isChecked = config.nineNineModuleEnabled
        binding.switchModuleUber.isChecked = config.uberModuleEnabled
        binding.switchDualConflictGuard.isChecked = config.dualConflictGuardEnabled
        binding.switchChainApps.isChecked = config.chainAcrossAppsEnabled
        binding.edtChainMaxWait.setText(config.chainMaxWaitMin.toString())
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
        binding.btnOpenFavorites.text = if (favCount > 0) "Ver passageiros fixos ($favCount)" else getString(R.string.main_btn_favorites)

        updateZoneStatusText(config.mapZones, config.allowedKeywords.size)
        isUpdatingUi = false
    }

    private fun updateZoneStatusText(zones: List<MapZone>, streetsCount: Int) {
        val allowedCount = zones.count { it.mode == ZoneMode.ALLOWED }
        val blockedCount = zones.count { it.mode == ZoneMode.BLOCKED }

        binding.txtPolygonStatus.text = if (zones.isNotEmpty()) {
            "Área pronta: $allowedCount zona(s) liberada(s) e $blockedCount proibida(s) · $streetsCount ruas conhecidas"
        } else if (streetsCount > 0) {
            "Usando a lista de $streetsCount ruas. Desenhe no mapa para o app entender melhor a sua área."
        } else {
            "Você ainda não desenhou sua área. Toque em “Abrir o mapa” para começar."
        }
        binding.txtPolygonStatus.setCompoundDrawablesRelative(
            UiPolish.icon(this, if (zones.isNotEmpty()) R.drawable.ic_check_circle else R.drawable.ic_info,
                if (zones.isNotEmpty()) R.color.st_ok else R.color.st_warn)?.apply { setBounds(0, 0, dp(18), dp(18)) },
            null, null, null
        )
        binding.txtPolygonStatus.compoundDrawablePadding = dp(8)
    }

    private fun setupListeners() {
        binding.btnEarnings.setOnClickListener {
            startActivity(Intent(this, EarningsActivity::class.java))
        }

        binding.btnAccount.setOnClickListener { AccountDialogs.showAccountMenu(this, it) }
        binding.btnWhatsNew.setOnClickListener {
            val intent = Intent(this, WhatsNewActivity::class.java).apply {
                putExtra(WhatsNewActivity.EXTRA_FROM_MAIN, true)
            }
            startActivity(intent)
        }

        binding.headerStreetLists.setOnClickListener {
            val isVisible = binding.layoutStreetListsContent.visibility == View.VISIBLE
            binding.layoutStreetListsContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            UiPolish.rotateArrow(binding.txtStreetListsToggleArrow, expanded = !isVisible)
        }

        binding.headerVisualCustomization.setOnClickListener {
            val isVisible = binding.layoutVisualCustomizationContent.visibility == View.VISIBLE
            binding.layoutVisualCustomizationContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            UiPolish.rotateArrow(binding.txtVisualToggleArrow, expanded = !isVisible)
        }

        binding.headerLogsDiagnostic.setOnClickListener {
            val isVisible = binding.layoutLogsDiagnosticContent.visibility == View.VISIBLE
            binding.layoutLogsDiagnosticContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            UiPolish.rotateArrow(binding.txtLogsDiagnosticToggleArrow, expanded = !isVisible)
        }

        binding.btnSetupWizard.setOnClickListener {
            SetupWizardActivity.open(this)
        }

        binding.btnAccessibilitySettings.setOnClickListener {
            SetupChecks.open(this, SetupChecks.accessibilityIntents(this))
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
                    if (ShizukuTapBridge.isPermissionDenied()) {
                        // Negado com "nao perguntar de novo": o pedido nao aparece mais, so dentro do Shizuku
                        packageManager.getLaunchIntentForPackage(ShizukuTapBridge.SHIZUKU_PACKAGE)?.let { startActivity(it) }
                        Toast.makeText(
                            this,
                            "No Shizuku, abra \"Apps autorizados\" e ligue o Filtro.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        ShizukuTapBridge.requestPermission()
                    }
                }
                else -> {
                    ShizukuTapBridge.ensureBound(this)
                    updatePermissionStatus()
                    val msg = if (ShizukuTapBridge.isServiceConnected()) "Shizuku ligado para a Uber." else "Shizuku autorizado, conectando..."
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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

        // Trocar zonas com outro motorista (WhatsApp)
        binding.btnShareZones.setOnClickListener {
            saveConfigFromUi(silent = true)
            ZoneShareUi.shareMyZones(this, repository)
        }
        binding.btnImportZones.setOnClickListener {
            saveConfigFromUi(silent = true)
            zoneFileLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"))
        }

        binding.btnSave.setOnClickListener {
            saveConfigFromUi(silent = false)
        }

        binding.btnClearRideScreenshots.setOnClickListener {
            val (count, _) = ConfigRepository.getRideScreenshotsStats(this)
            MaterialAlertDialogBuilder(this)
                .setTitle("Apagar as fotos salvas?")
                .setIcon(UiPolish.icon(this, R.drawable.ic_delete, R.color.st_bad))
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
            MaterialAlertDialogBuilder(this)
                .setTitle("Apagar os registros?")
                .setIcon(UiPolish.icon(this, R.drawable.ic_delete, R.color.st_bad))
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
            binding.switchChainApps,
            binding.switchRejectUberStops,
            binding.switchUberCustomHourly,
            binding.switchNegociaHourly,
            binding.switchTrafficCheck,
            binding.switchShowRideBanner,
            binding.switchRequireBoth,
            binding.switchAutoAcceptInterZone,
            binding.switchYellowOutside,
            binding.switchIntoArea,
            binding.switchAvBrasilOdd,
            binding.switchRejectRiskBadge,
            binding.switchRejectUncertain,
            binding.switchFilterTotalDist,
            binding.switchFilterPickupDist,
            binding.switchFilterRating,
            binding.switchRejectNewPassenger,
            binding.switchFilterHourly,
            binding.switchFilterPricePerKm,
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
                    UiPolish.tick(sw)
                    if (sw == binding.switchFilterTotalDist && binding.switchFilterTotalDist.isChecked) {
                        if (!validateInterZoneDistanceOrWarn()) return@setOnCheckedChangeListener
                    }
                    binding.switchRoundDownNetHourly.isEnabled =
                        binding.switchUseNetHourlyRate.isChecked || binding.switchFuelEnabled.isChecked
                    if (sw == binding.switchModule99 ||
                        sw == binding.switchModuleUber ||
                        sw == binding.switchDualConflictGuard ||
                        sw == binding.switchChainApps
                    ) {
                        refreshDualConflictUiState()
                    }
                    if (sw == binding.switchDebug) {
                        binding.switchXrayDiagnostic.isEnabled = binding.switchDebug.isChecked
                    }
                    saveConfigFromUi(silent = true)
                    if (sw == binding.switchEnabled) {
                        if (binding.switchEnabled.isChecked) {
                            // Saindo do OFF: ON de verdade (sem pausa) e religa a leitura de tela
                            repository.setRunStateQuick(com.ruan.uberareafilter.model.RunState.ON)
                            if (!com.ruan.uberareafilter.service.UberAccessibilityService.running) {
                                if (com.ruan.uberareafilter.setup.SetupChecks.enableOwnAccessibilityService(this)) {
                                    Toast.makeText(this, "Filtro ligado.", Toast.LENGTH_SHORT).show()
                                } else {
                                    SetupChecks.open(this, SetupChecks.accessibilityIntents(this))
                                }
                            }
                        } else {
                            Toast.makeText(this, "Leitura de tela desligada. Pode usar o app do banco.", Toast.LENGTH_LONG).show()
                        }
                        refreshHero()
                    }
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
            MaterialAlertDialogBuilder(this)
                .setTitle("Tipo de combustível")
                .setIcon(UiPolish.icon(this, R.drawable.ic_fuel, R.color.acc_orange_on))
                .setSingleChoiceItems(types, curIdx) { dialog, which ->
                    currentFuelType = types[which]
                    saveConfigFromUi(silent = true)
                    updateFuelUiLabels(repository.load())
                    dialog.dismiss()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        binding.switchZoneCardColors.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            saveConfigFromUi(silent = true)
            if (!isChecked) return@setOnCheckedChangeListener
            val cfg = repository.load()
            val additional = cfg.mapZones.filter { it.mode == ZoneMode.ALLOWED }.drop(1)
            if (additional.isEmpty()) {
                Toast.makeText(this, "Demarque uma zona adicional no mapa para escolher a cor dela.", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            ZoneColorEditor.edit(this, cfg.mapZones, additional.map { it.id }) { colored ->
                repository.save(repository.load().copy(mapZones = colored))
                Toast.makeText(this, "Cores das zonas salvas.", Toast.LENGTH_SHORT).show()
            }
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
        binding.switchChainApps.isEnabled = policyEnabled
        val chainOn = policyEnabled && binding.switchChainApps.isChecked
        binding.edtChainMaxWait.isEnabled = chainOn
        binding.rowChainMaxWait.alpha = if (chainOn) 1.0f else 0.5f
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

            MaterialAlertDialogBuilder(this)
                .setTitle("O limite de km está baixo")
                .setIcon(UiPolish.icon(this, R.drawable.ic_warning, R.color.st_warn))
                .setMessage(
                    "Suas zonas de aceitação estão a cerca de $minFmt de distância entre si, " +
                        "mas o filtro 'KM Total máximo' está ativo em apenas $curFmt.\n\n" +
                        "Como as zonas são muito distantes em relação ao KM máximo ativo, nenhuma corrida entre elas seria permitida.\n\n" +
                        "Deseja aumentar o KM Total máximo para $suggestedKm km e ativar a Corrida entre Zonas (card roxo)?"
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
        val minPricePerKm = binding.edtMinPricePerKm.text?.toString()?.replace(",", ".")?.toDoubleOrNull()
            ?.takeIf { it in 0.01..50.0 } ?: 1.20
        val uberMinHourly = binding.edtUberMinHourly.text?.toString()?.replace(",", ".")?.toDoubleOrNull() ?: minHourly
        val negociaMinHourly = binding.edtNegociaMinHourly.text?.toString()?.replace(",", ".")?.toDoubleOrNull()
            ?.takeIf { it in 1.0..500.0 } ?: 40.0
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
            chainAcrossAppsEnabled = binding.switchChainApps.isChecked,
            chainMaxWaitMin = (binding.edtChainMaxWait.text?.toString()?.trim()?.toIntOrNull() ?: 3).coerceIn(1, 30),
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
            acceptRidesIntoArea = binding.switchIntoArea.isChecked,
            avBrasilOddSideOnly = binding.switchAvBrasilOdd.isChecked,
            reject99RiskAreaBadge = binding.switchRejectRiskBadge.isChecked,
            rejectUncertainLocation = binding.switchRejectUncertain.isChecked,
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
            filterByPricePerKmEnabled = binding.switchFilterPricePerKm.isChecked,
            minPricePerKm = minPricePerKm,
            zoneCardColorsEnabled = binding.switchZoneCardColors.isChecked,
            offButtonMinutes = (binding.edtOffButtonMinutes.text?.toString()?.trim()?.toIntOrNull() ?: 10).coerceIn(0, 240),
            uberCustomHourlyEnabled = binding.switchUberCustomHourly.isChecked,
            uberMinHourlyRate = uberMinHourly,
            negociaHourlyEnabled = binding.switchNegociaHourly.isChecked,
            negociaMinHourlyRate = negociaMinHourly,
            trafficCheckEnabled = binding.switchTrafficCheck.isChecked,
            trafficMinTotalKm = binding.edtTrafficMinKm.text?.toString()?.replace(",", ".")?.toDoubleOrNull()?.coerceIn(0.0, 50.0) ?: 0.0,
            trafficApiKey = binding.edtTrafficApiKey.text?.toString()?.trim().orEmpty(),
            trafficVehicle = if (binding.groupTrafficVehicle.checkedButtonId == R.id.btnVehicleCar) "car" else "moto",
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
            saveRideScreenshotsEnabled = binding.switchSaveRideScreenshots.isChecked,
            maxRideHistory = (binding.edtMaxRideHistory.text?.toString()?.trim()?.toIntOrNull() ?: ConfigRepository.MAX_HISTORY_ITEMS)
                .coerceIn(ConfigRepository.MIN_HISTORY_ITEMS, ConfigRepository.MAX_HISTORY_ITEMS)
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
            "$count foto(s) guardada(s) · $sizeText · só no seu celular"
        } else {
            getString(R.string.main_shots_info)
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
                text = "Nenhuma corrida ainda. Assim que chegar uma corrida na 99 ou na Uber, ela aparece aqui com a decisão e o motivo."
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, com.ruan.uberareafilter.R.color.fi_on_surface_variant))
            }
            container.addView(emptyView)
            return
        }

        // Cores do tema (seguem claro/escuro sozinhas)
        val cSurface = ContextCompat.getColor(this, com.ruan.uberareafilter.R.color.fi_surface_container_low)
        val cOutline = ContextCompat.getColor(this, com.ruan.uberareafilter.R.color.fi_outline_variant)
        val cOnSurface = ContextCompat.getColor(this, com.ruan.uberareafilter.R.color.fi_on_surface)
        val cOnVariant = ContextCompat.getColor(this, com.ruan.uberareafilter.R.color.fi_on_surface_variant)
        val cOk = ContextCompat.getColor(this, com.ruan.uberareafilter.R.color.fi_primary)
        val cBad = ContextCompat.getColor(this, com.ruan.uberareafilter.R.color.fi_error)
        val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        items.take(historyShown).forEach { item ->
            val shotFile = item.screenshotPath
                .takeIf { it.isNotBlank() }
                ?.let { File(it) }
                ?.takeIf { it.exists() }

            val statusColor = if (item.isInsideArea) cOk else cBad
            val statusBg = ContextCompat.getColor(this, if (item.isInsideArea) R.color.st_ok_container else R.color.st_bad_container)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(8))
                // Faixa colorida na esquerda: verde = podia aceitar, vermelho = recusada
                val bg = android.graphics.drawable.LayerDrawable(arrayOf(
                    GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(cSurface)
                        setStroke(dp(1), cOutline)
                    },
                    GradientDrawable().apply {
                        cornerRadii = floatArrayOf(dp(16).toFloat(), dp(16).toFloat(), 0f, 0f, 0f, 0f, dp(16).toFloat(), dp(16).toFloat())
                        setColor(statusColor)
                    }
                )).apply {
                    setLayerWidth(1, dp(5))
                    setLayerGravity(1, android.view.Gravity.START or android.view.Gravity.FILL_VERTICAL)
                }
                background = bg
                foreground = ContextCompat.getDrawable(this@MainActivity, android.util.TypedValue().also {
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                }.resourceId)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                isClickable = true
                setOnClickListener { showRideScreenshotDialog(item, shotFile) }
            }

            // Linha 1: selo (Aceitável / Recusada) + hora + valor em destaque
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            headerRow.addView(TextView(this).apply {
                text = if (item.isInsideArea) "Aceitável" else "Recusada"
                setTextColor(statusColor)
                textSize = 11.5f
                typeface = sansMedium
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(statusBg) }
                setPadding(dp(8), dp(2), dp(8), dp(2))
            })
            headerRow.addView(TextView(this).apply {
                text = item.timeFormatted
                setTextColor(cOnVariant)
                textSize = 13f
                fontFeatureSettings = "tnum"
                setPadding(dp(10), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (item.priceText.isNotEmpty()) headerRow.addView(TextView(this).apply {
                text = item.priceText
                setTextColor(cOnSurface)
                textSize = 18f
                typeface = sansMedium
                fontFeatureSettings = "tnum"
            })

            val metricsLine = TextView(this).apply {
                text = listOf(item.hourlyRateText, item.pricePerKmText, item.ratingText)
                    .filter { it.isNotEmpty() }
                    .joinToString("  ·  ")
                setTextColor(cOnVariant)
                textSize = 13f
                fontFeatureSettings = "tnum"
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
                setPadding(0, dp(4), 0, 0)
            }

            fun routeLine(iconRes: Int, tintRes: Int, label: String, value: String) = TextView(this).apply {
                val sb = android.text.SpannableStringBuilder()
                sb.append("$label  ")
                sb.setSpan(android.text.style.ForegroundColorSpan(cOnVariant), 0, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(value.ifEmpty { "—" })
                text = sb
                setTextColor(cOnSurface)
                textSize = 13.5f
                setCompoundDrawablesRelative(
                    UiPolish.icon(this@MainActivity, iconRes, tintRes)?.apply { setBounds(0, 0, dp(16), dp(16)) }, null, null, null
                )
                compoundDrawablePadding = dp(8)
                setPadding(0, dp(6), 0, 0)
            }
            val pickupLine = routeLine(R.drawable.ic_trip_origin, R.color.st_ok, "Busca", item.pickupAddress)
            val dropLine = routeLine(R.drawable.ic_flag, R.color.acc_blue_on, "Destino", item.dropoffAddress)
            val reasonLine = TextView(this).apply {
                text = item.reason
                setTextColor(if (item.isInsideArea) cOnVariant else cBad)
                textSize = 12.5f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(24), dp(6), 0, dp(4))
            }

            val shotBadge = TextView(this).apply {
                text = if (shotFile != null) "Toque para ver a foto da corrida" else "Sem foto desta corrida"
                setTextColor(cOnVariant)
                textSize = 12f
                setCompoundDrawablesRelative(
                    UiPolish.icon(this@MainActivity, R.drawable.ic_camera, R.color.fi_on_surface_variant)?.apply { setBounds(0, 0, dp(14), dp(14)) }, null, null, null
                )
                compoundDrawablePadding = dp(6)
                setPadding(dp(24), dp(2), 0, dp(6))
            }

            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            fun actionButton(label: String, iconRes: Int, onClick: () -> Unit) =
                com.google.android.material.button.MaterialButton(
                    this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    strokeWidth = 0
                    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    text = label
                    textSize = 12.5f
                    minHeight = dp(44)
                    setIconResource(iconRes)
                    iconSize = dp(16)
                    iconPadding = dp(4)
                    setPaddingRelative(dp(4), paddingTop, dp(4), paddingBottom)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { onClick() }
                }

            actionsRow.addView(actionButton("Liberar rua", R.drawable.ic_check_circle) { promptPickAddressForList(item, isAllow = true) })
            actionsRow.addView(actionButton("Bloquear", R.drawable.ic_lock) { promptPickAddressForList(item, isAllow = false) })
            actionsRow.addView(actionButton("Fixo", R.drawable.ic_favorite) { saveHistoryItemAsFavorite(item) })

            card.addView(headerRow)
            card.addView(metricsLine)
            card.addView(pickupLine)
            card.addView(dropLine)
            card.addView(reasonLine)
            card.addView(shotBadge)
            card.addView(actionsRow)
            container.addView(card)
        }

        // Ate 500 corridas guardadas (opcao de desenvolvedor): a lista abre aos poucos
        if (items.size > historyShown) {
            val more = minOf(HISTORY_PAGE, items.size - historyShown)
            container.addView(
                com.google.android.material.button.MaterialButton(
                    this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "Mostrar mais $more (${items.size} guardadas)"
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                    setOnClickListener {
                        historyShown += HISTORY_PAGE
                        renderRideHistory(repository.getRideHistory())
                    }
                }
            )
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
                append(item.timeFormatted)
                if (metrics.isNotEmpty()) append(" · $metrics")
                append("\nBusca: ${item.pickupAddress.ifBlank { "—" }}")
                append("\nDestino: ${item.dropoffAddress.ifBlank { "—" }}")
                append("\nMotivo: ${item.reason}")
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

        MaterialAlertDialogBuilder(this)
            .setTitle("Foto da corrida")
            .setIcon(UiPolish.icon(this, R.drawable.ic_camera, R.color.acc_cyan_on))
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
        if (!isAllow) {
            promptBlockOptions(item)
            return
        }
        val options = buildList {
            if (item.pickupAddress.isNotBlank()) add(extractStreetOrPlaceName(item.pickupAddress))
            if (item.dropoffAddress.isNotBlank()) add(extractStreetOrPlaceName(item.dropoffAddress))
        }.distinct()

        if (options.isEmpty()) return

        val title = if (isAllow) "Adicionar à Área Permitida:" else "Adicionar à Lista Negra (Bloqueados):"
        MaterialAlertDialogBuilder(this)
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

    /**
     * "Bloquear" no historico: o que exatamente bloquear. Antes so dava para bloquear a RUA inteira
     * (ex.: "Rodovia Washington Luiz" bloqueava a rodovia toda so por causa do shopping).
     */
    private fun promptBlockOptions(item: RideHistoryItem) {
        data class Opt(val label: String, val keyword: String, val place: BlockedPlace?)
        val opts = mutableListOf<Opt>()
        listOf(item.pickupAddress, item.dropoffAddress).forEach { raw ->
            val addr = raw.replace(Regex("""^\s*(uber|99)\s*:\s*""", RegexOption.IGNORE_CASE), "").trim()
            if (addr.isBlank()) return@forEach
            val p = AddressParser.parse(addr)
            val street = p.streetRaw?.trim()
            val number = p.number?.trim()
            // A lista de bloqueados e separada por virgula: o nome do local vai sem virgula
            p.poi?.substringBefore(",")?.trim()?.takeIf { it.length >= 3 }?.let { poi ->
                val place = BlockedMatcher.learn(poi, addr)
                val where = place?.let { " (${it.label})" }.orEmpty()
                opts += Opt("Só este local: $poi$where", poi, place)
            }
            if (street != null && !number.isNullOrBlank()) {
                opts += Opt("Só este endereço: $street, $number", "$street nº $number", null)
            }
            if (street != null) {
                opts += Opt("A rua inteira: $street (todas as corridas nela)", street, null)
            }
            p.bairroRaw?.replace(",", " ")?.trim()?.takeIf { it.length >= 3 }?.let { b ->
                opts += Opt("O bairro inteiro: $b", b, null)
            }
        }
        val unique = opts.distinctBy { it.keyword.lowercase() }
        if (unique.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("O que bloquear?")
            .setIcon(UiPolish.icon(this, R.drawable.ic_lock, R.color.acc_red_on))
            .setItems(unique.map { it.label }.toTypedArray()) { _, which ->
                val chosen = unique[which]
                repository.addBlockedKeyword(chosen.keyword)
                chosen.place?.let { repository.addBlockedPlaces(listOf(it)) }
                Toast.makeText(this, "Bloqueado: ${chosen.keyword}", Toast.LENGTH_SHORT).show()
                loadConfigIntoUi()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun extractStreetOrPlaceName(rawAddress: String): String {
        // Historico guarda "UBER: Rua X, Bairro" / "99: ..." -> remove o prefixo do app
        val stripped = rawAddress.replace(Regex("""^\s*(uber|99)\s*:\s*""", RegexOption.IGNORE_CASE), "").trim()
        AddressParser.parse(stripped).streetRaw?.trim()?.takeIf { it.length >= 4 }?.let { return it }
        val firstPart = stripped.split(",").firstOrNull()?.trim().orEmpty()
        return if (firstPart.length >= 4) firstPart else stripped.take(35)
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
                "Salvo nos passageiros fixos (${item.timeFormatted})",
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

    private var statusRechecks = 0

    /**
     * Status real de cada recurso (SetupChecks.snapshot): a Acessibilidade separa "ligada" de "rodando",
     * o motor interno e checado fora da thread principal e "Sobrepor tela" deixou de ser cobrado
     * (os cards em cima da 99/Uber sao desenhados pela propria Acessibilidade).
     */
    private fun updatePermissionStatus() {
        val cfg = repository.load()
        lifecycleScope.launch(Dispatchers.IO) {
            val snap = runCatching { SetupChecks.snapshot(this@MainActivity, cfg) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) renderSetupStatus(snap)
            }
        }
    }

    private fun renderSetupStatus(snap: SetupChecks.Snapshot) {
        lastSnapshot = snap
        renderHealth(snap)
        refreshHero()

        val recommended = snap.pendingRecommended
        binding.btnSetupWizard.text = when {
            snap.pendingRequired == 1 -> "Configurar agora · falta 1 passo"
            snap.pendingRequired > 1 -> "Configurar agora · faltam ${snap.pendingRequired} passos"
            recommended > 0 -> "Revisar configuração · $recommended ajuste${if (recommended > 1) "s" else ""} recomendado${if (recommended > 1) "s" else ""}"
            else -> "Configuração completa"
        }

        binding.btnStandaloneAdb.text = when {
            snap.daemonAlive && snap.lastTapRefused -> "Toque na Uber: o Android recusou o último toque"
            snap.daemonAlive -> "Toque automático na Uber: liberado"
            snap.adbPaired -> "Ligar toque automático na Uber (já pareado)"
            else -> "Configurar toque automático na Uber"
        }

        // Shizuku continua escondido para quem nao instalou (o motor interno faz o mesmo sem outro app)
        val sz = snap.shizuku
        binding.btnShizukuSettings.visibility =
            if (sz == ShizukuTapBridge.State.NOT_INSTALLED) View.GONE else View.VISIBLE
        binding.btnShizukuSettings.text = when (sz) {
            ShizukuTapBridge.State.NOT_INSTALLED -> getString(com.ruan.uberareafilter.R.string.main_btn_shizuku)
            ShizukuTapBridge.State.NOT_RUNNING -> "Shizuku (alternativa): abrir e iniciar"
            ShizukuTapBridge.State.NO_PERMISSION -> "Shizuku (alternativa): autorizar o Filtro"
            ShizukuTapBridge.State.DENIED -> "Shizuku: negado, liberar dentro do app Shizuku"
            ShizukuTapBridge.State.CONNECTING -> "Shizuku: conectando..."
            ShizukuTapBridge.State.ACTIVE -> "Shizuku ligado"
        }
        if (sz == ShizukuTapBridge.State.CONNECTING) ShizukuTapBridge.ensureBound(this)

        // O Android leva ~1 s para subir o servico depois de ligar a chave: confere de novo (no maximo 2x)
        if (snap.a11y == SetupChecks.A11y.ON_NOT_RUNNING && statusRechecks < 2) {
            statusRechecks++
            binding.root.postDelayed({ if (!isFinishing) updatePermissionStatus() }, 2000L)
        }
    }

    private var lastSnapshot: SetupChecks.Snapshot? = null
    private var healthRenderedOnce = false

    /** Liga o acabamento visual: "?" de ajuda, chave branca no cartão, topo que ganha linha ao rolar. */
    private fun setupPolish() {
        UiPolish.attachHelpButtons(this, binding.root as android.view.ViewGroup)
        UiPolish.styleHeroSwitch(binding.switchEnabled)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        binding.txtTopVersion.text = if (version != null) "99 e Uber · v$version" else getString(R.string.main_subtitle)
        binding.rootScrollView.setOnScrollChangeListener { _, _, y, _, _ ->
            binding.topBarDivider.alpha = (y / dp(24).toFloat()).coerceIn(0f, 1f)
        }
        listOf(binding.layoutStreetListsContent, binding.layoutVisualCustomizationContent, binding.layoutLogsDiagnosticContent)
            .forEach { (it.parent as? android.view.ViewGroup)?.let(UiPolish::smoothExpand) }
        binding.btnResume.setOnClickListener {
            repository.setRunStateQuick(com.ruan.uberareafilter.model.RunState.ON)
            Toast.makeText(this, "Pausa encerrada: o filtro volta a aceitar e recusar.", Toast.LENGTH_SHORT).show()
            refreshHero()
        }
        refreshHero()
    }

    /** Cartão principal + selo do topo: ON (verde), PAUSA (laranja), falta algo (vermelho) ou OFF (cinza). */
    private fun refreshHero() {
        val cfg = repository.load()
        val snap = lastSnapshot
        val state = when {
            !cfg.isEnabled -> UiPolish.HeroState.OFF
            cfg.actionsPaused -> UiPolish.HeroState.PAUSED
            snap != null && snap.a11y != SetupChecks.A11y.RUNNING -> UiPolish.HeroState.ATTENTION
            else -> UiPolish.HeroState.ON
        }
        val (title, sub, iconRes) = when (state) {
            UiPolish.HeroState.ON -> Triple(R.string.hero_on_title, R.string.hero_on_sub, R.drawable.ic_power)
            UiPolish.HeroState.PAUSED -> Triple(R.string.hero_paused_title, R.string.hero_paused_sub, R.drawable.ic_pause_circle)
            UiPolish.HeroState.ATTENTION -> Triple(R.string.hero_on_title, R.string.hero_attention_sub, R.drawable.ic_warning)
            UiPolish.HeroState.OFF -> Triple(R.string.hero_off_title, R.string.hero_off_sub, R.drawable.ic_power)
        }
        binding.txtHeroTitle.setText(title)
        binding.txtHeroSubtitle.setText(sub)
        binding.imgHeroState.setImageResource(iconRes)
        binding.imgHeroState.imageTintList = ColorStateList.valueOf(Color.WHITE)
        binding.imgHeroState.backgroundTintList = ColorStateList.valueOf(Color.argb(if (state == UiPolish.HeroState.OFF) 40 else 56, 255, 255, 255))
        binding.btnResume.visibility = if (state == UiPolish.HeroState.PAUSED) View.VISIBLE else View.GONE
        UiPolish.paintHeroBackground(binding.heroContent, dp(18).toFloat(), state)
        UiPolish.paintStatusChip(binding.txtTopStatus, state)
    }

    /** Verificação do app: barra de progresso, resumo e uma linha por item, com "Resolver" no que falta. */
    private fun renderHealth(snap: SetupChecks.Snapshot) {
        val cfg = repository.load()
        val hasArea = cfg.mapZones.isNotEmpty() || cfg.polygonPoints.size >= 3 || cfg.allowedKeywords.isNotEmpty()
        val checks = SetupChecks.healthChecks(snap, hasArea)
        val counted = checks.filter { it.level != SetupChecks.Level.OFF }
        val ok = counted.count { it.level == SetupChecks.Level.OK }
        val bad = checks.count { it.level == SetupChecks.Level.BAD }
        val warn = checks.count { it.level == SetupChecks.Level.WARN }
        val worst = when {
            bad > 0 -> SetupChecks.Level.BAD
            warn > 0 -> SetupChecks.Level.WARN
            else -> SetupChecks.Level.OK
        }
        val st = UiPolish.levelStyle(worst)

        binding.txtHealthSummary.text = when {
            bad == 0 && warn == 0 -> getString(R.string.health_all_ok)
            bad > 0 -> "Falta${if (bad > 1) "m" else ""} $bad ${if (bad > 1) "itens importantes" else "item importante"}" +
                if (warn > 0) " e $warn recomendado${if (warn > 1) "s" else ""}" else ""
            else -> "Funcionando. $warn ${if (warn > 1) "ajustes recomendados" else "ajuste recomendado"} para ficar melhor."
        }
        binding.txtHealthSummary.setTextColor(UiPolish.color(this, if (worst == SetupChecks.Level.OK) R.color.st_ok else st.fg))
        binding.imgHealthTile.setImageResource(if (worst == SetupChecks.Level.OK) R.drawable.ic_check_circle else st.icon)
        binding.imgHealthTile.imageTintList = ColorStateList.valueOf(UiPolish.color(this, st.fg))
        binding.imgHealthTile.backgroundTintList = ColorStateList.valueOf(UiPolish.color(this, st.bg))
        binding.healthProgress.setIndicatorColor(UiPolish.color(this, st.fg))
        binding.healthProgress.trackColor = UiPolish.color(this, st.bg)
        val pct = if (counted.isEmpty()) 100 else ok * 100 / counted.size
        binding.healthProgress.setProgressCompat(pct, healthRenderedOnce)

        val list = binding.healthList
        list.removeAllViews()
        // Primeiro o que falta (vermelho, amarelo), depois o que está pronto
        val ordered = checks.sortedBy {
            when (it.level) {
                SetupChecks.Level.BAD -> 0
                SetupChecks.Level.WARN -> 1
                SetupChecks.Level.OFF -> 2
                SetupChecks.Level.OK -> 3
            }
        }
        val rows = ordered.mapIndexed { i, c -> UiPolish.healthRow(this, c, i > 0) { fix -> runFix(fix) } }
        rows.forEach { list.addView(it) }
        if (!healthRenderedOnce) UiPolish.animateEntrance(rows)
        healthRenderedOnce = true
    }

    /** O que cada botão "Resolver" da verificação faz. */
    private fun runFix(fix: SetupChecks.Fix) {
        when (fix) {
            SetupChecks.Fix.TURN_ON -> binding.switchEnabled.isChecked = true
            SetupChecks.Fix.ACCESSIBILITY -> {
                if (!SetupChecks.enableOwnAccessibilityService(this)) {
                    SetupChecks.open(this, SetupChecks.accessibilityIntents(this))
                } else {
                    Toast.makeText(this, "Leitura das corridas ligada.", Toast.LENGTH_SHORT).show()
                    binding.root.postDelayed({ updatePermissionStatus() }, 1500L)
                }
            }
            SetupChecks.Fix.RESTRICTED -> SetupChecks.open(this, SetupChecks.appDetailsIntents(this))
            SetupChecks.Fix.UBER_TAP -> binding.btnStandaloneAdb.performClick()
            SetupChecks.Fix.BATTERY -> SetupChecks.open(this, SetupChecks.batteryIntents(this))
            SetupChecks.Fix.NOTIFICATIONS -> SetupChecks.open(this, SetupChecks.notificationIntents(this))
            SetupChecks.Fix.OVERLAY -> SetupChecks.open(this, SetupChecks.overlayIntents(this))
            SetupChecks.Fix.LOCATION -> requestDriverLocationPermission()
            SetupChecks.Fix.MAP -> binding.btnOpenMap.performClick()
        }
    }

    private fun handleStandaloneAdbClick() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = StandaloneAdbBridge.getSetupEnvironmentStatus(this@MainActivity)
            if (status.isDaemonAlive) {
                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Toque automático na Uber ligado")
                        .setIcon(UiPolish.icon(this@MainActivity, R.drawable.ic_touch, R.color.st_ok))
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
                // Passo a passo guiado (modo desenvolvedor, Wi-Fi, pareamento), com o status de cada parte
                withContext(Dispatchers.Main) { SetupWizardActivity.open(this@MainActivity, focusUber = true) }
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
        val wifiLine = if (envStatus.wifiConnected) "✓ 1. Wi-Fi conectado" else "✕ 1. Wi-Fi desligado (conecte num Wi-Fi agora)"
        val devLine = if (envStatus.devOptionsEnabled) "✓ 2. Opções do desenvolvedor ativas" else "! 2. Ative 'Opções do Desenvolvedor' (toque 7x em 'Número da versão' em Sobre o telefone)"
        val wirelessLine = if (envStatus.wirelessDebugPort != null) {
            "✓ 3. Depuração por Wi-Fi detectada (porta ${envStatus.wirelessDebugPort})"
        } else {
            "⏳ 3. Ligue a chave 'Depuração por Wi-Fi'"
        }
        val pairLine = if (envStatus.isPaired) {
            "✓ 4. Pareamento feito (não precisa mais de código)"
        } else {
            "4. Parear com o código de 6 dígitos (só na 1ª vez)"
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
            MaterialAlertDialogBuilder(this)
                .setTitle("Ligar o toque automático na Uber")
                .setIcon(UiPolish.icon(this, R.drawable.ic_touch, R.color.acc_blue_on))
                .setMessage(
                    "$checklist\n\n" +
                        "Seu celular já está pareado!\n" +
                        "Não precisa digitar código nenhum: toque no botão abaixo, ative 'Depuração por Wi-Fi' (ou use o atalho 'Depuração sem fio' na barra rápida do Android) e o app inicia sozinho em segundos.\n\n" +
                        "Detalhe: $statusDetail"
                )
                .setPositiveButton(if (envStatus.devOptionsEnabled) "Abrir opções do desenvolvedor" else "Abrir “Sobre o telefone”") { _, _ ->
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
                .setNegativeButton("Parear de novo") { _, _ ->
                    StandaloneAdbBridge.clearPairing(this)
                    showStandaloneAdbDialog("Pareamento reiniciado. Siga os passos da 1ª vez.")
                }
                .show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Liberar o toque automático na Uber (1ª vez)")
                .setIcon(UiPolish.icon(this, R.drawable.ic_touch, R.color.acc_blue_on))
            .setMessage(
                "$checklist\n\n" +
                    "Como funciona (bem simples):\n" +
                    "• Você só digita os 6 dígitos ESTA ÚNICA VEZ.\n" +
                    "• Nas próximas vezes (mesmo se reiniciar), nunca mais pede código: basta ligar Wi-Fi + 'Depuração por Wi-Fi' que inicia sozinho!\n\n" +
                    "Passo atual: tocaremos em 'Parear 1ª vez', enviaremos uma notificação para digitar os 6 dígitos sem sair da tela de Depuração por Wi-Fi."
            )
            .setPositiveButton(if (envStatus.devOptionsEnabled) "Parear (1ª vez)" else "Ativar opções do desenvolvedor") { _, _ ->
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

        MaterialAlertDialogBuilder(this)
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
        SetupChecks.open(this, SetupChecks.developerOptionsIntents(highlightWireless = true))
    }

    private fun openAboutPhoneSettings() {
        SetupChecks.open(this, SetupChecks.aboutPhoneIntents())
    }

    private fun isAccessibilityEnabled(): Boolean =
        SetupChecks.a11yState(this) == SetupChecks.A11y.RUNNING

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
        INSIDE("Card de corrida aceita", "#0A2E1D"),
        OUTSIDE("Card de corrida recusada", "#330A0A"),
        INTER_ZONE("Card de corrida entre zonas", "#271252"),
        YELLOW("Card de retorno à área (até 1 km)", "#3B2806")
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

        tintColorButton(binding.btnColorGreen, config.colorInsideHex, CardColorSlot.INSIDE)
        tintColorButton(binding.btnColorRed, config.colorOutsideHex, CardColorSlot.OUTSIDE)
        tintColorButton(binding.btnColorPurple, config.colorInterZoneHex, CardColorSlot.INTER_ZONE)
        tintColorButton(binding.btnColorYellow, config.colorYellowReturnHex, CardColorSlot.YELLOW)
    }

    private fun decisionFor(slot: CardColorSlot): com.ruan.uberareafilter.engine.Decision = when (slot) {
        CardColorSlot.INSIDE -> com.ruan.uberareafilter.engine.Decision.INSIDE_AREA
        CardColorSlot.OUTSIDE -> com.ruan.uberareafilter.engine.Decision.OUTSIDE_AREA
        CardColorSlot.INTER_ZONE -> com.ruan.uberareafilter.engine.Decision.INTER_ZONE
        CardColorSlot.YELLOW -> com.ruan.uberareafilter.engine.Decision.RETURN_TO_AREA
    }

    /**
     * A amostra mostra o card como ele REALMENTE vai aparecer: o fundo ja com o brilho ajustado
     * pelo CardPalette e um anel na cor do acento (barra lateral, simbolo e R$/h).
     */
    private fun tintColorButton(
        btn: com.google.android.material.button.MaterialButton,
        hex: String,
        slot: CardColorSlot
    ) {
        val raw = safeColor(hex, slot.defaultHex)
        val (bg, accent) = CardPalette.swatch(raw, decisionFor(slot))
        btn.backgroundTintList = ColorStateList.valueOf(bg)
        btn.strokeColor = ColorStateList.valueOf(accent)
        btn.strokeWidth = dp(2)
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
                val (swBg, swAccent) = CardPalette.swatch(Color.parseColor(hex), decisionFor(slot))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(swBg)
                    setStroke(dp(3), swAccent)
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

        dialog = MaterialAlertDialogBuilder(this)
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

    private fun showPreview(decision: com.ruan.uberareafilter.engine.Decision, trafficHeavy: Boolean = false) {
        if (!Settings.canDrawOverlays(this)) {
            // So a previa usa "Sobrepor tela"; os cards em cima da 99/Uber vem da Acessibilidade
            Toast.makeText(this, "Para ver a prévia aqui no painel, permita \"Sobrepor tela\" e volte.", Toast.LENGTH_LONG).show()
            SetupChecks.open(this, SetupChecks.overlayIntents(this))
            return
        }
        val overlay = previewOverlay ?: OverlayManager(applicationContext).also { previewOverlay = it }
        val cfg = repository.load()
        val est = FuelCalculator.estimate(6.7, 18.90, 42.0, cfg)
        val profitLine = est?.let { listOfNotNull(it.profitText, it.netHourlyText).joinToString(" · ") }

        val (title, status, app, checklist) = if (trafficHeavy) listOf(
            "Trânsito pesado agora",
            "RECUSANDO",
            "99",
            "✘ Trânsito agora: 25 min (a 99 dizia 11) → R$ 16/h\n✔ Nota 4,92  •  ✔ R$1,80/km  •  ✔ Emb 1,8km\n✔ Embarque: Rua Felizardo Fortes\n✔ Destino: Av. Brasil, Bonsucesso"
        ) else when (decision) {
            com.ruan.uberareafilter.engine.Decision.INSIDE_AREA -> listOf(
                "Dentro da área",
                "ACEITANDO",
                "UBER",
                "🚦 Trânsito ok: 12 min (a 99 dizia 11)\n✔ Nota 4,95  •  ✔ R$42/h  •  ✔ Emb 1,2km\n✔ Embarque: Rua Pirangi\n✔ Destino: Av. Lobo Júnior"
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
            com.ruan.uberareafilter.engine.Decision.UNCERTAIN -> listOf(
                "Local incerto — confira",
                "VOCÊ DECIDE",
                "UBER",
                "✔ R$42/h  •  ✔ Emb 1,2km\n✔ Embarque: Rua Pirangi\n⚠ Destino: Rua Uranos (rua de divisa sem nº)"
            )
            com.ruan.uberareafilter.engine.Decision.RETURN_TO_AREA -> listOf(
                "Fora da área • Emb ≤ 1 km",
                "RETORNO",
                "99",
                "✔ Embarque a 0,8 km (fora da área)\n✔ Destino na área: Rua Pirangi"
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
                hourlyText = if (trafficHeavy) "R$ 16/h" else "R$ 42/h",
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
            com.ruan.uberareafilter.engine.Decision.UNCERTAIN,
            com.ruan.uberareafilter.engine.Decision.RETURN_TO_AREA
        )
        lifecycleScope.launch {
            for (d in order) {
                showPreview(d)
                kotlinx.coroutines.delay(2600L)
            }
            // Corrida que passou em tudo, mas o transito de agora derrubou o R$/h
            showPreview(com.ruan.uberareafilter.engine.Decision.OUTSIDE_AREA, trafficHeavy = true)
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
            Toast.makeText(this@MainActivity, "Registros salvos em $path", Toast.LENGTH_LONG).show()

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

    companion object {
        private const val PREF_DRIVER_LOC_ASKED = "driver_location_asked_v1"
    }
}
