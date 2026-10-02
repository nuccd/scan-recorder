package io.github.g00fy2.quickie

import android.graphics.Matrix
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class BarcodeCoordinatesTest {
  @Test
  fun uprightMlKitCoordinatesMapBackToTheSameBufferAtEveryRightAngle() {
    for (angle in listOf(0, 90, 180, 270)) {
      val imageBox = when (angle) {
        0 -> RectF(10f, 20f, 30f, 50f)
        90 -> RectF(150f, 10f, 180f, 30f)
        180 -> RectF(70f, 150f, 90f, 180f)
        else -> RectF(20f, 70f, 50f, 90f)
      }
      val transform = requireNotNull(BarcodeCoordinates.imageToView(Matrix(), angle, 100, 200, Matrix()))
      transform.mapRect(imageBox)
      assertEquals(10f, imageBox.left, 0.01f)
      assertEquals(20f, imageBox.top, 0.01f)
      assertEquals(30f, imageBox.right, 0.01f)
      assertEquals(50f, imageBox.bottom, 0.01f)
    }
  }

  @Test
  fun differentAnalysisResolutionAndPreviewCropAreIncludedInTheMapping() {
    val sensorToBuffer = Matrix().apply { setScale(0.5f, 0.5f) }
    val sensorToView = Matrix().apply {
      setScale(2f, 2f)
      postTranslate(-40f, -80f)
    }
    val transform = requireNotNull(BarcodeCoordinates.imageToView(sensorToBuffer, 0, 100, 200, sensorToView))
    val box = RectF(10f, 20f, 30f, 50f)
    transform.mapRect(box)
    assertEquals(RectF(0f, 0f, 80f, 120f), box)
  }

  @Test
  fun boxesOutsideTheFrameOrOnlyPartlyInsideAreRejected() {
    val window = RectF(40f, 40f, 140f, 140f)
    val transform = Matrix().apply { setTranslate(40f, 40f) }
    assertTrue(BarcodeCoordinates.isInsideWindow(RectF(10f, 10f, 90f, 90f), transform, window))
    assertFalse(BarcodeCoordinates.isInsideWindow(RectF(-20f, 10f, 90f, 90f), transform, window))
    assertFalse(BarcodeCoordinates.isInsideWindow(RectF(110f, 10f, 140f, 90f), transform, window))
  }

  @Test
  fun invalidTransformDoesNotFallBackToScanningOutsideTheFrame() {
    val invalid = Matrix().apply { setScale(0f, 0f) }
    assertNull(BarcodeCoordinates.imageToView(invalid, 0, 100, 200, Matrix()))
  }
}