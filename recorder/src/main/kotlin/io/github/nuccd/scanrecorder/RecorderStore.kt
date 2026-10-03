package io.github.nuccd.scanrecorder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/** Accessed only by the ViewModel's single worker. Every list mutation is transactional. */
@Suppress("TooManyFunctions") // CRUD operations share a single database and transaction boundary.
internal class RecorderStore(context: Context, databaseName: String = "scan-recorder.db") :
  SQLiteOpenHelper(context, databaseName, null, 1) {
  override fun onConfigure(db: SQLiteDatabase) {
    db.setForeignKeyConstraintsEnabled(true)
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE batches (id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL, " +
        "duplicates INTEGER NOT NULL DEFAULT 0, next_position INTEGER NOT NULL DEFAULT 0)"
    )
    db.execSQL(
      "CREATE TABLE records (id TEXT PRIMARY KEY, batch_id TEXT NOT NULL REFERENCES batches(id) ON DELETE CASCADE, " +
        "value TEXT NOT NULL, created_at INTEGER NOT NULL, position INTEGER NOT NULL)"
    )
    db.execSQL("CREATE INDEX records_order ON records(batch_id, position)")
    db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    error("Unsupported database upgrade: $oldVersion -> $newVersion")
  }

  fun load(defaultName: String): RecorderSnapshot = transaction {
    val batches = batches()
    if (batches.isEmpty()) {
      createBatch(defaultName)
    } else {
      val selected = setting("current_batch")
      snapshot(batches.firstOrNull { it.id == selected } ?: batches.first())
    }
  }

  private fun createBatch(name: String): RecorderSnapshot {
    val batch = ScanBatch(UUID.randomUUID().toString(), name, System.currentTimeMillis(), false)
    writableDatabase.insertOrThrow(
      "batches",
      null,
      ContentValues().apply {
        put("id", batch.id)
        put("name", batch.name)
        put("created_at", batch.createdAt)
        put("duplicates", 0)
      }
    )
    setSetting("current_batch", batch.id)
    return snapshot(batch)
  }

  fun newBatch(name: String): RecorderSnapshot = transaction { createBatch(name) }

  fun renameBatch(batchId: String, name: String): RecorderSnapshot = transaction {
    val normalized = name.trim()
    require(normalized.isNotEmpty())
    val values = ContentValues().apply { put("name", normalized) }
    check(
      writableDatabase.update("batches", values, "id = ?", arrayOf(batchId)) == 1
    )
    val available = batches()
    val selected = setting("current_batch")
    snapshot(available.firstOrNull { it.id == selected } ?: available.first())
  }

  fun deleteAllBatches(defaultName: String): RecorderSnapshot = transaction {
    writableDatabase.delete("batches", null, null)
    createBatch(defaultName)
  }

  fun deleteBatch(batchId: String, defaultName: String): RecorderSnapshot = transaction {
    check(writableDatabase.delete("batches", "id = ?", arrayOf(batchId)) == 1)
    val remaining = batches()
    if (remaining.isEmpty()) {
      createBatch(defaultName)
    } else {
      val selected = setting("current_batch")
      val batch = remaining.firstOrNull { it.id == selected } ?: remaining.first()
      setSetting("current_batch", batch.id)
      snapshot(batch)
    }
  }

  fun select(batchId: String): RecorderSnapshot = transaction {
    val batch = batches().first { it.id == batchId }
    setSetting("current_batch", batch.id)
    snapshot(batch)
  }

  fun setDuplicates(batchId: String, allowed: Boolean): RecorderSnapshot = transaction {
    writableDatabase.update(
      "batches",
      ContentValues().apply { put("duplicates", if (allowed) 1 else 0) },
      "id = ?",
      arrayOf(batchId)
    )
    snapshot(batches().first { it.id == batchId })
  }

  fun save(batchId: String, value: String, editingId: String? = null): Pair<RecorderSnapshot, Boolean> = transaction {
    val batch = batches().first { it.id == batchId }
    val existing = records(batchId)
    if (!RecordRules.canStore(existing, value, batch.allowDuplicates, editingId)) {
      snapshot(batch) to false
    } else {
      if (editingId == null) {
        val record = ScanRecord(UUID.randomUUID().toString(), value, System.currentTimeMillis())
        insert(batchId, nextPosition(batchId), record)
      } else {
        check(
          writableDatabase.update(
            "records",
            ContentValues().apply { put("value", value) },
            "id = ? AND batch_id = ?",
            arrayOf(editingId, batchId)
          ) == 1
        )
      }
      snapshot(batch) to true
    }
  }

  fun delete(batchId: String, recordId: String): Pair<RecorderSnapshot, DeletedRecord> = transaction {
    val deleted = readableDatabase.rawQuery(
      "SELECT id, value, created_at, position FROM records WHERE id = ? AND batch_id = ?",
      arrayOf(recordId, batchId)
    ).use { cursor ->
      check(cursor.moveToFirst())
      DeletedRecord(batchId, cursor.getLong(3), ScanRecord(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
    }
    writableDatabase.delete("records", "id = ? AND batch_id = ?", arrayOf(recordId, batchId))
    snapshot(batches().first { it.id == batchId }) to deleted
  }

  fun restore(deleted: DeletedRecord): RecorderSnapshot = transaction {
    // Undo restores the original record even if the duplicate switch has changed in the meantime.
    insert(deleted.batchId, deleted.position, deleted.record)
    snapshot(batches().first { it.id == deleted.batchId })
  }

  fun clear(batchId: String): RecorderSnapshot = transaction {
    writableDatabase.delete("records", "batch_id = ?", arrayOf(batchId))
    snapshot(batches().first { it.id == batchId })
  }

  private fun batches(): List<ScanBatch> = readableDatabase.rawQuery(
    "SELECT id, name, created_at, duplicates FROM batches ORDER BY created_at DESC, rowid DESC",
    null
  ).use { cursor ->
    buildList {
      while (cursor.moveToNext()) {
        add(
          ScanBatch(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getInt(3) != 0)
        )
      }
    }
  }

  private fun records(batchId: String): List<ScanRecord> = readableDatabase.rawQuery(
    "SELECT id, value, created_at FROM records WHERE batch_id = ? ORDER BY position, rowid",
    arrayOf(batchId)
  ).use { cursor ->
    buildList {
      while (cursor.moveToNext()) add(ScanRecord(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
    }
  }

  private fun snapshot(batch: ScanBatch): RecorderSnapshot {
    val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM records", null).use { cursor ->
      check(cursor.moveToFirst())
      cursor.getLong(0)
    }
    return RecorderSnapshot(batches(), batch, records(batch.id), count)
  }

  private fun nextPosition(batchId: String): Long {
    val position = readableDatabase.rawQuery("SELECT next_position FROM batches WHERE id = ?", arrayOf(batchId))
      .use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
      }
    writableDatabase.update(
      "batches",
      ContentValues().apply { put("next_position", position + 1) },
      "id = ?",
      arrayOf(batchId)
    )
    return position
  }

  private fun insert(batchId: String, position: Long, record: ScanRecord) {
    writableDatabase.insertOrThrow(
      "records",
      null,
      ContentValues().apply {
        put("id", record.id)
        put("batch_id", batchId)
        put("value", record.value)
        put("created_at", record.createdAt)
        put("position", position)
      }
    )
  }

  private fun setting(key: String): String? = readableDatabase.rawQuery(
    "SELECT value FROM settings WHERE key = ?",
    arrayOf(key)
  )
    .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

  private fun setSetting(key: String, value: String) {
    writableDatabase.insertWithOnConflict(
      "settings",
      null,
      ContentValues().apply {
        put("key", key)
        put("value", value)
      },
      SQLiteDatabase.CONFLICT_REPLACE
    )
  }

  private fun <T> transaction(operation: () -> T): T {
    val db = writableDatabase
    db.beginTransaction()
    try {
      val result = operation()
      db.setTransactionSuccessful()
      return result
    } finally {
      db.endTransaction()
    }
  }
}