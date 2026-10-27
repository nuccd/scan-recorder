package io.github.g00fy2.quickie

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class ScanDiagnosticsTrackerTest {
  @Test
  fun `first frame publishes actual buffer metadata immediately`() {
    val snapshot = ScanDiagnosticsTracker().record(
      width = 1920,
      height = 1080,
      rotationDegrees = 90,
      decodeMillis = 47,
      decodedCount = 3,
      inWindowCount = 2,
      distinctInWindowCount = 1,
      status = ScanFrameStatus.WAITING_FOR_STABILITY,
      nowMillis = 27
    )!!

    assertEquals(1920, snapshot.width)
    assertEquals(1080, snapshot.height)
    assertEquals(90, snapshot.rotationDegrees)
    assertEquals(47L, snapshot.decodeMillis)
    assertEquals(3, snapshot.decodedCount)
    assertEquals(2, snapshot.inWindowCount)
    assertEquals(1, snapshot.distinctInWindowCount)
    assertEquals(ScanFrameStatus.WAITING_FOR_STABILITY, snapshot.status)
    assertEquals(1L, snapshot.totalFrames)
  }

  @Test
  fun `throttled frames retain intermittent decoding and filtering counts`() {
    val tracker = ScanDiagnosticsTracker()
    frame(tracker, now = 100)
    assertNull(frame(tracker, ScanFrameStatus.WAITING_FOR_STABILITY, 200, 1, 1, 1))
    assertNull(frame(tracker, ScanFrameStatus.OUTSIDE_WINDOW, 300, 1))
    assertNull(frame(tracker, now = 1099))
    val snapshot = frame(tracker, now = 1100)!!

    assertEquals(5L, snapshot.totalFrames)
    assertEquals(3L, snapshot.statusCounts[ScanFrameStatus.NO_DECODE])
    assertEquals(1L, snapshot.statusCounts[ScanFrameStatus.WAITING_FOR_STABILITY])
    assertEquals(1L, snapshot.statusCounts[ScanFrameStatus.OUTSIDE_WINDOW])
    assertEquals(0, snapshot.decodedCount)
    assertNull(frame(tracker, now = 1100))
  }

  @Test
  fun `all processing stages have independent cumulative counters`() {
    val tracker = ScanDiagnosticsTracker()
    ScanFrameStatus.entries.forEachIndexed { index, status ->
      val snapshot = frame(tracker, status, index * 1000L)!!
      assertEquals(status, snapshot.status)
      assertEquals((index + 1).toLong(), snapshot.totalFrames)
      assertEquals(ScanFrameStatus.entries.toSet(), snapshot.statusCounts.keys)
    }
    val snapshot = frame(tracker, now = ScanFrameStatus.entries.size * 1000L)!!
    ScanFrameStatus.entries.forEach { status ->
      assertEquals(if (status == ScanFrameStatus.NO_DECODE) 2L else 1L, snapshot.statusCounts[status])
    }
    assertEquals(snapshot.totalFrames, snapshot.statusCounts.values.sum())
  }

  @Test
  fun `published counters remain unchanged when later frames arrive`() {
    val tracker = ScanDiagnosticsTracker()
    val first = frame(tracker)!!
    val second = frame(tracker, ScanFrameStatus.DELIVERED, 1000, 1, 1, 1)!!

    assertEquals(1L, first.totalFrames)
    assertEquals(0L, first.statusCounts[ScanFrameStatus.DELIVERED])
    assertEquals(2L, second.totalFrames)
    assertEquals(1L, second.statusCounts[ScanFrameStatus.DELIVERED])
  }

  @Test
  fun `a new session clears counters and publishes immediately at an earlier timestamp`() {
    val tracker = ScanDiagnosticsTracker()
    val previous = frame(tracker, ScanFrameStatus.DELIVERED, 9000, 1, 1, 1)!!
    frame(tracker, ScanFrameStatus.REVIEW_PAUSED, 9500)
    tracker.reset()
    val next = frame(tracker, ScanFrameStatus.CAMERA_ADJUSTING, 0)!!

    assertEquals(1L, next.totalFrames)
    assertEquals(1L, next.statusCounts[ScanFrameStatus.CAMERA_ADJUSTING])
    assertEquals(0L, next.statusCounts[ScanFrameStatus.DELIVERED])
    assertEquals(0L, next.statusCounts[ScanFrameStatus.REVIEW_PAUSED])
    assertEquals(1L, previous.statusCounts[ScanFrameStatus.DELIVERED])
  }

  @Test
  fun `negative and regressing timestamps cannot flood snapshots or lose frame counts`() {
    val tracker = ScanDiagnosticsTracker()
    frame(tracker, now = -100)
    assertNull(frame(tracker, now = -50))
    assertNull(frame(tracker, now = 999))
    assertEquals(4L, frame(tracker, now = 1000)!!.totalFrames)
    assertNull(frame(tracker, now = 0))
    assertNull(frame(tracker, now = 1999))
    assertEquals(7L, frame(tracker, now = 2000)!!.totalFrames)
  }

  @Test
  fun `extreme clock regression cannot overflow the throttle interval`() {
    val tracker = ScanDiagnosticsTracker()
    frame(tracker, now = Long.MAX_VALUE - 1000L)
    assertNull(frame(tracker, now = Long.MIN_VALUE))
    assertEquals(3L, frame(tracker, now = Long.MAX_VALUE)!!.totalFrames)
  }

  @Test
  fun `invalid timing and counts cannot publish negative or contradictory metadata`() {
    val snapshot = ScanDiagnosticsTracker().record(
      width = 1280,
      height = 720,
      rotationDegrees = 270,
      decodeMillis = -1,
      decodedCount = 2,
      inWindowCount = 8,
      distinctInWindowCount = 5,
      status = ScanFrameStatus.MULTIPLE_VALUES,
      nowMillis = 0
    )!!

    assertEquals(0L, snapshot.decodeMillis)
    assertEquals(2, snapshot.decodedCount)
    assertEquals(2, snapshot.inWindowCount)
    assertEquals(2, snapshot.distinctInWindowCount)
    val negative = frame(ScanDiagnosticsTracker(), decoded = -1, inside = -2, distinct = -3)!!
    assertEquals(0, negative.decodedCount)
    assertEquals(0, negative.inWindowCount)
    assertEquals(0, negative.distinctInWindowCount)
  }

  private fun frame(
    tracker: ScanDiagnosticsTracker,
    status: ScanFrameStatus = ScanFrameStatus.NO_DECODE,
    now: Long = 0,
    decoded: Int = 0,
    inside: Int = 0,
    distinct: Int = 0
  ): ScanFrameDiagnostics? = tracker.record(1280, 720, 0, 30, decoded, inside, distinct, status, now)
}