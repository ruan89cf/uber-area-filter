package com.ruan.uberareafilter.driver

import android.graphics.Bitmap
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
 * Roteador central que despacha a leitura da tela para o modulo correspondente (Uber ou 99).
 */
object AppCaptureRouter {

    private val uberModule = UberCaptureModule()
    private val nineNineModule = NineNineCaptureModule()

    fun parseActiveScreen(
        visionText: Text,
        bitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        config: FilterConfig
    ): ParsedRideOffer? {
        // 1. Verifica primeiro se a tela contem assinatura exclusiva do card da Uber
        // (ex: "7 min • 3.8 km", "Tenho interesse", "Exclusivo", "4,90 (600)", "1 parada")
        if (uberModule.isEnabled(config)) {
            val uberOffer = uberModule.tryParseScreen(visionText, bitmap, screenWidth, screenHeight, config)
            if (uberOffer != null && uberOffer.isRideOffer) {
                return uberOffer
            }
        }

        // 2. Verifica o modulo da 99 Motorista (oferta, "Continuar aceitando" ou tela de 5 estrelas)
        if (nineNineModule.isEnabled(config)) {
            val nineNineOffer = nineNineModule.tryParseScreen(visionText, bitmap, screenWidth, screenHeight, config)
            if (nineNineOffer != null &&
                (nineNineOffer.isRideOffer || nineNineOffer.isContinuePrompt || nineNineOffer.isRatingScreen)
            ) {
                return nineNineOffer
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
        val parsed = RideOfferParser.parseFromOcr(
            visionText = visionText,
            bitmap = bitmap,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            rejectHints = config.rejectButtonHints
        )
        return if (parsed.isRideOffer || parsed.isContinuePrompt || parsed.isRatingScreen) {
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

    private val ownOverlayMarkers = listOf(
        "fora da area",
        "fora dos criterios",
        "dentro da area",
        "corrida entre zonas",
        "recusando...",
        "voce decide",
        "aguardando leitura",
        "favoritar ",
        "salvo nos favoritos",
        "embarque fora:",
        "destino fora:",
        "zona proibida",
        "/km •"
    )

    // Ex: "7 min • 3.8 km", "6 minutos • 3.3 km", "34 minutos • 34.6 km", "26 minutos • 10.2 km"
    private val uberLegHeaderRegex = Regex(
        """\b(\d+)\s*min(?:utos?)?\s*[•·.*\-]?\s*(\d+(?:[.,]\d+)?)\s*(km|m)\b""",
        RegexOption.IGNORE_CASE
    )

    // Ex: "4,90 (600)", "★ 4,94 (211)", "4,96 (58)"
    private val uberRatingWithCountRegex = Regex("""\b([345][.,]\d{2})\s*\(\s*\d+\s*\)""")
    private val genericRatingRegex = Regex("""\b([345][.,]\d{2})\b""")

    // Ex: "R$ 7,97", "R$ 38,17", "R$ 15,08", "R$ 14,09"
    private val currencyRegex = Regex("""(r\$|rs|\$)\s*(\d+([.,]\d{1,2})?)""", RegexOption.IGNORE_CASE)

    // Ex: "1 parada", "2 paradas", " multiplas paradas"
    private val uberStopRegex = Regex("""\b(\d+)\s*paradas?\b|\bparada\b""", RegexOption.IGNORE_CASE)

    private val uberAcceptHints = listOf(
        "aceitar",
        "tenho interesse",
        "confirmar",
        "selecionar"
    )

    override fun tryParseScreen(
        visionText: Text,
        bitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        config: FilterConfig
    ): ParsedRideOffer? {
        val sortedLines = visionText.textBlocks
            .flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))

        val cardLines = mutableListOf<String>()
        var hasAcceptOrInterestBtn = false
        var acceptCenter: Pair<Float, Float>? = null
        var headerBadgeBox: Rect? = null
        var priceBox: Rect? = null
        var hasUberSpecificMarker = false

        for (lineObj in sortedLines) {
            val raw = lineObj.text.trim()
            if (raw.isEmpty()) continue
            val norm = TextNormalizer.normalize(raw)

            if (raw.contains("✔") ||
                raw.contains("✘") ||
                norm.contains("/km") ||
                ownOverlayMarkers.any { norm.contains(it) } ||
                norm == "on" ||
                norm == "pausa"
            ) {
                continue
            }

            val box = lineObj.boundingBox
            // Ignora os 33% superiores da tela (onde fica a caixa preta de navegacao GPS da Uber "120 m Avenida Actura"
            // e o nosso proprio card de notificacao no topo)
            if (box != null && box.top < screenHeight * 0.33f) {
                continue
            }

            // Se tiver marcadores exclusivos da 99 ("taxa99", "tarifa base", "nao afeta a ta", "/hora"), nao e card da Uber!
            if (norm.contains("taxa99") || norm.contains("nao afeta a ta") || norm.contains("/hora")) {
                return null
            }

            cardLines.add(raw)

            // Detecta a linha do cabecalho interno do card da Uber ("Moto", "Exclusivo", "UberX", "Flash")
            if (headerBadgeBox == null &&
                box != null &&
                box.top in (screenHeight * 0.36f).toInt()..(screenHeight * 0.62f).toInt() &&
                (norm == "moto" || norm == "exclusivo" || norm.contains("moto exclusivo") || norm == "uberx" || norm == "flash")
            ) {
                headerBadgeBox = box
            }

            if (norm == "exclusivo" ||
                norm == "verificado" ||
                norm == "tenho interesse" ||
                uberRatingWithCountRegex.containsMatchIn(raw) ||
                uberLegHeaderRegex.containsMatchIn(raw)
            ) {
                hasUberSpecificMarker = true
            }

            if (priceBox == null && box != null && currencyRegex.containsMatchIn(raw)) {
                priceBox = box
            }

            if (uberAcceptHints.any { norm == it || norm.startsWith("aceitar") || norm.contains("tenho interesse") }) {
                if (box == null || box.top > screenHeight * 0.72f) {
                    hasAcceptOrInterestBtn = true
                    if (box != null && acceptCenter == null) {
                        acceptCenter = box.exactCenterX() to box.exactCenterY()
                    }
                }
            }
        }

        if (!hasAcceptOrInterestBtn || !hasUberSpecificMarker) {
            return null
        }

        val legsResult = extractUberLegsAndStops(cardLines)
        val ridePrice = extractUberPrice(cardLines)
        val rating = extractUberRating(cardLines)

        // Confirma que e um card real de corrida da Uber (tem preco + pelo menos 1 trecho de rota)
        if (ridePrice == null && legsResult.pickup == null) {
            return null
        }

        val pickupDistKm = legsResult.pickupDistKm
        val dropoffDistKm = legsResult.dropoffDistKm
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

        // Calculo do R$/h Real na Uber:
        // Soma Tempo de Buscar (pickupMinutes) + Tempo de Levar (dropoffMinutes) + 2 min de espera (+ 2 min por parada extra)
        val waitMinutes = 2.0 + (legsResult.stopsCount * 2.0)
        val hourlyRate = if (ridePrice != null && ridePrice > 0.0) {
            val pMin = legsResult.pickupMinutes
            val dMin = legsResult.dropoffMinutes
            if (pMin != null && dMin != null && (pMin + dMin) > 0.0) {
                val totalMin = pMin + dMin + waitMinutes
                (ridePrice / totalMin) * 60.0
            } else if (totalDistKm != null && totalDistKm > 0.1) {
                val totalMin = (totalDistKm * 2.4) + waitMinutes
                (ridePrice / totalMin) * 60.0
            } else {
                null
            }
        } else {
            null
        }

        // Posicao exata do botao quadrado [ X ] no canto superior direito do card da Uber:
        // Nas 5 imagens da Uber, o [ X ] fica alinhado horizontalmente com o selo [ Moto ] / [ Exclusivo ], em X = 87.0%!
        val rejectX = screenWidth * 0.870f
        val rejectY = when {
            headerBadgeBox != null -> headerBadgeBox.exactCenterY()
            priceBox != null -> (priceBox.top - (priceBox.height() * 0.60f)).coerceAtLeast(screenHeight * 0.42f)
            legsResult.hasStops -> screenHeight * 0.465f
            else -> screenHeight * 0.515f
        }
        val rejectPoint = rejectX to rejectY
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
            secondaryRejectTapPoint = null,
            rejectTapTargets = listOf(rejectPoint),
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

    private fun extractUberPrice(lines: List<String>): Double? {
        for (line in lines) {
            val match = currencyRegex.find(line) ?: continue
            val value = match.groupValues[2].replace(",", ".").toDoubleOrNull()
            if (value != null && value >= 3.0) {
                return value
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
            if (currencyRegex.containsMatchIn(line) || uberLegHeaderRegex.containsMatchIn(line)) continue
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

    private fun extractUberLegsAndStops(lines: List<String>): UberLegsData {
        val addresses = mutableListOf<String>()
        val distances = mutableListOf<Double?>()
        val minutes = mutableListOf<Double?>()
        var hasStops = false
        var stopsCount = 0

        for (line in lines) {
            val norm = TextNormalizer.normalize(line)
            val stopMatch = uberStopRegex.find(norm)
            if (stopMatch != null) {
                hasStops = true
                val count = stopMatch.groupValues.getOrNull(1)?.toIntOrNull() ?: 1
                if (count > stopsCount) stopsCount = count
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val headerMatch = uberLegHeaderRegex.find(line)
            if (headerMatch != null) {
                val minVal = headerMatch.groupValues[1].toDoubleOrNull()
                val rawDist = headerMatch.groupValues[2].replace(",", ".").toDoubleOrNull()
                val unit = headerMatch.groupValues[3].lowercase()
                val distKm = if (rawDist != null) {
                    if (unit == "m") rawDist / 1000.0 else rawDist
                } else {
                    null
                }
                minutes.add(minVal)
                distances.add(distKm)

                val addrParts = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size && addrParts.size < 2) {
                    val cand = lines[j].trim()
                    val normCand = TextNormalizer.normalize(cand)

                    // Para ao encontrar proximo trecho ou botao final
                    if (uberLegHeaderRegex.containsMatchIn(cand) ||
                        uberAcceptHints.any { normCand == it || normCand.startsWith("aceitar") || normCand.contains("tenho interesse") }
                    ) {
                        break
                    }

                    // Ignora linhas de nota ("4,90 (600)"), "Verificado" ou "1 parada"
                    if (uberRatingWithCountRegex.containsMatchIn(cand) ||
                        normCand == "verificado" ||
                        normCand == "exclusivo" ||
                        uberStopRegex.containsMatchIn(normCand)
                    ) {
                        j++
                        continue
                    }

                    if (cand.length >= 4) {
                        addrParts.add(cand)
                    }
                    j++
                }
                if (addrParts.isNotEmpty()) {
                    addresses.add(addrParts.joinToString(" "))
                }
                i = j
            } else {
                i++
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
