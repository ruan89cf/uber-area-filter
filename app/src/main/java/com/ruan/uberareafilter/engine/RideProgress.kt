package com.ruan.uberareafilter.engine

import java.util.Calendar

/**
 * Em que pe esta a corrida atual, lido da tela "em viagem" da 99/Uber:
 *  - etapa: indo BUSCAR o passageiro ou ja LEVANDO (passageiro no carro);
 *  - quantos minutos faltam, pela "Chegada prevista: 21:34" da navegacao.
 * Usado para saber se da para encaixar a proxima corrida do OUTRO app (ver [ChainPlanner]).
 */
object RideProgress {

    enum class Stage { PICKUP, TRIP, UNKNOWN }

    data class Snapshot(val stage: Stage, val etaMinutes: Int?)

    private val tripHints = listOf(
        "finalizar corrida", "finalizar viagem", "concluir viagem", "encerrar corrida", "encerrar viagem"
    )
    private val pickupHints = listOf(
        "cheguei", "iniciar corrida", "iniciar viagem", "a caminho do passageiro", "ir para o passageiro",
        "aguardando passageiro", "chegou ao local", "ir para o ponto de embarque", "buscar passageiro"
    )
    // "chegada prevista: 21:34" / "chegada as 21h34" / "chegada 21:34"
    private val etaRegex = Regex("""chegada\s*(?:prevista)?\s*(?:as|a)?\s*:?\s*(\d{1,2})\s*[:h]\s*(\d{2})""")

    fun parse(texts: List<String>, nowMs: Long): Snapshot {
        val norms = texts.map { TextNormalizer.normalize(it) }
        val stage = when {
            norms.any { n -> tripHints.any { n.contains(it) } } -> Stage.TRIP
            norms.any { n -> pickupHints.any { n.contains(it) } } -> Stage.PICKUP
            else -> Stage.UNKNOWN
        }
        val eta = norms.firstNotNullOfOrNull { n ->
            etaRegex.find(n)?.let { m ->
                val h = m.groupValues[1].toIntOrNull()
                val min = m.groupValues[2].toIntOrNull()
                if (h != null && min != null && h in 0..23 && min in 0..59) minutesUntil(h, min, nowMs) else null
            }
        }
        return Snapshot(stage, eta)
    }

    /** Minutos do agora ate HH:MM (vira o dia se o horario ja passou ha mais de 5 min). */
    internal fun minutesUntil(hour: Int, minute: Int, nowMs: Long): Int? {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var diff = cal.timeInMillis - nowMs
        if (diff < -5 * 60_000L) diff += 24 * 3_600_000L
        val mins = Math.round(diff / 60_000.0).toInt().coerceAtLeast(0)
        return mins.takeIf { it <= 180 }
    }
}

/**
 * "Voce esta online/offline" nas telas dos apps de motorista (Uber: "Procurando viagens", "Ficar online"...).
 * Serve para o painel de Ganhos parar de contar horas quando voce fica offline com o app aberto.
 */
object DriverPresence {
    private val onlineHints = listOf("voce esta online", "procurando viagens", "procurando corridas", "buscando corridas")
    private val offlineHints = listOf("voce esta offline", "voce esta off-line", "voce ficou offline", "voce ficou off-line")

    /** true = online, false = offline, null = a tela nao diz. */
    fun detect(texts: List<String>): Boolean? {
        val norms = texts.map { TextNormalizer.normalize(it).trim() }
        // Dialogo "A demanda esta alta... Voltar a ficar online / Ficar offline": ainda nao decidiu
        if (norms.any { it.contains("voltar a ficar online") }) return null
        val on = norms.any { n -> onlineHints.any { n.contains(it) } }
        val off = norms.any { n -> offlineHints.any { n.contains(it) } } || norms.any { it == "ficar online" }
        return when {
            on && !off -> true
            off && !on -> false
            else -> null
        }
    }
}
