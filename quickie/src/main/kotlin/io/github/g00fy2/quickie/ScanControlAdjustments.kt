package io.github.g00fy2.quickie

/** Rejects stale control callbacks and frames decoded across camera geometry changes. */
internal class ScanControlAdjustments {
  enum class Operation { ZOOM, FOCUS }
  data class Token(val id: Long, val operation: Operation)

  private val active = mutableMapOf<Operation, Token>()
  private val frameUnblocked = mutableSetOf<Token>()
  private var nextId = 0L

  @Volatile var revision: Long = 0L
    private set

  val isAdjusting: Boolean get() = active.values.any { it !in frameUnblocked }

  fun begin(operation: Operation): Token {
    val token = Token(++nextId, operation)
    active.put(operation, token)?.let(frameUnblocked::remove)
    revision++
    return token
  }

  fun finish(token: Token): Boolean {
    if (active[token.operation] != token) return false
    active.remove(token.operation)
    frameUnblocked.remove(token)
    revision++
    return true
  }

  /** Keeps the callback token alive while allowing freshly captured frames after a bounded wait. */
  fun allowFrames(token: Token): Boolean {
    if (active[token.operation] != token || !frameUnblocked.add(token)) return false
    revision++
    return true
  }

  fun cancel(operation: Operation) {
    active.remove(operation)?.let(frameUnblocked::remove)
    revision++
  }

  fun clear() {
    active.clear()
    frameUnblocked.clear()
    revision++
  }

  fun acceptsFrame(frameRevision: Long): Boolean = !isAdjusting && frameRevision == revision
}