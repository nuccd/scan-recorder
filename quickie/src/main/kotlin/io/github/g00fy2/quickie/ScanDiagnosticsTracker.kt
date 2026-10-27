package io.github.g00fy2.quickie

/** Counts every frame while publishing at most one snapshot per second of monotonic session time. */
internal class ScanDiagnosticsTracker {
  private var totalFrames = 0L
  private val statusCounts = ScanFrameStatus.entries.associateWith { 0L }.toMutableMap()
  private var lastObservedMillis = 0L
  private var lastSnapshotMillis: Long? = null

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
    nowMillis: Long
  ): ScanFrameDiagnostics? {
    totalFrames++
    statusCounts[status] = statusCounts.getValue(status) + 1L
    val now = nowMillis.coerceAtLeast(lastObservedMillis)
    lastObservedMillis = now
    val previousSnapshot = lastSnapshotMillis
    if (previousSnapshot != null && now - previousSnapshot < 1000L) return null
    lastSnapshotMillis = now
    val decoded = decodedCount.coerceAtLeast(0)
    val inside = inWindowCount.coerceIn(0, decoded)
    return ScanFrameDiagnostics(
      width = width.coerceAtLeast(0),
      height = height.coerceAtLeast(0),
      rotationDegrees = rotationDegrees,
      decodeMillis = decodeMillis.coerceAtLeast(0L),
      decodedCount = decoded,
      inWindowCount = inside,
      distinctInWindowCount = distinctInWindowCount.coerceIn(0, inside),
      status = status,
      totalFrames = totalFrames,
      statusCounts = statusCounts.toMap()
    )
  }

  fun reset() {
    totalFrames = 0L
    ScanFrameStatus.entries.forEach { statusCounts[it] = 0L }
    lastObservedMillis = 0L
    lastSnapshotMillis = null
  }
}