package io.github.nuccd.scanrecorder

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

internal class RecorderReviewFlowTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val application = instrumentation.targetContext.applicationContext as Application
  private val databaseName = "review-test-${UUID.randomUUID()}.db"
  private val savedState = SavedStateHandle()
  private val viewModels = ViewModelStore()
  private lateinit var model: RecorderViewModel

  @Before
  fun open() {
    onMain {
      model = RecorderViewModel(application, savedState, databaseName)
      viewModels.put("test", model)
    }
    awaitReady()
  }

  @After
  fun close() {
    onMain { viewModels.clear() }
    application.deleteDatabase(databaseName)
  }

  @Test
  fun recognitionAndCancellationNeverWriteARecord() {
    onMain { model.offerScan("001234567890123") }
    assertEquals("001234567890123", state().pendingScan?.value)
    assertEquals(0, state().snapshot?.records?.size)
    assertEquals(0, persistentRecords().size)
    assertEquals("001234567890123", savedState.get<String>("pending_scan_value"))
    onMain { model.cancelScan() }
    assertNull(state().pendingScan)
    assertFalse(savedState.contains("pending_scan_value"))
    assertEquals(0, persistentRecords().size)
  }

  @Test
  fun confirmationWritesExactlyOnceAndSuppressesOtherCandidatesDuringReview() {
    onMain {
      model.offerScan("A")
      model.offerScan("B")
      assertEquals("A", model.state.value?.pendingScan?.value)
      model.confirmScan()
      model.confirmScan()
    }
    awaitReady()
    assertNull(state().pendingScan)
    assertEquals(listOf("A"), persistentRecords().map { it.value })
  }

  @Test
  fun duplicateIsHeldForAcknowledgementAndReportsEveryExistingSequence() {
    onMain {
      model.offerScan("A")
      model.confirmScan()
    }
    awaitReady()
    val batch = requireNotNull(state().snapshot).batch.id
    onMain { model.setDuplicates(batch, true) }
    awaitReady()
    onMain {
      model.offerScan("A")
      model.confirmScan()
    }
    awaitReady()
    onMain { model.setDuplicates(batch, false) }
    awaitReady()
    onMain { model.offerScan("A") }
    assertNotNull(state().pendingScan)
    assertEquals(listOf(1, 2), requireNotNull(state().snapshot).duplicateRows("A"))
    onMain { model.confirmScan() }
    assertNull(state().pendingScan)
    assertEquals(2, persistentRecords().size)
  }

  @Test
  fun pendingCandidateCanBeRestoredWithoutBeingAutomaticallySaved() {
    val batchId = requireNotNull(state().snapshot).batch.id
    val restoredState = SavedStateHandle(mapOf("pending_scan_batch" to batchId, "pending_scan_value" to "QR\n中文"))
    lateinit var restored: RecorderViewModel
    onMain {
      restored = RecorderViewModel(application, restoredState, databaseName)
      viewModels.put("restored", restored)
    }
    assertEquals("QR\n中文", restored.state.value?.pendingScan?.value)
    assertEquals(0, persistentRecords().size)
  }

  private fun persistentRecords(): List<ScanRecord> = RecorderStore(application, databaseName).use {
    it.load("unused").records
  }

  @Test
  fun deletingAllBatchesAlsoClearsAnyPendingCandidateAndItsSavedState() {
    val batch = requireNotNull(state().snapshot).batch.id
    onMain { model.save(batch, "keep until deletion") }
    awaitReady()
    onMain {
      model.offerScan("pending")
      model.deleteAllBatches()
    }
    awaitReady()
    val after = requireNotNull(state().snapshot)
    assertEquals(1, after.batches.size)
    assertTrue(after.records.isEmpty())
    assertEquals(0L, after.totalRecordCount)
    assertNull(state().pendingScan)
    assertFalse(savedState.contains("pending_scan_value"))
    assertFalse(savedState.contains("pending_scan_batch"))
    assertTrue(persistentRecords().isEmpty())
  }

  private fun state(): RecorderState {
    lateinit var current: RecorderState
    onMain { current = requireNotNull(model.state.value) }
    return current
  }

  private fun awaitReady() {
    val deadline = SystemClock.elapsedRealtime() + 5000
    while (SystemClock.elapsedRealtime() < deadline) {
      val current = state()
      if (!current.working && current.snapshot != null) return
      SystemClock.sleep(10)
    }
    assertTrue("ViewModel operation timed out", !state().working && state().snapshot != null)
  }

  private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
}