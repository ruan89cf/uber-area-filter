package com.ruan.uberareafilter.engine

import com.ruan.uberareafilter.driver.CaptureMode
import com.ruan.uberareafilter.model.DualConflictPolicy
import com.ruan.uberareafilter.model.FilterConfig

/**
 * Coordenador de conflitos para modo simultaneo (99 + Uber ligados juntos):
 * - Evita duplo aceite enquanto ha uma corrida ativa em andamento (Ride #1)
 * - Grava Destino da 1a e da 2a corrida em sequencia: ao encerrar a 1a, promove a 2a com seu destino;
 *   se a corrida ativa for cancelada, libera o endereco/trava e reabilita aceites nos dois apps.
 * - Detecta cancelamento explicito (Uber) e retorno silencioso para tela livre apos aviso de voz (99).
 * - Fica completamente inativo quando apenas 99 ou apenas Uber estiver habilitado.
 */
class DualRideCoordinator {

    data class ActiveRideSlot(
        val appSource: String,
        val pickupAddress: String,
        val dropoffAddress: String,
        val priceText: String,
        val acceptedAt: Long,
        val confirmedActiveOnScreen: Boolean = false,
        val lastActiveScreenSeenAt: Long = 0L,
        /** Km da viagem em si (embarque -> destino), para estimar quanto falta quando ainda esta buscando. */
        val tripKm: Double? = null
    )

    enum class ConflictAction {
        ALLOW,
        ALLOW_AS_SEQUENTIAL,
        BLOCK_AUTO_ACCEPT_ONLY,
        AUTO_REJECT_OTHER_APP
    }

    data class ConflictDecision(
        val action: ConflictAction,
        val activeRide: ActiveRideSlot? = null
    )

    data class TransitionEvent(
        val message: String,
        val unlocked: Boolean
    )

    var primaryRide: ActiveRideSlot? = null
        private set

    var queuedRide: ActiveRideSlot? = null
        private set

    var lastReleasedDestination: String? = null
        private set

    private val recentEvaluatedOffers = mutableMapOf<String, Pair<ActiveRideSlot, Long>>()

    // Andamento da corrida de cada app (lido da tela "em viagem"): etapa e minutos que faltam
    private data class Progress(val stage: RideProgress.Stage, val etaMin: Int?, val at: Long)
    private val progress = mutableMapOf<String, Progress>()

    /** A oferta foi recusada pelo app: nao pode virar "corrida ativa" se a tela de viagem aparecer depois. */
    fun forgetCandidateOffer(appSource: String) {
        recentEvaluatedOffers.remove(appSource)
    }

    /**
     * Etapa da corrida em andamento: TRIP = passageiro a bordo (corrida iniciada, "Finalizar corrida" na tela),
     * PICKUP = indo buscar / esperando o passageiro. So na TRIP da para encaixar a proxima do outro app.
     */
    fun activeStage(): RideProgress.Stage {
        val current = primaryRide ?: return RideProgress.Stage.UNKNOWN
        return progress[current.appSource.uppercase()]?.stage ?: RideProgress.Stage.UNKNOWN
    }

    /**
     * Quantos minutos faltam para a corrida em andamento terminar (null = nao da para saber).
     * Na etapa de BUSCA soma o tempo ate o embarque com a viagem em si (km da viagem x 2,4 min/km).
     */
    fun remainingMinutes(nowMs: Long = System.currentTimeMillis()): Double? {
        val current = primaryRide ?: return null
        val p = progress[current.appSource.uppercase()] ?: return null
        val eta = p.etaMin ?: return null
        if (nowMs - p.at > 10 * 60_000L) return null
        val elapsed = (nowMs - p.at) / 60_000.0
        return when (p.stage) {
            RideProgress.Stage.TRIP -> (eta - elapsed).coerceAtLeast(0.0)
            RideProgress.Stage.PICKUP -> {
                val trip = current.tripKm ?: return null
                (eta - elapsed).coerceAtLeast(0.0) + 2.0 + trip * 2.4
            }
            RideProgress.Stage.UNKNOWN -> null
        }
    }
    private var idleScreenFirstSeenAt: Long = 0L
    private var idleScreenApp: String = ""

    fun isActive(config: FilterConfig): Boolean =
        CaptureMode.from(config) == CaptureMode.DUAL && config.dualConflictGuardEnabled

    fun reset() {
        primaryRide = null
        queuedRide = null
        progress.clear()
        idleScreenFirstSeenAt = 0L
        idleScreenApp = ""
    }

    /**
     * Registra a ultima oferta avaliada por aplicativo caso o motorista aceite manualmente no dedo.
     */
    fun rememberCandidateOffer(offer: ParsedRideOffer, priceFormatted: String, nowMs: Long = System.currentTimeMillis()) {
        if (!offer.isRideOffer) return
        val slot = ActiveRideSlot(
            appSource = offer.appSource,
            pickupAddress = offer.pickupAddress.orEmpty(),
            dropoffAddress = offer.dropoffAddress.orEmpty(),
            priceText = priceFormatted,
            acceptedAt = nowMs,
            tripKm = offer.dropoffDistanceKm
        )
        recentEvaluatedOffers[offer.appSource] = slot to nowMs
    }

    /**
     * Registra aceite de corrida (1a corrida ou 2a corrida em sequencia).
     */
    fun onRideAccepted(
        offer: ParsedRideOffer,
        config: FilterConfig,
        priceFormatted: String,
        nowMs: Long = System.currentTimeMillis()
    ): TransitionEvent? {
        if (!isActive(config)) {
            reset()
            return null
        }

        val slot = ActiveRideSlot(
            appSource = offer.appSource,
            pickupAddress = offer.pickupAddress.orEmpty(),
            dropoffAddress = offer.dropoffAddress.orEmpty(),
            priceText = priceFormatted,
            acceptedAt = nowMs,
            confirmedActiveOnScreen = false,
            lastActiveScreenSeenAt = 0L,
            tripKm = offer.dropoffDistanceKm
        )
        recentEvaluatedOffers.remove(offer.appSource)

        val current = primaryRide
        if (current == null) {
            primaryRide = slot
            idleScreenFirstSeenAt = 0L
            idleScreenApp = ""
            val destLabel = slot.dropoffAddress.ifBlank { slot.pickupAddress.ifBlank { "A definir" } }
            return TransitionEvent("🔒 Trava iniciada [${slot.appSource}] • Destino #1: $destLabel", unlocked = false)
        }

        // Mesma corrida clicada de novo em menos de 5s -> nao duplica na fila
        if (current.appSource == slot.appSource &&
            (nowMs - current.acceptedAt < 5_000L || sameRoute(current, slot))
        ) {
            return null
        }

        // Segunda corrida em sequencia: grava endereco da 2a corrida na fila
        queuedRide = slot
        val destLabel = slot.dropoffAddress.ifBlank { slot.pickupAddress.ifBlank { "A definir" } }
        return TransitionEvent("📌 Corrida #2 gravada [${slot.appSource}] • Destino #2: $destLabel", unlocked = false)
    }

    /**
     * Avalia se uma nova oferta pode ser auto-aceita ou sofre bloqueio/recusa por conflito.
     */
    fun evaluateOfferConflict(offer: ParsedRideOffer, config: FilterConfig): ConflictDecision {
        if (!isActive(config)) {
            if (primaryRide != null || queuedRide != null) reset()
            return ConflictDecision(ConflictAction.ALLOW, null)
        }

        val current = primaryRide ?: return ConflictDecision(ConflictAction.ALLOW, null)

        // Mesmo aplicativo mandando corrida encadeada em sequencia
        if (offer.appSource.equals(current.appSource, ignoreCase = true)) {
            return ConflictDecision(ConflictAction.ALLOW_AS_SEQUENTIAL, current)
        }

        // Outro aplicativo tentando tocar enquanto ha viagem em andamento
        return when (config.dualConflictPolicy) {
            DualConflictPolicy.BLOCK_AUTO_ACCEPT_ONLY ->
                ConflictDecision(ConflictAction.BLOCK_AUTO_ACCEPT_ONLY, current)
            DualConflictPolicy.AUTO_REJECT_OTHER_APP ->
                ConflictDecision(ConflictAction.AUTO_REJECT_OTHER_APP, current)
        }
    }

    /**
     * Atualiza estado a partir da leitura atual da tela (viagem ativa, cancelamento Uber/99, avaliacao ou mapa livre).
     */
    fun onScreenObserved(
        parsed: ParsedRideOffer,
        config: FilterConfig,
        nowMs: Long = System.currentTimeMillis()
    ): TransitionEvent? {
        if (!isActive(config)) {
            if (primaryRide != null || queuedRide != null) reset()
            return null
        }

        val app = parsed.appSource

        // 1. Cancelamento explicito na tela ("Passageiro cancelou", "Voce cancelou a corrida"...)
        if (parsed.isCancelledScreen) {
            idleScreenFirstSeenAt = 0L
            val current = primaryRide
            val queued = queuedRide
            if (current != null && queued != null &&
                current.appSource.equals(app, ignoreCase = true) && queued.appSource.equals(app, ignoreCase = true)
            ) {
                // Com a proxima corrida do mesmo app na fila, quem cancela quase sempre e o passageiro da proxima
                queuedRide = null
                val keepDest = current.dropoffAddress.ifBlank { current.pickupAddress.ifBlank { "A definir" } }
                return TransitionEvent(
                    "🔄 Fila #2 [$app] CANCELADA • Mantendo Corrida #1 [$app] (Dest: $keepDest)",
                    unlocked = false
                )
            }
            return releaseAppRide(app, reason = "CANCELADA", nowMs = nowMs)
        }

        // 2. Tela de 5 estrelas ("Como foi sua corrida") -> viagem concluida!
        if (parsed.isRatingScreen) {
            idleScreenFirstSeenAt = 0L
            return releaseAppRide(app, reason = "FINALIZADA", nowMs = nowMs)
        }

        // 3. Tela em viagem confirmada ("Cheguei", "Iniciar/Finalizar corrida/viagem")
        if (parsed.isInActiveRide) {
            idleScreenFirstSeenAt = 0L
            idleScreenApp = ""
            val snap = RideProgress.parse(parsed.allTexts, nowMs)
            val key = app.uppercase()
            val prev = progress[key]
            val stage = if (snap.stage != RideProgress.Stage.UNKNOWN) snap.stage else prev?.stage ?: RideProgress.Stage.UNKNOWN
            // Mudou de etapa (buscou o passageiro): o horario de chegada antigo era do embarque, nao vale mais
            val keepOldEta = prev != null && prev.stage == stage
            progress[key] = when {
                snap.etaMinutes != null -> Progress(stage, snap.etaMinutes, nowMs)
                keepOldEta -> prev!!.copy(stage = stage)
                else -> Progress(stage, null, nowMs)
            }
            val current = primaryRide
            if (current == null) {
                val recent = recentEvaluatedOffers[app]?.takeIf { nowMs - it.second <= 45_000L }?.first
                val created = (recent ?: ActiveRideSlot(
                    appSource = app,
                    pickupAddress = parsed.pickupAddress.orEmpty(),
                    dropoffAddress = parsed.dropoffAddress.orEmpty(),
                    priceText = "",
                    acceptedAt = nowMs
                )).copy(
                    confirmedActiveOnScreen = true,
                    lastActiveScreenSeenAt = nowMs
                )
                primaryRide = created
                val destLabel = created.dropoffAddress.ifBlank { created.pickupAddress.ifBlank { "Em andamento" } }
                return TransitionEvent("🔒 Corrida ativa detectada [$app] • Destino: $destLabel", unlocked = false)
            }
            if (current.appSource.equals(app, ignoreCase = true)) {
                primaryRide = current.copy(confirmedActiveOnScreen = true, lastActiveScreenSeenAt = nowMs)
            }
            return null
        }

        // 4. Retorno para tela inicial livre / nova oferta solta (especialmente na 99, que cancela so por voz!)
        val current = primaryRide ?: return null
        if (!current.appSource.equals(app, ignoreCase = true)) {
            return null
        }

        if (parsed.isIdleHomeScreen) {
            if (idleScreenApp != app || idleScreenFirstSeenAt == 0L) {
                idleScreenApp = app
                idleScreenFirstSeenAt = nowMs
            }
            val confirmedThenLeftTrip = current.confirmedActiveOnScreen &&
                (nowMs - current.lastActiveScreenSeenAt >= 1_200L)
            val settledAfterAccept = (nowMs - current.acceptedAt >= 6_500L) &&
                (nowMs - idleScreenFirstSeenAt >= 1_000L)
            if (confirmedThenLeftTrip || settledAfterAccept) {
                idleScreenFirstSeenAt = 0L
                idleScreenApp = ""
                return releaseAppRide(app, reason = "ENCERRADA/CANCELADA (tela livre)", nowMs = nowMs)
            }
            return null
        }

        // Se chegou oferta comum sem confirmacao de viagem previa apos 7s, indica cancelamento silencioso antes do embarque
        if (parsed.isRideOffer && !current.confirmedActiveOnScreen && (nowMs - current.acceptedAt >= 7_000L)) {
            idleScreenFirstSeenAt = 0L
            return releaseAppRide(app, reason = "CANCELADA antes do embarque (nova chamada)", nowMs = nowMs)
        }

        idleScreenFirstSeenAt = 0L
        return null
    }

    /**
     * Liberacao manual em 1 toque (ex: usuario ouviu a voz de cancelamento da 99 enquanto olhava Waze/Uber).
     */
    fun releaseManually(): TransitionEvent? {
        val current = primaryRide ?: return null
        idleScreenFirstSeenAt = 0L
        idleScreenApp = ""
        return releaseAppRide(current.appSource, reason = "LIBERADA manualmente", nowMs = System.currentTimeMillis())
    }

    private fun releaseAppRide(appSource: String, reason: String, nowMs: Long): TransitionEvent? {
        val current = primaryRide
        val queued = queuedRide
        if (current != null && current.appSource.equals(appSource, ignoreCase = true)) {
            progress.remove(appSource.uppercase())
        }

        // Caso a 2a corrida na fila tenha sido cancelada separadamente enquanto a 1a ainda roda
        if (current != null &&
            !current.appSource.equals(appSource, ignoreCase = true) &&
            queued != null &&
            queued.appSource.equals(appSource, ignoreCase = true)
        ) {
            val qAddr = queued.dropoffAddress.ifBlank { queued.pickupAddress }
            queuedRide = null
            lastReleasedDestination = qAddr.ifBlank { lastReleasedDestination }
            val keepDest = current.dropoffAddress.ifBlank { current.pickupAddress.ifBlank { "A definir" } }
            return TransitionEvent(
                "🔄 Fila #2 [$appSource] $reason • Mantendo Corrida #1 [${current.appSource}] (Dest: $keepDest)",
                unlocked = false
            )
        }

        if (current != null && current.appSource.equals(appSource, ignoreCase = true)) {
            val endedAddr = current.dropoffAddress.ifBlank { current.pickupAddress }
            if (endedAddr.isNotBlank()) {
                lastReleasedDestination = endedAddr
            }

            if (queued != null) {
                // Corrida #1 acabou/cancelou -> promove Corrida #2 com o endereco da 2a corrida salvo!
                val promoted = queued.copy(acceptedAt = nowMs)
                primaryRide = promoted
                queuedRide = null
                val dest2 = promoted.dropoffAddress.ifBlank { promoted.pickupAddress.ifBlank { "A definir" } }
                return TransitionEvent(
                    "🔄 #1 [${current.appSource}] $reason → Ativada #2 [${promoted.appSource}] (Dest: $dest2)",
                    unlocked = false
                )
            }

            primaryRide = null
            val addrHint = if (endedAddr.isNotBlank()) " • Último end.: $endedAddr" else ""
            return TransitionEvent(
                "🔓 Corrida [${current.appSource}] $reason$addrHint → Apps liberados!",
                unlocked = true
            )
        }

        return null
    }

    private fun sameRoute(a: ActiveRideSlot, b: ActiveRideSlot): Boolean {
        val dropMatch = a.dropoffAddress.isNotBlank() &&
            a.dropoffAddress.equals(b.dropoffAddress, ignoreCase = true)
        val pickMatch = a.pickupAddress.isNotBlank() &&
            a.pickupAddress.equals(b.pickupAddress, ignoreCase = true)
        return dropMatch && pickMatch
    }
}
