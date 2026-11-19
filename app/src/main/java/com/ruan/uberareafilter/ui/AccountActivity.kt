package com.ruan.uberareafilter.ui

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ruan.uberareafilter.BuildConfig
import com.ruan.uberareafilter.account.AccountGate
import com.ruan.uberareafilter.account.AccountState
import com.ruan.uberareafilter.account.AccountStore
import com.ruan.uberareafilter.account.BillingManager
import com.ruan.uberareafilter.account.ServerConfig
import com.ruan.uberareafilter.account.SyncManager
import com.ruan.uberareafilter.account.UpdateManager
import com.ruan.uberareafilter.ui.AccountUi.Kind
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Conta: entrar (e-mail e senha, Google ou codigo), criar conta (nome, sobrenome, nascimento, genero),
 * confirmar e-mail, esqueci a senha, plano, cupom, pagamento e atualizacao. Sem licenca o app fica aqui.
 */
class AccountActivity : AppCompatActivity() {

    companion object {
        const val SCREEN_PLANS = "plans"
        const val SCREEN_UPDATE = "update"
        const val SCREEN_REGISTER = "register"

        /** Abre a tela da conta. `locked` = o app nao pode seguir sem resolver (login/plano/atualizacao). */
        fun open(context: Context, locked: Boolean, screen: String? = null) {
            context.startActivity(
                Intent(context, AccountActivity::class.java)
                    .putExtra("locked", locked)
                    .putExtra("screen", screen)
                    .addFlags(if (locked) Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK else 0)
            )
        }
    }

    private enum class Screen { LOGIN, REGISTER, VERIFY, FORGOT, RESET, CODE, PROFILE, ACCOUNT, PLANS, UPDATE }

    private lateinit var ui: AccountUi
    private lateinit var root: LinearLayout
    private var billing: BillingManager? = null
    private var busy = false
    private var screen = Screen.LOGIN
    private val history = ArrayDeque<Screen>()

    // dados que passam de uma tela para a outra
    private var pendingEmail = ""
    private var pendingEmailSent = true
    private var draft = Draft()
    private var downloading = false

    private data class Draft(
        var firstName: String = "", var lastName: String = "", var birthDate: String = "", var gender: String = "",
        var email: String = ""
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Primeira vez no app: apresentacao com os cards antes do login
        if (!AccountGate.hasSession(this) && !OnboardingActivity.seen(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        ui = AccountUi(this)
        root = ui.column().apply { setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(28)) }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ui.pageBg)
            isFillViewport = true
            addView(root)
        })
        billing = BillingManager(this, lifecycleScope, ::toast) { render() }
        screen = startScreen()
        render()
    }

    private fun startScreen(): Screen {
        val st = AccountGate.state(this)
        if (st?.mustUpdate == true) return Screen.UPDATE
        if (!AccountGate.hasSession(this)) {
            // Cadastro direto (veio do ultimo card da apresentacao); voltar leva ao "Entrar"
            return if (intent.getStringExtra("screen") == SCREEN_REGISTER) { history.addLast(Screen.LOGIN); Screen.REGISTER } else Screen.LOGIN
        }
        if (st != null && !st.profileComplete) return Screen.PROFILE
        return when (intent.getStringExtra("screen")) {
            SCREEN_PLANS -> Screen.PLANS
            SCREEN_REGISTER -> Screen.REGISTER
            SCREEN_UPDATE -> Screen.UPDATE
            else -> Screen.ACCOUNT
        }
    }

    override fun onResume() {
        super.onResume()
        if (AccountGate.hasSession(this) && screen in setOf(Screen.ACCOUNT, Screen.PLANS, Screen.UPDATE)) {
            lifecycleScope.launch {
                AccountGate.refresh(this@AccountActivity)
                if (AccountGate.state(this@AccountActivity)?.mustUpdate == true) screen = Screen.UPDATE
                if (!AccountGate.hasSession(this@AccountActivity)) screen = Screen.LOGIN
                render()
            }
        }
    }

    override fun onDestroy() {
        billing?.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (history.isNotEmpty()) {
            screen = history.removeLast()
            render()
            return
        }
        // Travado: voltar fecha o app em vez de abrir as telas sem licenca
        if (!AccountGate.isLicensed(this) || AccountGate.state(this)?.mustUpdate == true) finishAffinity() else super.onBackPressed()
    }

    private fun go(next: Screen) {
        history.addLast(screen)
        screen = next
        render()
    }

    private fun reset(to: Screen) {
        history.clear()
        screen = to
        render()
    }

    // ------------------------------------------------------------------ telas
    private fun render() {
        root.removeAllViews()
        when (screen) {
            Screen.LOGIN -> renderLogin()
            Screen.REGISTER -> renderRegister()
            Screen.VERIFY -> renderVerify()
            Screen.FORGOT -> renderForgot()
            Screen.RESET -> renderReset()
            Screen.CODE -> renderCode()
            Screen.PROFILE -> renderProfile()
            Screen.ACCOUNT -> renderAccount()
            Screen.PLANS -> renderPlans()
            Screen.UPDATE -> renderUpdate(mandatory = AccountGate.state(this)?.mustUpdate == true)
        }
        // Rodape: versao instalada (ajuda o suporte)
        root.addView(ui.text("Filtro Inteligente ${BuildConfig.VERSION_NAME}", 11f, ui.muted, center = true).apply { setPadding(0, ui.dp(24), 0, 0) })
    }

    private fun renderLogin() {
        root.addView(ui.brandHeader("Entre na sua conta para filtrar suas corridas."))
        AccountGate.state(this)?.message?.takeIf { it.isNotBlank() }?.let { root.addView(ui.text(it, 14f, ui.red, center = true)) }
        val email = ui.input("E-mail", Kind.EMAIL, draft.email)
        val pass = ui.input("Senha", Kind.PASSWORD)
        root.addView(email)
        root.addView(pass)
        root.addView(ui.button("Entrar") {
            val e = email.text.toString().trim()
            val p = pass.text.toString()
            if (e.isEmpty() || p.isEmpty()) return@button toast("Digite e-mail e senha")
            draft.email = e
            act({ AccountGate.loginWithPassword(this, e, p) }) { r ->
                if (r.code == "EMAIL_NOT_VERIFIED") {
                    pendingEmail = r.email.ifBlank { e }
                    pendingEmailSent = r.emailSent
                    go(Screen.VERIFY)
                    true
                } else false
            }
        })
        root.addView(ui.link("Esqueci a senha") { draft.email = email.text.toString().trim(); go(Screen.FORGOT) })
        root.addView(ui.orDivider())
        root.addView(ui.button("Criar conta grátis", outline = true) { draft.email = email.text.toString().trim(); go(Screen.REGISTER) })
        if (ServerConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
            root.addView(ui.button("Continuar com Google", color = ui.blue) { googleLogin() })
        }
        root.addView(ui.link("Tenho um código de acesso") { go(Screen.CODE) })
    }

    /** Campos do perfil (usados no cadastro e no "complete seu cadastro"). */
    private inner class ProfileFields(initial: Draft) {
        val first: AccountUi.Field = ui.input("Nome", Kind.NAME, initial.firstName)
        val last: AccountUi.Field = ui.input("Sobrenome", Kind.NAME, initial.lastName)
        var birth = initial.birthDate
        val birthButton = ui.button(birthLabel(), outline = true) {}
        val genderGroup = RadioGroup(this@AccountActivity).apply { orientation = RadioGroup.VERTICAL }
        private val genders = listOf("masculino" to "Masculino", "feminino" to "Feminino", "outro" to "Outro", "nao_informar" to "Prefiro não dizer")

        init {
            birthButton.setOnClickListener { pickBirth() }
            genders.forEach { (key, label) ->
                genderGroup.addView(RadioButton(this@AccountActivity).apply {
                    id = View.generateViewId()
                    text = label
                    tag = key
                    setTextColor(ui.fg)
                })
            }
            genders.indexOfFirst { it.first == initial.gender }.takeIf { it >= 0 }?.let {
                genderGroup.check(genderGroup.getChildAt(it).id)
            }
        }

        private fun birthLabel() = if (birth.isBlank()) "Data de nascimento" else "Nascimento: " +
            birth.split("-").let { "${it[2]}/${it[1]}/${it[0]}" }

        private fun pickBirth() {
            val c = Calendar.getInstance()
            val parts = birth.split("-").mapNotNull { it.toIntOrNull() }
            val (y, m, d) = if (parts.size == 3) Triple(parts[0], parts[1] - 1, parts[2]) else Triple(c.get(Calendar.YEAR) - 30, 0, 1)
            DatePickerDialog(this@AccountActivity, { _, yy, mm, dd ->
                birth = "%04d-%02d-%02d".format(yy, mm + 1, dd)
                birthButton.text = birthLabel()
            }, y, m, d).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
        }

        fun addTo(parent: LinearLayout) {
            parent.addView(first)
            parent.addView(last)
            parent.addView(birthButton)
            parent.addView(ui.text("Gênero", 14f, ui.muted).apply { setPadding(0, ui.dp(12), 0, 0) })
            parent.addView(genderGroup)
        }

        fun gender(): String =
            genderGroup.findViewById<RadioButton>(genderGroup.checkedRadioButtonId)?.tag as? String ?: ""

        /** Confere aqui (resposta rapida); o servidor confere de novo. */
        fun read(): AccountGate.Profile? {
            val p = AccountGate.Profile(first.text.toString().trim(), last.text.toString().trim(), birth, gender())
            val err = when {
                p.firstName.isEmpty() -> "Digite seu nome"
                p.lastName.isEmpty() -> "Digite seu sobrenome"
                p.birthDate.isEmpty() -> "Escolha a data de nascimento"
                p.gender.isEmpty() -> "Escolha o gênero"
                else -> null
            }
            if (err != null) { toast(err); return null }
            draft = draft.copy(firstName = p.firstName, lastName = p.lastName, birthDate = p.birthDate, gender = p.gender)
            return p
        }
    }

    private fun renderRegister() {
        root.addView(ui.title("Criar conta"))
        root.addView(ui.text("Preencha seus dados. Conta nova em celular novo ganha dias grátis para testar.", 14f, ui.muted))
        val fields = ProfileFields(draft)
        fields.addTo(root)
        root.addView(ui.section("Acesso"))
        val email = ui.input("E-mail", Kind.EMAIL, draft.email)
        val pass = ui.input("Senha (8 ou mais letras/números)", Kind.PASSWORD)
        val pass2 = ui.input("Repita a senha", Kind.PASSWORD)
        root.addView(email)
        root.addView(pass)
        root.addView(pass2)
        root.addView(ui.button("Criar conta") {
            val profile = fields.read() ?: return@button
            val e = email.text.toString().trim()
            val p = pass.text.toString()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) return@button toast("E-mail inválido")
            if (p.length < 8) return@button toast("A senha precisa ter 8 caracteres ou mais")
            if (p != pass2.text.toString()) return@button toast("As senhas não são iguais")
            draft.email = e
            act({ AccountGate.register(this, e, p, profile) }) { r ->
                if (r.ok) {
                    pendingEmail = r.email.ifBlank { e }
                    pendingEmailSent = r.emailSent
                    go(Screen.VERIFY)
                }
                r.ok
            }
        })
        root.addView(ui.link("Já tenho conta") { onBackPressed() })
    }

    private fun renderVerify() {
        root.addView(ui.title("Confirme seu e-mail"))
        root.addView(ui.text("Enviamos um código de 6 números para $pendingEmail. Confira também a caixa de spam.", 15f))
        if (!pendingEmailSent) {
            root.addView(ui.text("Se o e-mail não chegar em alguns minutos, peça o código ao suporte.", 13f, ui.muted))
        }
        val code = ui.input("Código de 6 números", Kind.NUMBER)
        root.addView(code)
        root.addView(ui.button("Confirmar") {
            val c = code.text.toString().trim()
            if (c.length != 6) return@button toast("Digite os 6 números do código")
            act({ AccountGate.verifyEmail(this, pendingEmail, c) }) { false }
        })
        root.addView(ui.link("Reenviar código") {
            act({ AccountGate.resendCode(this, pendingEmail, reset = false) }) { r ->
                if (r.ok) toast("Código reenviado")
                r.ok
            }
        })
    }

    private fun renderForgot() {
        root.addView(ui.title("Esqueci a senha"))
        root.addView(ui.text("Digite o e-mail da conta. Vamos mandar um código para criar uma senha nova.", 15f))
        val email = ui.input("E-mail", Kind.EMAIL, draft.email)
        root.addView(email)
        root.addView(ui.button("Enviar código") {
            val e = email.text.toString().trim()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) return@button toast("E-mail inválido")
            draft.email = e
            act({ AccountGate.forgotPassword(this, e) }) { r ->
                if (r.ok) {
                    pendingEmail = e
                    pendingEmailSent = r.emailSent
                    go(Screen.RESET)
                }
                r.ok
            }
        })
    }

    private fun renderReset() {
        root.addView(ui.title("Nova senha"))
        root.addView(ui.text("Se houver conta com $pendingEmail, enviamos um código de 6 números.", 15f))
        if (!pendingEmailSent) root.addView(ui.text("Se o e-mail não chegar em alguns minutos, peça o código ao suporte.", 13f, ui.muted))
        val code = ui.input("Código de 6 números", Kind.NUMBER)
        val pass = ui.input("Senha nova (8 ou mais)", Kind.PASSWORD)
        val pass2 = ui.input("Repita a senha nova", Kind.PASSWORD)
        root.addView(code)
        root.addView(pass)
        root.addView(pass2)
        root.addView(ui.button("Trocar senha e entrar") {
            val c = code.text.toString().trim()
            val p = pass.text.toString()
            if (c.length != 6) return@button toast("Digite os 6 números do código")
            if (p.length < 8) return@button toast("A senha precisa ter 8 caracteres ou mais")
            if (p != pass2.text.toString()) return@button toast("As senhas não são iguais")
            act({ AccountGate.resetPassword(this, pendingEmail, c, p) }) { false }
        })
        root.addView(ui.link("Reenviar código") {
            act({ AccountGate.resendCode(this, pendingEmail, reset = true) }) { r -> if (r.ok) toast("Código reenviado"); r.ok }
        })
    }

    private fun renderCode() {
        root.addView(ui.title("Código de acesso"))
        root.addView(ui.text("Recebeu um código do suporte? Digite aqui para entrar.", 15f))
        val code = ui.input("Código recebido", Kind.CODE)
        root.addView(code)
        root.addView(ui.button("Entrar com código") {
            val c = code.text.toString().trim()
            if (c.isEmpty()) return@button toast("Digite o código")
            act({ AccountGate.loginWithCode(this, c) }) { false }
        })
    }

    private fun renderProfile() {
        val st = AccountGate.state(this)
        root.addView(ui.title("Complete seu cadastro"))
        root.addView(ui.text("Só falta isso para usar o app.", 15f, ui.muted))
        val fields = ProfileFields(Draft(st?.firstName.orEmpty(), st?.lastName.orEmpty(), st?.birthDate.orEmpty(), st?.gender.orEmpty()))
        fields.addTo(root)
        root.addView(ui.button("Salvar e continuar") {
            val p = fields.read() ?: return@button
            act({ AccountGate.updateProfile(this, p) }) { false }
        })
        root.addView(ui.link("Sair da conta") { AccountGate.logout(this); reset(Screen.LOGIN) })
    }

    private fun renderAccount() {
        val st = AccountGate.state(this)
        val licensed = AccountGate.isLicensed(this)
        val name = listOfNotNull(st?.firstName?.takeIf { it.isNotBlank() }, st?.lastName?.takeIf { it.isNotBlank() }).joinToString(" ")
        root.addView(ui.title(name.ifBlank { "Minha conta" }))
        root.addView(ui.text(st?.email.orEmpty(), 14f, ui.muted))
        root.addView(statusCard(st, licensed))
        if (st?.hasUpdate == true) root.addView(updateCard(st, mandatory = false))

        if (st?.status != "blocked") {
            if (st?.status != "active" || st.plan == "teste") {
                root.addView(ui.button("Ver planos e assinar", color = ui.blue) { go(Screen.PLANS) })
            }
            root.addView(ui.section("Tem um cupom?"))
            val coupon = ui.input("Digite o cupom", Kind.CODE)
            root.addView(coupon)
            root.addView(ui.button("Aplicar cupom", outline = true) {
                val code = coupon.text.toString().trim()
                if (code.isEmpty()) return@button toast("Digite o cupom")
                act({ AccountGate.redeemCoupon(this, code) }) { r ->
                    if (r.ok) { toast(r.state?.message ?: "Cupom aplicado"); render() }
                    r.ok
                }
            })
        }

        root.addView(ui.section("Seus dados na conta"))
        root.addView(ui.text("Zonas do mapa, ganhos, histórico e registros ficam salvos na sua conta. Trocou de celular? Ao entrar, eles voltam.", 13f, ui.muted))
        root.addView(ui.button("Salvar cópia agora", outline = true) {
            if (busy) return@button
            busy = true
            toast("Salvando…")
            lifecycleScope.launch {
                val msg = runCatching { SyncManager.syncNow(this@AccountActivity, force = true) }.getOrDefault("Sem conexão")
                busy = false
                toast(msg)
            }
        })

        root.addView(ui.section(""))
        if (licensed) root.addView(ui.button("Continuar para o app") { openMain() })
        root.addView(ui.button("Sair da conta", outline = true) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Sair da conta?")
                .setMessage("O filtro para até você entrar de novo. Suas zonas e ganhos continuam salvos na conta.")
                .setPositiveButton("Sair") { _, _ ->
                    SyncManager.syncInBackground(this)
                    AccountGate.logout(this)
                    reset(Screen.LOGIN)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        })
    }

    private fun renderPlans() {
        val st = AccountGate.state(this)
        root.addView(ui.title("Planos"))
        val monthly = st?.priceMonthly ?: 29.9
        val yearly = st?.priceYearly ?: 299.0
        val trialLeft = if (st?.status == "trial") AccountUi.daysLeft(st.until) else 0L
        if (trialLeft > 0) {
            root.addView(ui.card(ui.blue,
                ui.text("Você ainda tem $trialLeft ${if (trialLeft == 1L) "dia grátis" else "dias grátis"}", 16f, ui.blue, bold = true),
                ui.text("Assinando agora, esses dias continuam valendo: eles são somados ao fim do seu plano.", 14f)
            ))
        }
        root.addView(ui.card(ui.green,
            ui.text("Mensal", 18f, bold = true),
            ui.text("${AccountUi.money(monthly)} por mês", 16f),
            ui.text("Cancela quando quiser pela Play Store.", 13f, ui.muted),
            ui.button("Assinar mensal") { billing?.buy(BillingManager.PRODUCT_MONTHLY) }
        ))
        val perMonth = yearly / 12.0
        val save = if (monthly > 0) ((1 - perMonth / monthly) * 100).toInt() else 0
        root.addView(ui.card(ui.green,
            ui.text("Anual", 18f, bold = true),
            ui.text("${AccountUi.money(yearly)} por ano · sai ${AccountUi.money(perMonth)} por mês", 16f),
            ui.text(if (save > 0) "Economia de $save% em relação ao mensal." else "Pague uma vez por ano.", 13f, ui.muted),
            ui.button("Assinar anual") { billing?.buy(BillingManager.PRODUCT_YEARLY) }
        ))
        root.addView(ui.text("Pagamento pelo Google Play (Pix, cartão ou saldo). O valor final aparece na tela do Google antes de confirmar.", 13f, ui.muted).apply {
            setPadding(0, ui.dp(12), 0, 0)
        })
        root.addView(ui.link("Voltar") { onBackPressed() })
    }

    private fun renderUpdate(mandatory: Boolean) {
        val st = AccountGate.state(this) ?: return renderAccount()
        root.addView(ui.title(if (mandatory) "Atualize o app" else "Nova versão"))
        root.addView(updateCard(st, mandatory))
        if (mandatory) {
            root.addView(ui.text("Esta atualização é obrigatória: o filtro fica parado até atualizar. Suas zonas e configurações continuam.", 14f, ui.muted))
        } else {
            root.addView(ui.link("Agora não") { onBackPressed() })
        }
    }

    private fun statusCard(st: AccountState?, licensed: Boolean): View {
        val (title, line, color) = when (st?.status) {
            "trial" -> {
                val d = AccountUi.daysLeft(st.until)
                Triple("Teste grátis", "Faltam $d ${if (d == 1L) "dia" else "dias"} · até ${AccountUi.date(st.until)}", ui.blue)
            }
            "active" -> if (st.until == null) Triple("Acesso vitalício", "Sem data para acabar", ui.green)
            else Triple("Plano ativo", "${planName(st.plan)} até ${AccountUi.date(st.until)}", ui.green)
            "expired" -> Triple("Plano vencido", "Assine ou use um cupom para continuar", ui.red)
            "blocked" -> Triple("Acesso bloqueado", st.message ?: "Fale com o suporte", ui.red)
            else -> Triple("Sem plano", st?.message ?: "Confira sua conexão", ui.muted)
        }
        val card = ui.card(color, ui.text(title, 18f, color, bold = true), ui.text(line, 14f))
        if (!licensed && st?.status in setOf("trial", "active")) {
            card.addView(ui.text("Conectando ao servidor para liberar este celular…", 12f, ui.muted))
        }
        st?.message?.takeIf { st.status != "blocked" && it.isNotBlank() }?.let { card.addView(ui.text(it, 13f, ui.muted)) }
        return card
    }

    private fun updateCard(st: AccountState, mandatory: Boolean): View {
        val name = st.latestVersionName.ifBlank { st.latestVersionCode.takeIf { it > 0 }?.toString().orEmpty() }
        val card = ui.card(
            if (mandatory) ui.red else android.graphics.Color.parseColor("#F59E0B"),
            ui.text(if (mandatory) "Atualização obrigatória" else "Nova versão disponível", 17f, bold = true),
            ui.text(if (name.isNotBlank()) "Versão $name (você tem ${BuildConfig.VERSION_NAME})" else "Você tem a versão ${BuildConfig.VERSION_NAME}", 13f, ui.muted),
            ui.text(st.updateMessage.ifBlank { if (mandatory) "Atualize para continuar usando." else "Atualize quando puder." }, 14f)
        )
        if (st.updateIsApk || st.updateUrl.isNotBlank()) {
            val btn = ui.button("Baixar e instalar") {}
            btn.setOnClickListener { startUpdate(st, btn) }
            card.addView(btn)
        }
        return card
    }

    // ------------------------------------------------------------------ acoes
    /**
     * Roda uma acao da conta. `handle` cuida de casos especiais (true = ja tratado);
     * senao: erro vira aviso; login ok segue para perfil/app.
     */
    private fun act(action: suspend () -> AccountGate.Result, handle: (AccountGate.Result) -> Boolean) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val r = runCatching { action() }.getOrElse { AccountGate.Result(false, null, "Sem conexão com o servidor") }
            busy = false
            if (handle(r)) return@launch
            if (!r.ok) {
                toast(r.error ?: "Não deu certo, tente de novo")
                if (!AccountGate.hasSession(this@AccountActivity) && screen in setOf(Screen.ACCOUNT, Screen.PROFILE)) reset(Screen.LOGIN)
                return@launch
            }
            afterLogin(r)
        }
    }

    /** Entrou (ou atualizou o perfil): completa cadastro, traz os dados da conta e abre o app. */
    private suspend fun afterLogin(r: AccountGate.Result) {
        val st = r.state ?: AccountGate.state(this)
        if (st?.mustUpdate == true) return reset(Screen.UPDATE)
        if (st != null && !st.profileComplete) return reset(Screen.PROFILE)
        if (st?.status == "trial") AccountStore(this).welcomePending = true
        runCatching { SyncManager.restoreIfEmpty(this) }.getOrNull()?.let { toast(it) }
        if (AccountGate.isLicensed(this)) openMain() else reset(Screen.ACCOUNT)
    }

    private fun startUpdate(st: AccountState, btn: android.widget.Button) {
        if (downloading) return
        if (!st.updateIsApk) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(st.updateUrl))) }
            return
        }
        if (!UpdateManager.canInstall(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Permitir a atualização")
                .setMessage("O Android vai pedir para permitir que o Filtro Inteligente instale a versão nova. Ative a opção e volte aqui.")
                .setPositiveButton("Abrir") { _, _ -> UpdateManager.openInstallPermission(this) }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        downloading = true
        btn.text = "Baixando…"
        lifecycleScope.launch {
            val (file, err) = UpdateManager.download(this@AccountActivity) { pct -> btn.text = "Baixando… $pct%" }
            downloading = false
            if (file == null) {
                btn.text = "Baixar e instalar"
                toast(err ?: "Falha ao baixar")
                return@launch
            }
            btn.text = "Instalar"
            UpdateManager.lastSavedLocation?.let { toast("Arquivo de instalação salvo em $it (app Arquivos > Downloads)") }
            btn.setOnClickListener { UpdateManager.install(this@AccountActivity, file) }
            UpdateManager.install(this@AccountActivity, file)
        }
    }

    private fun googleLogin() {
        if (busy) return
        lifecycleScope.launch {
            val token = runCatching {
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(GetSignInWithGoogleOption.Builder(ServerConfig.GOOGLE_WEB_CLIENT_ID).build())
                    .build()
                val cred = CredentialManager.create(this@AccountActivity).getCredential(this@AccountActivity, request).credential
                if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    GoogleIdTokenCredential.createFrom(cred.data).idToken
                } else null
            }.getOrNull()
            if (token == null) {
                toast("Login com Google cancelado ou indisponível")
                return@launch
            }
            act({ AccountGate.loginWithGoogle(this@AccountActivity, token) }) { false }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun planName(p: String?) = when (p) {
        BillingManager.PRODUCT_MONTHLY -> "Mensal"
        BillingManager.PRODUCT_YEARLY -> "Anual"
        "cupom" -> "Cupom"
        "vitalicio" -> "Vitalício"
        else -> p ?: "Plano"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
