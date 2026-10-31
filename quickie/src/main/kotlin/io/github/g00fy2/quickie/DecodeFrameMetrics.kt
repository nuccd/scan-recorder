package io.github.g00fy2.quickie

/** Per-frame elapsed times can overlap when ML Kit and native decoding execute concurrently. */
internal data class DecodeFrameMetrics(
  val decodeMillis: Long = 0L,
  val mlKitMillis: Long = 0L,
  val nativeMillis: Long = 0L,
  val mlKitCount: Int = 0,
  val nativeCount: Int = 0,
  val nativeAvailable: Boolean = true
)