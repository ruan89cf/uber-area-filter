package com.ruan.uberareafilter.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ruan.uberareafilter.driver.AppCaptureRouter
import com.ruan.uberareafilter.engine.AreaEvaluator
import com.ruan.uberareafilter.engine.Decision
import com.ruan.uberareafilter.engine.ParsedRideOffer
import com.ruan.uberareafilter.model.FavoriteRideItem
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.model.RideHistoryItem
import com.ruan.uberareafilter.overlay.OverlayManager
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
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private var scannerLoopJob: Job? = null
    private var currentForegroundPkg: String = ""
    private var lastLoggedPkg: String = ""
    private var lastFingerprint: String = ""
    private var lastEvaluatedAt: Long = 0L
    private var lastActionAt: Long = 0L
    private var lastContinueClickAt: Long = 0L
    private var lastHeartShownAt: Long = 0L
    private var lastFavoriteAlertCheckAt: Long = 0L
    private var lastUberEventAt: Long = 0L
    private val sentFavoriteAlerts = mutableSetOf<String>()

    // Somente apps de MOTORISTA (evita monitorar com.ubercab passageiro, com.taxis99 passageiro, uberchip, digio.uber...)
    private val nineNinePkgKeywords = listOf("com.app99.driver", "com.didiglobal.driver", "com.didi.driver")
    private val uberPkgKeywords = listOf("com.ubercab.driver")
    private val debugExtraPkgKeywords = listOf("photos", "gallery", "galeria")
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
        overlayManager = OverlayManager(applicationContext)
        ensureNotificationChannel()
        log("Servico iniciado (Modulos: 99 + Uber | Fechamento Cirurgico + Multi-Zonas + Logs em Disco).")
        startPeriodicScanner()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()?.lowercase().orEmpty()
        if (pkg.isEmpty() || pkg == packageName.lowercase() || pkg in ignoredTransientPackages || pkg.contains("inputmethod")) {
            return
        }

        val config = configRepository.load()
        if (config.uberModuleEnabled && uberPkgKeywords.any { pkg.contains(it) }) {
            lastUberEventAt = System.currentTimeMillis()
        }

        // So troca o pacote principal se for um pacote monitorado (99/Uber) ou uma mudanca real de janela (TYPE_WINDOW_STATE_CHANGED)
        val isDriverPkg = isTargetPackage(pkg, config)
        if (isDriverPkg || event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentForegroundPkg = pkg
            val monitored = isDriverAppVisibleOnScreen(config)
            syncFloatingPill(config, monitored)

            if (config.debugMode && pkg != lastLoggedPkg) {
                lastLoggedPkg = pkg
                log("App em tela: $pkg (${if (monitored) "MONITORADO" else "ignorado"})")
            }
        }
    }

    /**
     * Verifica se o app de motorista (99 / Uber) esta aberto em tela cheia OU se a Uber abriu
     * sua janela flutuante de corrida sobre outro app (inclusive quando a Uber nao esta em 1o plano),
     * ignorando apenas as bolhas pequenas (icones redondos < 28% da altura da tela).
     */
    private fun isDriverAppVisibleOnScreen(config: FilterConfig): Boolean {
        val rootPkg = rootInActiveWindow?.packageName?.toString()?.lowercase().orEmpty()

        // Nunca executa cliques automaticos em cima da nossa propria tela de configuracao
        if (rootPkg == packageName.lowercase()) {
            currentForegroundPkg = rootPkg
            return false
        }

        // 1. Verifica todas as janelas interativas na tela (captura tanto app em 1o plano quanto janela sobreposta da Uber >= 28% da tela)
        val minCardWindowHeight = (resources.displayMetrics.heightPixels * 0.28f).toInt()
        try {
            val activeWindows = windows
            if (activeWindows != null) {
                val rect = Rect()
                for (win in activeWindows) {
                    val wPkg = win.root?.packageName?.toString()?.lowercase().orEmpty()
                    if (wPkg == packageName.lowercase()) continue
                    if (isTargetPackage(wPkg, config)) {
                        win.getBoundsInScreen(rect)
                        // Ignora a bolha redonda pequena (< 28% da tela), mas aceita card flutuante da Uber (~45-55%) e tela cheia
                        if (rect.height() >= minCardWindowHeight) {
                            currentForegroundPkg = wPkg
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Verifica se a janela principal ativa e 99 ou Uber
        if (rootPkg.isNotEmpty() && rootPkg !in ignoredTransientPackages && !rootPkg.contains("inputmethod")) {
            if (isTargetPackage(rootPkg, config)) {
                currentForegroundPkg = rootPkg
                return true
            }
            // Se a Uber disparou evento nos ultimos 3,5 segundos (ex: pop-up de corrida sobre tela inicial/outro app), permite escanear
            if (config.uberModuleEnabled && System.currentTimeMillis() - lastUberEventAt < 3500L) {
                return true
            }
            if (rootPkg.contains("launcher") ||
                rootPkg.contains("whatsapp") ||
                rootPkg.contains("quicksearchbox") ||
                rootPkg.contains("settings")
            ) {
                currentForegroundPkg = rootPkg
                return false
            }
        }

        if (config.uberModuleEnabled && System.currentTimeMillis() - lastUberEventAt < 3500L) {
            return true
        }

        return isTargetPackage(currentForegroundPkg, config)
    }

    private fun syncFloatingPill(config: FilterConfig, isMonitoredApp: Boolean) {
        if (!isMonitoredApp) {
            overlayManager.hideBanner()
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
            }
        )
    }

    private fun startPeriodicScanner() {
        scannerLoopJob?.cancel()
        scannerLoopJob = serviceScope.launch {
            while (isActive) {
                val nextDelayMs = 800L
                try {
                    val config = configRepository.load()
                    val now = System.currentTimeMillis()

                    if (config.favoriteAlertsEnabled && now - lastFavoriteAlertCheckAt > 25_000L) {
                        lastFavoriteAlertCheckAt = now
                        checkUpcomingFavoriteAlerts()
                    }

                    val isMonitored = isDriverAppVisibleOnScreen(config)
                    syncFloatingPill(config, isMonitored)

                    if (config.isEnabled && isMonitored) {
                        scanActiveScreen(config)
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

    private suspend fun scanActiveScreen(config: FilterConfig) {
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
            config = config
        )
        bitmap.recycle()
        if (ocrParsed == null) return

        if (config.debugMode && ocrParsed.allTexts.isNotEmpty() && ocrParsed.isRideOffer) {
            val totalKmStr = ocrParsed.totalDistanceKm?.let { String.format(Locale.US, "%.1fkm", it) } ?: "?"
            val pickupKmStr = ocrParsed.pickupDistanceKm?.let { String.format(Locale.US, "%.1fkm", it) } ?: "?"
            val hrStr = ocrParsed.hourlyRate?.let { String.format(Locale.US, "R$%.0f/h", it) } ?: "?"
            val stopsStr = if (ocrParsed.hasMultipleStops) " | Paradas=${ocrParsed.stopsCount}" else ""
            log(
                "OCR [${ocrParsed.appSource}] (OFERTA): R$/km=${ocrParsed.pricePerKm?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A"} | " +
                    "$hrStr | EmbKm=$pickupKmStr | Tot=$totalKmStr$stopsStr | Emb=${ocrParsed.pickupAddress ?: "N/A"} | Dest=${ocrParsed.dropoffAddress ?: "N/A"}"
            )
        }

        processParsedScreen(ocrParsed, config, source = "OCR-${ocrParsed.appSource}")
    }

    private suspend fun processParsedScreen(
        parsed: ParsedRideOffer,
        config: FilterConfig,
        source: String
    ) {
        val now = System.currentTimeMillis()

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
            handleOffer(parsed, config, source)
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
        source: String
    ) {
        val now = System.currentTimeMillis()
        val isSameOffer = parsed.fingerprint == lastFingerprint

        val result = evaluator.evaluate(parsed, config)

        if (isSameOffer) {
            if (result.decision != Decision.OUTSIDE_AREA || !config.autoRejectEnabled) {
                if (now - lastEvaluatedAt < 3500L) return
            } else {
                if (now - lastActionAt < 1300L) return
            }
        }

        lastFingerprint = parsed.fingerprint
        lastEvaluatedAt = now
        val appTag = "[${parsed.appSource}]"
        log("[$source] Decisao: ${result.decision} -> ${result.reason} | ${result.checklistText}")

        val priceFmt = parsed.ridePrice?.let { String.format(Locale("pt", "BR"), "R$ %.2f", it) } ?: ""
        val kmFmt = parsed.pricePerKm?.let { String.format(Locale("pt", "BR"), "R$ %.2f/km", it) } ?: ""
        val hrFmt = parsed.hourlyRate?.let { String.format(Locale("pt", "BR"), "R$ %.0f/h", it) } ?: ""
        val totKmFmt = parsed.totalDistanceKm?.let { String.format(Locale("pt", "BR"), "%.1f km", it) } ?: ""
        val ratingFmt = parsed.passengerRating?.let { String.format(Locale("pt", "BR"), "★ %.2f", it) }
            ?: if (parsed.isNewPassenger) "★ Novo" else ""
        val stopsFmt = if (parsed.hasMultipleStops) "🛑 ${parsed.stopsCount} parada(s)" else ""

        val metricsBadges = listOf(kmFmt, hrFmt, ratingFmt, totKmFmt, priceFmt, stopsFmt)
            .filter { it.isNotEmpty() }
            .joinToString(" • ")

        if (!isSameOffer && (!parsed.pickupAddress.isNullOrBlank() || !parsed.dropoffAddress.isNullOrBlank())) {
            val timeShort = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
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
                reason = result.reason
            )
            configRepository.addRideHistory(historyItem)
        }

        val checklist = result.checklistText.ifBlank { result.reason }

        when (result.decision) {
            Decision.INSIDE_AREA -> {
                val headline = if (config.autoAcceptInsideArea) {
                    "✅ $appTag DENTRO DA ÁREA • ACEITANDO..."
                } else {
                    "✅ $appTag DENTRO DA ÁREA • VOCÊ DECIDE"
                }
                overlayManager.showRideStatus(
                    decision = Decision.INSIDE_AREA,
                    headline = headline,
                    metricsLine = metricsBadges.ifEmpty { null },
                    detailLine = checklist,
                    autoHideMs = 6000L
                )

                if (config.autoAcceptInsideArea && now - lastActionAt > 1400L) {
                    lastActionAt = now
                    if (config.humanDelayEnabled) {
                        delay(Random.nextLong(280L, 520L))
                    }
                    val accepted = performNodeOrTap(parsed.acceptNode, parsed.acceptTapPoint)
                    log("Auto-aceitar $appTag disparado em ${parsed.acceptTapPoint} (sucesso=$accepted)")
                }
            }

            Decision.INTER_ZONE -> {
                // Card Roxo: corrida entre duas Zonas Permitidas diferentes (respeitando KM Maximo).
                // Nunca aceita nem recusa no automatico — destaca em roxo para o motorista decidir!
                overlayManager.showRideStatus(
                    decision = Decision.INTER_ZONE,
                    headline = "🟣 $appTag CORRIDA ENTRE ZONAS • VOCÊ DECIDE",
                    metricsLine = metricsBadges.ifEmpty { null },
                    detailLine = checklist,
                    autoHideMs = 7000L
                )
            }

            Decision.RETURN_TO_AREA -> {
                // Card Amarelo (Item 6): motorista fora da area definida, mas tocou corrida a <= 1,0 km!
                // Nao cancela automatico — exibe card amarelo para o motorista pegar e se encaminhar para a area!
                overlayManager.showRideStatus(
                    decision = Decision.RETURN_TO_AREA,
                    headline = "🟡 $appTag FORA DA ÁREA (EMB ≤ 1KM) • VOCÊ DECIDE",
                    metricsLine = metricsBadges.ifEmpty { null },
                    detailLine = checklist,
                    autoHideMs = 7000L
                )
            }

            Decision.OUTSIDE_AREA -> {
                if (config.autoRejectEnabled) {
                    lastActionAt = now
                    overlayManager.showRideStatus(
                        decision = Decision.OUTSIDE_AREA,
                        headline = "⚠️ $appTag FORA DOS CRITÉRIOS • RECUSANDO...",
                        metricsLine = metricsBadges.ifEmpty { null },
                        detailLine = checklist,
                        autoHideMs = 4500L
                    )
                    if (config.humanDelayEnabled) {
                        delay(Random.nextLong(240L, 420L))
                    }
                    val clicked = performSinglePrecisionReject(parsed)
                    val pt = parsed.rejectTapPoint
                    log("Auto-recusa cirurgica $appTag disparada em (${pt?.first?.toInt()}x${pt?.second?.toInt()}, sucesso=$clicked)")
                } else {
                    overlayManager.showRideStatus(
                        decision = Decision.OUTSIDE_AREA,
                        headline = "⚠️ $appTag FORA DOS CRITÉRIOS (AUTO-RECUSA OFF)",
                        metricsLine = metricsBadges.ifEmpty { null },
                        detailLine = checklist
                    )
                }
            }

            Decision.UNKNOWN_KEEP -> {
                // Nao exibe o banner cinza "AGUARDANDO LEITURA" para nao poluir a tela (aguarda proximo frame OCR)
            }
        }
    }

    /**
     * Dispara APENAS 1 toque cirurgico por captura de tela no botao X (92.2%, 6.3% quando parado
     * ou 91.5%, 37.8% quando com barra preta de GPS no topo).
     * NUNCA dispara 2o ou 3o toque cego apos o card fechar, evitando abrir o menu de saldo R$ 0,00
     * ou puxar a barra de navegacao GPS!
     */
    private fun performSinglePrecisionReject(parsed: ParsedRideOffer): Boolean {
        parsed.rejectNode?.let { n ->
            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        val target = parsed.rejectTapPoint ?: parsed.rejectTapTargets.firstOrNull()
        if (target != null) {
            return dispatchTap(target.first, target.second)
        }
        return false
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
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 65L))
            .build()
        return dispatchGesture(gesture, null, null)
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

    override fun onDestroy() {
        super.onDestroy()
        overlayManager.hideAll()
        scannerLoopJob?.cancel()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "UberFilter"
        private const val FAV_CHANNEL_ID = "uber_filter_favorites_alerts"
    }
}
