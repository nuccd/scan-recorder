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
  fun `both matching evidence and minimum observation time are required`() {
    val candidate = StableScanCandidate()
    assertNull(candidate.next("A", 0))
    assertNull(candidate.next("A", 50))
    assertNull(candidate.next("A", 100))
    assertEquals("A", candidate.next("A", 200))
  }

  @Test
  fun `slow processing with continuous matching decodes does not count as a missing decode`() {
    val candidate = StableScanCandidate()
    assertNull(candidate.next("A", 0))
    assertNull(candidate.next("A", 200))
    assertEquals("A", candidate.next("A", 400))
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
  fun `an intermittent missing frame preserves matching evidence within the gap limit`() {
    val candidate = StableScanCandidate()
    assertNull(candidate.next("A", 0))
    assertNull(candidate.next("A", 80))
    assertNull(candidate.next(null, 130))
    assertEquals("A", candidate.next("A", 200))
  }

  @Test
  fun `a missing frame cannot complete confirmation just because enough time has passed`() {
    val candidate = StableScanCandidate()
    assertNull(candidate.next("A", 0))
    assertNull(candidate.next("A", 80))
    assertNull(candidate.next("A", 100))
    assertNull(candidate.next(null, 200))
    assertEquals("A", candidate.next("A", 220))
  }

  @Test
  fun `missing frames never count toward the three matching decodes`() {
    val candidate = StableScanCandidate()
    assertNull(candidate.next("A", 0))
    assertNull(candidate.next(null, 70))
    assertNull(candidate.next("A", 140))
    assertNull(candidate.next(null, 200))
    assertEquals("A", candidate.next("A", 240))
  }

  @Test
  fun `the gap limit is inclusive and a longer gap restarts observation`() {
    val atBoundary = StableScanCandidate()
    atBoundary.next("A", 0)
    atBoundary.next("A", 80)
    atBoundary.next(null, 150)
    assertEquals("A", atBoundary.next("A", 300))

    val expired = StableScanCandidate()
    expired.next("A", 0)
    expired.next("A", 80)
    expired.next(null, 150)
    assertNull(expired.next("A", 301))
    assertNull(expired.next("A", 401))
    assertEquals("A", expired.next("A", 501))
  }

  @Test
  fun `a different valid value starts fresh without inheriting an earlier missing timeout`() {
    val candidate = StableScanCandidate()
    candidate.next("A", 0)
    candidate.next("A", 80)
    candidate.next(null, 130)
    assertNull(candidate.next("B", 200))
    assertNull(candidate.next("B", 350))
    assertEquals("B", candidate.next("B", 400))
  }

  @Test
  fun `continued empty frames cannot keep old evidence alive`() {
    val candidate = StableScanCandidate()
    candidate.next("A", 0)
    candidate.next("A", 80)
    assertNull(candidate.next(null, 130))
    assertNull(candidate.next(null, 180))
    assertNull(candidate.next(null, 230))
    assertNull(candidate.next(null, 280))
    assertNull(candidate.next("A", 300))
    assertNull(candidate.next("A", 400))
    assertEquals("A", candidate.next("A", 500))
  }

  @Test
  fun `alternating decoded values cannot borrow evidence from one another`() {
    val candidate = StableScanCandidate()
    listOf("A", "B", "A", "B", "A", "B").forEachIndexed { index, value ->
      assertNull(candidate.next(value, index * 100L))
    }
  }

  @Test
  fun `evidence older than the overall window must be collected again`() {
    val candidate = StableScanCandidate()
    candidate.next("A", 0)
    candidate.next("A", 100)
    assertEquals("A", candidate.next("A", 200))
    for (time in listOf(300L, 400L, 500L, 600L)) assertEquals("A", candidate.next("A", time))
    assertNull(candidate.next("A", 700))
    assertNull(candidate.next("A", 800))
    assertEquals("A", candidate.next("A", 900))
  }

  @Test
  fun `backward time restarts evidence even when the last valid decode was earlier`() {
    val candidate = StableScanCandidate()
    candidate.next("A", 1000)
    candidate.next("A", 1100)
    candidate.next(null, 1200)
    assertNull(candidate.next("A", 1190))
    assertNull(candidate.next("A", 1200))
    assertNull(candidate.next("A", 1290))
    assertEquals("A", candidate.next("A", 1390))
  }

  @Test
  fun `an explicit interruption discards otherwise fresh evidence and its missing timeout`() {
    val candidate = StableScanCandidate()
    candidate.next("A", 0)
    candidate.next("A", 80)
    candidate.next(null, 130)
    candidate.reset()
    assertNull(candidate.next("A", 200))
    assertNull(candidate.next("A", 300))
    assertEquals("A", candidate.next("A", 400))
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