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
 * [decodeMillis] measures processing by both engines through the merged frame callback.
 * Engine timings and counts describe the published frame; average and maximum include every session frame.
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
  public val statusCounts: Map<ScanFrameStatus, Long>,
  public val mlKitMillis: Long = 0,
  public val nativeMillis: Long = 0,
  public val mlKitCount: Int = 0,
  public val nativeCount: Int = 0,
  public val averageDecodeMillis: Double = 0.0,
  public val maxDecodeMillis: Long = 0,
  public val sessionMillis: Long = 0,
  public val nativeAvailable: Boolean = true
)