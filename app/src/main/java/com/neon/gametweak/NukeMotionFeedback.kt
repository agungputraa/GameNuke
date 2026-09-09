package com.neon.gametweak

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Observes touches without consuming them, preserving button clicks, ripples and scrolling. */
fun Modifier.nukePressFeedback(): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) .94f else 1f, tween(110), label = "button-press")
    this.graphicsLayer { scaleX = scale; scaleY = scale }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            pressed = true
            try { waitForUpOrCancellation(pass = PointerEventPass.Initial) } finally { pressed = false }
        }
    }
}

fun Modifier.nukeCardEntrance(): Modifier = composed {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(if (entered) 1f else 0f, tween(180), label = "card-entrance")
    this.graphicsLayer { alpha = progress; translationY = (1f - progress) * size.height / 3f }
}

fun android.view.View.installNukePressFeedback() {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(.94f).scaleY(.94f).setDuration(110).start()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        }
        false
    }
}
