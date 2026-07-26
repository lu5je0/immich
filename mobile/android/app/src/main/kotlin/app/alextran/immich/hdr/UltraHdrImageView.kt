package app.alextran.immich.hdr

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.annotation.RequiresApi
import io.flutter.embedding.android.FlutterView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

private const val FADE_IN_MS = 150L
private const val GAIN_RAMP_MS = 800L
private const val GAIN_RAMP_DELAY_MS = 100L
private const val MAX_TEXTURE_DIM = 4096
private const val MAX_SCALE = 8f
private const val DOUBLE_TAP_SCALE = 3f
private const val ZOOMED_EPSILON = 1.01f

object HdrBitmapStore {
  val decodeExecutor: ExecutorService = Executors.newFixedThreadPool(2)

  // The overlay hides during page swipes and re-shows when the page settles,
  // so keep the last decodes around to make that instant.
  val cache = LruCache<String, Bitmap>(2)

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  fun decode(context: Context, source: String, targetDim: Int): Bitmap? {
    return try {
      val decoderSource = if (source.startsWith("content:")) {
        ImageDecoder.createSource(context.contentResolver, Uri.parse(source))
      } else {
        ImageDecoder.createSource(File(source))
      }
      // Default (hardware) allocator and no target color space: the gain map stays attached
      // to the bitmap and HWUI applies it when the window is in HDR color mode.
      ImageDecoder.decodeBitmap(decoderSource) { decoder, info, _ ->
        val maxTarget = minOf(if (targetDim > 0) targetDim else MAX_TEXTURE_DIM, MAX_TEXTURE_DIM)
        val maxSource = max(info.size.width, info.size.height)
        if (maxTarget in 1 until maxSource) {
          decoder.setTargetSampleSize(max(1, maxSource / maxTarget))
        }
      }
    } catch (_: Throwable) {
      null
    }
  }
}

// Fullscreen overlay attached directly to the activity's content view, entirely outside the
// Flutter compositor: HWUI renders the HDR bitmap (applying the gain map) and touches arrive
// natively, so Flutter's frame loop plays no part in zooming - the Google Photos pipeline.
//
// While the viewer's app bars are visible the canvas is clipped to the region between them,
// letting the Flutter-drawn bars (and their scrim gradients, which hide the resulting SDR/HDR
// seam) show through.
//
// Touch ownership: pinches, double taps and all interaction while zoomed are handled here;
// every other touch is forwarded verbatim to the FlutterView below. Page swipes stay fully
// Flutter-driven: the overlay slides in lockstep via setSwipeOffset, keeping the outgoing
// photo at HDR brightness, and a newly landed page fades in after a short beat.
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class HdrOverlayLayout(context: Context) : FrameLayout(context) {
  var onZoomChanged: ((Boolean) -> Unit)? = null
  var onSingleTap: (() -> Unit)? = null

  private val zoomView = ZoomableHdrView(context).apply { alpha = 0f }
  private var owning = false
  private var gestureActive = false
  private var flutterView: View? = null
  private var currentSource: String? = null
  private var generation = 0
  private var hdrAcquired = false

  init {
    addView(zoomView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    zoomView.onZoomChanged = { zoomed -> onZoomChanged?.invoke(zoomed) }
    zoomView.onSingleTap = { onSingleTap?.invoke() }
  }

  private val engaged: Boolean get() = currentSource != null && zoomView.currentBitmap != null

  fun setSource(source: String, targetDim: Int) {
    val generationAtStart = ++generation
    zoomView.translationX = 0f
    if (currentSource == source && zoomView.currentBitmap != null) {
      // clear() released HDR mode; re-showing the same bitmap must re-acquire it.
      display(zoomView.currentBitmap!!)
      return
    }
    currentSource = source

    val key = "$source|$targetDim"
    val cached = HdrBitmapStore.cache.get(key)
    if (cached != null) {
      zoomView.setImage(cached)
      display(cached)
      return
    }

    zoomView.animate().cancel()
    zoomView.alpha = 0f
    zoomView.clearImage()
    HdrBitmapStore.decodeExecutor.execute {
      val bitmap = HdrBitmapStore.decode(context, source, targetDim) ?: return@execute
      HdrBitmapStore.cache.put(key, bitmap)
      post {
        if (generation == generationAtStart && currentSource == source) {
          zoomView.setImage(bitmap)
          display(bitmap)
        }
      }
    }
  }

  fun clear() {
    if (currentSource == null) return
    generation++
    currentSource = null
    zoomView.animate().cancel()
    zoomView.alpha = 0f
    zoomView.translationX = 0f
    zoomView.resetZoom()
    releaseHdrIfNeeded()
  }

  // Slides the HDR photo horizontally in lockstep with Flutter's page swipe so the
  // current photo keeps its HDR brightness while being dragged away.
  fun setSwipeOffset(offsetPx: Float) {
    zoomView.translationX = -offsetPx
  }

  fun setChrome(topPx: Int, bottomPx: Int, visible: Boolean) {
    zoomView.setChrome(topPx, bottomPx, visible)
  }

  private fun display(bitmap: Bitmap) {
    if (bitmap.hasGainmap()) {
      if (!hdrAcquired) {
        hdrAcquired = true
        UltraHdrPlugin.acquireHdr()
      }
      // Per-photo gradual brightening via the photo's own gainmap ratios.
      zoomView.startGainRamp()
    }
    zoomView.animate().alpha(1f).setDuration(FADE_IN_MS).start()
  }

  private fun releaseHdrIfNeeded() {
    if (hdrAcquired) {
      hdrAcquired = false
      UltraHdrPlugin.releaseHdr()
    }
  }

  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    val action = ev.actionMasked
    if (action == MotionEvent.ACTION_DOWN) {
      // While zoomed the native view owns every gesture from the start (panning,
      // fling, double tap); otherwise ownership is only taken by pinch/double tap.
      owning = engaged && zoomView.isZoomed
      // Decide once per gesture: if the content disappears mid-gesture (page swipes
      // clear it), keep forwarding the rest of the stream to Flutter so the PageView
      // still receives the up event and its fling velocity.
      gestureActive = engaged
    }
    if (!gestureActive) {
      return false
    }

    val target = if (engaged) zoomView else null
    val gestureEnds = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL

    if (!owning) {
      if (target != null && ev.pointerCount >= 2) {
        takeOwnership(ev)
      } else {
        if (target != null) {
          doubleTapDetector.onTouchEvent(ev)
          if (owning) {
            // Ownership was just taken by a double tap; swallow this event so the
            // zoom animation it started isn't cancelled by handleTouch(DOWN).
            if (gestureEnds) {
              gestureActive = false
            }
            return true
          }
        }
        if (!owning) {
          forwardToFlutter(ev)
          if (gestureEnds) {
            gestureActive = false
          }
          return true
        }
      }
    }

    if (target != null) {
      target.handleTouch(ev)
      if (gestureEnds && !target.isZoomed) {
        owning = false
      }
    } else {
      owning = false
    }
    if (gestureEnds) {
      gestureActive = false
    }
    return true
  }

  private val doubleTapDetector = GestureDetector(
    context,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onDoubleTap(e: MotionEvent): Boolean {
        if (!engaged) return false
        takeOwnership(e)
        zoomView.doubleTapAt(e.x / max(1, width), e.y / max(1, height))
        return true
      }
    },
  )

  private fun takeOwnership(ev: MotionEvent) {
    if (owning) return
    owning = true
    val flutter = findFlutter() ?: return
    // Clone the in-flight event so Flutter can match the cancel to the gesture
    // stream it has been receiving (kills its long-press timers etc.).
    val cancel = MotionEvent.obtain(ev)
    cancel.action = MotionEvent.ACTION_CANCEL
    flutter.dispatchTouchEvent(cancel)
    cancel.recycle()
  }

  private fun forwardToFlutter(ev: MotionEvent) {
    findFlutter()?.dispatchTouchEvent(ev)
  }

  private fun findFlutter(): View? {
    val cached = flutterView
    if (cached != null && cached.isAttachedToWindow) {
      return cached
    }
    val root = (parent as? ViewGroup) ?: return null
    val found = findFlutterView(root)
    flutterView = found
    return found
  }

  private fun findFlutterView(root: View): View? {
    if (root === this) return null
    if (root is FlutterView) return root
    if (root is ViewGroup) {
      for (i in 0 until root.childCount) {
        findFlutterView(root.getChildAt(i))?.let { return it }
      }
    }
    return null
  }
}

// Native zoomable image view: fit-center base matrix plus a gesture-driven user transform,
// drawn by HWUI which applies the gain map in HDR color mode.
class ZoomableHdrView(context: Context) : View(context) {
  var onZoomChanged: ((Boolean) -> Unit)? = null
  var onSingleTap: (() -> Unit)? = null

  val isZoomed: Boolean get() = currentScale() > ZOOMED_EPSILON
  val currentBitmap: Bitmap? get() = bitmap

  private var bitmap: Bitmap? = null
  private val baseMatrix = Matrix()
  private val suppMatrix = Matrix()
  private val drawMatrix = Matrix()
  private val matrixValues = FloatArray(9)
  private val displayRect = RectF()
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
  private var reportedZoomed = false

  private var chromeTopPx = 0
  private var chromeBottomPx = 0
  private var chromeVisible = false

  private val scroller = OverScroller(context)
  private var flingRunnable: Runnable? = null
  private var zoomAnimator: ValueAnimator? = null
  private var gainAnimator: ValueAnimator? = null

  private val scaleDetector = ScaleGestureDetector(
    context,
    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScale(detector: ScaleGestureDetector): Boolean {
        val current = currentScale()
        var factor = detector.scaleFactor
        val target = current * factor
        if (target > MAX_SCALE) factor = MAX_SCALE / current
        if (target < 0.8f) factor = 0.8f / current
        suppMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
        applyEdits()
        return true
      }
    },
  )

  private val gestureDetector = GestureDetector(
    context,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
        if (!isZoomed || scaleDetector.isInProgress) return false
        suppMatrix.postTranslate(-dx, -dy)
        applyEdits()
        return true
      }

      override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
        if (!isZoomed || scaleDetector.isInProgress) return false
        fling(vx.toInt(), vy.toInt())
        return true
      }

      override fun onDoubleTap(e: MotionEvent): Boolean {
        doubleTapAt(e.x / max(1, width), e.y / max(1, height))
        return true
      }

      override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        if (isZoomed) {
          onSingleTap?.invoke()
        }
        return true
      }
    },
  )

  fun setImage(image: Bitmap) {
    gainAnimator?.cancel()
    bitmap = image
    suppMatrix.reset()
    updateBaseMatrix()
  }

  fun clearImage() {
    cancelFling()
    zoomAnimator?.cancel()
    gainAnimator?.cancel()
    bitmap = null
    resetZoom()
    invalidate()
  }

  // Gradually brightens the highlights by animating the exponent applied to the
  // photo's own gainmap ratios (t=0 -> pure SDR, t=1 -> full gain). The window
  // headroom stays pinned, so this is deterministic per photo - unlike
  // desiredHdrHeadroom changes, which the system smooths on its own schedule.
  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  fun startGainRamp() {
    val image = bitmap ?: return
    val gainmap = image.gainmap ?: return
    val base = baseRatios.getOrPut(image) { Pair(gainmap.ratioMin.clone(), gainmap.ratioMax.clone()) }
    gainAnimator?.cancel()
    applyGain(gainmap, base, 0f)
    invalidate()
    gainAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = GAIN_RAMP_MS
      startDelay = GAIN_RAMP_DELAY_MS
      interpolator = android.view.animation.AccelerateDecelerateInterpolator()
      addUpdateListener {
        applyGain(gainmap, base, it.animatedValue as Float)
        invalidate()
      }
      start()
    }
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private fun applyGain(gainmap: android.graphics.Gainmap, base: Pair<FloatArray, FloatArray>, t: Float) {
    fun pow(v: Float) = Math.pow(v.toDouble(), t.toDouble()).toFloat()
    val (minR, maxR) = base
    gainmap.setRatioMin(pow(minR[0]), pow(minR[1]), pow(minR[2]))
    gainmap.setRatioMax(pow(maxR[0]), pow(maxR[1]), pow(maxR[2]))
  }

  private companion object {
    // Pristine gainmap ratios per bitmap: cached bitmaps may be re-displayed after an
    // interrupted ramp left their gainmap scaled, so the originals must be remembered.
    val baseRatios = java.util.WeakHashMap<Bitmap, Pair<FloatArray, FloatArray>>()
  }

  fun resetZoom() {
    cancelFling()
    zoomAnimator?.cancel()
    suppMatrix.reset()
    if (reportedZoomed) {
      reportedZoomed = false
      onZoomChanged?.invoke(false)
    }
    invalidate()
  }

  fun setChrome(topPx: Int, bottomPx: Int, visible: Boolean) {
    if (chromeTopPx == topPx && chromeBottomPx == bottomPx && chromeVisible == visible) return
    chromeTopPx = topPx
    chromeBottomPx = bottomPx
    chromeVisible = visible
    invalidate()
  }

  fun doubleTapAt(fractionX: Float, fractionY: Float) {
    val px = fractionX * width
    val py = fractionY * height
    if (currentScale() > 1.5f) {
      animateScaleTo(1f, width / 2f, height / 2f)
    } else {
      animateScaleTo(DOUBLE_TAP_SCALE, px, py)
    }
  }

  fun handleTouch(event: MotionEvent) {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        cancelFling()
        zoomAnimator?.cancel()
      }

      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        if (currentScale() < 1f) {
          animateScaleTo(1f, width / 2f, height / 2f)
        }
      }
    }
    scaleDetector.onTouchEvent(event)
    gestureDetector.onTouchEvent(event)
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    updateBaseMatrix()
  }

  override fun onDraw(canvas: Canvas) {
    val image = bitmap ?: return
    if (chromeVisible && (chromeTopPx > 0 || chromeBottomPx > 0)) {
      // Let the Flutter-drawn app bars (and the SDR strip beneath their scrims)
      // show through while they are visible.
      canvas.clipRect(0, chromeTopPx, width, height - chromeBottomPx)
    }
    // Cover the SDR PhotoView below: without this the letterboxed regions are
    // transparent and the underlying 1x image shows through while zooming.
    canvas.drawColor(Color.BLACK)
    drawMatrix.set(baseMatrix)
    drawMatrix.postConcat(suppMatrix)
    canvas.drawBitmap(image, drawMatrix, paint)
  }

  private fun currentScale(): Float {
    suppMatrix.getValues(matrixValues)
    return matrixValues[Matrix.MSCALE_X]
  }

  private fun applyEdits() {
    checkMatrixBounds()
    invalidate()
    val zoomed = isZoomed
    if (zoomed != reportedZoomed) {
      reportedZoomed = zoomed
      onZoomChanged?.invoke(zoomed)
    }
  }

  private fun updateBaseMatrix() {
    val image = bitmap ?: return
    if (width == 0 || height == 0) return
    val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
    baseMatrix.setScale(scale, scale)
    baseMatrix.postTranslate((width - image.width * scale) / 2f, (height - image.height * scale) / 2f)
    checkMatrixBounds()
    invalidate()
  }

  private fun updateDisplayRect(): RectF? {
    val image = bitmap ?: return null
    drawMatrix.set(baseMatrix)
    drawMatrix.postConcat(suppMatrix)
    displayRect.set(0f, 0f, image.width.toFloat(), image.height.toFloat())
    drawMatrix.mapRect(displayRect)
    return displayRect
  }

  private fun checkMatrixBounds() {
    val rect = updateDisplayRect() ?: return
    val viewWidth = width.toFloat()
    val viewHeight = height.toFloat()

    val dx = when {
      rect.width() <= viewWidth -> viewWidth / 2f - rect.centerX()
      rect.left > 0 -> -rect.left
      rect.right < viewWidth -> viewWidth - rect.right
      else -> 0f
    }
    val dy = when {
      rect.height() <= viewHeight -> viewHeight / 2f - rect.centerY()
      rect.top > 0 -> -rect.top
      rect.bottom < viewHeight -> viewHeight - rect.bottom
      else -> 0f
    }
    suppMatrix.postTranslate(dx, dy)
  }

  private fun animateScaleTo(target: Float, px: Float, py: Float) {
    zoomAnimator?.cancel()
    val start = currentScale()
    if (start == target) return
    zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 250
      interpolator = DecelerateInterpolator()
      addUpdateListener {
        val t = it.animatedValue as Float
        val desired = start + (target - start) * t
        val factor = desired / currentScale()
        suppMatrix.postScale(factor, factor, px, py)
        applyEdits()
      }
      start()
    }
  }

  private fun fling(velocityX: Int, velocityY: Int) {
    val rect = updateDisplayRect() ?: return
    val startX = (-rect.left).toInt()
    val startY = (-rect.top).toInt()
    val maxX = max(0f, rect.width() - width).toInt()
    val maxY = max(0f, rect.height() - height).toInt()
    scroller.fling(startX, startY, -velocityX, -velocityY, 0, maxX, 0, maxY)

    val runnable = object : Runnable {
      var lastX = startX
      var lastY = startY

      override fun run() {
        if (flingRunnable !== this || !scroller.computeScrollOffset()) return
        val x = scroller.currX
        val y = scroller.currY
        suppMatrix.postTranslate((lastX - x).toFloat(), (lastY - y).toFloat())
        checkMatrixBounds()
        invalidate()
        lastX = x
        lastY = y
        postOnAnimation(this)
      }
    }
    flingRunnable = runnable
    postOnAnimation(runnable)
  }

  private fun cancelFling() {
    flingRunnable = null
    scroller.forceFinished(true)
  }
}
