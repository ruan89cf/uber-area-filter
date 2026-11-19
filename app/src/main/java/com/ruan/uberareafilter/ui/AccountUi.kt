package com.ruan.uberareafilter.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ruan.uberareafilter.R

/**
 * Pecas visuais das telas de conta, no padrao do app: fundo marfim (grafite quente a noite),
 * titulos em serifa, botao principal preto (verde a noite), campos com contorno e cantos de 14 dp.
 */
class AccountUi(private val ctx: Context) {

    private fun c(id: Int) = ContextCompat.getColor(ctx, id)

    val fg get() = c(R.color.fi_on_surface)
    val muted get() = c(R.color.fi_on_surface_variant)
    val cardBg get() = c(R.color.fi_surface)
    val pageBg get() = c(R.color.fi_background)
    val line get() = c(R.color.fi_outline_variant)
    val primary get() = c(R.color.fi_primary)
    val onPrimary get() = c(R.color.fi_on_primary)
    val green = Color.parseColor("#06C167")
    val red = Color.parseColor("#D0101F")
    val blue = Color.parseColor("#2563EB")
    val amber = Color.parseColor("#F59E0B")

    private val serif: Typeface = Typeface.create("serif", Typeface.BOLD)

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun text(s: String, size: Float, color: Int = fg, bold: Boolean = false, center: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (center) gravity = Gravity.CENTER_HORIZONTAL
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(2), 0, dp(2))
    }

    /** Titulo grande em serifa (como os titulos do painel). */
    fun title(s: String, center: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = 28f
        typeface = serif
        setTextColor(fg)
        if (center) gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(4), 0, dp(6))
    }

    fun section(s: String) = text(s, 13f, muted, bold = true).apply {
        isAllCaps = true
        letterSpacing = 0.08f
        setPadding(0, dp(22), 0, dp(6))
    }

    /** Topo das telas de entrar/criar conta: logo, nome e frase. */
    fun brandHeader(subtitle: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(8), 0, dp(18))
        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_app_logo)
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76))
            elevation = dp(6).toFloat()
        })
        addView(title("Filtro Inteligente", center = true).apply { setPadding(0, dp(14), 0, dp(2)) })
        addView(text(subtitle, 15f, muted, center = true))
    }

    enum class Kind { TEXT, CODE, EMAIL, PASSWORD, NAME, NUMBER }

    /** Campo com contorno (Material). `text` devolve o que foi digitado. */
    inner class Field(hint: String, kind: Kind, value: String) :
        TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle) {
        val edit = TextInputEditText(this.context)
        val text: Editable? get() = edit.text

        init {
            this.hint = hint
            setBoxCornerRadii(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
            boxStrokeColor = primary
            setHintTextColor(ColorStateList.valueOf(muted))
            edit.inputType = when (kind) {
                Kind.CODE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                Kind.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                Kind.PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                Kind.NAME -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                Kind.NUMBER -> InputType.TYPE_CLASS_NUMBER
                Kind.TEXT -> InputType.TYPE_CLASS_TEXT
            }
            if (kind == Kind.PASSWORD) endIconMode = END_ICON_PASSWORD_TOGGLE
            if (kind == Kind.NUMBER) { edit.textSize = 22f; edit.letterSpacing = 0.3f; edit.gravity = Gravity.CENTER }
            edit.setTextColor(fg)
            edit.isSingleLine = true
            edit.setText(value)
            addView(edit)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        }
    }

    fun input(hint: String, kind: Kind = Kind.TEXT, value: String = "") = Field(hint, kind, value)

    /** Botao: principal (preto de dia / verde a noite), colorido ou com contorno. */
    fun button(label: String, outline: Boolean = false, color: Int? = null, onClick: () -> Unit) = MaterialButton(
        ctx, null, if (outline) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle
    ).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        letterSpacing = 0.01f
        cornerRadius = dp(14)
        insetTop = 0
        insetBottom = 0
        if (outline) {
            setTextColor(fg)
            strokeColor = ColorStateList.valueOf(c(R.color.fi_outline))
            strokeWidth = dp(1)
        } else {
            val bg = color ?: primary
            backgroundTintList = ColorStateList.valueOf(bg)
            setTextColor(if (color == null) onPrimary else Color.WHITE)
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

    /** Botao so de texto (ex.: "Esqueci a senha"). */
    fun link(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(fg)
        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(14), 0, dp(10))
        setOnClickListener { onClick() }
    }

    /** Divisor "ou". */
    fun orDivider(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(16), 0, dp(4))
        fun bar() = View(ctx).apply {
            setBackgroundColor(line)
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
        }
        addView(bar())
        addView(text("ou", 13f, muted).apply { setPadding(dp(12), 0, dp(12), 0) })
        addView(bar())
    }

    fun card(stroke: Int, vararg children: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(cardBg)
            setStroke(dp(1), line)
        }
        elevation = dp(1).toFloat()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        // Faixa colorida no topo do card (cor do estado)
        addView(View(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(stroke) }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(5)).apply { bottomMargin = dp(10) }
        })
        children.forEach { addView(it) }
    }

    fun column(padding: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    companion object {
        private val br = java.util.Locale("pt", "BR")
        fun money(v: Double): String = java.text.NumberFormat.getCurrencyInstance(br).format(v)
        fun date(t: Long?): String = t?.let { java.text.SimpleDateFormat("dd/MM/yyyy", br).format(java.util.Date(it)) } ?: "—"
        fun daysLeft(until: Long?): Long {
            if (until == null) return 0
            val ms = until - System.currentTimeMillis()
            return if (ms <= 0) 0 else (ms + 86_399_999L) / 86_400_000L
        }
    }
}
