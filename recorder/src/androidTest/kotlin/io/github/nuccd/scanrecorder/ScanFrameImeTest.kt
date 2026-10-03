package io.github.nuccd.scanrecorder

import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.RadioGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.g00fy2.quickie.ContinuousScannerView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.UUID

/** Exercises a real input dialog and IME, with isolated records inside a temporary batch. */
@RunWith(Parameterized::class)
internal class ScanFrameImeTest(private val square: Boolean, private val save: Boolean) {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private lateinit var activity: MainActivity
  private lateinit var model: RecorderViewModel
  private var originalBatch: String? = null
  private var testBatch: String? = null
  private val windowField = ContinuousScannerView::class.java.getDeclaredField("scanWindow").apply {
    isAccessible = true
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "square={0},save={1}")
    fun cases(): List<Array<Any>> = listOf(
      arrayOf(false, false),
      arrayOf(false, true),
      arrayOf(true, false),
      arrayOf(true, true)
    )
  }

  @Before
  fun launch() {
    val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    activity = instrumentation.startActivitySync(intent) as MainActivity
    instrumentation.runOnMainSync { model = ViewModelProvider(activity)[RecorderViewModel::class.java] }
    awaitReady()
    originalBatch = requireNotNull(model.state.value?.snapshot).batch.id
    instrumentation.runOnMainSync { model.newBatch("ime-test-${UUID.randomUUID()}") }
    awaitReady()
    testBatch = requireNotNull(model.state.value?.snapshot).batch.id
    selectFrame(!square)
    selectFrame(square)
  }

  @After
  fun finish() {
    if (!::activity.isInitialized) return
    instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button2")
      ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    testBatch?.let { batch ->
      instrumentation.runOnMainSync { model.deleteBatch(batch) }
      awaitReady()
      originalBatch?.let { original ->
        instrumentation.runOnMainSync { model.selectBatch(original) }
        awaitReady()
      }
    }
    instrumentation.runOnMainSync { activity.finish() }
  }

  @Test
  fun frameAndRecognitionWindowReturnToNormalAfterTheKeyboardCloses() {
    val before = frameState()
    assertTrue(before.size.width > 0 && before.size.height > 0)
    instrumentation.runOnMainSync { activity.findViewById<View>(R.id.add_button).performClick() }
    val input = focusInput()
    waitFor("Keyboard did not resize the preview or enter fullscreen mode") {
      val current = frameState()
      current.keyboard && (current.previewHeight < before.previewHeight || fullscreenKeyboard())
    }
    val value = "001234567890123"
    val text = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
    }
    assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text))
    if (fullscreenKeyboard()) {
      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
      waitFor("Fullscreen keyboard did not close") { !frameState().keyboard }
    }
    clickButton(if (save) "android:id/button1" else "android:id/button2")
    awaitReady()
    waitFor("Frame should return to $before") { frameState() == before }
    val records = requireNotNull(model.state.value?.snapshot).records
    assertEquals(if (save) listOf(value) else emptyList<String>(), records.map { it.value })
  }

  private fun selectFrame(useSquare: Boolean) {
    instrumentation.runOnMainSync {
      activity.findViewById<RadioGroup>(R.id.frame_selector)
        .check(if (useSquare) R.id.qr_frame else R.id.barcode_frame)
    }
    instrumentation.waitForIdleSync()
    waitFor("Initial frame did not finish measuring") {
      val current = frameState()
      current.measuredCorrectly && current.size.width > 0 && current.size.height > 0
    }
  }

  private fun frameState(): FrameState {
    lateinit var result: FrameState
    instrumentation.runOnMainSync {
      val scanner = activity.findViewById<ContinuousScannerView>(R.id.scanner)
      val frame = activity.findViewById<View>(R.id.scan_frame)
      val keyboard = ViewCompat.getRootWindowInsets(activity.findViewById(R.id.root))
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
      val measuredCorrectly = frame.width == frame.layoutParams.width && frame.height == frame.layoutParams.height &&
        frame.width == frame.measuredWidth && frame.height == frame.measuredHeight
      val window = (windowField.get(scanner) as RectF?)?.let(::RectF)
      result = FrameState(Size(frame.width, frame.height), scanner.height, keyboard, measuredCorrectly, window)
    }
    return result
  }

  private fun fullscreenKeyboard(): Boolean {
    var fullscreen = false
    instrumentation.runOnMainSync {
      fullscreen = requireNotNull(activity.getSystemService(InputMethodManager::class.java)).isFullscreenMode
    }
    return fullscreen
  }

  private fun focusInput(): AccessibilityNodeInfo {
    var input: AccessibilityNodeInfo? = null
    waitFor("Input field not found") {
      val nodes = ArrayDeque<AccessibilityNodeInfo>()
      instrumentation.uiAutomation.rootInActiveWindow?.let(nodes::add)
      while (nodes.isNotEmpty() && input == null) {
        val node = nodes.removeFirst()
        if (node.className == "android.widget.EditText") input = node
        repeat(node.childCount) { index -> node.getChild(index)?.let(nodes::add) }
      }
      input != null
    }
    val bounds = RectF()
    val screenBounds = android.graphics.Rect()
    requireNotNull(input).getBoundsInScreen(screenBounds)
    bounds.set(screenBounds)
    val now = SystemClock.uptimeMillis()
    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
      val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.centerX(), bounds.centerY(), 0)
      instrumentation.sendPointerSync(event)
      event.recycle()
    }
    return requireNotNull(input)
  }

  private fun clickButton(id: String) {
    var button: AccessibilityNodeInfo? = null
    waitFor("Dialog button not found: $id") {
      button = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)
        ?.firstOrNull { it.isVisibleToUser }
      button != null
    }
    assertTrue(requireNotNull(button).performAction(AccessibilityNodeInfo.ACTION_CLICK))
  }

  private fun awaitReady() = waitFor("Storage operation timed out") {
    model.state.value?.let { !it.working && it.snapshot != null } == true
  }

  private fun waitFor(description: String, predicate: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 5000
    while (SystemClock.elapsedRealtime() < deadline) {
      if (predicate()) return
      SystemClock.sleep(20)
    }
    assertTrue("$description; actual=${frameState()}", predicate())
  }
}

internal data class FrameState(
  val size: Size,
  val previewHeight: Int,
  val keyboard: Boolean,
  val measuredCorrectly: Boolean,
  val window: RectF?
)