package io.github.nuccd.scanrecorder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class RecordRulesTest {
  private val records = listOf(ScanRecord("a", "001234", 1), ScanRecord("b", "BOX-A", 2))

  @Test
  fun `duplicates are rejected only when disabled`() {
    assertFalse(RecordRules.canStore(records, "001234", false))
    assertTrue(RecordRules.canStore(records, "001234", true))
    assertTrue(RecordRules.canStore(records, "BOX-B", false))
  }

  @Test
  fun `editing a record keeps its own value but cannot collide with another record`() {
    assertTrue(RecordRules.canStore(records, "001234", false, "a"))
    assertFalse(RecordRules.canStore(records, "BOX-A", false, "a"))
  }

  @Test
  fun `values are compared verbatim rather than as numbers or trimmed text`() {
    assertTrue(RecordRules.canStore(records, "1234", false))
    assertTrue(RecordRules.canStore(records, "001234 ", false))
    assertFalse(RecordRules.canStore(records, "", true))
  }

  @Test
  fun `turning off duplicates preserves previously stored duplicates and counts them correctly`() {
    val duplicateRecords = records + ScanRecord("c", "001234", 3)
    val state = RecorderSnapshot(listOf(), ScanBatch("batch", "Test", 0, false), duplicateRecords)
    assertEquals(3, state.records.size)
    assertEquals(2, state.distinctCount)
    assertFalse(RecordRules.canStore(state.records, "001234", state.batch.allowDuplicates))
    assertTrue(RecordRules.canStore(state.records, "001234", state.batch.allowDuplicates, "a"))
  }
}