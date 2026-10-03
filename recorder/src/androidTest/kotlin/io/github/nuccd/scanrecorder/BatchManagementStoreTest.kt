package io.github.nuccd.scanrecorder

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

internal class BatchManagementStoreTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val databaseName = "batch-test-${UUID.randomUUID()}.db"
  private lateinit var store: RecorderStore

  @Before
  fun open() {
    store = RecorderStore(context, databaseName)
  }

  @After
  fun close() {
    store.close()
    context.deleteDatabase(databaseName)
  }

  @Test
  fun renamingPreservesIdentityRecordsOrderAndDuplicatePreferenceAfterReopening() {
    val batch = store.load("before").batch
    store.setDuplicates(batch.id, true)
    store.save(batch.id, "001234567890123")
    val before = store.save(batch.id, "中文\n二维码").first
    val renamed = store.renameBatch(batch.id, "  after  ")
    assertEquals(before.batch.copy(name = "after"), renamed.batch)
    assertEquals(before.records, renamed.records)
    assertEquals(2L, renamed.totalRecordCount)
    store.close()
    store = RecorderStore(context, databaseName)
    assertEquals(renamed, store.load("unused"))
  }

  @Test
  fun renamingAnotherBatchKeepsTheSelectionAndCountsRecordsAcrossAllBatches() {
    val first = store.load("first").batch
    store.save(first.id, "A")
    val second = store.newBatch("second").batch
    store.save(second.id, "B")
    store.save(second.id, "C")
    store.newBatch("empty")
    val before = store.select(first.id)
    val after = store.renameBatch(second.id, "renamed")
    assertEquals(before.batch, after.batch)
    assertEquals(before.records, after.records)
    assertEquals(3L, after.totalRecordCount)
    assertEquals("renamed", after.batches.first { it.id == second.id }.name)
  }

  @Test
  fun blankNamesAreRejectedWithoutChangingStoredData() {
    val batch = store.load("original").batch
    val before = store.save(batch.id, "keep").first
    assertTrue(runCatching { store.renameBatch(batch.id, " \n ") }.isFailure)
    assertEquals(before, store.load("unused"))
  }

  @Test
  fun deletingAllBatchesRemovesAllRecordsAndPersistsOneFreshUsableBatch() {
    val first = store.load("first").batch
    store.save(first.id, "A")
    val second = store.newBatch("second").batch
    store.setDuplicates(second.id, true)
    store.save(second.id, "B")
    store.newBatch("empty")
    val after = store.deleteAllBatches("replacement")
    assertEquals(listOf(after.batch), after.batches)
    assertNotEquals(first.id, after.batch.id)
    assertNotEquals(second.id, after.batch.id)
    assertEquals("replacement", after.batch.name)
    assertFalse(after.batch.allowDuplicates)
    assertTrue(after.records.isEmpty())
    assertEquals(0L, after.totalRecordCount)
    store.close()
    store = RecorderStore(context, databaseName)
    assertEquals(after, store.load("unused"))
    assertEquals(listOf("00123"), store.save(after.batch.id, "00123").first.records.map { it.value })
  }

  @Test
  fun failedReplacementCreationRollsBackAllBatchAndRecordDeletion() {
    val first = store.load("first").batch
    store.save(first.id, "A")
    val second = store.newBatch("second").batch
    val before = store.save(second.id, "B").first
    store.writableDatabase.execSQL(
      "CREATE TRIGGER reject_new_batch BEFORE INSERT ON batches BEGIN SELECT RAISE(ABORT, 'test failure'); END"
    )
    assertTrue(runCatching { store.deleteAllBatches("replacement") }.isFailure)
    assertEquals(before, store.load("unused"))
    assertEquals(listOf("A"), store.select(first.id).records.map { it.value })
    assertEquals(2L, store.select(second.id).totalRecordCount)
  }
}