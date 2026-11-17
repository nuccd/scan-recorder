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
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

internal class RecorderReviewFlowTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val application = instrumentation.targetContext.applicationContext as Application
  private val databaseName = "review-test-${UUID.randomUUID()}.db"
  private var savedState = SavedStateHandle()
  private val viewModels = ViewModelStore()
  private val openedModels = mutableListOf<RecorderViewModel>()
  private val tuning = TuningSettingsStore(application)
  private val keyboard = KeyboardSettingsStore(application)
  private var originalQuickScan = false
  private var originalKeyboard = false
  private lateinit var model: RecorderViewModel

  @Before
  fun open() {
    originalQuickScan = tuning.quickScanEnabled
    originalKeyboard = keyboard.enabled
    tuning.quickScanEnabled = false
    keyboard.enabled = false
    onMain {
      model = createModel(savedState)
    }
    awaitReady()
  }

  @After
  fun close() {
    try {
      closeModels()
      application.deleteDatabase(databaseName)
    } finally {
      tuning.quickScanEnabled = originalQuickScan
      keyboard.enabled = originalKeyboard
    }
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
  fun quickScanSavesWithoutReviewAndReportsBothDuplicateOutcomes() {
    tuning.quickScanEnabled = true
    onMain { model.offerScan("00123\n中文") }
    awaitReady()
    assertNull(state().pendingScan)
    assertFalse(savedState.contains("pending_scan_value"))
    assertEquals(listOf("00123\n中文"), persistentRecords().map { it.value })

    onMain { model.offerScan("00123\n中文") }
    awaitReady()
    assertEquals(RecorderEvent.QuickScanDuplicate(listOf(1)), model.events.value?.take())
    assertNull(state().pendingScan)
    assertEquals(1, persistentRecords().size)

    val batch = requireNotNull(state().snapshot).batch.id
    onMain { model.setDuplicates(batch, true) }
    awaitReady()
    onMain { model.offerScan("00123\n中文") }
    awaitReady()
    assertEquals(RecorderEvent.Saved(2, true, listOf(1)), model.events.value?.take())
    assertEquals(2, persistentRecords().size)
    assertNull(state().pendingScan)
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
  fun failedConfirmationRetainsTheCandidateAndSavedStateUntilASuccessfulRetry() {
    val batch = requireNotNull(state().snapshot).batch.id
    onMain { model.save(batch, "already saved") }
    awaitReady()
    val value = " 00123\n中文\\n "
    onMain { model.offerScan(value) }
    val before = state()
    val persistedState = savedValues()
    executeSql(
      "CREATE TRIGGER reject_review_save BEFORE INSERT ON records " +
        "BEGIN SELECT RAISE(ABORT, 'review-save-failure'); END"
    )

    onMain { model.confirmScan() }
    awaitReady()

    assertEquals(before.snapshot, state().snapshot)
    assertEquals(PendingScan(batch, value), state().pendingScan)
    assertEquals(persistedState, savedValues())
    assertEquals(before.snapshot, persistentSnapshot())
    val failure = takeEvent()
    assertTrue("A failed insert must report a storage failure", failure is RecorderEvent.Failure)
    failure as RecorderEvent.Failure
    assertEquals(R.string.storage_failed, failure.resource)
    assertTrue(failure.detail.contains("review-save-failure"))

    executeSql("DROP TRIGGER reject_review_save")
    onMain { model.confirmScan() }
    awaitReady()
    onMain { model.confirmScan() }
    awaitReady()

    assertEquals(listOf("already saved", value), persistentRecords().map { it.value })
    assertNull(state().pendingScan)
    assertFalse(savedState.contains("pending_scan_batch"))
    assertFalse(savedState.contains("pending_scan_value"))
    assertEquals(RecorderEvent.Saved(2, true), takeEvent())
  }

  @Test
  fun recreatedCandidateIsConfirmedExactlyOnceInItsOriginalBatchWhenDuplicatesAreAllowed() {
    val firstBatch = requireNotNull(state().snapshot).batch.id
    onMain { model.save(firstBatch, "keep in first batch") }
    awaitReady()
    onMain { model.newBatch("restored review") }
    awaitReady()
    val batch = requireNotNull(state().snapshot).batch.id
    onMain {
      model.setDuplicates(batch, true)
      model.save(batch, "already saved")
    }
    awaitReady()
    val value = " 00123\n中文\\n "
    onMain { model.offerScan(value) }
    val before = state()
    val restoredValues = savedValues()

    closeModels()
    onMain {
      savedState = SavedStateHandle(restoredValues)
      model = createModel(savedState)
    }
    awaitReady()

    assertEquals(before.snapshot, state().snapshot)
    assertEquals(PendingScan(batch, value), state().pendingScan)
    assertEquals(restoredValues, savedValues())
    assertEquals(before.snapshot, persistentSnapshot())
    assertTrue(requireNotNull(state().snapshot).batch.allowDuplicates)
    onMain {
      model.confirmScan()
      model.confirmScan()
      model.offerScan("must not replace the pending candidate")
    }
    awaitReady()
    onMain { model.confirmScan() }
    awaitReady()

    val after = persistentSnapshot()
    assertEquals(batch, after.batch.id)
    assertEquals(listOf("already saved", value), after.records.map { it.value })
    assertEquals(3L, after.totalRecordCount)
    RecorderStore(application, databaseName).use { store ->
      assertEquals(listOf("keep in first batch"), store.select(firstBatch).records.map { it.value })
      store.select(batch)
    }
    assertNull(state().pendingScan)
    assertFalse(savedState.contains("pending_scan_batch"))
    assertFalse(savedState.contains("pending_scan_value"))
    assertEquals(RecorderEvent.Saved(2, true), takeEvent())
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
    closeModels()
    onMain {
      savedState = restoredState
      model = createModel(savedState)
    }
    awaitReady()
    assertEquals("QR\n中文", state().pendingScan?.value)
    assertEquals(0, persistentRecords().size)
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

  private fun createModel(handle: SavedStateHandle): RecorderViewModel =
    RecorderViewModel(application, handle, databaseName).also {
      openedModels.add(it)
      viewModels.put("test", it)
    }

  private fun closeModels() {
    // onCleared queues SQLite close; wait for it before reopening or deleting the database.
    val workerField = RecorderViewModel::class.java.getDeclaredField("worker").apply { isAccessible = true }
    val workers = openedModels.map { workerField.get(it) as ExecutorService }
    onMain { viewModels.clear() }
    for (worker in workers) {
      assertTrue("ViewModel worker did not close", worker.awaitTermination(5, TimeUnit.SECONDS))
    }
    openedModels.clear()
    instrumentation.waitForIdleSync()
  }

  private fun savedValues(): Map<String, Any?> {
    var values = emptyMap<String, Any?>()
    onMain { values = savedState.keys().associateWith { savedState.get<Any?>(it) } }
    return values
  }

  private fun persistentSnapshot(): RecorderSnapshot = RecorderStore(application, databaseName).use {
    it.load("unused")
  }

  private fun persistentRecords(): List<ScanRecord> = persistentSnapshot().records

  private fun executeSql(sql: String) = RecorderStore(application, databaseName).use {
    it.writableDatabase.execSQL(sql)
  }

  private fun takeEvent(): RecorderEvent? {
    var event: RecorderEvent? = null
    onMain { event = model.events.value?.take() }
    return event
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