package com.ruan.uberareafilter.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gerencia o canal ADB-over-Wi-Fi embutido diretamente no APK (sem precisar de app Shizuku externo).
 * Pareia via Depuracao por Wi-Fi (SPAKE2/TLS), inicia `StandaloneTapDaemon` como `shell` (UID 2000)
 * e mantem toques rapidos via LocalSocket mesmo quando o motorista vai para a rua no 4G/5G.
 */
object StandaloneAdbBridge {

    enum class ConnectOutcome {
        CONNECTED,
        PAIRING_REQUIRED,
        WIFI_DEBUG_OFF,
        FAILED
    }

    data class ConnectResult(
        val outcome: ConnectOutcome,
        val detail: String
    )

    data class SetupEnvironmentStatus(
        val wifiConnected: Boolean,
        val devOptionsEnabled: Boolean,
        val wirelessDebugPort: Int?,
        val isPaired: Boolean,
        val isDaemonAlive: Boolean
    )

    private const val KEY_FILE = "standalone_adb_rsa.pk8"
    private const val CERT_FILE = "standalone_adb_cert.der"
    private const val PAIRED_FLAG_FILE = "standalone_adb_paired.flag"
    private const val TOKEN_FILE = "standalone_adb_token.txt"

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var managerInstance: LocalAdbConnectionManager? = null
    @Volatile
    private var pairingMdns: AdbMdns? = null
    @Volatile
    private var discoveredPairingHost: String? = null
    @Volatile
    private var discoveredPairingPort: Int = -1
    @Volatile
    private var pairingPortLatch: CountDownLatch? = null

    @Volatile
    private var connectMdns: AdbMdns? = null
    @Volatile
    private var discoveredConnectPort: Int = -1
    @Volatile
    private var autoStartCallback: ((ConnectResult) -> Unit)? = null
    private val autoConnectInFlight = AtomicBoolean(false)

    private val tapLock = Any()
    private val connectLock = Any()

    private class LocalAdbConnectionManager(
        private val privKey: PrivateKey,
        private val cert: Certificate
    ) : AbsAdbConnectionManager() {
        init {
            setApi(Build.VERSION.SDK_INT)
            setThrowOnUnauthorised(true)
            setTimeout(5, TimeUnit.SECONDS)
        }

        override fun getPrivateKey(): PrivateKey = privKey
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "FiltroInteligente"
    }

    private fun getOrCreateManager(context: Context): LocalAdbConnectionManager {
        managerInstance?.let { return it }
        synchronized(this) {
            managerInstance?.let { return it }
            val dir = context.applicationContext.filesDir
            val keyFile = File(dir, KEY_FILE)
            val certFile = File(dir, CERT_FILE)

            val (privKey, cert) = if (keyFile.exists() && certFile.exists()) {
                runCatching { loadKeyAndCert(keyFile, certFile) }.getOrNull()
                    ?: generateAndSaveKeyAndCert(keyFile, certFile)
            } else {
                generateAndSaveKeyAndCert(keyFile, certFile)
            }
            return LocalAdbConnectionManager(privKey, cert).also { managerInstance = it }
        }
    }

    private fun loadKeyAndCert(keyFile: File, certFile: File): Pair<PrivateKey, Certificate> {
        val keyBytes = keyFile.readBytes()
        val certBytes = certFile.readBytes()
        val privKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certBytes))
        return privKey to cert
    }

    private fun generateAndSaveKeyAndCert(keyFile: File, certFile: File): Pair<PrivateKey, Certificate> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        val cert = buildSelfSignedX509Certificate(kp)
        keyFile.writeBytes(kp.private.encoded)
        certFile.writeBytes(cert.encoded)
        return kp.private to cert
    }

    /**
     * Gera um certificado X.509 v3 autoassinado (SHA256withRSA) em ASN.1 DER puro
     * usando apenas a JCA padrao do Android, evitando conflitos com o bootclasspath BouncyCastle.
     */
    internal fun buildSelfSignedX509Certificate(kp: java.security.KeyPair): Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86_400_000L)
        val notAfter = Date(now + 10L * 365L * 86_400_000L)
        val utcFmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val version = derTlv(0xA0, byteArrayOf(0x02, 0x01, 0x02))
        val serialBytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        serialBytes[0] = ((serialBytes[0].toInt() and 0x7F) or 0x01).toByte()
        val serial = derTlv(0x02, serialBytes)

        // OID 1.2.840.113549.1.1.11 (sha256WithRSAEncryption) + NULL
        val sha256RsaAlgId = derSeq(
            byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B),
            byteArrayOf(0x05, 0x00)
        )

        // CN=FiltroInteligente -> OID 2.5.4.3
        val cnBytes = "FiltroInteligente".toByteArray(StandardCharsets.UTF_8)
        val name = derSeq(
            derTlv(
                0x31,
                derSeq(
                    byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03),
                    derTlv(0x0C, cnBytes)
                )
            )
        )

        val validity = derSeq(
            derTlv(0x17, utcFmt.format(notBefore).toByteArray(StandardCharsets.US_ASCII)),
            derTlv(0x17, utcFmt.format(notAfter).toByteArray(StandardCharsets.US_ASCII))
        )

        val tbsCertificate = derSeq(
            version,
            serial,
            sha256RsaAlgId,
            name,
            validity,
            name,
            kp.public.encoded
        )

        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(kp.private)
            update(tbsCertificate)
        }.sign()

        val bitStringSig = derTlv(0x03, byteArrayOf(0x00) + sig)
        val certDer = derSeq(tbsCertificate, sha256RsaAlgId, bitStringSig)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer))
    }

    private fun derSeq(vararg items: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        for (item in items) body.write(item)
        return derTlv(0x30, body.toByteArray())
    }

    private fun derTlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len <= 0xFF -> {
                out.write(0x81)
                out.write(len)
            }
            else -> {
                out.write(0x82)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun isPaired(context: Context): Boolean =
        File(context.applicationContext.filesDir, PAIRED_FLAG_FILE).exists()

    fun clearPairing(context: Context) {
        setPaired(context, false)
        discoveredConnectPort = -1
        discoveredPairingPort = -1
    }

    fun isWifiConnected(context: Context): Boolean = runCatching {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    fun isDeveloperOptionsEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(
            context.applicationContext.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        ) == 1
    }.getOrDefault(false)

    fun getSetupEnvironmentStatus(context: Context): SetupEnvironmentStatus {
        return SetupEnvironmentStatus(
            wifiConnected = isWifiConnected(context),
            devOptionsEnabled = isDeveloperOptionsEnabled(context),
            wirelessDebugPort = discoveredConnectPort.takeIf { it > 0 },
            isPaired = isPaired(context),
            isDaemonAlive = isDaemonAlive(context)
        )
    }

    fun isConnectAutoMonitorRunning(): Boolean = connectMdns != null

    /**
     * Observa `_adb-tls-connect._tcp` via mDNS enquanto estiver no Wi-Fi:
     * assim que o motorista já pareado ligar 'Depuração por Wi-Fi', conecta e inicia
     * o daemon automaticamente sem pedir código nem clique adicional.
     */
    fun startConnectAutoMonitor(
        context: Context,
        onAutoConnected: ((ConnectResult) -> Unit)? = null
    ) {
        val appCtx = context.applicationContext
        autoStartCallback = onAutoConnected
        if (connectMdns != null) return

        val mdns = AdbMdns(appCtx, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { _: InetAddress?, port: Int ->
            if (port > 0) {
                discoveredConnectPort = port
                if (isPaired(appCtx) &&
                    isWifiConnected(appCtx) &&
                    !isDaemonAlive(appCtx) &&
                    autoConnectInFlight.compareAndSet(false, true)
                ) {
                    Thread({
                        try {
                            val res = connectAndStartDaemon(appCtx)
                            if (res.outcome == ConnectOutcome.CONNECTED) {
                                stopConnectAutoMonitor()
                            }
                            autoStartCallback?.invoke(res)
                        } finally {
                            autoConnectInFlight.set(false)
                        }
                    }, "StandaloneAdbAutoConnect").start()
                }
            }
        }
        connectMdns = mdns
        runCatching { mdns.start() }
    }

    fun stopConnectAutoMonitor() {
        autoStartCallback = null
        runCatching { connectMdns?.stop() }
        connectMdns = null
    }

    private fun setPaired(context: Context, paired: Boolean) {
        val flag = File(context.applicationContext.filesDir, PAIRED_FLAG_FILE)
        if (paired) {
            runCatching { flag.writeText("1") }
        } else {
            runCatching { flag.delete() }
        }
    }

    private fun getOrCreateToken(context: Context): String {
        cachedToken?.let { return it }
        synchronized(this) {
            cachedToken?.let { return it }
            val tokenFile = File(context.applicationContext.filesDir, TOKEN_FILE)
            val existing = runCatching { tokenFile.readText().trim() }.getOrNull()
            if (!existing.isNullOrBlank() && existing.length >= 16) {
                cachedToken = existing
                return existing
            }
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val generated = bytes.joinToString("") { "%02x".format(it) }
            runCatching { tokenFile.writeText(generated) }
            cachedToken = generated
            return generated
        }
    }

    fun isDaemonAlive(context: Context): Boolean {
        return sendSocketCommand(context, "PING") == "PONG"
    }

    /**
     * Inicia a descoberta mDNS em segundo plano da porta de pareamento (`_adb-tls-pairing._tcp`).
     */
    fun startPairingDiscovery(context: Context) {
        stopPairingDiscovery()
        discoveredPairingPort = -1
        discoveredPairingHost = null
        pairingPortLatch = CountDownLatch(1)
        val appCtx = context.applicationContext
        val mdns = AdbMdns(appCtx, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { addr: InetAddress?, port: Int ->
            if (port > 0) {
                discoveredPairingHost = addr?.hostAddress ?: "127.0.0.1"
                discoveredPairingPort = port
                pairingPortLatch?.countDown()
            }
        }
        pairingMdns = mdns
        runCatching { mdns.start() }
    }

    fun stopPairingDiscovery() {
        runCatching { pairingMdns?.stop() }
        pairingMdns = null
    }

    fun currentDiscoveredPairingEndpoint(): Pair<String, Int>? {
        val p = discoveredPairingPort
        return if (p > 0) (discoveredPairingHost ?: "127.0.0.1") to p else null
    }

    /**
     * Executa o pareamento SPAKE2/TLS usando o codigo de 6 digitos e, em seguida,
     * conecta ao `adbd` local para iniciar o daemon `shell`.
     */
    fun pairAndStart(context: Context, pairingCode: String, manualPort: Int? = null): ConnectResult {
        val cleanCode = pairingCode.filter { it.isDigit() }
        if (cleanCode.length != 6) {
            return ConnectResult(ConnectOutcome.FAILED, "Digite os 6 dígitos do código de pareamento.")
        }

        var port = manualPort?.takeIf { it in 1..65535 } ?: discoveredPairingPort
        var host = discoveredPairingHost ?: "127.0.0.1"
        if (port <= 0) {
            if (pairingMdns == null) {
                startPairingDiscovery(context)
            }
            runCatching { pairingPortLatch?.await(3500L, TimeUnit.MILLISECONDS) }
            port = discoveredPairingPort
            host = discoveredPairingHost ?: "127.0.0.1"
        }

        if (port <= 0) {
            return ConnectResult(
                ConnectOutcome.WIFI_DEBUG_OFF,
                "Porta de pareamento não encontrada. Mantenha a janela 'Parear dispositivo com código' aberta no Wi-Fi."
            )
        }

        val mgr = getOrCreateManager(context)
        val paired = try {
            mgr.pair(host, port, cleanCode)
        } catch (e: Throwable) {
            return ConnectResult(
                ConnectOutcome.FAILED,
                "Falha no pareamento (porta $port): ${e.message ?: e.javaClass.simpleName}"
            )
        }

        if (!paired) {
            return ConnectResult(ConnectOutcome.FAILED, "Código rejeitado pelo Android (verifique os 6 dígitos).")
        }

        setPaired(context, true)
        stopPairingDiscovery()
        return connectAndStartDaemon(context)
    }

    /**
     * Conecta automaticamente ao `adbd` local (Depuracao por Wi-Fi ligada) e inicia o daemon `shell`.
     */
    fun connectAndStartDaemon(context: Context): ConnectResult {
        synchronized(connectLock) {
            if (isDaemonAlive(context)) {
                stopConnectAutoMonitor()
                return ConnectResult(ConnectOutcome.CONNECTED, "Daemon independente já está ativo.")
            }

            val appCtx = context.applicationContext
            val mgr = getOrCreateManager(appCtx)
            val connected = try {
                if (mgr.isConnected) true else mgr.autoConnect(appCtx, 4500L)
            } catch (_: AdbPairingRequiredException) {
                setPaired(appCtx, false)
                return ConnectResult(
                    ConnectOutcome.PAIRING_REQUIRED,
                    "Pareamento necessário (use 'Parear dispositivo com código' nas Opções do Desenvolvedor)."
                )
            } catch (e: Throwable) {
                return ConnectResult(
                    ConnectOutcome.WIFI_DEBUG_OFF,
                    "Depuração por Wi-Fi não encontrada. Ative Wi-Fi e 'Depuração por Wi-Fi' para iniciar."
                )
            }

            if (!connected) {
                return ConnectResult(
                    ConnectOutcome.WIFI_DEBUG_OFF,
                    "Não conectou à Depuração por Wi-Fi. Verifique se está ligada em Opções do Desenvolvedor."
                )
            }

            setPaired(appCtx, true)

            val launched = launchShellDaemon(appCtx, mgr)
            runCatching { mgr.disconnect() }

            if (!launched) {
                return ConnectResult(ConnectOutcome.FAILED, "Conectou ao ADB, mas não conseguiu iniciar o processo shell.")
            }

            repeat(14) {
                if (isDaemonAlive(appCtx)) {
                    stopConnectAutoMonitor()
                    return ConnectResult(
                        ConnectOutcome.CONNECTED,
                        "Modo Independente ATIVO ✅ (pode desligar o Wi-Fi e rodar no 4G/5G)"
                    )
                }
                Thread.sleep(220L)
            }

            return ConnectResult(ConnectOutcome.FAILED, "Daemon shell iniciou mas não respondeu ao PING na porta local.")
        }
    }

    private fun launchShellDaemon(context: Context, mgr: AbsAdbConnectionManager): Boolean {
        return try {
            val token = getOrCreateToken(context)
            val port = StandaloneTapDaemon.DEFAULT_PORT
            val fallbackApk = context.packageCodePath
            val pkgName = context.packageName
            val cmd = buildString {
                append("pkill -f ${StandaloneTapDaemon::class.java.name} >/dev/null 2>&1; ")
                append("APK=$(pm path $pkgName | head -n 1 | cut -d: -f2 | tr -d '\\r'); ")
                append("if [ -z \"\$APK\" ]; then APK=\"$fallbackApk\"; fi; ")
                append("CLASSPATH=\"\$APK\" setsid /system/bin/app_process /system/bin ")
                append("${StandaloneTapDaemon::class.java.name} $port $token ")
                append("</dev/null >/dev/null 2>&1 & ")
                append("sleep 0.45; exit\n")
            }
            val stream = mgr.openStream("shell:")
            stream.openOutputStream().use { out ->
                out.write(cmd.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            runCatching { stream.close() }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Dispara `input tap X Y` pelo daemon independente em execucao como `shell` (UID 2000).
     */
    fun tryTap(context: Context, x: Float, y: Float): Boolean {
        val resp = sendSocketCommand(context, "TAP ${x.toInt()} ${y.toInt()}")
        return resp == "OK"
    }

    fun stopDaemon(context: Context) {
        sendSocketCommand(context, "STOP")
    }

    private fun sendSocketCommand(context: Context, command: String): String? {
        val token = getOrCreateToken(context)
        synchronized(tapLock) {
            var socket: Socket? = null
            return try {
                socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", StandaloneTapDaemon.DEFAULT_PORT), 800)
                socket.soTimeout = 2000
                val writer = PrintWriter(OutputStreamWriter(socket.outputStream), true)
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                writer.println("$token $command")
                reader.readLine()?.trim()
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    fun statusLabel(context: Context): String = when {
        isDaemonAlive(context) -> "ATIVO ✅ (100% independente no 4G/5G)"
        isPaired(context) -> "Pareado (ligue Depuração Wi-Fi e toque para iniciar)"
        else -> "Não pareado (toque abaixo p/ ativar sem Shizuku)"
    }
}
