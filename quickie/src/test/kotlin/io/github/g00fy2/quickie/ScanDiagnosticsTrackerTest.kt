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

  @Test
  fun `session average and peak include every frame during throttling`() {
    val tracker = ScanDiagnosticsTracker()
    val first = frame(tracker, now = 100, duration = 10)!!
    assertNull(frame(tracker, now = 200, duration = 200))
    assertNull(frame(tracker, now = 300, duration = 30))
    val last = frame(tracker, now = 1100, duration = 40)!!

    assertEquals(10.0, first.averageDecodeMillis)
    assertEquals(10L, first.maxDecodeMillis)
    assertEquals(0L, first.sessionMillis)
    assertEquals(70.0, last.averageDecodeMillis)
    assertEquals(200L, last.maxDecodeMillis)
    assertEquals(1000L, last.sessionMillis)
    assertEquals(40L, last.decodeMillis)
  }

  @Test
  fun `engine fields describe the published frame while total duration uses its existing argument`() {
    val tracker = ScanDiagnosticsTracker()
    val first = frame(
      tracker,
      duration = 80,
      metrics = DecodeFrameMetrics(999, 45, 20, 2, 1, false)
    )!!
    assertNull(frame(tracker, now = 500, metrics = DecodeFrameMetrics(999, 1, 2, 1, 2)))
    val last = frame(tracker, now = 1000, metrics = DecodeFrameMetrics(999, 11, 12, 3, 4))!!

    assertEquals(80L, first.decodeMillis)
    assertEquals(80.0, first.averageDecodeMillis)
    assertEquals(45L, first.mlKitMillis)
    assertEquals(20L, first.nativeMillis)
    assertEquals(2, first.mlKitCount)
    assertEquals(1, first.nativeCount)
    assertEquals(false, first.nativeAvailable)
    assertEquals(11L, last.mlKitMillis)
    assertEquals(12L, last.nativeMillis)
    assertEquals(3, last.mlKitCount)
    assertEquals(4, last.nativeCount)
    assertEquals(true, last.nativeAvailable)
  }

  @Test
  fun `reset clears timing aggregates and engine metadata for the next session`() {
    val tracker = ScanDiagnosticsTracker()
    frame(tracker, now = 9000, duration = 600, metrics = DecodeFrameMetrics(600, 500, 100, 2, 3, false))
    frame(tracker, now = 9500, duration = 1000)
    tracker.reset()
    val next = frame(tracker, now = 10, duration = 8)!!

    assertEquals(8.0, next.averageDecodeMillis)
    assertEquals(8L, next.maxDecodeMillis)
    assertEquals(0L, next.sessionMillis)
    assertEquals(0L, next.mlKitMillis)
    assertEquals(0L, next.nativeMillis)
    assertEquals(0, next.mlKitCount)
    assertEquals(0, next.nativeCount)
    assertEquals(true, next.nativeAvailable)
  }

  @Test
  fun `session duration stays monotonic and handles the full nonnegative clock range`() {
    val tracker = ScanDiagnosticsTracker()
    frame(tracker, now = Long.MIN_VALUE)
    assertNull(frame(tracker, now = -1))
    val last = frame(tracker, now = Long.MAX_VALUE)!!

    assertEquals(Long.MAX_VALUE, last.sessionMillis)
    assertNull(frame(tracker, now = Long.MIN_VALUE))
    assertEquals(Long.MAX_VALUE, last.sessionMillis)
  }

  @Test
  fun `extreme decode durations cannot overflow the average and negative durations count as zero`() {
    val tracker = ScanDiagnosticsTracker()
    frame(tracker, duration = Long.MAX_VALUE)
    assertNull(frame(tracker, now = 1, duration = Long.MAX_VALUE))
    val last = frame(tracker, now = 1000, duration = Long.MIN_VALUE)!!

    assertEquals(Long.MAX_VALUE, last.maxDecodeMillis)
    assertEquals(Long.MAX_VALUE.toDouble() * (2.0 / 3.0), last.averageDecodeMillis, 2048.0)
    assertEquals(0L, last.decodeMillis)
    assertEquals(3L, last.totalFrames)
  }

  @Test
  fun `negative engine metrics are clamped before publication`() {
    val snapshot = frame(
      ScanDiagnosticsTracker(),
      metrics = DecodeFrameMetrics(Long.MIN_VALUE, Long.MIN_VALUE, -1, Int.MIN_VALUE, -1, false)
    )!!

    assertEquals(0L, snapshot.mlKitMillis)
    assertEquals(0L, snapshot.nativeMillis)
    assertEquals(0, snapshot.mlKitCount)
    assertEquals(0, snapshot.nativeCount)
    assertEquals(false, snapshot.nativeAvailable)
  }

  @Suppress("LongParameterList")
  private fun frame(
    tracker: ScanDiagnosticsTracker,
    status: ScanFrameStatus = ScanFrameStatus.NO_DECODE,
    now: Long = 0,
    decoded: Int = 0,
    inside: Int = 0,
    distinct: Int = 0,
    duration: Long = 30,
    metrics: DecodeFrameMetrics = DecodeFrameMetrics()
  ): ScanFrameDiagnostics? = tracker.record(1280, 720, 0, duration, decoded, inside, distinct, status, now, metrics)
}