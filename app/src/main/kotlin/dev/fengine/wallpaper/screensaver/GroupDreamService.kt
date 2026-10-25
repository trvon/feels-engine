package dev.fengine.wallpaper.screensaver

import android.animation.ValueAnimator
import android.app.UiModeManager
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.BatteryManager
import android.service.dreams.DreamService
import android.util.Log
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.data.Backdrop
import dev.fengine.wallpaper.data.Ledger
import dev.fengine.wallpaper.data.LedgerStore
import dev.fengine.wallpaper.data.PhotoFit
import dev.fengine.wallpaper.data.Screen
import dev.fengine.wallpaper.data.Screens
import dev.fengine.wallpaper.smart.Images
import dev.fengine.wallpaper.ui.Palette
import dev.fengine.wallpaper.wallpaper.PhotoColors
import kotlin.random.Random
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android's "Screen saver" playing a group: one photo at a time, cross-faded, with a slow Ken Burns
 * drift. The system shows it while the phone charges or docks, on whichever display is active; the
 * drift is recomputed on every layout so a fold mid-session just re-covers.
 *
 * Two ImageViews in MATRIX mode: the back one receives the next photo and fades in over the front
 * one, then they swap. Bitmaps are decoded off the main thread at the view's size.
 */
class GroupDreamService : DreamService() {
    // The dream shares the process with the live engine: a failure here must not end it.
    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, e ->
                    Log.w(TAG, "screen saver failed; staying black", e)
                    runCatching { isScreenBright = false }
                }
        )
    private var player: Job? = null
    private lateinit var front: ImageView
    private lateinit var back: ImageView
    private var drift: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        front = ImageView(this).apply { scaleType = ImageView.ScaleType.MATRIX }
        back =
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.MATRIX
                alpha = 0f
            }
        root.addView(
            front,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        root.addView(
            back,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        player = scope.launch { play() }
    }

    override fun onDreamingStopped() {
        player?.cancel()
        player = null
        drift?.cancel()
        drift = null
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private suspend fun play() {
        val settings = (application as FengineApp).settings.read()
        val group = Screens.effective(settings, Screen.SCREEN_SAVER)
        val photos = group?.rotation.orEmpty()
        val carMode =
            (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType ==
                Configuration.UI_MODE_TYPE_CAR
        val plugged =
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val verdict =
            DreamGate.decide(carMode, plugged, settings.dreamWirelessOnly, photos.isNotEmpty())
        withContext(Dispatchers.IO) {
            val store = LedgerStore(this@GroupDreamService)
            val now = System.currentTimeMillis()
            if (Ledger.shouldRecordScreenSaver(store.lastScreenSaver(), verdict.name, plugged, now))
                store.record(Ledger.Event.ScreenSaver(verdict.name, plugged), now)
        }
        if (DreamGate.show(verdict) == DreamGate.Show.BLACK) {
            // Not finish(): Android restarts a screen saver that ends itself while the phone
            // charges.
            Log.i(TAG, "not playing: $verdict (plugged=$plugged); staying black")
            isScreenBright = false
            return
        }
        val order = DreamPlan.order(photos, settings.shuffle, group!!.cursor, System.nanoTime())
        Log.i(TAG, "playing ${group.name}: ${order.size} photos")
        var i = 0
        var failedInARow = 0
        while (scope.isActive) {
            // Every photo failed (grants gone, files deleted): stop decoding, stay black.
            if (failedInARow >= order.size) {
                Log.w(TAG, "no photo could be decoded; staying black")
                isScreenBright = false
                return
            }
            val uri = order[i % order.size]
            i++
            val edge = maxOf(front.width, front.height).coerceAtLeast(1080)
            val bitmap =
                withContext(Dispatchers.IO) {
                    runCatching { Images.decode(this@GroupDreamService, Uri.parse(uri), edge) }
                        .onFailure { Log.w(TAG, "decode failed for $uri", it) }
                        .getOrNull()
                }
            if (bitmap == null) {
                failedInARow++
                delay(DreamPlan.FADE_MS)
                continue
            }
            failedInARow = 0
            val whole = settings.photoFit == PhotoFit.WHOLE
            val backdrop =
                if (whole && settings.backdrop == Backdrop.PHOTO) {
                    withContext(Dispatchers.IO) { PhotoColors.of(this@GroupDreamService, uri) }
                        ?.let { Palette.backdrop(it.primaryColor.toArgb()) } ?: Color.BLACK
                } else Color.BLACK
            show(bitmap, whole, backdrop)
            delay(DreamPlan.PHOTO_MS)
        }
    }

    /** Fade [bitmap] in over the current photo and start its drift; the old bitmap is released. */
    private fun show(bitmap: Bitmap, whole: Boolean = false, backdrop: Int = Color.BLACK) {
        val old = front.drawable
        back.setImageBitmap(bitmap)
        // An opaque backdrop on the incoming view, so its fade covers the old photo's borders too.
        back.setBackgroundColor(if (whole) backdrop else Color.TRANSPARENT)
        startDrift(back, bitmap, whole)
        back
            .animate()
            .alpha(1f)
            .setDuration(DreamPlan.FADE_MS)
            .withEndAction {
                // Swap roles: what was back is now front; the previous front becomes the empty
                // back.
                val f = front
                front = back
                back = f
                back.alpha = 0f
                back.setImageDrawable(null)
                (old as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()
            }
            .start()
    }

    private fun startDrift(view: ImageView, bitmap: Bitmap, whole: Boolean = false) {
        drift?.cancel()
        val rnd = Random(System.nanoTime())
        val from = KenBurns.Focal(0.3f + rnd.nextFloat() * 0.4f, 0.3f + rnd.nextFloat() * 0.4f)
        val to = KenBurns.Focal(0.3f + rnd.nextFloat() * 0.4f, 0.3f + rnd.nextFloat() * 0.4f)
        val matrix = Matrix()
        drift =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DreamPlan.PHOTO_MS + DreamPlan.FADE_MS
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    val w = view.width
                    val h = view.height
                    if (w == 0 || h == 0 || bitmap.isRecycled) return@addUpdateListener
                    val f =
                        KenBurns.frame(
                            bitmap.width,
                            bitmap.height,
                            w,
                            h,
                            a.animatedValue as Float,
                            DreamPlan.ZOOM_FROM,
                            DreamPlan.ZOOM_TO,
                            from,
                            to,
                            whole = whole,
                        )
                    matrix.reset()
                    matrix.postScale(f.scale, f.scale)
                    matrix.postTranslate(f.dx, f.dy)
                    view.imageMatrix = matrix
                }
                start()
            }
    }

    companion object {
        const val TAG = "GroupDreamService"
    }
}
