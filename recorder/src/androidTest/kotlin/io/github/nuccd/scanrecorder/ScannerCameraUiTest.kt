package io.github.nuccd.scanrecorder

import android.os.Bundle
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.g00fy2.quickie.ScanFocusState
import io.github.g00fy2.quickie.ScanFocusStatus
import io.github.g00fy2.quickie.ScanZoomState
import io.github.nuccd.scanrecorder.databinding.ActivityMainBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Camera states are injected into a detached UI; no device camera or stored records are changed. */
internal class ScannerCameraUiTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @Test
  fun zoomNeedsAnActiveCameraWithARealAdjustableRangeAndDisplaysReportedRatio() = onMain {
    val binding = binding()
    val controls = ScannerCameraUi(binding, null)
    controls.setActive(true)
    assertFalse(binding.zoomSlider.isEnabled)
    controls.updateZoomState(ScanZoomState(1f, 1f, 8f))
    assertTrue(binding.zoomSlider.isEnabled)
    assertEquals(0, binding.zoomSlider.progress)
    controls.updateZoomState(ScanZoomState(8f, 1f, 8f))
    assertEquals(1000, binding.zoomSlider.progress)
    assertEquals(binding.root.context.getString(R.string.zoom_ratio, 8f), binding.zoomValue.text.toString())
    controls.setActive(false)
    assertFalse(binding.zoomSlider.isEnabled)
    controls.setActive(true)
    controls.updateZoomState(ScanZoomState(1f, 1f, 1f))
    assertFalse(binding.zoomSlider.isEnabled)
    assertEquals(0, binding.zoomSlider.progress)
    controls.updateZoomState(null)
    assertFalse(binding.zoomSlider.isEnabled)
    binding.scanner.release()
  }

  @Test
  fun sliderRequestsContinuousCameraZoomAndReportedUpdatesDoNotIssueNewRequests() = onMain {
    val binding = binding()
    val requested = mutableListOf<Float>()
    val controls = ScannerCameraUi(binding, null) { requested.add(it) }
    controls.setActive(true)
    controls.updateZoomState(ScanZoomState(1f, 1f, 9f))
    val arguments = Bundle().apply {
      putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 500f)
    }
    assertTrue(
      binding.zoomSlider.performAccessibilityAction(
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
        arguments
      )
    )
    assertEquals(3f, requested.single(), 0.001f)
    assertEquals(binding.root.context.getString(R.string.zoom_ratio, 1f), binding.zoomValue.text.toString())
    controls.updateZoomState(ScanZoomState(3f, 1f, 9f))
    assertEquals(1, requested.size)
    assertEquals(500, binding.zoomSlider.progress)
    controls.setActive(false)
    val unchanged = Bundle().apply {
      putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1000f)
    }
    binding.zoomSlider.performAccessibilityAction(
      AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
      unchanged
    )
    assertEquals(1, requested.size)
    binding.scanner.release()
  }

  @Test
  fun cameraFeedbackDuringADragDoesNotMoveTheThumbOrLoseTheRequestedRatio() = onMain {
    val binding = binding()
    val requested = mutableListOf<Float>()
    val controls = ScannerCameraUi(binding, null) { requested.add(it) }
    controls.setActive(true)
    controls.updateZoomState(ScanZoomState(1f, 1f, 9f))
    binding.zoomSlider.layout(0, 0, 1000, 100)
    val time = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 750f, 50f, 0)
    assertTrue(binding.zoomSlider.dispatchTouchEvent(down))
    down.recycle()
    assertTrue(requested.isNotEmpty())
    val thumbPosition = binding.zoomSlider.progress
    controls.updateZoomState(ScanZoomState(2f, 1f, 9f))
    assertEquals(thumbPosition, binding.zoomSlider.progress)
    val saved = Bundle().also(controls::saveState)
    assertEquals(requested.last(), saved.getFloat("camera_zoom_ratio"), 0f)
    assertEquals(binding.root.context.getString(R.string.zoom_ratio, 2f), binding.zoomValue.text.toString())
    val up = MotionEvent.obtain(time, time + 10, MotionEvent.ACTION_UP, 750f, 50f, 0)
    binding.zoomSlider.dispatchTouchEvent(up)
    up.recycle()
    assertTrue(binding.zoomSlider.progress < thumbPosition)
    binding.scanner.release()
  }

  @Test
  fun savedMagnificationSurvivesRecreationAndIsClampedToTheNextCameraRange() = onMain {
    val first = binding()
    val controls = ScannerCameraUi(first, null)
    controls.updateZoomState(ScanZoomState(1f, 1f, 8f))
    controls.updateZoomState(ScanZoomState(6f, 1f, 8f))
    controls.setActive(false)
    controls.updateZoomState(null)
    val saved = Bundle().also(controls::saveState)
    val next = binding()
    val restored = ScannerCameraUi(next, saved)
    restored.updateZoomState(ScanZoomState(1f, 1f, 4f))
    val clamped = Bundle().also(restored::saveState)
    assertEquals(4f, clamped.getFloat("camera_zoom_ratio"), 0f)
    // The UI must keep showing the observed value until the camera actually applies the restored request.
    assertEquals(next.root.context.getString(R.string.zoom_ratio, 1f), next.zoomValue.text.toString())
    restored.updateZoomState(ScanZoomState(4f, 1f, 4f))
    assertEquals(next.root.context.getString(R.string.zoom_ratio, 4f), next.zoomValue.text.toString())
    val fresh = binding()
    val default = Bundle().also(ScannerCameraUi(fresh, null)::saveState)
    assertEquals(1f, default.getFloat("camera_zoom_ratio"), 0f)
    first.scanner.release()
    next.scanner.release()
    fresh.scanner.release()
  }

  @Test
  fun synchronousRestoreFeedbackCannotBeOverwrittenByTheInitialCameraState() = onMain {
    val binding = binding()
    val saved = Bundle().apply { putFloat("camera_zoom_ratio", 3f) }
    val requests = mutableListOf<Float>()
    val target = AtomicReference<ScannerCameraUi>()
    val controls = ScannerCameraUi(binding, saved) { requested ->
      requests.add(requested)
      target.get().updateZoomState(ScanZoomState(requested, 1f, 9f))
    }
    target.set(controls)
    controls.setActive(true)
    controls.updateZoomState(ScanZoomState(1f, 1f, 9f))
    assertEquals(listOf(3f), requests)
    assertEquals(500, binding.zoomSlider.progress)
    assertEquals(binding.root.context.getString(R.string.zoom_ratio, 3f), binding.zoomValue.text.toString())
    assertTrue(binding.zoomSlider.isEnabled)
    val restored = Bundle().also(controls::saveState)
    assertEquals(3f, restored.getFloat("camera_zoom_ratio"), 0f)
    binding.scanner.release()
  }

  @Test
  fun focusIndicatorNeverConsumesPreviewTouchesInAnyFocusState() = onMain {
    val binding = binding()
    val indicator = binding.focusIndicator
    indicator.layout(0, 0, 320, 240)
    for (status in ScanFocusStatus.entries) {
      indicator.show(ScanFocusState(160f, 120f, status))
      val time = SystemClock.uptimeMillis()
      for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
        val event = MotionEvent.obtain(time, time, action, 160f, 120f, 0)
        assertFalse(indicator.dispatchTouchEvent(event))
        event.recycle()
      }
    }
    indicator.hide()
    binding.scanner.release()
  }

  private fun binding(): ActivityMainBinding {
    val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_ScanRecorder)
    return ActivityMainBinding.inflate(LayoutInflater.from(context))
  }

  private fun onMain(action: () -> Unit) {
    instrumentation.runOnMainSync(action)
  }
}