package dev.fengine.wallpaper.screensaver

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.service.dreams.DreamService
import android.util.Log
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.smart.Images
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Android's "Screen saver" playing a group: one photo at a time, cross-faded, with a slow
 * Ken Burns drift. The system shows it while the phone charges or docks, on whichever display
 * is active; the drift is recomputed on every layout so a fold mid-session just re-covers.
 *
 * Two ImageViews in MATRIX mode: the back one receives the next photo and fades in over the
 * front one, then they swap. Bitmaps are decoded off the main thread at the view's size.
 */
class GroupDreamService : DreamService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
        back = ImageView(this).apply { scaleType = ImageView.ScaleType.MATRIX; alpha = 0f }
        root.addView(front, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(back, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        player = scope.launch { play() }
    }

    override fun onDreamingStopped() {
        player?.cancel(); player = null
        drift?.cancel(); drift = null
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private suspend fun play() {
        val settings = (application as FengineApp).settings.read()
        val group = settings.dreamGroup ?: settings.homeGroup ?: settings.groups.firstOrNull()
        val photos = group?.allPhotos.orEmpty()
        if (photos.isEmpty()) { Log.i(TAG, "nothing to play"); return }
        val order = DreamPlan.order(photos, settings.shuffle, group!!.cursor, System.nanoTime())
        Log.i(TAG, "playing ${group.name}: ${order.size} photos")
        var i = 0
        while (scope.isActive) {
            val uri = order[i % order.size]; i++
            val edge = maxOf(front.width, front.height).coerceAtLeast(1080)
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { Images.decode(this@GroupDreamService, Uri.parse(uri), edge) }
                    .onFailure { Log.w(TAG, "decode failed for $uri", it) }.getOrNull()
            } ?: continue
            show(bitmap)
            delay(DreamPlan.PHOTO_MS)
        }
    }

    /** Fade [bitmap] in over the current photo and start its drift; the old bitmap is released. */
    private fun show(bitmap: Bitmap) {
        val old = front.drawable
        back.setImageBitmap(bitmap)
        startDrift(back, bitmap)
        back.animate().alpha(1f).setDuration(DreamPlan.FADE_MS).withEndAction {
            // Swap roles: what was back is now front; the previous front becomes the empty back.
            val f = front; front = back; back = f
            back.alpha = 0f
            back.setImageDrawable(null)
            (old as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()
        }.start()
    }

    private fun startDrift(view: ImageView, bitmap: Bitmap) {
        drift?.cancel()
        val rnd = Random(System.nanoTime())
        val from = KenBurns.Focal(0.3f + rnd.nextFloat() * 0.4f, 0.3f + rnd.nextFloat() * 0.4f)
        val to = KenBurns.Focal(0.3f + rnd.nextFloat() * 0.4f, 0.3f + rnd.nextFloat() * 0.4f)
        val matrix = Matrix()
        drift = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DreamPlan.PHOTO_MS + DreamPlan.FADE_MS
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val w = view.width; val h = view.height
                if (w == 0 || h == 0 || bitmap.isRecycled) return@addUpdateListener
                val f = KenBurns.frame(bitmap.width, bitmap.height, w, h, a.animatedValue as Float, DreamPlan.ZOOM_FROM, DreamPlan.ZOOM_TO, from, to)
                matrix.reset(); matrix.postScale(f.scale, f.scale); matrix.postTranslate(f.dx, f.dy)
                view.imageMatrix = matrix
            }
            start()
        }
    }

    companion object {
        const val TAG = "GroupDreamService"
    }
}
