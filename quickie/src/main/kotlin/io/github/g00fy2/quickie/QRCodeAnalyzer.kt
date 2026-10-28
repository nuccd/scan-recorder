package io.github.g00fy2.quickie

import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

internal class QRCodeAnalyzer(
  private val barcodeFormats: IntArray,
  private val onSuccess: ((Barcode) -> Unit),
  private val onFailure: ((Exception) -> Unit),
  private val onPassCompleted: ((Boolean) -> Unit),
  private val onFrame: ((List<DecodedBarcode>, ImageProxy, Long) -> Unit)? = null
) : ImageAnalysis.Analyzer {

  private val barcodeScannerDelegate = lazy {
    val optionsBuilder = if (barcodeFormats.size > 1) {
      BarcodeScannerOptions.Builder().setBarcodeFormats(barcodeFormats.first(), *barcodeFormats.drop(1).toIntArray())
    } else {
      BarcodeScannerOptions.Builder().setBarcodeFormats(barcodeFormats.firstOrNull() ?: Barcode.FORMAT_UNKNOWN)
    }
    try {
      BarcodeScanning.getClient(optionsBuilder.build())
    } catch (e: Exception) { // catch if for some reason MlKitContext has not been initialized
      onFailure(e)
      null
    }
  }

  private val barcodeScanner by barcodeScannerDelegate
  private val linearDecoder by lazy { LinearBarcodeDecoder(barcodeFormats) }
  private var linearDecoderAvailable = true
  private var linearDecoderReported = false

  private var failureOccurred = false
  private var failureTimestamp = 0L

  @Volatile private var closed = false

  @ExperimentalGetImage
  override fun analyze(imageProxy: ImageProxy) {
    val mediaImage = imageProxy.image

    // skip analysis if no media image or error occurred in previous pass
    val coolingDown = failureOccurred && System.currentTimeMillis() - failureTimestamp < 1000L
    if (closed || mediaImage == null || coolingDown) {
      imageProxy.close()
      return
    }

    failureOccurred = false
    val scanner = barcodeScanner
    if (scanner == null || closed) {
      scanner?.close()
      imageProxy.close()
      return
    }
    try {
      val startedAt = SystemClock.elapsedRealtime()
      val task = scanner.process(InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees))
      // Both engines only read this image. Keep it open until native decoding and the ML Kit task have finished.
      // This runs on the analysis executor, not the UI thread; legacy single-scan users keep their original path.
      val linearCodes = if (onFrame == null) emptyList() else decodeLinear(imageProxy)
      task
        .addOnSuccessListener { codes ->
          val decodeMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
          onFrame?.invoke(toDecodedBarcodes(codes) + linearCodes, imageProxy, decodeMillis)
          if (onFrame == null) codes.firstOrNull()?.let(onSuccess)
        }
        .addOnFailureListener {
          failureOccurred = true
          failureTimestamp = System.currentTimeMillis()
          onFailure(it)
        }
        .addOnCompleteListener {
          try {
            onPassCompleted(failureOccurred)
          } finally {
            imageProxy.close()
          }
        }
    } catch (exception: Exception) {
      imageProxy.close()
      onFailure(exception)
    }
  }

  private fun decodeLinear(image: ImageProxy): List<DecodedBarcode> {
    if (!linearDecoderAvailable) return emptyList()
    return try {
      val startedAt = SystemClock.elapsedRealtime()
      linearDecoder.decode(image).also { codes ->
        if (codes.isNotEmpty() && !linearDecoderReported) {
          linearDecoderReported = true
          val elapsed = SystemClock.elapsedRealtime() - startedAt
          Log.i("SCAN_DIAG", "native linear decoder active count=${codes.size} elapsedMs=$elapsed")
        }
      }
    } catch (error: LinkageError) {
      linearDecoderAvailable = false
      Log.w("SCAN_DIAG", "native linear decoder unavailable type=${error.javaClass.simpleName}")
      emptyList()
    } catch (exception: Exception) {
      linearDecoderAvailable = false
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
    closed = true
    if (barcodeScannerDelegate.isInitialized()) barcodeScanner?.close()
  }
}