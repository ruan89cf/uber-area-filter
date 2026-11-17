package com.ruan.uberareafilter.driver

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import com.ruan.uberareafilter.engine.ParsedRideOffer
import com.ruan.uberareafilter.engine.RideOfferParser
import com.ruan.uberareafilter.engine.TextNormalizer
import com.ruan.uberareafilter.model.FilterConfig

/**
 * Arquitetura Modular de Captura por Aplicativo:
 * - Modulo 1: NineNineCaptureModule (99 Motorista)
 * - Modulo 2: UberCaptureModule (Uber Driver — suporta card em 1o plano, janela flutuante sobreposta e corridas com Paradas)
 * - Extensivel para futuros modulos (ex: iFood Entregador, Indrive, Lalamove)
 */
enum class CaptureMode(val label: String) {
    SINGLE_99("99_EXCLUSIVO"),
    SINGLE_UBER("UBER_EXCLUSIVO"),
    DUAL("DUPLO_SIMULTANEO"),
    NONE("DESATIVADO");

    companion object {
        fun from(config: FilterConfig): CaptureMode = when {
            config.nineNineModuleEnabled && config.uberModuleEnabled -> DUAL
            config.nineNineModuleEnabled -> SINGLE_99
            config.uberModuleEnabled -> SINGLE_UBER
            else -> NONE
        }
    }
}

interface DriverCaptureModule {
    val moduleId: String
    fun isEnabled(config: FilterConfig): Boolean
    fun tryParseScreen(
        visionText: Text,
        bitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        config: FilterConfig
    ): ParsedRideOffer?
}

/**
 * Roteador central que despacha a leitura da tela para o modulo correspondente (Uber ou 99):
 * - Em modo exclusivo (apenas 99 ou apenas Uber), especializa o pipeline sem heuristicas cruzadas.
 * - Em modo simultaneo (99 + Uber), arbitra janelas e cards pelos marcadores estruturais reais de cada app.
 */
object AppCaptureRouter {

    private val uberModule = UberCaptureModule()
    private val nineNineModule = NineNineCaptureModule()

    private val uberEstMarkerRegex = Regex("""\best\.?\s*(?:de\s*)?(?:r\$|rs|\$)""", RegexOption.IGNORE_CASE)
    private val uberRatingBadgeRegex = Regex("""\b[345][.,]\d{2}\s*\(\s*\d+\s*\)""")
    private val nineNineCorridasRegex = Regex("""\b\d+\s*corridas?\b""", RegexOption.IGNORE_CASE)

    fun parseActiveScreen(
        visionText: Text,
        bitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        config: FilterConfig,
        preferredAppSource: String? = null,
        uberWindowBounds: Rect? = null
    ): ParsedRideOffer? {
        val orderedLines = visionText.textBlocks
            .flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .map { it.text to it.boundingBox }

        return parseOrderedLines(
            orderedLines = orderedLines,
            bitmap = bitmap,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            config = config,
            preferredAppSource = preferredAppSource,
            uberWindowBounds = uberWindowBounds
        )
    }

    internal fun parseOrderedLines(
        orderedLines: List<Pair<String, Rect?>>,
        bitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        config: FilterConfig,
        preferredAppSource: String? = null,
        uberWindowBounds: Rect? = null
    ): ParsedRideOffer? {
        return when (CaptureMode.from(config)) {
            CaptureMode.NONE -> null

            // Especializacao total 99: sem heuristicas da Uber que possam bloquear formatos novos ("Taxa99" + "2 min (584 m)")
            CaptureMode.SINGLE_99 -> {
                val parsed = nineNineModule.parseOrderedLines(
                    orderedLines = orderedLines,
                    bitmap = bitmap,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    config = config,
                    strictSingleApp = true
                )
                parsed
            }

            // Especializacao total Uber: sem interferencia da 99 (cobre 1o plano e pop-up em 2o plano)
            CaptureMode.SINGLE_UBER -> {
                val parsed = uberModule.tryParseOrderedLinesWithBounds(
                    orderedLines = orderedLines,
                    bitmap = bitmap,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    windowBounds = uberWindowBounds,
                    strictSingleApp = true
                )
                if (parsed != null && parsed.isRideOffer) parsed else null
            }

            // Modo simultaneo (99 + Uber ativos juntos)
            CaptureMode.DUAL -> parseDualSimultaneous(
                orderedLines = orderedLines,
                bitmap = bitmap,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                config = config,
                preferredAppSource = preferredAppSource,
                uberWindowBounds = uberWindowBounds
            )
        }
    }

    private fun parseDualSimultaneous(
        orderedLines: List<Pair<String, Rect?>>,
        bitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        config: FilterConfig,
        preferredAppSource: String?,
        uberWindowBounds: Rect?
    ): ParsedRideOffer? {
        val offerZoneLines = orderedLines.filter { (_, box) ->
            box == null || box.top >= (screenHeight * 0.24f).toInt()
        }
        val lowerZoneLines = orderedLines.filter { (_, box) ->
            box == null || box.top >= (screenHeight * 0.42f).toInt()
        }

        fun isNineNineMarker(norm: String): Boolean {
            return norm == "escolher" ||
                norm.contains("escolha uma") ||
                norm.contains("100% dos ganhos") ||
                norm.contains("nao afeta a ta") ||
                norm.contains("taxa99") ||
                norm.contains("taxa 99") ||
                norm.contains("/hora") ||
                norm.contains("tarifa base dinamica") ||
                norm.contains("perfil essencial") ||
                norm.contains("cpf e cartao") ||
                norm.contains("99 abastece") ||
                norm.startsWith("moto nova") ||
                nineNineCorridasRegex.containsMatchIn(norm)
        }

        fun isUberMarker(raw: String, norm: String): Boolean {
            return norm == "tenho interesse" ||
                norm == "exclusivo" ||
                norm == "verificado" ||
                uberEstMarkerRegex.containsMatchIn(norm) ||
                uberRatingBadgeRegex.containsMatchIn(raw)
        }

        val hasStrong99 = offerZoneLines.any { (raw, _) -> isNineNineMarker(TextNormalizer.normalize(raw)) }
        val hasLowerCard99 = lowerZoneLines.any { (raw, _) -> isNineNineMarker(TextNormalizer.normalize(raw)) }
        val hasStrongUber = offerZoneLines.any { (raw, _) -> isUberMarker(raw, TextNormalizer.normalize(raw)) }
        val hasLowerCardUber = lowerZoneLines.any { (raw, _) -> isUberMarker(raw, TextNormalizer.normalize(raw)) } &&
            !hasLowerCard99

        val tryUberFirst = preferredAppSource == "UBER" ||
            uberWindowBounds != null ||
            hasLowerCardUber ||
            (preferredAppSource != "99" && hasStrongUber && !hasStrong99)

        var triedUber = false
        if (tryUberFirst) {
            triedUber = true
            val uberOffer = uberModule.tryParseOrderedLinesWithBounds(
                orderedLines = orderedLines,
                bitmap = bitmap,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                windowBounds = uberWindowBounds,
                strictSingleApp = false
            )
            if (uberOffer != null && uberOffer.isRideOffer) {
                return uberOffer
            }
        }

        // Nunca bloqueia 99 por "X min (Y km/m)" (99 tambem usa "2 min (584 m)" e "6 min (2,4 km)")!
        val strictlyBlockedByUber = (hasLowerCardUber && !hasLowerCard99) ||
            (hasStrongUber && !hasStrong99 && (preferredAppSource == "UBER" || uberWindowBounds != null))

        if (!strictlyBlockedByUber) {
            val nineNineOffer = nineNineModule.parseOrderedLines(
                orderedLines = orderedLines,
                bitmap = bitmap,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                config = config,
                strictSingleApp = false
            )
            if (nineNineOffer != null) {
                return nineNineOffer
            }
        }

        // Rede de seguranca: se a 99 estava em foco mas a Uber abriu card flutuante em 2o plano sem janela reportada pelo Android
        if (!triedUber) {
            val uberFallback = uberModule.tryParseOrderedLinesWithBounds(
                orderedLines = orderedLines,
                bitmap = bitmap,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                windowBounds = null,
                strictSingleApp = false
            )
            if (uberFallback != null && uberFallback.isRideOffer) {
                return uberFallback
            }
        }

        // Quando ha protecao de conflito no modo simultaneo, retorna o estado da tela (em viagem, cancelada, mapa livre)
        if (config.dualConflictGuardEnabled) {
            if (preferredAppSource == "UBER" || uberWindowBounds != null) {
                uberModule.parseLifecycleState(orderedLines, uberWindowBounds)?.let { return it }
            }
            if (preferredAppSource == "99" || hasStrong99) {
                nineNineModule.parseOrderedLines(
                    orderedLines = orderedLines,
                    bitmap = bitmap,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    config = config,
                    strictSingleApp = false,
                    includeLifecycleStates = true
                )?.let { return it }
            }
        }

        return null
    }
}

/**
 * Modulo dedicado para 99 Motorista.
 */
class NineNineCaptureModule : DriverCaptureModule {
    override val moduleId: String = "99"

    override fun isEnabled(config: FilterConfig): Boolean = config.nineNineModuleEnabled

    override fun tryParseScreen(
        visionText: Text,
        bitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        config: FilterConfig
    ): ParsedRideOffer? {
        val orderedLines = visionText.textBlocks
            .flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .map { it.text to it.boundingBox }
        return parseOrderedLines(orderedLines, bitmap, screenWidth, screenHeight, config, strictSingleApp = false)
    }

    internal fun parseOrderedLines(
        orderedLines: List<Pair<String, Rect?>>,
        bitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        config: FilterConfig,
        strictSingleApp: Boolean = false,
        includeLifecycleStates: Boolean = false
    ): ParsedRideOffer? {
        val parsed = RideOfferParser.parseFromOrderedLines(
            sortedLines = orderedLines,
            bitmap = bitmap,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            rejectHints = config.rejectButtonHints,
            strictSingleApp = strictSingleApp
        )
        val hasOfferOrPrompt = parsed.isRideOffer || parsed.isContinuePrompt || parsed.isRatingScreen
        val hasLifecycleState = includeLifecycleStates &&
            (parsed.isInActiveRide || parsed.isCancelledScreen || parsed.isIdleHomeScreen)
        return if (hasOfferOrPrompt || hasLifecycleState) {
            parsed.copy(appSource = "99")
        } else {
            null
        }
    }
}

/**
 * Modulo dedicado para Uber Driver:
 * - Suporta a janela sobreposta da Uber mesmo quando a Uber nao esta em 1o plano
 * - Suporta botoes "Aceitar" (Exclusivo) e "Tenho interesse" (Radar de viagens)
 * - Detecta corridas com paradas intermediarias ("1 parada", "2 paradas", etc.)
 * - Localiza o botao quadrado [ X ] no canto superior direito INTERNO do card branco da Uber
 *   (mesma linha horizontal do selo "Moto" / "Exclusivo", X = 87.0% da largura da tela)
 * - Calcula R$/h real somando Tempo de Buscar + Tempo de Levar + 2 min de espera (+ 2 min por parada)
 */
class UberCaptureModule : DriverCaptureModule {
    override val moduleId: String = "UBER"

    override fun isEnabled(config: FilterConfig): Boolean = config.uberModuleEnabled

    private val ownOverlayMarkers = RideOfferParser.COMMON_OWN_OVERLAY_MARKERS + listOf("filtro ativo")

    // Aceita exclusivamente os formatos da Uber (separador depois de min/minutos: • ou (),
    // NUNCA "(12 min 5,7 km)" com parentese antes dos minutos, que e exclusivo da 99!)
    private val uberLegHeaderRegex = Regex(
        """(?<!\()\b(\d{1,3})\s*min(?:uto[s]?)?\s*(?:[•·.*\-]|\()\s*(\d+(?:[.,]\d+)?)\s*(km|m)\b\)?""",
        RegexOption.IGNORE_CASE
    )
    private val nineNineLegCountRegex = Regex("""\b\d+\s*corridas?\b""", RegexOption.IGNORE_CASE)

    private val uberMinutesOnlyRegex = Regex("""^\s*(\d{1,3})\s*min(?:uto[s]?)?\s*$""", RegexOption.IGNORE_CASE)
    private val uberDistanceOnlyRegex = Regex("""^\s*\(?\s*(\d+(?:[.,]\d+)?)\s*(km|m)\s*\)?\s*$""", RegexOption.IGNORE_CASE)
    private val uberEstPerKmRegex = Regex("""est\.?\s*(?:de\s*)?(?:r\$|rs|\$)\s*(\d+(?:[.,]\d+)?)\s*/\s*km""", RegexOption.IGNORE_CASE)
    private val uberEstPrefixRegex = Regex("""^est\.?\s*(?:de\s*)?r?\$""", RegexOption.IGNORE_CASE)
    private val decimalPartRegex = Regex("""[.,]\d{1,2}\b""")

    // Ex: "4,90 (600)", "★ 4,94 (211)", "4,96 (58)", "★ 4,91 (380)"
    private val uberRatingWithCountRegex = Regex("""\b([345][.,]\d{2})\s*\(\s*\d+\s*\)""")
    private val genericRatingRegex = Regex("""\b([345][.,]\d{2})\b""")

    // Ex: "R$ 7,97", "R$ 38,17", "R$ 25", "R$ 11,06"
    private val currencyRegex = Regex("""(r\$|rs|\$)\s*(\d+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE)

    // Ex: "1 parada", "2 paradas", "multiplas paradas", "com parada" (evita falso positivo em nome de rua como "Parada de Lucas" / "Parada de Onibus")
    private val uberStopCountRegex = Regex("""\b([1-9])\s*paradas?\b""", RegexOption.IGNORE_CASE)
    private val uberExplicitStopLabelRegex = Regex(
        """^\s*(?:com\s+parada|parada\s+adicional|parada\s+intermediaria|multiplas?\s+paradas?|adicionar\s+parada)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val uberMultiStopWordsRegex = Regex("""\bmultiplas?\s+paradas?\b""", RegexOption.IGNORE_CASE)
    private val streetPrefixRegex = Regex(
        """\b(?:rua|r\.?|av\.?|avenida|estr\.?|estrada|rod\.?|rodovia|trav\.?|travessa|alameda|largo|praca|ponto|bairro|estacao)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Retorna a quantidade de paradas intermediarias detectadas em uma linha normalizada da Uber (0 se nenhuma).
     * Ignora enderecos que contenham a palavra "Parada" (ex: "Parada de Lucas", "Parada de Onibus").
     */
    private fun detectUberStopCount(norm: String): Int {
        if (uberExplicitStopLabelRegex.containsMatchIn(norm)) return 1
        if (uberMultiStopWordsRegex.containsMatchIn(norm)) return 2
        val countMatch = uberStopCountRegex.find(norm) ?: return 0
        if (norm.contains(",") || streetPrefixRegex.containsMatchIn(norm)) return 0
        return countMatch.groupValues.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    }

    private val uberAcceptHints = listOf(
        "aceitar",
        "tenho interesse",
        "confirmar",
        "selecionar"
    )
    private val uberCancelledHints = listOf(
        "usuario cancelou",
        "passageiro cancelou",
        "cliente cancelou",
        "viagem cancelada",
        "corrida cancelada",
        "solicitacao cancelada"
    )
    private val uberRatingHints = listOf(
        "avaliar passageiro",
        "como foi a viagem",
        "como foi sua viagem",
        "enviar avaliacao"
    )
    private val uberActiveRideHints = listOf(
        "iniciar viagem",
        "finalizar viagem",
        "concluir viagem",
        "ir para o ponto de embarque",
        "a caminho do passageiro",
        "chegou ao local",
        "aguardando passageiro",
        "em viagem",
        "chegada prevista"
    )
    private val uberIdleHints = listOf(
        "voce esta online",
        "encontrar viagens",
        "oportunidades",
        "radar de viagens",
        "voce ficou off-line",
        "procurando viagens"
    )

    internal fun parseLifecycleState(
        orderedLines: List<Pair<String, Rect?>>,
        windowBounds: Rect?
    ): ParsedRideOffer? {
        val norms = orderedLines
            .filter { (_, box) -> windowBounds == null || box == null || windowBounds.contains(box.centerX(), box.centerY()) }
            .map { (raw, _) -> raw.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { raw ->
                val norm = TextNormalizer.normalize(raw)
                raw.contains("✔") || raw.contains("✘") || ownOverlayMarkers.any { norm.contains(it) }
            }
            .map { TextNormalizer.normalize(it) }

        if (norms.isEmpty()) return null

        val isCancelled = norms.any { line -> uberCancelledHints.any { line.contains(it) } }
        val isRating = !isCancelled && norms.any { line -> uberRatingHints.any { line.contains(it) } }
        val isActive = !isCancelled && !isRating && norms.any { line -> uberActiveRideHints.any { line.contains(it) } }
        val isIdle = !isCancelled && !isRating && !isActive && norms.any { line -> uberIdleHints.any { line.contains(it) } }

        if (!isCancelled && !isRating && !isActive && !isIdle) return null

        return ParsedRideOffer(
            isRideOffer = false,
            isContinuePrompt = false,
            isRatingScreen = isRating,
            ratingPassengerName = null,
            isInActiveRide = isActive,
            hasRiskAreaBadge = false,
            allTexts = norms,
            pickupAddress = null,
            dropoffAddress = null,
            ridePrice = null,
            pickupDistanceKm = null,
            dropoffDistanceKm = null,
            totalDistanceKm = null,
            pricePerKm = null,
            hourlyRate = null,
            passengerRating = null,
            isNewPassenger = false,
            addressCandidates = emptyList(),
            rejectNode = null,
            rejectTapPoint = null,
            secondaryRejectTapPoint = null,
            rejectTapTargets = emptyList(),
            hasNaoAfetaTaPill = false,
            acceptNode = null,
            acceptTapPoint = null,
            continueNode = null,
            continueTapPoint = null,
            fingerprint = "",
            appSource = "UBER",
            isCancelledScreen = isCancelled,
            isIdleHomeScreen = isIdle
        )
    }

    override fun tryParseScreen(
        visionText: Text,
        bitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        config: FilterConfig
    ): ParsedRideOffer? = tryParseScreenWithBounds(visionText, bitmap, screenWidth, screenHeight, windowBounds = null)

    fun tryParseScreenWithBounds(
        visionText: Text,
        bitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        windowBounds: Rect?,
        strictSingleApp: Boolean = false
    ): ParsedRideOffer? {
        val orderedLines = visionText.textBlocks
            .flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .map { it.text to it.boundingBox }
        return tryParseOrderedLinesWithBounds(
            orderedLines = orderedLines,
            bitmap = bitmap,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            windowBounds = windowBounds,
            strictSingleApp = strictSingleApp
        )
    }

    internal fun tryParseOrderedLinesWithBounds(
        orderedLines: List<Pair<String, Rect?>>,
        bitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        windowBounds: Rect?,
        strictSingleApp: Boolean = false
    ): ParsedRideOffer? {
        val sortedLines = orderedLines.filter { (_, box) ->
            windowBounds == null || box == null || windowBounds.contains(box.centerX(), box.centerY())
        }
        return parseFromOrderedLines(sortedLines, screenWidth, screenHeight, bitmap, strictSingleApp = strictSingleApp)
    }

    internal fun parseFromOrderedLines(
        sortedLines: List<Pair<String, Rect?>>,
        screenWidth: Int,
        screenHeight: Int,
        bitmap: Bitmap? = null,
        strictSingleApp: Boolean = false
    ): ParsedRideOffer? {
        data class IndexedLine(val raw: String, val norm: String, val box: Rect?)

        val filtered = mutableListOf<IndexedLine>()
        var hasAcceptOrInterestBtn = false
        var acceptCenter: Pair<Float, Float>? = null
        var headerBadgeBox: Rect? = null
        var priceBox: Rect? = null
        var hasUberSpecificMarker = false
        var hasLegHeader = false
        var hasCard99MarkerInBottom = false
        var hasAny99Marker = false

        fun isNineNineLine(norm: String): Boolean {
            return norm.contains("taxa99") ||
                norm.contains("taxa 99") ||
                norm.contains("/hora") ||
                norm.contains("dos ganhos") ||
                norm == "escolher" ||
                norm.contains("escolha uma") ||
                norm.startsWith("moto nova") ||
                norm.contains("99 abastece") ||
                norm.contains("nao afeta a ta") ||
                norm.contains("perfil essencial") ||
                norm.contains("cpf e cartao") ||
                nineNineLegCountRegex.containsMatchIn(norm)
        }

        for ((rawText, box) in sortedLines) {
            val raw = rawText.trim()
            if (raw.isEmpty()) continue
            val norm = TextNormalizer.normalize(raw)

            if (raw.contains("✔") ||
                raw.contains("✘") ||
                norm.contains("xrecorder") ||
                norm.contains("gravado por") ||
                ownOverlayMarkers.any { norm.contains(it) } ||
                norm == "on" ||
                norm == "pausa"
            ) {
                continue
            }

            // Ignora linha de banner do nosso proprio app ("R$ 1,45/km • R$ 42/h"), mas MANTEM "Est. de R$0,84/km" da Uber!
            if (norm.contains("/km •") || (norm.contains("/km") && !norm.contains("est.") && !norm.startsWith("est"))) {
                continue
            }

            // Ignora o topo da tela (< 24%: barra preta de navegacao GPS / notificacoes / cabecalho de app de fundo)
            if (box != null && box.top < screenHeight * 0.24f) {
                continue
            }

            if (isNineNineLine(norm)) {
                hasAny99Marker = true
                if (box == null || box.top >= screenHeight * 0.48f) {
                    hasCard99MarkerInBottom = true
                }
            }

            filtered.add(IndexedLine(raw, norm, box))

            // Detecta a linha do cabecalho interno do card da Uber ("Moto", "Exclusivo", "UberX", "Flash")
            if (headerBadgeBox == null &&
                box != null &&
                box.top in (screenHeight * 0.26f).toInt()..(screenHeight * 0.72f).toInt() &&
                (norm == "moto" || norm.endsWith(" moto") || norm == "exclusivo" ||
                    norm.contains("moto exclusivo") || norm == "uberx" || norm == "flash" || norm == "comfort")
            ) {
                headerBadgeBox = box
            }

            if (uberLegHeaderRegex.containsMatchIn(normalizeLegOcrNoise(raw))) {
                hasLegHeader = true
                hasUberSpecificMarker = true
            }

            if (norm == "exclusivo" ||
                norm == "verificado" ||
                norm == "tenho interesse" ||
                uberEstPerKmRegex.containsMatchIn(norm) ||
                norm.contains("incluido") ||
                uberRatingWithCountRegex.containsMatchIn(raw)
            ) {
                hasUberSpecificMarker = true
            }

            if (priceBox == null &&
                box != null &&
                !norm.contains("/km") &&
                !norm.contains("incluido") &&
                !norm.startsWith("+") &&
                !norm.contains("liq") &&
                !norm.contains("lucro") &&
                !raw.contains("•") &&
                !raw.contains("★") &&
                currencyRegex.containsMatchIn(raw)
            ) {
                priceBox = box
            }

            if (uberAcceptHints.any { norm == it || norm.startsWith("aceitar") || norm.contains("tenho interesse") }) {
                if (box == null || box.top > screenHeight * 0.50f) {
                    hasAcceptOrInterestBtn = true
                    if (box != null && acceptCenter == null) {
                        acceptCenter = box.exactCenterX() to box.exactCenterY()
                    }
                }
            }
        }

        if (hasCard99MarkerInBottom) return null
        if (hasAny99Marker && !hasUberSpecificMarker) return null

        // Clipa linhas acima do cabecalho real do card da Uber ("Moto", "Exclusivo", "UberX") para evitar vazamento
        // do historico/banner do proprio app ou textos de outros apps atras do card transparente da Uber
        val topClipBoundary = when {
            headerBadgeBox != null -> (headerBadgeBox.top - (screenHeight * 0.025f).toInt())
                .coerceAtLeast((screenHeight * 0.28f).toInt())
            hasAny99Marker && priceBox != null -> (priceBox.top - (screenHeight * 0.06f).toInt())
                .coerceAtLeast((screenHeight * 0.33f).toInt())
            else -> (screenHeight * 0.33f).toInt()
        }

        val cardLines = filtered
            .filter { item -> item.box == null || item.box.top >= topClipBoundary }
            .filter { item -> !isNineNineLine(item.norm) }
            .map { it.raw }

        if (strictSingleApp && hasAny99Marker && cardLines.isEmpty()) return null

        // Em todos os cards da Uber existem trechos "min (km)" ou "min • km" + botao Aceitar/Tenho interesse
        if ((!hasAcceptOrInterestBtn && !hasLegHeader) || !hasUberSpecificMarker) {
            return null
        }

        val legsResult = extractUberLegsAndStops(cardLines)
        val rawPriceInfo = extractUberPriceInfo(cardLines)
        val estPerKm = extractEstimatedPerKm(cardLines)
        val rating = extractUberRating(cardLines)

        val initialTotalKm = if (legsResult.pickupDistKm != null && legsResult.dropoffDistKm != null) {
            legsResult.pickupDistKm + legsResult.dropoffDistKm
        } else {
            legsResult.dropoffDistKm ?: legsResult.pickupDistKm
        }
        val expectedPriceFromEst = if (estPerKm != null && estPerKm > 0.2 && initialTotalKm != null) {
            estPerKm * initialTotalKm
        } else {
            null
        }
        val ridePrice = RideOfferParser.recoverMissingDecimalPrice(
            rawValue = rawPriceInfo?.first,
            hasDecimal = rawPriceInfo?.second ?: true,
            totalDistKm = initialTotalKm,
            expectedPrice = expectedPriceFromEst
        )

        // Confirma que e um card real de corrida da Uber (tem preco OU trechos + marcador Uber)
        if (ridePrice == null && legsResult.pickup == null && !hasLegHeader) {
            return null
        }

        var pickupDistKm = legsResult.pickupDistKm
        var dropoffDistKm = legsResult.dropoffDistKm
        var dropoffMinutes = legsResult.dropoffMinutes

        // Contingencia cirurgica: se o OCR perdeu o ponto decimal em uma perna ("7.1 km" -> "71 km", "7.3 km" -> "73 km"),
        // compara com o total implicito por "Est. de R$ X,XX/km" e corrige a perna inflada por / 10.
        if (estPerKm != null && estPerKm > 0.2 && ridePrice != null) {
            val impliedTotal = ridePrice / estPerKm
            val p = pickupDistKm
            val d = dropoffDistKm
            if (p != null && d != null && (p + d) > impliedTotal * 2.0) {
                val curErr = kotlin.math.abs((p + d) - impliedTotal)
                val fixPErr = if (p >= 10.0) kotlin.math.abs((p / 10.0 + d) - impliedTotal) else Double.MAX_VALUE
                val fixDErr = if (d >= 10.0) kotlin.math.abs((p + d / 10.0) - impliedTotal) else Double.MAX_VALUE
                if (fixPErr <= fixDErr && fixPErr < curErr * 0.4) {
                    pickupDistKm = kotlin.math.round((p / 10.0) * 10.0) / 10.0
                } else if (fixDErr < curErr * 0.4) {
                    dropoffDistKm = kotlin.math.round((d / 10.0) * 10.0) / 10.0
                }
            } else if (p != null && d == null && p >= 10.0 && p > impliedTotal * 2.0) {
                val fixErr = kotlin.math.abs((p / 10.0) - impliedTotal)
                if (fixErr < kotlin.math.abs(p - impliedTotal) * 0.4) {
                    pickupDistKm = kotlin.math.round((p / 10.0) * 10.0) / 10.0
                }
            }
        }

        // Contingencia cirurgica: se o OCR perdeu o cabecalho da 2a perna ("11 minutos (7.6 km)"),
        // mas temos Preco e "Est. de R$ X,XX/km" + EmbKm, derivamos o KM do destino para nao inflar R$/h!
        if (pickupDistKm != null && dropoffDistKm == null && estPerKm != null && estPerKm > 0.2 && ridePrice != null) {
            val estTotalKm = ridePrice / estPerKm
            val remainingKm = estTotalKm - pickupDistKm
            if (remainingKm in 0.3..150.0) {
                dropoffDistKm = kotlin.math.round(remainingKm * 10.0) / 10.0
                if (dropoffMinutes == null) {
                    dropoffMinutes = kotlin.math.max(1.0, kotlin.math.round(dropoffDistKm * 1.8))
                }
            }
        }

        val totalDistKm = if (pickupDistKm != null && dropoffDistKm != null) {
            pickupDistKm + dropoffDistKm
        } else {
            dropoffDistKm ?: pickupDistKm
        }

        val pricePerKm = if (ridePrice != null && totalDistKm != null && totalDistKm > 0.1) {
            ridePrice / totalDistKm
        } else {
            null
        }

        // Calculo do R$/h Real na Uber com piso fisico de velocidade urbana realista (evita distorcao tipo "8 min em 12.6 km"):
        // Piso: cada km leva pelo menos 1,4 min (~43 km/h de media max no transito urbano) + 2 min espera (+ 2 min por parada)
        val waitMinutes = 2.0 + (legsResult.stopsCount * 2.0)
        val pEffMin = when {
            legsResult.pickupMinutes != null && pickupDistKm != null ->
                kotlin.math.max(legsResult.pickupMinutes, pickupDistKm * 1.4)
            legsResult.pickupMinutes != null -> legsResult.pickupMinutes
            pickupDistKm != null -> pickupDistKm * 1.8
            else -> null
        }
        val dEffMin = when {
            dropoffMinutes != null && dropoffDistKm != null ->
                kotlin.math.max(dropoffMinutes, dropoffDistKm * 1.4)
            dropoffMinutes != null -> dropoffMinutes
            dropoffDistKm != null -> dropoffDistKm * 1.8
            else -> null
        }
        val hourlyRate = if (ridePrice != null && ridePrice > 0.0) {
            when {
                pEffMin != null && dEffMin != null && (pEffMin + dEffMin) > 0.0 -> {
                    val totalMin = pEffMin + dEffMin + waitMinutes
                    (ridePrice / totalMin) * 60.0
                }
                pEffMin != null && pickupDistKm != null && totalDistKm != null && totalDistKm > pickupDistKm + 0.1 -> {
                    val totalMin = pEffMin + ((totalDistKm - pickupDistKm) * 1.8) + waitMinutes
                    (ridePrice / totalMin) * 60.0
                }
                totalDistKm != null && totalDistKm > 0.1 -> {
                    val totalMin = (totalDistKm * 2.4) + waitMinutes
                    (ridePrice / totalMin) * 60.0
                }
                else -> null
            }
        } else {
            null
        }

        // Posicao exata do botao quadrado [ X ] no canto superior direito do card da Uber:
        // 1. Encontra a borda superior exata do card branco subindo de dentro do card (imune a textos do mapa!)
        // 2. Procura os tracos escuros do "X" estritamente dentro do quadrado do botao [cardTop+1.4% .. +6.5%]
        // 3. Fallback formula exata medida em todos os prints: X = 87.05% da largura, Y = cardTop + 3.8% da altura
        val anchorInsideCardY = (headerBadgeBox?.centerY()
            ?: priceBox?.top?.minus((screenHeight * 0.015f).toInt())
            ?: (screenHeight * 0.56f).toInt()).coerceIn(0, screenHeight - 1)
        val cardTopEdge = bitmap?.let { findUberCardTopEdge(it, screenWidth, screenHeight, anchorInsideCardY) }
        val visualClosePoint = if (bitmap != null && cardTopEdge != null) {
            findUberCloseButtonInBitmap(bitmap, screenWidth, screenHeight, cardTopEdge)
        } else {
            null
        }
        val topFormulaPoint = cardTopEdge?.let {
            (screenWidth * 0.8705f) to (it + (screenHeight * 0.038f))
        }

        val rejectX = visualClosePoint?.first ?: topFormulaPoint?.first ?: (screenWidth * 0.8705f)
        val rejectY = visualClosePoint?.second ?: topFormulaPoint?.second ?: when {
            headerBadgeBox != null -> headerBadgeBox.exactCenterY() + (screenHeight * 0.016f)
            priceBox != null -> (priceBox.top - (priceBox.height() * 0.45f)).coerceAtLeast(screenHeight * 0.42f)
            legsResult.hasStops -> screenHeight * 0.475f
            else -> screenHeight * 0.530f
        }
        val rejectPoint = rejectX to rejectY
        val fallbackRejectPoint = topFormulaPoint ?: headerBadgeBox?.let {
            (screenWidth * 0.8705f) to (it.exactCenterY() + (screenHeight * 0.016f))
        }
        val allRejectTargets = listOfNotNull(visualClosePoint, topFormulaPoint, fallbackRejectPoint, rejectPoint)
            .distinctBy { "${it.first.toInt()}x${it.second.toInt()}" }
        val fallbackAccept = (screenWidth * 0.50f) to (screenHeight * 0.89f)

        val fingerprint = listOfNotNull(
            "UBER",
            ridePrice?.toString(),
            rating?.toString(),
            legsResult.pickup,
            legsResult.dropoff,
            legsResult.stopsCount.toString()
        ).joinToString("|")

        return ParsedRideOffer(
            isRideOffer = true,
            isContinuePrompt = false,
            isRatingScreen = false,
            ratingPassengerName = null,
            isInActiveRide = false,
            hasRiskAreaBadge = false,
            allTexts = cardLines,
            pickupAddress = legsResult.pickup,
            dropoffAddress = legsResult.dropoff,
            ridePrice = ridePrice,
            pickupDistanceKm = pickupDistKm,
            dropoffDistanceKm = dropoffDistKm,
            totalDistanceKm = totalDistKm,
            pricePerKm = pricePerKm,
            hourlyRate = hourlyRate,
            passengerRating = rating,
            isNewPassenger = false,
            addressCandidates = listOfNotNull(legsResult.dropoff, legsResult.pickup),
            rejectNode = null,
            rejectTapPoint = rejectPoint,
            secondaryRejectTapPoint = fallbackRejectPoint,
            rejectTapTargets = allRejectTargets,
            hasNaoAfetaTaPill = false,
            acceptNode = null,
            acceptTapPoint = acceptCenter ?: fallbackAccept,
            continueNode = null,
            continueTapPoint = null,
            fingerprint = fingerprint,
            appSource = "UBER",
            hasMultipleStops = legsResult.hasStops,
            stopsCount = legsResult.stopsCount
        )
    }

    private fun rowWhiteFraction(bitmap: Bitmap, y: Int, xStart: Int, xEnd: Int, stepX: Int): Double {
        if (y !in 0 until bitmap.height || xEnd <= xStart) return 0.0
        var white = 0
        var total = 0
        for (x in xStart until xEnd step stepX) {
            val c = bitmap.getPixel(x, y)
            if (Color.red(c) > 244 && Color.green(c) > 244 && Color.blue(c) > 244) {
                white++
            }
            total++
        }
        return if (total > 0) white.toDouble() / total else 0.0
    }

    /**
     * Sobe a partir do interior branco do card da Uber (faixa livre X=55%..78%) ate detectar
     * exatamente a borda superior do card branco, evitando falsos positivos no mapa acima.
     */
    private fun findUberCardTopEdge(
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        anchorInsideCardY: Int
    ): Int? {
        return try {
            val xStart = (screenWidth * 0.55f).toInt().coerceIn(0, bitmap.width - 1)
            val xEnd = (screenWidth * 0.78f).toInt().coerceIn(xStart + 1, bitmap.width)
            val stepX = kotlin.math.max(3, screenWidth / 300)
            val minTopY = (screenHeight * 0.28f).toInt().coerceAtLeast(0)
            val maxAnchorSearchY = (anchorInsideCardY + (screenHeight * 0.08f).toInt()).coerceAtMost(bitmap.height - 1)

            var insideY = -1
            for (y in anchorInsideCardY..maxAnchorSearchY step 3) {
                if (rowWhiteFraction(bitmap, y, xStart, xEnd, stepX) >= 0.80) {
                    insideY = y
                    break
                }
            }
            if (insideY < 0) {
                for (y in anchorInsideCardY downTo minTopY step 3) {
                    if (rowWhiteFraction(bitmap, y, xStart, xEnd, stepX) >= 0.80) {
                        insideY = y
                        break
                    }
                }
            }
            if (insideY < 0) return null

            var topEdge = insideY
            for (y in insideY downTo minTopY step 2) {
                if (rowWhiteFraction(bitmap, y, xStart, xEnd, stepX) < 0.60) {
                    topEdge = y + 2
                    break
                }
            }
            topEdge
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Encontra visualmente os tracos escuros do "X" estritamente dentro da area do botao
     * [ X ] no topo direito do card branco (X = 82%..93%, Y = cardTop+1.4% .. cardTop+6.5%).
     */
    private fun findUberCloseButtonInBitmap(
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        cardTopEdge: Int
    ): Pair<Float, Float>? {
        return try {
            val xStart = (screenWidth * 0.82f).toInt().coerceIn(0, bitmap.width - 1)
            val xEnd = (screenWidth * 0.93f).toInt().coerceIn(xStart + 1, bitmap.width)
            val yStart = (cardTopEdge + (screenHeight * 0.014f).toInt()).coerceIn(0, bitmap.height - 1)
            val yEnd = (cardTopEdge + (screenHeight * 0.065f).toInt()).coerceIn(yStart + 1, bitmap.height)
            val step = kotlin.math.max(2, screenWidth / 400)

            var sumX = 0L
            var sumY = 0L
            var count = 0
            for (y in yStart until yEnd step step) {
                for (x in xStart until xEnd step step) {
                    val c = bitmap.getPixel(x, y)
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)
                    if (r < 90 && g < 90 && b < 90 && kotlin.math.abs(r - g) < 20 && kotlin.math.abs(g - b) < 20) {
                        sumX += x
                        sumY += y
                        count++
                    }
                }
            }
            if (count >= 6) {
                (sumX.toFloat() / count) to (sumY.toFloat() / count)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun normalizeLegOcrNoise(raw: String): String {
        var s = raw.trim()
        // Remove espaco espuriamente inserido ao redor do ponto/virgula decimal ("7. 6 km" -> "7.6 km")
        s = s.replace(Regex("""(\d)\s*([.,])\s*(\d)"""), "$1$2$3")
        // Remove letra/simbolo de marcador solto colado antes de digitos/min ("d 1l minutos", "? 1l min", "b 23 minutos")
        s = s.replace(
            Regex("""(?:^|\s)[a-zA-Z?•·*>~]\s+([0-9Il|!]{1,3}\s*min(?:uto[s]?)?)""", RegexOption.IGNORE_CASE)
        ) { m ->
            (if (m.value.startsWith(" ")) " " else "") + m.groupValues[1]
        }
        // Corrige digitos misturados com I/l/|/! antes de "min" (ex: "ll min", "1l minutos", "l2 min", "1 1 min")
        s = s.replace(Regex("""\b([0-9Il|!]{1,3})\s*(min(?:uto[s]?)?)""", RegexOption.IGNORE_CASE)) { m ->
            val rawNum = m.groupValues[1]
            if (rawNum.any { it.isDigit() } || rawNum.all { it in "Il|!" }) {
                val digits = rawNum.replace(Regex("""[Il|!]"""), "1")
                "$digits ${m.groupValues[2]}"
            } else {
                m.value
            }
        }
        s = s.replace(Regex("""\b(\d)\s+(\d)\s*(min(?:uto[s]?)?)""", RegexOption.IGNORE_CASE)) { m ->
            "${m.groupValues[1]}${m.groupValues[2]} ${m.groupValues[3]}"
        }
        // Corrige numeros misturados com I/l dentro de "(21.l km)" ou "(l.2 km)"
        s = s.replace(Regex("""\(\s*([0-9Il|!]+(?:[.,][0-9Il|!]+)?)\s*(km|m)\s*\)""", RegexOption.IGNORE_CASE)) { m ->
            val fixedNum = m.groupValues[1].replace(Regex("""[Il|!]"""), "1")
            "($fixedNum ${m.groupValues[2]})"
        }
        return s
    }

    private fun extractEstimatedPerKm(lines: List<String>): Double? {
        for (line in lines) {
            val match = uberEstPerKmRegex.find(line) ?: continue
            val valKm = match.groupValues[1].replace(",", ".").toDoubleOrNull()
            if (valKm != null && valKm > 0.2) return valKm
        }
        return null
    }

    private fun extractUberPriceInfo(lines: List<String>): Pair<Double, Boolean>? {
        for (line in lines) {
            val norm = TextNormalizer.normalize(line)
            if (norm.contains("/km") ||
                norm.contains("incluido") ||
                uberEstPerKmRegex.containsMatchIn(line) ||
                uberEstPrefixRegex.containsMatchIn(norm) ||
                norm.startsWith("+") ||
                norm.contains("liq") ||
                norm.contains("lucro") ||
                line.contains("•") ||
                line.contains("★")
            ) {
                continue
            }
            val match = currencyRegex.find(line) ?: continue
            val value = match.groupValues[2].replace(",", ".").toDoubleOrNull()
            if (value != null && value >= 3.0) {
                return value to decimalPartRegex.containsMatchIn(match.groupValues[2])
            }
        }
        return null
    }

    private fun extractUberRating(lines: List<String>): Double? {
        // Prioriza o formato padrao da Uber "★ 4,90 (600)"
        for (line in lines) {
            val match = uberRatingWithCountRegex.find(line)
            if (match != null) {
                val value = match.groupValues[1].replace(",", ".").toDoubleOrNull()
                if (value != null && value in 3.0..5.0) return value
            }
        }
        for (line in lines) {
            if (currencyRegex.containsMatchIn(line) || uberLegHeaderRegex.containsMatchIn(normalizeLegOcrNoise(line))) continue
            val match = genericRatingRegex.find(line)
            if (match != null) {
                val value = match.groupValues[1].replace(",", ".").toDoubleOrNull()
                if (value != null && value in 3.5..5.0) return value
            }
        }
        return null
    }

    private data class UberLegsData(
        val pickup: String?,
        val dropoff: String?,
        val pickupDistKm: Double?,
        val dropoffDistKm: Double?,
        val pickupMinutes: Double?,
        val dropoffMinutes: Double?,
        val hasStops: Boolean,
        val stopsCount: Int
    )

    private fun isMetadataOrActionLine(raw: String, norm: String): Boolean {
        return uberRatingWithCountRegex.containsMatchIn(raw) ||
            norm == "verificado" ||
            norm == "exclusivo" ||
            norm == "moto" ||
            norm.endsWith(" moto") ||
            norm.contains("incluido") ||
            uberEstPerKmRegex.containsMatchIn(raw) ||
            uberEstPrefixRegex.containsMatchIn(norm) ||
            currencyRegex.containsMatchIn(raw) ||
            detectUberStopCount(norm) > 0 ||
            uberAcceptHints.any { norm == it || norm.startsWith("aceitar") || norm.contains("tenho interesse") }
    }

    private fun extractUberLegsAndStops(lines: List<String>): UberLegsData {
        val addresses = mutableListOf<String?>()
        val distances = mutableListOf<Double?>()
        val minutes = mutableListOf<Double?>()
        var hasStops = false
        var stopsCount = 0

        for (line in lines) {
            val norm = TextNormalizer.normalize(line)
            val count = detectUberStopCount(norm)
            if (count > 0) {
                hasStops = true
                if (count > stopsCount) stopsCount = count
            }
        }

        // Pre-combina linhas divididas pelo OCR ("11 minutos" seguido de "(7.6 km)") e separa cabeçalhos inline
        // que vieram colados ao final da cidade do trecho 1 (ex: "Rio de Janeiro 11 minutos (5.5 km)")
        val splitLines = mutableListOf<String>()
        for (rawLine in lines) {
            val cleaned = normalizeLegOcrNoise(rawLine)
            val inlineMatch = uberLegHeaderRegex.find(cleaned)
            if (inlineMatch != null && inlineMatch.range.first > 3) {
                val prefix = cleaned.substring(0, inlineMatch.range.first).trim().trimEnd(',', '-', '•', '·')
                val suffix = cleaned.substring(inlineMatch.range.first).trim()
                if (prefix.length >= 3) splitLines.add(prefix)
                if (suffix.isNotEmpty()) splitLines.add(suffix)
            } else {
                splitLines.add(cleaned)
            }
        }

        val normalizedLines = mutableListOf<String>()
        var idx = 0
        while (idx < splitLines.size) {
            val cur = splitLines[idx]
            val next = if (idx + 1 < splitLines.size) splitLines[idx + 1] else null
            if (uberMinutesOnlyRegex.matches(cur) && next != null && uberDistanceOnlyRegex.matches(next)) {
                normalizedLines.add("$cur $next")
                idx += 2
            } else {
                normalizedLines.add(cur)
                idx++
            }
        }

        var i = 0
        var lastAddressEndIdx = 0
        while (i < normalizedLines.size) {
            val line = normalizedLines[i].trim()
            val normLine = TextNormalizer.normalize(line)
            val headerMatch = uberLegHeaderRegex.find(line)
            val distOnlyMatch = if (headerMatch == null && addresses.isNotEmpty() && !isMetadataOrActionLine(line, normLine)) {
                uberDistanceOnlyRegex.find(line)
            } else {
                null
            }

            if (headerMatch != null || distOnlyMatch != null) {
                val minVal = headerMatch?.groupValues?.get(1)?.toDoubleOrNull()
                val rawDist = (headerMatch?.groupValues?.get(2) ?: distOnlyMatch?.groupValues?.get(1))
                    ?.replace(",", ".")?.toDoubleOrNull()
                val unit = (headerMatch?.groupValues?.get(3) ?: distOnlyMatch?.groupValues?.get(2))?.lowercase()
                val distKm = if (rawDist != null) {
                    if (unit == "m") rawDist / 1000.0 else rawDist
                } else {
                    null
                }
                minutes.add(minVal)
                distances.add(distKm)

                val addrParts = mutableListOf<String>()
                var j = i + 1
                while (j < normalizedLines.size && addrParts.size < 2) {
                    val cand = normalizedLines[j].trim()
                    val normCand = TextNormalizer.normalize(cand)

                    if (uberLegHeaderRegex.containsMatchIn(cand) ||
                        uberDistanceOnlyRegex.matches(cand) ||
                        uberAcceptHints.any { normCand == it || normCand.startsWith("aceitar") || normCand.contains("tenho interesse") }
                    ) {
                        break
                    }

                    if (isMetadataOrActionLine(cand, normCand)) {
                        j++
                        continue
                    }

                    if (cand.length >= 4) {
                        addrParts.add(cand)
                        // Se a linha ja fecha com cidade (ex: "Rio de Janeiro", "Duque de Caxias"), encerra esse endereco
                        if (normCand.endsWith("rio de janeiro") || normCand.endsWith("duque de caxias") ||
                            normCand.endsWith("nova iguacu") || normCand.endsWith("sao joao de meriti") ||
                            normCand.endsWith("belford roxo") || normCand.endsWith("nilopolis")
                        ) {
                            j++
                            break
                        }
                    }
                    j++
                }
                addresses.add(if (addrParts.isNotEmpty()) addrParts.joinToString(" ") else null)
                lastAddressEndIdx = j
                i = j
            } else {
                i++
            }
        }

        // Fallback extra: se temos Embarque (1 endereco) mas o cabeçalho do 2o trecho falhou totalmente no OCR,
        // captura linhas restantes de endereco logo abaixo antes do botao final como Destino.
        if (addresses.size == 1 && lastAddressEndIdx in 1 until normalizedLines.size) {
            val extraAddr = mutableListOf<String>()
            var k = lastAddressEndIdx
            while (k < normalizedLines.size && extraAddr.size < 2) {
                val cand = normalizedLines[k].trim()
                val normCand = TextNormalizer.normalize(cand)
                if (uberAcceptHints.any { normCand == it || normCand.startsWith("aceitar") || normCand.contains("tenho interesse") }) {
                    break
                }
                if (!isMetadataOrActionLine(cand, normCand) && cand.length >= 4) {
                    extraAddr.add(cand)
                }
                k++
            }
            if (extraAddr.isNotEmpty()) {
                addresses.add(extraAddr.joinToString(" "))
            }
        }

        return UberLegsData(
            pickup = addresses.getOrNull(0),
            dropoff = addresses.getOrNull(1),
            pickupDistKm = distances.getOrNull(0),
            dropoffDistKm = distances.getOrNull(1),
            pickupMinutes = minutes.getOrNull(0),
            dropoffMinutes = minutes.getOrNull(1),
            hasStops = hasStops,
            stopsCount = stopsCount
        )
    }
}
