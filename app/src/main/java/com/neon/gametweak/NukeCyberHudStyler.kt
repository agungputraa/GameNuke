package com.neon.gametweak

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * NukeCyberHudStyler — High-Tech Tactical Cyberpunk HUD UI Design System.
 *
 * Provides reusable canvas-drawn textured pattern backgrounds, tactical corner
 * brackets, holographic glassmorphic cards, and sleek esports badges.
 *
 * Developer: Agung Developer
 */
object NukeCyberHudStyler {

    // Cyberpunk Tactical Color Palette
    const val COLOR_BG_OBSIDIAN = 0xFF080C11.toInt()
    const val COLOR_BG_CARD = 0xFF0D141D.toInt()
    const val COLOR_BG_CARD_ALT = 0xFF111A24.toInt()
    const val COLOR_CYAN_NEON = 0xFF00F0FF.toInt()
    const val COLOR_CYAN_DIM = 0xFF007A82.toInt()
    const val COLOR_EMERALD_NEON = 0xFF00FF9D.toInt()
    const val COLOR_EMERALD_DIM = 0xFF00663E.toInt()
    const val COLOR_CRIMSON_NEON = 0xFFFF0055.toInt()
    const val COLOR_AMBER_NEON = 0xFFFFB300.toInt()
    const val COLOR_TEXT_PRIMARY = 0xFFECEFF4.toInt()
    const val COLOR_TEXT_MUTED = 0xFF7E8B9B.toInt()
    const val COLOR_BORDER_SUBTLE = 0xFF1C2A38.toInt()

    /**
     * Custom Canvas Drawable that draws an Obsidian base with a subtle technical dot-grid
     * pattern and tactical L-shaped HUD corner brackets.
     */
    class TacticalPanelDrawable(
        private val density: Float,
        private val cornerRadiusPx: Float,
        private val strokeColor: Int = COLOR_CYAN_NEON,
        private val bgColor: Int = COLOR_BG_OBSIDIAN,
        private val showGrid: Boolean = true,
        private val showBrackets: Boolean = true
    ) : Drawable() {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_BORDER_SUBTLE
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }

        private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.SQUARE
        }

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(16, 0, 240, 255) // Ultra subtle neon cyan dots
            style = Paint.Style.FILL
        }

        private val boundsRect = RectF()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            boundsRect.set(bounds)
        }

        override fun draw(canvas: Canvas) {
            val r = boundsRect
            // 1. Draw rounded background
            canvas.drawRoundRect(r, cornerRadiusPx, cornerRadiusPx, bgPaint)

            // 2. Draw subtle technical dot grid pattern
            if (showGrid) {
                val step = 14f * density
                val dotSize = 1.0f * density
                var x = r.left + step
                while (x < r.right) {
                    var y = r.top + step
                    while (y < r.bottom) {
                        canvas.drawCircle(x, y, dotSize, gridPaint)
                        y += step
                    }
                    x += step
                }
            }

            // 3. Draw subtle panel border
            canvas.drawRoundRect(r, cornerRadiusPx, cornerRadiusPx, borderPaint)

            // 4. Draw Tactical Military Corner Brackets
            if (showBrackets) {
                val blen = 16f * density
                val pad = 4f * density

                // Top-Left [
                canvas.drawLine(r.left + pad, r.top + pad + blen, r.left + pad, r.top + pad, bracketPaint)
                canvas.drawLine(r.left + pad, r.top + pad, r.left + pad + blen, r.top + pad, bracketPaint)

                // Top-Right ]
                canvas.drawLine(r.right - pad - blen, r.top + pad, r.right - pad, r.top + pad, bracketPaint)
                canvas.drawLine(r.right - pad, r.top + pad, r.right - pad, r.top + pad + blen, bracketPaint)

                // Bottom-Left [
                canvas.drawLine(r.left + pad, r.bottom - pad - blen, r.left + pad, r.bottom - pad, bracketPaint)
                canvas.drawLine(r.left + pad, r.bottom - pad, r.left + pad + blen, r.bottom - pad, bracketPaint)

                // Bottom-Right ]
                canvas.drawLine(r.right - pad - blen, r.bottom - pad, r.right - pad, r.bottom - pad, bracketPaint)
                canvas.drawLine(r.right - pad, r.bottom - pad, r.right - pad, r.bottom - pad - blen, bracketPaint)
            }
        }

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bgPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * Builds a glassmorphic gradient card background with subtle inner glow.
     */
    fun buildCardBackground(
        density: Float,
        cornerRadiusDp: Float = 10f,
        strokeColor: Int = COLOR_BORDER_SUBTLE,
        fillColor: Int = COLOR_BG_CARD
    ): Drawable {
        return GradientDrawable().apply {
            colors = intArrayOf(
                Color.argb(240, (fillColor shr 16) and 0xFF, (fillColor shr 8) and 0xFF, fillColor and 0xFF),
                Color.argb(255, (COLOR_BG_OBSIDIAN shr 16) and 0xFF, (COLOR_BG_OBSIDIAN shr 8) and 0xFF, COLOR_BG_OBSIDIAN and 0xFF)
            )
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            cornerRadius = cornerRadiusDp * density
            setStroke((1f * density).toInt(), strokeColor)
        }
    }

    /**
     * Builds an Esports Tactical Header with illuminated icon, status badge, title, and sleek close button.
     */
    fun buildHeader(
        context: Context,
        title: String,
        badgeText: String,
        iconEmoji: String = "⚡",
        accentColor: Int = COLOR_CYAN_NEON,
        onClose: () -> Unit
    ): LinearLayout {
        val d = context.resources.displayMetrics.density

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * d).toInt()
            }
            background = GradientDrawable().apply {
                colors = intArrayOf(Color.parseColor("#152332"), Color.parseColor("#0C151F"))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = 8 * d
                setStroke((1f * d).toInt(), Color.argb(120, (accentColor shr 16) and 0xFF, (accentColor shr 8) and 0xFF, accentColor and 0xFF))
            }
            setPadding((10 * d).toInt(), (7 * d).toInt(), (8 * d).toInt(), (7 * d).toInt())
        }

        // Icon Box
        val iconTv = TextView(context).apply {
            text = iconEmoji
            textSize = 15f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()).apply {
                rightMargin = (8 * d).toInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.argb(50, (accentColor shr 16) and 0xFF, (accentColor shr 8) and 0xFF, accentColor and 0xFF))
                cornerRadius = 6 * d
                setStroke((1f * d).toInt(), accentColor)
            }
        }
        header.addView(iconTv)

        // Title Column
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleTv = TextView(context).apply {
            text = title.uppercase()
            textSize = 12.5f
            setTextColor(COLOR_TEXT_PRIMARY)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.05f
        }
        titleCol.addView(titleTv)

        val badgeTv = TextView(context).apply {
            text = badgeText
            textSize = 8.5f
            setTextColor(accentColor)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        titleCol.addView(badgeTv)

        header.addView(titleCol)

        // Sleek Close Button
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1B2735"))
                cornerRadius = 13 * d
                setStroke((1f * d).toInt(), Color.parseColor("#2F4156"))
            }
            setOnClickListener { onClose() }
        }
        header.addView(closeBtn)

        return header
    }

    /**
     * Builds a neon action button with high-contrast tactical styling.
     */
    fun buildNeonButton(
        context: Context,
        label: String,
        neonColor: Int = COLOR_CYAN_NEON,
        onClick: () -> Unit
    ): TextView {
        val d = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label.uppercase()
            textSize = 10.5f
            setTextColor(Color.parseColor("#080C10"))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            setPadding((12 * d).toInt(), (7 * d).toInt(), (12 * d).toInt(), (7 * d).toInt())

            val normalBg = GradientDrawable().apply {
                colors = intArrayOf(neonColor, Color.argb(200, (neonColor shr 16) and 0xFF, (neonColor shr 8) and 0xFF, neonColor and 0xFF))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                cornerRadius = 6 * d
            }
            background = RippleDrawable(ColorStateList.valueOf(Color.WHITE), normalBg, null)
            setOnClickListener { onClick() }
        }
    }
}
