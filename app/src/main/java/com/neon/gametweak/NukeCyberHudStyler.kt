package com.neon.gametweak

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Lightweight shared UI system for all Game Nuke classic-View floating panels.
 *
 * REACTOR EMERALD uses the app-logo language, then follows the currently-selected AI profile.
 * The root tactical drawable uses one tiny 5–6 FPS mode accent only while attached; there is no
 * blur, bitmap texture, particle system, shader loop or per-frame object allocation.
 */
object NukeCyberHudStyler {

    val COLOR_BG_OBSIDIAN: Int get() = NukeAiThemeController.currentPalette.background
    val COLOR_BG_CARD: Int get() = NukeAiThemeController.currentPalette.panel
    val COLOR_BG_CARD_ALT: Int get() = NukeAiThemeController.currentPalette.panelRaised
    val COLOR_BG_RAISED: Int get() = NukeAiThemeController.currentPalette.panelSoft
    // Legacy names retained so existing overlay call-sites keep compiling while becoming mode-aware.
    val COLOR_CYAN_NEON: Int get() = NukeAiThemeController.currentPalette.accent
    val COLOR_CYAN_DIM: Int get() = NukeAiThemeController.currentPalette.accentDim
    val COLOR_EMERALD_NEON: Int get() = NukeAiThemeController.currentPalette.accent
    val COLOR_EMERALD_DIM: Int get() = NukeAiThemeController.currentPalette.accentDim
    val COLOR_TELEMETRY: Int get() = NukeAiThemeController.currentPalette.telemetry
    val COLOR_CRIMSON_NEON: Int get() = NukeAiThemeController.currentPalette.danger
    val COLOR_AMBER_NEON: Int get() = NukeAiThemeController.currentPalette.warning
    val COLOR_TEXT_PRIMARY: Int get() = NukeAiThemeController.currentPalette.text
    val COLOR_TEXT_MUTED: Int get() = NukeAiThemeController.currentPalette.muted
    val COLOR_BORDER_SUBTLE: Int get() = NukeAiThemeController.currentPalette.border
    val COLOR_BORDER_BRIGHT: Int get() = NukeAiThemeController.currentPalette.borderBright

    /** Static panel chassis with sparse reactor-grid detail. */
    class TacticalPanelDrawable(
        private val density: Float,
        private val cornerRadiusPx: Float,
        private val strokeColor: Int = COLOR_CYAN_NEON,
        private val bgColor: Int = COLOR_BG_OBSIDIAN,
        private val showGrid: Boolean = true,
        private val showBrackets: Boolean = true
    ) : Drawable() {

        private val mode = NukeAiThemeController.currentMode
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_BORDER_SUBTLE
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(86, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor))
            style = Paint.Style.STROKE
            strokeWidth = .55f * density
        }
        private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            strokeCap = Paint.Cap.SQUARE
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(14, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor))
            style = Paint.Style.STROKE
            strokeWidth = .55f * density
        }
        private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(34, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor))
            style = Paint.Style.STROKE
            strokeWidth = .75f * density
        }
        private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(122, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor))
            style = Paint.Style.FILL
        }
        private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(28, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor))
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        private val boundsRect = RectF()
        private val innerRect = RectF()
        private val trace = Path()
        private val frameRunnable = Runnable { invalidateSelf() }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            boundsRect.set(bounds)
            innerRect.set(boundsRect)
            innerRect.inset(4f * density, 4f * density)
            val r = boundsRect
            if (r.width() > 1f && r.height() > 1f) {
                bgPaint.shader = LinearGradient(
                    r.left, r.top, r.right, r.bottom,
                    intArrayOf(bgColor, blend(bgColor, strokeColor, 0.08f), blend(bgColor, Color.BLACK, 0.16f)),
                    floatArrayOf(0f, .56f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            trace.rewind()
            val inset = 12f * density
            val elbow = 32f * density
            trace.moveTo(r.left + inset, r.top + r.height() * .28f)
            trace.lineTo(r.left + elbow, r.top + r.height() * .28f)
            trace.lineTo(r.left + elbow + 10f * density, r.top + r.height() * .34f)
            trace.lineTo(r.left + r.width() * .36f, r.top + r.height() * .34f)
            trace.moveTo(r.right - inset, r.bottom - r.height() * .23f)
            trace.lineTo(r.right - elbow, r.bottom - r.height() * .23f)
            trace.lineTo(r.right - elbow - 10f * density, r.bottom - r.height() * .29f)
            trace.lineTo(r.left + r.width() * .64f, r.bottom - r.height() * .29f)
        }

        override fun draw(canvas: Canvas) {
            val r = boundsRect
            canvas.drawRoundRect(r, cornerRadiusPx, cornerRadiusPx, bgPaint)

            if (showGrid) {
                // Sparse orthogonal grid: dramatically fewer primitives than the old dense dot field.
                val step = 28f * density
                var x = r.left + step
                while (x < r.right) {
                    canvas.drawLine(x, r.top + 8f * density, x, r.bottom - 8f * density, gridPaint)
                    x += step
                }
                var y = r.top + step
                while (y < r.bottom) {
                    canvas.drawLine(r.left + 8f * density, y, r.right - 8f * density, y, gridPaint)
                    y += step
                }
                canvas.drawPath(trace, tracePaint)
                canvas.drawCircle(r.left + 32f * density, r.top + r.height() * .28f, 1.7f * density, nodePaint)
                canvas.drawCircle(r.right - 32f * density, r.bottom - r.height() * .23f, 1.7f * density, nodePaint)
            }            // Clean static profile signature: zero allocation, zero CPU load.
            when (mode) {
                NukeAiThemeController.Mode.LOW_POWER -> {
                    canvas.drawCircle(r.right - 30f * density, r.top + 30f * density, 14f * density, fxPaint)
                    canvas.drawCircle(r.right - 30f * density, r.top + 30f * density, 21f * density, fxPaint)
                }
                NukeAiThemeController.Mode.BALANCE -> {
                    val y = r.top + r.height() * 0.5f
                    canvas.drawLine(r.left + 14f * density, y, r.right - 14f * density, y, fxPaint)
                    canvas.drawCircle(r.left + r.width() * .27f, y, 1.4f * density, nodePaint)
                    canvas.drawCircle(r.left + r.width() * .73f, y, 1.2f * density, nodePaint)
                }
                NukeAiThemeController.Mode.PERFORMANCE -> {
                    var x = r.left + 16f * density
                    repeat(4) {
                        canvas.drawLine(x, r.bottom - 13f * density, x + 18f * density, r.bottom - 31f * density, fxPaint)
                        x += 26f * density
                    }
                }
                NukeAiThemeController.Mode.EXTREME -> {
                    val cx = r.right - 34f * density
                    val cy = r.bottom - 26f * density
                    canvas.drawArc(cx - 20f * density, cy - 20f * density, cx + 20f * density, cy + 20f * density, 205f, 250f, false, fxPaint)
                    canvas.drawArc(cx - 11f * density, cy - 28f * density, cx + 11f * density, cy + 18f * density, 210f, 235f, false, fxPaint)
                }
            }

            canvas.drawRoundRect(r, cornerRadiusPx, cornerRadiusPx, borderPaint)
            canvas.drawRoundRect(innerRect, (cornerRadiusPx - 4f * density).coerceAtLeast(0f), (cornerRadiusPx - 4f * density).coerceAtLeast(0f), innerPaint)

            if (showBrackets) {
                val blen = 18f * density
                val pad = 5f * density
                canvas.drawLine(r.left + pad, r.top + pad + blen, r.left + pad, r.top + pad, bracketPaint)
                canvas.drawLine(r.left + pad, r.top + pad, r.left + pad + blen, r.top + pad, bracketPaint)
                canvas.drawLine(r.right - pad - blen, r.top + pad, r.right - pad, r.top + pad, bracketPaint)
                canvas.drawLine(r.right - pad, r.top + pad, r.right - pad, r.top + pad + blen, bracketPaint)
                canvas.drawLine(r.left + pad, r.bottom - pad - blen, r.left + pad, r.bottom - pad, bracketPaint)
                canvas.drawLine(r.left + pad, r.bottom - pad, r.left + pad + blen, r.bottom - pad, bracketPaint)
                canvas.drawLine(r.right - pad - blen, r.bottom - pad, r.right - pad, r.bottom - pad, bracketPaint)
                canvas.drawLine(r.right - pad, r.bottom - pad, r.right - pad, r.bottom - pad - blen, bracketPaint)
            }
        }

        private fun blend(base: Int, overlay: Int, ratio: Float): Int {
            val t = ratio.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(base) * (1f - t) + Color.red(overlay) * t).toInt(),
                (Color.green(base) * (1f - t) + Color.green(overlay) * t).toInt(),
                (Color.blue(base) * (1f - t) + Color.blue(overlay) * t).toInt()
            )
        }

        override fun setAlpha(alpha: Int) { bgPaint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { bgPaint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    fun buildCardBackground(
        density: Float,
        cornerRadiusDp: Float = 12f,
        strokeColor: Int = COLOR_BORDER_SUBTLE,
        fillColor: Int = COLOR_BG_CARD
    ): Drawable = GradientDrawable().apply {
        colors = intArrayOf(fillColor, COLOR_BG_OBSIDIAN)
        orientation = GradientDrawable.Orientation.TL_BR
        cornerRadius = cornerRadiusDp * density
        setStroke((1f * density).toInt().coerceAtLeast(1), strokeColor)
    }

    /**
     * 8–10 FPS accent rail shared by classic child overlays. It invalidates only a 4dp strip,
     * allocates nothing in onDraw(), and stops automatically when detached.
     */
    private class ModeFxRailView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val palette = NukeAiThemeController.currentPalette
            paint.color = palette.accent
            line.color = Color.argb(165, Color.red(palette.accent), Color.green(palette.accent), Color.blue(palette.accent))
            when (palette.mode) {
                NukeAiThemeController.Mode.LOW_POWER -> {
                    canvas.drawCircle(width * .5f, height * .5f, 1.6f * d, paint)
                }
                NukeAiThemeController.Mode.BALANCE -> {
                    val y = height * 0.5f
                    canvas.drawRect(0f, y, width.toFloat(), (y + 2f * d).coerceAtMost(height.toFloat()), paint)
                }
                NukeAiThemeController.Mode.PERFORMANCE -> {
                    val segment = height / 4f
                    repeat(3) { i ->
                        val y = (i + 0.5f) * segment
                        canvas.drawRect(0f, y, width.toFloat(), (y + 3f * d).coerceAtMost(height.toFloat()), paint)
                    }
                }
                NukeAiThemeController.Mode.EXTREME -> {
                    path.rewind()
                    path.moveTo(width * .5f, height.toFloat())
                    path.quadTo(-width.toFloat(), height * 0.5f, width * .72f, height * .45f)
                    path.quadTo(width * 1.7f, height * .20f, width * .35f, 0f)
                    canvas.drawPath(path, line)
                }
            }
        }
    }

    /** Compact two-level esports header used across child overlays. */
    fun buildHeader(
        context: Context,
        title: String,
        badgeText: String,
        iconEmoji: String = "⚡",
        accentColor: Int = COLOR_CYAN_NEON,
        onClose: () -> Unit
    ): LinearLayout {
        val d = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (50 * d).toInt()
            ).apply { bottomMargin = (9 * d).toInt() }
            background = GradientDrawable().apply {
                colors = intArrayOf(COLOR_BG_RAISED, COLOR_BG_CARD)
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = 10 * d
                setStroke((1f * d).toInt().coerceAtLeast(1), Color.argb(115, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
            }
            setPadding((8 * d).toInt(), (6 * d).toInt(), (7 * d).toInt(), (6 * d).toInt())

            addView(ModeFxRailView(context).apply {
                layoutParams = LinearLayout.LayoutParams((4 * d).toInt().coerceAtLeast(3), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = (6 * d).toInt()
                }
            })

            addView(TextView(context).apply {
                text = iconEmoji
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()).apply { rightMargin = (8 * d).toInt() }
                background = GradientDrawable().apply {
                    colors = intArrayOf(Color.argb(55, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)), Color.argb(18, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
                    orientation = GradientDrawable.Orientation.TL_BR
                    cornerRadius = 8 * d
                    setStroke((1f * d).toInt().coerceAtLeast(1), accentColor)
                }
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                addView(TextView(context).apply {
                    text = title.uppercase()
                    textSize = 12f
                    setTextColor(COLOR_TEXT_PRIMARY)
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    letterSpacing = .055f
                    maxLines = 1
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(context).apply {
                        text = "●"
                        textSize = 7f
                        setTextColor(accentColor)
                        setPadding(0, 0, (4 * d).toInt(), 0)
                    })
                    addView(TextView(context).apply {
                        text = badgeText.uppercase()
                        textSize = 8f
                        setTextColor(COLOR_TEXT_MUTED)
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                        maxLines = 1
                    })
                })
            })

            addView(TextView(context).apply {
                text = "×"
                textSize = 18f
                setTextColor(COLOR_TEXT_MUTED)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((32 * d).toInt(), (32 * d).toInt())
                background = GradientDrawable().apply {
                    setColor(COLOR_BG_OBSIDIAN)
                    cornerRadius = 8 * d
                    setStroke((1f * d).toInt().coerceAtLeast(1), COLOR_BORDER_BRIGHT)
                }
                setOnClickListener { onClose() }
            })
        }
    }

    fun buildNeonButton(
        context: Context,
        label: String,
        neonColor: Int = COLOR_CYAN_NEON,
        onClick: () -> Unit
    ): TextView {
        val d = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label.uppercase()
            textSize = 10f
            setTextColor(COLOR_BG_OBSIDIAN)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = .045f
            minHeight = (40 * d).toInt()
            setPadding((12 * d).toInt(), (7 * d).toInt(), (12 * d).toInt(), (7 * d).toInt())
            val normalBg = GradientDrawable().apply {
                colors = intArrayOf(neonColor, COLOR_TELEMETRY)
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = 8 * d
            }
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(50,255,255,255)), normalBg, null)
            setOnClickListener { onClick() }
        }
    }
}
