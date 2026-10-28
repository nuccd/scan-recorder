package io.github.g00fy2.quickie

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Size
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.github.g00fy2.quickie.config.BarcodeFormat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Embedded text barcode scanner. The host requests CAMERA permission before calling [start]. */
@Suppress("TooManyFunctions") // Public view facade delegates camera controls and diagnostics to dedicated helpers.
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
  private var tuning = ScanTuningOptions()
  private var trigger = ScanTrigger()
  private val cameraControls = ScanCameraControls(previewView, mainExecutor) { stableCandidate.reset() }
  private val diagnostics = ScanDiagnosticsTracker()
  private var lastAmbiguous: Boolean? = null
  private var onAmbiguous: (Boolean) -> Unit = {}
  private var onDiagnostics: (ScanFrameDiagnostics) -> Unit = {}

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
    diagnostics.reset()
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
        val frameRevision = AtomicLong()
        val frameAnalyzer = createAnalyzer(formats, startGeneration, frameRevision, onScan, onError)
        val boundCamera = bindCamera(cameraProvider, owner, frameAnalyzer, frameRevision)
        camera = boundCamera
        cameraControls.bind(boundCamera, owner)
        onReady(boundCamera.cameraInfo.hasFlashUnit())
      } catch (exception: Exception) {
        if (startGeneration == generation) {
          stop()
          onError(exception)
        }
      }
    }, mainExecutor)
  }

  @OptIn(ExperimentalGetImage::class)
  private fun bindCamera(
    cameraProvider: ProcessCameraProvider,
    owner: LifecycleOwner,
    frameAnalyzer: QRCodeAnalyzer,
    frameRevision: AtomicLong
  ): Camera {
    val cameraPreview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
    val cameraAnalysis = createAnalysis()
    preview = cameraPreview
    analysis = cameraAnalysis
    analyzer = frameAnalyzer
    cameraAnalysis.setAnalyzer(executor) { image ->
      frameRevision.set(cameraControls.revision)
      frameAnalyzer.analyze(image)
    }
    return cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, cameraAnalysis)
  }

  private fun createAnalysis(): ImageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setResolutionSelector(
      ResolutionSelector.Builder().setResolutionStrategy(
        ResolutionStrategy(
          Size(tuning.resolution.width, tuning.resolution.height),
          ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
        )
      ).build()
    ).build()

  private fun createAnalyzer(
    formats: List<BarcodeFormat>,
    session: Int,
    frameRevision: AtomicLong,
    onScan: (String) -> Unit,
    onError: (Exception) -> Unit
  ): QRCodeAnalyzer = QRCodeAnalyzer(
    barcodeFormats = formats.map { it.value }.toIntArray(),
    onSuccess = {},
    onFailure = { exception ->
      mainExecutor.execute { if (session == generation) onError(exception) }
    },
    onPassCompleted = {},
    onFrame = { codes, image, metrics ->
      if (session == generation) processFrame(codes, image, metrics, frameRevision.get(), onScan)
    },
    tuning = tuning
  )

  private fun processFrame(
    codes: List<DecodedBarcode>,
    image: ImageProxy,
    metrics: DecodeFrameMetrics,
    frameRevision: Long,
    onScan: (String) -> Unit
  ) {
    val decoded = codes.filter { it.rawValue.isNotEmpty() }
    val imageToView = previewView.sensorToViewTransform?.let { sensorToView ->
      BarcodeCoordinates.imageToView(
        image.imageInfo.sensorToBufferTransformMatrix,
        image.imageInfo.rotationDegrees,
        image.width,
        image.height,
        sensorToView
      )
    }
    if (imageToView == null) {
      stableCandidate.reset()
      reportFrame(image, metrics, decoded.size, emptyList(), ScanFrameStatus.TRANSFORM_UNAVAILABLE)
      return
    }
    val window = scanWindow ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
    val inWindow = decoded.mapNotNull { code ->
      code.rawValue.takeIf { BarcodeCoordinates.isInsideWindow(code.bounds, imageToView, window) }
    }
    if (!cameraControls.acceptsFrame(frameRevision)) {
      stableCandidate.reset()
      reportFrame(image, metrics, decoded.size, inWindow, ScanFrameStatus.CAMERA_ADJUSTING)
      return
    }
    val visibleValues = inWindow.toSet()
    val now = SystemClock.elapsedRealtime()
    val target = findTarget(visibleValues, decoded.isNotEmpty(), now)
    val delivered = trigger.next(visibleValues, target, now)
    val status = when {
      !deliveryEnabled -> ScanFrameStatus.REVIEW_PAUSED
      delivered != null -> ScanFrameStatus.DELIVERED
      decoded.isEmpty() -> ScanFrameStatus.NO_DECODE
      visibleValues.isEmpty() -> ScanFrameStatus.OUTSIDE_WINDOW
      visibleValues.size > 1 -> ScanFrameStatus.MULTIPLE_VALUES
      target == null -> ScanFrameStatus.WAITING_FOR_STABILITY
      else -> ScanFrameStatus.ALREADY_REPORTED
    }
    reportFrame(image, metrics, decoded.size, inWindow, status)
    delivered?.let(onScan)
  }

  private fun findTarget(visibleValues: Set<String>, hasDecoded: Boolean, now: Long): String? {
    if (!deliveryEnabled) {
      stableCandidate.reset()
      return null
    }
    val ambiguous = visibleValues.size > 1
    // Only an undecoded frame may retain brief evidence; ambiguity and known out-of-window codes must clear it.
    if (ambiguous || (visibleValues.isEmpty() && hasDecoded)) stableCandidate.reset()
    if (lastAmbiguous != ambiguous) {
      lastAmbiguous = ambiguous
      onAmbiguous(ambiguous)
    }
    return stableCandidate.next(visibleValues.singleOrNull(), now)
  }

  private fun reportFrame(
    image: ImageProxy,
    metrics: DecodeFrameMetrics,
    decodedCount: Int,
    inWindow: List<String>,
    status: ScanFrameStatus
  ) {
    diagnostics.record(
      image.width, image.height, image.imageInfo.rotationDegrees, metrics.decodeMillis,
      decodedCount, inWindow.size, inWindow.toSet().size, status, SystemClock.elapsedRealtime(), metrics
    )?.let(onDiagnostics)
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
      cameraControls.setInteractionEnabled(enabled)
    }
  }

  public fun setAmbiguityListener(listener: (Boolean) -> Unit) {
    onAmbiguous = listener
  }

  /** Receives actual zoom changes on the main thread; null means the camera is unavailable. */
  public fun setZoomStateListener(listener: (ScanZoomState?) -> Unit) {
    cameraControls.setZoomListener(listener)
  }

  /** Retained across stop/start, clamped to the bound camera's supported range. */
  public fun setZoomRatio(ratio: Float) {
    cameraControls.setZoomRatio(ratio)
  }

  /** Main-thread feedback for taps in the preview; focusing is disabled during result review. */
  public fun setFocusStateListener(listener: (ScanFocusState) -> Unit) {
    cameraControls.setFocusListener(listener)
  }

  /** Receives throttled frame diagnostics without barcode contents on the main thread. */
  public fun setDiagnosticsListener(listener: (ScanFrameDiagnostics) -> Unit) {
    onDiagnostics = listener
  }

  /** Apply experimental options while stopped, before [start]. */
  public fun setTuningOptions(options: ScanTuningOptions) {
    check(analysis == null) { "Stop the scanner before changing tuning options" }
    tuning = options
    stableCandidate = StableScanCandidate(
      options.minimumFrames,
      options.minimumMillis,
      options.maximumGapMillis,
      options.maximumObservationMillis
    )
    cameraControls.focusWaitMillis = options.focusWaitMillis
  }

  /** Clears diagnostic counters without interrupting camera capture or result review. */
  public fun resetDiagnostics() {
    diagnostics.reset()
  }

  public fun setTorch(enabled: Boolean) {
    camera?.cameraControl?.enableTorch(enabled)
  }

  public fun stop() {
    generation++
    cameraControls.unbind()
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