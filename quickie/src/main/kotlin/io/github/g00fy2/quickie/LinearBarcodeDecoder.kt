package io.github.g00fy2.quickie

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.common.Barcode
import zxingcpp.BarcodeReader
import java.nio.ByteBuffer

/** Supplements ML Kit with linear-only native decoding, retaining every valid result and its bounds. */
internal class LinearBarcodeDecoder(formats: IntArray) {
  private val linearFormats = LINEAR_FORMATS.filterKeys { Barcode.FORMAT_ALL_FORMATS in formats || it in formats }
    .values.toSet()
  private var invertedLuminance: ByteBuffer? = null
  private val reader by lazy {
    BarcodeReader(
      BarcodeReader.Options(
        formats = linearFormats,
        tryHarder = true,
        tryRotate = true,
        tryInvert = true,
        tryDownscale = false,
        minLineCount = 2,
        textMode = BarcodeReader.TextMode.PLAIN
      )
    )
  }

  fun decode(image: ImageProxy): List<DecodedBarcode> {
    if (linearFormats.isEmpty()) return emptyList()
    val luminance = image.planes.first()
    check(luminance.pixelStride == 1 && luminance.buffer.isDirect) { "A direct, contiguous Y plane is required" }
    // JNI reports points relative to the rotated crop. Decode the complete buffer to match ML Kit coordinates.
    val found = convert(reader.read(FullImageProxy(image)))
    return found.ifEmpty {
      // Native tryInvert does not cover every linear symbology; retry once without changing the source plane.
      val inverse = invert(image.width, image.height, luminance)
      convert(reader.read(FullImageProxy(image, inverse)))
    }
  }

  internal fun decode(bitmap: Bitmap, rotation: Int = 0): List<DecodedBarcode> {
    if (linearFormats.isEmpty()) return emptyList()
    val found = convert(reader.read(bitmap, rotation = rotation))
    return found.ifEmpty { decodeInverted(bitmap, rotation) }
  }

  private fun decodeInverted(bitmap: Bitmap, rotation: Int): List<DecodedBarcode> {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    for (index in pixels.indices) pixels[index] = pixels[index] xor 0x00ffffff
    val inverse = Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    return try {
      convert(reader.read(inverse, rotation = rotation))
    } finally {
      inverse.recycle()
    }
  }

  private fun invert(width: Int, height: Int, plane: ImageProxy.PlaneProxy): ImageProxy.PlaneProxy {
    val length = width * height
    val inverse = invertedLuminance?.takeIf { it.capacity() >= length }
      ?: ByteBuffer.allocateDirect(length).also { invertedLuminance = it }
    inverse.clear()
    inverse.limit(length)
    val source = plane.buffer.duplicate()
    for (y in 0 until height) {
      val row = y * plane.rowStride
      for (x in 0 until width) inverse.put(source.get(row + x).toInt().inv().toByte())
    }
    inverse.rewind()
    return LuminancePlane(inverse, width)
  }

  private fun convert(results: List<BarcodeReader.Result>): List<DecodedBarcode> = results.mapNotNull { result ->
    val value = result.text?.takeIf { it.isNotEmpty() && result.error == null } ?: return@mapNotNull null
    val position = result.position
    val corners = listOf(position.topLeft, position.topRight, position.bottomRight, position.bottomLeft)
    val bounds = RectF(
      corners.minOf { it.x }.toFloat(),
      corners.minOf { it.y }.toFloat(),
      corners.maxOf { it.x }.toFloat(),
      corners.maxOf { it.y }.toFloat()
    )
    DecodedBarcode(value, bounds)
  }

  private class FullImageProxy(
    private val image: ImageProxy,
    private val luminance: ImageProxy.PlaneProxy? = null
  ) : ImageProxy by image {
    override fun getCropRect(): Rect = Rect(0, 0, width, height)

    override fun getPlanes(): Array<ImageProxy.PlaneProxy> = luminance?.let { arrayOf(it) } ?: image.planes
  }

  private class LuminancePlane(private val bytes: ByteBuffer, private val stride: Int) : ImageProxy.PlaneProxy {
    override fun getBuffer(): ByteBuffer = bytes

    override fun getRowStride(): Int = stride

    override fun getPixelStride(): Int = 1
  }

  private companion object {
    val LINEAR_FORMATS = mapOf(
      Barcode.FORMAT_CODABAR to BarcodeReader.Format.CODABAR,
      Barcode.FORMAT_CODE_39 to BarcodeReader.Format.CODE_39,
      Barcode.FORMAT_CODE_93 to BarcodeReader.Format.CODE_93,
      Barcode.FORMAT_CODE_128 to BarcodeReader.Format.CODE_128,
      Barcode.FORMAT_EAN_13 to BarcodeReader.Format.EAN_13,
      Barcode.FORMAT_EAN_8 to BarcodeReader.Format.EAN_8,
      Barcode.FORMAT_ITF to BarcodeReader.Format.ITF,
      Barcode.FORMAT_UPC_A to BarcodeReader.Format.UPC_A,
      Barcode.FORMAT_UPC_E to BarcodeReader.Format.UPC_E
    )
  }
}