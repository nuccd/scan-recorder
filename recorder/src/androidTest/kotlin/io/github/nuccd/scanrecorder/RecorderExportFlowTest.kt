package io.github.nuccd.scanrecorder

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

internal class RecorderExportFlowTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val baseApplication = instrumentation.targetContext.applicationContext as Application
  private val databaseName = "export-test-${UUID.randomUUID()}.db"
  private val directory = File(baseApplication.cacheDir, "export-test-${UUID.randomUUID()}")
  private val pendingFile = File(directory, "pending-export.txt")
  private val viewModels = ViewModelStore()
  private lateinit var application: Application
  private lateinit var model: RecorderViewModel

  @Before
  fun open() {
    assertTrue(directory.mkdirs())
    application = ExportApplication(baseApplication, directory)
    createModel()
  }

  @After
  fun close() {
    if (::model.isInitialized) closeModel()
    baseApplication.deleteDatabase(databaseName)
    assertTrue(directory.deleteRecursively())
  }

  @Test
  fun preparedSnapshotKeepsOriginalBytesAndOrderAfterRecordsAreEditedAddedAndDeleted() {
    val batch = seedRecords()
    val prepared = snapshot()
    prepare()
    assertArrayEquals(expectedBytes(), pendingFile.readBytes())

    perform { model.save(batch, "changed after preparation", prepared.records[0].id) }
    perform { model.delete(batch, prepared.records[1].id) }
    perform { model.save(batch, "added after preparation") }
    val changed = snapshot()
    assertEquals(
      listOf("changed after preparation", "emoji 😀", "added after preparation"),
      changed.records.map { it.value }
    )

    val destination = File(directory, "snapshot.txt")
    perform { model.finishTxtExport(Uri.fromFile(destination)) }

    assertArrayEquals(expectedBytes(), destination.readBytes())
    assertEquals(RecorderEvent.Exported(3, false), takeEvent())
    assertFalse(pendingFile.exists())
    assertEquals(changed, persistentSnapshot())
  }

  @Test
  fun preparedSnapshotSurvivesViewModelRecreationBeforeTheDestinationReturns() {
    seedRecords()
    prepare()
    val before = persistentSnapshot()
    closeModel()
    createModel()

    assertArrayEquals(expectedBytes(), pendingFile.readBytes())
    val destination = File(directory, "after-recreation.txt")
    perform { model.finishTxtExport(Uri.fromFile(destination)) }

    assertArrayEquals(expectedBytes(), destination.readBytes())
    assertEquals(RecorderEvent.Exported(3, false), takeEvent())
    assertEquals(before, persistentSnapshot())
    assertFalse(pendingFile.exists())
  }

  @Test
  fun cancellingDeletesOnlyThePreparedSnapshotAndNeverReportsSuccess() {
    seedRecords()
    prepare()
    val before = persistentSnapshot()

    perform { model.finishTxtExport(null) }

    assertEquals(null, takeEvent())
    assertFalse(pendingFile.exists())
    assertEquals(before, persistentSnapshot())
    assertEquals(before, snapshot())
  }

  @Test
  fun openingFailurePreservesTheSnapshotForRetryEvenAfterSwitchingBatches() {
    seedRecords()
    prepare()
    perform { model.newBatch("selected after export preparation") }
    val batch = snapshot().batch.id
    perform { model.save(batch, "this belongs to the new batch") }
    val before = persistentSnapshot()

    // A directory is an invalid file destination, independent of storage permissions or free space.
    perform { model.finishTxtExport(Uri.fromFile(directory)) }

    assertExportFailure()
    assertArrayEquals(expectedBytes(), pendingFile.readBytes())
    assertEquals(before, persistentSnapshot())

    val destination = File(directory, "retry-after-open-failure.txt")
    perform { model.finishTxtExport(Uri.fromFile(destination)) }
    assertArrayEquals(expectedBytes(), destination.readBytes())
    assertEquals(RecorderEvent.Exported(3, false), takeEvent())
    assertFalse(pendingFile.exists())
    assertEquals(before, persistentSnapshot())
  }

  @Test
  fun interruptedWriteRetainsTheCompleteSnapshotAndRetryStartsFromItsBeginning() {
    val batch = snapshot().batch.id
    // The provider limits its socket buffer so the writer cannot finish before the partial read closes it.
    val value = "中\\\n".repeat(32_768)
    val expected = ("中\\\\\\n".repeat(32_768) + "\r\n").toByteArray(Charsets.UTF_8)
    perform { model.save(batch, value) }
    prepare()
    assertArrayEquals(expected, pendingFile.readBytes())
    val before = persistentSnapshot()
    val token = UUID.randomUUID().toString()
    val destination = Uri.parse("content://${instrumentation.context.packageName}.export-failure/fail-write/$token")

    perform { model.finishTxtExport(destination) }

    assertExportFailure()
    val progress = requireNotNull(application.contentResolver.call(destination, "receivedBytes", token, null))
    val received = progress.getInt("receivedBytes")
    assertTrue("The provider must have accepted a prefix before the write failed", received in 1 until expected.size)
    assertArrayEquals(expected, pendingFile.readBytes())
    assertEquals(before, persistentSnapshot())

    val retry = File(directory, "retry-after-write-failure.txt")
    perform { model.finishTxtExport(Uri.fromFile(retry)) }
    assertArrayEquals(expected, retry.readBytes())
    assertEquals(RecorderEvent.Exported(1, false), takeEvent())
    assertFalse(pendingFile.exists())
    assertEquals(before, persistentSnapshot())
  }

  private fun seedRecords(): String {
    val batch = snapshot().batch.id
    for (value in listOf(" 00123 ", "中文\\路径\n下一行\r末尾", "emoji 😀")) {
      perform { model.save(batch, value) }
    }
    return batch
  }

  private fun expectedBytes(): ByteArray =
    " 00123 \r\n中文\\\\路径\\n下一行\\r末尾\r\nemoji 😀\r\n".toByteArray(Charsets.UTF_8)

  private fun prepare() {
    perform { model.prepareTxtExport() }
    val event = takeEvent()
    assertTrue("Expected the file chooser request, got $event", event is RecorderEvent.SaveDocument)
    assertTrue(pendingFile.isFile)
  }

  private fun assertExportFailure() {
    val event = takeEvent()
    assertTrue("Expected an export failure, got $event", event is RecorderEvent.Failure)
    event as RecorderEvent.Failure
    assertEquals(R.string.export_failed, event.resource)
    assertTrue(event.detail.isNotEmpty())
  }

  private fun createModel() {
    onMain {
      model = RecorderViewModel(application, SavedStateHandle(), databaseName)
      viewModels.put("export", model)
    }
    awaitReady()
  }

  private fun closeModel() {
    // onCleared queues the database close; wait before reopening or deleting this test's files.
    val field = RecorderViewModel::class.java.getDeclaredField("worker").apply { isAccessible = true }
    val worker = field.get(model) as ExecutorService
    onMain { viewModels.clear() }
    assertTrue("Export worker did not finish cleanup", worker.awaitTermination(5, TimeUnit.SECONDS))
  }

  private fun snapshot(): RecorderSnapshot {
    lateinit var current: RecorderSnapshot
    onMain { current = requireNotNull(model.state.value?.snapshot) }
    return current
  }

  private fun persistentSnapshot(): RecorderSnapshot = RecorderStore(application, databaseName).use {
    it.load("unused")
  }

  private fun takeEvent(): RecorderEvent? {
    var event: RecorderEvent? = null
    onMain { event = model.events.value?.take() }
    return event
  }

  private fun perform(action: () -> Unit) {
    onMain(action)
    awaitReady()
  }

  private fun awaitReady() {
    val deadline = SystemClock.elapsedRealtime() + 5000
    while (SystemClock.elapsedRealtime() < deadline) {
      var ready = false
      onMain { ready = model.state.value?.let { !it.working && it.snapshot != null } == true }
      if (ready) return
      SystemClock.sleep(10)
    }
    throw AssertionError("Export operation did not finish")
  }

  private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

  private class ExportApplication(base: Application, private val directory: File) : Application() {
    init {
      attachBaseContext(base)
    }

    override fun getFilesDir(): File = directory
  }
}