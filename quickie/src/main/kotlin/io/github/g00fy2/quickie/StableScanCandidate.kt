package io.github.g00fy2.quickie

/** A candidate must remain identical across consecutive decoded frames and a minimum time window. */
internal class StableScanCandidate(
  private val minimumFrames: Int = 3,
  private val minimumMillis: Long = 200L,
  private val maximumGapMillis: Long = 1000L
) {
  private var candidate: String? = null
  private var frames = 0
  private var firstSeen = 0L
  private var lastSeen = 0L

  fun next(value: String?, nowMillis: Long): String? {
    if (value == null) {
      reset()
      return null
    }
    val sameCandidate = candidate == value && nowMillis - lastSeen in 0..maximumGapMillis
    if (!sameCandidate) {
      candidate = value
      frames = 0
      firstSeen = nowMillis
    }
    frames = (frames + 1).coerceAtMost(minimumFrames)
    lastSeen = nowMillis
    return value.takeIf { frames >= minimumFrames && nowMillis - firstSeen >= minimumMillis }
  }

  fun reset() {
    candidate = null
    frames = 0
  }
}