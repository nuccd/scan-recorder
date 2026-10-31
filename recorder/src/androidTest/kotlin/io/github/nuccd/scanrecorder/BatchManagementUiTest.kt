package io.github.nuccd.scanrecorder

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Only test-owned batches are mutated; global deletion is cancelled at its confirmation dialog. */
internal class BatchManagementUiTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val appId = instrumentation.targetContext.packageName
  private lateinit var activity: MainActivity
  private lateinit var model: RecorderViewModel
  private var originalBatch: String? = null
  private val testBatches = mutableListOf<String>()

  @Before
  fun launch() {
    val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    activity = instrumentation.startActivitySync(intent) as MainActivity
    instrumentation.runOnMainSync { model = ViewModelProvider(activity)[RecorderViewModel::class.java] }
    awaitReady()
    originalBatch = requireNotNull(model.state.value?.snapshot).batch.id
    instrumentation.runOnMainSync { model.newBatch("ui-test-${UUID.randomUUID()}") }
    awaitReady()
    val batch = requireNotNull(model.state.value?.snapshot).batch.id
    testBatches.add(batch)
    instrumentation.runOnMainSync {
      model.setDuplicates(batch, true)
      model.save(batch, "001234567890123")
    }
    awaitReady()
  }

  @After
  fun finish() {
    if (!::activity.isInitialized) return
    instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button2")
      ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    for (id in testBatches) {
      instrumentation.runOnMainSync { model.deleteBatch(id) }
      awaitReady()
    }
    originalBatch?.let { id ->
      instrumentation.runOnMainSync { model.selectBatch(id) }
      awaitReady()
    }
    instrumentation.runOnMainSync { activity.finish() }
  }

  @Test
  fun renameRejectsBlankInputAndSavesTheNewNameWithoutChangingRecords() {
    val before = requireNotNull(model.state.value?.snapshot)
    openManagement()
    click("rename_batch_button")
    assertEquals(before.batch.name, node("$appId:id/dialog_input").text.toString())
    setInput(" ")
    click("android:id/button1")
    assertTrue(node("$appId:id/dialog_input").isContentInvalid)
    assertEquals(before, model.state.value?.snapshot)
    setInput("重命名批次")
    click("android:id/button1")
    awaitReady()
    val after = requireNotNull(model.state.value?.snapshot)
    assertEquals(before.batch.copy(name = "重命名批次"), after.batch)
    assertEquals(before.records, after.records)
    openManagement()
    screenshot("batch-management-renamed.png")
    assertTrue(node("android:id/text1").text.toString().contains("重命名批次"))
  }

  @Test
  fun deleteAllShowsTheGlobalScopeAndCancellingPreservesAllData() {
    val before = requireNotNull(model.state.value?.snapshot)
    openManagement()
    screenshot("batch-management-actions.png")
    click("delete_all_batches_button")
    val message = node("android:id/message").text.toString()
    assertTrue(message.contains("全部 ${before.batches.size} 个批次"))
    assertTrue(message.contains("${before.totalRecordCount} 条记录"))
    assertTrue(message.contains("无法撤销"))
    assertTrue(message.contains("空批次"))
    screenshot("batch-delete-all-confirmation.png")
    click("android:id/button2")
    instrumentation.waitForIdleSync()
    assertEquals(before, model.state.value?.snapshot)
  }

  @Test
  fun newBatchButtonCreatesAndSelectsABatchWhileKeepingThePreviousRecords() {
    val before = requireNotNull(model.state.value?.snapshot)
    openManagement()
    click("new_batch_button")
    setInput("created-${UUID.randomUUID()}")
    click("android:id/button1")
    awaitReady()
    val after = requireNotNull(model.state.value?.snapshot)
    testBatches.add(after.batch.id)
    assertEquals(before.batches.size + 1, after.batches.size)
    assertTrue(after.records.isEmpty())
    assertEquals(before.totalRecordCount, after.totalRecordCount)
    instrumentation.runOnMainSync { model.selectBatch(before.batch.id) }
    awaitReady()
    assertEquals(before.records, model.state.value?.snapshot?.records)
  }

  private fun openManagement() {
    click("batch_button")
    for (id in listOf("new_batch_button", "rename_batch_button", "delete_batch_button", "delete_all_batches_button")) {
      assertTrue(node("$appId:id/$id").isVisibleToUser)
    }
    assertEquals("关闭", node("android:id/button2").text.toString())
  }

  private fun click(id: String) {
    val fullId = if (id.startsWith("android:")) id else "$appId:id/$id"
    assertTrue(node(fullId).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    instrumentation.waitForIdleSync()
  }

  private fun setInput(value: String) {
    val arguments = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
    }
    val input = node("$appId:id/dialog_input")
    assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
  }

  private fun node(id: String): AccessibilityNodeInfo {
    var result: AccessibilityNodeInfo? = null
    awaitReady {
      result = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)
        ?.firstOrNull { it.isVisibleToUser }
      result != null
    }
    return requireNotNull(result)
  }

  private fun screenshot(name: String) {
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    bitmap.recycle()
  }

  private fun awaitReady(
    predicate: () -> Boolean = {
      model.state.value?.let { !it.working && it.snapshot != null } == true
    }
  ) {
    val deadline = SystemClock.elapsedRealtime() + 5000
    while (SystemClock.elapsedRealtime() < deadline) {
      if (predicate()) return
      SystemClock.sleep(20)
    }
    assertTrue("Batch UI operation timed out", predicate())
  }
}