package com.andropadpro.client.theme

import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.Button
import androidx.annotation.ColorInt
import com.andropadpro.client.view.BackgroundMediaView
import com.andropadpro.client.view.DPadView
import com.andropadpro.client.view.FaceButtonsView
import com.andropadpro.client.view.JoystickView
import com.andropadpro.client.view.TriggerView

@Suppress("UnstableApiUsage")
object ThemeManager {

    private const val PREFS        = "AndroPadPro"
    private const val KEY_THEME_ID = "theme_id"
    private const val KEY_ACCENT   = "accent_override"
    private const val KEY_BG_URI   = "bg_image_uri"

    // ── Persistence ───────────────────────────────────────────────────────────

    fun save(context: Context, theme: GamepadTheme, @ColorInt accentOverride: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME_ID, theme.id)
            .putInt(KEY_ACCENT, accentOverride)
            .apply()
    }

    fun saveBgUri(context: Context, uri: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BG_URI, uri)
            .apply()
    }

    /**
     * Returns (theme, accentColor) with per-element opacity merged from prefs.
     * Master opacity multiplies each individual control group value.
     */
    fun load(context: Context): Pair<GamepadTheme, Int> {
        val prefs  = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id     = prefs.getString(KEY_THEME_ID, "xbox") ?: "xbox"
        val base   = GamepadThemes.findById(id)
        val accent = prefs.getInt(KEY_ACCENT, base.accentColor)
        val bgUri  = prefs.getString(KEY_BG_URI, null)
        val master = prefs.getFloat("opacity_master", 1.0f).coerceIn(0f, 1f)

        fun op(key: String, default: Float) =
            (prefs.getFloat(key, default).coerceIn(0f, 1f) * master).coerceIn(0f, 1f)

        val theme = base.copy(
            backgroundImageUri   = bgUri,
            opacityJoysticks     = op("opacity_joysticks",      1.0f),
            opacityDpad          = op("opacity_dpad",           1.0f),
            opacityFaceButtons   = op("opacity_face_buttons",   1.0f),
            opacityTriggers      = op("opacity_triggers",       1.0f),
            opacityShoulders     = op("opacity_shoulders",      1.0f),
            opacityCenterButtons = op("opacity_center_buttons", 1.0f),
            // Background opacity is independent of the master control slider
            opacityBackground    = prefs.getFloat("opacity_background", 0.85f).coerceIn(0f, 1f)
        )
        return theme to accent
    }

    // ── Application ───────────────────────────────────────────────────────────

    /**
     * Apply [theme] to all live views.
     *
     * [backgroundView] is the full-screen [BackgroundMediaView] that sits behind
     * the controls. It will show the static image or video from the theme URI,
     * or be left in SOLID mode if no URI is configured.
     *
     * Call this on every [onResume] to pick up opacity changes.
     */
    fun apply(
        context: Context,
        theme: GamepadTheme,
        @ColorInt accentOverride: Int,
        backgroundView: BackgroundMediaView,
        ltTrigger: TriggerView,
        rtTrigger: TriggerView,
        dpad: DPadView,
        leftStick: JoystickView,
        rightStick: JoystickView,
        faceButtons: FaceButtonsView,
        btnLB: Button,
        btnRB: Button,
        btnTouchpad: Button
    ) {
        // ── Background layer ─────────────────────────────────────────────────
        val bgUri = theme.backgroundImageUri
        // Only update background when NOT in screen-stream mode
        if (backgroundView.getCurrentMode() != BackgroundMediaView.Mode.SCREEN_STREAM) {
            if (bgUri != null) {
                val mime = mimeTypeOf(context, bgUri)
                if (mime?.startsWith("video/") == true) {
                    backgroundView.showVideo(bgUri)
                } else {
                    backgroundView.showStaticImage(bgUri)
                }
            } else {
                backgroundView.showSolid(theme.backgroundColor)
            }
        }
        backgroundView.alpha = theme.opacityBackground

        // ── Trigger views ────────────────────────────────────────────────────
        ltTrigger.setColors(theme.triggerColor, accentOverride)
        ltTrigger.setLabel("LT")
        rtTrigger.setColors(theme.triggerColor, accentOverride)
        rtTrigger.setLabel("RT")
        ltTrigger.alpha = theme.opacityTriggers
        rtTrigger.alpha = theme.opacityTriggers

        // ── D-Pad ────────────────────────────────────────────────────────────
        dpad.setColors(theme.dpadColor, theme.dpadPressedColor)
        dpad.alpha = theme.opacityDpad

        // ── Joysticks ────────────────────────────────────────────────────────
        leftStick.setColors(theme.stickBaseColor, theme.stickBorderColor, theme.stickKnobColor, accentOverride)
        rightStick.setColors(theme.stickBaseColor, theme.stickBorderColor, theme.stickKnobColor, accentOverride)
        leftStick.alpha  = theme.opacityJoysticks
        rightStick.alpha = theme.opacityJoysticks

        // ── Face buttons ─────────────────────────────────────────────────────
        faceButtons.setColors(theme.faceButtonA, theme.faceButtonB, theme.faceButtonX, theme.faceButtonY, theme.faceTextColor)
        faceButtons.alpha = theme.opacityFaceButtons

        // ── Shoulder buttons ─────────────────────────────────────────────────
        btnLB.setBackgroundColor(accentOverride); btnLB.setTextColor(theme.accentTextColor)
        btnRB.setBackgroundColor(accentOverride); btnRB.setTextColor(theme.accentTextColor)
        btnLB.alpha = theme.opacityShoulders
        btnRB.alpha = theme.opacityShoulders

        // ── Center / touchpad buttons ─────────────────────────────────────────
        btnTouchpad.setBackgroundColor(accentOverride)
        btnTouchpad.setTextColor(theme.accentTextColor)
        btnTouchpad.alpha = theme.opacityCenterButtons
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun mimeTypeOf(context: Context, uriString: String): String? =
        try { context.contentResolver.getType(Uri.parse(uriString)) } catch (_: Exception) { null }
}
