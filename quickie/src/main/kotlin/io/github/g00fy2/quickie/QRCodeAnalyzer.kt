package io.github.g00fy2.quickie

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

internal class QRCodeAnalyzer(
  barcodeFormats: IntArray,
  private val onSuccess: (Barcode) -> Unit,
  private val onFailure: (Exception) -> Unit,
  private val onPassCompleted: (Boolean) -> Unit,
  private val onFrame: ((List<DecodedBarcode>, ImageProxy, DecodeFrameMetrics) -> Unit)? = null,
  private val tuning: ScanTuningOptions = ScanTuningOptions()
) : ImageAnalysis.Analyzer {
  private val plan = ScanDecodePlan(tuning, barcodeFormats, onFrame != null)
  private val mainHandler = Handler(Looper.getMainLooper())
  private val mainExecutor = Executor { mainHandler.post(it) }
  private val scannerLock = Any()
  private val barcodeScannerDelegate = lazy {
    val formats = plan.formats
    BarcodeScanning.getClient(
      BarcodeScannerOptions.Builder().setBarcodeFormats(formats.first(), *formats.drop(1).toIntArray()).build()
    )
  }
  private val barcodeScanner get() = synchronized(scannerLock) {
    check(!closed) { "Analyzer is closed" }
    barcodeScannerDelegate.value
  }
  private val linearDecoder by lazy { LinearBarcodeDecoder(plan.formats, tuning) }
  private var nativeAvailable = true

  @Volatile private var failureTimestamp: Long? = null

  @Volatile private var closed = false

  @ExperimentalGetImage
  override fun analyze(imageProxy: ImageProxy) {
    val failedAt = failureTimestamp
    if (closed || (failedAt != null && SystemClock.elapsedRealtime() - failedAt < 1000L)) {
      imageProxy.close()
      return
    }
    val startedAt = SystemClock.elapsedRealtime()
    try {
      if (plan.useMlKit) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
          imageProxy.close()
        } else {
          val task = barcodeScanner.process(InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees))
          // Record completion before native work so its wait cannot inflate the ML Kit duration.
          val mlKitMillis = AtomicLong()
          task.addOnCompleteListener(DIRECT_EXECUTOR) { mlKitMillis.set(elapsed(startedAt)) }
          val nativeStarted = SystemClock.elapsedRealtime()
          val nativeCodes = if (plan.useNative) decodeNative(imageProxy) else emptyList()
          val nativeMillis = if (plan.useNative) elapsed(nativeStarted) else 0L
          task.addOnCompleteListener(mainExecutor) {
            completeMlKit(
              task,
              imageProxy,
              nativeCodes,
              DecodeFrameMetrics(
                elapsed(startedAt),
                mlKitMillis.get(),
                nativeMillis,
                nativeCount = nativeCodes.size,
                nativeAvailable = nativeAvailable
              )
            )
          }
        }
      } else {
        val codes = decodeNative(imageProxy)
        val nativeMillis = elapsed(startedAt)
        mainExecutor.execute {
          completeFrame(imageProxy) {
            onFrame?.invoke(
              codes,
              imageProxy,
              DecodeFrameMetrics(
                elapsed(startedAt),
                nativeMillis = nativeMillis,
                nativeCount = codes.size,
                nativeAvailable = nativeAvailable
              )
            )
          }
        }
      }
    } catch (exception: Exception) {
      mainExecutor.execute { completeFrame(imageProxy) { reportFailure(exception) } }
    }
  }

  private fun completeMlKit(
    task: Task<List<Barcode>>,
    image: ImageProxy,
    nativeCodes: List<DecodedBarcode>,
    metrics: DecodeFrameMetrics
  ) {
    completeFrame(image) {
      if (task.isSuccessful) {
        val codes = task.result
        if (onFrame == null) codes.firstOrNull()?.let(onSuccess)
        onFrame?.invoke(toDecodedBarcodes(codes) + nativeCodes, image, metrics.copy(mlKitCount = codes.size))
      } else if (nativeCodes.isNotEmpty()) {
        onFrame?.invoke(nativeCodes, image, metrics)
      } else {
        reportFailure(task.exception ?: IllegalStateException("ML Kit decoding did not complete"))
      }
    }
  }

  private fun completeFrame(image: ImageProxy, callback: () -> Unit) {
    try {
      if (!closed) {
        failureTimestamp = null
        callback()
        onPassCompleted(failureTimestamp != null)
      }
    } finally {
      image.close()
    }
  }

  private fun reportFailure(exception: Exception) {
    failureTimestamp = SystemClock.elapsedRealtime()
    onFailure(exception)
  }

  private fun decodeNative(image: ImageProxy): List<DecodedBarcode> {
    if (!nativeAvailable) return emptyList()
    return try {
      linearDecoder.decode(image)
    } catch (error: LinkageError) {
      nativeAvailable = false
      Log.w("SCAN_DIAG", "native linear decoder unavailable type=${error.javaClass.simpleName}")
      emptyList()
    } catch (exception: Exception) {
      nativeAvailable = false
      Log.w("SCAN_DIAG", "native linear decoder disabled type=${exception.javaClass.simpleName}")
      emptyList()
    }
  }

  private fun toDecodedBarcodes(codes: List<Barcode>): List<DecodedBarcode> = codes.mapNotNull { code ->
    val value = code.rawValue?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
    val bounds = code.boundingBox ?: return@mapNotNull null
    DecodedBarcode(value, RectF(bounds))
  }

  fun close() {
    synchronized(scannerLock) {
      closed = true
      if (barcodeScannerDelegate.isInitialized()) barcodeScannerDelegate.value.close()
    }
  }

  private companion object {
    val DIRECT_EXECUTOR = Executor { it.run() }
    fun elapsed(startedAt: Long): Long = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
  }
}