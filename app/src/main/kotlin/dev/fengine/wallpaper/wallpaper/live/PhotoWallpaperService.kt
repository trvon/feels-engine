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
import android.os.Handler
import android.os.HandlerThread
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
 *
 * Threading: every state change and every draw happens on one render thread, never on the main
 * thread. A GPU stall in a canvas post (seen on emulators) then costs frames, not the process:
 * the main thread stays free for WorkManager jobs and binder calls. The engine callbacks arrive
 * on the main thread and are handed over with a post.
 */
class PhotoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = PhotoEngine()

    inner class PhotoEngine : Engine() {
        private val renderThread = HandlerThread("feels-render", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        private val render = Handler(renderThread.looper)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        /** Touched only on the render thread. */
        private val state = LiveEngineState()
        private var watch: Job? = null
        private var load: Job? = null
        private var current: Bitmap? = null
        private var previous: Bitmap? = null
        private var fade: ValueAnimator? = null
        private var fadeAlpha = 1f
        private var softwareOnly = false
        private var slowPosts = 0
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()
        private var frames = 0L
        private var fadeFrames = 0L
        private var fadeStart = 0L

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) wallpaperFlags else -1
            Log.i(TAG, "engine ${System.identityHashCode(this)} created, preview=$isPreview flags=$flags display=${displayContext?.display?.displayId}")
            setOffsetNotificationsEnabled(true)
            watch = scope.launch {
                (application as FengineApp).settings.settings.collectLatest { settings ->
                    val uri = photoFor(settings)
                    render.post { act(state.onPhoto(uri)) }
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
            render.post { if (w != state.width || h != state.height) Log.i(TAG, "surface ${w}x$h"); act(state.onSurface(w, h)) }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            // Finish any draw in flight before the surface goes away.
            val done = java.util.concurrent.CountDownLatch(1)
            render.post { act(state.onVisibility(false)); done.countDown() }
            done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            super.onSurfaceDestroyed(holder)
        }

        override fun onOffsetsChanged(xOff: Float, yOff: Float, xStep: Float, yStep: Float, xPx: Int, yPx: Int) {
            render.post { act(state.onOffset(xOff)) }
        }

        override fun onZoomChanged(z: Float) {
            render.post { act(state.onZoom(z)) }
        }

        override fun onVisibilityChanged(v: Boolean) {
            render.post { act(state.onVisibility(v)) }
        }

        override fun onWallpaperFlagsChanged(which: Int) {
            super.onWallpaperFlagsChanged(which)
            scope.launch {
                val uri = photoFor((application as FengineApp).settings.read())
                render.post { act(state.onPhoto(uri)) }
            }
        }

        override fun onDestroy() {
            Log.i(TAG, "engine ${System.identityHashCode(this)} destroyed")
            watch?.cancel(); scope.cancel()
            render.post {
                fade?.cancel(); load?.cancel()
                current?.recycle(); previous?.recycle(); current = null; previous = null
            }
            renderThread.quitSafely()
            super.onDestroy()
        }

        /** Perform what the state asked for: the only place I/O happens. */
        private fun act(d: LiveEngineState.Decision) {
            if (d.cancelFade) { fade?.cancel(); finishFade() }
            d.decode?.let { uri -> decodeThen(uri, d.keepOld) }
            if (d.startFade) startFade()
            if (d.draw) draw()
        }

        private fun decodeThen(uri: String, keepOld: Boolean) {
            load?.cancel()
            val w = state.width; val h = state.height
            load = scope.launch(Dispatchers.IO) {
                val bitmap = decode(uri, w, h) ?: return@launch
                render.post {
                    val decision = state.onDecoded(uri, keepOld)
                    if (decision == LiveEngineState.Decision.NONE && state.shown != uri) { bitmap.recycle(); return@post } // stale
                    fade?.cancel()
                    previous?.recycle()
                    previous = if (decision.startFade) current else null
                    if (!decision.startFade) current?.recycle()
                    current = bitmap
                    if (!decision.startFade) fadeAlpha = 1f
                    act(decision)
                }
            }
        }

        /**
         * Decode at exactly the size the frames need: a 12 MP photo on a 2448x1848 panel becomes
         * a 2803x2116 bitmap (24 MB as a hardware bitmap), not the 49 MB a power-of-two sample
         * size would leave. The sample size does the cheap part, the target size the rest.
         */
        private fun decode(uri: String, w: Int, h: Int): Bitmap? = runCatching {
            val (tw, th) = LiveFrame.decodeTarget(w, h)
            val source = ImageDecoder.createSource(contentResolver, Uri.parse(uri))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (dw, dh) = LiveFrame.decodeSize(info.size.width, info.size.height, tw, th)
                decoder.setTargetSize(dw, dh)
                decoder.allocator = if (softwareOnly) ImageDecoder.ALLOCATOR_SOFTWARE else ImageDecoder.ALLOCATOR_DEFAULT
            }
        }.onFailure { Log.w(TAG, "decode failed for $uri", it) }.getOrNull()

        private fun startFade() {
            fadeAlpha = 0f
            fadeFrames = 0; fadeStart = android.os.SystemClock.elapsedRealtime()
            fade = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FADE_MS
                interpolator = LinearInterpolator()
                var lastFrame = 0L
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    val now = android.os.SystemClock.elapsedRealtime()
                    // 120 Hz panels tick twice as often; a 900 ms fade needs no more than 60 fps.
                    if (t < 1f && now - lastFrame < MIN_FRAME_MS) return@addUpdateListener
                    lastFrame = now
                    fadeAlpha = LiveFrame.fadeAlpha(t)
                    act(state.onFadeFrame(t))
                }
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

        /** One frame. The state already decided it is wanted and the surface is visible. */
        private fun draw() {
            val holder = surfaceHolder
            val canvas = (if (softwareOnly) null else runCatching { holder.lockHardwareCanvas() }.getOrNull())
                ?: run { softwareOnly = true; holder.lockCanvas() } ?: return
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                canvas.drawColor(Color.BLACK)
                previous?.let { if (fadeAlpha < 1f) drawPhoto(canvas, it, 255) }
                current?.let { drawPhoto(canvas, it, (fadeAlpha * 255).toInt()) }
                frames++
                if (fade != null) fadeFrames++ else Log.d(TAG, "frame ${frames}: offset=${state.xOffset} zoom=${state.zoom}")
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            // A GPU path that stalls (emulators, some drivers) is worse than software drawing: after
            // two slow posts the engine switches to the software canvas for the rest of its life.
            if (!softwareOnly) {
                val ms = android.os.SystemClock.elapsedRealtime() - started
                if (ms > SLOW_POST_MS) { if (++slowPosts >= 2) { softwareOnly = true; Log.w(TAG, "hardware canvas stalled ${ms} ms twice; using software from now on") } }
                else slowPosts = 0
            }
        }

        private fun drawPhoto(canvas: Canvas, bitmap: Bitmap, alpha: Int) {
            if (bitmap.isRecycled) return
            val f = LiveFrame.frame(bitmap.width, bitmap.height, state.width, state.height, state.xOffset, state.zoom)
            matrix.reset(); matrix.postScale(f.scale, f.scale); matrix.postTranslate(f.dx, f.dy)
            paint.alpha = alpha
            canvas.drawBitmap(bitmap, matrix, paint)
        }
    }

    companion object {
        const val TAG = "PhotoWallpaper"
        const val FADE_MS = 900L
        const val SLOW_POST_MS = 500L
        const val MIN_FRAME_MS = 15L
    }
}
