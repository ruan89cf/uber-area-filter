package com.ruan.uberareafilter.setup

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.ruan.uberareafilter.adb.StandaloneAdbBridge
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.service.DriverLocationProvider
import com.ruan.uberareafilter.service.UberAccessibilityService
import com.ruan.uberareafilter.shizuku.ShizukuTapBridge

/**
 * Fonte unica da verdade sobre "o que esta ligado" no celular.
 *
 * Antes cada tela checava do seu jeito, e havia tres erros de status:
 *  - Acessibilidade "OK" mesmo quando o Android tinha parado o servico (ligada, mas sem rodar);
 *  - "Sobrepor tela" cobrado como obrigatorio, mas os cards em cima da 99/Uber sao desenhados
 *    pela propria Acessibilidade (so a previa do painel usa essa permissao);
 *  - Shizuku "Ativo" com a permissao dada, mas o servico de toque ainda desconectado.
 *
 * [snapshot] faz um PING no motor interno (socket local), entao deve rodar FORA da thread principal.
 */
object SetupChecks {

    enum class A11y { OFF, ON_NOT_RUNNING, RUNNING }

    enum class Level { OK, WARN, BAD, OFF }

    data class Line(val level: Level, val text: String)

    data class Snapshot(
        val a11y: A11y,
        val restrictedLikely: Boolean,
        val notificationsOk: Boolean,
        val batteryUnrestricted: Boolean,
        val overlayOk: Boolean,
        val locationOk: Boolean,
        val devOptions: Boolean,
        val wifi: Boolean,
        val wirelessDebugOn: Boolean,
        val adbPaired: Boolean,
        val daemonAlive: Boolean,
        val lastTapRefused: Boolean,
        val writeSecureSettings: Boolean,
        val shizuku: ShizukuTapBridge.State,
        val filterEnabled: Boolean,
        val module99: Boolean,
        val moduleUber: Boolean,
        val uberAutoTaps: Boolean
    ) {
        /** A Uber ignora o toque comum da Acessibilidade: precisa do motor interno ou do Shizuku. */
        val uberTapReady: Boolean get() = daemonAlive || shizuku == ShizukuTapBridge.State.ACTIVE

        /** So cobra o toque da Uber de quem usa a Uber com aceite ou recusa automatica. */
        val uberTapRequired: Boolean get() = moduleUber && uberAutoTaps

        val pendingRequired: Int
            get() {
                var n = 0
                if (a11y != A11y.RUNNING) n++
                if (uberTapRequired && !uberTapReady) n++
                return n
            }

        val pendingRecommended: Int
            get() {
                var n = 0
                if (!notificationsOk) n++
                if (!batteryUnrestricted) n++
                return n
            }
    }

    // ------------------------------------------------------------------
    // Leitura do estado
    // ------------------------------------------------------------------

    /** Chamar fora da thread principal (faz PING no motor interno). */
    fun snapshot(context: Context, cfg: FilterConfig): Snapshot {
        val app = context.applicationContext
        val wss = hasWriteSecureSettings(app)
        return Snapshot(
            a11y = a11yState(app),
            restrictedLikely = restrictedSettingsLikely(app),
            notificationsOk = notificationsOk(app),
            batteryUnrestricted = batteryUnrestricted(app),
            overlayOk = Settings.canDrawOverlays(app),
            locationOk = DriverLocationProvider.hasPermission(app),
            devOptions = StandaloneAdbBridge.isDeveloperOptionsEnabled(app),
            wifi = StandaloneAdbBridge.isWifiConnected(app),
            wirelessDebugOn = isWirelessDebuggingOn(app),
            adbPaired = StandaloneAdbBridge.isPaired(app),
            daemonAlive = StandaloneAdbBridge.isDaemonAlive(app),
            lastTapRefused = StandaloneAdbBridge.lastTapRefused,
            writeSecureSettings = wss,
            shizuku = ShizukuTapBridge.state(app),
            filterEnabled = cfg.isEnabled,
            module99 = cfg.nineNineModuleEnabled,
            moduleUber = cfg.uberModuleEnabled,
            uberAutoTaps = cfg.autoAcceptInsideArea || cfg.autoRejectEnabled
        )
    }

    fun serviceComponent(context: Context): ComponentName =
        ComponentName(context.packageName, UberAccessibilityService::class.java.name)

    /** O Filtro aparece ligado em Configuracoes > Acessibilidade? */
    fun isListedInAccessibility(context: Context): Boolean {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull() ?: return false
        val me = serviceComponent(context)
        return raw.split(':').any { ComponentName.unflattenFromString(it.trim()) == me }
    }

    /** Diferencia "desligada", "ligada mas parada pelo Android" e "funcionando". */
    fun a11yState(context: Context): A11y = when {
        UberAccessibilityService.running -> A11y.RUNNING
        isListedInAccessibility(context) -> A11y.ON_NOT_RUNNING
        else -> A11y.OFF
    }

    /**
     * Android 13+ bloqueia a Acessibilidade de apps instalados por arquivo (WhatsApp, Drive, navegador).
     * Instalacao pelo PC (USB) nao tem esse bloqueio, e o pareamento do motor interno ja libera sozinho.
     */
    fun restrictedSettingsLikely(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (hasWriteSecureSettings(context)) return false
        return runCatching {
            val src = context.packageManager.getInstallSourceInfo(context.packageName).packageSource
            src == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || src == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
        }.getOrDefault(false)
    }

    fun notificationsOk(context: Context): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(true)

    fun batteryUnrestricted(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /** Liberada pelo pareamento do motor interno (pm grant), uma unica vez. */
    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isWirelessDebuggingOn(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // Acoes que dispensam o motorista de navegar nas Configuracoes
    // (so funcionam depois do pareamento, que concede WRITE_SECURE_SETTINGS)
    // ------------------------------------------------------------------

    /** Liga a Acessibilidade do Filtro mantendo os outros servicos que ja estavam ligados. */
    fun enableOwnAccessibilityService(context: Context): Boolean {
        if (!hasWriteSecureSettings(context)) return false
        return runCatching {
            val cr = context.contentResolver
            val me = serviceComponent(context)
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val others = current.split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() && ComponentName.unflattenFromString(it) != me }
            Settings.Secure.putString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (others + me.flattenToString()).joinToString(":")
            )
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }

    /** Desliga e liga de novo (resolve o "ligado, mas nao esta funcionando"). Chamar fora da thread principal. */
    fun restartOwnAccessibilityService(context: Context): Boolean {
        if (!hasWriteSecureSettings(context)) return false
        val removed = runCatching {
            val cr = context.contentResolver
            val me = serviceComponent(context)
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val others = current.split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() && ComponentName.unflattenFromString(it) != me }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            true
        }.getOrDefault(false)
        if (!removed) return false
        Thread.sleep(700L)
        return enableOwnAccessibilityService(context)
    }

    /**
     * Liga a Depuracao por Wi-Fi sozinho (precisa estar num Wi-Fi).
     * @param restoreAfter desligar de novo quando o motor subir (ver [restoreWirelessDebuggingIfWeEnabled])
     */
    fun enableWirelessDebugging(context: Context, restoreAfter: Boolean = false): Boolean {
        if (!hasWriteSecureSettings(context)) return false
        if (!StandaloneAdbBridge.isWifiConnected(context)) return false
        if (isWirelessDebuggingOn(context)) return true
        val ok = runCatching {
            Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
            true
        }.getOrDefault(false)
        if (ok && restoreAfter) weEnabledWirelessDebugging = true
        return ok
    }

    fun disableWirelessDebugging(context: Context) {
        if (!hasWriteSecureSettings(context)) return
        runCatching { Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 0) }
    }

    // ---- Religar o motor sozinho depois de reiniciar o celular ----

    private const val PREFS = "setup_prefs"
    private const val KEY_AUTO_RESTART = "auto_restart_engine"
    private const val KEY_WIZARD_SHOWN = "wizard_auto_shown_v1"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    @Volatile
    private var lastAutoEnableAt = 0L

    @Volatile
    var weEnabledWirelessDebugging = false
        private set

    fun isAutoRestartEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_RESTART, true)

    fun setAutoRestartEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_RESTART, enabled).apply()
    }

    fun shouldAutoOpenWizard(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WIZARD_SHOWN, false)

    fun markWizardAutoOpened(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WIZARD_SHOWN, true).apply()
    }

    /**
     * Depois de reiniciar, o motor interno morre e a Depuracao por Wi-Fi volta desligada.
     * Se o motorista ja pareou (WRITE_SECURE_SETTINGS) e esta num Wi-Fi, o proprio app liga a depuracao;
     * o monitor mDNS conecta e sobe o motor. No maximo uma tentativa a cada 10 minutos.
     */
    fun maybeAutoEnableWirelessDebugging(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAutoEnableAt < 10 * 60_000L) return false
        if (!isAutoRestartEnabled(context)) return false
        if (!StandaloneAdbBridge.isPaired(context)) return false
        if (!StandaloneAdbBridge.isDeveloperOptionsEnabled(context)) return false
        if (isWirelessDebuggingOn(context)) return false
        lastAutoEnableAt = now
        return enableWirelessDebugging(context, restoreAfter = true)
    }

    /** Depois que o motor subiu, devolve a Depuracao por Wi-Fi ao estado anterior (desligada). */
    fun restoreWirelessDebuggingIfWeEnabled(context: Context) {
        if (!weEnabledWirelessDebugging) return
        weEnabledWirelessDebugging = false
        disableWirelessDebugging(context)
    }

    // ------------------------------------------------------------------
    // Resumo para o painel
    // ------------------------------------------------------------------

    /** O que o botao "Resolver" de um item da verificacao abre. */
    enum class Fix { TURN_ON, ACCESSIBILITY, RESTRICTED, UBER_TAP, BATTERY, NOTIFICATIONS, OVERLAY, LOCATION, MAP }

    /** Item da verificacao do app: titulo curto, o que esta acontecendo e (se faltar) como resolver. */
    data class Check(
        val level: Level,
        val title: String,
        val detail: String,
        val fix: Fix? = null,
        val fixLabel: String = "Resolver"
    )

    /**
     * Lista completa para o cartao "Verificacao do app". Ordem: o que impede de funcionar primeiro.
     * [hasArea] = existe zona no mapa ou lista de ruas (vem da config, fora do snapshot).
     */
    fun healthChecks(s: Snapshot, hasArea: Boolean): List<Check> {
        val out = mutableListOf<Check>()
        out += when {
            !s.filterEnabled -> Check(
                Level.OFF, "Leitura das corridas",
                "Desligada por você. Nada é lido até você ligar de novo.",
                Fix.TURN_ON, "Ligar"
            )
            s.a11y == A11y.RUNNING -> Check(
                Level.OK, "Leitura das corridas",
                "Funcionando: o app lê cada oferta da 99 e da Uber."
            )
            s.a11y == A11y.ON_NOT_RUNNING -> Check(
                Level.BAD, "Leitura das corridas",
                "Falta isso: o Android parou o leitor. Desligue e ligue o “Filtro de Área” na Acessibilidade.",
                Fix.ACCESSIBILITY
            )
            s.restrictedLikely -> Check(
                Level.BAD, "Leitura das corridas",
                "Falta isso: o Android bloqueou a Acessibilidade deste app. Em “Informações do app”, toque nos 3 pontinhos e em “Permitir configurações restritas”.",
                Fix.RESTRICTED
            )
            else -> Check(
                Level.BAD, "Leitura das corridas",
                "Falta isso: ligar o “Filtro de Área” na Acessibilidade. Sem isso o app não vê as corridas.",
                Fix.ACCESSIBILITY
            )
        }
        out += if (hasArea) {
            Check(Level.OK, "Sua área no mapa", "Desenhada. O app sabe onde você quer rodar.")
        } else {
            Check(
                Level.WARN, "Sua área no mapa",
                "Falta isso: desenhar no mapa onde você quer trabalhar. Sem área, o app só usa os filtros de valor e distância.",
                Fix.MAP, "Desenhar"
            )
        }
        if (s.moduleUber) {
            out += when {
                s.daemonAlive && s.lastTapRefused -> Check(
                    Level.WARN, "Toque automático na Uber",
                    "Ligado, mas o Android recusou o último toque. Abra para religar o motor.",
                    Fix.UBER_TAP
                )
                s.uberTapReady -> Check(Level.OK, "Toque automático na Uber", "Liberado: o app consegue tocar nos botões da Uber.")
                s.uberTapRequired -> Check(
                    Level.WARN, "Toque automático na Uber",
                    if (s.adbPaired) "Falta isso: conectar em qualquer Wi-Fi por uns 10 segundos (serve até o roteador de outro celular, sem internet). O app liga o motor sozinho e depois funciona no 4G/5G até o celular reiniciar. Enquanto isso, na Uber o app só mostra o card."
                    else "Falta isso: liberar o toque na Uber (configuração feita uma vez só). Enquanto isso, na Uber o app só mostra o card.",
                    Fix.UBER_TAP, "Liberar"
                )
                else -> Check(Level.OFF, "Toque automático na Uber", "Não é preciso: aceite e recusa automáticos estão desligados.")
            }
        }
        out += if (s.batteryUnrestricted) {
            Check(Level.OK, "Bateria", "Sem restrição: o Android não fecha o filtro no meio do turno.")
        } else {
            Check(
                Level.WARN, "Bateria",
                "Falta isso: deixar o app “Sem restrição” de bateria. Senão o Android pode fechar o filtro depois de um tempo.",
                Fix.BATTERY, "Liberar"
            )
        }
        out += if (s.locationOk) {
            Check(Level.OK, "Localização", "Liberada: ajuda a saber se você já está dentro da área.")
        } else {
            Check(
                Level.WARN, "Localização",
                "Recomendado: liberar a localização. Com ela o app acerta mais quando a rua do passageiro fica na divisa.",
                Fix.LOCATION, "Liberar"
            )
        }
        out += if (s.overlayOk) {
            Check(Level.OK, "Aparecer sobre outros apps", "Liberado: o botão OFF e a prévia do card funcionam.")
        } else {
            Check(
                Level.WARN, "Aparecer sobre outros apps",
                "Recomendado: liberar. É o que mantém o botão OFF na tela e mostra a prévia do card.",
                Fix.OVERLAY, "Liberar"
            )
        }
        out += if (s.notificationsOk) {
            Check(Level.OK, "Notificações", "Liberadas: avisos de passageiros fixos chegam na hora.")
        } else {
            Check(
                Level.WARN, "Notificações",
                "Recomendado: liberar para receber os avisos de horário dos passageiros fixos.",
                Fix.NOTIFICATIONS, "Liberar"
            )
        }
        return out
    }

    fun summaryLines(s: Snapshot): List<Line> {
        val lines = mutableListOf<Line>()
        lines += when (s.a11y) {
            A11y.RUNNING -> Line(Level.OK, "Leitor de corridas: funcionando")
            A11y.ON_NOT_RUNNING -> Line(Level.BAD, "Leitor de corridas: ligado, mas parado pelo Android")
            A11y.OFF -> Line(Level.BAD, "Leitor de corridas: desligado (Acessibilidade)")
        }
        if (s.moduleUber) {
            lines += when {
                s.daemonAlive && s.lastTapRefused ->
                    Line(Level.WARN, "Toque na Uber: motor ligado, mas o Android recusou o último toque")
                s.daemonAlive -> Line(Level.OK, "Toque na Uber: motor interno ligado")
                s.shizuku == ShizukuTapBridge.State.ACTIVE -> Line(Level.OK, "Toque na Uber: Shizuku ligado")
                s.adbPaired -> Line(
                    if (s.uberTapRequired) Level.WARN else Level.OFF,
                    "Toque na Uber: falta ligar o motor (precisa de Wi-Fi)"
                )
                s.uberTapRequired -> Line(Level.WARN, "Toque na Uber: não configurado (só mostra o aviso)")
                else -> Line(Level.OFF, "Toque na Uber: desligado (aceite e recusa automáticos estão desligados)")
            }
        }
        lines += if (s.batteryUnrestricted) {
            Line(Level.OK, "Bateria: sem restrição")
        } else {
            Line(Level.WARN, "Bateria: o Android pode desligar o filtro")
        }
        if (!s.notificationsOk) lines += Line(Level.WARN, "Notificações: bloqueadas")
        return lines
    }

    // ------------------------------------------------------------------
    // Telas do Android (cada uma com plano B se o fabricante mudou o caminho)
    // ------------------------------------------------------------------

    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"

    fun accessibilityIntents(context: Context): List<Intent> = listOf(
        // A chave extra so destaca o Filtro na lista (Pixel/Motorola); os outros ignoram
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, serviceComponent(context).flattenToString()),
        Intent(Settings.ACTION_SETTINGS)
    )

    fun appDetailsIntents(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
    )

    fun notificationIntents(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    )

    @SuppressLint("BatteryLife")
    fun batteryIntents(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    )

    fun overlayIntents(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    )

    fun aboutPhoneIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).putExtra(EXTRA_FRAGMENT_ARG_KEY, "build_number"),
        Intent(Settings.ACTION_SETTINGS)
    )

    /** Abre as Opcoes do desenvolvedor ja com "Depuracao por Wi-Fi" destacada (quando o Android aceita). */
    fun developerOptionsIntents(highlightWireless: Boolean): List<Intent> = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            if (highlightWireless) putExtra(EXTRA_FRAGMENT_ARG_KEY, "toggle_adb_wireless")
        },
        Intent(Settings.ACTION_SETTINGS)
    )

    fun wifiIntents(): List<Intent> = listOf(
        Intent(Settings.Panel.ACTION_WIFI),
        Intent(Settings.ACTION_WIFI_SETTINGS)
    )

    /** Tenta cada tela em ordem; devolve false se nenhuma abriu. */
    fun open(activity: Activity, intents: List<Intent>): Boolean {
        for (intent in intents) {
            val ok = runCatching { activity.startActivity(intent) }.isSuccess
            if (ok) return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // Caminhos por fabricante (o nome das telas muda de marca para marca)
    // ------------------------------------------------------------------

    data class BrandGuide(
        val brand: String,
        val aboutPath: String,
        val buildLabel: String,
        val devOptionsPath: String,
        val batteryExtra: String?,
        val tapExtra: String?
    )

    fun brandGuide(): BrandGuide {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        return when {
            m.contains("samsung") -> BrandGuide(
                brand = "Samsung",
                aboutPath = "Configurações > Sobre o telefone > Informações do software",
                buildLabel = "Número de compilação",
                devOptionsPath = "Configurações > Opções do desenvolvedor (no fim da lista)",
                batteryExtra = "Na Samsung, confira também: Configurações > Bateria > Limites de uso em segundo plano, " +
                    "e tire o Filtro de \"Apps em suspensão\".",
                tapExtra = null
            )
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> BrandGuide(
                brand = "Xiaomi / Redmi / POCO",
                aboutPath = "Configurações > Sobre o telefone",
                buildLabel = "Versão do HyperOS (ou Versão da MIUI)",
                devOptionsPath = "Configurações > Configurações adicionais > Opções do desenvolvedor",
                batteryExtra = "Na Xiaomi, confira também: Configurações > Apps > Gerenciar apps > Filtro > " +
                    "Economia de bateria > Sem restrições, e ligue \"Inicialização automática\".",
                tapExtra = "Na Xiaomi, ligue também \"Depuração USB (configurações de segurança)\" nas Opções do " +
                    "desenvolvedor. Sem ela o Android recusa os toques automáticos."
            )
            m.contains("motorola") || m.contains("lenovo") -> BrandGuide(
                brand = "Motorola",
                aboutPath = "Configurações > Sobre o telefone",
                buildLabel = "Número da versão (último item da tela)",
                devOptionsPath = "Configurações > Sistema > Opções do desenvolvedor",
                batteryExtra = null,
                tapExtra = null
            )
            m.contains("google") -> BrandGuide(
                brand = "Google Pixel",
                aboutPath = "Configurações > Sobre o telefone",
                buildLabel = "Número da versão (último item da tela)",
                devOptionsPath = "Configurações > Sistema > Opções do desenvolvedor",
                batteryExtra = null,
                tapExtra = null
            )
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> BrandGuide(
                brand = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
                aboutPath = "Configurações > Sobre o dispositivo > Versão",
                buildLabel = "Número da versão",
                devOptionsPath = "Configurações > Configurações adicionais > Opções do desenvolvedor",
                batteryExtra = "Confira também: Configurações > Bateria > permita a atividade em segundo plano do Filtro.",
                tapExtra = "Se os toques não funcionarem, ligue também \"Desativar monitoramento de permissões\" " +
                    "nas Opções do desenvolvedor."
            )
            else -> BrandGuide(
                brand = (Build.MANUFACTURER ?: "Android").replaceFirstChar { it.uppercase() },
                aboutPath = "Configurações > Sobre o telefone (às vezes dentro de \"Informações do software\")",
                buildLabel = "Número da versão (ou \"Número de compilação\")",
                devOptionsPath = "Configurações > Sistema > Opções do desenvolvedor",
                batteryExtra = null,
                tapExtra = null
            )
        }
    }
}
