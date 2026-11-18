package com.ruan.uberareafilter.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ruan.uberareafilter.R
import com.ruan.uberareafilter.adb.AdbPairingReceiver
import com.ruan.uberareafilter.adb.StandaloneAdbBridge
import com.ruan.uberareafilter.setup.SetupChecks
import com.ruan.uberareafilter.setup.SetupChecks.A11y
import com.ruan.uberareafilter.shizuku.ShizukuTapBridge
import com.ruan.uberareafilter.storage.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Assistente de configuração para quem nunca mexeu em "modo desenvolvedor".
 *
 * Cada passo mostra: por que existe, o estado REAL agora, o que fazer em frases curtas
 * (com o caminho do fabricante do celular) e um botão que abre exatamente a tela certa.
 * Ao voltar de qualquer tela do Android a lista se atualiza sozinha (a cada 2,5 s enquanto aberta).
 *
 * Depois do pareamento do motor interno, o app ganha WRITE_SECURE_SETTINGS e passa a fazer
 * sozinho o que antes exigia navegar nas Configurações (ligar a Acessibilidade, ligar a
 * Depuração por Wi-Fi depois de reiniciar).
 */
class SetupWizardActivity : AppCompatActivity() {

    private enum class Kind { REQUIRED, RECOMMENDED, OPTIONAL }

    private class Step(
        val key: String,
        val title: String,
        val kind: Kind,
        val done: Boolean,
        val doneText: String,
        val body: (LinearLayout) -> Unit,
        val doneExtra: ((LinearLayout) -> Unit)? = null
    )

    private lateinit var repository: ConfigRepository
    private lateinit var stepsBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var txtProgress: TextView
    private lateinit var txtSubtitle: TextView
    private lateinit var btnDone: MaterialButton

    private var last: SetupChecks.Snapshot? = null
    private var refreshing = false
    private var focusUberPending = false
    private val guide = SetupChecks.brandGuide()
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2500L)
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh(force = true) }

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (fine && !background) askBackgroundLocation() else refresh(force = true)
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (!ok) SetupChecks.open(this, SetupChecks.notificationIntents(this))
        refresh(force = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        repository = ConfigRepository(this)
        val mode = if (repository.load().darkThemeEnabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)

        stepsBox = findViewById(R.id.wizardSteps)
        scroll = findViewById(R.id.wizardScroll)
        progress = findViewById(R.id.progressWizard)
        txtProgress = findViewById(R.id.txtWizardProgress)
        txtSubtitle = findViewById(R.id.txtWizardSubtitle)
        btnDone = findViewById(R.id.btnWizardDone)

        focusUberPending = intent.getBooleanExtra(EXTRA_FOCUS_UBER, false)
        txtSubtitle.text = "Celular: ${guide.brand} · Android ${Build.VERSION.RELEASE}. " +
            "Siga de cima para baixo. Ao voltar de cada tela do Android, esta lista se atualiza sozinha."

        findViewById<MaterialButton>(R.id.btnWizardBack).setOnClickListener { finish() }
        btnDone.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun refresh(force: Boolean = false) {
        if (refreshing) return
        refreshing = true
        val cfg = repository.load()
        lifecycleScope.launch(Dispatchers.IO) {
            val snap = runCatching { SetupChecks.snapshot(this@SetupWizardActivity, cfg) }.getOrNull()
            withContext(Dispatchers.Main) {
                refreshing = false
                if (snap != null && (force || snap != last)) {
                    last = snap
                    render(snap)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Montagem dos passos
    // ------------------------------------------------------------------

    private fun render(s: SetupChecks.Snapshot) {
        val steps = buildSteps(s)
        stepsBox.removeAllViews()
        var number = 0
        var uberCard: View? = null
        steps.forEach { step ->
            number++
            val card = stepCard(number, step)
            stepsBox.addView(card)
            if (step.key == "uber") uberCard = card
        }

        val counted = steps.filter { it.kind != Kind.OPTIONAL }
        val doneCount = counted.count { it.done }
        progress.max = counted.size.coerceAtLeast(1)
        progress.setProgressCompat(doneCount, true)
        txtProgress.text = when {
            s.pendingRequired > 0 -> "$doneCount de ${counted.size} prontos · falta${if (s.pendingRequired > 1) "m" else ""} " +
                "${s.pendingRequired} passo${if (s.pendingRequired > 1) "s" else ""} obrigatório${if (s.pendingRequired > 1) "s" else ""}"
            doneCount < counted.size -> "$doneCount de ${counted.size} prontos · o filtro já funciona; os outros evitam surpresas"
            else -> "Tudo pronto ✓"
        }
        btnDone.text = getString(if (s.pendingRequired == 0) R.string.wizard_done_ready else R.string.wizard_done_later)

        if (focusUberPending) {
            focusUberPending = false
            uberCard?.let { target -> scroll.post { scroll.smoothScrollTo(0, (target.top - dp(8)).coerceAtLeast(0)) } }
        }
    }

    private fun buildSteps(s: SetupChecks.Snapshot): List<Step> {
        val steps = mutableListOf<Step>()
        val label = getString(R.string.accessibility_service_label)

        // 1. Acessibilidade -----------------------------------------------------------
        steps += Step(
            key = "a11y",
            title = "Ligar o leitor de corridas",
            kind = Kind.REQUIRED,
            done = s.a11y == A11y.RUNNING,
            doneText = "Funcionando: o filtro já lê as ofertas da 99 e da Uber.",
            body = { box -> if (s.a11y == A11y.ON_NOT_RUNNING) a11yStoppedBody(box, s, label) else a11yOffBody(box, s, label) }
        )

        // 2. Notificações -------------------------------------------------------------
        steps += Step(
            key = "notif",
            title = "Permitir notificações",
            kind = Kind.RECOMMENDED,
            done = s.notificationsOk,
            doneText = "Liberadas.",
            body = { box ->
                box.addView(para("O **código de pareamento** da Uber e os avisos de passageiro favorito chegam por notificação."))
                box.addView(primary("Permitir notificações") { requestNotifications() })
            }
        )

        // 3. Bateria ------------------------------------------------------------------
        steps += Step(
            key = "battery",
            title = "Bateria sem restrição",
            kind = Kind.RECOMMENDED,
            done = s.batteryUnrestricted,
            doneText = "O Android não vai fechar o filtro para economizar bateria.",
            body = { box ->
                box.addView(para("Sem isso, o Android pode **desligar o filtro no meio do turno** para economizar bateria."))
                box.addView(numbered(listOf(
                    "Toque em **Liberar bateria**.",
                    "Na janela que abrir, toque em **Permitir**."
                )))
                box.addView(primary("Liberar bateria") { SetupChecks.open(this, SetupChecks.batteryIntents(this)) })
                guide.batteryExtra?.let { extra ->
                    box.addView(note(extra))
                    box.addView(secondary("Abrir dados do app") { SetupChecks.open(this, SetupChecks.appDetailsIntents(this)) })
                }
            },
            doneExtra = guide.batteryExtra?.let { extra -> { box: LinearLayout -> box.addView(caption(extra)) } }
        )

        // 4. Toque automático na Uber -------------------------------------------------
        if (s.moduleUber) {
            val ready = s.uberTapReady && !s.lastTapRefused
            steps += Step(
                key = "uber",
                title = "Toque automático na Uber",
                kind = if (s.uberTapRequired) Kind.REQUIRED else Kind.RECOMMENDED,
                done = ready,
                doneText = when {
                    s.daemonAlive -> "Motor interno ligado. Pode desligar o Wi-Fi: ele continua no 4G/5G."
                    else -> "Usando o Shizuku."
                },
                body = { box -> uberBody(box, s) },
                doneExtra = { box -> uberDoneExtra(box, s) }
            )
        }

        // 5. Localização (opcional) ---------------------------------------------------
        steps += Step(
            key = "location",
            title = "Localização (opcional)",
            kind = Kind.OPTIONAL,
            done = s.locationOk,
            doneText = "Liberada: ajuda a confirmar embarques sem número.",
            body = { box ->
                box.addView(para("Ajuda o filtro a **confirmar embarques sem número** quando você está dentro da sua área. O app não liga o GPS: só lê a última posição que a Uber e a 99 já calcularam."))
                box.addView(secondary("Permitir localização") { requestLocation() })
                box.addView(caption("Na segunda tela, escolha \"Permitir o tempo todo\"."))
            }
        )

        // 6. Prévia do card (opcional) ------------------------------------------------
        steps += Step(
            key = "overlay",
            title = "Prévia do card no painel (opcional)",
            kind = Kind.OPTIONAL,
            done = s.overlayOk,
            doneText = "Liberada.",
            body = { box ->
                box.addView(para("Só serve para o botão **Testar card na tela** do painel. Os cards em cima da 99 e da Uber **não precisam** disto."))
                box.addView(secondary("Permitir sobrepor") { SetupChecks.open(this, SetupChecks.overlayIntents(this)) })
            }
        )
        return steps
    }

    private fun a11yStoppedBody(box: LinearLayout, s: SetupChecks.Snapshot, label: String) {
        box.addView(para("A chave está ligada, mas o Android **parou o serviço**. Acontece depois de atualizar o app ou quando a economia de bateria fecha apps."))
        if (s.writeSecureSettings) {
            box.addView(primary("Religar automaticamente") { restartAccessibility() })
        }
        box.addView(numbered(listOf(
            "Toque em **Abrir Acessibilidade** e entre em **$label**.",
            "**Desligue** a chave e **ligue de novo**.",
            "Se voltar a parar, faça o passo da **bateria** logo abaixo."
        )))
        box.addView(
            if (s.writeSecureSettings) secondary("Abrir Acessibilidade") { openAccessibility() }
            else primary("Abrir Acessibilidade") { openAccessibility() }
        )
    }

    private fun a11yOffBody(box: LinearLayout, s: SetupChecks.Snapshot, label: String) {
        box.addView(para("É o que deixa o filtro **ler as ofertas** da 99 e da Uber. Sem isso, nada funciona."))
        if (s.writeSecureSettings) {
            box.addView(primary("Ligar automaticamente") { enableAccessibility() })
            box.addView(caption("Disponível porque você já pareou o motor interno. Ou faça à mão:"))
        }
        if (s.restrictedLikely) box.addView(restrictedSettingsNote(highlight = true))
        box.addView(numbered(listOf(
            "Toque em **Abrir Acessibilidade**.",
            "Procure **$label**. Fica em \"Apps instalados\", \"Apps baixados\" ou \"Serviços instalados\".",
            "Ligue a chave e toque em **Permitir**.",
            "Volte para cá."
        )))
        box.addView(
            if (s.writeSecureSettings) secondary("Abrir Acessibilidade") { openAccessibility() }
            else primary("Abrir Acessibilidade") { openAccessibility() }
        )
        if (!s.restrictedLikely && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !s.writeSecureSettings) {
            box.addView(restrictedSettingsNote(highlight = false))
        }
    }

    private fun uberBody(box: LinearLayout, s: SetupChecks.Snapshot) {
        if (s.daemonAlive && s.lastTapRefused) {
            box.addView(para("O motor está ligado, mas o Android **recusou o último toque** na Uber."))
            box.addView(note(guide.tapExtra ?: "Abra as Opções do desenvolvedor e procure uma opção de segurança para depuração ou toques (algumas marcas exigem ligar). Depois reinicie o motor."))
            box.addView(secondary("Abrir Opções do desenvolvedor") {
                SetupChecks.open(this, SetupChecks.developerOptionsIntents(highlightWireless = false))
            })
            return
        }

        box.addView(para("A Uber **ignora o toque comum** da Acessibilidade. Para aceitar ou recusar sozinho, o filtro usa um **motor interno** do Android, ligado pela Depuração por Wi-Fi. Você configura **uma vez só** (uns 3 minutos)."))

        // a) Modo desenvolvedor
        box.addView(checkRow(s.devOptions, "Modo desenvolvedor ligado"))
        if (!s.devOptions) {
            box.addView(numbered(listOf(
                "Toque em **Abrir Sobre o telefone** (${guide.aboutPath}).",
                "Toque **7 vezes seguidas** em **${guide.buildLabel}**. Se pedir, digite a senha do celular.",
                "Vai aparecer \"Você agora é um desenvolvedor\". Volte para cá."
            )))
            box.addView(primary("Abrir Sobre o telefone") { SetupChecks.open(this, SetupChecks.aboutPhoneIntents()) })
            box.addView(caption("Nada muda no seu celular: só aparece um menu novo (${guide.devOptionsPath})."))
        }

        // b) Wi-Fi
        box.addView(checkRow(s.wifi, "Conectado a um Wi-Fi"))
        if (!s.wifi && !s.daemonAlive) {
            box.addView(para("Conecte em qualquer Wi-Fi (o de casa serve). Só é preciso para ligar; depois o motor funciona no 4G/5G."))
            box.addView(secondary("Abrir Wi-Fi") { SetupChecks.open(this, SetupChecks.wifiIntents()) })
        }

        // c) Pareamento
        box.addView(checkRow(s.adbPaired, "Pareado com o celular (uma vez só)"))
        if (s.devOptions && !s.adbPaired) {
            if (!s.notificationsOk) {
                box.addView(note("Primeiro libere as **notificações** (passo 2): o código de 6 números é digitado pela notificação."))
            }
            box.addView(numbered(listOf(
                "Toque em **Parear agora**. Chega uma notificação do Filtro e abrem as **Opções do desenvolvedor**.",
                "Ligue **Depuração por Wi-Fi** (em alguns celulares: \"Depuração sem fio\") e toque em **Permitir**. Marque \"Sempre permitir nesta rede\".",
                "Toque no **nome** \"Depuração por Wi-Fi\" para entrar e depois em **Parear dispositivo com código de pareamento**.",
                "Aparece um código de 6 números. **Sem fechar essa janela**, puxe a barra de notificações, toque em **Digitar código** na notificação do Filtro e digite os 6 números.",
                "Pronto: o motor liga sozinho e este passo fica verde."
            )))
            box.addView(primary("Parear agora") { pairNow(s) })
            box.addView(secondary("Digitar o código aqui (tela dividida)") { manualCodeDialog() })
        }

        // d) Motor
        box.addView(checkRow(s.daemonAlive, "Motor ligado"))
        if (s.adbPaired && !s.daemonAlive) {
            if (s.writeSecureSettings) {
                box.addView(para("Toque em **Ligar motor**: o app liga a Depuração por Wi-Fi e o motor sozinho" +
                    (if (s.wifi) "." else " (conecte num Wi-Fi antes).")))
                box.addView(primary("Ligar motor") { startEngine(auto = true) })
            } else {
                box.addView(numbered(listOf(
                    "Num Wi-Fi, ligue a **Depuração por Wi-Fi** (Opções do desenvolvedor ou o atalho na barra rápida).",
                    "Volte para cá: o app conecta sozinho em alguns segundos."
                )))
                box.addView(primary("Abrir Depuração por Wi-Fi") { startEngine(auto = false) })
                box.addView(secondary("Conectar agora") { connectNow() })
            }
        }

        guide.tapExtra?.let { box.addView(note(it)) }
        shizukuNote(box, s)
    }

    private fun uberDoneExtra(box: LinearLayout, s: SetupChecks.Snapshot) {
        if (!s.daemonAlive) return
        if (s.writeSecureSettings) {
            val sw = MaterialSwitch(this).apply {
                text = "Religar o motor sozinho depois de reiniciar o celular (quando estiver num Wi-Fi)"
                isChecked = SetupChecks.isAutoRestartEnabled(this@SetupWizardActivity)
                minHeight = dp(48)
                setOnCheckedChangeListener { _, checked ->
                    SetupChecks.setAutoRestartEnabled(this@SetupWizardActivity, checked)
                }
            }
            box.addView(sw)
        } else {
            box.addView(caption("Para o motor religar sozinho depois de reiniciar o celular, libere os atalhos uma vez (precisa da Depuração por Wi-Fi ligada, num Wi-Fi)."))
            box.addView(secondary("Liberar atalhos") { grantShortcuts() })
        }
    }

    private fun shizukuNote(box: LinearLayout, s: SetupChecks.Snapshot) {
        if (s.shizuku == ShizukuTapBridge.State.NOT_INSTALLED) return
        box.addView(note("Já usa o **Shizuku**? Ele também serve para tocar na Uber. Agora: ${ShizukuTapBridge.statusLabel(this)}."))
        val action = when (s.shizuku) {
            ShizukuTapBridge.State.NO_PERMISSION -> "Autorizar no Shizuku"
            ShizukuTapBridge.State.NOT_RUNNING, ShizukuTapBridge.State.DENIED -> "Abrir o Shizuku"
            else -> null
        } ?: return
        box.addView(secondary(action) {
            if (s.shizuku == ShizukuTapBridge.State.NO_PERMISSION) {
                ShizukuTapBridge.requestPermission()
            } else {
                packageManager.getLaunchIntentForPackage(ShizukuTapBridge.SHIZUKU_PACKAGE)?.let { startActivity(it) }
                if (s.shizuku == ShizukuTapBridge.State.DENIED) {
                    toast("No Shizuku, abra \"Apps autorizados\" e ligue o Filtro.")
                }
            }
        })
    }

    private fun restrictedSettingsNote(highlight: Boolean): View {
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(para(
            if (highlight) "**Vai aparecer \"Configuração restrita\".** O Android bloqueia apps instalados fora da Play Store. Libere uma vez:"
            else "**Apareceu \"Configuração restrita\"?** O Android bloqueia apps instalados fora da Play Store. Libere uma vez:"
        ))
        inner.addView(numbered(listOf(
            "Tente ligar a chave uma vez e toque em **OK** no aviso (sem esse aviso o Android não mostra a opção).",
            "Toque em **Abrir dados do app**.",
            "Toque nos **⋮** (três pontinhos, no canto de cima) e em **Permitir configurações restritas**. Confirme com a senha ou digital.",
            "Volte para a Acessibilidade e ligue a chave."
        )))
        inner.addView(secondary("Abrir dados do app") { SetupChecks.open(this, SetupChecks.appDetailsIntents(this)) })
        return boxed(inner)
    }

    // ------------------------------------------------------------------
    // Ações
    // ------------------------------------------------------------------

    private fun openAccessibility() {
        SetupChecks.open(this, SetupChecks.accessibilityIntents(this))
    }

    private fun enableAccessibility() {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = SetupChecks.enableOwnAccessibilityService(this@SetupWizardActivity)
            delay(1500L)
            withContext(Dispatchers.Main) {
                if (ok && SetupChecks.a11yState(this@SetupWizardActivity) == A11y.RUNNING) {
                    toast("Leitor de corridas ligado ✅")
                } else if (ok) {
                    toast("Ligado. Se não ficar verde em alguns segundos, use \"Abrir Acessibilidade\".")
                } else {
                    toast("Não deu para ligar sozinho. Use \"Abrir Acessibilidade\".")
                }
                refresh(force = true)
            }
        }
    }

    private fun restartAccessibility() {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = SetupChecks.restartOwnAccessibilityService(this@SetupWizardActivity)
            delay(1500L)
            withContext(Dispatchers.Main) {
                toast(if (ok) "Serviço religado." else "Não deu para religar sozinho. Use \"Abrir Acessibilidade\".")
                refresh(force = true)
            }
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SetupChecks.open(this, SetupChecks.notificationIntents(this))
        }
    }

    private fun requestLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            askBackgroundLocation()
        }
    }

    private fun askBackgroundLocation() {
        AlertDialog.Builder(this)
            .setTitle("Último passo")
            .setMessage("Na próxima tela, escolha \"Permitir o tempo todo\". O filtro trabalha com a Uber e a 99 abertas por cima dele.")
            .setPositiveButton("Abrir") { _, _ ->
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton("Agora não", null)
            .show()
    }

    private fun pairNow(s: SetupChecks.Snapshot) {
        if (!s.notificationsOk) {
            requestNotifications()
            toast("Libere as notificações e toque em Parear agora de novo.")
            return
        }
        if (!s.wifi) {
            toast("Conecte num Wi-Fi primeiro.")
            SetupChecks.open(this, SetupChecks.wifiIntents())
            return
        }
        StandaloneAdbBridge.startPairingDiscovery(this)
        AdbPairingReceiver.showPairingPromptNotification(this)
        SetupChecks.open(this, SetupChecks.developerOptionsIntents(highlightWireless = true))
        toast("Ligue \"Depuração por Wi-Fi\", entre nela e toque em \"Parear dispositivo com código\".")
    }

    private fun manualCodeDialog() {
        StandaloneAdbBridge.startPairingDiscovery(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val edtCode = EditText(this).apply {
            hint = "Código de 6 números"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val edtPort = EditText(this).apply {
            hint = "Porta (deixe vazio: o app acha sozinho)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        container.addView(caption("Deixe a tela do código aberta em cima (tela dividida) e digite aqui embaixo."))
        container.addView(edtCode)
        container.addView(edtPort)
        AlertDialog.Builder(this)
            .setTitle("Digitar código de pareamento")
            .setView(container)
            .setPositiveButton("Parear") { _, _ ->
                val code = edtCode.text.toString().trim()
                val port = edtPort.text.toString().trim().toIntOrNull()
                lifecycleScope.launch(Dispatchers.IO) {
                    val res = StandaloneAdbBridge.pairAndStart(this@SetupWizardActivity, code, port)
                    withContext(Dispatchers.Main) {
                        toast(res.detail)
                        refresh(force = true)
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** auto = o app liga a Depuração por Wi-Fi sozinho (precisa dos atalhos liberados). */
    private fun startEngine(auto: Boolean) {
        val appCtx = applicationContext
        if (!auto) {
            StandaloneAdbBridge.startConnectAutoMonitor(appCtx) { res ->
                runOnUiThread {
                    if (res.outcome == StandaloneAdbBridge.ConnectOutcome.CONNECTED) toast(res.detail)
                    refresh(force = true)
                }
            }
            SetupChecks.open(this, SetupChecks.developerOptionsIntents(highlightWireless = true))
            return
        }
        toast("Ligando o motor...")
        lifecycleScope.launch(Dispatchers.IO) {
            val enabled = SetupChecks.enableWirelessDebugging(appCtx, restoreAfter = true)
            if (!enabled) {
                withContext(Dispatchers.Main) {
                    toast("Não deu para ligar a Depuração por Wi-Fi sozinho. Ligue à mão e volte.")
                    SetupChecks.open(this@SetupWizardActivity, SetupChecks.developerOptionsIntents(highlightWireless = true))
                }
                return@launch
            }
            var res = StandaloneAdbBridge.ConnectResult(StandaloneAdbBridge.ConnectOutcome.FAILED, "")
            for (attempt in 0 until 4) {
                delay(1800L)
                res = StandaloneAdbBridge.connectAndStartDaemon(appCtx)
                if (res.outcome == StandaloneAdbBridge.ConnectOutcome.CONNECTED ||
                    res.outcome == StandaloneAdbBridge.ConnectOutcome.PAIRING_REQUIRED
                ) break
            }
            if (res.outcome == StandaloneAdbBridge.ConnectOutcome.CONNECTED) {
                SetupChecks.restoreWirelessDebuggingIfWeEnabled(appCtx)
            }
            withContext(Dispatchers.Main) {
                toast(
                    if (res.outcome == StandaloneAdbBridge.ConnectOutcome.CONNECTED) "Motor ligado ✅"
                    else res.detail.ifBlank { "Não conectou. Se o Android perguntou, toque em Permitir e tente de novo." }
                )
                refresh(force = true)
            }
        }
    }

    private fun connectNow() {
        lifecycleScope.launch(Dispatchers.IO) {
            val res = StandaloneAdbBridge.connectAndStartDaemon(applicationContext)
            withContext(Dispatchers.Main) {
                toast(res.detail)
                refresh(force = true)
            }
        }
    }

    private fun grantShortcuts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val res = StandaloneAdbBridge.grantSetupPermissions(applicationContext)
            withContext(Dispatchers.Main) {
                toast(res.detail)
                if (res.outcome == StandaloneAdbBridge.ConnectOutcome.WIFI_DEBUG_OFF) {
                    SetupChecks.open(this@SetupWizardActivity, SetupChecks.developerOptionsIntents(highlightWireless = true))
                }
                refresh(force = true)
            }
        }
    }

    // ------------------------------------------------------------------
    // Peças visuais
    // ------------------------------------------------------------------

    private fun stepCard(number: Int, step: Step): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(if (!step.done && step.kind == Kind.REQUIRED) R.color.fi_primary else R.color.fi_outline_variant)
            setCardBackgroundColor(color(if (step.done) R.color.fi_surface_container_low else R.color.fi_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = TextView(this).apply {
            text = if (step.done) "✓" else number.toString()
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(if (step.done) R.color.fi_on_primary else R.color.fi_on_surface))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (step.done) setColor(color(R.color.fi_primary)) else {
                    setColor(color(R.color.fi_surface_container_high))
                    setStroke(dp(1), color(R.color.fi_outline_variant))
                }
            }
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val title = TextView(this).apply {
            text = step.title
            setTextAppearance(R.style.TextAppearance_Filtro_ItemTitle)
            isAccessibilityHeading = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tag = TextView(this).apply {
            text = when {
                step.done -> "Pronto"
                step.kind == Kind.REQUIRED -> "Obrigatório"
                step.kind == Kind.RECOMMENDED -> "Recomendado"
                else -> "Opcional"
            }
            textSize = 11f
            letterSpacing = 0.04f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val fg = when {
                step.done -> R.color.fi_primary
                step.kind == Kind.REQUIRED -> R.color.fi_error
                step.kind == Kind.RECOMMENDED -> R.color.fi_warning
                else -> R.color.fi_on_surface_variant
            }
            setTextColor(color(fg))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(99).toFloat()
                setStroke(dp(1), color(fg))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        header.addView(badge)
        header.addView(title)
        header.addView(tag)
        inner.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(42), dp(6), 0, 0)
        }
        if (step.done) {
            content.addView(caption(step.doneText))
            step.doneExtra?.invoke(content)
        } else {
            step.body(content)
        }
        inner.addView(content)
        card.addView(inner)
        return card
    }

    private fun para(text: String): TextView = TextView(this).apply {
        this.text = rich(text)
        setTextAppearance(R.style.TextAppearance_Filtro_Body)
        setTextColor(color(R.color.fi_on_surface))
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = rich(text)
        setTextAppearance(R.style.TextAppearance_Filtro_Caption)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(2), 0, dp(4))
    }

    /** Passos numerados com recuo (o número fica na margem, o texto alinha embaixo dele). */
    private fun numbered(items: List<String>): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }
        items.forEachIndexed { i, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(TextView(this).apply {
                text = "${i + 1}."
                setTextAppearance(R.style.TextAppearance_Filtro_Body)
                setTextColor(color(R.color.fi_primary))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(dp(22), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = rich(item)
                setTextAppearance(R.style.TextAppearance_Filtro_Body)
                setTextColor(color(R.color.fi_on_surface))
                setLineSpacing(dp(2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            col.addView(row)
        }
        return col
    }

    private fun checkRow(ok: Boolean, text: String): TextView = TextView(this).apply {
        val sb = SpannableStringBuilder()
        sb.append(if (ok) "✓  " else "○  ")
        sb.setSpan(ForegroundColorSpan(color(if (ok) R.color.fi_primary else R.color.fi_on_surface_variant)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(text)
        this.text = sb
        setTextAppearance(R.style.TextAppearance_Filtro_Body)
        setTextColor(color(if (ok) R.color.fi_on_surface_variant else R.color.fi_on_surface))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun note(text: String): View = boxed(para(text))

    private fun boxed(child: View): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(color(R.color.fi_surface_container_high))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(8) }
            addView(child)
        }
    }

    private fun primary(text: String, onClick: () -> Unit): MaterialButton = MaterialButton(this).apply {
        this.text = text
        minHeight = dp(48)
        layoutParams = buttonParams()
        setOnClickListener { onClick() }
    }

    private fun secondary(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            minHeight = dp(48)
            layoutParams = buttonParams()
            setOnClickListener { onClick() }
        }

    private fun buttonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(2); bottomMargin = dp(4) }

    /** "**negrito**" vira negrito; o resto fica como está. */
    private fun rich(text: String): CharSequence {
        val sb = SpannableStringBuilder()
        val parts = text.split("**")
        parts.forEachIndexed { i, part ->
            val start = sb.length
            sb.append(part)
            if (i % 2 == 1 && part.isNotEmpty()) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_FOCUS_UBER = "extra_focus_uber"

        fun open(context: Context, focusUber: Boolean = false) {
            context.startActivity(
                Intent(context, SetupWizardActivity::class.java).putExtra(EXTRA_FOCUS_UBER, focusUber)
            )
        }
    }
}
