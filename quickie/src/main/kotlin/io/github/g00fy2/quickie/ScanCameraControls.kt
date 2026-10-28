package io.github.g00fy2.quickie

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ZoomState
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Main-thread camera controls; only [revision] is read by the analysis executor. */
internal class ScanCameraControls(
  private val preview: PreviewView,
  private val mainExecutor: Executor,
  private val onGeometryChanged: () -> Unit
) {
  private val adjustments = ScanControlAdjustments()
  private val handler = Handler(Looper.getMainLooper())
  private var camera: Camera? = null
  private var zoomObserver: Observer<ZoomState>? = null
  private var requestedZoom = 1f
  private var zoomState: ScanZoomState? = null
  private var focusState = ScanFocusState(0f, 0f, ScanFocusStatus.HIDDEN)
  private var interactionEnabled = true
  private var focusVisible = false
  private var zoomListener: (ScanZoomState?) -> Unit = {}
  private var focusListener: (ScanFocusState) -> Unit = {}
  private var releaseFocusFrames = Runnable {}
  private val hideFocus = Runnable {
    focusVisible = false
    publishFocus(focusState.copy(status = ScanFocusStatus.HIDDEN))
  }

  val revision: Long get() = adjustments.revision

  init {
    installTapListener()
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun installTapListener() {
    val detector = GestureDetector(
      preview.context,
      object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onSingleTapUp(event: MotionEvent): Boolean {
          preview.performClick()
          focusAt(event.x, event.y)
          return true
        }
      }
    )
    preview.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
  }

  fun bind(boundCamera: Camera, owner: LifecycleOwner) {
    unbind()
    camera = boundCamera
    zoomObserver = Observer<ZoomState> { actual ->
      if (camera === boundCamera) {
        if (zoomState == null) {
          Log.i(DIAGNOSTIC_TAG, "camera ready zoomRange=${actual.minZoomRatio}..${actual.maxZoomRatio}")
        }
        zoomState = ScanZoomState(actual.zoomRatio, actual.minZoomRatio, actual.maxZoomRatio)
        zoomListener(zoomState)
      }
    }.also { boundCamera.cameraInfo.zoomState.observe(owner, it) }
    applyZoom(boundCamera)
  }

  fun setZoomListener(listener: (ScanZoomState?) -> Unit) {
    zoomListener = listener
    listener(zoomState)
  }

  fun setFocusListener(listener: (ScanFocusState) -> Unit) {
    focusListener = listener
    listener(focusState)
  }

  fun setZoomRatio(ratio: Float) {
    require(ratio.isFinite() && ratio > 0f) { "Zoom ratio must be finite and positive" }
    requestedZoom = ratio
    camera?.let(::applyZoom)
  }

  private fun applyZoom(boundCamera: Camera) {
    val state = boundCamera.cameraInfo.zoomState.value ?: return
    val ratio = requestedZoom.coerceIn(state.minZoomRatio, state.maxZoomRatio)
    requestedZoom = ratio
    val token = adjustments.begin(ScanControlAdjustments.Operation.ZOOM)
    onGeometryChanged()
    try {
      val result = boundCamera.cameraControl.setZoomRatio(ratio)
      result.addListener({
        if (adjustments.finish(token)) {
          onGeometryChanged()
          // The observer remains authoritative, including when this request was rejected.
          try {
            result.get().let { Log.i(DIAGNOSTIC_TAG, "zoom applied ratio=${zoomState?.ratio}") }
          } catch (exception: Exception) {
            requestedZoom = zoomState?.ratio ?: requestedZoom
            Log.i(DIAGNOSTIC_TAG, "zoom failed type=${exception.javaClass.simpleName}")
          }
        }
      }, mainExecutor)
    } catch (exception: Exception) {
      if (adjustments.finish(token)) onGeometryChanged()
      requestedZoom = zoomState?.ratio ?: requestedZoom
      Log.i(DIAGNOSTIC_TAG, "zoom failed type=${exception.javaClass.simpleName}")
    }
  }

  private fun focusAt(x: Float, y: Float) {
    val boundCamera = camera
    val previewReady = preview.width > 0 && preview.height > 0
    if (boundCamera == null || !interactionEnabled || !previewReady) return
    handler.removeCallbacks(hideFocus)
    handler.removeCallbacks(releaseFocusFrames)
    focusVisible = true
    val position = ScanFocusState(x + preview.left, y + preview.top, ScanFocusStatus.FOCUSING)
    val token = adjustments.begin(ScanControlAdjustments.Operation.FOCUS)
    onGeometryChanged()
    handler.postDelayed(hideFocus, FOCUS_FEEDBACK_MILLIS)
    try {
      val point = preview.meteringPointFactory.createPoint(x, y)
      val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        .setAutoCancelDuration(FOCUS_AUTO_CANCEL_MILLIS, TimeUnit.MILLISECONDS).build()
      val autofocus = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
      if (!boundCamera.cameraInfo.isFocusMeteringSupported(autofocus)) {
        adjustments.finish(token)
        onGeometryChanged()
        publishFocus(position.copy(status = ScanFocusStatus.UNSUPPORTED))
        return
      }
      publishFocus(position)
      releaseFocusFrames = Runnable {
        if (adjustments.allowFrames(token)) {
          onGeometryChanged()
          Log.i(DIAGNOSTIC_TAG, "focus pending; analysis resumed after ${FOCUS_FRAME_WAIT_MILLIS}ms")
        }
      }
      handler.postDelayed(releaseFocusFrames, FOCUS_FRAME_WAIT_MILLIS)
      val result = boundCamera.cameraControl.startFocusAndMetering(action)
      result.addListener({
        if (adjustments.finish(token)) {
          onGeometryChanged()
          val status = try {
            if (result.get().isFocusSuccessful) ScanFocusStatus.FOCUSED else ScanFocusStatus.FAILED
          } catch (_: Exception) {
            ScanFocusStatus.FAILED
          }
          if (focusVisible) publishFocus(position.copy(status = status))
        }
      }, mainExecutor)
    } catch (_: Exception) {
      if (adjustments.finish(token)) onGeometryChanged()
      publishFocus(position.copy(status = ScanFocusStatus.FAILED))
    }
  }

  fun setInteractionEnabled(enabled: Boolean) {
    interactionEnabled = enabled
    if (!enabled) {
      adjustments.cancel(ScanControlAdjustments.Operation.FOCUS)
      onGeometryChanged()
      handler.removeCallbacks(hideFocus)
      handler.removeCallbacks(releaseFocusFrames)
      hideFocus.run()
      try {
        camera?.cameraControl?.cancelFocusAndMetering()
      } catch (_: Exception) {
        // A camera closing at the same time must not break result review.
      }
    }
  }

  fun acceptsFrame(frameRevision: Long): Boolean = adjustments.acceptsFrame(frameRevision)

  fun unbind() {
    adjustments.clear()
    onGeometryChanged()
    zoomObserver?.let { camera?.cameraInfo?.zoomState?.removeObserver(it) }
    zoomObserver = null
    camera = null
    zoomState = null
    zoomListener(null)
    handler.removeCallbacks(hideFocus)
    handler.removeCallbacks(releaseFocusFrames)
    hideFocus.run()
  }

  private fun publishFocus(state: ScanFocusState) {
    focusState = state
    if (state.status != ScanFocusStatus.HIDDEN) {
      Log.i(DIAGNOSTIC_TAG, "focus status=${state.status} x=${state.x} y=${state.y}")
    }
    focusListener(state)
  }

  private companion object {
    const val FOCUS_FEEDBACK_MILLIS = 3000L
    const val FOCUS_AUTO_CANCEL_MILLIS = 3000L
    const val FOCUS_FRAME_WAIT_MILLIS = 300L
    const val DIAGNOSTIC_TAG = "SCAN_DIAG"
  }
}