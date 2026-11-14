package io.github.nuccd.scanrecorder

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** An isolated local receiver; never injects keyboard events or touches stored records. */
internal class KeyboardBridgeTest {
  private val bridge = KeyboardBridge { }
  private val sockets = mutableListOf<Socket>()

  @After fun close() {
    bridge.setActive(false)
    sockets.forEach { it.close() }
  }

  @Test fun offlineAndBlockedContentAreNeverQueuedForReconnect() {
    assertEquals(KeyboardDelivery.PENDING, bridge.send("offline"))
    bridge.setActive(true)
    assertEquals(KeyboardDelivery.BLOCKED, bridge.send("a\tb"))
    val frames = client()
    await { bridge.state == KeyboardConnection.CONNECTED }
    SystemClock.sleep(600)
    assertTrue(frames.isEmpty())
    assertEquals(KeyboardDelivery.SUBMITTED, bridge.send("00123中文"))
    assertEquals(1, frames.size)
    assertTrue(frames.single().endsWith("3030313233e4b8ade69687"))
  }

  @Test fun lostAcknowledgementIsUncertainAndReconnectDoesNotReplay() {
    bridge.setActive(true)
    val first = client(dropResult = true)
    await { bridge.state == KeyboardConnection.CONNECTED }
    assertEquals(KeyboardDelivery.UNCERTAIN, bridge.send("once"))
    assertEquals(1, first.size)
    val next = client()
    await { bridge.state == KeyboardConnection.CONNECTED }
    SystemClock.sleep(600)
    assertTrue(next.isEmpty())
  }

  @Test fun pausedReceiverCannotAcceptNewText() {
    bridge.setActive(true)
    val frames = client(paused = true)
    await { bridge.state == KeyboardConnection.PAUSED }
    assertEquals(KeyboardDelivery.PENDING, bridge.send("not typed"))
    assertTrue(frames.isEmpty())
  }

  @Test
  fun quickScanSavesAndSubmitsOneFrameWithoutConfirmation() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app = instrumentation.targetContext.applicationContext as Application
    val tuning = TuningSettingsStore(app)
    val keyboard = KeyboardSettingsStore(app)
    val originalQuick = tuning.quickScanEnabled
    val originalKeyboard = keyboard.enabled
    val database = "quick-usb-${UUID.randomUUID()}.db"
    val models = ViewModelStore()
    lateinit var model: RecorderViewModel
    try {
      tuning.quickScanEnabled = true
      keyboard.enabled = true
      instrumentation.runOnMainSync {
        model = RecorderViewModel(app, SavedStateHandle(), database)
        models.put("quick", model)
        model.refreshKeyboard(true)
      }
      val frames = client()
      await { model.keyboardConnection.value == KeyboardConnection.CONNECTED && model.state.value?.working == false }
      instrumentation.runOnMainSync {
        model.offerScan("00123中文")
        assertNull(model.state.value?.pendingScan)
      }
      await { model.state.value?.working == false }
      val saved = requireNotNull(model.state.value?.snapshot).records.single()
      assertEquals("00123中文", saved.value)
      assertEquals(KeyboardDelivery.SUBMITTED, saved.delivery)
      assertEquals(1, frames.size)
      assertTrue(frames.single().endsWith("3030313233e4b8ade69687"))
    } finally {
      instrumentation.runOnMainSync { models.clear() }
      tuning.quickScanEnabled = originalQuick
      keyboard.enabled = originalKeyboard
      app.deleteDatabase(database)
    }
  }

  private fun client(dropResult: Boolean = false, paused: Boolean = false): List<String> {
    var socket: Socket? = null
    await {
      socket = runCatching { Socket("127.0.0.1", KeyboardProtocol.PORT) }.getOrNull()
      socket != null
    }
    val connected = requireNotNull(socket)
    sockets.add(connected)
    val frames = CopyOnWriteArrayList<String>()
    Thread {
      runCatching {
        val input = connected.getInputStream().bufferedReader()
        val output = connected.getOutputStream().bufferedWriter()
        assertEquals(KeyboardProtocol.HELLO, input.readLine())
        output.write("READY/1\n")
        output.flush()
        while (!connected.isClosed) {
          val line = input.readLine() ?: break
          val reply = if (line == "PING") {
            if (paused) "PONG PAUSED" else "PONG ACTIVE"
          } else {
            frames.add(line)
            if (dropResult) throw java.io.IOException("Simulated lost acknowledgement")
            "RESULT ${line.split(' ')[1]} OK"
          }
          output.write("$reply\n")
          output.flush()
        }
      }
      connected.close()
    }.apply { isDaemon = true }.start()
    return frames
  }

  private fun await(condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 5000
    while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
    assertTrue("USB channel did not become ready", condition())
  }
}