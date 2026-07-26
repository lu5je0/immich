package app.alextran.immich.hdr

import android.content.ContentUris
import android.content.Context
import android.graphics.ImageDecoder
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.MediaStore.Images
import android.view.Display
import androidx.annotation.RequiresApi
import app.alextran.immich.core.HttpClientManager
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max

private const val CACHE_DIR_NAME = "uhdr"
private const val MAX_CACHE_BYTES = 512L * 1024 * 1024

private class HdrRequest(val taskFuture: Future<*>, val cancellationSignal: CancellationSignal)

class HdrImagesImpl(context: Context) : HdrImageApi {
  private val ctx: Context = context.applicationContext
  private val resolver = ctx.contentResolver
  private val threadPool = Executors.newFixedThreadPool(2)
  private val requestMap = ConcurrentHashMap<Long, HdrRequest>()
  private val gainmapCache = ConcurrentHashMap<String, Boolean>()
  private val cacheDir = File(ctx.cacheDir, CACHE_DIR_NAME)

  companion object {
    val CANCELLED = Result.success<String?>(null)
  }

  init {
    threadPool.execute(::trimCache)
  }

  override fun isUltraHdrSupported(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    val displayManager = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    return displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.isHdr == true
  }

  override fun getUltraHdrSource(
    localId: String?,
    remoteUrl: String?,
    requestId: Long,
    callback: (Result<String?>) -> Unit
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      return callback(Result.success(null))
    }

    val signal = CancellationSignal()
    val task = threadPool.submit {
      try {
        val source = when {
          localId != null -> getLocalSource(localId, signal)
          remoteUrl != null -> getRemoteSource(remoteUrl, signal)
          else -> null
        }
        callback(Result.success(source))
      } catch (e: Exception) {
        when (e) {
          is OperationCanceledException, is CancellationException -> callback(CANCELLED)
          else -> callback(Result.failure(e))
        }
      } finally {
        requestMap.remove(requestId)
      }
    }
    requestMap[requestId] = HdrRequest(task, signal)
  }

  override fun cancelRequest(requestId: Long) {
    val request = requestMap.remove(requestId) ?: return
    request.cancellationSignal.cancel()
    request.taskFuture.cancel(false)
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private fun getLocalSource(localId: String, signal: CancellationSignal): String? {
    gainmapCache["local:$localId"]?.let { hasGainmap ->
      return if (hasGainmap) localUri(localId).toString() else null
    }

    signal.throwIfCanceled()
    val uri = localUri(localId)
    val mime = resolver.getType(uri)
    if (mime != null && mime != "image/jpeg") {
      gainmapCache["local:$localId"] = false
      return null
    }

    val hasGainmap = try {
      probeGainmap(ImageDecoder.createSource(resolver, uri), signal)
    } catch (e: Exception) {
      if (e is OperationCanceledException) throw e
      false
    }
    gainmapCache["local:$localId"] = hasGainmap
    return if (hasGainmap) uri.toString() else null
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private fun getRemoteSource(remoteUrl: String, signal: CancellationSignal): String? {
    val key = sha1(remoteUrl)
    val file = File(cacheDir, "$key.img")
    val noHdrMarker = File(cacheDir, "$key.nohdr")

    gainmapCache["remote:$key"]?.let { hasGainmap ->
      if (!hasGainmap) return null
      if (file.exists()) return file.absolutePath
    }
    if (noHdrMarker.exists()) return null

    if (!file.exists()) {
      downloadToFile(remoteUrl, file, signal)
    }

    signal.throwIfCanceled()
    val hasGainmap = try {
      probeGainmap(ImageDecoder.createSource(file), signal)
    } catch (e: Exception) {
      if (e is OperationCanceledException) throw e
      false
    }
    gainmapCache["remote:$key"] = hasGainmap
    if (!hasGainmap) {
      // The original is not Ultra HDR; drop it and remember, so future views never
      // re-download a full-size file just to probe it again.
      file.delete()
      cacheDir.mkdirs()
      noHdrMarker.createNewFile()
      return null
    }
    return file.absolutePath
  }

  private fun downloadToFile(url: String, target: File, signal: CancellationSignal) {
    signal.throwIfCanceled()
    cacheDir.mkdirs()
    val tmp = File(cacheDir, "${target.name}.tmp")

    val requestBuilder = Request.Builder().url(url)
    HttpClientManager.getAuthHeaders(url).forEach { (key, value) ->
      requestBuilder.addHeader(key, value)
    }
    val call = HttpClientManager.getClient().newCall(requestBuilder.build())
    signal.setOnCancelListener(call::cancel)

    try {
      call.execute().use { response ->
        if (!response.isSuccessful) {
          throw IOException("HTTP ${response.code}: ${response.message}")
        }
        val body = response.body ?: throw IOException("Empty response body")
        tmp.outputStream().use { output ->
          body.byteStream().copyTo(output)
        }
      }
      signal.throwIfCanceled()
      if (!tmp.renameTo(target)) {
        throw IOException("Could not move download into cache")
      }
      threadPool.execute(::trimCache)
    } catch (e: Exception) {
      tmp.delete()
      if (call.isCanceled()) throw OperationCanceledException()
      throw e
    } finally {
      signal.setOnCancelListener(null)
    }
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private fun probeGainmap(source: ImageDecoder.Source, signal: CancellationSignal): Boolean {
    signal.throwIfCanceled()
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      decoder.setTargetSampleSize(max(1, max(info.size.width, info.size.height) / 64))
    }
    val hasGainmap = bitmap.hasGainmap()
    bitmap.recycle()
    return hasGainmap
  }

  private fun localUri(localId: String): Uri =
    ContentUris.withAppendedId(Images.Media.EXTERNAL_CONTENT_URI, localId.toLong())

  private fun trimCache() {
    val files = cacheDir.listFiles() ?: return
    var total = files.sumOf { it.length() }
    if (total <= MAX_CACHE_BYTES) return
    for (file in files.sortedBy { it.lastModified() }) {
      if (total <= MAX_CACHE_BYTES) break
      val length = file.length()
      if (file.delete()) total -= length
    }
  }

  private fun sha1(value: String): String =
    MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
      .joinToString("") { "%02x".format(it) }
}
