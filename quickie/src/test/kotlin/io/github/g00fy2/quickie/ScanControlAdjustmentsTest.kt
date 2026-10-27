package io.github.g00fy2.quickie

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ScanControlAdjustmentsTest {
  @Test
  fun `rapid zoom changes ignore older completion and keep newer request pending`() {
    val state = ScanControlAdjustments()
    val old = state.begin(ScanControlAdjustments.Operation.ZOOM)
    val latest = state.begin(ScanControlAdjustments.Operation.ZOOM)
    assertFalse(state.finish(old))
    assertFalse(state.acceptsFrame(state.revision))
    assertTrue(state.finish(latest))
    assertTrue(state.acceptsFrame(state.revision))
  }

  @Test
  fun `a frame captured before or during a camera adjustment is discarded after completion`() {
    val state = ScanControlAdjustments()
    val before = state.revision
    val request = state.begin(ScanControlAdjustments.Operation.ZOOM)
    val during = state.revision
    assertFalse(state.acceptsFrame(during))
    state.finish(request)
    assertFalse(state.acceptsFrame(before))
    assertFalse(state.acceptsFrame(during))
    assertTrue(state.acceptsFrame(state.revision))
  }

  @Test
  fun `zoom completion cannot resume decoding while autofocus is still pending`() {
    val state = ScanControlAdjustments()
    val zoom = state.begin(ScanControlAdjustments.Operation.ZOOM)
    val focus = state.begin(ScanControlAdjustments.Operation.FOCUS)
    state.finish(zoom)
    assertFalse(state.acceptsFrame(state.revision))
    state.finish(focus)
    assertTrue(state.acceptsFrame(state.revision))
  }

  @Test
  fun `callbacks from a stopped session cannot settle a restarted session`() {
    val state = ScanControlAdjustments()
    val old = state.begin(ScanControlAdjustments.Operation.FOCUS)
    state.clear()
    val restarted = state.begin(ScanControlAdjustments.Operation.FOCUS)
    assertFalse(state.finish(old))
    assertFalse(state.acceptsFrame(state.revision))
    assertTrue(state.finish(restarted))
  }

  @Test
  fun `entering review cancels focus feedback without settling an unrelated zoom`() {
    val state = ScanControlAdjustments()
    val focus = state.begin(ScanControlAdjustments.Operation.FOCUS)
    val zoom = state.begin(ScanControlAdjustments.Operation.ZOOM)
    state.cancel(ScanControlAdjustments.Operation.FOCUS)
    assertFalse(state.finish(focus))
    assertFalse(state.acceptsFrame(state.revision))
    assertTrue(state.finish(zoom))
    assertTrue(state.acceptsFrame(state.revision))
  }
}