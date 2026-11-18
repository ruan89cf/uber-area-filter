package com.ruan.uberareafilter.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import android.graphics.Path
import android.graphics.Rect
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ruan.uberareafilter.adb.AdbPairingReceiver
import com.ruan.uberareafilter.adb.StandaloneAdbBridge
import com.ruan.uberareafilter.driver.AppCaptureRouter
import com.ruan.uberareafilter.driver.CaptureMode
import com.ruan.uberareafilter.engine.AddressParser
import com.ruan.uberareafilter.engine.AreaEvaluator
import com.ruan.uberareafilter.engine.BlockedMatcher
import com.ruan.uberareafilter.engine.ChainPlanner
import com.ruan.uberareafilter.engine.CounterOfferFlow
import com.ruan.uberareafilter.engine.DriverFix
import com.ruan.uberareafilter.engine.DriverPresence
import com.ruan.uberareafilter.engine.OfferSignature
import com.ruan.uberareafilter.engine.Decision
import com.ruan.uberareafilter.engine.DualRideCoordinator
import com.ruan.uberareafilter.engine.FuelCalculator
import com.ruan.uberareafilter.engine.NegotiationAdvisor
import com.ruan.uberareafilter.engine.TrafficCheck
import com.ruan.uberareafilter.map.TrafficRouteClient
import com.ruan.uberareafilter.engine.ParsedRideOffer
import com.ruan.uberareafilter.engine.RideLedgerTracker
import com.ruan.uberareafilter.engine.RideProgress
import com.ruan.uberareafilter.storage.EarningsStore
import com.ruan.uberareafilter.model.FavoriteRideItem
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.model.RideHistoryItem
import com.ruan.uberareafilter.model.RunState
import com.ruan.uberareafilter.overlay.OverlayManager
import com.ruan.uberareafilter.overlay.RideCardModel
import com.ruan.uberareafilter.shizuku.ShizukuTapBridge
import com.ruan.uberareafilter.setup.SetupChecks
import com.ruan.uberareafilter.storage.ConfigRepository
import com.ruan.uberareafilter.ui.FavoritesActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Servico de acessibilidade principal:
 * - Leitura hibrida rapida (Acessibilidade + OCR ML Kit + Deteccao Visual de Pixels no Bitmap)
 * - Varredura Multi-Alvo para fechar ofertas em 100% dos formatos (circulo X no topo 6.3%, pilula ou circulo X em 37.8%)
 * - Deteccao blindada de janela ativa da 99 (nao perde o foco quando notificacoes/animacoes Flutter ocorrem)
 * - Auto-clique imediato em "Continuar aceitando solicitacoes"
 * - Botao ❤️ Favoritar na tela de 5 estrelas ("Como foi sua corrida com Camila?")
 * - Alertas automaticos 30, 20 e 10 minutos antes do horario de passageiros favoritos
 */
class UberAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var configRepository: ConfigRepository
    private lateinit var evaluator: AreaEvaluator
    private lateinit var overlayManager: OverlayManager
    private lateinit var ledger: RideLedgerTracker
    private lateinit var chainPlanner: ChainPlanner

    // "Aceitar se tocar de novo" (botao do card): corridas liberadas por voce, valem 20 min
    private data class AcceptOverride(val sig: OfferSignature, val until: Long)
    private val acceptOverrides = mutableListOf<AcceptOverride>()
    private var lastBlockedTermsKey: String = ""
    // Ultima decisao (vai junto na marca "🐞 Algo deu errado")
    private var lastDecisionSummary: String = ""
    private val lastPresenceAt = HashMap<String, Long>()
    private val lastPresenceValue = HashMap<String, Boolean>()

    // Contadores para o log [STATUS] (a cada 10 min)
    private var statFrames = 0
    private var statOcrMsTotal = 0L
    private var statOffers = 0
    private var statUnmatched = 0
    private var statLastAt = 0L
    private val dualCoordinator = DualRideCoordinator()
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private var scannerLoopJob: Job? = null
    private var currentForegroundPkg: String = ""
    private var lastLoggedPkg: String = ""
    private var lastLoggedMonitored: Boolean? = null
    private var lastLoggedModeLabel: String = ""
    private var lastFingerprint: String = ""
    /** Negocia em que so o preco atrapalha: o app nao toca, entao nao precisa reavaliar a cada leitura. */
    private var lastNegotiateOnlyFingerprint: String = ""
    /** Contraproposta da Negocia em andamento (lapis -> valor -> Confirmar oferta). */
    private var counterJob: CounterOfferFlow.Job? = null
    private var lastManualEditorLogAt: Long = 0L
    private var lastActiveRideScreenAt = 0L
    private var lastEvaluatedAt: Long = 0L
    private var lastActionAt: Long = 0L
    private val lastActionAtByApp = mutableMapOf<String, Long>()
    private var lastRejectOfferFingerprint: String = ""
    private var lastRejectOfferAttempts: Int = 0
    private var lastRejectOfferAt: Long = 0L
    private var lastLoggedNodeOffer: String = ""
    private var lastUberOfferSeenAt: Long = 0L
    private var lastOfferSeenAt: Long = 0L
    private var lastMonitoredConfirmedAt: Long = 0L
    private var lastUberBackgroundProbeUntil: Long = 0L
    private var activeBannerApp: String = ""
    private var activeBannerPrice: Double? = null
    private var activeBannerPickup: String = ""
    private var activeBannerDropoff: String = ""
    private var activeBannerShownAt: Long = 0L
    private var lastContinueClickAt: Long = 0L
    private var lastHeartShownAt: Long = 0L
    private var lastFavoriteAlertCheckAt: Long = 0L
    private var lastAdbWatchCheckAt: Long = 0L
    private var lastUberEventAt: Long = 0L
    private var lastUnmatchedDebugAt: Long = 0L
    private var lastXrayWinEventAt: Long = 0L
    private var lastXrayOfferFingerprint: String = ""
    private var lastSavedShotFingerprint: String = ""
    private var lastXrayCycleKey: String = ""
    private var lastXrayCycleAt: Long = 0L
    private val sentFavoriteAlerts = mutableSetOf<String>()

    private data class VisibleDriverState(
        val isMonitored: Boolean,
        val resolvedPkg: String,
        val preferredAppSource: String?,
        val uberWindowBounds: Rect?,
        val hasFullscreenNonDriverApp: Boolean
    )

    // Somente apps de MOTORISTA (evita monitorar com.ubercab passageiro, com.taxis99 passageiro, uberchip, digio.uber...)
    private val nineNinePkgKeywords = listOf("com.app99.driver", "com.didiglobal.driver", "com.didi.driver")
    private val uberPkgKeywords = listOf("com.ubercab.driver")
    private val debugExtraPkgKeywords = listOf("photos", "gallery", "galeria")
    private val recorderOverlayKeywords = listOf("screenrecorder", "xrecorder", "azrecorder", "mobizen", "du.recorder")
    private val pipMediaKeywords = listOf("youtube", "netflix", "twitch", "primevideo", "disney", "pip", "vlc")
    private val ignoredTransientPackages = setOf(
        "android",
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.google.android.gms",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        offRequested = false
        configRepository = ConfigRepository(applicationContext)
        ledger = RideLedgerTracker(EarningsStore.get(applicationContext)) { log(it) }
        ledger.restoreOpenRides(System.currentTimeMillis())
        OffCountdownOverlay.hide(applicationContext, "filtro religado")
        // Religado (bloco rapido, app ou Configuracoes): volta para ON
        if (!configRepository.load().isEnabled) configRepository.setRunStateQuick(RunState.ON)
        FilterTileService.requestRefresh(applicationContext)
        evaluator = AreaEvaluator(applicationContext)
        chainPlanner = ChainPlanner { address -> evaluator.pointOf(address, configRepository.load()) }
        overlayManager = OverlayManager(this)
        ShizukuTapBridge.init(applicationContext)
        ensureNotificationChannel()
        val mode = CaptureMode.from(configRepository.load())
        lastLoggedModeLabel = mode.label
        // O status do motor interno faz PING por socket: fora da thread principal, senao o Android
        // bloqueia e o log mostrava "nao pareado/desligado" mesmo com o motor rodando.
        serviceScope.launch(Dispatchers.IO) {
            val appCtx = applicationContext
            log(
                "Servico iniciado (Modo=${mode.label} | Motor interno: ${StandaloneAdbBridge.statusLabel(appCtx)} | " +
                    "Shizuku: ${ShizukuTapBridge.statusLabel(appCtx)})."
            )
            if (StandaloneAdbBridge.isPaired(appCtx) && !StandaloneAdbBridge.isDaemonAlive(appCtx)) {
                val res = StandaloneAdbBridge.connectAndStartDaemon(appCtx)
                if (res.outcome == StandaloneAdbBridge.ConnectOutcome.CONNECTED) {
                    log("✔ ${res.detail}")
                }
            }
        }
        installCrashLogger()
        logSessionHeader()
        logPreviousExitReasons()
        startPeriodicScanner()
    }

    /** Cabecalho da sessao: versao, aparelho e TODAS as regras ativas (para reproduzir bugs pelo log). */
    private fun logSessionHeader() {
        val cfg = configRepository.load()
        lastConfigSummary = cfg.debugSummary()
        val version = runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        }.getOrDefault("?")
        log(
            "=== SESSAO v$version • ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} • " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) • " +
                "GPS=${DriverLocationProvider.current(applicationContext) != null} ==="
        )
        log("[CONFIG] " + lastConfigSummary.entries.joinToString(" | ") { "${it.key}=${it.value}" })
    }

    /** A cada 10 min: bateria, leituras, tempo medio de OCR, ofertas e corridas abertas (para achar gargalos). */
    private fun maybeLogStatus(now: Long, config: FilterConfig, monitored: Boolean) {
        if (statLastAt == 0L) { statLastAt = now; return }
        if (now - statLastAt < 10 * 60_000L) return
        val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging ?: false
        val avgOcr = if (statFrames > 0) statOcrMsTotal / statFrames else 0
        val rt = Runtime.getRuntime()
        val memMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        // Celular no suporte, no sol: o Android segura o processador e a leitura fica lenta
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val heat = when (pm?.currentThermalStatus) {
            android.os.PowerManager.THERMAL_STATUS_NONE -> "normal"
            android.os.PowerManager.THERMAL_STATUS_LIGHT -> "morno"
            android.os.PowerManager.THERMAL_STATUS_MODERATE -> "QUENTE"
            android.os.PowerManager.THERMAL_STATUS_SEVERE -> "MUITO QUENTE"
            null -> "?"
            else -> "CRÍTICO"
        }
        val screenOn = pm?.isInteractive ?: true
        log(
            "[STATUS] ${(now - statLastAt) / 60000} min: leituras=$statFrames (OCR médio ${avgOcr}ms) • ofertas avaliadas=$statOffers • " +
                "telas sem oferta=$statUnmatched • bateria=$pct%${if (charging) " carregando" else ""} • calor=$heat • " +
                "tela=${if (screenOn) "ligada" else "DESLIGADA"} • memória=${memMb}MB • " +
                "estado=${config.runState} • app motorista na frente=$monitored • corrida aberta=${ledger.hasOpenRide()}"
        )
        statFrames = 0; statOcrMsTotal = 0L; statOffers = 0; statUnmatched = 0; statLastAt = now
    }

    /** Registra so o que mudou nas regras (ex.: "autoRecusa: true -> false"). */
    private fun logConfigChanges(cfg: FilterConfig) {
        val now = cfg.debugSummary()
        val old = lastConfigSummary
        lastConfigSummary = now
        if (old.isEmpty()) return
        val diff = now.filter { (k, v) -> old[k] != v }.map { (k, v) -> "$k: ${old[k]} -> $v" }
        if (diff.isNotEmpty()) log("[CONFIG-MUDOU] " + diff.joinToString(" | "))
    }

    /**
     * Nome novo na lista de bloqueados (ex.: "caxias shopping"): procura o local no mapa uma vez para
     * bloquear tambem as entradas/nomes diferentes do mesmo lugar (rua + numero + raio de 150 m).
     */
    private fun maybeResolveBlockedTerms(config: FilterConfig) {
        val key = config.blockedKeywords.joinToString("|").lowercase()
        if (key == lastBlockedTermsKey) return
        lastBlockedTermsKey = key
        val pending = config.blockedKeywords.filter { t ->
            t.trim().length >= 4 &&
                !configRepository.isBlockedTermChecked(t) &&
                BlockedMatcher.addressKeyword(t) == null &&
                AddressParser.parse(t).street == null
        }
        if (pending.isEmpty()) return
        serviceScope.launch(Dispatchers.IO) {
            for (t in pending) {
                val place = runCatching { evaluator.resolveBlockedTerm(t, config) }.getOrNull()
                configRepository.markBlockedTermChecked(t)
                if (place != null) {
                    configRepository.addBlockedPlaces(listOf(place)).forEach { p ->
                        log("[BLOQUEIO] Achei '${p.term}' no mapa: ${p.label}. Entradas com outro nome a até 150 m também ficam bloqueadas.")
                    }
                } else {
                    log("[BLOQUEIO] '${t.trim()}' não é um local com endereço no mapa (bairro/rua?): continua bloqueando pelo nome")
                }
            }
        }
    }

    /** Travamento: grava a pilha no log antes do Android encerrar o app. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashLogHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogHandler(previous) { t, e ->
            runCatching {
                log("💥 TRAVAMENTO na thread '${t.name}': ${e.javaClass.simpleName}: ${e.message}\n" +
                    e.stackTraceToString().lines().take(25).joinToString("\n"))
            }
        })
    }

    private class CrashLogHandler(
        private val next: Thread.UncaughtExceptionHandler?,
        private val onCrash: (Thread, Throwable) -> Unit
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            onCrash(t, e)
            next?.uncaughtException(t, e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        val pkg = event.packageName?.toString()?.lowercase().orEmpty()
        val config = configRepository.load()
        val now = System.currentTimeMillis()

        // 1. Mudanca na lista global de janelas (captura instantaneamente pop-up da Uber em 2o plano / SYSTEM_ALERT_WINDOW)
        if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val state = resolveVisibleDriverState(config, applyAntiOscillationHold = true)
            if (state.preferredAppSource == "UBER" && state.uberWindowBounds != null) {
                lastUberEventAt = now
                lastUberBackgroundProbeUntil = maxOf(lastUberBackgroundProbeUntil, now + 6000L)
                if (config.debugMode && config.xrayDiagnosticEnabled && now - lastXrayWinEventAt > 4000L) {
                    lastXrayWinEventAt = now
                    log("[RAIO-X WIN-EVENT] ev=WINDOWS_CHANGED | ${buildWindowsXraySummary()}")
                }
            }
            if (state.isMonitored) {
                currentForegroundPkg = state.resolvedPkg
            }
            syncFloatingPill(config, state.isMonitored)
            return
        }

        if (pkg.isEmpty() || pkg == packageName.lowercase() || pkg in ignoredTransientPackages || pkg.contains("inputmethod")) {
            return
        }

        // Gravadores de tela flutuantes (XRecorder) e janelas PiP nao podem oscilar o foco nem derrubar o card
        if (isRecorderOrFloatingPipEvent(pkg)) {
            return
        }

        // 2. Sinais da Uber (1o plano ou popup em 2o plano sobre launcher/WhatsApp/99): abre sonda silenciosa sem piscar o pill
        if (config.uberModuleEnabled && uberPkgKeywords.any { pkg.contains(it) }) {
            lastUberEventAt = now
            lastUberBackgroundProbeUntil = maxOf(lastUberBackgroundProbeUntil, now + 7000L)
            if (config.debugMode && config.xrayDiagnosticEnabled && now - lastXrayWinEventAt > 4000L) {
                lastXrayWinEventAt = now
                log("[RAIO-X WIN-EVENT] ev=$eventType pkg=$pkg | ${buildWindowsXraySummary()}")
            }
        }

        // Se o modulo Uber estiver desligado (modo 99_EXCLUSIVO), ignora completamente eventos da Uber
        val isEnabledDriverPkg = isTargetPackage(pkg, config)
        if (eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            (isEnabledDriverPkg || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        ) {
            val state = resolveVisibleDriverState(config, applyAntiOscillationHold = true)
            val resolvedPkg = state.resolvedPkg.ifEmpty { pkg }
            if (state.isMonitored || state.hasFullscreenNonDriverApp || isEnabledDriverPkg) {
                currentForegroundPkg = resolvedPkg
            }
            syncFloatingPill(config, state.isMonitored)

            if (config.debugMode && (resolvedPkg != lastLoggedPkg || state.isMonitored != lastLoggedMonitored)) {
                lastLoggedPkg = resolvedPkg
                lastLoggedMonitored = state.isMonitored
                log("App em tela: $resolvedPkg (${if (state.isMonitored) "MONITORADO" else "ignorado"})")
            }
        }
    }

    private fun isRecorderOrFloatingPipEvent(pkg: String): Boolean {
        if (recorderOverlayKeywords.any { pkg.contains(it) }) {
            return true
        }
        if (pipMediaKeywords.any { pkg.contains(it) }) {
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val winRect = Rect()
            var hasFullscreenWindow = false
            try {
                windows?.forEach { win ->
                    val wPkg = win.root?.packageName?.toString()?.lowercase().orEmpty()
                    if (wPkg == pkg) {
                        win.getBoundsInScreen(winRect)
                        if (winRect.height() >= (screenH * 0.72f).toInt() && winRect.width() >= (screenW * 0.85f).toInt()) {
                            hasFullscreenWindow = true
                        }
                    }
                }
            } catch (_: Exception) {}
            if (!hasFullscreenWindow) return true
        }
        return false
    }

    private fun isUberWindowMatch(win: AccessibilityWindowInfo): Boolean {
        val wPkg = win.root?.packageName?.toString()?.lowercase().orEmpty()
        val wTitle = win.title?.toString()?.lowercase().orEmpty()
        return uberPkgKeywords.any { wPkg.contains(it) } ||
            (wPkg.isEmpty() && wTitle.contains("uber"))
    }

    private fun isNineNineWindowMatch(win: AccessibilityWindowInfo): Boolean {
        val wPkg = win.root?.packageName?.toString()?.lowercase().orEmpty()
        return nineNinePkgKeywords.any { wPkg.contains(it) }
    }

    private fun isFloatingUtilityPackage(pkg: String, rect: Rect, screenW: Int, screenH: Int, winType: Int): Boolean {
        if (pkg in ignoredTransientPackages || pkg.contains("inputmethod")) return true
        if (winType == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || winType == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true
        if (recorderOverlayKeywords.any { pkg.contains(it) }) return true
        if (pipMediaKeywords.any { pkg.contains(it) } &&
            (rect.height() < (screenH * 0.72f).toInt() || rect.width() < (screenW * 0.85f).toInt())
        ) {
            return true
        }
        return false
    }

    /**
     * Fonte unica de verdade do estado visivel em tela usando Z-order real (win.layer decrescente):
     * - Em modo exclusivo (SINGLE_99 ou SINGLE_UBER), ignora completamente o outro app de corrida.
     * - Em modo simultaneo (DUAL), compara a camada visual real (win.layer) para nao oscilar nem deixar
     *   janelas pausadas da Uber/99 em segundo plano roubarem o foco.
     * - Ignora bolhas pequenas (< 18% da altura), gravador XRecorder e YouTube em PiP.
     */
    private fun resolveVisibleDriverState(
        config: FilterConfig,
        applyAntiOscillationHold: Boolean = true
    ): VisibleDriverState {
        val mode = CaptureMode.from(config)
        if (mode == CaptureMode.NONE) {
            return VisibleDriverState(false, "", null, null, false)
        }

        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val minCardWindowHeight = (screenH * 0.18f).toInt()
        val fullscreenHeight = (screenH * 0.72f).toInt()
        val now = System.currentTimeMillis()
        val ownPkg = packageName.lowercase()

        var topUberRect: Rect? = null
        var topUberLayer = Int.MIN_VALUE
        var top99Pkg = ""
        var top99Layer = Int.MIN_VALUE
        var topOtherFullscreenPkg = ""
        var topOtherFullscreenLayer = Int.MIN_VALUE
        var topOwnSettingsLayer = Int.MIN_VALUE

        try {
            val activeWindows = windows?.sortedByDescending { it.layer }.orEmpty()
            val rect = Rect()
            for (win in activeWindows) {
                val wPkg = win.root?.packageName?.toString()?.lowercase().orEmpty()
                win.getBoundsInScreen(rect)

                if (wPkg == ownPkg) {
                    if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        rect.height() >= fullscreenHeight &&
                        win.layer > topOwnSettingsLayer
                    ) {
                        topOwnSettingsLayer = win.layer
                    }
                    continue
                }

                if (isFloatingUtilityPackage(wPkg, rect, screenW, screenH, win.type)) {
                    continue
                }

                val isUberWin = (mode == CaptureMode.DUAL || mode == CaptureMode.SINGLE_UBER) &&
                    rect.height() >= minCardWindowHeight &&
                    isUberWindowMatch(win)
                val is99Win = (mode == CaptureMode.DUAL || mode == CaptureMode.SINGLE_99) &&
                    rect.height() >= minCardWindowHeight &&
                    isNineNineWindowMatch(win)

                if (isUberWin) {
                    val existingH = topUberRect?.height() ?: Int.MAX_VALUE
                    val candidateIsOverlay = rect.height() < fullscreenHeight
                    val existingIsFullscreen = topUberRect != null && existingH >= fullscreenHeight
                    if (topUberRect == null || win.layer > topUberLayer || (win.layer == topUberLayer && candidateIsOverlay && existingIsFullscreen)) {
                        topUberRect = Rect(rect)
                        topUberLayer = win.layer
                    }
                } else if (is99Win) {
                    if (win.layer > top99Layer) {
                        top99Layer = win.layer
                        top99Pkg = wPkg.ifEmpty { "com.app99.driver" }
                    }
                } else if (config.debugMode && debugExtraPkgKeywords.any { wPkg.contains(it) } && rect.height() >= minCardWindowHeight) {
                    if (win.layer > top99Layer) {
                        top99Layer = win.layer
                        top99Pkg = wPkg
                    }
                } else if (wPkg.isNotEmpty() && rect.height() >= fullscreenHeight && rect.width() >= (screenW * 0.80f).toInt()) {
                    if (win.layer > topOtherFullscreenLayer) {
                        topOtherFullscreenLayer = win.layer
                        topOtherFullscreenPkg = wPkg
                    }
                }
            }
        } catch (_: Exception) {}

        val topDriverLayer = maxOf(topUberLayer, top99Layer)
        // Se a nossa propria tela de configuracao esta em tela cheia na frente e nao ha card Uber por cima, pausa captura
        if (topOwnSettingsLayer >= topDriverLayer && topOwnSettingsLayer > Int.MIN_VALUE) {
            return VisibleDriverState(
                isMonitored = false,
                resolvedPkg = ownPkg,
                preferredAppSource = null,
                uberWindowBounds = null,
                hasFullscreenNonDriverApp = true
            )
        }

        // Verifica se outro app em tela cheia (ex: Launcher, WhatsApp, Chrome, Settings) esta ACIMA dos apps de corrida
        val nonDriverOnTop = topOtherFullscreenLayer > topDriverLayer && topOtherFullscreenLayer > Int.MIN_VALUE
        val hasFullscreenNonDriver = nonDriverOnTop || topOwnSettingsLayer > topDriverLayer

        if (!hasFullscreenNonDriver) {
            if (topUberRect != null && (top99Layer == Int.MIN_VALUE || topUberLayer >= top99Layer)) {
                lastMonitoredConfirmedAt = now
                return VisibleDriverState(
                    isMonitored = true,
                    resolvedPkg = "com.ubercab.driver",
                    preferredAppSource = "UBER",
                    uberWindowBounds = topUberRect,
                    hasFullscreenNonDriverApp = false
                )
            }
            if (top99Layer > Int.MIN_VALUE) {
                lastMonitoredConfirmedAt = now
                return VisibleDriverState(
                    isMonitored = true,
                    resolvedPkg = top99Pkg,
                    preferredAppSource = "99",
                    uberWindowBounds = null,
                    hasFullscreenNonDriverApp = false
                )
            }
        }

        // Fallback via rootInActiveWindow quando windows estiver vazio/em transicao
        val rootPkg = rootInActiveWindow?.packageName?.toString()?.lowercase().orEmpty()
        if (rootPkg == ownPkg) {
            return VisibleDriverState(false, ownPkg, null, null, true)
        }
        if (rootPkg.isNotEmpty() &&
            !hasFullscreenNonDriver &&
            rootPkg !in ignoredTransientPackages &&
            !rootPkg.contains("inputmethod") &&
            !recorderOverlayKeywords.any { rootPkg.contains(it) } &&
            !pipMediaKeywords.any { rootPkg.contains(it) }
        ) {
            if (isTargetPackage(rootPkg, config)) {
                val src = if (uberPkgKeywords.any { rootPkg.contains(it) }) "UBER" else "99"
                lastMonitoredConfirmedAt = now
                return VisibleDriverState(true, rootPkg, src, null, false)
            }
        }

        // Hold curto anti-oscilacao (1,2s) caso nao haja app nao-monitorado cobrindo em tela cheia
        if (applyAntiOscillationHold &&
            !hasFullscreenNonDriver &&
            topOtherFullscreenPkg.isEmpty() &&
            now - lastMonitoredConfirmedAt < 1200L &&
            isTargetPackage(currentForegroundPkg, config)
        ) {
            val src = if (uberPkgKeywords.any { currentForegroundPkg.contains(it) }) "UBER" else "99"
            return VisibleDriverState(true, currentForegroundPkg, src, null, false)
        }

        val nonDriverPkg = topOtherFullscreenPkg.ifEmpty { rootPkg }
        return VisibleDriverState(
            isMonitored = false,
            resolvedPkg = nonDriverPkg,
            preferredAppSource = null,
            uberWindowBounds = null,
            hasFullscreenNonDriverApp = hasFullscreenNonDriver || topOtherFullscreenPkg.isNotEmpty()
        )
    }

    private fun detectScreenWindowContext(config: FilterConfig): Pair<String?, Rect?> {
        val state = resolveVisibleDriverState(config, applyAntiOscillationHold = true)
        return state.preferredAppSource to state.uberWindowBounds
    }

    /** App de motorista na frente, para as cores do botao: 99 = amarelo, Uber = preto. */
    private fun floatingBrand(config: FilterConfig): String {
        val pkg = currentForegroundPkg.lowercase()
        return when {
            uberPkgKeywords.any { pkg.contains(it) } -> "UBER"
            pkg.contains("99") -> "99"
            config.nineNineModuleEnabled && !config.uberModuleEnabled -> "99"
            else -> "UBER"
        }
    }

    /** Toque no icone da ampulheta: abre o Filtro Inteligente (sem passar pelas configuracoes do Android). */
    private fun openMainApp() {
        runCatching {
            val intent = Intent(this, com.ruan.uberareafilter.ui.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
            log("[BOTAO] Abri o app pelo ícone do botão flutuante")
        }.onFailure { log("[BOTAO] ⚠ Falha ao abrir o app: ${it.message}") }
    }

    private fun syncFloatingPill(config: FilterConfig, isMonitoredApp: Boolean) {
        if (!isMonitoredApp) {
            overlayManager.hideBanner()
        }
        val activeLock = if (dualCoordinator.isActive(config)) dualCoordinator.primaryRide else null
        val lockBadgeText = activeLock?.let { lock ->
            val queueSuffix = if (dualCoordinator.queuedRide != null) " +1" else ""
            "🔒 ${lock.appSource}$queueSuffix ✕"
        }
        overlayManager.updateFloatingControl(
            visible = config.floatingButtonEnabled && isMonitoredApp,
            runState = config.runState,
            onToggleFilter = {
                // Ciclo de 3 toques: ON -> PAUSA (so le) -> OFF (desliga a leitura de tela)
                val latest = configRepository.load()
                when (latest.runState) {
                    RunState.ON -> {
                        configRepository.setRunStateQuick(RunState.PAUSED)
                        overlayManager.hideBanner()
                        syncFloatingPill(latest.copy(actionsPaused = true), true)
                        log("Filtro em PAUSA pelo botao flutuante: so leitura, sem aceitar/recusar")
                    }
                    RunState.PAUSED -> turnOffScreenReading("botao flutuante")
                    RunState.OFF -> {
                        configRepository.setRunStateQuick(RunState.ON)
                        syncFloatingPill(latest.copy(isEnabled = true, actionsPaused = false), true)
                        log("Filtro LIGADO pelo botao flutuante")
                    }
                }
            },
            onLongPressFavorite = {
                triggerHeartPromptManualOrRating(passengerName = null)
            },
            rideLockBadgeText = lockBadgeText,
            brand = floatingBrand(config),
            onOpenApp = { openMainApp() },
            onReleaseRideLock = {
                val rel = dualCoordinator.releaseManually()
                if (rel != null) {
                    lastFingerprint = ""
                    lastSavedShotFingerprint = ""
                    log(rel.message)
                    val latestCfg = configRepository.load()
                    syncFloatingPill(latestCfg, true)
                    overlayManager.showRideStatus(
                        decision = Decision.INSIDE_AREA,
                        headline = "Trava liberada",
                        detailLine = rel.message,
                        autoHideMs = 3000L
                    )
                }
            }
        )
    }

    /**
     * OFF: desliga o proprio servico de Acessibilidade (apps de banco deixam de reclamar).
     * Para religar: bloco "Filtro" nas Configuracoes rapidas ou o botao ligar dentro do app.
     */
    private fun turnOffScreenReading(origin: String) {
        if (offRequested) return
        offRequested = true
        configRepository.setRunStateQuick(RunState.OFF)
        log("Filtro DESLIGADO ($origin): leitura de tela desligada. Religue pelo bloco 'Filtro' das Configuracoes rapidas ou pelo app.")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(
                    applicationContext,
                    "Filtro desligado (leitura de tela OFF). Para religar: bloco \"Filtro\" nas Configurações rápidas ou abra o app.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            val pillPos = runCatching { overlayManager.floatingPillPosition() }.getOrNull()
            runCatching { overlayManager.hideAll() }
            runCatching { ledger.flush() }
            val cfgOff = configRepository.load()
            if (cfgOff.floatingButtonEnabled) {
                OffCountdownOverlay.show(applicationContext, cfgOff.offButtonMinutes, pillPos?.first, pillPos?.second)
            }
            FilterTileService.requestRefresh(applicationContext)
            disableSelf()
        }
    }

    private fun startPeriodicScanner() {
        scannerLoopJob?.cancel()
        scannerLoopJob = serviceScope.launch {
            while (isActive) {
                val nextDelayMs = 780L
                try {
                    val config = configRepository.load()
                    if (!config.isEnabled) {
                        // Desligado pela tela do app: solta a Acessibilidade de verdade
                        turnOffScreenReading("app")
                        break
                    }
                    val now = System.currentTimeMillis()
                    logConfigChanges(config)
                    maybeResolveBlockedTerms(config)
                    val mode = CaptureMode.from(config)
                    if (!dualCoordinator.isActive(config) && (dualCoordinator.primaryRide != null || dualCoordinator.queuedRide != null)) {
                        dualCoordinator.reset()
                    }
                    if (mode.label != lastLoggedModeLabel) {
                        lastLoggedModeLabel = mode.label
                        log("Modo de captura atualizado: ${mode.label}")
                    }

                    if (config.favoriteAlertsEnabled && now - lastFavoriteAlertCheckAt > 25_000L) {
                        lastFavoriteAlertCheckAt = now
                        checkUpcomingFavoriteAlerts()
                    }

                    if (now - lastAdbWatchCheckAt > 5_000L) {
                        lastAdbWatchCheckAt = now
                        maybeAutoWatchStandaloneAdb(config)
                    }

                    val state = resolveVisibleDriverState(config, applyAntiOscillationHold = true)
                    val ownPkg = packageName.lowercase()
                    val inOwnSettings = rootInActiveWindow?.packageName?.toString()?.lowercase() == ownPkg ||
                        state.resolvedPkg == ownPkg

                    val hasQuietUberProbe = !inOwnSettings &&
                        config.uberModuleEnabled &&
                        (now <= lastUberBackgroundProbeUntil || now - lastUberOfferSeenAt < 3000L)

                    if (state.isMonitored && state.resolvedPkg.isNotEmpty()) {
                        currentForegroundPkg = state.resolvedPkg
                    }
                    // App em primeiro plano mudou (99 / Uber / outro): ajuda a entender "por que nao leu"
                    val fgKey = if (state.isMonitored) state.resolvedPkg else "outro:${state.resolvedPkg}"
                    if (fgKey != lastLoggedForeground) {
                        lastLoggedForeground = fgKey
                        val desc = if (state.isMonitored) state.resolvedPkg + " (monitorado)" else state.resolvedPkg.ifEmpty { "?" } + " (ignorado)"
                        log("[APP] Primeiro plano: $desc")
                    }
                    syncFloatingPill(config, state.isMonitored)
                    ledger.tickWork(now, config.runState)
                    maybeLogStatus(now, config, state.isMonitored)

                    if (config.isEnabled && (state.isMonitored || hasQuietUberProbe)) {
                        scanActiveScreen(
                            config = config,
                            visibleState = state,
                            quietProbeOnly = !state.isMonitored
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no scanner", e)
                    val t = System.currentTimeMillis()
                    if (t - lastScannerErrorLogAt > 10_000L) {
                        lastScannerErrorLogAt = t
                        val where = e.stackTrace.take(4).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
                        log("⚠ ERRO no leitor: ${e.javaClass.simpleName}: ${e.message} @ $where")
                    }
                }
                delay(nextDelayMs)
            }
        }
    }

    private fun isTargetPackage(pkg: String, config: FilterConfig): Boolean {
        if (pkg.isEmpty() || pkg == packageName.lowercase()) return false
        if (config.nineNineModuleEnabled && nineNinePkgKeywords.any { pkg.contains(it) }) return true
        if (config.uberModuleEnabled && uberPkgKeywords.any { pkg.contains(it) }) return true
        if (config.debugMode && debugExtraPkgKeywords.any { pkg.contains(it) }) return true
        return false
    }

    private suspend fun scanActiveScreen(
        config: FilterConfig,
        visibleState: VisibleDriverState,
        quietProbeOnly: Boolean = false
    ) {
        // Usa exclusivamente takeScreenshot + ML Kit OCR + Roteador Modular (99 / Uber) + Inspecao Visual de Pixels
        val frameStartedAt = System.currentTimeMillis()
        val bitmap = captureScreenBitmap() ?: return
        val visionText = recognizeText(bitmap)
        statFrames++
        statOcrMsTotal += System.currentTimeMillis() - frameStartedAt
        val width = bitmap.width
        val height = bitmap.height

        if (visionText == null) {
            bitmap.recycle()
            return
        }

        // "Voce esta online/offline": o painel de Ganhos so conta horas com voce online
        if (!quietProbeOnly && visibleState.isMonitored) {
            val lines = visionText.textBlocks.flatMap { it.lines }.map { it.text }
            DriverPresence.detect(lines)?.let { online ->
                val app = visibleState.preferredAppSource
                    ?: if (visibleState.resolvedPkg.contains("ubercab")) "UBER" else "99"
                val nowP = System.currentTimeMillis()
                if (lastPresenceValue[app] != online || nowP - (lastPresenceAt[app] ?: 0L) > 30_000L) {
                    lastPresenceValue[app] = online
                    lastPresenceAt[app] = nowP
                    ledger.onPresence(app, online, nowP)
                }
            }
        }

        val ocrParsed = AppCaptureRouter.parseActiveScreen(
            visionText = visionText,
            bitmap = bitmap,
            screenWidth = width,
            screenHeight = height,
            config = config,
            preferredAppSource = visibleState.preferredAppSource,
            uberWindowBounds = visibleState.uberWindowBounds
        )
        // Captura silenciosa do print EXATO do momento em que a corrida aparece (antes do banner/recusa)
        val pendingShotBytes = if (
            ocrParsed != null &&
            ocrParsed.isRideOffer &&
            config.saveRideScreenshotsEnabled &&
            ocrParsed.fingerprint != lastFingerprint &&
            ocrParsed.fingerprint != lastSavedShotFingerprint
        ) {
            encodeRideScreenshotJpeg(bitmap)
        } else {
            null
        }
        bitmap.recycle()
        if (ocrParsed == null) {
            statUnmatched++
            if (!quietProbeOnly) advanceCounterOfferJob(System.currentTimeMillis())
            if (activeBannerApp.isNotEmpty() && System.currentTimeMillis() - lastOfferSeenAt > 2500L) {
                resetActiveBannerOffer()
            }
            val now = System.currentTimeMillis()
            if (config.debugMode && now - lastUnmatchedDebugAt > 3500L) {
                val snippet = visionText.textBlocks.flatMap { it.lines }
                    .map { it.text.trim() }
                    .filter { it.isNotEmpty() }
                val lower = snippet.joinToString(" | ").lowercase()
                val hasOfferLook = lower.contains("tenho interesse") || lower.contains("exclusivo") ||
                    (lower.contains("r$") && (lower.contains("min") || lower.contains("aceitar") || lower.contains("km")))
                val hasXrayLifecycleLook = config.xrayDiagnosticEnabled && (
                    lower.contains("cancel") || lower.contains("finalizar") || lower.contains("cheguei") ||
                        lower.contains("aguardando") || lower.contains("iniciar viagem") ||
                        lower.contains("iniciar corrida") || lower.contains("em viagem") ||
                        lower.contains("offline") || lower.contains("off-line")
                    )
                val shouldLogUnmatched = (!quietProbeOnly && (hasOfferLook || hasXrayLifecycleLook)) ||
                    (quietProbeOnly && config.xrayDiagnosticEnabled && (hasOfferLook || lower.contains("cancel") || lower.contains("uber")))
                if (shouldLogUnmatched) {
                    lastUnmatchedDebugAt = now
                    log("OCR [NAO-RECONHECIDO] (probe=$quietProbeOnly, modo=${CaptureMode.from(config).label}, prefer=${visibleState.preferredAppSource ?: "N/A"}, fg=$currentForegroundPkg): ${snippet.take(10).joinToString(" | ")}")
                    if (config.xrayDiagnosticEnabled) {
                        log("[RAIO-X WIN-NAO-CASADO] ${buildWindowsXraySummary()}")
                        log("[RAIO-X OCR-NAO-CASADO] ${buildOcrXrayLines(visionText, height)}")
                    }
                }
            }
            return
        }

        if (ocrParsed.isRideOffer) {
            val now = System.currentTimeMillis()
            lastOfferSeenAt = now
            lastMonitoredConfirmedAt = now
            if (ocrParsed.appSource == "UBER") {
                lastUberOfferSeenAt = now
                if (quietProbeOnly) {
                    lastUberBackgroundProbeUntil = maxOf(lastUberBackgroundProbeUntil, now + 5000L)
                }
            }
        } else if (activeBannerApp.isNotEmpty() && System.currentTimeMillis() - lastOfferSeenAt > 2500L) {
            resetActiveBannerOffer()
        }

        if (config.debugMode && ocrParsed.allTexts.isNotEmpty() && ocrParsed.isRideOffer) {
            val totalKmStr = ocrParsed.totalDistanceKm?.let { String.format(Locale.US, "%.1fkm", it) } ?: "?"
            val pickupKmStr = ocrParsed.pickupDistanceKm?.let { String.format(Locale.US, "%.1fkm", it) } ?: "?"
            val hrStr = ocrParsed.hourlyRate?.let { String.format(Locale.US, "R$%.0f/h", it) } ?: "?"
            val stopsStr = if (ocrParsed.hasMultipleStops) " | Paradas=${ocrParsed.stopsCount}" else ""
            val negStr = if (ocrParsed.isNegotiable) " | NEGOCIA" else ""
            val bgTag = if (quietProbeOnly) " [2o-PLANO]" else ""
            log(
                "OCR [${ocrParsed.appSource}]$bgTag (OFERTA$negStr): R$/km=${ocrParsed.pricePerKm?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A"} | " +
                    "$hrStr | EmbKm=$pickupKmStr | Tot=$totalKmStr$stopsStr | Emb=${ocrParsed.pickupAddress ?: "N/A"} | Dest=${ocrParsed.dropoffAddress ?: "N/A"}"
            )
        }

        val resolvedParsed = if (ocrParsed.appSource == "UBER" && ocrParsed.isRideOffer) {
            attachUberAccessibilityNodes(ocrParsed, width, height, config.debugMode)
        } else {
            ocrParsed
        }

        if (config.debugMode && config.xrayDiagnosticEnabled) {
            if (resolvedParsed.isRideOffer && resolvedParsed.fingerprint != lastXrayOfferFingerprint) {
                lastXrayOfferFingerprint = resolvedParsed.fingerprint
                val rPt = resolvedParsed.rejectTapPoint?.let { "${it.first.toInt()}x${it.second.toInt()}" } ?: "?"
                val aPt = resolvedParsed.acceptTapPoint?.let { "${it.first.toInt()}x${it.second.toInt()}" } ?: "?"
                val bgTag = if (quietProbeOnly) " [2o-PLANO]" else ""
                log("[RAIO-X OFERTA ${resolvedParsed.appSource}]$bgTag X=$rPt Aceitar=$aPt | ${buildWindowsXraySummary()}")
                log("[RAIO-X OCR-OFERTA] ${buildOcrXrayLines(visionText, height)}")
            } else if (!resolvedParsed.isRideOffer) {
                val cycleTag = when {
                    resolvedParsed.isCounterOfferEditor -> "CONTRAPROPOSTA"
                    resolvedParsed.isCancelledScreen -> "CANCELADA"
                    resolvedParsed.isRatingScreen -> "AVALIACAO"
                    resolvedParsed.isInActiveRide -> "EM_VIAGEM"
                    resolvedParsed.isIdleHomeScreen -> "OCIOSO"
                    resolvedParsed.isContinuePrompt -> "CONTINUAR"
                    else -> ""
                }
                if (cycleTag.isNotEmpty()) {
                    val nowX = System.currentTimeMillis()
                    val cycleKey = "${resolvedParsed.appSource}:$cycleTag"
                    val isIdleRepeat = cycleTag == "OCIOSO" && cycleKey == lastXrayCycleKey && dualCoordinator.primaryRide == null
                    if (!isIdleRepeat && (cycleKey != lastXrayCycleKey || nowX - lastXrayCycleAt > 6000L)) {
                        lastXrayCycleKey = cycleKey
                        lastXrayCycleAt = nowX
                        log("[RAIO-X CICLO $cycleKey] ${buildWindowsXraySummary()}")
                        log("[RAIO-X OCR-CICLO] ${buildOcrXrayLines(visionText, height)}")
                    }
                }
            }
        }

        processParsedScreen(
            parsed = resolvedParsed,
            config = config,
            source = "OCR-${resolvedParsed.appSource}",
            pendingShotBytes = pendingShotBytes
        )
    }

    private fun encodeRideScreenshotJpeg(bitmap: Bitmap): ByteArray? {
        return try {
            ByteArrayOutputStream().use { baos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 78, baos)
                baos.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildWindowsXraySummary(): String {
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val rect = Rect()
        val list = try {
            windows?.sortedByDescending { it.layer }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (list.isEmpty()) return "janelas=0"
        return list.mapIndexedNotNull { idx, win ->
            win.getBoundsInScreen(rect)
            if (rect.height() < 40) return@mapIndexedNotNull null
            val pkg = win.root?.packageName?.toString()?.lowercase() ?: "(root-null)"
            val typeStr = when (win.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "SYS"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
                else -> "T${win.type}"
            }
            val pctH = ((rect.height() * 100f) / screenH).toInt()
            val flags = buildString {
                if (win.isActive) append("A")
                if (win.isFocused) append("F")
            }
            val title = win.title?.toString()?.trim()?.take(20)?.let { if (it.isNotEmpty()) " '$it'" else "" } ?: ""
            "#$idx $typeStr $pkg L=${win.layer} $flags [${rect.left},${rect.top}-${rect.right},${rect.bottom}](${pctH}%H)$title"
        }.take(8).joinToString(" | ").ifEmpty { "janelas-pequenas" }
    }

    private fun buildOcrXrayLines(visionText: Text, screenHeight: Int): String {
        val h = screenHeight.coerceAtLeast(1)
        val sorted = visionText.textBlocks
            .flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
        return sorted.mapNotNull { line ->
            val raw = line.text.trim().replace("\n", " ")
            if (raw.isEmpty() || raw.contains("✔") || raw.contains("✘")) return@mapNotNull null
            val box = line.boundingBox
            if (box != null) {
                val pctY = ((box.centerY() * 100f) / h).toInt().coerceIn(0, 100)
                "@Y$pctY%[${box.top}..${box.bottom}]:\"$raw\""
            } else {
                "@Y?:\"$raw\""
            }
        }.take(45).joinToString(" | ") // 45: nomes do mapa nao empurram o card para fora do log
    }

    /**
     * Encontra nos nativos de Acessibilidade na janela ativa da Uber (`com.ubercab.driver`)
     * para clicar diretamente no botao [ X ] ("Fechar"/"Recusar") ou "Tenho interesse"/"Aceitar",
     * alem de refinar a coordenada exata de toque pelos boundsInScreen reais.
     */
    private fun attachUberAccessibilityNodes(
        parsed: ParsedRideOffer,
        screenWidth: Int,
        screenHeight: Int,
        debugMode: Boolean = false
    ): ParsedRideOffer {
        val candidateRoots = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows?.forEach { win ->
                val pkg = win.root?.packageName?.toString()?.lowercase().orEmpty()
                if (uberPkgKeywords.any { pkg.contains(it) }) {
                    win.root?.let { candidateRoots.add(it) }
                }
            }
        } catch (_: Exception) {}
        rootInActiveWindow?.let { root ->
            val pkg = root.packageName?.toString()?.lowercase().orEmpty()
            if (uberPkgKeywords.any { pkg.contains(it) } && candidateRoots.none { it == root }) {
                candidateRoots.add(root)
            }
        }
        if (candidateRoots.isEmpty()) return parsed

        var foundRejectNode: AccessibilityNodeInfo? = null
        var foundRejectExplicitLabel = false
        var foundRejectArea = Long.MAX_VALUE
        var foundRejectCenter: Pair<Float, Float>? = null
        var foundRejectSource = "NONE"
        var foundAcceptNode: AccessibilityNodeInfo? = null
        var foundAcceptCenter: Pair<Float, Float>? = null

        val closeLabels = listOf("fechar", "recusar", "dismiss", "close", "descartar", "ignorar", "cancelar")
        val acceptLabels = listOf("tenho interesse", "aceitar", "confirmar")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        candidateRoots.forEach { queue.add(it) }
        val tmpRect = Rect()
        val topRightCandidates = mutableListOf<String>()
        var visited = 0

        while (queue.isNotEmpty() && visited < 2500) {
            val node = queue.removeFirst()
            visited++

            val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            val txt = node.text?.toString()?.trim()?.lowercase().orEmpty()
            node.getBoundsInScreen(tmpRect)

            if (!tmpRect.isEmpty && tmpRect.width() > 0 && tmpRect.height() > 0) {
                val cx = tmpRect.exactCenterX()
                val cy = tmpRect.exactCenterY()

                if (debugMode &&
                    topRightCandidates.size < 6 &&
                    cx in (screenWidth * 0.72f)..(screenWidth * 0.98f) &&
                    cy in (screenHeight * 0.40f)..(screenHeight * 0.64f) &&
                    tmpRect.width() <= screenWidth * 0.30f &&
                    tmpRect.height() <= screenHeight * 0.14f &&
                    (node.isClickable || desc.isNotEmpty() || txt == "x")
                ) {
                    val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
                    topRightCandidates.add("$cls(clk=${node.isClickable},d='$desc',t='$txt',@${cx.toInt()}x${cy.toInt()})")
                }

                // 1. Procura o botao [ X ] exclusivamente dentro da faixa vertical do cabecalho do card (41%..64%)
                val isCloseDesc = closeLabels.any { desc.contains(it) || txt == it } || desc == "x" || txt == "x"
                val isInTopRightCorner = cx in (screenWidth * 0.78f)..(screenWidth * 0.96f) &&
                    cy in (screenHeight * 0.41f)..(screenHeight * 0.64f) &&
                    tmpRect.width() <= screenWidth * 0.20f &&
                    tmpRect.height() <= screenHeight * 0.10f

                if (isCloseDesc && cy in (screenHeight * 0.39f)..(screenHeight * 0.65f) && tmpRect.width() <= screenWidth * 0.28f) {
                    val clickableTarget = findClickableSelfOrParent(node) ?: node
                    clickableTarget.getBoundsInScreen(tmpRect)
                    val area = tmpRect.width().toLong() * tmpRect.height().toLong()
                    if (!tmpRect.isEmpty && (!foundRejectExplicitLabel || area < foundRejectArea)) {
                        foundRejectNode = clickableTarget
                        foundRejectExplicitLabel = true
                        foundRejectArea = area
                        foundRejectCenter = tmpRect.exactCenterX() to tmpRect.exactCenterY()
                        foundRejectSource = "NODE_DESC(${clickableTarget.className?.toString()?.substringAfterLast('.') ?: "?"})"
                    }
                } else if (!foundRejectExplicitLabel && node.isClickable && isInTopRightCorner) {
                    val area = tmpRect.width().toLong() * tmpRect.height().toLong()
                    if (area < foundRejectArea) {
                        foundRejectNode = node
                        foundRejectArea = area
                        foundRejectCenter = cx to cy
                        foundRejectSource = "NODE_SQUARE(${node.className?.toString()?.substringAfterLast('.') ?: "?"})"
                    }
                }

                // 2. Procura o botao "Tenho interesse" / "Aceitar" na parte inferior do card
                if (foundAcceptNode == null &&
                    cy > screenHeight * 0.65f &&
                    (acceptLabels.any { txt.contains(it) || desc.contains(it) })
                ) {
                    val clickableTarget = findClickableSelfOrParent(node) ?: node
                    foundAcceptNode = clickableTarget
                    val targetRect = Rect()
                    clickableTarget.getBoundsInScreen(targetRect)
                    if (!targetRect.isEmpty) {
                        foundAcceptCenter = targetRect.exactCenterX() to targetRect.exactCenterY()
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        val combinedTargets = (listOfNotNull(foundRejectCenter) + parsed.rejectTapTargets)
            .distinctBy { "${it.first.toInt()}x${it.second.toInt()}" }
        val effectiveRejectPoint = combinedTargets.firstOrNull() ?: parsed.rejectTapPoint
        if (debugMode) {
            val targetsStr = combinedTargets.joinToString(",") { "${it.first.toInt()}x${it.second.toInt()}" }
            log("UberRejectSrc=$foundRejectSource | alvos=[$targetsStr] | nos=$visited | janelas=${candidateRoots.size}")
            if (foundRejectSource == "NONE" && parsed.fingerprint != lastLoggedNodeOffer) {
                lastLoggedNodeOffer = parsed.fingerprint
                log("UberTopRightNodes(${topRightCandidates.size}): ${topRightCandidates.joinToString(" ; ")}")
            }
        }
        return parsed.copy(
            rejectNode = foundRejectNode,
            rejectTapPoint = effectiveRejectPoint,
            rejectTapTargets = combinedTargets,
            acceptNode = foundAcceptNode,
            acceptTapPoint = foundAcceptCenter ?: parsed.acceptTapPoint
        )
    }

    private fun findClickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private suspend fun processParsedScreen(
        parsed: ParsedRideOffer,
        config: FilterConfig,
        source: String,
        pendingShotBytes: ByteArray? = null
    ) {
        val now = System.currentTimeMillis()
        if (parsed.isInActiveRide) lastActiveRideScreenAt = now
        runCatching { ledger.onScreen(parsed, now) }.onFailure { log("[GANHOS] ⚠ erro no ciclo: ${it.message}") }

        dualCoordinator.onScreenObserved(parsed, config, now)?.let { evt ->
            log(evt.message)
            if (evt.unlocked) {
                lastFingerprint = ""
                lastSavedShotFingerprint = ""
            }
            syncFloatingPill(config, true)
        }

        // Tela do lapis da Negocia ("Sua oferta"): o app so mexe nela quando foi ele que abriu
        if (parsed.isCounterOfferEditor) {
            handleCounterOfferEditor(parsed, config, now)
            return
        }
        advanceCounterOfferJob(now)

        // A) Janela "Varias solicitacoes seguidas recusadas" -> clicar IMEDIATAMENTE e sem atraso em "Continuar aceitando solicitacoes"
        if (parsed.isContinuePrompt && !parsed.isRideOffer && config.autoClickContinueEnabled) {
            if (now - lastContinueClickAt > 800L) {
                lastContinueClickAt = now
                val clicked = performNodeOrTap(parsed.continueNode, parsed.continueTapPoint)
                log("[$source] Auto-clique imediato em 'Continuar aceitando solicitacoes' (sucesso=$clicked)")
            }
            return
        }

        // B) Janela de 5 Estrelinhas ("Como foi sua corrida com Camila?") -> mostrar botao ❤️ Favoritar
        if (parsed.isRatingScreen) {
            if (now - lastHeartShownAt > 6500L) {
                lastHeartShownAt = now
                triggerHeartPromptManualOrRating(parsed.ratingPassengerName)
            }
            return
        }

        // C) Card de Oferta de Corrida (normal ou em sequencia durante navegacao GPS)
        if (parsed.isRideOffer) {
            handleOffer(parsed, config, source, pendingShotBytes)
        }
    }

    private fun triggerHeartPromptManualOrRating(passengerName: String?) {
        val candidate = configRepository.getLastCandidateForFavorite()
        val summary = if (candidate != null) {
            "⏰ ${candidate.timeFormatted} • ${candidate.pickupAddress.take(22)} ➔ ${candidate.dropoffAddress.take(22)}"
        } else {
            val nowTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            "⏰ Horário $nowTime • Salvar rota nos Favoritos"
        }

        overlayManager.showHeartFavoritePrompt(
            rideSummary = summary,
            passengerName = passengerName,
            onSaveFavorite = {
                serviceScope.launch {
                    saveCandidateToFavorites(candidate, passengerName)
                }
            },
            onMarkProblem = { markProblem(if (passengerName == null) "botão flutuante (segurar)" else "tela de avaliação") }
        )
    }

    /**
     * "🐞 Algo deu errado agora?": grava no log uma MARCA com tudo que o app sabia naquele instante,
     * para achar depois o momento exato do problema e corrigir o app.
     */
    private fun markProblem(source: String) {
        val cfg = runCatching { configRepository.load() }.getOrNull()
        val lock = dualCoordinator.primaryRide?.let { "${it.appSource} → ${it.dropoffAddress.take(40)}" } ?: "livre"
        val queued = dualCoordinator.queuedRide?.let { " (+fila ${it.appSource} → ${it.dropoffAddress.take(30)})" }.orEmpty()
        log(
            "[MARCA] 🐞 Você marcou um problema ($source) • estado=${cfg?.runState ?: "?"} • app na frente=$currentForegroundPkg • " +
                "trava=$lock$queued • corrida aberta no painel=${ledger.hasOpenRide()} • última oferta: ${lastDecisionSummary.ifBlank { "nenhuma" }}"
        )
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(applicationContext, "Problema marcado. Depois conte o que aconteceu.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Por que o app fechou da ultima vez (Android matou por memoria, travou, atualizacao...). */
    private fun logPreviousExitReasons() {
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val prefs = getSharedPreferences("diagnostico", Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong("ultima_saida_registrada", 0L)
            val exits = am.getHistoricalProcessExitReasons(packageName, 0, 10)
                .filter { it.processName == packageName && it.timestamp > lastSeen }
                .sortedBy { it.timestamp }
            val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
            exits.forEach { e ->
                val desc = e.description?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                log("[SAIDA] O app fechou em ${fmt.format(Date(e.timestamp))}: ${exitReasonLabel(e.reason)}$desc • memória ${e.pss / 1024}MB")
            }
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("ultima_saida_registrada", it).apply() }
        }.onFailure { log("[SAIDA] não consegui ler o motivo do último fechamento: ${it.javaClass.simpleName}") }
    }

    private fun exitReasonLabel(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "o próprio app encerrou (botão OFF / fim da contagem)"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "encerrado pelo sistema (sinal)"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Android fechou por FALTA DE MEMÓRIA"
        android.app.ApplicationExitInfo.REASON_CRASH -> "TRAVOU (erro no app)"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "TRAVOU (erro nativo)"
        android.app.ApplicationExitInfo.REASON_ANR -> "TRAVOU (app sem responder)"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "falhou ao iniciar"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "uma permissão foi mudada"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android fechou por uso excessivo de bateria/processador"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "fechado por pedido (forçar parada, limpar recentes ou atualização)"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "você parou o app"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "um serviço do qual o app depende parou"
        android.app.ApplicationExitInfo.REASON_OTHER -> "outro motivo do sistema"
        16 -> "atualização do app"
        15 -> "mudança no pacote do app"
        14 -> "congelado pelo sistema"
        else -> "motivo $reason"
    }

    private suspend fun saveCandidateToFavorites(candidate: RideHistoryItem?, passengerName: String?) {
        val now = Date()
        val nowTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val dayAndDate = SimpleDateFormat("EEE, dd/MM/yyyy", Locale("pt", "BR")).format(now)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("pt", "BR")) else it.toString() }

        val pickupAddr = candidate?.pickupAddress?.ifBlank { "Embarque registrado às $nowTime" }
            ?: "Embarque registrado às $nowTime"
        val dropoffAddr = candidate?.dropoffAddress?.ifBlank { "Destino registrado" }
            ?: "Destino registrado"

        val pCoords = geocodeForFavorite("$pickupAddr, Rio de Janeiro, RJ")
        val dCoords = geocodeForFavorite("$dropoffAddr, Rio de Janeiro, RJ")

        val fav = FavoriteRideItem(
            id = System.currentTimeMillis(),
            passengerName = passengerName?.trim().orEmpty(),
            rideTimeFormatted = candidate?.timeFormatted?.ifBlank { nowTime } ?: nowTime,
            dateFormatted = dayAndDate,
            pickupAddress = pickupAddr,
            dropoffAddress = dropoffAddr,
            pickupLat = pCoords?.first ?: -22.8425,
            pickupLng = pCoords?.second ?: -43.2785,
            dropoffLat = dCoords?.first ?: 0.0,
            dropoffLng = dCoords?.second ?: 0.0,
            priceText = candidate?.priceText ?: "",
            ratingText = candidate?.ratingText ?: "★ 5,00"
        )
        configRepository.addFavorite(fav)
        val who = if (fav.passengerName.isNotEmpty()) "${fav.passengerName} " else ""
        log("❤️ Corrida ${who}salva nos Favoritos (${fav.rideTimeFormatted} - ${fav.pickupAddress})")
    }

    private fun checkUpcomingFavoriteAlerts() {
        val favorites = configRepository.getFavorites()
        if (favorites.isEmpty()) return

        val cal = Calendar.getInstance()
        val nowMinutesOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)

        for (fav in favorites) {
            val parts = fav.rideTimeFormatted.trim().split(":")
            if (parts.size != 2) continue
            val h = parts[0].toIntOrNull() ?: continue
            val m = parts[1].toIntOrNull() ?: continue
            val favMinutesOfDay = h * 60 + m

            val diff = (favMinutesOfDay - nowMinutesOfDay + 1440) % 1440
            val bucket = when (diff) {
                in 28..31 -> 30
                in 18..21 -> 20
                in 8..11 -> 10
                else -> null
            } ?: continue

            val alertKey = "$todayKey|${fav.id}|$bucket"
            if (sentFavoriteAlerts.contains(alertKey)) continue
            sentFavoriteAlerts.add(alertKey)

            sendFavoriteUpcomingNotification(fav, diff, bucket)
        }
    }

    private fun sendFavoriteUpcomingNotification(fav: FavoriteRideItem, exactMinutesLeft: Int, bucket: Int) {
        val passengerLabel = fav.passengerName.ifBlank { "Passageiro Favorito" }
        val title = "⏰ Alerta de Favorito: faltam ~$exactMinutesLeft min (${fav.rideTimeFormatted})!"
        val body = "👤 $passengerLabel pediu corrida às ${fav.rideTimeFormatted}.\n🟢 Embarque: ${fav.pickupAddress}\n🏁 Destino: ${fav.dropoffAddress}"

        log("⏰ Alerta de Favorito ($bucket min): $passengerLabel às ${fav.rideTimeFormatted} em ${fav.pickupAddress}")

        overlayManager.showRideStatus(
            decision = Decision.INSIDE_AREA,
            headline = "⏰ FAVORITO EM ~$exactMinutesLeft MIN (${fav.rideTimeFormatted})",
            metricsLine = "👤 $passengerLabel • Posicione-se no embarque!",
            detailLine = "🟢 ${fav.pickupAddress.take(42)}",
            autoHideMs = 8000L
        )

        try {
            val openFavIntent = Intent(this, FavoritesActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                this,
                fav.id.toInt(),
                openFavIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val query = Uri.encode("${fav.pickupAddress}, Rio de Janeiro, RJ")
            val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$query")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val navPendingIntent = PendingIntent.getActivity(
                this,
                (fav.id + 1000).toInt(),
                navIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, FAV_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(title)
                .setContentText("🟢 Embarque: ${fav.pickupAddress}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(contentPendingIntent)
                .addAction(android.R.drawable.ic_menu_directions, "📍 Ir p/ Embarque", navPendingIntent)
                .addAction(android.R.drawable.ic_menu_mapmode, "🗺️ Ver no Mapa", contentPendingIntent)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify((fav.id % 100000).toInt() + bucket, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar notificacao de favorito", e)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FAV_CHANNEL_ID,
                "Alertas de Passageiros Favoritos (30/20/10 min)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisa 30, 20 e 10 minutos antes do horário de corridas favoritas para você se posicionar"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private suspend fun geocodeForFavorite(query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        try {
            val geocoder = Geocoder(applicationContext, Locale("pt", "BR"))
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

    private suspend fun handleOffer(
        parsed: ParsedRideOffer,
        rawConfig: FilterConfig,
        source: String,
        pendingShotBytes: ByteArray? = null
    ) {
        // PAUSA: avalia e mostra o card normalmente, mas nenhuma acao de aceitar/recusar
        val config = if (rawConfig.actionsPaused) {
            rawConfig.copy(autoRejectEnabled = false, autoAcceptInsideArea = false, autoAcceptInterZone = false)
        } else {
            rawConfig
        }
        val now = System.currentTimeMillis()
        val isSameOffer = parsed.fingerprint == lastFingerprint
        val appActionAt = lastActionAtByApp[parsed.appSource] ?: 0L

        // GPS do motorista so vale para ofertas "em espera": em corrida, a distancia ate o embarque
        // pode ser contada a partir do destino atual (e nao de onde o carro esta agora).
        val driverFix = if (dualCoordinator.primaryRide == null && now - lastActiveRideScreenAt > 90_000L) {
            DriverLocationProvider.current(applicationContext)
        } else {
            null
        }
        val evalStartedAt = System.currentTimeMillis()
        // Negocia: mesmas regras, mas com o R$/h minimo da Negocia quando ele esta ligado
        val ruleConfig = if (parsed.isNegotiable) config.forNegotiation() else config
        val result = evaluator.evaluate(parsed, ruleConfig, driverFix)
        val evalMs = System.currentTimeMillis() - evalStartedAt
        val conflict = dualCoordinator.evaluateOfferConflict(parsed, config)
        val wantsAutoReject = result.decision == Decision.OUTSIDE_AREA ||
            (result.decision == Decision.UNCERTAIN && config.rejectUncertainLocation) ||
            conflict.action == DualRideCoordinator.ConflictAction.AUTO_REJECT_OTHER_APP

        if (isSameOffer) {
            if (parsed.fingerprint == lastNegotiateOnlyFingerprint && now - lastEvaluatedAt < 3500L) return
            if (!wantsAutoReject || !config.autoRejectEnabled) {
                if (now - lastEvaluatedAt < 3500L) return
            } else {
                val cooldownMs = if (parsed.appSource == "UBER") 1200L else 2200L
                if (now - appActionAt < cooldownMs) return
            }
        }

        lastFingerprint = parsed.fingerprint
        lastEvaluatedAt = now
        val appTag = "[${parsed.appSource}]"

        // Nome bloqueado visto com rua+numero ("Caxias Shopping, Rodovia Washington Luiz, 2895"):
        // grava o endereco para pegar o mesmo lugar com outro nome ("Entrada norte, Rod. Washington Luiz, 2895")
        if (result.learnedBlockedPlaces.isNotEmpty()) {
            configRepository.addBlockedPlaces(result.learnedBlockedPlaces).forEach { p ->
                log("[BLOQUEIO] Aprendi o endereço de '${p.term}': ${p.label}. Outros nomes do mesmo lugar também ficam bloqueados.")
            }
        }

        // ---- Encaixe 99 <-> Uber: durante corrida no outro app, a busca e medida do DESTINO dela ----
        // Regra do motorista (18/11): so com o passageiro da corrida atual A BORDO (corrida iniciada) e
        // [o que falta dela] + [ida ate o proximo passageiro] <= limite (padrao 3 min). Fora disso, vale a trava.
        var chainPlan: ChainPlanner.Plan? = null
        var chainNote: String? = null
        var decided = result
        val activeForChain = conflict.activeRide
        if (config.chainAcrossAppsEnabled && activeForChain != null &&
            (conflict.action == DualRideCoordinator.ConflictAction.BLOCK_AUTO_ACCEPT_ONLY ||
                conflict.action == DualRideCoordinator.ConflictAction.AUTO_REJECT_OTHER_APP)
        ) {
            val activeLabel = if (activeForChain.appSource.equals("UBER", true)) "Uber" else activeForChain.appSource
            val stage = dualCoordinator.activeStage()
            if (stage != RideProgress.Stage.TRIP) {
                chainNote = "⛓ Encaixe só depois que o passageiro da $activeLabel embarcar (corrida iniciada)"
                log(
                    "[ENCAIXE] $appTag não encaixa: a corrida da ${activeForChain.appSource} ainda não começou (" +
                        (if (stage == RideProgress.Stage.PICKUP) "indo buscar / esperando o passageiro" else "etapa não lida") +
                        ") -> vale a trava"
                )
            } else {
                val remaining = dualCoordinator.remainingMinutes(now)
                chainPlan = runCatching {
                    chainPlanner.plan(activeForChain.appSource, activeForChain.dropoffAddress, parsed, remaining, config.chainMaxWaitMin)
                }.getOrNull()
                if (chainPlan != null) {
                    val chained = evaluator.evaluate(chainPlan.adjusted, config, null)
                    decided = chained.copy(
                        checklistText = chainPlan.summary() + "\n" + chained.checklistText.ifBlank { chained.reason }
                    )
                    log(
                        "[ENCAIXE] $appTag durante corrida ${activeForChain.appSource} (passageiro a bordo): busca real ${chainPlan.chainKm} km " +
                            "(o card dizia ${parsed.pickupDistanceKm ?: "?"} km) • ${chainPlan.timeText()} • " +
                            "tempo ${if (chainPlan.waitOk) "OK" else "ESTOURA"} • regras: ${chained.decision} -> ${chained.reason}"
                    )
                } else {
                    chainNote = "⛓ Não consegui medir a busca a partir do destino da $activeLabel"
                    log("[ENCAIXE] $appTag não deu para medir a busca a partir do destino da ${activeForChain.appSource} (endereço não achado no mapa): vale a trava")
                }
            }
        }
        val chainAcceptable = chainPlan != null &&
            (decided.decision == Decision.INSIDE_AREA || decided.decision == Decision.INTER_ZONE)
        val conflictAction = when {
            chainPlan == null -> conflict.action
            // passou nas regras e cabe no tempo: aceita e vira a corrida #2 (fila)
            chainAcceptable && chainPlan.waitOk -> DualRideCoordinator.ConflictAction.ALLOW_AS_SEQUENTIAL
            // regras recusam (busca longe, R$/h baixo, fora da area...): recusa normal
            decided.decision == Decision.OUTSIDE_AREA -> DualRideCoordinator.ConflictAction.ALLOW
            // passou nas regras mas o tempo estoura (ou nao li quanto falta): vale a trava
            else -> conflict.action
        }

        // ---- Corrida "Negocia" da 99 ----
        // Passa nas regras pelo valor do passageiro: aceita como corrida normal.
        // So o preco atrapalha (com o minimo das suas regras ela passaria em tudo): o app pede o valor
        // minimo (lapis -> escreve o valor -> Confirmar oferta). Area, nota, bloqueio... = recusa normal.
        val negotiation = if (parsed.isNegotiable && chainPlan == null) {
            runCatching { NegotiationAdvisor.advise(parsed, ruleConfig) }.getOrNull()
        } else {
            null
        }
        var negotiatePriceOnly = false
        var negotiateTooFar = false
        var negotiateAtMinDecision: Decision? = null
        var negotiateAtMinReason = ""
        if (negotiation != null && !negotiation.passesAtPassengerPrice &&
            (decided.decision == Decision.OUTSIDE_AREA || decided.decision == Decision.UNCERTAIN)
        ) {
            val atMin = runCatching {
                evaluator.evaluate(NegotiationAdvisor.withPrice(parsed, negotiation.minPrice), ruleConfig, driverFix)
            }.getOrNull()
            val priceOnly = atMin != null &&
                (atMin.decision == Decision.INSIDE_AREA || atMin.decision == Decision.INTER_ZONE)
            negotiateAtMinDecision = atMin?.decision
            negotiateAtMinReason = atMin?.reason.orEmpty()
            negotiatePriceOnly = priceOnly && negotiation.withinReach
            negotiateTooFar = priceOnly && !negotiation.withinReach
        }

        log("[$source] Decisao: ${decided.decision} (${evalMs}ms, estado=${rawConfig.runState}, gps=${driverFix != null}) -> ${decided.reason} | ${decided.checklistText}")
        if (negotiation != null) {
            val what = when {
                negotiatePriceOnly -> "só o preço atrapalha: ${NegotiationAdvisor.logText(negotiation)} • peço o mínimo, não recuso"
                negotiateTooFar -> "só o preço atrapalha, mas ${NegotiationAdvisor.logText(negotiation)}: longe demais (+${negotiation.raisePercent}%), recusa normal"
                negotiation.passesAtPassengerPrice && (decided.decision == Decision.INSIDE_AREA || decided.decision == Decision.INTER_ZONE) ->
                    "${NegotiationAdvisor.logText(negotiation)} • segue como corrida normal"
                negotiateAtMinDecision != null ->
                    "mesmo pedindo R$ ${String.format(Locale("pt", "BR"), "%.2f", negotiation.minPrice)} ela não passaria (${negotiateAtMinReason.take(90)}) • segue a regra normal"
                else -> "o problema não é o preço (${decided.reason.take(80)}) • segue a regra normal"
            }
            log("[NEGOCIA] [${parsed.appSource}] R$ ${String.format(Locale("pt", "BR"), "%.2f", negotiation.passengerPrice)} " +
                "(botões ${parsed.counterOfferPrices.joinToString("/") { String.format(Locale("pt", "BR"), "%.2f", it) }.ifEmpty { "não lidos" }}): $what")
        }
        lastDecisionSummary = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now)) +
            " ${parsed.appSource} ${decided.decision}: ${parsed.pickupAddress.orEmpty().take(40)} → " +
            "${parsed.dropoffAddress.orEmpty().take(40)} (${decided.reason.take(100)})"

        val br = Locale("pt", "BR")
        val priceFmt = parsed.ridePrice?.let { String.format(br, "R$ %.2f", it) } ?: ""
        dualCoordinator.rememberCandidateOffer(parsed, priceFmt, now)
        val ledgerFuel = parsed.totalDistanceKm?.let { km ->
            FuelCalculator.estimate(km, parsed.ridePrice, parsed.hourlyRate, config, requireFuelDisplayEnabled = false)?.cost
        }
        ledger.onOfferEvaluated(parsed, if (negotiatePriceOnly) "NEGOCIA" else decided.decision.name, ledgerFuel, now)
        if (!isSameOffer) logRouteSample(parsed, config, driverFix, decided.decision.name)
        statOffers++
        val kmFmt = parsed.pricePerKm?.let { String.format(br, "R$ %.2f/km", it) } ?: ""
        val totKmFmt = parsed.totalDistanceKm?.let { String.format(br, "%.1f km", it) } ?: ""
        val ratingFmt = parsed.passengerRating?.let { String.format(br, "★ %.2f", it) }
            ?: if (parsed.isNewPassenger) "★ Novo" else ""
        val stopsFmt = if (parsed.hasMultipleStops) "🛑 ${parsed.stopsCount} parada(s)" else ""

        val fuelEstimate = FuelCalculator.estimate(parsed, config)
        val rawHrFmt = parsed.hourlyRate?.let { String.format(br, "R$ %.0f/h", it) } ?: ""
        val netHrText = fuelEstimate?.netHourlyText
        val hrFmt = if (config.useNetHourlyRate && netHrText != null) {
            netHrText
        } else {
            rawHrFmt
        }
        val profitLabel = fuelEstimate?.let { est ->
            if (config.useNetHourlyRate && netHrText != null) {
                est.profitText
            } else {
                listOfNotNull(est.profitText, est.netHourlyText).joinToString(" · ").ifEmpty { null }
            }
        }
        val secondaryList = listOf(kmFmt, totKmFmt, ratingFmt, stopsFmt).filter { it.isNotEmpty() }

        var savedShotPath = ""
        if (!isSameOffer && (!parsed.pickupAddress.isNullOrBlank() || !parsed.dropoffAddress.isNullOrBlank() || parsed.ridePrice != null)) {
            val timeShort = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            savedShotPath = if (pendingShotBytes != null && config.saveRideScreenshotsEnabled) {
                val targetPath = ConfigRepository.buildRideScreenshotPath(this, now)
                val written = withContext(Dispatchers.IO) {
                    ConfigRepository.saveRideScreenshotBytesToPath(targetPath, pendingShotBytes)
                }
                if (written) {
                    lastSavedShotFingerprint = parsed.fingerprint
                    targetPath
                } else {
                    ""
                }
            } else {
                ""
            }
            val historyItem = RideHistoryItem(
                id = now,
                timeFormatted = timeShort,
                pickupAddress = "${parsed.appSource}: ${parsed.pickupAddress.orEmpty()}",
                dropoffAddress = parsed.dropoffAddress.orEmpty(),
                priceText = priceFmt,
                pricePerKmText = kmFmt,
                hourlyRateText = hrFmt,
                ratingText = ratingFmt,
                isInsideArea = decided.decision == Decision.INSIDE_AREA || decided.decision == Decision.INTER_ZONE,
                reason = decided.reason,
                screenshotPath = savedShotPath
            )
            configRepository.addRideHistory(historyItem)
        }

        val checklist = decided.checklistText.ifBlank { decided.reason }
        val bannerHideMs = config.bannerAutoHideSeconds.coerceIn(1, 30) * 1000L
        val sameBannerOffer = isSameActiveBannerOffer(parsed)

        // ---- "Aceitar se tocar de novo": corrida que voce liberou no botao do card ----
        val signature = OfferSignature.of(parsed.appSource, parsed.pickupAddress, parsed.dropoffAddress)
        acceptOverrides.removeAll { it.until < now }
        val override = signature?.let { s -> acceptOverrides.firstOrNull { it.sig.matches(s) } }

        val blockedByConflict = conflictAction == DualRideCoordinator.ConflictAction.BLOCK_AUTO_ACCEPT_ONLY
        // Negocia que passa nas regras pelo valor do passageiro e aceita como corrida normal
        val willAutoAccept = conflictAction != DualRideCoordinator.ConflictAction.AUTO_REJECT_OTHER_APP && when (decided.decision) {
            Decision.INSIDE_AREA -> config.autoAcceptInsideArea && !blockedByConflict
            Decision.INTER_ZONE -> config.autoAcceptInterZone && !blockedByConflict
            else -> false
        }
        // Botao discreto grudado no card: so quando o app NAO vai aceitar sozinho (as vezes o app erra).
        // Na Negocia em que so o preco atrapalha, nao: o botao aceitaria pelo valor baixo do passageiro.
        // So na 99: e ela que toca a mesma corrida de novo (a Uber nao repete a oferta)
        val quickLabel = if (override == null && !willAutoAccept && signature != null &&
            decided.decision != Decision.UNKNOWN_KEEP && !negotiatePriceOnly &&
            parsed.appSource == "99"
        ) QUICK_ACCEPT_LABEL else null
        val quickCallback: (() -> Unit)? = if (quickLabel != null && signature != null) {
            { onQuickAcceptTapped(parsed, signature) }
        } else {
            null
        }

        fun maybeShowBanner(cardModel: RideCardModel) {
            if (!config.showRideInfoBannerEnabled) return
            val model = if (rawConfig.actionsPaused) cardModel.copy(statusLabel = "PAUSADO · SÓ LEITURA") else cardModel
            if (sameBannerOffer) {
                if (overlayManager.isBannerVisible() && (now - activeBannerShownAt) < bannerHideMs) {
                    overlayManager.updateRideCardContent(model)
                }
                return
            }
            activeBannerApp = parsed.appSource
            activeBannerPrice = parsed.ridePrice
            activeBannerPickup = parsed.pickupAddress?.trim()?.lowercase().orEmpty()
            activeBannerDropoff = parsed.dropoffAddress?.trim()?.lowercase().orEmpty()
            activeBannerShownAt = now
            overlayManager.showRideCard(model.copy(quickActionLabel = quickLabel), quickCallback)
        }

        // Foto da corrida JUNTO com o card do filtro (para comparar o que o app decidiu com a oferta real)
        fun scheduleShotWithCard(statusText: String) {
            val path = savedShotPath
            if (path.isEmpty() || !config.showRideInfoBannerEnabled) return
            val caption = "Filtro: $statusText · " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
            overlayManager.captureRideCardBitmap { card ->
                if (card != null) {
                    serviceScope.launch(Dispatchers.IO) { composeShotWithCard(path, card, caption) }
                }
            }
        }

        if (override != null) {
            acceptOverrides.remove(override)
            maybeShowBanner(
                RideCardModel(
                    decision = Decision.INSIDE_AREA,
                    appSource = parsed.appSource,
                    title = "Você liberou esta corrida",
                    statusLabel = if (rawConfig.actionsPaused) "VOCÊ DECIDE" else "ACEITANDO",
                    priceText = priceFmt.ifEmpty { null },
                    hourlyText = hrFmt.ifEmpty { null },
                    secondaryMetrics = secondaryList,
                    fuelText = fuelEstimate?.costText,
                    profitText = profitLabel,
                    profitPositive = (fuelEstimate?.netProfit ?: 1.0) > 0.0,
                    checklist = "✔ Tocou de novo: você pediu para aceitar\n$checklist",
                    autoHideMs = bannerHideMs
                )
            )
            scheduleShotWithCard("LIBERADA POR VOCÊ")
            if (rawConfig.actionsPaused) {
                log("[LIBERADA] $appTag A corrida que você liberou tocou de novo, mas o filtro está em PAUSA: só mostrei o card")
                return
            }
            lastActionAt = now
            lastActionAtByApp[parsed.appSource] = now
            if (config.humanDelayEnabled) delay(Random.nextLong(280L, 520L))
            if (!isOfferAppStillSafeToTap(config, parsed.appSource)) {
                log("[LIBERADA] $appTag aceite cancelado: janela mudou durante o atraso")
                return
            }
            val accepted = performAcceptForOffer(parsed)
            log("[LIBERADA] $appTag A corrida que você liberou tocou de novo: aceite disparado (sucesso=$accepted) • regras diziam: ${decided.reason}")
            if (accepted) registerAccepted(parsed, config, priceFmt, now)
            return
        }

        // Opcao 2 do Controle de Conflito: recusa automaticamente TODAS as corridas do outro app durante viagem ativa
        if (conflictAction == DualRideCoordinator.ConflictAction.AUTO_REJECT_OTHER_APP) {
            val active = conflict.activeRide
            val activeDest = active?.dropoffAddress?.ifBlank { active.pickupAddress }.orEmpty()
            val conflictChecklist = buildString {
                append("🔒 Em viagem [${active?.appSource ?: "?"}]")
                if (activeDest.isNotBlank()) append(" → Dest: $activeDest")
                append("\n")
                chainNote?.let { append(it).append("\n") }
                chainPlan?.takeIf { !it.waitOk }?.let { append("⚠ Tempo: ").append(it.timeText()).append("\n") }
                append(checklist)
            }
            val status = if (config.autoRejectEnabled) "RECUSANDO CONFLITO" else "BLOQUEADO"
            maybeShowBanner(
                RideCardModel(
                    decision = Decision.OUTSIDE_AREA,
                    appSource = parsed.appSource,
                    title = "Conflito (${active?.appSource ?: "em corrida"})",
                    statusLabel = status,
                    priceText = priceFmt.ifEmpty { null },
                    hourlyText = hrFmt.ifEmpty { null },
                    secondaryMetrics = secondaryList,
                    fuelText = fuelEstimate?.costText,
                    profitText = profitLabel,
                    profitPositive = (fuelEstimate?.netProfit ?: 1.0) > 0.0,
                    checklist = conflictChecklist,
                    autoHideMs = bannerHideMs
                )
            )
            scheduleShotWithCard(status)
            if (config.autoRejectEnabled) {
                ledger.onAutoRejected(parsed, "Conflito em corrida", now)
                executeSurgicalReject(parsed, config, appTag, now)
            }
            return
        }

        // Pintura por zona: card com a cor da zona de destino (so corridas aceitaveis; vermelho/laranja nunca mudam)
        val zoneColorHex = if (config.zoneCardColorsEnabled && decided.dropoffZoneId != null) {
            config.mapZones.firstOrNull { it.id == decided.dropoffZoneId }?.cardColorHex?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val chainApp = chainPlan?.activeApp?.let { if (it.equals("UBER", true)) "Uber" else it }
        val chainWaitNote = chainPlan?.let { p ->
            if (p.waitOk) null
            else if (p.remainingMin == null) "⚠ Não li quanto falta da $chainApp: não encaixa"
            else "⚠ ${p.timeText()}: não encaixa"
        } ?: chainNote

        // Negocia em que so o preco atrapalha: o app pede o valor minimo das suas regras.
        // So envia sozinho com o aceite automatico ligado (entre zonas: o aceite entre zonas) e sem trava 99/Uber;
        // senao mostra quanto pedir e a negociacao fica com voce.
        if (negotiatePriceOnly && negotiation != null) {
            lastNegotiateOnlyFingerprint = parsed.fingerprint
            val autoAllowed = when (negotiateAtMinDecision) {
                Decision.INTER_ZONE -> config.autoAcceptInterZone
                Decision.INSIDE_AREA -> config.autoAcceptInsideArea
                else -> false
            }
            val pencil = parsed.counterOfferPencilTap
            val runningJob = counterJob?.takeIf { it.fingerprint == parsed.fingerprint && now - it.startedAt < 60_000L }
            val sendNow = autoAllowed && !blockedByConflict && pencil != null && runningJob == null
            val sending = sendNow || runningJob?.active == true
            val alreadySent = runningJob?.stage == CounterOfferFlow.Stage.SENT
            val askText = String.format(br, "%.2f", runningJob?.confirmedValue ?: negotiation.typedValue)
            maybeShowBanner(
                RideCardModel(
                    decision = Decision.UNCERTAIN,
                    appSource = parsed.appSource,
                    title = when {
                        alreadySent -> "Negocie: pedi $askText"
                        sending -> "Negocie: pedindo $askText"
                        else -> NegotiationAdvisor.title(negotiation)
                    },
                    statusLabel = if (sending || alreadySent) "NEGOCIANDO" else "VOCÊ NEGOCIA",
                    priceText = priceFmt.ifEmpty { null },
                    hourlyText = hrFmt.ifEmpty { null },
                    secondaryMetrics = secondaryList,
                    fuelText = fuelEstimate?.costText,
                    profitText = profitLabel,
                    profitPositive = (fuelEstimate?.netProfit ?: 1.0) > 0.0,
                    checklist = (
                        if (sending || alreadySent) {
                            NegotiationAdvisor.sendingLines(negotiation, runningJob?.confirmedValue ?: negotiation.typedValue, alreadySent)
                        } else {
                            NegotiationAdvisor.cardLines(negotiation)
                        } + checklist
                        ).joinToString("\n"),
                    autoHideMs = maxOf(bannerHideMs, NEGOTIATE_CARD_MIN_MS)
                )
            )
            scheduleShotWithCard(if (sending || alreadySent) "NEGOCIANDO" else "VOCÊ NEGOCIA")
            if (!sendNow) {
                if (runningJob == null && !alreadySent) {
                    val why = when {
                        !autoAllowed -> "aceite automático desligado"
                        blockedByConflict -> "trava 99/Uber ligada (corrida em andamento)"
                        else -> "não achei o lápis na tela"
                    }
                    log("[NEGOCIA] $appTag não enviei a contraproposta ($why): mostrei quanto pedir")
                }
                return
            }
            lastActionAt = now
            lastActionAtByApp[parsed.appSource] = now
            if (config.humanDelayEnabled) delay(Random.nextLong(280L, 520L))
            if (!isOfferAppStillSafeToTap(config, parsed.appSource)) {
                log("[NEGOCIA] $appTag contraproposta cancelada: a janela mudou antes do toque no lápis")
                return
            }
            val tapped = dispatchTap(pencil!!.first, pencil.second)
            counterJob = CounterOfferFlow.Job(
                app = parsed.appSource,
                fingerprint = parsed.fingerprint,
                minPrice = negotiation.minPrice,
                target = negotiation.typedValue,
                startedAt = System.currentTimeMillis()
            )
            ledger.onCounterOffer(parsed.appSource, parsed.fingerprint, negotiation.typedValue, now)
            log(
                "[NEGOCIA] $appTag toquei no lápis (${pencil.first.toInt()}x${pencil.second.toInt()}, sucesso=$tapped) para pedir " +
                    "R$ $askText (mínimo das suas regras R$ ${String.format(br, "%.2f", negotiation.minPrice)})"
            )
            return
        }
        // Linhas da Negocia no card: "valor ja passa" (card verde) ou "precisaria +X%" (card vermelho)
        val negotiationLines = when {
            negotiation == null -> emptyList()
            negotiateTooFar -> NegotiationAdvisor.cardLines(negotiation)
            negotiation.passesAtPassengerPrice &&
                (decided.decision == Decision.INSIDE_AREA || decided.decision == Decision.INTER_ZONE) ->
                NegotiationAdvisor.cardLines(negotiation)
            else -> emptyList()
        }

        when (decided.decision) {
            Decision.INSIDE_AREA, Decision.INTER_ZONE -> {
                val isInter = decided.decision == Decision.INTER_ZONE
                val active = conflict.activeRide
                val activeDest = active?.dropoffAddress?.ifBlank { active.pickupAddress }.orEmpty()
                val autoOn = if (isInter) config.autoAcceptInterZone else config.autoAcceptInsideArea
                val titleText = when {
                    chainPlan != null -> if (blockedByConflict) "Encaixe? (${chainApp} em andamento)" else "Encaixe após a $chainApp"
                    blockedByConflict -> "🔒 Em viagem (${active?.appSource ?: "?"})"
                    isInter -> "Corrida entre zonas?"
                    else -> "Dentro da área"
                }
                val statusText = when {
                    blockedByConflict -> if (chainPlan != null) "VOCÊ DECIDE" else "DUPLO ACEITE BLOQUEADO"
                    autoOn -> "ACEITANDO"
                    else -> "VOCÊ DECIDE"
                }
                val finalChecklist = when {
                    chainPlan != null && chainWaitNote != null -> "$chainWaitNote\n$checklist"
                    chainPlan != null -> checklist
                    blockedByConflict -> "🔒 ${active?.appSource} ativo${if (activeDest.isNotBlank()) " (Dest: $activeDest)" else ""}\n" +
                        (chainWaitNote?.let { "$it\n" } ?: "") + checklist
                    else -> checklist
                }.let { base -> (negotiationLines + base).joinToString("\n") }
                maybeShowBanner(
                    RideCardModel(
                        decision = decided.decision,
                        appSource = parsed.appSource,
                        title = titleText,
                        statusLabel = statusText,
                        priceText = priceFmt.ifEmpty { null },
                        hourlyText = hrFmt.ifEmpty { null },
                        secondaryMetrics = secondaryList,
                        fuelText = fuelEstimate?.costText,
                        profitText = profitLabel,
                        profitPositive = (fuelEstimate?.netProfit ?: 1.0) > 0.0,
                        checklist = finalChecklist,
                        zoneColorHex = zoneColorHex,
                        autoHideMs = bannerHideMs
                    )
                )
                scheduleShotWithCard(statusText)

                val lastAcceptAt = lastActionAtByApp[parsed.appSource] ?: 0L
                val label = if (isInter) "Auto-aceitar entre zonas" else "Auto-aceitar"
                if (blockedByConflict) {
                    log("🔒 $label bloqueado $appTag: em corrida no app ${active?.appSource} (Dest: $activeDest)${chainWaitNote?.let { " • $it" } ?: ""}")
                } else if (autoOn && now - lastAcceptAt > 1200L) {
                    lastActionAt = now
                    lastActionAtByApp[parsed.appSource] = now
                    // Ultimo criterio: o transito de agora. Sem resposta = segue a estimativa da 99.
                    val traffic = checkTrafficBeforeAccept(parsed, ruleConfig, driverFix)
                    if (traffic != null && !traffic.passes) {
                        val status = if (config.autoRejectEnabled) "RECUSANDO" else "RECUSA OFF"
                        maybeShowBanner(
                            RideCardModel(
                                decision = Decision.OUTSIDE_AREA,
                                appSource = parsed.appSource,
                                title = "Trânsito pesado agora",
                                statusLabel = status,
                                priceText = priceFmt.ifEmpty { null },
                                hourlyText = traffic.hourlyNow?.let { String.format(br, "R$ %.0f/h", it) } ?: hrFmt.ifEmpty { null },
                                secondaryMetrics = secondaryList,
                                fuelText = fuelEstimate?.costText,
                                profitText = profitLabel,
                                profitPositive = (fuelEstimate?.netProfit ?: 1.0) > 0.0,
                                checklist = "✘ ${traffic.summary()}\n$finalChecklist",
                                autoHideMs = bannerHideMs
                            )
                        )
                        scheduleShotWithCard("TRÂNSITO")
                        log("[TRANSITO] $appTag ${traffic.summary()}: abaixo das suas regras, não aceitei")
                        if (config.autoRejectEnabled) {
                            ledger.onAutoRejected(parsed, "Trânsito: ${traffic.summary()}", now)
                            executeSurgicalReject(parsed, config, appTag, now)
                        }
                        return
                    }
                    if (config.humanDelayEnabled) {
                        delay(Random.nextLong(280L, 520L))
                    }
                    if (!isOfferAppStillSafeToTap(config, parsed.appSource)) {
                        log("$label $appTag cancelado: janela mudou durante o atraso (protecao contra toque errado)")
                    } else {
                        val accepted = performAcceptForOffer(parsed)
                        log("$label $appTag disparado em ${parsed.acceptTapPoint} (sucesso=$accepted)${if (chainPlan != null) " • ENCAIXE após ${chainPlan.activeApp}" else ""}")
                        if (accepted) registerAccepted(parsed, config, priceFmt, now)
                    }
                }
            }

            Decision.RETURN_TO_AREA -> {
                maybeShowBanner(
                    RideCardModel(
                        decision = Decision.RETURN_TO_AREA,
                        appSource = parsed.appSource,
                        title = "Retorno à área (Emb ≤ 1 km)",
                        statusLabel = "RETORNO",
                        priceText = priceFmt.ifEmpty { null },
                        hourlyText = hrFmt.ifEmpty { null },
                        secondaryMetrics = secondaryList,
                        fuelText = fuelEstimate?.costText,
                        profitText = profitLabel,
                        profitPositive = (fuelEstimate?.netProfit ?: 1.0) > 0.0,
                        checklist = checklist,
                        autoHideMs = bannerHideMs
                    )
                )
                scheduleShotWithCard("RETORNO")
            }

            Decision.OUTSIDE_AREA -> {
                val status = if (config.autoRejectEnabled) "RECUSANDO" else "RECUSA OFF"
                maybeShowBanner(
                    RideCardModel(
                        decision = Decision.OUTSIDE_AREA,
                        appSource = parsed.appSource,
                        title = if (chainPlan != null) "Encaixe ruim (após a $chainApp)" else "Fora dos critérios",
                        statusLabel = status,
                        priceText = priceFmt.ifEmpty { null },
                        hourlyText = hrFmt.ifEmpty { null },
                        secondaryMetrics = secondaryList,
                        fuelText = fuelEstimate?.costText,
                        profitText = profitLabel,
                        profitPositive = (fuelEstimate?.netProfit ?: 1.0) > 0.0,
                        checklist = (negotiationLines + checklist).joinToString("\n"),
                        autoHideMs = bannerHideMs
                    )
                )
                scheduleShotWithCard(status)
                if (config.autoRejectEnabled) {
                    ledger.onAutoRejected(parsed, decided.reason, now)
                    executeSurgicalReject(parsed, config, appTag, now)
                }
            }

            Decision.UNCERTAIN -> {
                // Local incerto: NUNCA aceita sozinho. Recusa automatica so se voce ativou "Na dúvida, recusar".
                val rejectNow = config.rejectUncertainLocation && config.autoRejectEnabled
                val status = if (rejectNow) "RECUSANDO (DÚVIDA)" else "VOCÊ DECIDE"
                maybeShowBanner(
                    RideCardModel(
                        decision = Decision.UNCERTAIN,
                        appSource = parsed.appSource,
                        title = "Local incerto — confira",
                        statusLabel = status,
                        priceText = priceFmt.ifEmpty { null },
                        hourlyText = hrFmt.ifEmpty { null },
                        secondaryMetrics = secondaryList,
                        fuelText = fuelEstimate?.costText,
                        profitText = profitLabel,
                        profitPositive = (fuelEstimate?.netProfit ?: 1.0) > 0.0,
                        checklist = checklist,
                        autoHideMs = bannerHideMs
                    )
                )
                scheduleShotWithCard(status)
                if (rejectNow) {
                    ledger.onAutoRejected(parsed, decided.reason, now)
                    executeSurgicalReject(parsed, config, appTag, now)
                } else {
                    log("⚠ $appTag Local incerto: auto-aceite NAO disparado (${decided.reason})")
                }
            }

            Decision.UNKNOWN_KEEP -> {
                // Nao exibe o banner cinza "AGUARDANDO LEITURA" para nao poluir a tela (aguarda proximo frame OCR)
            }
        }
    }

    private fun fmtBr(v: Double): String = String.format(Locale("pt", "BR"), "%.2f", v)

    private var lastTrafficFingerprint = ""
    private var lastTrafficVerdict: TrafficCheck.Verdict? = null

    /**
     * Tempo com o transito de agora (Google Routes) para a corrida que o app vai aceitar.
     * Devolve null quando nao deu para consultar (sem chave, sem GPS, sem internet, demorou mais de 1,5 s):
     * nesse caso o aceite segue pela estimativa da 99, como sempre.
     */
    private suspend fun checkTrafficBeforeAccept(parsed: ParsedRideOffer, config: FilterConfig, evalFix: DriverFix?): TrafficCheck.Verdict? {
        if (!TrafficCheck.shouldCheck(parsed, config)) return null
        if (parsed.fingerprint.isNotBlank() && parsed.fingerprint == lastTrafficFingerprint) return lastTrafficVerdict
        val startedAt = System.currentTimeMillis()
        val verdict = withTimeoutOrNull(TRAFFIC_BUDGET_MS) {
            val fix = evalFix ?: runCatching { DriverLocationProvider.current(applicationContext) }.getOrNull()
            val me = fix?.let { it.lat to it.lng } ?: return@withTimeoutOrNull null
            val pick = parsed.pickupAddress?.let { a -> runCatching { evaluator.pointOf(a, config) }.getOrNull() } ?: return@withTimeoutOrNull null
            val drop = parsed.dropoffAddress?.let { a -> runCatching { evaluator.pointOf(a, config) }.getOrNull() } ?: return@withTimeoutOrNull null
            // Roda a parte: se a internet travar, o prazo vence e o aceite segue sem esperar a conexao
            val call = serviceScope.async(Dispatchers.IO) {
                TrafficRouteClient.minutesWithTraffic(config.trafficApiKey.trim(), me, pick, drop, TRAFFIC_BUDGET_MS.toInt())
            }
            val res = call.await() ?: return@withTimeoutOrNull null
            TrafficCheck.judge(parsed, res.minutes, config)
        }
        val ms = System.currentTimeMillis() - startedAt
        lastTrafficFingerprint = parsed.fingerprint
        lastTrafficVerdict = verdict
        if (verdict == null) {
            log("[TRANSITO] [${parsed.appSource}] sem resposta em ${ms} ms (internet/GPS/endereço): segui a estimativa da 99")
        } else {
            log("[TRANSITO] [${parsed.appSource}] ${verdict.summary()} em ${ms} ms • ${if (verdict.passes) "passa" else "NÃO passa"} nas regras")
        }
        return verdict
    }

    /**
     * Medicao da rota (passarela): anota a posicao do motorista (GPS) e o ponto do embarque/destino no mapa,
     * junto com o km/min que a 99/Uber mostrou. Fica so no registro do celular; depois comparamos com a
     * rota de moto real. Roda em paralelo: nao atrasa a decisao nem o toque.
     */
    private fun logRouteSample(parsed: ParsedRideOffer, config: FilterConfig, evalFix: DriverFix?, decision: String) {
        val inRide = dualCoordinator.primaryRide != null || System.currentTimeMillis() - lastActiveRideScreenAt <= 90_000L
        val fix = evalFix ?: runCatching { DriverLocationProvider.current(applicationContext) }.getOrNull()
        serviceScope.launch {
            fun pt(p: Pair<Double, Double>?) = p?.let { String.format(Locale.US, "%.5f,%.5f", it.first, it.second) } ?: "?"
            fun num(v: Double?, f: String) = v?.let { String.format(Locale.US, f, it) } ?: "?"
            val pick = parsed.pickupAddress?.let { a -> runCatching { evaluator.pointOf(a, config) }.getOrNull() }
            val drop = parsed.dropoffAddress?.let { a -> runCatching { evaluator.pointOf(a, config) }.getOrNull() }
            val me = fix?.let { String.format(Locale.US, "%.5f,%.5f ±%.0fm %ds", it.lat, it.lng, it.accuracyMeters, it.ageMs / 1000) } ?: "?"
            log(
                "[ROTA] ${parsed.appSource} | eu=$me | emb=${pt(pick)} ${num(parsed.pickupDistanceKm, "%.2f")}km " +
                    "${num(parsed.pickupMinutes, "%.0f")}min | dest=${pt(drop)} ${num(parsed.dropoffDistanceKm, "%.2f")}km " +
                    "${num(parsed.dropoffMinutes, "%.0f")}min | emCorrida=$inRide | decisao=$decision | " +
                    "Emb=${parsed.pickupAddress.orEmpty().take(60)} | Dest=${parsed.dropoffAddress.orEmpty().take(60)}"
            )
        }
    }

    /**
     * Tela "Sua oferta" da Negocia. So age quando FOI O APP que abriu o lapis (contraproposta em andamento):
     * escreve o valor, confere o que apareceu no campo e so entao toca em "Confirmar oferta".
     * Aberta por voce = o app nao toca em nada.
     */
    private suspend fun handleCounterOfferEditor(parsed: ParsedRideOffer, config: FilterConfig, now: Long) {
        val job = counterJob
        if (job == null || !job.active) {
            if (now - lastManualEditorLogAt > 30_000L) {
                lastManualEditorLogAt = now
                log("[NEGOCIA] Tela do lápis aberta por você: o app não toca em nada (campo: ${parsed.editorValue?.let { fmtBr(it) } ?: "?"})")
            }
            return
        }
        if (config.actionsPaused) {
            counterJob = job.copy(stage = CounterOfferFlow.Stage.ABORTED)
            onCounterOfferAborted(job, "o filtro entrou em PAUSA")
            return
        }
        if (!isOfferAppStillSafeToTap(config, job.app)) return
        // O nosso card nao segura toques, mas cobre o topo da tela do lapis na leitura: sai da frente
        if (job.stage == CounterOfferFlow.Stage.OPENING_EDITOR) overlayManager.hideBanner()
        val (next, action) = CounterOfferFlow.onEditor(job, parsed.editorValue, parsed.editorPlusTap, parsed.editorConfirmTap, now)
        counterJob = next
        val shown = parsed.editorValue?.let { fmtBr(it) } ?: "?"
        when (action) {
            is CounterOfferFlow.Action.SetText -> {
                val ok = setTextInFocusedField(action.text)
                log("[NEGOCIA] [${job.app}] escrevi ${action.text} no campo (aceitou=$ok; campo mostrava $shown)")
            }
            is CounterOfferFlow.Action.TapPlus -> {
                repeat(action.times) {
                    dispatchTap(action.at.first, action.at.second)
                    delay(170L)
                }
                log("[NEGOCIA] [${job.app}] o campo não aceitou o texto: toquei ${action.times}x em +0,5 (campo mostrava $shown)")
            }
            is CounterOfferFlow.Action.TapConfirm -> {
                val ok = dispatchTap(action.at.first, action.at.second)
                log("[NEGOCIA] [${job.app}] campo mostra R$ ${fmtBr(action.value)}: toquei em Confirmar oferta (sucesso=$ok)")
            }
            is CounterOfferFlow.Action.Abort -> onCounterOfferAborted(next, action.reason)
            else -> Unit
        }
    }

    /** Contraproposta em andamento e a tela nao e a do lapis: fechou depois de confirmar (= enviada) ou travou. */
    private fun advanceCounterOfferJob(now: Long) {
        val job = counterJob ?: return
        if (!job.active) return
        val (next, action) = CounterOfferFlow.onOtherScreen(job, now)
        counterJob = next
        when (action) {
            is CounterOfferFlow.Action.Sent -> {
                log("[NEGOCIA] [${job.app}] ✔ contraproposta de R$ ${fmtBr(action.value)} enviada (a tela do lápis fechou). Agora é com o passageiro.")
                if (kotlin.math.abs(action.value - job.target) > 0.009) {
                    ledger.onCounterOffer(job.app, job.fingerprint, action.value, now)
                }
            }
            is CounterOfferFlow.Action.Abort -> onCounterOfferAborted(next, action.reason)
            else -> Unit
        }
    }

    private fun onCounterOfferAborted(job: CounterOfferFlow.Job, reason: String) {
        val ask = fmtBr(job.target)
        log("[NEGOCIA] [${job.app}] ⚠ desisti da contraproposta: $reason. Se ainda der, peça R$ $ask você mesmo.")
        if (configRepository.load().showRideInfoBannerEnabled) {
            overlayManager.showRideCard(
                RideCardModel(
                    decision = Decision.UNCERTAIN,
                    appSource = job.app,
                    title = "Negocie: digite $ask",
                    statusLabel = "VOCÊ NEGOCIA",
                    checklist = "💬 Negocie: não consegui enviar sozinho\n💬 Negocie: no lápis, digite $ask",
                    autoHideMs = NEGOTIATE_CARD_MIN_MS
                ),
                null
            )
        }
    }

    /**
     * Escreve no campo com foco (a tela "Sua oferta" da 99) pela acessibilidade, como "colar":
     * nao usa o teclado, entao nao depende do teclado instalado.
     */
    private fun setTextInFocusedField(text: String): Boolean {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        runCatching { windows }.getOrNull()?.forEach { w ->
            val r = runCatching { w.root }.getOrNull() ?: return@forEach
            val pkg = r.packageName?.toString()?.lowercase().orEmpty()
            if (pkg.contains("99") && roots.none { it == r }) roots.add(r)
        }
        for (root in roots) {
            val pkg = root.packageName?.toString()?.lowercase().orEmpty()
            if (pkg == packageName.lowercase()) continue
            val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            val target = focused?.takeIf { it.isEditable } ?: findEditableNode(root, 0)
            if (target != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                if (runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)) {
                    return true
                }
            }
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            findEditableNode(child, depth + 1)?.let { return it }
        }
        return null
    }

    /** Aceite que deu certo: painel de Ganhos + trava 99/Uber (1a corrida ou encaixe na fila). */
    private fun registerAccepted(parsed: ParsedRideOffer, config: FilterConfig, priceFmt: String, now: Long) {
        ledger.onAccepted(parsed, now)
        dualCoordinator.onRideAccepted(parsed, config, priceFmt, now)?.let { evt ->
            log(evt.message)
            syncFloatingPill(config, true)
        }
    }

    /**
     * Botao do card "Aceitar se tocar de novo":
     *  - se a oferta ainda esta na tela (ninguem recusou), aceita agora;
     *  - senao, guarda a corrida por 20 min: se ela tocar de novo, o app aceita sozinho.
     */
    private fun onQuickAcceptTapped(parsed: ParsedRideOffer, signature: OfferSignature) {
        serviceScope.launch {
            val now = System.currentTimeMillis()
            val cfg = configRepository.load()
            val stillOnScreen = lastFingerprint == parsed.fingerprint &&
                now - lastOfferSeenAt < 2500L &&
                lastRejectOfferFingerprint != parsed.fingerprint
            if (stillOnScreen && isOfferAppStillSafeToTap(cfg, parsed.appSource)) {
                val ok = performAcceptForOffer(parsed)
                log("[LIBERADA] Botão do card: oferta ainda na tela, aceite disparado (sucesso=$ok) • ${parsed.pickupAddress} → ${parsed.dropoffAddress}")
                if (ok) {
                    registerAccepted(parsed, cfg, parsed.ridePrice?.let { String.format(Locale("pt", "BR"), "R$ %.2f", it) } ?: "", now)
                    overlayManager.confirmQuickAction("✓ Aceita")
                    return@launch
                }
            }
            acceptOverrides.removeAll { it.sig.matches(signature) }
            acceptOverrides.add(AcceptOverride(signature, now + QUICK_ACCEPT_WINDOW_MS))
            log(
                "[LIBERADA] Você liberou esta corrida: se ela tocar de novo nos próximos ${QUICK_ACCEPT_WINDOW_MS / 60_000} min, " +
                    "o app aceita sozinho • ${parsed.appSource}: ${parsed.pickupAddress} → ${parsed.dropoffAddress}"
            )
            overlayManager.confirmQuickAction("✓ Aceito se tocar de novo")
        }
    }

    /**
     * Junta o card do filtro (em cima) com a foto da oferta (embaixo) no MESMO arquivo da foto,
     * para comparar o que o app decidiu com o que a 99/Uber mostrou.
     */
    private fun composeShotWithCard(path: String, card: Bitmap, caption: String) {
        try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val shot = BitmapFactory.decodeFile(path, opts)
            if (shot == null) {
                card.recycle()
                return
            }
            val w = shot.width
            val pad = (w * 0.03f).toInt()
            val cardW = w - pad * 2
            val cardScale = cardW.toFloat() / card.width
            val cardH = (card.height * cardScale).toInt()
            val capH = (w * 0.07f).toInt()
            val out = Bitmap.createBitmap(w, pad + capH + cardH + pad + shot.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(android.graphics.Color.parseColor("#101010"))
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#CCFFFFFF")
                textSize = capH * 0.55f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            canvas.drawText(caption, pad.toFloat(), pad + capH * 0.7f, textPaint)
            canvas.drawBitmap(card, null, Rect(pad, pad + capH, pad + cardW, pad + capH + cardH), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawBitmap(shot, 0f, (pad + capH + cardH + pad).toFloat(), null)
            FileOutputStream(path).use { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            shot.recycle()
            out.recycle()
            card.recycle()
        } catch (e: Throwable) {
            log("⚠ Foto com o card: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun executeSurgicalReject(
        parsed: ParsedRideOffer,
        config: FilterConfig,
        appTag: String,
        now: Long
    ) {
        dualCoordinator.forgetCandidateOffer(parsed.appSource)
        lastActionAt = now
        lastActionAtByApp[parsed.appSource] = now
        if (config.humanDelayEnabled) {
            delay(Random.nextLong(240L, 420L))
        } else if (parsed.appSource == "UBER") {
            // Aguarda 240ms para o BottomSheet da Uber terminar a animacao de subida antes do input tap
            delay(240L)
        }
        if (!isOfferAppStillSafeToTap(config, parsed.appSource, parsed.rejectTapPoint)) {
            log("Auto-recusa $appTag cancelada: janela mudou durante o atraso (protecao contra toque errado)")
            return
        }
        val attempts = if (parsed.fingerprint == lastRejectOfferFingerprint &&
            now - lastRejectOfferAt < 10_000L
        ) {
            lastRejectOfferAttempts + 1
        } else {
            lastRejectOfferFingerprint = parsed.fingerprint
            1
        }
        lastRejectOfferAttempts = attempts
        lastRejectOfferAt = now
        if (parsed.appSource == "UBER" && attempts > 2) {
            return
        }
        val clicked = performSinglePrecisionReject(parsed, attempt = attempts)
        val targets = parsed.rejectTapTargets.ifEmpty { listOfNotNull(parsed.rejectTapPoint) }
        val pt = if (targets.isNotEmpty()) targets[(attempts - 1) % targets.size] else parsed.rejectTapPoint
        log("Auto-recusa cirurgica $appTag #$attempts disparada em (${pt?.first?.toInt()}x${pt?.second?.toInt()}, alvos=${targets.size}, sucesso=$clicked)")
    }

    private fun resetActiveBannerOffer() {
        activeBannerApp = ""
        activeBannerPrice = null
        activeBannerPickup = ""
        activeBannerDropoff = ""
        lastFingerprint = ""
        lastSavedShotFingerprint = ""
    }

    private fun isSameActiveBannerOffer(parsed: ParsedRideOffer): Boolean {
        if (activeBannerApp.isEmpty() || activeBannerApp != parsed.appSource) return false
        if (activeBannerPrice != parsed.ridePrice) return false
        val p = parsed.pickupAddress?.trim()?.lowercase().orEmpty()
        val d = parsed.dropoffAddress?.trim()?.lowercase().orEmpty()
        val pMatch = activeBannerPickup.isEmpty() || p.isEmpty() ||
            p.contains(activeBannerPickup.take(12)) || activeBannerPickup.contains(p.take(12))
        val dMatch = activeBannerDropoff.isEmpty() || d.isEmpty() ||
            d.contains(activeBannerDropoff.take(12)) || activeBannerDropoff.contains(d.take(12))
        return pMatch && dMatch
    }

    private fun isOfferAppStillSafeToTap(
        config: FilterConfig,
        appSource: String,
        rejectTapPoint: Pair<Float, Float>? = null
    ): Boolean {
        val state = resolveVisibleDriverState(config, applyAntiOscillationHold = true)
        val ownPkg = packageName.lowercase()
        val rootPkg = rootInActiveWindow?.packageName?.toString()?.lowercase().orEmpty()
        val now = System.currentTimeMillis()
        return when (appSource) {
            "UBER" -> {
                val uBounds = state.uberWindowBounds
                val hasFloatingUberWindow = uBounds != null &&
                    parsedRejectContains(uBounds, rejectTapPoint)
                if ((rootPkg == ownPkg || state.resolvedPkg == ownPkg) && !hasFloatingUberWindow) {
                    false
                } else {
                    config.uberModuleEnabled && (
                        state.preferredAppSource == "UBER" ||
                            hasFloatingUberWindow ||
                            (now - lastUberOfferSeenAt <= 2500L && state.resolvedPkg != ownPkg)
                        )
                }
            }
            "99" -> {
                if (rootPkg == ownPkg || state.resolvedPkg == ownPkg) {
                    false
                } else {
                    config.nineNineModuleEnabled && (
                        state.preferredAppSource == "99" ||
                            (!state.hasFullscreenNonDriverApp && state.preferredAppSource != "UBER" && state.uberWindowBounds == null)
                        )
                }
            }
            else -> rootPkg != ownPkg && state.resolvedPkg != ownPkg && state.isMonitored
        }
    }

    private fun parsedRejectContains(bounds: Rect, pt: Pair<Float, Float>?): Boolean {
        if (pt == null) return true
        return bounds.contains(pt.first.toInt(), pt.second.toInt())
    }

    /**
     * Dispara APENAS 1 toque cirurgico por captura de tela no botao X.
     * Na 99 prioriza ACTION_CLICK no no nativo; na Uber prioriza toque via Shizuku (`input tap`)
     * se disponivel, com fallback para gesto estatico e ACTION_CLICK.
     */
    private suspend fun performSinglePrecisionReject(parsed: ParsedRideOffer, attempt: Int = 1): Boolean {
        val targets = parsed.rejectTapTargets.ifEmpty { listOfNotNull(parsed.rejectTapPoint) }
        val target = if (targets.isNotEmpty()) targets[(attempt - 1) % targets.size] else null

        if (!parsed.appSource.equals("UBER", ignoreCase = true)) {
            parsed.rejectNode?.let { n ->
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            if (target != null) {
                clearHeadsUpOver(target.first, target.second)
                return dispatchTap(target.first, target.second)
            }
            return false
        }

        if (target != null) {
            return performUberTap(target.first, target.second)
        }
        if (attempt >= 2 && parsed.rejectNode != null) {
            val nodeClicked = runCatching {
                parsed.rejectNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (nodeClicked) return true
        }
        parsed.rejectNode?.let { n ->
            val rect = Rect()
            n.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                return performUberTap(rect.exactCenterX(), rect.exactCenterY())
            }
        }
        return false
    }

    private suspend fun performAcceptForOffer(parsed: ParsedRideOffer): Boolean {
        if (parsed.appSource.equals("UBER", ignoreCase = true)) {
            parsed.acceptTapPoint?.let { (x, y) ->
                return performUberTap(x, y)
            }
            parsed.acceptNode?.let { n ->
                val rect = Rect()
                n.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    return performUberTap(rect.exactCenterX(), rect.exactCenterY())
                }
            }
            return false
        }
        return performNodeOrTap(parsed.acceptNode, parsed.acceptTapPoint)
    }

    private suspend fun performUberTap(x: Float, y: Float): Boolean {
        overlayManager.markAutomatedTapInProgress(950L)
        val standaloneOk = withContext(Dispatchers.IO) {
            StandaloneAdbBridge.tryTap(applicationContext, x, y)
        }
        if (standaloneOk) {
            log("✔ Toque Independente (ADB) entregue em (${x.toInt()}x${y.toInt()})")
            return true
        }
        if (ShizukuTapBridge.hasPermission()) {
            val tapped = withContext(Dispatchers.IO) {
                ShizukuTapBridge.tryTap(applicationContext, x, y)
            }
            if (tapped) {
                log("✔ Toque via Shizuku entregue em (${x.toInt()}x${y.toInt()})")
                return true
            }
        }
        return dispatchTap(x, y)
    }

    private fun performNodeOrTap(
        node: AccessibilityNodeInfo?,
        tapPoint: Pair<Float, Float>?
    ): Boolean {
        node?.let { n ->
            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            val rect = Rect()
            n.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                return dispatchTap(rect.exactCenterX(), rect.exactCenterY())
            }
        }
        tapPoint?.let { (x, y) ->
            return dispatchTap(x, y)
        }
        return false
    }

    /**
     * Notificacao do Android (heads-up) por cima do ponto do toque: o toque caia nela e nao na 99
     * (18/11 19:09 e 19:38: recusa precisou de 3 a 5 toques). Arrasta a notificacao para cima antes de tocar.
     */
    private suspend fun clearHeadsUpOver(x: Float, y: Float): Boolean {
        val wins = runCatching { windows }.getOrNull() ?: return false
        val r = Rect()
        for (w in wins) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            w.getBoundsInScreen(r)
            // a barra de status (ate ~130 px) nao conta; a notificacao e bem mais alta
            if (r.height() < 150 || !r.contains(x.toInt(), y.toInt())) continue
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull().orEmpty().lowercase()
            if (!pkg.contains("systemui")) continue
            val cx = r.exactCenterX()
            val path = Path().apply {
                moveTo(cx, r.exactCenterY())
                lineTo(cx, (r.top - 20).coerceAtLeast(1).toFloat())
            }
            overlayManager.markAutomatedTapInProgress(950L)
            val ok = dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, 180L)).build(),
                null,
                null
            )
            log("[RECUSA] Notificação do Android cobrindo o toque (${r.left},${r.top}-${r.right},${r.bottom}): arrastei para cima antes de tocar (ok=$ok)")
            delay(380L)
            return ok
        }
        return false
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        overlayManager.markAutomatedTapInProgress(950L)
        // Toque 100% estatico (ponto unico sem lineTo) para compatibilidade com Jetpack Compose
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                log("✔ Toque entregue em (${x.toInt()}x${y.toInt()})")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                log("⚠️ Gesto cancelado pelo Android em (${x.toInt()}x${y.toInt()})")
            }
        }
        return dispatchGesture(gesture, callback, null)
    }

    private suspend fun captureScreenBitmap(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hwBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                            val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap?.recycle()
                            hwBuffer.close()
                            if (cont.isActive) cont.resume(softBitmap)
                        } catch (e: Exception) {
                            Log.e(TAG, "Falha ao converter screenshot", e)
                            if (cont.isActive) cont.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private suspend fun recognizeText(bitmap: Bitmap): Text? = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { result ->
                if (cont.isActive) cont.resume(result)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(null)
            }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        ConfigRepository.appendLog("[$time] $message", applicationContext)
    }

    override fun onInterrupt() {
        log("[SERVICO] O Android interrompeu a leitura de tela (onInterrupt)")
        overlayManager.hideAll()
    }

    private fun maybeAutoWatchStandaloneAdb(config: FilterConfig) {
        val appCtx = applicationContext
        if (!config.uberModuleEnabled ||
            !StandaloneAdbBridge.isPaired(appCtx) ||
            !StandaloneAdbBridge.isWifiConnected(appCtx)
        ) {
            StandaloneAdbBridge.stopConnectAutoMonitor()
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            if (StandaloneAdbBridge.isDaemonAlive(appCtx)) {
                StandaloneAdbBridge.stopConnectAutoMonitor()
                return@launch
            }
            StandaloneAdbBridge.startConnectAutoMonitor(appCtx) { res ->
                if (res.outcome == StandaloneAdbBridge.ConnectOutcome.CONNECTED) {
                    log("✔ ${res.detail}")
                    // Se fomos nos que ligamos a Depuracao por Wi-Fi, desligamos de novo: o motor continua rodando
                    SetupChecks.restoreWirelessDebuggingIfWeEnabled(appCtx)
                    AdbPairingReceiver.showResultNotification(appCtx, ok = true, message = res.detail)
                }
            }
            // Depois de reiniciar o celular: ja pareado + num Wi-Fi -> o proprio app liga a Depuracao por Wi-Fi
            // (o monitor acima conecta e sobe o motor). Precisa do pareamento feito nesta versao ou mais nova.
            if (SetupChecks.maybeAutoEnableWirelessDebugging(appCtx)) {
                log("Religando o motor interno sozinho (Depuração por Wi-Fi ligada pelo app)...")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::ledger.isInitialized) runCatching { ledger.flush() }
        log("Servico encerrado (onDestroy)")
        running = false
        FilterTileService.requestRefresh(applicationContext)
        overlayManager.hideAll()
        StandaloneAdbBridge.stopConnectAutoMonitor()
        ShizukuTapBridge.release(applicationContext)
        scannerLoopJob?.cancel()
        serviceScope.cancel()
    }

    companion object {
        private const val QUICK_ACCEPT_LABEL = "Aceitar se tocar de novo"
        private const val QUICK_ACCEPT_WINDOW_MS = 20 * 60_000L
        /** Card da Negocia fica pelo menos 12 s: da tempo de ler quanto pedir antes de tocar no botao da 99. */
        private const val NEGOTIATE_CARD_MIN_MS = 12_000L
        /** Tempo maximo esperando o transito antes de aceitar pela estimativa da 99. */
        private const val TRAFFIC_BUDGET_MS = 1_500L

        private const val TAG = "UberFilter"

        /** true entre onServiceConnected e onDestroy: separa "ligado na Acessibilidade" de "rodando de verdade". */
        @Volatile
        var running: Boolean = false

        @Volatile
        private var offRequested: Boolean = false

        private var lastConfigSummary: Map<String, String> = emptyMap()
        private var lastLoggedForeground: String = ""
        private var lastScannerErrorLogAt: Long = 0L
        private const val FAV_CHANNEL_ID = "uber_filter_favorites_alerts"
    }
}
