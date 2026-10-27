package io.github.nuccd.scanrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.g00fy2.quickie.ContinuousScannerView
import io.github.g00fy2.quickie.ScanFocusState
import io.github.g00fy2.quickie.ScanFocusStatus
import io.github.g00fy2.quickie.ScanFrameDiagnostics
import io.github.g00fy2.quickie.ScanZoomState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/** Exercises real CameraX state on the selected test device without creating scan records. */
internal class CameraControlsIntegrationTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private lateinit var activity: MainActivity
  private lateinit var scanner: ContinuousScannerView
  private var permissionGranted = false

  @Volatile private var zoom: ScanZoomState? = null

  @Volatile private var focus: ScanFocusState? = null

  @Volatile private var frame: ScanFrameDiagnostics? = null

  @Volatile private var failure: String? = null

  @Before
  fun launchCamera() {
    val context = instrumentation.targetContext
    permissionGranted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    if (!permissionGranted) {
      shell("pm grant ${context.packageName} android.permission.CAMERA")
    }
    waitFor("Camera permission was not granted") {
      context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val intent = Intent(context, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    activity = instrumentation.startActivitySync(intent) as MainActivity
    instrumentation.runOnMainSync {
      activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      scanner = ContinuousScannerView(activity)
      activity.setContentView(scanner, ViewGroup.LayoutParams(-1, -1))
      scanner.setZoomStateListener { zoom = it }
      scanner.setFocusStateListener { focus = it }
      scanner.setDiagnosticsListener { frame = it }
      start()
    }
    waitFor("Camera did not publish zoom state") { zoom != null }
    waitFor("Camera did not analyze frames") { frame != null }
  }

  @After
  fun finish() {
    instrumentation.runOnMainSync {
      if (::scanner.isInitialized) scanner.release()
      if (::activity.isInitialized) activity.finish()
    }
    if (!permissionGranted) {
      val command = "pm revoke ${instrumentation.targetContext.packageName} android.permission.CAMERA"
      shell(command)
    }
  }

  @Test
  fun actualZoomAndAnalysisMetadataRemainAvailableAfterPauseAndResume() {
    val initial = requireNotNull(zoom)
    assumeTrue("Device has no adjustable zoom", initial.maxRatio > initial.minRatio)
    val target = (initial.minRatio + initial.maxRatio) / 2f
    instrumentation.runOnMainSync { scanner.setZoomRatio(target) }
    waitFor("Requested zoom did not reach actual CameraX state") { abs((zoom?.ratio ?: 0f) - target) < 0.01f }
    val actualFrame = requireNotNull(frame)
    assertTrue(actualFrame.width > 0 && actualFrame.height > 0)
    assertTrue(actualFrame.decodeMillis >= 0)
    assertEquals(actualFrame.totalFrames, actualFrame.statusCounts.values.sum())

    instrumentation.runOnMainSync { scanner.stop() }
    assertEquals(null, zoom)
    assertEquals(ScanFocusStatus.HIDDEN, focus?.status)
    frame = null
    instrumentation.runOnMainSync { start() }
    waitFor("Resumed camera lost requested zoom") { abs((zoom?.ratio ?: 0f) - target) < 0.01f }
    waitFor("Resumed camera stopped analyzing frames") { frame != null }
    assertNotNull(frame)
  }

  @Test
  fun previewTapsGiveFocusFeedbackAndCannotInterruptResultReview() {
    instrumentation.runOnMainSync { scanner.setResultDeliveryEnabled(false) }
    tapPreview()
    instrumentation.waitForIdleSync()
    assertEquals(ScanFocusStatus.HIDDEN, focus?.status)

    instrumentation.runOnMainSync { scanner.setResultDeliveryEnabled(true) }
    tapPreview()
    waitFor("Preview tap did not produce focus feedback") { focus?.let { it.status != ScanFocusStatus.HIDDEN } == true }
    val feedback = requireNotNull(focus)
    assertTrue(feedback.x > 0 && feedback.y > 0)
    assertTrue(
      feedback.status in setOf(
        ScanFocusStatus.FOCUSING,
        ScanFocusStatus.FOCUSED,
        ScanFocusStatus.FAILED,
        ScanFocusStatus.UNSUPPORTED
      )
    )
    instrumentation.runOnMainSync { scanner.setResultDeliveryEnabled(false) }
    assertEquals(ScanFocusStatus.HIDDEN, focus?.status)
    tapPreview()
    instrumentation.waitForIdleSync()
    assertEquals(ScanFocusStatus.HIDDEN, focus?.status)
  }

  private fun start() {
    scanner.start(activity, onScan = {}, onError = { failure = it.toString() })
  }

  private fun shell(command: String) {
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
      .use { it.readBytes() }
  }

  private fun tapPreview() {
    instrumentation.runOnMainSync {
      val down = SystemClock.uptimeMillis()
      for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
        val event = MotionEvent.obtain(
          down,
          SystemClock.uptimeMillis(),
          action,
          scanner.width / 2f,
          scanner.height / 2f,
          0
        )
        scanner.dispatchTouchEvent(event)
        event.recycle()
      }
    }
  }

  private fun waitFor(description: String, predicate: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 15000L
    while (SystemClock.elapsedRealtime() < deadline && failure == null) {
      if (predicate()) return
      SystemClock.sleep(30)
    }
    assertTrue("$description; failure=$failure; zoom=$zoom; focus=$focus", failure == null && predicate())
  }
}