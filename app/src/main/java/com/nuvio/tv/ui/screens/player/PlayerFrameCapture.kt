package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Grabs the current video frame and returns a small, heavily blurred copy of it.
 *
 * Compose's blur cannot reach into a SurfaceView, so the rating window blurs a still
 * frame instead: PixelCopy reads the video surface into a tiny bitmap, a few box-blur
 * passes soften it, and the UI stretches it full-screen (bilinear upscaling of a tiny
 * image blurs it further). Returns null for protected/tunneled surfaces, where PixelCopy
 * fails — the window then falls back to a plain dim.
 */
internal object PlayerFrameCapture {
    private const val TAG = "PlayerFrameCapture"
    private const val CAPTURE_WIDTH = 192
    private const val CAPTURE_HEIGHT = 108
    private const val BLUR_RADIUS = 4
    private const val BLUR_PASSES = 3

    suspend fun captureBlurredFrame(videoView: View?): Bitmap? {
        val raw = when (videoView) {
            is SurfaceView -> copySurface(videoView)
            is TextureView -> runCatching { videoView.getBitmap(CAPTURE_WIDTH, CAPTURE_HEIGHT) }.getOrNull()
            else -> null
        } ?: return null
        return withContext(Dispatchers.Default) {
            runCatching { boxBlur(raw, BLUR_RADIUS, BLUR_PASSES) }.getOrNull()
        }
    }

    private suspend fun copySurface(view: SurfaceView): Bitmap? {
        val surface = view.holder?.surface
        if (surface == null || !surface.isValid || view.width <= 0 || view.height <= 0) return null
        val bitmap = Bitmap.createBitmap(CAPTURE_WIDTH, CAPTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        val result = withTimeoutOrNull(1_000L) {
            suspendCancellableCoroutine { cont ->
                try {
                    PixelCopy.request(view, bitmap, { code -> if (cont.isActive) cont.resume(code) },
                        Handler(Looper.getMainLooper()))
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "PixelCopy rejected the surface", e)
                    if (cont.isActive) cont.resume(PixelCopy.ERROR_SOURCE_INVALID)
                }
            }
        }
        Log.d(TAG, "PixelCopy result=$result")
        return bitmap.takeIf { result == PixelCopy.SUCCESS && !it.isMostlyBlack() }
    }

    /** A black copy usually means a protected surface; treat it as a failed capture. */
    private fun Bitmap.isMostlyBlack(): Boolean {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        var bright = 0
        for (p in pixels) {
            val luma = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
            if (luma > 24) bright++
        }
        return bright < pixels.size / 50
    }

    /** Separable box blur on ARGB pixels; [passes] of it approximate a gaussian. */
    internal fun boxBlur(source: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = source.width
        val h = source.height
        var pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        var buffer = IntArray(w * h)
        repeat(passes) {
            blurLine(pixels, buffer, w, h, radius, horizontal = true)
            blurLine(buffer, pixels, w, h, radius, horizontal = false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            fun index(i: Int): Int {
                val c = i.coerceIn(0, length - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = src[index(i)]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (i in 0 until length) {
                dst[index(i)] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val outP = src[index(i - radius)]
                val inP = src[index(i + radius + 1)]
                r += ((inP shr 16) and 0xFF) - ((outP shr 16) and 0xFF)
                g += ((inP shr 8) and 0xFF) - ((outP shr 8) and 0xFF)
                b += (inP and 0xFF) - (outP and 0xFF)
            }
        }
    }
}
