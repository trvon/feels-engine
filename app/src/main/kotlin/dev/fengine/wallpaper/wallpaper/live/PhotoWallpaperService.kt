package dev.fengine.wallpaper.wallpaper.live

import android.animation.ValueAnimator
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.view.animation.LinearInterpolator
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.data.Backdrop
import dev.fengine.wallpaper.data.ChangeTriggers
import dev.fengine.wallpaper.data.Ledger
import dev.fengine.wallpaper.data.LedgerStore
import dev.fengine.wallpaper.data.PhotoFit
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.schedule.Rotation
import dev.fengine.wallpaper.ui.Palette
import dev.fengine.wallpaper.wallpaper.PhotoColors
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
 * thread. A GPU stall in a canvas post (seen on emulators) then costs frames, not the process: the
 * main thread stays free for WorkManager jobs and binder calls. The engine callbacks arrive on the
 * main thread and are handed over with a post.
 */
class PhotoWallpaperService : WallpaperService() {
    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + survive)
    private var uiMode: Int? = null

    override fun onCreateEngine(): Engine = PhotoEngine()

    override fun onCreate() {
        super.onCreate()
        uiMode = resources.configuration.uiMode
    }

    /** Settings > Rotation, "When light or dark changes": one service, so one change per switch. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val changed = ChangeTriggers.nightChanged(uiMode, newConfig.uiMode)
        uiMode = newConfig.uiMode
        if (!changed || !LiveEngines.anyRunning) return
        val night =
            (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val s = (application as FengineApp).settings.read()
                // Turning dark with "Darker photos" on moves to a dark photo right away.
                if (s.changeOnTheme || (s.darkAtNight && night))
                    Rotation.run(applicationContext, Ledger.Source.THEME)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    inner class PhotoEngine : Engine() {
        private val renderThread =
            HandlerThread("feels-render", android.os.Process.THREAD_PRIORITY_DISPLAY).apply {
                start()
            }
        private val render = Handler(renderThread.looper)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + survive)
        /** Touched only on the render thread. */
        private val state = LiveEngineState()
        private var watch: Job? = null
        private var load: Job? = null
        private val slots = BitmapSlots<Bitmap>()
        private val current: Bitmap?
            get() = slots.current

        private val previous: Bitmap?
            get() = slots.previous

        private var fade: ValueAnimator? = null
        private var fadeAlpha = 1f
        private val canvas = CanvasPolicy(SLOW_POST_MS)
        private val fadeClock = FadeClock(MIN_FRAME_MS)
        private val health = FrameHealth()
        /**
         * Settings > Look: whole photo with a backdrop, and whether the backdrop takes the photo's
         * colour.
         */
        @Volatile private var fitWhole = false
        @Volatile private var backdropTinted = false
        @Volatile private var backdropColor = Color.BLACK
        @Volatile private var photoColors: WallpaperColors? = null
        /** The photo [photoColors] came from: a resize or fold re-decodes, the colours stay. */
        @Volatile private var colorsFor: String? = null
        @Volatile private var tapToChange = false
        private var lastTap: ChangeTriggers.Tap? = null
        private val mainHandler = Handler(android.os.Looper.getMainLooper())
        private val ledger by lazy { LedgerStore(applicationContext) }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()
        private var frames = 0L
        private var fadeFrames = 0L
        private var fadeStart = 0L

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) wallpaperFlags
                else -1
            val display =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    displayContext?.display?.displayId
                else null
            Log.i(
                TAG,
                "engine ${System.identityHashCode(this)} created, preview=$isPreview flags=$flags display=$display",
            )
            LiveEngines.created(System.identityHashCode(this), isPreview)
            setOffsetNotificationsEnabled(true)
            watch = scope.launch {
                (application as FengineApp)
                    .settings
                    .settings
                    .catch { Log.w(TAG, "settings unreadable", it) }
                    .collectLatest { settings ->
                        val uri = photoFor(settings)
                        val whole = settings.photoFit == PhotoFit.WHOLE
                        val tinted = settings.backdrop == Backdrop.PHOTO
                        tapToChange = settings.tapToChange
                        render.post {
                            if (whole != fitWhole || tinted != backdropTinted) {
                                fitWhole = whole
                                backdropTinted = tinted
                                act(state.onStyleChanged())
                            }
                            act(state.onPhoto(uri))
                        }
                    }
            }
        }

        /**
         * The photo this screen should show: its group's cursor photo, or the first before any
         * rotation.
         */
        private fun photoFor(settings: Settings): String? {
            val lock =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    wallpaperFlags and WallpaperManager.FLAG_LOCK != 0 &&
                    wallpaperFlags and WallpaperManager.FLAG_SYSTEM == 0
            val group =
                (if (lock) settings.lockGroup else settings.homeGroup)
                    ?: settings.homeGroup
                    ?: settings.groups.firstOrNull()
            val photos = group?.rotation.orEmpty()
            if (photos.isEmpty()) return null
            return photos[group!!.cursor.coerceIn(0, photos.size - 1)]
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            render.post {
                if (w != state.width || h != state.height) Log.i(TAG, "surface ${w}x$h")
                act(state.onSurface(w, h))
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            // Finish any draw in flight before the surface goes away; its content goes with it.
            Log.i(TAG, "surface lost")
            onRenderThread {
                hidden()
                act(state.onSurfaceLost())
            }
            super.onSurfaceDestroyed(holder)
        }

        /**
         * The system shows the surface right after this returns, so the frame is drawn before that.
         */
        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            Log.i(TAG, "surface redraw needed")
            onRenderThread { actShown(state.onRedrawNeeded()) }
        }

        /**
         * Run [block] on the render thread and wait for it, bounded so a stalled GPU never blocks
         * the caller for long.
         */
        private fun onRenderThread(block: () -> Unit) {
            val done = java.util.concurrent.CountDownLatch(1)
            render.post {
                try {
                    block()
                } finally {
                    done.countDown()
                }
            }
            done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        override fun onOffsetsChanged(
            xOff: Float,
            yOff: Float,
            xStep: Float,
            yStep: Float,
            xPx: Int,
            yPx: Int,
        ) {
            render.post { act(state.onOffset(xOff)) }
        }

        override fun onZoomChanged(z: Float) {
            render.post { act(state.onZoom(z)) }
        }

        override fun onVisibilityChanged(v: Boolean) {
            // Bound while it is the wallpaper, the engine can catch up on screen-on when a change
            // is
            // overdue (the alarm deferred): no wake-up, no render loop, just one due check.
            if (v && !isPreview)
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        dev.fengine.wallpaper.schedule.Rotation.run(
                            applicationContext,
                            dev.fengine.wallpaper.data.Ledger.Source.WAKE,
                        )
                    }
                }
            Log.i(TAG, "visible=$v")
            render.post {
                if (v) actShown(state.onVisibility(true))
                else {
                    hidden()
                    act(state.onVisibility(false))
                }
            }
        }

        /**
         * Launchers send a tap on empty home screen space; two close together show the next photo.
         * Single taps stay the launcher's (they also close folders and menus).
         */
        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean,
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP && tapToChange && !isPreview) {
                val tap = ChangeTriggers.Tap(android.os.SystemClock.uptimeMillis(), x, y)
                val slop = (48 * resources.displayMetrics.density).toInt()
                if (ChangeTriggers.isDoubleTap(lastTap, tap, slop)) {
                    lastTap = null
                    Log.i(TAG, "double tap: next photo")
                    scope.launch(Dispatchers.IO) {
                        runCatching { Rotation.run(applicationContext, Ledger.Source.TAP) }
                    }
                } else lastTap = tap
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
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
            LiveEngines.destroyed(System.identityHashCode(this))
            watch?.cancel()
            scope.cancel()
            render.post {
                fade?.cancel()
                load?.cancel()
                hidden()
                slots.onDestroy().forEach(Bitmap::recycle)
            }
            renderThread.quitSafely()
            super.onDestroy()
        }

        /** Perform what the state asked for: the only place I/O happens. */
        private fun act(d: LiveEngineState.Decision) {
            if (d.cancelFade) {
                fade?.cancel()
                finishFade()
            }
            d.decode?.let { uri -> decodeThen(uri, d.keepOld) }
            if (d.startFade) startFade()
            if (d.draw) draw()
        }

        /**
         * The current photo's colours, so the phone's palette follows Motion like a still
         * wallpaper's.
         */
        override fun onComputeColors(): WallpaperColors? = photoColors

        private fun decodeThen(uri: String, keepOld: Boolean) {
            load?.cancel()
            val w = state.width
            val h = state.height
            load =
                scope.launch(Dispatchers.IO) {
                    val bitmap = decode(uri, w, h) ?: return@launch
                    val colors =
                        if (uri == colorsFor) null else PhotoColors.of(applicationContext, uri)
                    // A destroyed engine's looper has quit: the post is refused, release here.
                    val posted = render.post {
                        if (colors != null && state.wanted == uri) {
                            photoColors = colors
                            colorsFor = uri
                            backdropColor = Palette.backdrop(colors.primaryColor.toArgb())
                            // Android asks onComputeColors for the new palette (Material You
                            // follows Motion).
                            mainHandler.post { runCatching { notifyColorsChanged() } }
                        }
                        val decision = state.onDecoded(uri, keepOld)
                        if (decision == LiveEngineState.Decision.NONE && state.shown != uri) {
                            bitmap.recycle()
                            return@post
                        } // stale
                        fade?.cancel() // ends through finishFade, which releases the old previous
                        slots.onDecoded(bitmap, fade = decision.startFade).forEach(Bitmap::recycle)
                        if (!decision.startFade) fadeAlpha = 1f
                        act(decision)
                    }
                    if (!posted) bitmap.recycle()
                }
        }

        /**
         * Decode at exactly the size the frames need: a 12 MP photo on a 2448x1848 panel becomes a
         * 2803x2116 bitmap (24 MB as a hardware bitmap), not the 49 MB a power-of-two sample size
         * would leave. The sample size does the cheap part, the target size the rest.
         */
        private fun decode(uri: String, w: Int, h: Int): Bitmap? = runCatching {
            val (tw, th) = LiveFrame.decodeTarget(w, h)
            val source = ImageDecoder.createSource(contentResolver, Uri.parse(uri))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (dw, dh) = LiveFrame.decodeSize(info.size.width, info.size.height, tw, th)
                decoder.setTargetSize(dw, dh)
                decoder.allocator =
                    if (canvas.softwareOnly) ImageDecoder.ALLOCATOR_SOFTWARE
                    else ImageDecoder.ALLOCATOR_DEFAULT
            }
        }
            .onFailure { Log.w(TAG, "decode failed for $uri", it) }
            .getOrNull()

        private fun startFade() {
            fadeAlpha = 0f
            fadeFrames = 0
            fadeStart = android.os.SystemClock.elapsedRealtime()
            fadeClock.reset()
            fade =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = FADE_MS
                    interpolator = LinearInterpolator()
                    addUpdateListener { a ->
                        val t = a.animatedValue as Float
                        // 120 Hz panels tick twice as often; a 900 ms fade needs no more than 60
                        // fps.
                        if (!fadeClock.shouldDraw(t, android.os.SystemClock.elapsedRealtime()))
                            return@addUpdateListener
                        fadeAlpha = LiveFrame.fadeAlpha(t)
                        act(state.onFadeFrame(t))
                    }
                    addListener(
                        object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) =
                                finishFade()
                        }
                    )
                    start()
                }
        }

        private fun finishFade() {
            if (fade != null)
                Log.i(
                    TAG,
                    "fade: $fadeFrames frames in ${android.os.SystemClock.elapsedRealtime() - fadeStart} ms",
                )
            fade = null
            fadeAlpha = 1f
            slots.onFadeFinished().forEach(Bitmap::recycle)
        }

        /** One frame. The state already decided it is wanted and the surface is visible. */
        private fun draw() {
            val holder = surfaceHolder
            val c =
                (if (canvas.softwareOnly) null
                else runCatching { holder.lockHardwareCanvas() }.getOrNull())
                    ?: run {
                        canvas.onHardwareUnavailable()
                        holder.lockCanvas()
                    }
            if (c == null) {
                Log.w(TAG, "no canvas; frame still owed")
                act(state.onDrawFailed())
                return
            }
            val started = android.os.SystemClock.elapsedRealtime()
            // A software canvas cannot draw hardware bitmaps decoded before the fallback.
            if (!c.isHardwareAccelerated)
                slots
                    .convert { b ->
                        if (b.config == Bitmap.Config.HARDWARE && !b.isRecycled)
                            b.copy(Bitmap.Config.ARGB_8888, false) ?: b
                        else b
                    }
                    .forEach(Bitmap::recycle)
            val blended = previous != null && fadeAlpha < 1f
            try {
                c.drawColor(if (fitWhole && backdropTinted) backdropColor else Color.BLACK)
                previous?.let { if (fadeAlpha < 1f) drawPhoto(c, it, 255) }
                current?.let { drawPhoto(c, it, (fadeAlpha * 255).toInt()) }
                frames++
                if (fade != null) fadeFrames++
                else Log.d(TAG, "frame ${frames}: offset=${state.xOffset} zoom=${state.zoom}")
            } catch (e: RuntimeException) {
                // One bad frame must not end the process (two deaths in 10 s cost Motion).
                Log.w(TAG, "frame failed", e)
            } finally {
                holder.unlockCanvasAndPost(c)
            }
            // A GPU path that stalls (emulators, some drivers) is worse than software drawing.
            val ms = android.os.SystemClock.elapsedRealtime() - started
            if (canvas.onPost(ms))
                Log.w(TAG, "hardware canvas stalled twice; using software from now on")
            if (!isPreview) health.drew(now(), blended)?.let { record(it) }
        }

        private fun now() = android.os.SystemClock.elapsedRealtime()

        /**
         * The screen is being shown. It owes a frame when the state asks for a draw or a decode, or
         * has nothing decoded at all; otherwise the surface already holds the right picture.
         */
        private fun actShown(d: LiveEngineState.Decision) {
            if (!isPreview && (d.draw || d.decode != null || state.shown == null))
                health.shown(now())
            act(d)
        }

        /** The screen went away: close the shown-screen record and note an interrupted fade. */
        private fun hidden() {
            if (!isPreview) health.hidden(now()).forEach(::record)
        }

        private fun record(r: FrameHealth.Record) {
            val ev =
                when (r) {
                    is FrameHealth.Record.FirstFrame -> Ledger.Event.FirstFrame(r.latencyMs)
                    is FrameHealth.Record.NoFrame -> Ledger.Event.NoFrame(r.shownMs)
                    FrameHealth.Record.AbandonedBlend -> Ledger.Event.AbandonedBlend
                }
            // File I/O (and the halving at 512 KB) stays off the render thread.
            scope.launch(Dispatchers.IO) { ledger.record(ev) }
        }

        private fun drawPhoto(canvas: Canvas, bitmap: Bitmap, alpha: Int) {
            if (bitmap.isRecycled) return
            val f =
                LiveFrame.frame(
                    bitmap.width,
                    bitmap.height,
                    state.width,
                    state.height,
                    state.xOffset,
                    state.zoom,
                    whole = fitWhole,
                )
            matrix.reset()
            matrix.postScale(f.scale, f.scale)
            matrix.postTranslate(f.dx, f.dy)
            paint.alpha = alpha
            canvas.drawBitmap(bitmap, matrix, paint)
        }
    }

    companion object {
        const val TAG = "PhotoWallpaper"

        /** Engine and service coroutines log a failure instead of ending the shared process. */
        private val survive = CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "engine task failed", e)
        }
        const val FADE_MS = 900L
        const val SLOW_POST_MS = 500L
        const val MIN_FRAME_MS = 15L
    }
}
