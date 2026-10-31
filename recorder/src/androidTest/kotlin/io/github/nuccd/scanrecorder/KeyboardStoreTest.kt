package io.github.nuccd.scanrecorder

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

internal class KeyboardStoreTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val name = "keyboard-${UUID.randomUUID()}.db"

  @After fun clean() {
    context.deleteDatabase(name)
  }

  @Test fun oldDatabaseUpgradeKeepsValuesBatchesAndDuplicatePreference() {
    val path = context.getDatabasePath(name)
    path.parentFile?.mkdirs()
    SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
      db.execSQL("CREATE TABLE batches (id TEXT PRIMARY KEY, name TEXT, created_at INTEGER, duplicates INTEGER)")
      db.execSQL("ALTER TABLE batches ADD COLUMN next_position INTEGER DEFAULT 1")
      db.execSQL("CREATE TABLE records (id TEXT, batch_id TEXT, value TEXT, created_at INTEGER, position INTEGER)")
      db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)")
      db.execSQL("INSERT INTO batches VALUES ('b', 'original', 1, 1, 1)")
      db.execSQL("INSERT INTO records VALUES ('r', 'b', '001234567890123456', 2, 0)")
      db.version = 1
    }
    RecorderStore(context, name).use { store ->
      val snapshot = store.load("unused")
      assertEquals("original", snapshot.batch.name)
      assertEquals(true, snapshot.batch.allowDuplicates)
      assertEquals("001234567890123456", snapshot.records.single().value)
      assertEquals(KeyboardDelivery.NONE, snapshot.records.single().delivery)
    }
  }

  @Test fun modeDefaultsToOffAndRemembersTheSavedChoice() {
    val preferences = context.getSharedPreferences("keyboard_mode", 0)
    val hadValue = preferences.contains("enabled")
    val original = preferences.getBoolean("enabled", false)
    try {
      assertTrue(preferences.edit().remove("enabled").commit())
      assertFalse(KeyboardSettingsStore(context).enabled)
      KeyboardSettingsStore(context).enabled = true
      assertTrue(KeyboardSettingsStore(context).enabled)
      KeyboardSettingsStore(context).enabled = false
      assertFalse(KeyboardSettingsStore(context).enabled)
    } finally {
      val editor = preferences.edit()
      if (hadValue) editor.putBoolean("enabled", original) else editor.remove("enabled")
      assertTrue(editor.commit())
    }
  }

  @Test fun blockedAndPendingRecordsPersistAndDuplicatesDoNotCreateAnotherDelivery() {
    RecorderStore(context, name).use { store ->
      val batch = store.load("test").batch.id
      store.save(batch, "00123", keyboard = true)
      store.save(batch, "原文\n不变", keyboard = true)
      assertFalse(store.save(batch, "00123", keyboard = true).second)
    }
    RecorderStore(context, name).use { store ->
      val records = store.load("unused").records
      assertEquals(listOf("00123", "原文\n不变"), records.map { it.value })
      assertEquals(listOf(KeyboardDelivery.PENDING, KeyboardDelivery.BLOCKED), records.map { it.delivery })
    }
  }

  @Test fun interruptedSendStaysUncertainAndEditingInvalidatesPreviousSubmission() {
    RecorderStore(context, name).use { store ->
      val batch = store.load("test").batch.id
      val record = store.save(batch, "A", keyboard = true).first.records.single()
      store.setDelivery(batch, record.id, KeyboardDelivery.SENDING)
      store.recoverInterruptedDeliveries()
      assertEquals(KeyboardDelivery.UNCERTAIN, store.load("unused").records.single().delivery)
      store.setDelivery(batch, record.id, KeyboardDelivery.SUBMITTED)
      val changed = store.save(batch, "B", record.id).first.records.single()
      assertEquals(KeyboardDelivery.PENDING, changed.delivery)
      val deleted = store.delete(batch, record.id).second
      assertEquals(changed, store.restore(deleted).records.single())
    }
  }
}