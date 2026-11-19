package com.ruan.uberareafilter.account

import android.content.Context
import com.ruan.uberareafilter.storage.ConfigRepository
import com.ruan.uberareafilter.storage.EarningsStore

/**
 * Executa os comandos que o dono mandou pelo painel para este celular (vem junto com a conferencia
 * da licenca). Cada comando roda uma vez so, mesmo se o aviso de "feito" nao chegar ao servidor.
 */
object CommandRunner {

    private const val PREFS = "comandos"

    suspend fun runPending(context: Context, st: AccountState?) {
        val cmds = st?.commands.orEmpty()
        if (cmds.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (c in cmds) {
            val doneKey = "feito_${c.id}"
            val result = prefs.getString(doneKey, null) ?: run {
                val r = runCatching { execute(context, c) }.getOrElse { "erro: ${it.message}" }
                prefs.edit().putString(doneKey, r).apply()
                ConfigRepository.appendLog("[CONTA] Comando do painel (${c.type} ${c.day}): $r", context)
                r
            }
            AccountGate.commandDone(context, c.id, result)
        }
    }

    private suspend fun execute(context: Context, c: AccountCommand): String = when (c.type) {
        "wipe_earnings_before" -> {
            val n = EarningsStore.get(context).deleteBefore(c.day)
            // A copia da conta tambem passa a ter so o que ficou
            runCatching { SyncManager.syncNow(context, force = true) }
            "$n lançamentos antes de ${c.day} apagados"
        }
        "sync_now" -> SyncManager.syncNow(context, force = true)
        else -> "comando desconhecido"
    }
}
