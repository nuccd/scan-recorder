package io.github.g00fy2.quickie

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Size
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.common.Barcode
import io.github.g00fy2.quickie.config.BarcodeFormat
import java.util.concurrent.Executors

/** Embedded text barcode scanner. The host requests CAMERA permission before calling [start]. */
public class ContinuousScannerView @JvmOverloads public constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
  private val previewView = PreviewView(context)
  private val executor = Executors.newSingleThreadExecutor()
  private val mainExecutor = ContextCompat.getMainExecutor(context)
  private var provider: ProcessCameraProvider? = null
  private var preview: Preview? = null
  private var analysis: ImageAnalysis? = null
  private var analyzer: QRCodeAnalyzer? = null
  private var camera: Camera? = null
  private var generation = 0
  private var released = false
  private var scanWindow: RectF? = null
  private var deliveryEnabled = true
  private var stableCandidate = StableScanCandidate()
  private var trigger = ScanTrigger()
  private var lastAmbiguous: Boolean? = null
  private var onAmbiguous: (Boolean) -> Unit = {}

  init {
    addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
  }

  /** Stable values are delivered once until they have left the scan window for at least 500 ms. */
  public fun start(
    owner: LifecycleOwner,
    formats: List<BarcodeFormat> = listOf(BarcodeFormat.FORMAT_ALL_FORMATS),
    onReady: (Boolean) -> Unit = {},
    onScan: (String) -> Unit,
    onError: (Exception) -> Unit
  ) {
    check(!released) { "Scanner has already been released" }
    require(formats.isNotEmpty()) { "At least one barcode format is required" }
    stop()
    val startGeneration = generation
    trigger = ScanTrigger()
    stableCandidate.reset()
    lastAmbiguous = null
    val future = try {
      ProcessCameraProvider.getInstance(context)
    } catch (exception: Exception) {
      stop()
      onError(exception)
      return
    }
    future.addListener({
      if (released || startGeneration != generation) return@addListener
      try {
        val cameraProvider = future.get()
        provider = cameraProvider
        val cameraPreview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val cameraAnalysis = createAnalysis()
        val frameAnalyzer = createAnalyzer(formats, startGeneration, onScan, onError)
        preview = cameraPreview
        analysis = cameraAnalysis
        analyzer = frameAnalyzer
        cameraAnalysis.setAnalyzer(executor, frameAnalyzer)
        camera = cameraProvider.bindToLifecycle(
          owner,
          CameraSelector.DEFAULT_BACK_CAMERA,
          cameraPreview,
          cameraAnalysis
        )
        onReady(camera?.cameraInfo?.hasFlashUnit() == true)
      } catch (exception: Exception) {
        if (startGeneration == generation) {
          stop()
          onError(exception)
        }
      }
    }, mainExecutor)
  }

  private fun createAnalysis(): ImageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setResolutionSelector(
      ResolutionSelector.Builder().setResolutionStrategy(
        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
      ).build()
    ).build()

  private fun createAnalyzer(
    formats: List<BarcodeFormat>,
    session: Int,
    onScan: (String) -> Unit,
    onError: (Exception) -> Unit
  ): QRCodeAnalyzer = QRCodeAnalyzer(
    barcodeFormats = formats.map { it.value }.toIntArray(),
    onSuccess = {},
    onFailure = { exception ->
      mainExecutor.execute { if (session == generation) onError(exception) }
    },
    onPassCompleted = {},
    onFrame = { codes, image -> if (session == generation) processFrame(codes, image, onScan) }
  )

  private fun processFrame(codes: List<Barcode>, image: ImageProxy, onScan: (String) -> Unit) {
    val sensorToView = previewView.sensorToViewTransform ?: return
    val imageToView = BarcodeCoordinates.imageToView(
      image.imageInfo.sensorToBufferTransformMatrix,
      image.imageInfo.rotationDegrees,
      image.width,
      image.height,
      sensorToView
    ) ?: return
    val window = scanWindow ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
    val visibleValues = codes.mapNotNull { code ->
      val bounds = code.boundingBox?.let(::RectF)
      val inside = bounds != null && BarcodeCoordinates.isInsideWindow(bounds, imageToView, window)
      code.rawValue?.takeIf { it.isNotEmpty() && inside }
    }.toSet()
    val now = SystemClock.elapsedRealtime()
    val target = if (deliveryEnabled) {
      val ambiguous = visibleValues.size > 1
      if (lastAmbiguous != ambiguous) {
        lastAmbiguous = ambiguous
        onAmbiguous(ambiguous)
      }
      stableCandidate.next(visibleValues.singleOrNull(), now)
    } else {
      stableCandidate.reset()
      null
    }
    trigger.next(visibleValues, target, now)?.let(onScan)
  }

  /** Bounds are relative to this view, and the complete decoded box must fit inside them. */
  public fun setScanWindow(bounds: RectF) {
    if (scanWindow != bounds) {
      scanWindow = RectF(bounds)
      stableCandidate.reset()
    }
  }

  /** Keep preview and presence tracking running while the host reviews a result. */
  public fun setResultDeliveryEnabled(enabled: Boolean) {
    if (deliveryEnabled != enabled) {
      deliveryEnabled = enabled
      stableCandidate.reset()
      lastAmbiguous = null
    }
  }

  public fun setAmbiguityListener(listener: (Boolean) -> Unit) {
    onAmbiguous = listener
  }

  public fun setTorch(enabled: Boolean) {
    camera?.cameraControl?.enableTorch(enabled)
  }

  public fun stop() {
    generation++
    analysis?.clearAnalyzer()
    preview?.let { provider?.unbind(it) }
    analysis?.let { provider?.unbind(it) }
    analyzer?.close()
    analyzer = null
    analysis = null
    preview = null
    camera = null
  }

  /** Call when the host view is permanently destroyed. */
  public fun release() {
    stop()
    released = true
    executor.shutdown()
  }
}