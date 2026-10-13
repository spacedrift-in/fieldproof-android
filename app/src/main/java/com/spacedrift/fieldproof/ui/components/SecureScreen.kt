package com.spacedrift.fieldproof.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Marks the current window as secure while the composable is on screen.
 *
 * Android then excludes the window content from screenshots, screen recording
 * and the recent-apps thumbnail, which stops captured evidence from leaking
 * through the OS surface without an explicit user share. The flag is cleared
 * when the screen leaves composition.
 */
@Composable
fun SecureScreen() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
