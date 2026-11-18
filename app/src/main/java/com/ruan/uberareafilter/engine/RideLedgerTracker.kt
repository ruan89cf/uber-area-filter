package com.ruan.uberareafilter.engine

import com.ruan.uberareafilter.model.RunState
import com.ruan.uberareafilter.storage.EarningsStore
import com.ruan.uberareafilter.storage.LedgerStore
import java.util.Locale

/**
 * Transforma o que o leitor de tela ve em dados do painel de Ganhos:
 * - toda oferta avaliada conta como "oferta recebida"; recusa automatica conta com o motivo;
 * - aceite automatico (ou aceite no dedo, detectado pela tela "em viagem" logo depois da oferta)
 *   vira uma corrida com valor, km, minutos e combustivel estimado;
 * - tela de avaliacao / volta para a tela livre fecha a corrida (concluida, com a DURACAO real);
 *   tela de cancelamento (do passageiro ou sua) marca como cancelada (nao conta no ganho);
 * - fila por app: a 99 deixa aceitar a PROXIMA corrida durante a viagem; ela espera a atual terminar;
 * - corrida "fantasma" (o fim nunca apareceu) nao fica aberta para sempre: 45 min sem a tela da
 *   corrida = concluida; aceite que nunca virou viagem em 30 min = cancelado.
 *
 * Horas online (TURNO): conta enquanto chegam ofertas ou ha corrida em andamento. Um intervalo sem
 * nada de ate 20 min conta (esperando oferta); mais que isso e pausa e nao conta. Tela "Voce esta
 * offline" encerra o turno na hora. App de motorista aberto sozinho (offline) NAO conta mais.
 *
 * Cada passo vai para o log com o prefixo [GANHOS], [CICLO] ou [TURNO].
 */
class RideLedgerTracker(
    private val store: LedgerStore,
    private val log: (String) -> Unit
) {
    private data class Candidate(
        val app: String,
        val ts: Long,
        val fingerprint: String,
        val price: Double?,
        val priceEstimated: Boolean,
        val km: Double,
        val pickupKm: Double,
        val minutes: Double,
        val fuelCost: Double,
        val pickup: String,
        val dropoff: String,
        val decision: String,
        var rejectedByUs: Boolean = false
    )

    private data class OpenRide(
        val id: Long,
        val app: String,
        val acceptedAt: Long,
        val price: Double?,
        var confirmed: Boolean = false,
        var lastActiveSeenAt: Long = 0L,
        /** Quando virou a corrida atual (para corridas da fila, e o fim da anterior). */
        var startedAt: Long = acceptedAt,
        /** Minutos estimados da corrida (busca + viagem), pelo R$/h do card. */
        val estMinutes: Double = 0.0,
        /** Viu "Finalizar corrida" (passageiro a bordo): voltar para a tela livre depois disso = concluida. */
        var tripStarted: Boolean = false,
        /** Voltou da memoria depois de religar: so conta horas quando a tela dela aparecer de novo. */
        var restoredAt: Long = 0L
    )

    private val candidates = HashMap<String, Candidate>()
    private val open = HashMap<String, ArrayDeque<OpenRide>>()
    private val lastTag = HashMap<String, String>()
    private val countedOffers = LinkedHashSet<String>()
    private val countedRejects = LinkedHashSet<String>()
    private val noOfferInRideLogged = HashSet<String>()

    // ---- Turno (horas online) ----
    private var lastTickAt = 0L
    private var committedMs = 0L
    private var gapPendingMs = 0L
    private var lastFlushAt = 0L
    private var lastWorkSignalAt = 0L
    private val lastSignalByApp = HashMap<String, Long>()
    private val presence = HashMap<String, Boolean>()
    private var shiftOpen = false
    private var lastExpireCheckAt = 0L

    private val br = Locale("pt", "BR")

    fun hasOpenRide(app: String? = null): Boolean =
        if (app == null) open.values.any { it.isNotEmpty() } else open[app]?.isNotEmpty() == true

    /** Quantas corridas abertas (atual + fila) o app tem. */
    fun openCount(app: String): Int = open[app]?.size ?: 0

    private fun queue(app: String): ArrayDeque<OpenRide> = open.getOrPut(app) { ArrayDeque() }

    /**
     * Depois que o servico religa (botao OFF, atualizacao, Android fechou o app), as corridas
     * abertas nos ultimos 90 min voltam para a memoria. Mais velhas que isso o app nao viu terminar:
     * entram como concluidas (e param de segurar o tempo online).
     */
    fun restoreOpenRides(now: Long) {
        val rows = runCatching { store.openRidesSince(now - 4 * 3_600_000L) }.getOrElse {
            log("[GANHOS] ⚠ Não consegui ler corridas abertas: ${it.javaClass.simpleName}: ${it.message}")
            return
        }
        if (rows.isEmpty()) return
        val (recent, old) = rows.partition { now - it.ts <= RESTORE_MAX_AGE_MS }
        old.forEach { e ->
            runCatching { store.setStatus(e.id, EarningsStore.STATUS_DONE) }
            log("[GANHOS] Corrida #${e.id} ${e.app} aberta desde ${(now - e.ts) / 60000} min: o fim não foi visto, ficou como CONCLUÍDA (corrija no painel se precisar)")
        }
        recent.forEach { e ->
            val q = queue(e.app)
            if (q.any { it.id == e.id }) return@forEach
            q.addLast(
                OpenRide(
                    id = e.id,
                    app = e.app,
                    acceptedAt = e.ts,
                    price = e.amount.takeIf { it > 0.0 },
                    // aceita ha mais de 2 min: quase certo que ja estava em andamento
                    confirmed = now - e.ts > 120_000L,
                    lastActiveSeenAt = now,
                    restoredAt = now
                )
            )
        }
        if (recent.isNotEmpty()) {
            log("[GANHOS] Retomei ${recent.size} corrida(s) aberta(s) depois de religar: " +
                recent.joinToString(", ") { "#${it.id} ${it.app}" })
        }
    }

    fun onOfferEvaluated(
        parsed: ParsedRideOffer,
        decision: String,
        fuelCost: Double?,
        now: Long
    ) {
        workSignal(parsed.appSource, now)
        val fp = parsed.fingerprint.ifBlank { "${parsed.appSource}:${parsed.ridePrice}:${parsed.dropoffAddress}" }
        if (remember(countedOffers, fp)) store.countOffer(now)
        val estimated = parsed.ridePrice == null && parsed.estimatedPrice != null
        val price = parsed.ridePrice ?: parsed.estimatedPrice
        val minutes = if (price != null && parsed.hourlyRate != null && parsed.hourlyRate > 0.0) {
            price / parsed.hourlyRate * 60.0
        } else 0.0
        candidates[parsed.appSource] = Candidate(
            app = parsed.appSource,
            ts = now,
            fingerprint = fp,
            price = price,
            priceEstimated = estimated,
            km = parsed.totalDistanceKm ?: 0.0,
            pickupKm = parsed.pickupDistanceKm ?: 0.0,
            minutes = minutes,
            fuelCost = fuelCost ?: 0.0,
            pickup = parsed.pickupAddress.orEmpty(),
            dropoff = parsed.dropoffAddress.orEmpty(),
            decision = decision
        )
    }

    /**
     * Negocia: o app pediu outro valor (contraproposta). Se o passageiro aceitar, a corrida entra no
     * painel com o valor PEDIDO, e a janela de 2 min para ligar o aceite a oferta conta a partir de agora.
     */
    fun onCounterOffer(app: String, fingerprint: String, price: Double, now: Long) {
        val cand = candidates[app] ?: return
        if (fingerprint.isNotBlank() && cand.fingerprint != fingerprint) return
        candidates[app] = cand.copy(price = price, priceEstimated = false, ts = now, decision = "NEGOCIA")
        log("[GANHOS] Negocia $app: pedi R$ ${String.format(Locale("pt", "BR"), "%.2f", price)}; se o passageiro aceitar, a corrida entra com esse valor")
    }

    fun onAutoRejected(parsed: ParsedRideOffer, reason: String, now: Long) {
        val fp = parsed.fingerprint.ifBlank { "${parsed.appSource}:${parsed.ridePrice}:${parsed.dropoffAddress}" }
        candidates[parsed.appSource]?.takeIf { it.fingerprint == fp }?.rejectedByUs = true
        if (remember(countedRejects, fp)) store.countRejected(now, reasonKeys(reason))
    }

    fun onAccepted(parsed: ParsedRideOffer, now: Long) {
        val app = parsed.appSource
        val q = queue(app)
        // o mesmo aceite chega duas vezes (toque + confirmacao): ignora o repetido
        val newest = q.lastOrNull()
        if (newest != null && now - newest.acceptedAt < 15_000L) return
        val cand = candidates[app]
        if (cand == null) {
            log("[GANHOS] Aceite $app sem oferta lida: corrida não registrada")
            return
        }
        val current = q.firstOrNull()
        createRide(cand, EarningsStore.SOURCE_AUTO, now)?.let { r ->
            if (current != null) {
                log("[GANHOS] Corrida #${r.id} $app aceita durante a corrida #${current.id}: fica na fila e começa quando a #${current.id} terminar")
            }
        }
    }

    /**
     * "Voce esta online/offline" lido na tela do app de motorista.
     * Offline encerra o turno na hora (o tempo parado com o app aberto e offline nao conta).
     */
    fun onPresence(app: String, online: Boolean, now: Long) {
        val prev = presence[app]
        presence[app] = online
        if (prev != online) log("[TURNO] $app: ${if (online) "você está ONLINE" else "você está OFFLINE"}")
        if (online) {
            workSignal(app, now)
            return
        }
        // Outro app ainda trabalhando (ofertas nos ultimos 5 min)? Entao o turno continua.
        val otherWorking = lastSignalByApp.any { (a, t) -> !a.equals(app, true) && now - t <= 5 * 60_000L && presence[a] != false }
        if (!otherWorking && !hasLiveRide(now)) endShift(now, "tela de offline ($app)")
    }

    /** Chamado para TODA tela lida (oferta, em viagem, avaliacao, cancelada, tela livre...). */
    fun onScreen(parsed: ParsedRideOffer, now: Long) {
        val app = parsed.appSource
        val tag = when {
            parsed.isRideOffer -> "OFERTA"
            parsed.isContinuePrompt -> "CONTINUAR"
            parsed.isCancelledScreen -> "CANCELADA"
            parsed.isRatingScreen -> "AVALIACAO"
            parsed.isInActiveRide -> "EM_VIAGEM"
            parsed.isIdleHomeScreen -> "TELA_LIVRE"
            parsed.isCounterOfferEditor -> "CONTRAPROPOSTA"
            else -> "OUTRA"
        }
        val prev = lastTag[app]
        if (prev != tag) {
            lastTag[app] = tag
            val q = open[app].orEmpty()
            val rideInfo = q.firstOrNull()?.let { o ->
                val queued = if (q.size > 1) ", fila: " + q.drop(1).joinToString(",") { "#${it.id}" } else ""
                " (corrida #${o.id} aberta, confirmada=${o.confirmed}$queued)"
            } ?: ""
            log("[CICLO] $app: ${prev ?: "-"} -> $tag$rideInfo")
        }
        // Tela livre sozinha nao prova que voce esta online (o app pode estar offline); o resto prova.
        if (tag != "OUTRA" && tag != "TELA_LIVRE") workSignal(app, now)

        when (tag) {
            "EM_VIAGEM" -> {
                val o = open[app]?.firstOrNull()
                if (o != null) {
                    if (!o.confirmed) log("[GANHOS] Corrida #${o.id} $app confirmada na tela (em viagem)")
                    o.confirmed = true
                    o.lastActiveSeenAt = now
                    o.restoredAt = 0L
                    if (!o.tripStarted && RideProgress.parse(parsed.allTexts, now).stage == RideProgress.Stage.TRIP) {
                        o.tripStarted = true
                        log("[GANHOS] Corrida #${o.id} $app: passageiro a bordo (viagem iniciada)")
                    }
                } else {
                    val cand = candidates[app]?.takeIf { now - it.ts <= 120_000L && !it.rejectedByUs }
                    if (cand != null) {
                        createRide(cand, EarningsStore.SOURCE_MANUAL, now)?.let { r ->
                            r.confirmed = true
                            r.lastActiveSeenAt = now
                        }
                    } else if (noOfferInRideLogged.add("$app:${now / 600_000L}")) {
                        log("[GANHOS] $app em viagem, mas sem oferta lida nos últimos 2 min: não registrei (lance no painel se quiser)")
                    }
                }
            }
            // Telas de fim contam uma vez so: varias leituras seguidas da MESMA avaliacao/cancelamento
            // nao podem fechar tambem a corrida da fila que acabou de virar a atual (18/11: #33, #35, #37).
            "AVALIACAO" -> if (prev != "AVALIACAO") closeCurrent(app, EarningsStore.STATUS_DONE, "tela de avaliação", now)
            "CANCELADA" -> if (prev != "CANCELADA") {
                val q = open[app]
                if (q != null && q.size > 1) {
                    // Com corrida na fila, quem cancela quase sempre e o passageiro da PROXIMA
                    // (o da atual ja esta no carro).
                    val r = q.removeLast()
                    finish(r, EarningsStore.STATUS_CANCELLED, "tela de cancelamento (era a corrida da fila)", now)
                } else {
                    closeCurrent(app, EarningsStore.STATUS_CANCELLED, "tela de cancelamento", now)
                }
            }
            "TELA_LIVRE" -> {
                val o = open[app]?.firstOrNull()
                when {
                    o == null -> Unit
                    o.confirmed && now - o.lastActiveSeenAt > 15_000L -> {
                        // Voltou para a tela livre pouco depois do aceite e sem iniciar a viagem = cancelada
                        // (ex.: aceitou por engano e cancelou; a tela "Voce cancelou" pode nao aparecer)
                        val elapsedMin = (now - o.startedAt) / 60_000.0
                        if (!o.tripStarted && elapsedMin < maxOf(3.0, o.estMinutes * 0.4)) {
                            closeCurrent(
                                app, EarningsStore.STATUS_CANCELLED,
                                "voltou para a tela livre ${elapsedMin.toInt()} min depois do aceite, sem iniciar a viagem", now
                            )
                        } else {
                            closeCurrent(app, EarningsStore.STATUS_DONE, "voltou para a tela livre", now)
                        }
                    }
                    // aceite que nunca virou viagem (outro motorista pegou / a 99 nao confirmou)
                    !o.confirmed && now - o.startedAt > 90_000L ->
                        closeCurrent(app, EarningsStore.STATUS_CANCELLED, "aceite que não começou (tela livre sem viagem)", now)
                }
            }
        }
    }

    /** Chamado a cada volta do leitor (~1 s). Soma o tempo do turno e limpa corridas fantasmas. */
    fun tickWork(now: Long, runState: RunState) {
        val dt = if (lastTickAt > 0L) (now - lastTickAt).coerceIn(0L, 10_000L) else 0L
        lastTickAt = now
        if (now - lastExpireCheckAt > 30_000L) {
            lastExpireCheckAt = now
            expireStaleRides(now)
        }
        when {
            runState == RunState.OFF -> gapPendingMs = 0L
            // Corrida em andamento sempre conta (mesmo com o Waze na frente)
            hasLiveRide(now) -> {
                if (!shiftOpen) openShift(now, "corrida em andamento")
                committedMs += gapPendingMs + dt
                gapPendingMs = 0L
            }
            // Esperando a proxima oferta: so conta se ela vier em ate 20 min (senao foi pausa)
            shiftOpen && lastWorkSignalAt > 0L && now - lastWorkSignalAt <= MAX_GAP_MS -> gapPendingMs += dt
            shiftOpen -> endShift(lastWorkSignalAt, "mais de ${MAX_GAP_MS / 60_000} min sem oferta nem corrida")
        }
        if (committedMs >= 60_000L || (committedMs >= 1000L && now - lastFlushAt > 60_000L)) flush(now)
    }

    fun flush(now: Long = System.currentTimeMillis()) {
        val sec = committedMs / 1000L
        if (sec > 0L) {
            runCatching { store.addOnline(now, sec) }
            committedMs -= sec * 1000L
        }
        lastFlushAt = now
    }

    /** Sinal de trabalho (oferta, tela da corrida, "voce esta online"): o intervalo desde o ultimo conta. */
    private fun workSignal(app: String?, now: Long) {
        if (!shiftOpen) {
            openShift(now, "primeira oferta/corrida")
        } else if (lastWorkSignalAt > 0L && now - lastWorkSignalAt <= MAX_GAP_MS) {
            committedMs += gapPendingMs
        }
        gapPendingMs = 0L
        lastWorkSignalAt = now
        if (app != null) lastSignalByApp[app] = now
    }

    private fun openShift(now: Long, why: String) {
        shiftOpen = true
        gapPendingMs = 0L
        lastWorkSignalAt = now
        log("[TURNO] Começou a contar horas online ($why)")
    }

    private fun endShift(at: Long, why: String) {
        if (!shiftOpen) return
        shiftOpen = false
        gapPendingMs = 0L
        log("[TURNO] Parou de contar horas: $why")
        flush(at.coerceAtLeast(lastFlushAt))
    }

    /** Corrida que de fato esta rolando (nao fantasma). */
    private fun hasLiveRide(now: Long): Boolean = open.values.any { q ->
        val o = q.firstOrNull() ?: return@any false
        if (o.restoredAt > 0L) return@any false // retomada e ainda nao vista: pode ser fantasma
        if (o.confirmed) {
            now - o.lastActiveSeenAt <= LIVE_NO_SCREEN_MS && now - o.startedAt <= LIVE_MAX_MS
        } else {
            now - o.startedAt <= 15 * 60_000L
        }
    }

    /** Fecha corridas que o app nao viu terminar, para nao ficarem abertas (e contando horas) para sempre. */
    private fun expireStaleRides(now: Long) {
        for ((app, q) in open) {
            val o = q.firstOrNull() ?: continue
            when {
                o.restoredAt > 0L && now - o.restoredAt > RESTORED_UNSEEN_MS ->
                    closeCurrent(app, EarningsStore.STATUS_DONE, "fim não visto (retomada depois de religar e a tela dela não voltou em ${RESTORED_UNSEEN_MS / 60_000} min)", o.restoredAt)
                o.confirmed && now - o.lastActiveSeenAt > LIVE_NO_SCREEN_MS ->
                    closeCurrent(app, EarningsStore.STATUS_DONE, "fim não visto (${LIVE_NO_SCREEN_MS / 60_000} min sem a tela da corrida)", o.lastActiveSeenAt)
                !o.confirmed && now - o.startedAt > UNCONFIRMED_MAX_MS ->
                    closeCurrent(app, EarningsStore.STATUS_CANCELLED, "aceite não confirmado em ${UNCONFIRMED_MAX_MS / 60_000} min", now)
            }
        }
    }

    private fun createRide(cand: Candidate, source: String, now: Long): OpenRide? {
        val price = cand.price
        val note = when {
            price == null -> "valor não lido — confira"
            cand.priceEstimated -> "valor estimado pelo R\$/hora (preço não lido) — confira"
            cand.decision == "NEGOCIA" -> "Negocia: valor que o app pediu — confira se foi esse"
            else -> ""
        }
        val id = runCatching {
            store.insert(
                EarningsStore.Entry(
                    ts = now,
                    kind = EarningsStore.KIND_RIDE,
                    app = cand.app,
                    amount = price ?: 0.0,
                    km = cand.km,
                    pickupKm = cand.pickupKm,
                    minutes = cand.minutes,
                    fuelCost = cand.fuelCost,
                    status = EarningsStore.STATUS_OPEN,
                    source = source,
                    pickup = cand.pickup,
                    dropoff = cand.dropoff,
                    note = note
                )
            )
        }.getOrElse {
            log("[GANHOS] ⚠ Falha ao gravar corrida: ${it.javaClass.simpleName}: ${it.message}")
            return null
        }
        runCatching { store.countAccepted(now) }
        candidates.remove(cand.app)
        // Corrida retomada depois de religar e que nao apareceu mais: nao pode ficar na frente da nova
        val head = open[cand.app]?.firstOrNull()
        if (head != null && head.restoredAt > 0L) {
            closeCurrent(cand.app, EarningsStore.STATUS_DONE, "fim não visto (retomada depois de religar; chegou corrida nova)", now)
        }
        val ride = OpenRide(id = id, app = cand.app, acceptedAt = now, price = price, estMinutes = cand.minutes)
        queue(cand.app).addLast(ride)
        val how = if (source == EarningsStore.SOURCE_AUTO) "aceite automático" else "aceite no dedo (detectado pela tela em viagem)"
        val priceTxt = when {
            price == null -> "R$ ?"
            cand.priceEstimated -> String.format(br, "~R$ %.2f (estimado pelo R\$/hora)", price)
            else -> String.format(br, "R$ %.2f", price)
        }
        log(
            "[GANHOS] Corrida #$id ${cand.app} registrada: $priceTxt" +
                String.format(br, " • %.1f km • %.0f min • comb. R$ %.2f", cand.km, cand.minutes, cand.fuelCost) +
                " • $how • decisão=${cand.decision} • ${cand.pickup} → ${cand.dropoff}"
        )
        return ride
    }

    private fun closeCurrent(app: String, status: String, why: String, now: Long) {
        val q = open[app] ?: return
        val o = q.removeFirstOrNull() ?: return
        finish(o, status, why, now)
        q.firstOrNull()?.let { next ->
            next.startedAt = now
            log("[GANHOS] Corrida #${next.id} $app (que estava na fila) agora é a corrida atual")
        }
    }

    private fun finish(o: OpenRide, status: String, why: String, now: Long) {
        runCatching { store.setStatus(o.id, status) }
        val minutes = (now - o.startedAt) / 60_000.0
        // Duracao real (do aceite ate a avaliacao): o painel mostra "tempo em corrida"
        if (status == EarningsStore.STATUS_DONE && minutes in 1.0..180.0) {
            runCatching { store.setMinutes(o.id, minutes) }
        }
        val label = if (status == EarningsStore.STATUS_CANCELLED) "CANCELADA (não conta no ganho)" else "CONCLUÍDA"
        log("[GANHOS] Corrida #${o.id} ${o.app} $label por $why após ${minutes.toLong()} min")
    }

    private fun remember(set: LinkedHashSet<String>, key: String): Boolean {
        if (!set.add(key)) return false
        if (set.size > 300) set.iterator().let { it.next(); it.remove() }
        return true
    }

    companion object {
        /** Intervalo maximo sem oferta/corrida que ainda conta como trabalho (esperando chamada). */
        const val MAX_GAP_MS = 20 * 60_000L
        /** Corrida restaurada so se foi aceita ha no maximo 90 min. */
        const val RESTORE_MAX_AGE_MS = 90 * 60_000L
        /** Sem ver a tela da corrida por 45 min = acabou sem o app ver. */
        const val LIVE_NO_SCREEN_MS = 45 * 60_000L
        const val LIVE_MAX_MS = 150 * 60_000L
        const val UNCONFIRMED_MAX_MS = 30 * 60_000L
        const val RESTORED_UNSEEN_MS = 20 * 60_000L

        /** "✘ R$/h líq.: R$22 (mín R$30) • Emb. longe: 2,7km" -> ["R$/h líq.", "Emb. longe"] */
        fun reasonKeys(reason: String): List<String> =
            reason.replace("✘", "").replace("✔", "")
                .split("•", "|")
                .map { part ->
                    val p = part.trim()
                    val cut = p.indexOfFirst { it == ':' || it == '(' || it.isDigit() }
                    (if (cut > 0) p.substring(0, cut) else p).trim().trimEnd('-', ' ').take(24)
                }
                .filter { it.length >= 3 }
    }
}
