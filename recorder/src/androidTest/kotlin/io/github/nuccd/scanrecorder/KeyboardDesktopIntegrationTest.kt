package io.github.nuccd.scanrecorder

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Run explicitly with an owned Windows test target; never part of unattended default input injection. */
internal class KeyboardDesktopIntegrationTest {
  @Test fun realDesktopTransportConnectsWhileInputRemainsPaused() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("desktop_input") == "transport-only")
    val bridge = KeyboardBridge { }
    try {
      bridge.setActive(true)
      await(15000) { bridge.state == KeyboardConnection.PAUSED }
      assertEquals(KeyboardDelivery.PENDING, bridge.send("must not type"))
    } finally {
      bridge.setActive(false)
    }
  }

  @Test fun confirmedRecordTravelsOverUsbToTheOwnedDesktopTarget() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("desktop_input") == "owned-target")
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val application = instrumentation.targetContext.applicationContext as Application
    val settings = KeyboardSettingsStore(application)
    val original = settings.enabled
    val models = ViewModelStore()
    val name = "desktop-test-${UUID.randomUUID()}.db"
    lateinit var model: RecorderViewModel
    try {
      settings.enabled = true
      instrumentation.runOnMainSync {
        model = RecorderViewModel(application, SavedStateHandle(), name)
        models.put("test", model)
        model.refreshKeyboard(true)
      }
      await(25000) { model.keyboardConnection.value == KeyboardConnection.CONNECTED }
      await(5000) { model.state.value?.working == false }
      instrumentation.runOnMainSync {
        model.offerScan("001234567890123456中文😀")
        assertEquals(0, model.state.value?.snapshot?.records?.size)
        model.confirmScan()
      }
      await(5000) { model.state.value?.working == false && model.state.value?.snapshot?.records?.size == 1 }
      instrumentation.runOnMainSync {
        val record = requireNotNull(model.state.value?.snapshot).records.single()
        assertEquals("001234567890123456中文😀", record.value)
        assertEquals(KeyboardDelivery.SUBMITTED, record.delivery)
      }
    } finally {
      instrumentation.runOnMainSync { models.clear() }
      settings.enabled = original
      application.deleteDatabase(name)
    }
  }

  private fun await(timeout: Long, condition: () -> Boolean) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.elapsedRealtime() + timeout
    var ready = false
    while (!ready && SystemClock.elapsedRealtime() < deadline) {
      instrumentation.runOnMainSync { ready = condition() }
      if (!ready) SystemClock.sleep(20)
    }
    assertTrue("Desktop integration did not become ready", ready)
  }
}