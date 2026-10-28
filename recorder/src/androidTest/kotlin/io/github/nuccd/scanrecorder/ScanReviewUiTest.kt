package io.github.nuccd.scanrecorder

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

internal class ScanReviewUiTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val appId = instrumentation.targetContext.packageName
  private lateinit var activity: MainActivity
  private lateinit var model: RecorderViewModel
  private var review: ScanReviewDialog? = null

  @Before
  fun launch() {
    val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    activity = instrumentation.startActivitySync(intent) as MainActivity
    instrumentation.runOnMainSync { model = ViewModelProvider(activity)[RecorderViewModel::class.java] }
    waitFor { model.state.value?.working == false }
  }

  @After
  fun finish() {
    instrumentation.runOnMainSync {
      model.cancelScan()
      review?.dismiss()
      activity.finish()
    }
  }

  @Test
  fun nativeConfirmationShowsOriginalTextAndCancellingKeepsTheCount() {
    val count = requireNotNull(model.state.value?.snapshot).records.size
    val value = "001234567890999"
    instrumentation.runOnMainSync { model.offerScan(value) }
    assertEquals(value, node("$appId:id/scan_value").text.toString())
    assertEquals("确认录入", node("android:id/button1").text.toString())
    assertEquals("取消录入", node("android:id/button2").text.toString())
    screenshot("review-confirmation.png")
    node("android:id/button2").performAction(AccessibilityNodeInfo.ACTION_CLICK)
    waitFor { model.state.value?.pendingScan == null }
    assertEquals(count, model.state.value?.snapshot?.records?.size)
  }

  @Test
  fun duplicateDialogShowsAllRowsAndCannotContinueWithoutAcknowledgement() {
    val records = listOf(ScanRecord("1", "A", 0), ScanRecord("2", "B", 0), ScanRecord("3", "A", 0))
    val snapshot = RecorderSnapshot(emptyList(), ScanBatch("test", "Test", 0, false), records)
    var acknowledged = false
    instrumentation.runOnMainSync {
      review = ScanReviewDialog(activity, { acknowledged = true }, {})
      review?.render(RecorderState(snapshot, false, PendingScan("test", "A")))
    }
    assertTrue(node("android:id/message").text.toString().contains("第 1、3 行重复"))
    assertEquals("确认，继续扫码", node("android:id/button1").text.toString())
    assertFalse(
      instrumentation.uiAutomation.rootInActiveWindow
        .findAccessibilityNodeInfosByViewId("android:id/button2").any { it.isVisibleToUser }
    )
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    assertFalse(acknowledged)
    assertTrue(requireNotNull(review).isShowing)
    screenshot("review-duplicate.png")
    node("android:id/button1").performAction(AccessibilityNodeInfo.ACTION_CLICK)
    waitFor { acknowledged }
  }

  @Test
  fun batchDeletionIsVisibleAndCancellingKeepsTheBatchAndRecords() {
    val before = requireNotNull(model.state.value?.snapshot)
    node("$appId:id/batch_button").performAction(AccessibilityNodeInfo.ACTION_CLICK)
    assertEquals("删除当前批次", node("$appId:id/delete_batch_button").text.toString())
    node("$appId:id/delete_batch_button").performAction(AccessibilityNodeInfo.ACTION_CLICK)
    val message = node("android:id/message").text.toString()
    assertTrue(message.contains(before.batch.name))
    assertTrue(message.contains("${before.records.size} 条记录"))
    assertTrue(message.contains("无法撤销"))
    assertEquals("删除批次", node("android:id/button1").text.toString())
    screenshot("batch-delete-confirmation.png")
    node("android:id/button2").performAction(AccessibilityNodeInfo.ACTION_CLICK)
    instrumentation.waitForIdleSync()
    assertEquals(before, model.state.value?.snapshot)
  }

  private fun node(id: String): AccessibilityNodeInfo {
    var found: AccessibilityNodeInfo? = null
    waitFor {
      found = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)
        ?.firstOrNull { it.isVisibleToUser }
      found != null
    }
    return requireNotNull(found)
  }

  private fun screenshot(name: String) {
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    bitmap.recycle()
  }

  private fun waitFor(predicate: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 5000
    while (SystemClock.elapsedRealtime() < deadline) {
      if (predicate()) return
      SystemClock.sleep(20)
    }
    assertTrue("UI operation timed out", predicate())
  }
}