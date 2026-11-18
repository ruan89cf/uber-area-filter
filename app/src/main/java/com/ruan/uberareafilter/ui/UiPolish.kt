package com.ruan.uberareafilter.ui

import android.animation.ArgbEvaluator
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.ruan.uberareafilter.R
import com.ruan.uberareafilter.setup.SetupChecks

/**
 * Acabamento visual do painel: botões "?" com explicação, cartão principal com degradê que muda
 * com o estado, linhas coloridas da verificação do app e pequenas animações.
 * Fica separado do MainActivity para o código de regras não se misturar com o de aparência.
 */
object UiPolish {

    /** Curva "sai rápido, chega suave" usada em todas as animações do app. */
    val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun color(ctx: Context, res: Int): Int = ContextCompat.getColor(ctx, res)

    /** Ícone vetorial (desenhado branco) pintado com a cor pedida, para diálogos e textos. */
    fun icon(ctx: Context, res: Int, colorRes: Int): Drawable? =
        ContextCompat.getDrawable(ctx, res)?.mutate()?.apply { setTint(color(ctx, colorRes)) }

    // ------------------------------------------------------------------
    // Ajuda "?"
    // ------------------------------------------------------------------

    fun showHelp(activity: Activity, help: HelpCatalog.Help) {
        val ctx = activity
        val sheet = BottomSheetDialog(ctx, R.style.ThemeOverlay_Filtro_HelpSheet)
        val onSurface = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val onSurfaceVar = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
        val accent = color(ctx, help.accent.on)
        val accentBg = color(ctx, help.accent.container)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24f), 0, dp(ctx, 24f), dp(ctx, 24f))
        }
        root.addView(BottomSheetDragHandleView(ctx))

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(ctx).apply {
            setImageResource(help.icon)
            imageTintList = ColorStateList.valueOf(accent)
            background = GradientDrawable().apply { cornerRadius = dp(ctx, 14f).toFloat(); setColor(accentBg) }
            val p = dp(ctx, 11f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 46f), dp(ctx, 46f))
        })
        head.addView(TextView(ctx).apply {
            text = help.title
            setTextColor(onSurface)
            textSize = 20f
            typeface = Typeface.SERIF
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(ctx, 14f)
            }
        })
        root.addView(head)

        root.addView(TextView(ctx).apply {
            text = help.body
            setTextColor(onSurfaceVar)
            textSize = 15.5f
            setLineSpacing(dp(ctx, 4f).toFloat(), 1f)
            setPadding(0, dp(ctx, 18f), 0, 0)
        })

        help.example?.let { ex ->
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(ctx, 16f).toFloat()
                    setColor(ColorUtils.setAlphaComponent(accentBg, 200))
                }
                val p = dp(ctx, 14f)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(ctx, 18f)
                }
            }
            box.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_lightbulb)
                imageTintList = ColorStateList.valueOf(accent)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 22f), dp(ctx, 22f))
            })
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(ctx, 12f)
                }
            }
            col.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.help_example).uppercase()
                setTextColor(accent)
                textSize = 11.5f
                letterSpacing = 0.08f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            col.addView(TextView(ctx).apply {
                text = ex
                setTextColor(onSurface)
                textSize = 14.5f
                setLineSpacing(dp(ctx, 3f).toFloat(), 1f)
                setPadding(0, dp(ctx, 4f), 0, 0)
            })
            box.addView(col)
            root.addView(box)
        }

        root.addView(MaterialButton(ctx).apply {
            text = ctx.getString(R.string.help_ok)
            minHeight = dp(ctx, 52f)
            cornerRadius = dp(ctx, 16f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 22f)
            }
            setOnClickListener { sheet.dismiss() }
        })

        sheet.setContentView(root)
        sheet.show()
    }

    private fun helpButton(activity: Activity, help: HelpCatalog.Help): ImageButton =
        ImageButton(activity).apply {
            setImageResource(R.drawable.ic_help)
            val tint = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
            imageTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(tint, 170))
            setBackgroundResource(R.drawable.bg_icon_ripple)
            val p = dp(activity, 10f)
            setPadding(p, p, p, p)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "${activity.getString(R.string.help_button)} ${help.title}"
            tooltipText = help.title
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                showHelp(activity, help)
            }
        }

    /**
     * Coloca um "?" ao lado de cada opção e de cada título de card que tem explicação no
     * [HelpCatalog]. As views continuam as mesmas (o binding segue funcionando); só ganham um vizinho.
     */
    fun attachHelpButtons(activity: Activity, root: ViewGroup) {
        val res = activity.resources
        val titleKeys = HelpCatalog.items.keys.mapNotNull { key ->
            val id = res.getIdentifier(key, "string", activity.packageName)
            if (id != 0) activity.getString(id) to key else null
        }.toMap()

        val switches = mutableListOf<Pair<View, HelpCatalog.Help>>()
        val headings = mutableListOf<Pair<TextView, HelpCatalog.Help>>()
        val fields = mutableListOf<Pair<View, HelpCatalog.Help>>()

        fun walk(v: View) {
            val idName = if (v.id != View.NO_ID) runCatching { res.getResourceEntryName(v.id) }.getOrNull() else null
            val byId = idName?.let { HelpCatalog.items[it] }
            when {
                v is MaterialSwitch && byId != null -> switches += v to byId
                v is TextView && v.isAccessibilityHeading -> titleKeys[v.text.toString()]?.let { key ->
                    HelpCatalog.items[key]?.let { headings += v to it }
                }
                byId != null && v is TextView -> fields += v to byId
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)

        val size = dp(activity, 40f)

        // Opções (switches): na mesma linha, à direita do texto e antes da chave
        for ((sw, help) in switches) {
            val parent = sw.parent as? LinearLayout ?: continue
            val idx = parent.indexOfChild(sw)
            val btn = helpButton(activity, help)
            if (parent.orientation == LinearLayout.HORIZONTAL) {
                parent.addView(btn, idx + 1, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_VERTICAL })
            } else {
                val lp = sw.layoutParams
                parent.removeViewAt(idx)
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    tag = sw.tag
                }
                row.addView(sw, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(btn, LinearLayout.LayoutParams(size, size))
                parent.addView(row, idx, lp)
            }
        }

        // Campos numéricos com explicação: o "?" entra antes do campo
        for ((field, help) in fields) {
            val til = generateSequence(field.parent) { it.parent }.firstOrNull { it is TextInputLayout } as? View ?: continue
            val row = til.parent as? LinearLayout ?: continue
            if (row.orientation != LinearLayout.HORIZONTAL) continue
            row.addView(helpButton(activity, help), row.indexOfChild(til), LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
        }

        // Títulos de card: "?" no fim da linha do cabeçalho (antes da seta, nos recolhíveis)
        for ((title, help) in headings) {
            val textBlock = title.parent as? View ?: continue
            val headerRow = textBlock.parent as? LinearLayout ?: continue
            if (headerRow.orientation != LinearLayout.HORIZONTAL) continue
            headerRow.addView(helpButton(activity, help), headerRow.indexOfChild(textBlock) + 1, LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
        }
    }

    // ------------------------------------------------------------------
    // Cartão principal
    // ------------------------------------------------------------------

    enum class HeroState { ON, PAUSED, OFF, ATTENTION }

    private var heroColors: IntArray? = null
    private var heroAnimator: ValueAnimator? = null

    fun heroGradient(ctx: Context, s: HeroState): IntArray = when (s) {
        HeroState.ON -> intArrayOf(color(ctx, R.color.hero_on_start), color(ctx, R.color.hero_on_end))
        HeroState.PAUSED -> intArrayOf(color(ctx, R.color.hero_pause_start), color(ctx, R.color.hero_pause_end))
        HeroState.ATTENTION -> intArrayOf(color(ctx, R.color.hero_warn_start), color(ctx, R.color.hero_warn_end))
        HeroState.OFF -> intArrayOf(color(ctx, R.color.hero_off_start), color(ctx, R.color.hero_off_end))
    }

    /** Pinta o fundo do cartão com o degradê do estado, fazendo a transição de cor suave. */
    fun paintHeroBackground(target: View, cornerRadiusPx: Float, s: HeroState) {
        val ctx = target.context
        val to = heroGradient(ctx, s)
        val drawable = (target.background as? GradientDrawable) ?: GradientDrawable(
            GradientDrawable.Orientation.TL_BR, to
        ).apply { cornerRadius = cornerRadiusPx }.also { target.background = it }
        val from = heroColors ?: to
        heroColors = to
        heroAnimator?.cancel()
        if (from.contentEquals(to)) {
            drawable.colors = to
            return
        }
        val ev = ArgbEvaluator()
        heroAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = EASE
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                drawable.colors = intArrayOf(
                    ev.evaluate(f, from[0], to[0]) as Int,
                    ev.evaluate(f, from[1], to[1]) as Int
                )
            }
            start()
        }
    }

    /** Chave do cartão principal: branca sobre o degradê (a verde padrão sumia no fundo verde). */
    fun styleHeroSwitch(sw: MaterialSwitch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.parseColor("#E6FFFFFF")))
        sw.trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#80FFFFFF"), Color.parseColor("#33FFFFFF")))
        sw.trackDecorationTintList = ColorStateList(states, intArrayOf(Color.TRANSPARENT, Color.parseColor("#B3FFFFFF")))
        sw.thumbIconTintList = ColorStateList.valueOf(Color.parseColor("#05944F"))
    }

    // ------------------------------------------------------------------
    // Selo de estado no topo
    // ------------------------------------------------------------------

    fun paintStatusChip(tv: TextView, s: HeroState) {
        val ctx = tv.context
        val (fg, bg, label) = when (s) {
            HeroState.ON -> Triple(R.color.st_ok, R.color.st_ok_container, R.string.top_status_on)
            HeroState.PAUSED -> Triple(R.color.st_warn, R.color.st_warn_container, R.string.top_status_paused)
            HeroState.ATTENTION -> Triple(R.color.st_bad, R.color.st_bad_container, R.string.top_status_attention)
            HeroState.OFF -> Triple(R.color.st_off, R.color.st_off_container, R.string.top_status_off)
        }
        tv.text = ctx.getString(label)
        tv.setTextColor(color(ctx, fg))
        tv.backgroundTintList = ColorStateList.valueOf(color(ctx, bg))
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(ctx, fg))
            setSize(dp(ctx, 7f), dp(ctx, 7f))
        }
        tv.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
    }

    // ------------------------------------------------------------------
    // Verificação do app
    // ------------------------------------------------------------------

    data class LevelStyle(val fg: Int, val bg: Int, val icon: Int)

    fun levelStyle(level: SetupChecks.Level): LevelStyle = when (level) {
        SetupChecks.Level.OK -> LevelStyle(R.color.st_ok, R.color.st_ok_container, R.drawable.ic_check)
        SetupChecks.Level.WARN -> LevelStyle(R.color.st_warn, R.color.st_warn_container, R.drawable.ic_warning)
        SetupChecks.Level.BAD -> LevelStyle(R.color.st_bad, R.color.st_bad_container, R.drawable.ic_error)
        SetupChecks.Level.OFF -> LevelStyle(R.color.st_off, R.color.st_off_container, R.drawable.ic_power)
    }

    /** Uma linha da verificação: bolinha colorida com ícone, título, explicação e botão "Resolver". */
    fun healthRow(ctx: Context, check: SetupChecks.Check, showDivider: Boolean, onFix: (SetupChecks.Fix) -> Unit): View {
        val st = levelStyle(check.level)
        val fg = color(ctx, st.fg)
        val onSurface = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val onSurfaceVar = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
        val outline = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)

        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (showDivider) wrap.addView(View(ctx).apply {
            setBackgroundColor(outline)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f)).apply {
                marginStart = dp(ctx, 46f)
            }
        })
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 10f), 0, dp(ctx, 10f))
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(st.icon)
            imageTintList = ColorStateList.valueOf(fg)
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_circle)
            backgroundTintList = ColorStateList.valueOf(color(ctx, st.bg))
            val p = dp(ctx, 7f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 32f), dp(ctx, 32f))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(ctx, 14f)
                marginEnd = dp(ctx, 8f)
            }
        }
        col.addView(TextView(ctx).apply {
            text = check.title
            setTextColor(onSurface)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        col.addView(TextView(ctx).apply {
            val sb = SpannableStringBuilder(check.detail)
            val lead = listOf("Falta isso:", "Recomendado:").firstOrNull { check.detail.startsWith(it) }
            if (lead != null) {
                sb.setSpan(ForegroundColorSpan(fg), 0, lead.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), 0, lead.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = sb
            setTextColor(onSurfaceVar)
            textSize = 13f
            setLineSpacing(dp(ctx, 2f).toFloat(), 1f)
            setPadding(0, dp(ctx, 2f), 0, 0)
        })
        row.addView(col)
        val fix = check.fix
        if (fix != null && check.level != SetupChecks.Level.OK) {
            row.addView(MaterialButton(ctx).apply {
                elevation = 0f
                stateListAnimator = null
                text = check.fixLabel
                setTextColor(fg)
                backgroundTintList = ColorStateList.valueOf(color(ctx, st.bg))
                minHeight = dp(ctx, 36f)
                minimumHeight = dp(ctx, 36f)
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(ctx, 18f)
                textSize = 13f
                setPadding(dp(ctx, 14f), 0, dp(ctx, 14f), 0)
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onFix(fix)
                }
            })
            row.isClickable = true
            row.isFocusable = true
            row.setBackgroundResource(TypedValue().also {
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }.resourceId)
            row.setOnClickListener { onFix(fix) }
        }
        row.contentDescription = "${check.title}. ${check.detail}"
        wrap.addView(row)
        return wrap
    }

    // ------------------------------------------------------------------
    // Movimento
    // ------------------------------------------------------------------

    /** Cards da aba entram subindo e aparecendo, um logo depois do outro. */
    fun animateEntrance(views: List<View>) {
        views.forEachIndexed { i, v ->
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = dp(v.context, 18f).toFloat()
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((i * 40L).coerceAtMost(240L))
                .setDuration(320)
                .setInterpolator(EASE)
                .start()
        }
    }

    fun rotateArrow(v: View, expanded: Boolean) {
        v.animate().rotation(if (expanded) 180f else 0f).setDuration(220).setInterpolator(EASE).start()
    }

    /** Abrir/fechar de seção com altura animada em vez de "pular". */
    fun smoothExpand(group: ViewGroup) {
        group.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(220)
            setInterpolator(LayoutTransition.CHANGING, EASE)
        }
    }

    /** Toque leve ao virar uma chave: confirma sem precisar olhar. */
    fun tick(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
