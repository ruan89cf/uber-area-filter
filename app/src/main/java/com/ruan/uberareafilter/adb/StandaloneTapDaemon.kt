package com.ruan.uberareafilter.adb

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Daemon leve executado dentro do proprio APK via `app_process` no usuario `shell` (UID 2000).
 * Escuta em loopback local `127.0.0.1:47831` protegido por token secreto aleatorio (evita bloqueio
 * SELinux de `unix_stream_socket connectto` no Android 15 e funciona normalmente no 4G/5G).
 */
object StandaloneTapDaemon {

    const val DEFAULT_PORT = 47831

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull() ?: DEFAULT_PORT
        val token = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return

        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
            }
        } catch (_: Throwable) {
            return
        }

        var running = true
        while (running) {
            var client: Socket? = null
            try {
                client = server.accept()
                client.soTimeout = 2500
                val reader = BufferedReader(InputStreamReader(client.inputStream))
                val writer = PrintWriter(OutputStreamWriter(client.outputStream), true)
                while (running) {
                    val line = reader.readLine() ?: break
                    val parts = line.trim().split(Regex("""\s+"""))
                    if (parts.firstOrNull() != token) {
                        writer.println("UNAUTHORIZED")
                        break
                    }
                    when (parts.getOrNull(1)?.uppercase()) {
                        "PING" -> writer.println("PONG")
                        "TAP" -> {
                            val x = parts.getOrNull(2)?.toIntOrNull()
                            val y = parts.getOrNull(3)?.toIntOrNull()
                            if (x != null && y != null) {
                                val ok = runCatching {
                                    ProcessBuilder("/system/bin/input", "tap", x.toString(), y.toString())
                                        .redirectErrorStream(true)
                                        .start()
                                        .waitFor() == 0
                                }.getOrDefault(false)
                                writer.println(if (ok) "OK" else "ERR")
                            } else {
                                writer.println("ERR")
                            }
                        }
                        "STOP" -> {
                            writer.println("BYE")
                            running = false
                            break
                        }
                        else -> writer.println("UNKNOWN")
                    }
                }
            } catch (_: Throwable) {
                // Ignora erro transiente de cliente e continua aceitando
            } finally {
                runCatching { client?.close() }
            }
        }
        runCatching { server.close() }
    }
}
