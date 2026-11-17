package com.ruan.uberareafilter.shizuku

import kotlin.system.exitProcess

/**
 * Servico executado dentro do processo privilegiado (shell UID 2000) do Shizuku.
 * Executa `input tap` em nivel de sistema sem a marcacao de Acessibilidade.
 */
class ShizukuTapUserService : IShizukuTapService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun tap(x: Int, y: Int): Boolean {
        return try {
            val proc = ProcessBuilder("/system/bin/input", "tap", x.toString(), y.toString())
                .redirectErrorStream(true)
                .start()
            val exitCode = proc.waitFor()
            exitCode == 0
        } catch (_: Throwable) {
            false
        }
    }
}
