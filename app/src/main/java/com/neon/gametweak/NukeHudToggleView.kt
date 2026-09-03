package com.neon.gametweak

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Compact HUD control that uses a real Android Switch for boolean features.
 * The shell remains custom-styled so it matches the Nuke cockpit, while the actual state affordance
 * is a native switch instead of a fake painted toggle.
 */
@Suppress("DEPRECATION")
class NukeHudToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val d = resources.displayMetrics.density
    private val icon = ImageView(context)
    private val label = TextView(context)
    private val toggle = Switch(context)
    private var callback: ((Boolean) -> Unit)? = null
    private var internalChange = false
    private var supported = true
    private var busy = false

    init {
        minimumHeight = dp(40)
        isClickable = true
        isFocusable = true
        clipToPadding = false
        setPadding(dp(7), dp(4), dp(5), dp(4))
        background = shellDrawable(false)

        icon.layoutParams = LayoutParams(dp(16), dp(16), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(1)
        }
        icon.setColorFilter(NukeHudPalette.Text)
        addView(icon)

        label.apply {
            setTextColor(NukeHudPalette.Text)
            textSize = 8f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 2
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        label.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(22)
            marginEnd = dp(28)
        }
        addView(label)

        toggle.apply {
            showText = false
            minWidth = dp(26)
            minimumWidth = dp(26)
            minimumHeight = dp(22)
            buttonTintList = null
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(NukeHudPalette.Text, NukeHudPalette.Muted),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.rgb(30, 171, 89), Color.rgb(62, 76, 69)),
            )
            setOnCheckedChangeListener { _, checked ->
                if (internalChange) return@setOnCheckedChangeListener
                if (!supported || busy) {
                    setCheckedSilently(!checked)
                    return@setOnCheckedChangeListener
                }
                callback?.invoke(checked)
            }
        }
        toggle.layoutParams = LayoutParams(dp(28), dp(28), Gravity.END or Gravity.CENTER_VERTICAL)
        addView(toggle)

        setOnClickListener {
            when {
                busy -> Unit
                supported -> toggle.isChecked = !toggle.isChecked
                else -> NukeToast.unsupported(context, Tx.t("${label.text} tidak didukung di perangkat ini", "${label.text} is not supported on this device"))
            }
        }
    }

    fun setIconResource(resId: Int) {
        icon.setImageDrawable(ContextCompat.getDrawable(context, resId)?.mutate())
        icon.setColorFilter(if (supported) NukeHudPalette.Text else NukeHudPalette.MutedDeep)
    }

    fun setLabel(value: String) {
        label.text = value
        contentDescription = value
    }

    fun setOnToggleRequested(listener: (Boolean) -> Unit) {
        callback = listener
    }

    fun setCheckedSilently(value: Boolean) {
        internalChange = true
        toggle.isChecked = value
        internalChange = false
        isActivated = value
        background = shellDrawable(value)
    }

    fun setSupported(value: Boolean) {
        supported = value
        alpha = if (value) 1f else .46f
        toggle.isEnabled = value && !busy
        icon.setColorFilter(if (value) NukeHudPalette.Text else NukeHudPalette.MutedDeep)
        label.setTextColor(if (value) NukeHudPalette.Text else NukeHudPalette.MutedDeep)
    }

    fun setBusy(value: Boolean) {
        busy = value
        toggle.isEnabled = supported && !value
        alpha = when {
            !supported -> .46f
            value -> .72f
            else -> 1f
        }
    }

    fun isChecked(): Boolean = toggle.isChecked

    private fun shellDrawable(on: Boolean): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            if (on) Color.rgb(9, 34, 22) else Color.rgb(7, 17, 13),
            Color.rgb(3, 9, 7),
        ),
    ).apply {
        cornerRadius = dpF(5f)
        setStroke(dp(1), if (on) Color.argb(170, 83, 245, 138) else Color.argb(75, 83, 245, 138))
    }

    private fun dp(v: Int): Int = (v * d).toInt().coerceAtLeast(1)
    private fun dpF(v: Float): Float = v * d
}
