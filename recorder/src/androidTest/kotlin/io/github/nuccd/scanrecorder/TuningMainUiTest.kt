package io.github.nuccd.scanrecorder

import android.content.ClipboardManager
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.Spinner
import androidx.test.platform.app.InstrumentationRegistry
import io.github.g00fy2.quickie.ScanDecoderMode
import io.github.g00fy2.quickie.ScanFrameDiagnostics
import io.github.g00fy2.quickie.ScanFrameStatus
import io.github.g00fy2.quickie.ScanResolution
import io.github.g00fy2.quickie.ScanTuningOptions
import io.github.nuccd.scanrecorder.databinding.ActivityMainBinding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Verifies the launcher entry, returned settings, metadata report and explicit statistics reset. */
internal class TuningMainUiTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private lateinit var activity: MainActivity
  private lateinit var binding: ActivityMainBinding
  private lateinit var original: ScanTuningOptions
  private lateinit var store: TuningSettingsStore

  @Before
  fun launch() {
    assumeTrue(BuildConfig.TUNING_ENABLED)
    store = TuningSettingsStore(instrumentation.targetContext)
    original = store.load()
    store.save(ScanTuningOptions())
    val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    activity = instrumentation.startActivitySync(intent) as MainActivity
    instrumentation.runOnMainSync { binding = ActivityMainBinding.bind(activity.findViewById(R.id.root)) }
    waitFor { binding.tuningButton.isEnabled }
  }

  @After
  fun finish() {
    if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
    if (::original.isInitialized) store.save(original)
  }

  @Test
  fun mainEntryOpensSettingsAndReloadsAppliedOptionsIntoTheReport() {
    applySettingsAndCheckReport(waitUntilVisible = true)
  }

  @Test
  fun applyingImmediatelyDuringTheSettingsTransitionAlsoReloadsOptions() {
    applySettingsAndCheckReport(waitUntilVisible = false)
  }

  private fun applySettingsAndCheckReport(waitUntilVisible: Boolean) {
    val monitor = instrumentation.addMonitor(TuningActivity::class.java.name, null, false)
    instrumentation.runOnMainSync {
      assertEquals(View.VISIBLE, binding.tuningButton.visibility)
      binding.tuningButton.performClick()
    }
    val settings = requireNotNull(monitor.waitForActivityWithTimeout(5000)) as TuningActivity
    instrumentation.removeMonitor(monitor)
    try {
      if (waitUntilVisible) waitFor { settings.hasWindowFocus() }
      instrumentation.runOnMainSync {
        settings.findViewById<Spinner>(R.id.tuning_decoder).setSelection(ScanDecoderMode.ML_KIT.ordinal)
        settings.findViewById<Spinner>(R.id.tuning_resolution).setSelection(ScanResolution.FULL_HD.ordinal)
        settings.findViewById<View>(R.id.tuning_apply).performClick()
      }
      waitFor { activity.hasWindowFocus() && settings.isDestroyed }
      instrumentation.runOnMainSync {
        binding.tuningCopyButton.performClick()
        val report = clipboard()
        assertTrue(report.contains("decoderMode=ML_KIT"))
        assertTrue(report.contains("resolution=FULL_HD"))
      }
    } finally {
      instrumentation.runOnMainSync { settings.finish() }
    }
  }

  @Test
  fun diagnosticsReportContainsCountersAndClearResetsTheVisibleSnapshot() {
    instrumentation.runOnMainSync {
      var resets = 0
      val ui = TuningDiagnosticsUi(activity, binding)
      ui.configure(true, {}) { resets++ }
      ui.onFrame(
        ScanFrameDiagnostics(
          width = 1280,
          height = 960,
          rotationDegrees = 90,
          decodeMillis = 40,
          decodedCount = 2,
          inWindowCount = 2,
          distinctInWindowCount = 1,
          status = ScanFrameStatus.WAITING_FOR_STABILITY,
          totalFrames = 10,
          statusCounts = ScanFrameStatus.entries.associateWith { 0L },
          mlKitMillis = 30,
          nativeMillis = 20,
          mlKitCount = 1,
          nativeCount = 1,
          averageDecodeMillis = 35.0,
          maxDecodeMillis = 70,
          sessionMillis = 1000
        )
      )
      assertTrue(binding.tuningDiagnostics.text.contains("1280×960"))
      binding.tuningCopyButton.performClick()
      val report = clipboard()
      assertTrue(report.contains("frameDecodeMs=40"))
      assertTrue(report.contains("minimumFrames=3"))
      assertTrue(report.contains("MULTIPLE_VALUES=0"))
      ui.setOptions(ScanTuningOptions(decoderMode = ScanDecoderMode.ML_KIT))
      binding.tuningCopyButton.performClick()
      assertTrue(clipboard().contains("decoderMode=ML_KIT"))
      assertTrue(!clipboard().contains("frameDecodeMs=40"))
      binding.tuningResetButton.performClick()
      assertEquals(1, resets)
      assertEquals(activity.getString(R.string.tuning_diagnostics_waiting), binding.tuningDiagnostics.text.toString())
    }
  }

  private fun clipboard(): String =
    activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()

  private fun waitFor(condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 5000
    var ready = false
    while (!ready && SystemClock.elapsedRealtime() < deadline) {
      instrumentation.runOnMainSync { ready = condition() }
      if (!ready) SystemClock.sleep(25)
    }
    assertTrue("Tuning UI did not become ready", ready)
    instrumentation.waitForIdleSync()
  }
}