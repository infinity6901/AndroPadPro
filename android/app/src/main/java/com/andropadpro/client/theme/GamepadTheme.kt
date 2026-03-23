package com.andropadpro.client.theme

import android.graphics.Color
import androidx.annotation.ColorInt

/**
 * Immutable description of a controller visual skin.
 *
 * Every opacity field is a Float in 0.0–1.0 that maps directly to View.alpha.
 * Defaults leave all controls fully opaque; users can dial them down via Settings.
 */
data class GamepadTheme(
    val id: String,
    val displayName: String,
    @ColorInt val backgroundColor: Int,
    @ColorInt val triggerColor: Int,
    @ColorInt val dpadColor: Int,
    @ColorInt val dpadPressedColor: Int,
    @ColorInt val stickBaseColor: Int,
    @ColorInt val stickBorderColor: Int,
    @ColorInt val stickKnobColor: Int,
    @ColorInt val accentColor: Int,
    @ColorInt val accentTextColor: Int,
    @ColorInt val faceButtonA: Int,
    @ColorInt val faceButtonB: Int,
    @ColorInt val faceButtonX: Int,
    @ColorInt val faceButtonY: Int,
    @ColorInt val faceTextColor: Int,
    @ColorInt val centerButtonColor: Int,
    @ColorInt val bottomButtonColor: Int,
    @ColorInt val hudBackground: Int        = Color.parseColor("#99000000"),
    @ColorInt val hudTextColor: Int         = Color.WHITE,
    val backgroundImageUri: String?         = null,

    // ── Per-element opacity (0.0 transparent → 1.0 opaque) ───────────────────
    val opacityJoysticks:     Float = 1.0f,
    val opacityDpad:          Float = 1.0f,
    val opacityFaceButtons:   Float = 1.0f,
    val opacityTriggers:      Float = 1.0f,
    val opacityShoulders:     Float = 1.0f,
    val opacityCenterButtons: Float = 1.0f,
    /** Background image / video layer opacity. */
    val opacityBackground:    Float = 0.85f
)

/** All built-in presets. */
object GamepadThemes {

    val XBOX = GamepadTheme(
        id = "xbox",
        displayName = "Xbox",
        backgroundColor   = Color.parseColor("#1a1a1a"),
        triggerColor      = Color.parseColor("#2d2d2d"),
        dpadColor         = Color.parseColor("#383838"),
        dpadPressedColor  = Color.parseColor("#107C10"),
        stickBaseColor    = Color.parseColor("#252525"),
        stickBorderColor  = Color.parseColor("#555555"),
        stickKnobColor    = Color.parseColor("#555555"),
        accentColor       = Color.parseColor("#107C10"),
        accentTextColor   = Color.WHITE,
        faceButtonA       = Color.parseColor("#107C10"),
        faceButtonB       = Color.parseColor("#D0393B"),
        faceButtonX       = Color.parseColor("#106EBE"),
        faceButtonY       = Color.parseColor("#FFBA00"),
        faceTextColor     = Color.WHITE,
        centerButtonColor = Color.parseColor("#2d2d2d"),
        bottomButtonColor = Color.parseColor("#222222")
    )

    val PLAYSTATION = GamepadTheme(
        id = "ps",
        displayName = "PlayStation",
        backgroundColor   = Color.parseColor("#0a1929"),
        triggerColor      = Color.parseColor("#0f2744"),
        dpadColor         = Color.parseColor("#152e4a"),
        dpadPressedColor  = Color.parseColor("#0078D4"),
        stickBaseColor    = Color.parseColor("#0d2035"),
        stickBorderColor  = Color.parseColor("#1e4d6b"),
        stickKnobColor    = Color.parseColor("#1a3d5c"),
        accentColor       = Color.parseColor("#0078D4"),
        accentTextColor   = Color.WHITE,
        faceButtonA       = Color.parseColor("#c0392b"),
        faceButtonB       = Color.parseColor("#c27c0e"),
        faceButtonX       = Color.parseColor("#1a6faf"),
        faceButtonY       = Color.parseColor("#1a8a4e"),
        faceTextColor     = Color.WHITE,
        centerButtonColor = Color.parseColor("#0d2035"),
        bottomButtonColor = Color.parseColor("#060e1a")
    )

    val MINIMAL = GamepadTheme(
        id = "minimal",
        displayName = "Minimal",
        backgroundColor   = Color.parseColor("#f0eeea"),
        triggerColor      = Color.parseColor("#dddbd6"),
        dpadColor         = Color.parseColor("#d0cec8"),
        dpadPressedColor  = Color.parseColor("#888888"),
        stickBaseColor    = Color.parseColor("#e8e6e2"),
        stickBorderColor  = Color.parseColor("#bbbbbb"),
        stickKnobColor    = Color.parseColor("#cccccc"),
        accentColor       = Color.parseColor("#888888"),
        accentTextColor   = Color.WHITE,
        faceButtonA       = Color.parseColor("#888888"),
        faceButtonB       = Color.parseColor("#888888"),
        faceButtonX       = Color.parseColor("#888888"),
        faceButtonY       = Color.parseColor("#888888"),
        faceTextColor     = Color.WHITE,
        centerButtonColor = Color.parseColor("#dddbd8"),
        bottomButtonColor = Color.parseColor("#e2e0db"),
        hudBackground     = Color.parseColor("#99ffffff"),
        hudTextColor      = Color.parseColor("#333333")
    )

    val DARK_CARBON = GamepadTheme(
        id = "dark",
        displayName = "Dark Carbon",
        backgroundColor   = Color.parseColor("#0d0d0d"),
        triggerColor      = Color.parseColor("#191919"),
        dpadColor         = Color.parseColor("#1f1f1f"),
        dpadPressedColor  = Color.parseColor("#444444"),
        stickBaseColor    = Color.parseColor("#161616"),
        stickBorderColor  = Color.parseColor("#303030"),
        stickKnobColor    = Color.parseColor("#252525"),
        accentColor       = Color.parseColor("#333333"),
        accentTextColor   = Color.parseColor("#aaaaaa"),
        faceButtonA       = Color.parseColor("#2a2a2a"),
        faceButtonB       = Color.parseColor("#2a2a2a"),
        faceButtonX       = Color.parseColor("#2a2a2a"),
        faceButtonY       = Color.parseColor("#2a2a2a"),
        faceTextColor     = Color.parseColor("#888888"),
        centerButtonColor = Color.parseColor("#181818"),
        bottomButtonColor = Color.parseColor("#141414")
    )

    val ALL = listOf(XBOX, PLAYSTATION, MINIMAL, DARK_CARBON)

    fun findById(id: String): GamepadTheme = ALL.firstOrNull { it.id == id } ?: XBOX
}
