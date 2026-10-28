package io.github.g00fy2.quickie

/** Counts every frame while publishing at most one snapshot per second of monotonic session time. */
internal class ScanDiagnosticsTracker {
  private var totalFrames = 0L
  private val statusCounts = ScanFrameStatus.entries.associateWith { 0L }.toMutableMap()
  private var lastObservedMillis = 0L
  private var lastSnapshotMillis: Long? = null
  private var sessionStartedMillis: Long? = null
  private var averageDecodeMillis = 0.0
  private var maxDecodeMillis = 0L

  @Suppress("LongParameterList")
  fun record(
    width: Int,
    height: Int,
    rotationDegrees: Int,
    decodeMillis: Long,
    decodedCount: Int,
    inWindowCount: Int,
    distinctInWindowCount: Int,
    status: ScanFrameStatus,
    nowMillis: Long,
    metrics: DecodeFrameMetrics = DecodeFrameMetrics()
  ): ScanFrameDiagnostics? {
    totalFrames = incrementSaturated(totalFrames)
    statusCounts[status] = incrementSaturated(statusCounts.getValue(status))
    val duration = decodeMillis.coerceAtLeast(0L)
    averageDecodeMillis += (duration.toDouble() - averageDecodeMillis) / totalFrames.toDouble()
    maxDecodeMillis = maxOf(maxDecodeMillis, duration)
    val now = nowMillis.coerceAtLeast(lastObservedMillis)
    lastObservedMillis = now
    val started = sessionStartedMillis ?: now.also { sessionStartedMillis = it }
    val previousSnapshot = lastSnapshotMillis
    if (previousSnapshot != null && now - previousSnapshot < 1000L) return null
    lastSnapshotMillis = now
    val decoded = decodedCount.coerceAtLeast(0)
    val inside = inWindowCount.coerceIn(0, decoded)
    return ScanFrameDiagnostics(
      width = width.coerceAtLeast(0),
      height = height.coerceAtLeast(0),
      rotationDegrees = rotationDegrees,
      decodeMillis = duration,
      decodedCount = decoded,
      inWindowCount = inside,
      distinctInWindowCount = distinctInWindowCount.coerceIn(0, inside),
      status = status,
      totalFrames = totalFrames,
      statusCounts = statusCounts.toMap(),
      mlKitMillis = metrics.mlKitMillis.coerceAtLeast(0L),
      nativeMillis = metrics.nativeMillis.coerceAtLeast(0L),
      mlKitCount = metrics.mlKitCount.coerceAtLeast(0),
      nativeCount = metrics.nativeCount.coerceAtLeast(0),
      averageDecodeMillis = averageDecodeMillis,
      maxDecodeMillis = maxDecodeMillis,
      sessionMillis = now - started,
      nativeAvailable = metrics.nativeAvailable
    )
  }

  fun reset() {
    totalFrames = 0L
    ScanFrameStatus.entries.forEach { statusCounts[it] = 0L }
    lastObservedMillis = 0L
    lastSnapshotMillis = null
    sessionStartedMillis = null
    averageDecodeMillis = 0.0
    maxDecodeMillis = 0L
  }

  private fun incrementSaturated(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L
}