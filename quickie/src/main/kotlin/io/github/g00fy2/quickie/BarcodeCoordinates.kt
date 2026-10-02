package io.github.g00fy2.quickie

import android.graphics.Matrix
import android.graphics.RectF

/** ML Kit returns upright image coordinates; CameraX matrices refer to the unrotated buffer. */
internal object BarcodeCoordinates {
  fun isInsideWindow(bounds: RectF, imageToView: Matrix, window: RectF): Boolean {
    val projected = RectF(bounds)
    imageToView.mapRect(projected)
    return window.contains(projected)
  }

  fun imageToView(
    sensorToBuffer: Matrix,
    rotationDegrees: Int,
    width: Int,
    height: Int,
    sensorToView: Matrix
  ): Matrix? {
    val rotation = Matrix().apply { setRotate(rotationDegrees.toFloat()) }
    val rotatedBounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
    rotation.mapRect(rotatedBounds)
    rotation.postTranslate(-rotatedBounds.left, -rotatedBounds.top)
    val sensorToImage = Matrix(sensorToBuffer).apply { postConcat(rotation) }
    val imageToSensor = Matrix()
    if (!sensorToImage.invert(imageToSensor)) return null
    return imageToSensor.apply { postConcat(sensorToView) }
  }
}