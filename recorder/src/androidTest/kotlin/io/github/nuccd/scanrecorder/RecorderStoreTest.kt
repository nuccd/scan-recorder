package io.github.nuccd.scanrecorder

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}