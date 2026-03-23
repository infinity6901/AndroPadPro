package com.andropadpro.client.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Full-screen background layer that supports three mutually exclusive modes:
 *
 * 1. [Mode.SOLID]         — Solid color (no image/video). Default.
 * 2. [Mode.STATIC_IMAGE]  — Static image from a content:// or file:// URI.
 * 3. [Mode.VIDEO_LOOP]    — Muted looping video via ExoPlayer (MP4, WebM, …).
 * 4. [Mode.SCREEN_STREAM] — Live bitmap frames pushed by [pushStreamFrame].
 *
 * Screen stream takes priority over video/image when active — call
 * [setMode] to switch. The view automatically cleans up ExoPlayer on detach.
 *
 * Opacity for the entire background layer is controlled via [View.alpha].
 * ThemeManager sets it from the user's opacity_background preference.
 */
@Suppress("UnstableApiUsage")
class BackgroundMediaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    enum class Mode { SOLID, STATIC_IMAGE, VIDEO_LOOP, SCREEN_STREAM }

    private val tag = "BackgroundMediaView"

    // ── Child views ───────────────────────────────────────────────────────────

    private val imageView = ImageView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        scaleType    = ImageView.ScaleType.CENTER_CROP
    }

    private var playerView: PlayerView? = null
    private var exoPlayer:  ExoPlayer?  = null

    // Stream mode uses the same ImageView but updates it per-frame
    private var currentMode: Mode = Mode.SOLID

    init {
        setBackgroundColor(Color.BLACK)
        addView(imageView)
    }

    // ── Public mode setters ───────────────────────────────────────────────────

    /**
     * Show a static image from [uri] (content:// or file:// string).
     * If the URI fails to load, the view stays as a solid black background.
     */
    fun showStaticImage(uri: String) {
        releasePlayer()
        imageView.visibility = VISIBLE
        try {
            val stream = context.contentResolver.openInputStream(Uri.parse(uri)) ?: return
            val bmp    = BitmapFactory.decodeStream(stream)
            stream.close()
            imageView.setImageBitmap(bmp)
            currentMode = Mode.STATIC_IMAGE
        } catch (e: Exception) {
            Log.w(tag, "Failed to load image: ${e.message}")
            imageView.setImageDrawable(null)
            currentMode = Mode.SOLID
        }
    }

    /**
     * Loop a muted video from [uri] using ExoPlayer.
     * Falls back to [showStaticImage] if the URI appears to be an image,
     * or to a solid background if ExoPlayer fails.
     */
    fun showVideo(uri: String) {
        // Detect if URI is actually an image and fall back
        val mime = try {
            context.contentResolver.getType(Uri.parse(uri))
        } catch (_: Exception) { null }

        if (mime != null && !mime.startsWith("video/")) {
            showStaticImage(uri)
            return
        }

        try {
            releasePlayer()

            val pv = PlayerView(context).apply {
                layoutParams     = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                useController    = false
                resizeMode       = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            addView(pv, 0)
            playerView = pv

            val player = ExoPlayer.Builder(context).build().also {
                it.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
                it.repeatMode   = Player.REPEAT_MODE_ONE
                it.volume       = 0f     // muted — audio comes from AudioStreamClient
                it.playWhenReady = true
                it.prepare()
            }
            pv.player  = player
            exoPlayer  = player

            imageView.visibility = GONE
            currentMode = Mode.VIDEO_LOOP

        } catch (e: Exception) {
            Log.w(tag, "ExoPlayer failed: ${e.message} — falling back to image")
            showStaticImage(uri)
        }
    }

    /**
     * Push a decoded bitmap from the screen stream directly into the image view.
     * Must be called on the **main thread** (post from ScreenStreamClient callback).
     */
    // Keep track of the last stream bitmap so we can recycle it properly.
    // We clear the ImageView first (releases its reference), THEN recycle the old bitmap,
    // THEN set the new one. This prevents ImageView from holding a recycled bitmap.
    private var lastStreamBitmap: Bitmap? = null

    fun pushStreamFrame(bmp: Bitmap) {
        if (currentMode != Mode.SCREEN_STREAM) return
        // 1. Clear ImageView reference to the old bitmap
        val old = lastStreamBitmap
        // 2. Set new bitmap first so the view is never blank
        imageView.setImageBitmap(bmp)
        // 3. Now safe to recycle the old one — ImageView no longer holds it
        if (old != null && !old.isRecycled) old.recycle()
        lastStreamBitmap = bmp
    }

    /** Switch to screen-stream mode. Stops video if playing. */
    fun setMode(mode: Mode) {
        when (mode) {
            Mode.SCREEN_STREAM -> {
                releasePlayer()
                imageView.visibility = VISIBLE
                imageView.setImageDrawable(null)
                currentMode = Mode.SCREEN_STREAM
            }
            Mode.SOLID -> {
                releasePlayer()
                imageView.setImageDrawable(null)
                lastStreamBitmap?.let { if (!it.isRecycled) it.recycle() }
                lastStreamBitmap = null
                imageView.visibility = VISIBLE
                currentMode = Mode.SOLID
            }
            else -> { /* use showStaticImage / showVideo */ }
        }
    }

    /** Clears to a solid color (useful when no URI is configured). */
    fun showSolid(color: Int = Color.BLACK) {
        releasePlayer()
        imageView.setImageDrawable(null)
        imageView.visibility = VISIBLE
        setBackgroundColor(color)
        currentMode = Mode.SOLID
    }

    // ── ExoPlayer lifecycle ───────────────────────────────────────────────────

    fun pausePlayback() {
        exoPlayer?.pause()
    }

    fun resumePlayback() {
        exoPlayer?.play()
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
        playerView?.let { removeView(it) }
        playerView = null
        imageView.visibility = VISIBLE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releasePlayer()
    }

    fun getCurrentMode(): Mode = currentMode
}
