package io.github.g00fy2.quickie

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class ScanTriggerTest {
  @Test
  fun `a stationary code is emitted once even across slow frames`() {
    val trigger = ScanTrigger()
    assertEquals("A", trigger.next(setOf("A"), "A", 0))
    assertNull(trigger.next(setOf("A"), "A", 10_000))
  }

  @Test
  fun `a brief detection dropout does not add another record`() {
    val trigger = ScanTrigger()
    trigger.next(setOf("A"), "A", 0)
    trigger.next(emptySet(), null, 100)
    assertNull(trigger.next(setOf("A"), "A", 300))
  }

  @Test
  fun `a code can be scanned again after leaving the frame`() {
    val trigger = ScanTrigger()
    trigger.next(setOf("A"), "A", 0)
    trigger.next(emptySet(), null, 100)
    assertEquals("A", trigger.next(setOf("A"), "A", 600))
  }

  @Test
  fun `selection jitter among visible codes does not cause repeated records`() {
    val trigger = ScanTrigger()
    val visible = setOf("A", "B")
    assertEquals("A", trigger.next(visible, "A", 0))
    assertEquals("B", trigger.next(visible, "B", 50))
    assertNull(trigger.next(visible, "A", 100))
    assertNull(trigger.next(visible, "B", 200))
  }
}