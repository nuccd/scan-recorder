package io.github.g00fy2.quickie

/**
 * Matching results must stay inside a short evidence window; brief missing frames add no evidence.
 * A null value represents an ordinary missing decode. Ambiguity and interrupted scanning require [reset].
 * Only an observed missing decode starts the gap timeout, so slower valid frames can still agree.
 */
internal class StableScanCandidate(
  private val minimumFrames: Int = 3,
  private val minimumMillis: Long = 200L,
  private val maximumGapMillis: Long = 150L,
  private val maximumObservationMillis: Long = 600L
) {
  private var candidate: String? = null
  private var frames = 0
  private var firstSeen = 0L
  private var missingSinceMillis: Long? = null
  private var lastFrameMillis: Long? = null

  fun next(value: String?, nowMillis: Long): String? {
    val previousFrame = lastFrameMillis
    if (previousFrame != null && nowMillis < previousFrame) reset()
    if (candidate != null && expired(nowMillis)) reset()
    lastFrameMillis = nowMillis
    if (value == null) {
      if (candidate != null && missingSinceMillis == null) missingSinceMillis = nowMillis
      return null
    }
    missingSinceMillis = null
    if (candidate != value) {
      candidate = value
      frames = 0
      firstSeen = nowMillis
    }
    frames = (frames + 1).coerceAtMost(minimumFrames)
    return value.takeIf { frames >= minimumFrames && nowMillis - firstSeen >= minimumMillis }
  }

  private fun expired(nowMillis: Long): Boolean {
    val missingSince = missingSinceMillis
    return nowMillis - firstSeen !in 0..maximumObservationMillis ||
      (missingSince != null && nowMillis - missingSince !in 0..maximumGapMillis)
  }

  fun reset() {
    candidate = null
    frames = 0
    missingSinceMillis = null
    lastFrameMillis = null
  }
}