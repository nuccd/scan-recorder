package io.github.nuccd.scanrecorder

import android.content.Intent
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import io.github.g00fy2.quickie.ScanDecoderMode
import io.github.g00fy2.quickie.ScanFormatScope
import io.github.g00fy2.quickie.ScanResolution
import io.github.g00fy2.quickie.ScanTuningOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Changes tuning preferences only; saves and restores the previous settings after each test. */
@Suppress("TooManyFunctions") // Keep form-editing helpers local to the Activity test fixture.
internal class TuningSettingsUiTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private lateinit var store: TuningSettingsStore
  private lateinit var original: ScanTuningOptions
  private lateinit var activity: TuningActivity
  private var originalKeyboard = false

  @Before
  fun prepare() {
    store = TuningSettingsStore(instrumentation.targetContext)
    original = store.load()
    originalKeyboard = KeyboardSettingsStore(instrumentation.targetContext).enabled
    KeyboardSettingsStore(instrumentation.targetContext).enabled = false
    store.save(ScanTuningOptions())
  }

  @After
  fun finish() {
    if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
    if (::original.isInitialized) store.save(original)
    KeyboardSettingsStore(instrumentation.targetContext).enabled = originalKeyboard
  }

  @Test
  fun applyPersistsEveryEditableOptionAndReturns() {
    launch()
    onMain {
      select(R.id.tuning_decoder, ScanDecoderMode.NATIVE.ordinal)
      checked(R.id.keyboard_mode, true)
      select(R.id.tuning_formats, ScanFormatScope.LINEAR.ordinal)
      select(R.id.tuning_resolution, ScanResolution.FULL_HD.ordinal)
      input(R.id.tuning_frames_input, "4")
      input(R.id.tuning_minimum_input, "250")
      input(R.id.tuning_gap_input, "300")
      input(R.id.tuning_observation_input, "900")
      input(R.id.tuning_focus_input, "400")
      input(R.id.tuning_lines_input, "3")
      input(R.id.tuning_default_zoom_input, "2.75")
      checked(R.id.tuning_harder, false)
      checked(R.id.tuning_rotate, false)
      checked(R.id.tuning_invert, false)
      checked(R.id.tuning_downscale, true)
      checked(R.id.tuning_explicit_invert, false)
      click(R.id.tuning_apply)
    }
    assertEquals(
      ScanTuningOptions(
        decoderMode = ScanDecoderMode.NATIVE,
        formatScope = ScanFormatScope.LINEAR,
        resolution = ScanResolution.FULL_HD,
        minimumFrames = 4,
        minimumMillis = 250,
        maximumGapMillis = 300,
        maximumObservationMillis = 900,
        focusWaitMillis = 400,
        nativeTryHarder = false,
        nativeTryRotate = false,
        nativeTryInvert = false,
        nativeTryDownscale = true,
        nativeInvertFallback = false,
        nativeMinLineCount = 3,
        defaultZoomRatio = 2.75f
      ),
      store.load()
    )
    assertTrue(activity.isFinishing)
    assertTrue(KeyboardSettingsStore(instrumentation.targetContext).enabled)
  }

  @Test
  fun restoringDefaultsOnlyChangesTheDraftAndCancellationPreservesTheSavedOptions() {
    val before = ScanTuningOptions(minimumFrames = 5, nativeTryDownscale = true, defaultZoomRatio = 4.5f)
    store.save(before)
    KeyboardSettingsStore(instrumentation.targetContext).enabled = true
    launch()
    onMain {
      click(R.id.tuning_reset)
      assertFalse(activity.findViewById<MaterialSwitch>(R.id.keyboard_mode).isChecked)
      assertEquals("3", activity.findViewById<EditText>(R.id.tuning_frames_input).text.toString())
      assertEquals("2.3", activity.findViewById<EditText>(R.id.tuning_default_zoom_input).text.toString())
      assertTrue(activity.findViewById<TextView>(R.id.tuning_notice).text.toString().contains("尚未应用"))
      assertEquals(before, store.load())
      click(R.id.tuning_cancel)
    }
    assertEquals(before, store.load())
    assertTrue(KeyboardSettingsStore(instrumentation.targetContext).enabled)
  }

  @Test
  fun invalidNumbersInconsistentWindowsAndNativeQrCannotBeApplied() {
    launch()
    onMain {
      for (value in listOf("", "999999999999999999999999999999", "-1", "11")) {
        input(R.id.tuning_frames_input, value)
        click(R.id.tuning_apply)
        assertNotNull(activity.findViewById<TextInputLayout>(R.id.tuning_frames).error)
        assertFalse(activity.isFinishing)
      }
      input(R.id.tuning_frames_input, "3")
      input(R.id.tuning_minimum_input, "1000")
      input(R.id.tuning_observation_input, "600")
      click(R.id.tuning_apply)
      assertNotNull(activity.findViewById<TextInputLayout>(R.id.tuning_observation_ms).error)
      input(R.id.tuning_minimum_input, "200")
      select(R.id.tuning_decoder, ScanDecoderMode.NATIVE.ordinal)
      select(R.id.tuning_formats, ScanFormatScope.QR.ordinal)
      click(R.id.tuning_apply)
      assertTrue(activity.findViewById<TextView>(R.id.tuning_error).text.toString().contains("仅支持一维"))
      assertFalse(activity.isFinishing)
    }
    assertEquals(ScanTuningOptions(), store.load())
  }

  @Test
  fun nonFiniteOutOfRangeOrMissingMagnificationCannotBeApplied() {
    launch()
    onMain {
      // Supply raw pasted values so numeric keyboard filtering cannot disguise an invalid value.
      activity.findViewById<EditText>(R.id.tuning_default_zoom_input).keyListener = null
      for (value in listOf("", "NaN", "Infinity", "-Infinity", "1e100", "abc", "0.9", "10.1", "-1")) {
        input(R.id.tuning_default_zoom_input, value)
        click(R.id.tuning_apply)
        assertNotNull(activity.findViewById<TextInputLayout>(R.id.tuning_default_zoom).error)
        assertFalse(activity.isFinishing)
        assertEquals(ScanTuningOptions(), store.load())
      }
      input(R.id.tuning_default_zoom_input, "3.125")
      click(R.id.tuning_apply)
    }
    assertEquals(3.125f, store.load().defaultZoomRatio, 0f)
    assertTrue(activity.isFinishing)
  }

  @Test
  fun olderPreferencesWithoutMagnificationKeepAllExistingParameters() {
    val previous = ScanTuningOptions(
      decoderMode = ScanDecoderMode.ML_KIT,
      formatScope = ScanFormatScope.LINEAR,
      minimumFrames = 7,
      focusWaitMillis = 450,
      nativeTryInvert = false,
      defaultZoomRatio = 4.25f
    )
    store.save(previous)
    val preferences = instrumentation.targetContext.getSharedPreferences("scan_tuning", 0)
    preferences.edit().remove("default_zoom").apply()
    val expected = previous.copy(defaultZoomRatio = 2.3f)
    assertEquals(expected, store.load())
    launch()
    onMain {
      assertEquals("2.3", activity.findViewById<EditText>(R.id.tuning_default_zoom_input).text.toString())
      click(R.id.tuning_apply)
    }
    assertEquals(expected, store.load())
    assertEquals(2.3f, preferences.getFloat("default_zoom", 0f), 0f)
  }

  @Test
  fun recreationRetainsUnappliedAndInvalidDraftValues() {
    launch()
    onMain {
      input(R.id.tuning_frames_input, "")
      input(R.id.tuning_gap_input, "500")
      input(R.id.tuning_default_zoom_input, "3.25")
      select(R.id.tuning_decoder, ScanDecoderMode.ML_KIT.ordinal)
      checked(R.id.tuning_explicit_invert, false)
    }
    val monitor = instrumentation.addMonitor(TuningActivity::class.java.name, null, false)
    onMain { activity.recreate() }
    activity = requireNotNull(monitor.waitForActivityWithTimeout(5000)) as TuningActivity
    instrumentation.removeMonitor(monitor)
    instrumentation.waitForIdleSync()
    onMain {
      assertEquals("", activity.findViewById<EditText>(R.id.tuning_frames_input).text.toString())
      assertEquals("500", activity.findViewById<EditText>(R.id.tuning_gap_input).text.toString())
      assertEquals("3.25", activity.findViewById<EditText>(R.id.tuning_default_zoom_input).text.toString())
      assertEquals(
        ScanDecoderMode.ML_KIT.ordinal,
        activity.findViewById<Spinner>(R.id.tuning_decoder).selectedItemPosition
      )
      assertFalse(activity.findViewById<MaterialSwitch>(R.id.tuning_explicit_invert).isChecked)
      click(R.id.tuning_cancel)
    }
    assertEquals(ScanTuningOptions(), store.load())
  }

  @Test
  fun damagedPreferencesFallBackToTheWholeBaseline() {
    val preferences = instrumentation.targetContext.getSharedPreferences("scan_tuning", 0)
    store.save(ScanTuningOptions(minimumFrames = 7))
    preferences.edit().putString("decoder", "not_an_engine").apply()
    assertEquals(ScanTuningOptions(), store.load())
    store.save(ScanTuningOptions(minimumFrames = 7))
    preferences.edit().putString("frames", "bad_type").apply()
    assertEquals(ScanTuningOptions(), store.load())
    store.save(ScanTuningOptions(minimumFrames = 7))
    preferences.edit().putFloat("default_zoom", Float.NaN).apply()
    assertEquals(ScanTuningOptions(), store.load())
    store.save(ScanTuningOptions(minimumFrames = 7))
    preferences.edit().putFloat("default_zoom", Float.POSITIVE_INFINITY).apply()
    assertEquals(ScanTuningOptions(), store.load())
  }

  private fun launch() {
    val intent = Intent(instrumentation.targetContext, TuningActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    activity = instrumentation.startActivitySync(intent) as TuningActivity
    instrumentation.waitForIdleSync()
  }

  private fun onMain(action: () -> Unit) {
    instrumentation.runOnMainSync(action)
  }

  private fun select(id: Int, position: Int) {
    activity.findViewById<Spinner>(id).setSelection(position)
  }

  private fun input(id: Int, text: String) {
    activity.findViewById<EditText>(id).setText(text)
  }

  private fun checked(id: Int, value: Boolean) {
    activity.findViewById<MaterialSwitch>(id).isChecked = value
  }

  private fun click(id: Int) {
    activity.findViewById<android.view.View>(id).performClick()
  }
}