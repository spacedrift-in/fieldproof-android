package com.spacedrift.fieldproof.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic status colors used by status chips, sync indicators and workflow
 * cards. Kept separate from the Material color scheme so status meaning is
 * never implied by color alone and dark mode stays coherent.
 */
@Immutable
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val offline: Color,
    val onOffline: Color,
    val neutral: Color,
    val onNeutral: Color
)

val LightStatusColors = StatusColors(
    success = Color(0xFF1E7A50),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFD0F0DF),
    onSuccessContainer = Color(0xFF083521),
    warning = Color(0xFF8F5A00),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFE8C2),
    onWarningContainer = Color(0xFF2D1B00),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    offline = Color(0xFF545C63),
    onOffline = Color(0xFFFFFFFF),
    neutral = Color(0xFF5A636B),
    onNeutral = Color(0xFFFFFFFF)
)

val DarkStatusColors = StatusColors(
    success = Color(0xFF6BD49C),
    onSuccess = Color(0xFF04371D),
    successContainer = Color(0xFF1E5C3C),
    onSuccessContainer = Color(0xFFD0F0DF),
    warning = Color(0xFFF2B95C),
    onWarning = Color(0xFF3A2700),
    warningContainer = Color(0xFF5E3E00),
    onWarningContainer = Color(0xFFFFE8C2),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    offline = Color(0xFFAEB7BE),
    onOffline = Color(0xFF262B2F),
    neutral = Color(0xFF7A848D),
    onNeutral = Color(0xFF15191C)
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }