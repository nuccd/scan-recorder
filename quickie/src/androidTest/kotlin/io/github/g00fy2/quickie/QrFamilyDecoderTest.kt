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

internal class QrFamilyDecoderTest {
  @Test
  fun mixedEnginesDecodeMicroVersionsAndRectangularQrInBothApplicableScopes() {
    val fixtures = with(QrFamilyFixtures) { listOf(M1, M2, M3, M4, RECTANGULAR) }
    val decoders = listOf(ScanFormatScope.ALL, ScanFormatScope.QR).associateWith { decoderFor(it) }
    for (sample in fixtures) {
      for (inverted in listOf(false, true)) {
        val bitmap = fixture(sample, inverted)
        try {
          decoders.forEach { (scope, decoder) ->
            val decoded = decoder.decode(bitmap).map { it.rawValue }
            assertEquals("$scope, inverted=$inverted", listOf(sample.payload), decoded)
          }
        } finally {
          bitmap.recycle()
        }
      }
    }
  }

  @Test
  fun nativeOnlyFindsOrdinaryMicroAndRectangularQrTogetherAndLinearScopeIgnoresThem() {
    val codes = with(QrFamilyFixtures) { listOf(ORDINARY, M4, RECTANGULAR) }
    val bitmap = fixture(codes)
    try {
      val expected = codes.map { it.payload }.toSet()
      for (scope in listOf(ScanFormatScope.ALL, ScanFormatScope.QR)) {
        val decoder = decoderFor(scope, ScanDecoderMode.NATIVE)
        assertEquals(scope.name, expected, decoder.decode(bitmap).map { it.rawValue }.toSet())
      }
      assertTrue(decoderFor(ScanFormatScope.LINEAR).decode(bitmap).isEmpty())
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun mixedEnginesLeaveOrdinaryQrOutOfTheNativePass() {
    val ordinary = fixture(QrFamilyFixtures.ORDINARY)
    try {
      for (scope in listOf(ScanFormatScope.ALL, ScanFormatScope.QR)) {
        assertTrue(decoderFor(scope).decode(ordinary).isEmpty())
      }
    } finally {
      ordinary.recycle()
    }
  }

  @Test
  fun rotatedCameraLuminanceKeepsMicroQrBoundsInTheScanWindow() {
    val decoder = decoderFor(ScanFormatScope.QR)
    val sample = QrFamilyFixtures.M4
    val bitmap = fixture(sample)
    val expected = RectF(
      LEFT.toFloat(),
      TOP.toFloat(),
      (LEFT + MODULE * sample.modules.first().length).toFloat(),
      (TOP + MODULE * sample.modules.size).toFloat()
    )
    try {
      for (rotation in listOf(0, 90, 180, 270)) {
        val source = CameraLuminance(bitmap, rotation)
        val result = decoder.decode(source.image).single()
        assertEquals(sample.payload, result.rawValue)
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

  private fun decoderFor(
    scope: ScanFormatScope,
    engine: ScanDecoderMode = ScanDecoderMode.COMBINED
  ): NativeBarcodeDecoder {
    val tuning = ScanTuningOptions(formatScope = scope, decoderMode = engine)
    val plan = ScanDecodePlan(tuning, intArrayOf(Barcode.FORMAT_ALL_FORMATS))
    return NativeBarcodeDecoder(plan.nativeFormats, tuning)
  }

  private fun fixture(sample: QrFixture, inverted: Boolean = false): Bitmap = fixture(listOf(sample), inverted)

  private fun fixture(samples: List<QrFixture>, inverted: Boolean = false): Bitmap {
    val width = maxOf(320, 2 * LEFT + MODULE * samples.maxOf { it.modules.first().length })
    val slotHeight = maxOf(248, 2 * TOP + MODULE * samples.maxOf { it.modules.size })
    val bitmap = Bitmap.createBitmap(width, samples.size * slotHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(if (inverted) Color.BLACK else Color.WHITE)
    val paint = Paint().apply { color = if (inverted) Color.WHITE else Color.BLACK }
    samples.forEachIndexed { index, sample ->
      drawModules(canvas, paint, sample.modules, TOP + index * slotHeight)
    }
    return bitmap
  }

  private fun drawModules(canvas: Canvas, paint: Paint, pattern: List<String>, top: Int) {
    pattern.forEachIndexed { row, modules ->
      modules.forEachIndexed { column, module ->
        if (module == '1') {
          val x = (LEFT + column * MODULE).toFloat()
          val y = (top + row * MODULE).toFloat()
          canvas.drawRect(x, y, x + MODULE, y + MODULE, paint)
        }
      }
    }
  }

  private companion object {
    const val LEFT = 61
    const val TOP = 41
    const val MODULE = 8
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