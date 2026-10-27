package io.github.nuccd.scanrecorder

import android.os.Bundle
import android.widget.SeekBar
import io.github.g00fy2.quickie.ScanFocusStatus
import io.github.g00fy2.quickie.ScanZoomState
import io.github.nuccd.scanrecorder.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Displays camera-reported magnification; restoring a value always goes through CameraX. */
internal class ScannerCameraUi(
  private val binding: ActivityMainBinding,
  savedState: Bundle?,
  private val requestZoom: (Float) -> Unit = binding.scanner::setZoomRatio
) {
  private var desiredRatio = savedState?.getFloat(ZOOM_STATE_KEY, 1f)?.takeIf { it.isFinite() && it > 0 } ?: 1f
  private var zoom: ScanZoomState? = null
  private var active = false
  private var tracking = false
  private var restorePending = true

  init {
    binding.zoomSlider.max = ZOOM_STEPS
    binding.zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        val current = zoom ?: return
        if (fromUser && binding.zoomSlider.isEnabled) {
          desiredRatio = ratioAt(progress, current)
          requestZoom(desiredRatio)
        }
      }

      override fun onStartTrackingTouch(seekBar: SeekBar?) {
        tracking = true
      }

      override fun onStopTrackingTouch(seekBar: SeekBar?) {
        tracking = false
        zoom?.let { current -> binding.zoomSlider.progress = progressAt(current.ratio, current) }
      }
    })
    binding.scanner.setZoomStateListener(::updateZoomState)
    binding.scanner.setFocusStateListener { state ->
      if (active || state.status == ScanFocusStatus.HIDDEN) binding.focusIndicator.show(state)
    }
    updateZoomState(null)
  }

  fun setActive(value: Boolean) {
    active = value
    updateEnabled()
    if (!value) {
      tracking = false
      zoom?.let { current -> binding.zoomSlider.progress = progressAt(current.ratio, current) }
      binding.focusIndicator.hide()
    }
  }

  fun saveState(state: Bundle) {
    state.putFloat(ZOOM_STATE_KEY, desiredRatio)
  }

  fun updateZoomState(state: ScanZoomState?) {
    zoom = state
    var restoredRatio: Float? = null
    if (state == null) {
      restorePending = true
      tracking = false
    } else {
      if (restorePending) {
        restorePending = false
        desiredRatio = desiredRatio.coerceIn(state.minRatio, state.maxRatio)
        if (abs(state.ratio - desiredRatio) > ZOOM_EPSILON) restoredRatio = desiredRatio
      } else if (!tracking) {
        desiredRatio = state.ratio
      }
      if (!tracking) binding.zoomSlider.progress = progressAt(state.ratio, state)
    }
    binding.zoomValue.text = binding.root.context.getString(R.string.zoom_ratio, state?.ratio ?: desiredRatio)
    updateEnabled()
    // CameraX can synchronously report a new state while applying this request.
    restoredRatio?.let(requestZoom)
  }

  private fun updateEnabled() {
    val current = zoom
    binding.zoomSlider.isEnabled = active && current != null && current.maxRatio - current.minRatio > ZOOM_EPSILON
    binding.zoomValue.alpha = if (binding.zoomSlider.isEnabled) 1f else 0.5f
  }

  private fun ratioAt(progress: Int, state: ScanZoomState): Float =
    (state.minRatio * exp(ln((state.maxRatio / state.minRatio).toDouble()) * progress / ZOOM_STEPS)).toFloat()
      .coerceIn(state.minRatio, state.maxRatio)

  private fun progressAt(ratio: Float, state: ScanZoomState): Int {
    if (state.maxRatio - state.minRatio <= ZOOM_EPSILON) return 0
    return (ln((ratio / state.minRatio).toDouble()) / ln((state.maxRatio / state.minRatio).toDouble()) * ZOOM_STEPS)
      .roundToInt().coerceIn(0, ZOOM_STEPS)
  }

  private companion object {
    const val ZOOM_STATE_KEY = "camera_zoom_ratio"
    const val ZOOM_STEPS = 1000
    const val ZOOM_EPSILON = 0.001f
  }
}