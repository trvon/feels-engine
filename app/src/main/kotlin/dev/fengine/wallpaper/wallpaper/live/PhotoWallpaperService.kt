package dev.fengine.wallpaper.wallpaper.live

import android.animation.ValueAnimator
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.view.animation.LinearInterpolator
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.wallpaper.WallpaperCrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The live engine: draws the current group's photo itself, with a cross-fade on change, parallax
 * from the launcher's page offset, the system zoom on transitions, and a fresh decode whenever the
 * surface changes size (fold, unfold, rotation).
 *
 * Efficiency rule: zero frames at rest. The engine draws only when something changed: a new photo
 * (one short animation), an offset or zoom event (one frame each), a surface or visibility change.
 * There is no render loop. Bitmaps are decoded once per surface size at the largest size any frame
 * can need, on the IO dispatcher, as hardware bitmaps when the surface gives a hardware canvas.
 */
class PhotoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = PhotoEngine()

    inner class PhotoEngine : Engine() {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var watch: Job? = null
        private var load: Job? = null
        private var current: Bitmap? = null
        private var previous: Bitmap? = null
        private var currentUri: String? = null
        private var fade: ValueAnimator? = null
        private var fadeAlpha = 1f
        private var xOffset = 0.5f
        private var zoom = 0f
        private var width = 0
        private var height = 0
        private var visible = false
        private var dirty = true
        private var softwareOnly = false
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()
        private var frames = 0L
        private var fadeFrames = 0L
        private var fadeStart = 0L

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setOffsetNotificationsEnabled(true)
            watch = scope.launch {
                (application as FengineApp).settings.settings.collectLatest { settings ->
                    val uri = photoFor(settings)
                    if (uri != currentUri) show(uri)
                }
            }
        }

        /** The photo this screen should show: its group's cursor photo, or the first before any rotation. */
        private fun photoFor(settings: Settings): String? {
            val lock = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                wallpaperFlags and WallpaperManager.FLAG_LOCK != 0 && wallpaperFlags and WallpaperManager.FLAG_SYSTEM == 0
            val group = (if (lock) settings.lockGroup else settings.homeGroup) ?: settings.homeGroup ?: settings.groups.firstOrNull()
            val photos = group?.allPhotos.orEmpty()
            if (photos.isEmpty()) return null
            return photos[group!!.cursor.coerceIn(0, photos.size - 1)]
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            if (w == width && h == height) return
            width = w; height = h
            Log.i(TAG, "surface ${w}x$h")
            // A new size needs a decode at that size; the old bitmap bridges the gap.
            currentUri?.let { show(it, keepOld = true) }
        }

        override fun onOffsetsChanged(xOff: Float, yOff: Float, xStep: Float, yStep: Float, xPx: Int, yPx: Int) {
            if (xOff == xOffset) return
            xOffset = xOff
            draw()
        }

        override fun onZoomChanged(z: Float) {
            if (z == zoom) return
            zoom = z
            draw()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (!v) { fade?.cancel(); finishFade() } else if (dirty) draw()
        }

        override fun onWallpaperFlagsChanged(which: Int) {
            super.onWallpaperFlagsChanged(which)
            scope.launch { show(photoFor((application as FengineApp).settings.read())) }
        }

        override fun onDestroy() {
            fade?.cancel(); load?.cancel(); watch?.cancel(); scope.cancel()
            current?.recycle(); previous?.recycle(); current = null; previous = null
            super.onDestroy()
        }

        /** Decode [uri] for the current surface off the main thread, then cross-fade to it. */
        private fun show(uri: String?, keepOld: Boolean = false) {
            load?.cancel()
            if (uri == null) { currentUri = null; current = null; draw(); return }
            if (width == 0 || height == 0) { currentUri = uri; return }
            load = scope.launch {
                val bitmap = withContext(Dispatchers.IO) { decode(uri) } ?: return@launch
                fade?.cancel()
                previous?.recycle()
                previous = if (keepOld) null else current
                current = bitmap
                currentUri = uri
                if (previous != null && visible) startFade() else { fadeAlpha = 1f; draw() }
            }
        }

        private fun decode(uri: String): Bitmap? = runCatching {
            val (tw, th) = LiveFrame.decodeTarget(width, height)
            val source = ImageDecoder.createSource(contentResolver, Uri.parse(uri))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setTargetSampleSize(WallpaperCrop.sampleSize(info.size.width, info.size.height, tw, th))
                decoder.allocator = if (softwareOnly) ImageDecoder.ALLOCATOR_SOFTWARE else ImageDecoder.ALLOCATOR_DEFAULT
            }
        }.onFailure { Log.w(TAG, "decode failed for $uri", it) }.getOrNull()

        private fun startFade() {
            fadeAlpha = 0f
            fadeFrames = 0; fadeStart = android.os.SystemClock.elapsedRealtime()
            fade = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FADE_MS
                interpolator = LinearInterpolator()
                addUpdateListener { a -> fadeAlpha = LiveFrame.fadeAlpha(a.animatedValue as Float); draw() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = finishFade()
                })
                start()
            }
        }

        private fun finishFade() {
            if (fade != null) Log.i(TAG, "fade: $fadeFrames frames in ${android.os.SystemClock.elapsedRealtime() - fadeStart} ms")
            fade = null
            fadeAlpha = 1f
            previous?.recycle(); previous = null
        }

        /** One frame. Skipped while invisible; the next visibility change draws the pending state. */
        private fun draw() {
            if (!visible) { dirty = true; return }
            val holder = surfaceHolder
            val canvas = (if (softwareOnly) null else runCatching { holder.lockHardwareCanvas() }.getOrNull())
                ?: run { softwareOnly = true; holder.lockCanvas() } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                previous?.let { if (fadeAlpha < 1f) drawPhoto(canvas, it, 255) }
                current?.let { drawPhoto(canvas, it, (fadeAlpha * 255).toInt()) }
                frames++
                if (fade != null) fadeFrames++ else Log.d(TAG, "frame ${frames}: offset=$xOffset zoom=$zoom")
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            dirty = false
        }

        private fun drawPhoto(canvas: Canvas, bitmap: Bitmap, alpha: Int) {
            if (bitmap.isRecycled) return
            val f = LiveFrame.frame(bitmap.width, bitmap.height, width, height, xOffset, zoom)
            matrix.reset(); matrix.postScale(f.scale, f.scale); matrix.postTranslate(f.dx, f.dy)
            paint.alpha = alpha
            canvas.drawBitmap(bitmap, matrix, paint)
        }
    }

    companion object {
        const val TAG = "PhotoWallpaper"
        const val FADE_MS = 900L
    }
}
