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

internal class RecorderStoreTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val databaseName = "test-${UUID.randomUUID()}.db"
  private lateinit var store: RecorderStore

  @Before
  fun open() {
    store = RecorderStore(context, databaseName)
  }

  @After
  fun clean() {
    store.close()
    context.deleteDatabase(databaseName)
  }

  @Test
  fun recordsAndSelectedBatchSurviveReopening() {
    val first = store.load("first").batch
    store.save(first.id, "001234567890123")
    val second = store.newBatch("second").batch
    store.setDuplicates(second.id, true)
    store.save(second.id, "中文\n二维码")
    store.close()
    store = RecorderStore(context, databaseName)
    val restored = store.load("unused")
    assertEquals(second.id, restored.batch.id)
    assertTrue(restored.batch.allowDuplicates)
    assertEquals(listOf("中文\n二维码"), restored.records.map { it.value })
    assertEquals(listOf("001234567890123"), store.select(first.id).records.map { it.value })
  }

  @Test
  fun duplicateSwitchAndEditingAreEnforcedInsideTheTransaction() {
    val batch = store.load("test").batch
    val first = store.save(batch.id, "A").first.records.first()
    assertFalse(store.save(batch.id, "A").second)
    store.setDuplicates(batch.id, true)
    assertTrue(store.save(batch.id, "A").second)
    val updated = store.setDuplicates(batch.id, false)
    assertEquals(2, updated.records.size)
    assertEquals(1, updated.distinctCount)
    assertTrue(store.save(batch.id, "B", first.id).second)
    assertFalse(store.save(batch.id, "A", first.id).second)
    assertEquals(listOf("B", "A"), store.select(batch.id).records.map { it.value })
  }

  @Test
  fun undoPreservesOriginalOrderEvenAfterAppendingANewRecord() {
    val batch = store.load("test").batch
    store.save(batch.id, "A")
    val last = store.save(batch.id, "B").first.records.last()
    val deleted = store.delete(batch.id, last.id).second
    store.save(batch.id, "C")
    val restored = store.restore(deleted)
    assertEquals(listOf("A", "B", "C"), restored.records.map { it.value })
  }

  @Test
  fun clearingOneBatchLeavesOtherBatchesIntact() {
    val first = store.load("first").batch
    store.save(first.id, "A")
    val second = store.newBatch("second").batch
    store.save(second.id, "A")
    store.clear(second.id)
    assertEquals(0, store.select(second.id).records.size)
    assertEquals(listOf("A"), store.select(first.id).records.map { it.value })
  }

  @Test
  fun deletingSelectedBatchRemovesItsRecordsAndPersistsTheNextSelection() {
    val first = store.load("first").batch
    store.save(first.id, "keep")
    val deleted = store.newBatch("delete").batch
    store.save(deleted.id, "A")
    store.save(deleted.id, "B")
    val updated = store.deleteBatch(deleted.id, "unused")
    assertEquals(listOf(first.id), updated.batches.map { it.id })
    assertEquals(first.id, updated.batch.id)
    assertEquals(listOf("keep"), updated.records.map { it.value })
    assertEquals(1, storedRecordCount())
    store.close()
    store = RecorderStore(context, databaseName)
    assertEquals(first.id, store.load("unused").batch.id)
    assertEquals(listOf(first.id), store.load("unused").batches.map { it.id })
  }

  @Test
  fun deletingAnEmptyBatchKeepsTheOtherSelectedBatchAndItsRecords() {
    val first = store.load("first").batch
    store.save(first.id, "keep")
    val empty = store.newBatch("empty").batch
    store.select(first.id)
    val updated = store.deleteBatch(empty.id, "unused")
    assertEquals(first.id, updated.batch.id)
    assertEquals(listOf(first.id), updated.batches.map { it.id })
    assertEquals(listOf("keep"), updated.records.map { it.value })
  }

  @Test
  fun deletingTheLastBatchCreatesANewEmptyBatchWithoutOrphanedRecords() {
    val deleted = store.load("last").batch
    store.setDuplicates(deleted.id, true)
    store.save(deleted.id, "A")
    val updated = store.deleteBatch(deleted.id, "new empty batch")
    assertEquals(1, updated.batches.size)
    assertNotEquals(deleted.id, updated.batch.id)
    assertEquals("new empty batch", updated.batch.name)
    assertFalse(updated.batch.allowDuplicates)
    assertTrue(updated.records.isEmpty())
    assertEquals(0, storedRecordCount())
    store.close()
    store = RecorderStore(context, databaseName)
    assertEquals(updated.batch.id, store.load("unused").batch.id)
  }

  private fun storedRecordCount(): Int = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM records", null)
    .use { cursor ->
      assertTrue(cursor.moveToFirst())
      cursor.getInt(0)
    }
}