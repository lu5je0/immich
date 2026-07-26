package app.alextran.immich.hdr

import android.animation.ValueAnimator
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

private const val HEADROOM_RAMP_MS = 800L
// Covers the latency of the colorMode switch so the ramp starts once HDR compositing is live.
private const val HEADROOM_RAMP_DELAY_MS = 100L
private const val MAX_DESIRED_HEADROOM = 8f
// Keep HDR active across the brief overlap when swiping between two HDR assets.
private const val RELEASE_DELAY_MS = 300L

class UltraHdrPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {
  private var channel: MethodChannel? = null
  private var overlay: HdrOverlayLayout? = null

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(binding.binaryMessenger, "immich_ultra_hdr_overlay").also {
      it.setMethodCallHandler(this)
    }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel?.setMethodCallHandler(null)
    channel = null
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      return result.success(null)
    }
    when (call.method) {
      "setSource" -> {
        val args = call.arguments as Map<*, *>
        ensureOverlay()?.setSource(args["source"] as String, (args["targetDim"] as Number).toInt())
        result.success(null)
      }

      "clear" -> {
        overlay?.clear()
        result.success(null)
      }

      "setSwipeOffset" -> {
        overlay?.setSwipeOffset((call.arguments as Number).toFloat())
        result.success(null)
      }

      "setChrome" -> {
        val args = call.arguments as Map<*, *>
        ensureOverlay()?.setChrome(
          (args["top"] as Number).toInt(),
          (args["bottom"] as Number).toInt(),
          args["visible"] as Boolean,
        )
        result.success(null)
      }

      else -> result.notImplemented()
    }
  }

  private fun ensureOverlay(): HdrOverlayLayout? {
    overlay?.let { return it }
    val currentActivity = activity ?: return null
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null

    val content = currentActivity.findViewById<ViewGroup>(android.R.id.content) ?: return null
    val layout = HdrOverlayLayout(currentActivity)
    layout.onZoomChanged = { zoomed -> channel?.invokeMethod("zoomChanged", zoomed) }
    layout.onSingleTap = { channel?.invokeMethod("singleTap", null) }
    content.addView(
      layout,
      ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    overlay = layout
    return layout
  }

  private fun removeOverlay() {
    val layout = overlay ?: return
    overlay = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      layout.clear()
    }
    (layout.parent as? ViewGroup)?.removeView(layout)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
  }

  override fun onDetachedFromActivityForConfigChanges() {
    removeOverlay()
    activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    activity = binding.activity
  }

  override fun onDetachedFromActivity() {
    removeOverlay()
    activity = null
  }

  companion object {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activity: Activity? = null
    private var hdrRefs = 0
    private var headroomAnimator: ValueAnimator? = null
    private val releaseRunnable = Runnable {
      if (hdrRefs == 0) {
        exitHdrMode()
      }
    }

    fun acquireHdr() {
      mainHandler.post {
        mainHandler.removeCallbacks(releaseRunnable)
        hdrRefs++
        if (hdrRefs == 1) {
          enterHdrMode()
        } else {
          startHeadroomRamp()
        }
      }
    }

    fun rampHeadroom() {
      mainHandler.post { startHeadroomRamp() }
    }

    fun releaseHdr() {
      mainHandler.post {
        hdrRefs = maxOf(0, hdrRefs - 1)
        if (hdrRefs == 0) {
          mainHandler.postDelayed(releaseRunnable, RELEASE_DELAY_MS)
        }
      }
    }

    private fun enterHdrMode() {
      val window = activity?.window ?: return
      // Clamp headroom to SDR before enabling HDR compositing; an unset (0 = unlimited)
      // headroom would pop to full brightness the instant colorMode switches.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        headroomAnimator?.cancel()
        window.desiredHdrHeadroom = 1f
      }
      if (window.colorMode != ActivityInfo.COLOR_MODE_HDR) {
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
      }
      startHeadroomRamp()
    }

    // Ramp the headroom so highlights brighten gradually instead of popping.
    private fun startHeadroomRamp() {
      val window = activity?.window ?: return
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
      if (window.colorMode != ActivityInfo.COLOR_MODE_HDR) return
      headroomAnimator?.cancel()
      window.desiredHdrHeadroom = 1f
      // Animate in log space so perceived brightness rises evenly, with ease-in-out.
      headroomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = HEADROOM_RAMP_MS
        startDelay = HEADROOM_RAMP_DELAY_MS
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
          val t = it.animatedValue as Float
          window.desiredHdrHeadroom = Math.pow(MAX_DESIRED_HEADROOM.toDouble(), t.toDouble()).toFloat()
        }
        start()
      }
    }

    private fun exitHdrMode() {
      val window = activity?.window ?: return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        headroomAnimator?.cancel()
        headroomAnimator = null
        // Keep the clamp at 1f so the next HDR entry starts dark instead of unlimited.
        window.desiredHdrHeadroom = 1f
      }
      window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
    }
  }
}
