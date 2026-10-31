package io.github.g00fy2.quickie

import android.graphics.ImageFormat
import android.graphics.Rect
import android.os.Looper
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Native-only decoding must not request a media image or initialize the ML Kit client. */
internal class NativeAnalyzerTest {
  @Test
  @OptIn(ExperimentalGetImage::class)
  fun nativeOnlyCallbacksRunOnMainBeforeTheImageIsClosedExactlyOnce() {
    val source = EmptyNativeImage()
    val completed = CountDownLatch(1)
    val analyzer = QRCodeAnalyzer(
      intArrayOf(Barcode.FORMAT_CODE_128),
      {},
      { throw AssertionError(it) },
      {},
      onFrame = { codes, _, metrics ->
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        assertEquals(0, source.closeCount.get())
        assertTrue(codes.isEmpty())
        assertEquals(0L, metrics.mlKitMillis)
        assertEquals(0, metrics.mlKitCount)
        completed.countDown()
      },
      tuning = ScanTuningOptions(decoderMode = ScanDecoderMode.NATIVE)
    )
    try {
      analyzer.analyze(source.image)
      assertTrue(completed.await(5, TimeUnit.SECONDS))
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertEquals(1, source.closeCount.get())
    } finally {
      analyzer.close()
    }
  }

  @Test
  @OptIn(ExperimentalGetImage::class)
  fun closingBeforeTheQueuedCallbackSuppressesDeliveryAndStillClosesTheImage() {
    val source = EmptyNativeImage()
    var delivered = false
    val analyzer = QRCodeAnalyzer(
      intArrayOf(Barcode.FORMAT_CODE_128),
      {},
      { throw AssertionError(it) },
      {},
      onFrame = { _, _, _ -> delivered = true },
      tuning = ScanTuningOptions(decoderMode = ScanDecoderMode.NATIVE)
    )
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      analyzer.analyze(source.image)
      analyzer.close()
    }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    assertFalse(delivered)
    assertEquals(1, source.closeCount.get())
  }
}

private class EmptyNativeImage {
  val closeCount = AtomicInteger()
  private val buffer = ByteBuffer.allocateDirect(64 * 32)
  private val info = nativeProxy<ImageInfo> { method ->
    check(method == "getRotationDegrees") { "Unexpected image-info access: $method" }
    0
  }
  private val plane = nativeProxy<ImageProxy.PlaneProxy> { method ->
    when (method) {
      "getBuffer" -> buffer
      "getRowStride" -> 64
      "getPixelStride" -> 1
      else -> error("Unexpected plane access: $method")
    }
  }
  val image: ImageProxy = nativeProxy { method ->
    when (method) {
      "getWidth" -> 64
      "getHeight" -> 32
      "getFormat" -> ImageFormat.YUV_420_888
      "getCropRect" -> Rect(0, 0, 64, 32)
      "getPlanes" -> arrayOf(plane)
      "getImageInfo" -> info
      "close" -> closeCount.incrementAndGet()
      else -> error("Native-only mode must not access $method")
    }
  }
}

private inline fun <reified T> nativeProxy(crossinline invoke: (String) -> Any): T =
  Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> invoke(method.name) } as T