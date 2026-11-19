package com.ruan.uberareafilter.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.ruan.uberareafilter.BuildConfig
import com.ruan.uberareafilter.account.AccountGate
import com.ruan.uberareafilter.account.AccountState
import com.ruan.uberareafilter.account.BillingManager
import com.ruan.uberareafilter.account.ServerConfig
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Conta e plano: login (Google ou codigo de acesso), situacao do plano, cupom, assinatura e
 * atualizacao obrigatoria. Sem licenca valida o app fica nesta tela.
 */
class AccountActivity : AppCompatActivity() {

    companion object {
        /** Abre a tela da conta. `locked` = o app nao pode seguir sem resolver (login/plano/atualizacao). */
        fun open(context: Context, locked: Boolean) {
            context.startActivity(
                Intent(context, AccountActivity::class.java)
                    .putExtra("locked", locked)
                    .addFlags(if (locked) Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK else 0)
            )
        }
    }

    private lateinit var root: LinearLayout
    private var billing: BillingManager? = null
    private var busy = false
    private val br = Locale("pt", "BR")

    private val dark get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val fg get() = if (dark) Color.parseColor("#E2E8F0") else Color.parseColor("#0F172A")
    private val muted get() = if (dark) Color.parseColor("#94A3B8") else Color.parseColor("#475569")
    private val cardBg get() = if (dark) Color.parseColor("#1E293B") else Color.parseColor("#F1F5F9")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(if (dark) Color.parseColor("#0F172A") else Color.WHITE)
            addView(root)
        })
        billing = BillingManager(this, lifecycleScope, ::toast) { render() }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (AccountGate.hasSession(this)) {
            lifecycleScope.launch {
                AccountGate.refresh(this@AccountActivity)
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
        // Travado: voltar fecha o app em vez de abrir as telas sem licenca
        if (!AccountGate.isLicensed(this)) finishAffinity() else super.onBackPressed()
    }

    // ------------------------------------------------------------------ tela
    private fun render() {
        root.removeAllViews()
        root.addView(text("Filtro Inteligente", 24f, fg, bold = true))
        val st = AccountGate.state(this)
        val logged = AccountGate.hasSession(this)
        val licensed = AccountGate.isLicensed(this)

        if (st?.mustUpdate == true) {
            renderUpdate(st, mandatory = true)
            return
        }
        if (!logged) {
            renderLogin(st?.message)
            return
        }
        root.addView(text(st?.email.orEmpty(), 14f, muted).apply { setPadding(0, dp(2), 0, dp(14)) })
        root.addView(statusCard(st, licensed))
        if (st?.hasUpdate == true) renderUpdate(st, mandatory = false)

        if (st?.status != "blocked") {
            section("Tem um cupom?")
            val coupon = input("Digite o cupom")
            root.addView(coupon)
            root.addView(button("Aplicar cupom") {
                val code = coupon.text.toString().trim()
                if (code.isEmpty()) return@button toast("Digite o cupom")
                run { AccountGate.redeemCoupon(this, code) }
            })

            if (st?.status != "active" || st.plan == "teste") {
                section("Assinar")
                root.addView(text("Mensal R$ 24,90 · Anual com desconto. Cobrança pelo Google Play (Pix ou cartão).", 13f, muted))
                root.addView(button("Assinar mensal") { billing?.buy(BillingManager.PRODUCT_MONTHLY) })
                root.addView(button("Assinar anual", outline = true) { billing?.buy(BillingManager.PRODUCT_YEARLY) })
            }
        }

        section("")
        if (licensed) {
            root.addView(button("Continuar para o app") { openMain() })
        }
        root.addView(button("Sair da conta", outline = true) {
            AccountGate.logout(this)
            render()
        })
    }

    private fun statusCard(st: AccountState?, licensed: Boolean): View {
        val (title, line, color) = when (st?.status) {
            "trial" -> Triple("Teste grátis", "Faltam ${daysLeft(st.until)} · até ${date(st.until)}", "#2563EB")
            "active" -> if (st.until == null) Triple("Acesso vitalício", "Sem data para acabar", "#16A34A")
            else Triple("Plano ativo", "${planName(st.plan)} até ${date(st.until)}", "#16A34A")
            "expired" -> Triple("Plano vencido", "Assine ou use um cupom para continuar", "#DC2626")
            "blocked" -> Triple("Acesso bloqueado", st.message ?: "Fale com o suporte", "#DC2626")
            else -> Triple("Sem plano", st?.message ?: "Confira sua conexão", "#64748B")
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(cardBg); setStroke(dp(2), Color.parseColor(color)) }
            addView(text(title, 18f, Color.parseColor(color), bold = true))
            addView(text(line, 14f, fg))
            if (!licensed && st?.status in setOf("trial", "active")) {
                addView(text("Conectando ao servidor para liberar este celular…", 12f, muted))
            }
            st?.message?.takeIf { st.status != "blocked" && it.isNotBlank() }?.let { addView(text(it, 13f, muted)) }
        }
    }

    private fun renderLogin(message: String?) {
        root.addView(text("Entre com sua conta para usar o app. Conta nova em celular novo ganha 7 dias grátis.", 15f, fg).apply {
            setPadding(0, dp(10), 0, dp(18))
        })
        message?.takeIf { it.isNotBlank() }?.let { root.addView(text(it, 14f, Color.parseColor("#DC2626"))) }
        if (ServerConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
            root.addView(button("Entrar com Google") { googleLogin() })
        } else {
            root.addView(text("Entrar com Google: em configuração. Use um código de acesso.", 13f, muted))
        }
        section("Entrar com código de acesso")
        val code = input("Código recebido")
        root.addView(code)
        root.addView(button("Entrar com código", outline = ServerConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
            val c = code.text.toString().trim()
            if (c.isEmpty()) return@button toast("Digite o código")
            run { AccountGate.loginWithCode(this, c) }
        })
    }

    private fun renderUpdate(st: AccountState, mandatory: Boolean) {
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(cardBg); setStroke(dp(2), Color.parseColor(if (mandatory) "#DC2626" else "#F59E0B")) }
            addView(text(if (mandatory) "Atualização obrigatória" else "Nova versão disponível", 17f, fg, bold = true))
            addView(text(st.updateMessage.ifBlank { if (mandatory) "Atualize para continuar usando." else "Atualize quando puder." }, 14f, fg))
            if (st.updateUrl.isNotBlank()) {
                addView(button("Atualizar agora") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(st.updateUrl))) }
                })
            }
        })
        if (mandatory) {
            root.addView(text("Versão instalada: ${BuildConfig.VERSION_NAME}", 12f, muted))
        }
    }

    // ------------------------------------------------------------------ acoes
    private fun run(action: suspend () -> Pair<AccountState?, String?>) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val (st, err) = action()
            busy = false
            if (err != null) toast(err) else st?.message?.let { toast(it) }
            render()
            if (err == null && AccountGate.isLicensed(this@AccountActivity) && intent.getBooleanExtra("locked", false)) openMain()
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
            run { AccountGate.loginWithGoogle(this@AccountActivity, token) }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    // ------------------------------------------------------------------ ajudas
    private fun daysLeft(until: Long?): String {
        if (until == null) return "—"
        val d = TimeUnit.MILLISECONDS.toDays(until - System.currentTimeMillis()).coerceAtLeast(0) + 1
        return if (d == 1L) "1 dia" else "$d dias"
    }

    private fun date(t: Long?) = t?.let { SimpleDateFormat("dd/MM/yyyy", br).format(Date(it)) } ?: "—"
    private fun planName(p: String?) = when (p) {
        BillingManager.PRODUCT_MONTHLY -> "Mensal"
        BillingManager.PRODUCT_YEARLY -> "Anual"
        "cupom" -> "Cupom"
        "vitalicio" -> "Vitalício"
        else -> p ?: "Plano"
    }

    private fun section(title: String) {
        root.addView(text(title, 15f, fg, bold = true).apply { setPadding(0, dp(22), 0, dp(6)) })
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun input(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        setTextColor(fg)
        setHintTextColor(muted)
        isSingleLine = true
    }

    private fun button(label: String, outline: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(if (outline) fg else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (outline) { setColor(Color.TRANSPARENT); setStroke(dp(1), muted) } else setColor(Color.parseColor("#16A34A"))
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
