package app.alextran.immich.hdr

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

private const val MAX_DESIRED_HEADROOM = 8f
// Exiting and re-entering HDR color mode reconfigures the window surface (a visible
// hitch), so keep HDR alive long enough to cover a page-swipe settle plus the delayed
// overlay resume (~350ms) and decode.
private const val RELEASE_DELAY_MS = 1000L

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
        }
      }
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
      if (window.colorMode != ActivityInfo.COLOR_MODE_HDR) {
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
      }
      // Headroom stays pinned; per-photo gradual brightening is done by animating
      // the photo's own gainmap ratios (immune to the system's slow smoothing of
      // headroom changes, which caused visible brightness flicker between photos).
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        window.desiredHdrHeadroom = MAX_DESIRED_HEADROOM
      }
    }

    private fun exitHdrMode() {
      val window = activity?.window ?: return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        window.desiredHdrHeadroom = 0f
      }
      window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
    }
  }
}
