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

internal class MicroQrDecoderTest {
  @Test
  fun qrScopeDecodesMicroQrAndLeavesOrdinaryQrToMlKit() {
    val decoder = decoderFor(ScanFormatScope.QR)
    val defaultDecoder = decoderFor(ScanFormatScope.ALL)
    for ((payload, pattern) in listOf("00742" to M1, M4_TEXT to M4)) {
      for (inverted in listOf(false, true)) {
        val bitmap = fixture(pattern, inverted)
        try {
          assertEquals(payload, decoder.decode(bitmap).single().rawValue)
          assertTrue(defaultDecoder.decode(bitmap).isEmpty())
        } finally {
          bitmap.recycle()
        }
      }
    }

    val ordinary = fixture(ORDINARY_QR)
    try {
      assertTrue(decoder.decode(ordinary).isEmpty())
    } finally {
      ordinary.recycle()
    }
  }

  @Test
  fun rotatedCameraLuminanceKeepsMicroQrBoundsInTheScanWindow() {
    val decoder = decoderFor(ScanFormatScope.QR)
    val bitmap = fixture(M4)
    val expected = RectF(
      LEFT.toFloat(),
      TOP.toFloat(),
      (LEFT + MODULE * M4.first().length).toFloat(),
      (TOP + MODULE * M4.size).toFloat()
    )
    try {
      for (rotation in listOf(0, 90, 180, 270)) {
        val source = CameraLuminance(bitmap, rotation)
        val result = decoder.decode(source.image).single()
        assertEquals(M4_TEXT, result.rawValue)
        val toView = requireNotNull(
          BarcodeCoordinates.imageToView(Matrix(), rotation, bitmap.width, bitmap.height, Matrix())
        )
        val actual = RectF(result.bounds).also { toView.mapRect(it) }
        assertEquals(expected.left, actual.left, 4f)
        assertEquals(expected.top, actual.top, 4f)
        assertEquals(expected.right, actual.right, 4f)
        assertEquals(expected.bottom, actual.bottom, 4f)

        val fullWindow = RectF(expected).apply { inset(-8f, -8f) }
        val clippedWindow = RectF(fullWindow).apply { right = expected.centerX() }
        assertTrue(BarcodeCoordinates.isInsideWindow(result.bounds, toView, fullWindow))
        assertFalse(BarcodeCoordinates.isInsideWindow(result.bounds, toView, clippedWindow))
        assertEquals(Rect(0, 0, 24, 24), source.image.cropRect)
        assertFalse(source.closed)
      }
    } finally {
      bitmap.recycle()
    }
  }

  private fun decoderFor(scope: ScanFormatScope): NativeBarcodeDecoder {
    val tuning = ScanTuningOptions(formatScope = scope)
    val plan = ScanDecodePlan(tuning, intArrayOf(Barcode.FORMAT_ALL_FORMATS))
    return NativeBarcodeDecoder(plan.nativeFormats, tuning)
  }

  private fun fixture(pattern: List<String>, inverted: Boolean = false): Bitmap {
    val bitmap = Bitmap.createBitmap(320, 248, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(if (inverted) Color.BLACK else Color.WHITE)
    val paint = Paint().apply { color = if (inverted) Color.WHITE else Color.BLACK }
    pattern.forEachIndexed { row, modules ->
      modules.forEachIndexed { column, module ->
        if (module == '1') {
          val x = (LEFT + column * MODULE).toFloat()
          val y = (TOP + row * MODULE).toFloat()
          canvas.drawRect(x, y, x + MODULE, y + MODULE, paint)
        }
      }
    }
    return bitmap
  }

  private companion object {
    const val LEFT = 61
    const val TOP = 41
    const val MODULE = 8
    const val M4_TEXT = "MICRO-QR-0123"

    // Generated independently with Segno 1.6.6 using mask=0 and boost_error=False.
    // The bitmap background supplies the quiet zone.
    val M1 = listOf(
      "11111110101",
      "10000010101",
      "10111010010",
      "10111010101",
      "10111010010",
      "10000010011",
      "11111110011",
      "00000000100",
      "11000100011",
      "01110101001",
      "10011010010"
    )
    val M4 = listOf(
      "11111110101010101",
      "10000010100110001",
      "10111010110010000",
      "10111010000100010",
      "10111010000111100",
      "10000010011101100",
      "11111110100101110",
      "00000000101010100",
      "10110100101001011",
      "01010111000111101",
      "10101111011110100",
      "01101101010001111",
      "11101111001001001",
      "01100110110111010",
      "11000110000101000",
      "01111000101000101",
      "11011001101111011"
    )
    val ORDINARY_QR = listOf(
      "111111100010101111111",
      "100000100000101000001",
      "101110101010001011101",
      "101110100000101011101",
      "101110100101101011101",
      "100000100111001000001",
      "111111101010101111111",
      "000000001010000000000",
      "111011111010111000100",
      "000101010011000001101",
      "001111110111010011101",
      "000011010001110101111",
      "011010101111000000001",
      "000000001110000111110",
      "111111101100101100011",
      "100000101010000100001",
      "101110101100111101011",
      "101110100001000010010",
      "101110101001001111101",
      "100000101111110110111",
      "111111101001001001001"
    )
  }
}

/** Supplies a strided direct Y plane and a crop that excludes the symbol. */
private class CameraLuminance(bitmap: Bitmap, rotation: Int) {
  var closed = false
    private set
  private val rowStride = bitmap.width + 16
  private val buffer = ByteBuffer.allocateDirect(rowStride * bitmap.height).apply {
    for (y in 0 until bitmap.height) {
      for (x in 0 until bitmap.width) put(y * rowStride + x, Color.red(bitmap.getPixel(x, y)).toByte())
    }
  }
  private val info = cameraProxy<ImageInfo> { method ->
    check(method == "getRotationDegrees") { "Unexpected image-info access: $method" }
    rotation
  }
  private val plane = cameraProxy<ImageProxy.PlaneProxy> { method ->
    when (method) {
      "getBuffer" -> buffer
      "getRowStride" -> rowStride
      "getPixelStride" -> 1
      else -> error("Unexpected image-plane access: $method")
    }
  }
  val image = cameraProxy<ImageProxy> { method ->
    when (method) {
      "getWidth" -> bitmap.width
      "getHeight" -> bitmap.height
      "getFormat" -> ImageFormat.YUV_420_888
      "getCropRect" -> Rect(0, 0, 24, 24)
      "getPlanes" -> arrayOf(plane)
      "getImageInfo" -> info
      "close" -> {
        closed = true
      }
      else -> error("Unexpected image access: $method")
    }
  }
}

private inline fun <reified T> cameraProxy(crossinline invoke: (String) -> Any): T =
  Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> invoke(method.name) } as T