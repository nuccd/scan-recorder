package io.github.g00fy2.quickie

/** Rejects stale control callbacks and frames decoded across camera geometry changes. */
internal class ScanControlAdjustments {
  enum class Operation { ZOOM, FOCUS }
  data class Token(val id: Long, val operation: Operation)

  private val active = mutableMapOf<Operation, Token>()
  private var nextId = 0L

  @Volatile var revision: Long = 0L
    private set

  val isAdjusting: Boolean get() = active.isNotEmpty()

  fun begin(operation: Operation): Token {
    val token = Token(++nextId, operation)
    active[operation] = token
    revision++
    return token
  }

  fun finish(token: Token): Boolean {
    if (active[token.operation] != token) return false
    active.remove(token.operation)
    revision++
    return true
  }

  fun cancel(operation: Operation) {
    active.remove(operation)
    revision++
  }

  fun clear() {
    active.clear()
    revision++
  }

  fun acceptsFrame(frameRevision: Long): Boolean = !isAdjusting && frameRevision == revision
}