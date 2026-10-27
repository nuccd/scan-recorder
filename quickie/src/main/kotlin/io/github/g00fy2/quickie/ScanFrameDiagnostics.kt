package io.github.g00fy2.quickie

/** The stage at which an analyzed frame was accepted or held back. */
public enum class ScanFrameStatus {
  TRANSFORM_UNAVAILABLE,
  NO_DECODE,
  OUTSIDE_WINDOW,
  MULTIPLE_VALUES,
  WAITING_FOR_STABILITY,
  ALREADY_REPORTED,
  REVIEW_PAUSED,
  DELIVERED,
  CAMERA_ADJUSTING
}

/**
 * Frame metadata and cumulative session counters, without barcode contents.
 * [width] and [height] describe the analysis buffer before [rotationDegrees] is applied.
 * [decodeMillis] measures ML Kit processing through its success callback.
 * [statusCounts] includes every [ScanFrameStatus], including stages not observed in this session.
 */
@Suppress("LongParameterList")
public data class ScanFrameDiagnostics(
  public val width: Int,
  public val height: Int,
  public val rotationDegrees: Int,
  public val decodeMillis: Long,
  public val decodedCount: Int,
  public val inWindowCount: Int,
  public val distinctInWindowCount: Int,
  public val status: ScanFrameStatus,
  public val totalFrames: Long,
  public val statusCounts: Map<ScanFrameStatus, Long>
)