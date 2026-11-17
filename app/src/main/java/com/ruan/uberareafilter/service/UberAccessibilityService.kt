package com.ruan.uberareafilter.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import android.graphics.Path
import android.graphics.Rect
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
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
import com.ruan.uberareafilter.engine.AreaEvaluator
import com.ruan.uberareafilter.engine.Decision
import com.ruan.uberareafilter.engine.DualRideCoordinator
import com.ruan.uberareafilter.engine.FuelCalculator
import com.ruan.uberareafilter.engine.ParsedRideOffer
import com.ruan.uberareafilter.model.FavoriteRideItem
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.model.RideHistoryItem
import com.ruan.uberareafilter.overlay.OverlayManager
import com.ruan.uberareafilter.overlay.RideCardModel
import com.ruan.uberareafilter.shizuku.ShizukuTapBridge
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
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
        configRepository = ConfigRepository(applicationContext)
        evaluator = AreaEvaluator(applicationContext)
        overlayManager = OverlayManager(this)
        ShizukuTapBridge.init(applicationContext)
        ensureNotificationChannel()
        val mode = CaptureMode.from(configRepository.load())
        lastLoggedModeLabel = mode.label
        log("Servico iniciado (Modo=${mode.label} | ADB Independente=${StandaloneAdbBridge.statusLabel(applicationContext)} | Shizuku=${ShizukuTapBridge.statusLabel(applicationContext)}).")
        if (StandaloneAdbBridge.isPaired(applicationContext) && !StandaloneAdbBridge.isDaemonAlive(applicationContext)) {
            serviceScope.launch(Dispatchers.IO) {
                val res = StandaloneAdbBridge.connectAndStartDaemon(applicationContext)
                if (res.outcome == StandaloneAdbBridge.ConnectOutcome.CONNECTED) {
                    log("✔ ${res.detail}")
                }
            }
        }
        startPeriodicScanner()
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
            isFilterActive = config.isEnabled,
            onToggleFilter = {
                val latest = configRepository.load()
                val newState = !latest.isEnabled
                configRepository.setEnabledQuick(newState)
                syncFloatingPill(latest.copy(isEnabled = newState), true)
                log(if (newState) "Filtro REATIVADO pelo botao flutuante" else "Filtro PAUSADO pelo botao flutuante")
            },
            onLongPressFavorite = {
                triggerHeartPromptManualOrRating(passengerName = null)
            },
            rideLockBadgeText = lockBadgeText,
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

    private fun startPeriodicScanner() {
        scannerLoopJob?.cancel()
        scannerLoopJob = serviceScope.launch {
            while (isActive) {
                val nextDelayMs = 780L
                try {
                    val config = configRepository.load()
                    val now = System.currentTimeMillis()
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
                    syncFloatingPill(config, state.isMonitored)

                    if (config.isEnabled && (state.isMonitored || hasQuietUberProbe)) {
                        scanActiveScreen(
                            config = config,
                            visibleState = state,
                            quietProbeOnly = !state.isMonitored
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no scanner", e)
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
        val bitmap = captureScreenBitmap() ?: return
        val visionText = recognizeText(bitmap)
        val width = bitmap.width
        val height = bitmap.height

        if (visionText == null) {
            bitmap.recycle()
            return
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
            val bgTag = if (quietProbeOnly) " [2o-PLANO]" else ""
            log(
                "OCR [${ocrParsed.appSource}]$bgTag (OFERTA): R$/km=${ocrParsed.pricePerKm?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A"} | " +
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
        }.take(28).joinToString(" | ")
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

        dualCoordinator.onScreenObserved(parsed, config, now)?.let { evt ->
            log(evt.message)
            if (evt.unlocked) {
                lastFingerprint = ""
                lastSavedShotFingerprint = ""
            }
            syncFloatingPill(config, true)
        }

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
            }
        )
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
        config: FilterConfig,
        source: String,
        pendingShotBytes: ByteArray? = null
    ) {
        val now = System.currentTimeMillis()
        val isSameOffer = parsed.fingerprint == lastFingerprint
        val appActionAt = lastActionAtByApp[parsed.appSource] ?: 0L

        val result = evaluator.evaluate(parsed, config)
        val conflict = dualCoordinator.evaluateOfferConflict(parsed, config)
        val wantsAutoReject = result.decision == Decision.OUTSIDE_AREA ||
            conflict.action == DualRideCoordinator.ConflictAction.AUTO_REJECT_OTHER_APP

        if (isSameOffer) {
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
        log("[$source] Decisao: ${result.decision} -> ${result.reason} | ${result.checklistText}")

        val br = Locale("pt", "BR")
        val priceFmt = parsed.ridePrice?.let { String.format(br, "R$ %.2f", it) } ?: ""
        dualCoordinator.rememberCandidateOffer(parsed, priceFmt, now)
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

        if (!isSameOffer && (!parsed.pickupAddress.isNullOrBlank() || !parsed.dropoffAddress.isNullOrBlank() || parsed.ridePrice != null)) {
            val timeShort = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val savedShotPath = if (pendingShotBytes != null && config.saveRideScreenshotsEnabled) {
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
                isInsideArea = result.decision == Decision.INSIDE_AREA || result.decision == Decision.INTER_ZONE,
                reason = result.reason,
                screenshotPath = savedShotPath
            )
            configRepository.addRideHistory(historyItem)
        }

        val checklist = result.checklistText.ifBlank { result.reason }
        val bannerHideMs = config.bannerAutoHideSeconds.coerceIn(1, 30) * 1000L
        val sameBannerOffer = isSameActiveBannerOffer(parsed)

        fun maybeShowBanner(model: RideCardModel) {
            if (!config.showRideInfoBannerEnabled) return
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
            overlayManager.showRideCard(model)
        }

        // Opcao 2 do Controle de Conflito: recusa automaticamente TODAS as corridas do outro app durante viagem ativa
        if (conflict.action == DualRideCoordinator.ConflictAction.AUTO_REJECT_OTHER_APP) {
            val active = conflict.activeRide
            val activeDest = active?.dropoffAddress?.ifBlank { active.pickupAddress }.orEmpty()
            val conflictChecklist = buildString {
                append("🔒 Em viagem [${active?.appSource ?: "?"}]")
                if (activeDest.isNotBlank()) append(" → Dest: $activeDest")
                append("\n")
                append(checklist)
            }
            maybeShowBanner(
                RideCardModel(
                    decision = Decision.OUTSIDE_AREA,
                    appSource = parsed.appSource,
                    title = "Conflito (${active?.appSource ?: "em corrida"})",
                    statusLabel = if (config.autoRejectEnabled) "RECUSANDO CONFLITO" else "BLOQUEADO",
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
            if (config.autoRejectEnabled) {
                executeSurgicalReject(parsed, config, appTag, now)
            }
            return
        }

        val blockedByConflict = conflict.action == DualRideCoordinator.ConflictAction.BLOCK_AUTO_ACCEPT_ONLY

        when (result.decision) {
            Decision.INSIDE_AREA -> {
                val active = conflict.activeRide
                val activeDest = active?.dropoffAddress?.ifBlank { active.pickupAddress }.orEmpty()
                val titleText = if (blockedByConflict) "🔒 Em viagem (${active?.appSource ?: "?"})" else "Dentro da área"
                val statusText = when {
                    blockedByConflict -> "DUPLO ACEITE BLOQUEADO"
                    config.autoAcceptInsideArea -> "ACEITANDO"
                    else -> "VOCÊ DECIDE"
                }
                val finalChecklist = if (blockedByConflict) {
                    "🔒 ${active?.appSource} ativo${if (activeDest.isNotBlank()) " (Dest: $activeDest)" else ""}\n$checklist"
                } else {
                    checklist
                }
                maybeShowBanner(
                    RideCardModel(
                        decision = Decision.INSIDE_AREA,
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
                        autoHideMs = bannerHideMs
                    )
                )

                val lastAcceptAt = lastActionAtByApp[parsed.appSource] ?: 0L
                if (blockedByConflict) {
                    log("🔒 Auto-aceite bloqueado $appTag: em corrida no app ${active?.appSource} (Dest: $activeDest)")
                } else if (config.autoAcceptInsideArea && now - lastAcceptAt > 1200L) {
                    lastActionAt = now
                    lastActionAtByApp[parsed.appSource] = now
                    if (config.humanDelayEnabled) {
                        delay(Random.nextLong(280L, 520L))
                    }
                    if (!isOfferAppStillSafeToTap(config, parsed.appSource)) {
                        log("Auto-aceitar $appTag cancelado: janela mudou durante o atraso (protecao contra toque errado)")
                    } else {
                        val accepted = performAcceptForOffer(parsed)
                        log("Auto-aceitar $appTag disparado em ${parsed.acceptTapPoint} (sucesso=$accepted)")
                        if (accepted) {
                            dualCoordinator.onRideAccepted(parsed, config, priceFmt, now)?.let { evt ->
                                log(evt.message)
                                syncFloatingPill(config, true)
                            }
                        }
                    }
                }
            }

            Decision.INTER_ZONE -> {
                val active = conflict.activeRide
                val activeDest = active?.dropoffAddress?.ifBlank { active.pickupAddress }.orEmpty()
                val statusText = when {
                    blockedByConflict -> "DUPLO ACEITE BLOQUEADO"
                    config.autoAcceptInterZone -> "ACEITANDO"
                    else -> "VOCÊ DECIDE"
                }
                val finalChecklist = if (blockedByConflict) {
                    "🔒 ${active?.appSource} ativo${if (activeDest.isNotBlank()) " (Dest: $activeDest)" else ""}\n$checklist"
                } else {
                    checklist
                }
                maybeShowBanner(
                    RideCardModel(
                        decision = Decision.INTER_ZONE,
                        appSource = parsed.appSource,
                        title = if (blockedByConflict) "🔒 Em viagem (${active?.appSource ?: "?"})" else "Corrida entre zonas?",
                        statusLabel = statusText,
                        priceText = priceFmt.ifEmpty { null },
                        hourlyText = hrFmt.ifEmpty { null },
                        secondaryMetrics = secondaryList,
                        fuelText = fuelEstimate?.costText,
                        profitText = profitLabel,
                        profitPositive = (fuelEstimate?.netProfit ?: 1.0) > 0.0,
                        checklist = finalChecklist,
                        autoHideMs = bannerHideMs
                    )
                )

                val lastAcceptAt = lastActionAtByApp[parsed.appSource] ?: 0L
                if (blockedByConflict) {
                    log("🔒 Auto-aceite entre zonas bloqueado $appTag: em corrida no app ${active?.appSource}")
                } else if (config.autoAcceptInterZone && now - lastAcceptAt > 1200L) {
                    lastActionAt = now
                    lastActionAtByApp[parsed.appSource] = now
                    if (config.humanDelayEnabled) {
                        delay(Random.nextLong(280L, 520L))
                    }
                    if (!isOfferAppStillSafeToTap(config, parsed.appSource)) {
                        log("Auto-aceitar entre zonas $appTag cancelado: janela mudou durante o atraso")
                    } else {
                        val accepted = performAcceptForOffer(parsed)
                        log("Auto-aceitar entre zonas $appTag disparado em ${parsed.acceptTapPoint} (sucesso=$accepted)")
                        if (accepted) {
                            dualCoordinator.onRideAccepted(parsed, config, priceFmt, now)?.let { evt ->
                                log(evt.message)
                                syncFloatingPill(config, true)
                            }
                        }
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
            }

            Decision.OUTSIDE_AREA -> {
                if (config.autoRejectEnabled) {
                    maybeShowBanner(
                        RideCardModel(
                            decision = Decision.OUTSIDE_AREA,
                            appSource = parsed.appSource,
                            title = "Fora dos critérios",
                            statusLabel = "RECUSANDO",
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
                    executeSurgicalReject(parsed, config, appTag, now)
                } else {
                    maybeShowBanner(
                        RideCardModel(
                            decision = Decision.OUTSIDE_AREA,
                            appSource = parsed.appSource,
                            title = "Fora dos critérios",
                            statusLabel = "RECUSA OFF",
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
                }
            }

            Decision.UNKNOWN_KEEP -> {
                // Nao exibe o banner cinza "AGUARDANDO LEITURA" para nao poluir a tela (aguarda proximo frame OCR)
            }
        }
    }

    private suspend fun executeSurgicalReject(
        parsed: ParsedRideOffer,
        config: FilterConfig,
        appTag: String,
        now: Long
    ) {
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
                    AdbPairingReceiver.showResultNotification(appCtx, ok = true, message = res.detail)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayManager.hideAll()
        StandaloneAdbBridge.stopConnectAutoMonitor()
        ShizukuTapBridge.release(applicationContext)
        scannerLoopJob?.cancel()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "UberFilter"
        private const val FAV_CHANNEL_ID = "uber_filter_favorites_alerts"
    }
}
