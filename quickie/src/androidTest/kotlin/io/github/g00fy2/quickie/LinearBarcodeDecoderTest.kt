package io.github.g00fy2.quickie

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/** Exercises JNI decoding with generated fixtures, never photographs or production scan contents. */
internal class LinearBarcodeDecoderTest {
  @Test
  fun normalAndInvertedCode128KeepTheirOriginalTextAndBounds() {
    for (inverted in listOf(false, true)) {
      val bitmap = fixture(inverted = inverted)
      try {
        val result = decoder().decode(bitmap).single()
        assertEquals(FIRST_TEXT, result.rawValue)
        assertBoundsInsideBitmap(result.bounds, bitmap)
        assertTrue(result.bounds.left >= 75f)
        assertTrue(result.bounds.top >= 65f)
      } finally {
        bitmap.recycle()
      }
    }
  }

  @Test
  fun multipleVisibleCodesAreAllReturnedForTheHostToRejectAmbiguity() {
    val bitmap = fixture(twoCodes = true)
    try {
      val results = decoder().decode(bitmap)
      assertEquals(setOf(FIRST_TEXT, SECOND_TEXT), results.map { it.rawValue }.toSet())
      assertTrue(results.all { it.bounds.width() > 0 && it.bounds.height() > 0 })
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun aTiltedCodeIsDecodedWithoutChangingThePayload() {
    val bitmap = fixture(tilt = 8f)
    try {
      val result = decoder().decode(bitmap).single()
      assertEquals(FIRST_TEXT, result.rawValue)
      assertBoundsInsideBitmap(result.bounds, bitmap)
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun nativeRotationReturnsBoundsInTheCompleteUprightImage() {
    val bitmap = fixture()
    try {
      val decoder = decoder()
      val original = decoder.decode(bitmap).single().bounds
      val expected = RectF(original)
      val rotate = Matrix().apply {
        setRotate(90f)
        postTranslate(bitmap.height.toFloat(), 0f)
      }
      rotate.mapRect(expected)
      val result = decoder.decode(bitmap, rotation = 90).single()
      assertEquals(FIRST_TEXT, result.rawValue)
      assertRectNear(expected, result.bounds)
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun imageProxyCropIsIgnoredWithoutChangingOrClosingTheOriginalImage() {
    val bitmap = fixture()
    try {
      val source = ImageSource(bitmap, Rect(0, 0, 20, 20))
      val decoder = decoder()
      val expected = decoder.decode(bitmap).single()
      val result = decoder.decode(source.image).single()
      assertEquals(expected.rawValue, result.rawValue)
      assertRectNear(expected.bounds, result.bounds)
      assertEquals(Rect(0, 0, 20, 20), source.image.cropRect)
      assertFalse(source.closed)
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun inverseLuminanceIsReusedWithoutChangingTheSourceOrKeepingOldCodes() {
    val decoder = decoder()
    for (twoCodes in listOf(true, false)) {
      val bitmap = fixture(inverted = true, twoCodes = twoCodes)
      try {
        val source = ImageSource(bitmap, Rect(0, 0, 20, 20))
        val buffer = source.image.planes.first().buffer
        val results = decoder.decode(source.image)
        val expected = if (twoCodes) setOf(FIRST_TEXT, SECOND_TEXT) else setOf(FIRST_TEXT)
        assertEquals(expected, results.map { it.rawValue }.toSet())
        assertEquals(0, buffer.position())
        assertEquals(0, buffer.get(0).toInt())
        assertEquals(Rect(0, 0, 20, 20), source.image.cropRect)
        assertFalse(source.closed)
      } finally {
        bitmap.recycle()
      }
    }
  }

  @Test
  fun matrixOnlyFormatsDoNotInvokeTheLinearEngine() {
    val bitmap = fixture()
    try {
      assertTrue(LinearBarcodeDecoder(intArrayOf(Barcode.FORMAT_QR_CODE)).decode(bitmap).isEmpty())
      assertTrue(LinearBarcodeDecoder(intArrayOf(Barcode.FORMAT_EAN_13)).decode(bitmap).isEmpty())
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun customNativeOptionsAndTheExplicitInversionFallbackReachTheReader() {
    val options = ScanTuningOptions(
      nativeTryHarder = false,
      nativeTryRotate = false,
      nativeTryInvert = false,
      nativeTryDownscale = true,
      nativeMinLineCount = 1,
      nativeInvertFallback = false
    )
    val normal = fixture()
    val inverted = fixture(inverted = true)
    try {
      val formats = intArrayOf(Barcode.FORMAT_CODE_128)
      assertEquals(FIRST_TEXT, LinearBarcodeDecoder(formats, options).decode(normal).single().rawValue)
      assertTrue(LinearBarcodeDecoder(formats, options).decode(inverted).isEmpty())
      val fallback = options.copy(nativeInvertFallback = true)
      assertEquals(FIRST_TEXT, LinearBarcodeDecoder(formats, fallback).decode(inverted).single().rawValue)
    } finally {
      normal.recycle()
      inverted.recycle()
    }
  }

  private fun decoder(): LinearBarcodeDecoder = LinearBarcodeDecoder(intArrayOf(Barcode.FORMAT_ALL_FORMATS))

  private fun fixture(inverted: Boolean = false, twoCodes: Boolean = false, tilt: Float = 0f): Bitmap {
    val bitmap = Bitmap.createBitmap(900, 440, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(if (inverted) Color.BLACK else Color.WHITE)
    canvas.rotate(tilt, bitmap.width / 2f, bitmap.height / 2f)
    val paint = Paint().apply { color = if (inverted) Color.WHITE else Color.BLACK }
    drawPattern(canvas, paint, FIRST_PATTERN, 80f, 70f)
    if (twoCodes) drawPattern(canvas, paint, SECOND_PATTERN, 80f, 270f)
    return bitmap
  }

  private fun drawPattern(canvas: Canvas, paint: Paint, pattern: String, left: Float, top: Float) {
    pattern.forEachIndexed { index, module ->
      if (module == '1') canvas.drawRect(left + index * 3, top, left + (index + 1) * 3, top + 110, paint)
    }
  }

  private fun assertBoundsInsideBitmap(bounds: RectF, bitmap: Bitmap) {
    assertTrue(bounds.width() > 0 && bounds.height() > 0)
    assertTrue(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()).contains(bounds))
  }

  private fun assertRectNear(expected: RectF, actual: RectF) {
    assertEquals(expected.left, actual.left, 3f)
    assertEquals(expected.top, actual.top, 3f)
    assertEquals(expected.right, actual.right, 3f)
    assertEquals(expected.bottom, actual.bottom, 3f)
  }

  private companion object {
    const val FIRST_TEXT = "BOX-A-12345"
    const val SECOND_TEXT = "BOX-B-67890"

    // Standard Code 128 modules with their mandatory symbol checksum and stop pattern.
    const val FIRST_PATTERN = "11010010000100010110001000111011011100010110100110111001010001100010011011100" +
      "10011100110101110111101110110111010111011000101111011101100011101011"
    const val SECOND_PATTERN = "11010010000100010110001000111011011100010110100110111001000101100010011011100" +
      "11001110100101110111101100001010011011110110111010001101100011101011"
  }
}

/** Supplies the same direct luminance buffer layout as CameraX without opening a camera. */
private class ImageSource(bitmap: Bitmap, private val crop: Rect) {
  var closed = false
  private val info = proxy<ImageInfo> { method ->
    check(method == "getRotationDegrees") { "Unexpected image-info access: $method" }
    0
  }
  private val buffer = ByteBuffer.allocateDirect(bitmap.width * bitmap.height).apply {
    for (y in 0 until bitmap.height) {
      for (x in 0 until bitmap.width) put(Color.red(bitmap.getPixel(x, y)).toByte())
    }
    rewind()
  }
  private val plane = proxy<ImageProxy.PlaneProxy> { method ->
    when (method) {
      "getBuffer" -> buffer
      "getRowStride" -> bitmap.width
      "getPixelStride" -> 1
      else -> error("Unexpected image-plane access: $method")
    }
  }
  val image: ImageProxy = proxy { method ->
    when (method) {
      "getWidth" -> bitmap.width
      "getHeight" -> bitmap.height
      "getFormat" -> ImageFormat.YUV_420_888
      "getCropRect" -> Rect(crop)
      "getPlanes" -> arrayOf(plane)
      "getImageInfo" -> info
      "close" -> {
        closed = true
      }
      else -> error("Unexpected image access: $method")
    }
  }
}

private inline fun <reified T> proxy(crossinline invoke: (String) -> Any): T =
  Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> invoke(method.name) } as T