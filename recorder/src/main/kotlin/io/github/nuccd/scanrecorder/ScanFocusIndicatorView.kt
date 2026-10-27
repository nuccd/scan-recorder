package io.github.nuccd.scanrecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import io.github.g00fy2.quickie.ScanFocusState
import io.github.g00fy2.quickie.ScanFocusStatus

/** A visual-only layer: taps always reach the camera preview underneath. */
internal class ScanFocusIndicatorView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : View(context, attrs) {
  private val density = resources.displayMetrics.density
  private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 2 * density
  }
  private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = resources.getDimension(R.dimen.focus_label_size)
    textAlign = Paint.Align.CENTER
    color = context.getColor(R.color.focus_label)
    setShadowLayer(3 * density, 0f, density, context.getColor(R.color.focus_shadow))
  }
  private var focus: ScanFocusState? = null
  private var message = ""
  private val dismiss = Runnable { hide() }

  init {
    isClickable = false
    isFocusable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  fun show(state: ScanFocusState) {
    removeCallbacks(dismiss)
    if (state.status == ScanFocusStatus.HIDDEN) {
      hide()
      return
    }
    focus = state
    val (color, text) = when (state.status) {
      ScanFocusStatus.FOCUSING -> R.color.focus_pending to R.string.focus_focusing
      ScanFocusStatus.FOCUSED -> R.color.focus_success to R.string.focus_focused
      ScanFocusStatus.FAILED -> R.color.focus_warning to R.string.focus_failed
      ScanFocusStatus.UNSUPPORTED -> R.color.focus_warning to R.string.focus_unsupported
      ScanFocusStatus.HIDDEN -> return
    }
    ring.color = context.getColor(color)
    message = context.getString(text)
    invalidate()
    if (state.status != ScanFocusStatus.FOCUSING) postDelayed(dismiss, 1800)
  }

  fun hide() {
    removeCallbacks(dismiss)
    focus = null
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val current = focus ?: return
    val radius = 20 * density
    val x = current.x.coerceIn(radius.coerceAtMost(width / 2f), (width - radius).coerceAtLeast(width / 2f))
    val y = current.y.coerceIn(radius.coerceAtMost(height / 2f), (height - radius).coerceAtLeast(height / 2f))
    canvas.drawCircle(x, y, radius, ring)
    val halfText = label.measureText(message) / 2
    val textX = x.coerceIn(halfText.coerceAtMost(width / 2f), (width - halfText).coerceAtLeast(width / 2f))
    val textY = if (y + radius + 20 * density < height) y + radius + 16 * density else y - radius - 8 * density
    canvas.drawText(message, textX, textY, label)
  }

  override fun onDetachedFromWindow() {
    removeCallbacks(dismiss)
    super.onDetachedFromWindow()
  }
}