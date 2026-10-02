package io.github.g00fy2.quickie

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class StableScanCandidateTest {
  @Test
  fun `a single decoding result is never accepted`() {
    assertNull(StableScanCandidate().next("001234", 0))
  }

  @Test
  fun `both consecutive agreement and minimum observation time are required`() {
    val candidate = StableScanCandidate()
    assertNull(candidate.next("A", 0))
    assertNull(candidate.next("A", 50))
    assertNull(candidate.next("A", 100))
    assertEquals("A", candidate.next("A", 200))
  }

  @Test
  fun `a differing result restarts observation`() {
    val candidate = StableScanCandidate()
    candidate.next("A", 0)
    candidate.next("A", 100)
    assertNull(candidate.next("B", 200))
    assertNull(candidate.next("A", 300))
    assertNull(candidate.next("A", 400))
    assertEquals("A", candidate.next("A", 500))
  }

  @Test
  fun `a missing or ambiguous frame restarts observation`() {
    val candidate = StableScanCandidate()
    candidate.next("A", 0)
    candidate.next("A", 100)
    candidate.next(null, 200)
    assertNull(candidate.next("A", 300))
    assertNull(candidate.next("A", 400))
    assertEquals("A", candidate.next("A", 500))
  }

  @Test
  fun `stale frames and a paused review cannot complete a new candidate`() {
    val candidate = StableScanCandidate()
    candidate.next("A", 0)
    candidate.next("A", 100)
    assertNull(candidate.next("A", 1200))
    candidate.reset()
    assertNull(candidate.next("A", 1300))
    assertNull(candidate.next("A", 1400))
    assertEquals("A", candidate.next("A", 1500))
  }
}