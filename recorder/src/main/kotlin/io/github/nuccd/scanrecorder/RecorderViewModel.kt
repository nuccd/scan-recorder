package io.github.nuccd.scanrecorder

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

internal data class RecorderState(
  val snapshot: RecorderSnapshot? = null,
  val working: Boolean = true,
  val pendingScan: PendingScan? = null
)

internal sealed interface RecorderEvent {
  data class Saved(val count: Int, val scanned: Boolean) : RecorderEvent
  data object Duplicate : RecorderEvent
  data class Deleted(val record: DeletedRecord) : RecorderEvent
  data class Notice(val resource: Int) : RecorderEvent
  data class Failure(val resource: Int, val detail: String) : RecorderEvent
  data class SaveDocument(val filename: String) : RecorderEvent
  data class Exported(val count: Int, val log: Boolean) : RecorderEvent
}

internal class Once<out T>(private val value: T) {
  private var consumed = false
  fun take(): T? = if (consumed) null else value.also { consumed = true }
}

// One entry point per user action; storage and export stay serialized on the same worker.
@Suppress("TooManyFunctions")
internal class RecorderViewModel @JvmOverloads constructor(
  application: Application,
  private val savedState: SavedStateHandle,
  databaseName: String = "scan-recorder.db"
) : AndroidViewModel(application) {
  private val store = RecorderStore(application, databaseName)
  private val worker = Executors.newSingleThreadExecutor()
  private val main = Handler(Looper.getMainLooper())
  private val mutableState = MutableLiveData(RecorderState(pendingScan = restoredScan()))
  private val mutableEvents = MutableLiveData<Once<RecorderEvent>>()
  val state: LiveData<RecorderState> = mutableState
  val events: LiveData<Once<RecorderEvent>> = mutableEvents
  private var snapshot: RecorderSnapshot? = null
  private val pendingExport = AtomicFile(File(application.filesDir, "pending-export.txt"))
  private var pendingOperations = 0

  init {
    submit { publish(store.load(defaultBatchName())) }
  }

  /** Runs on the UI thread. Recognition alone never writes to the database. */
  fun offerScan(value: String) {
    val current = requireNotNull(mutableState.value)
    val batch = current.snapshot?.batch
    if (batch == null || current.pendingScan != null || value.isEmpty()) return
    val pending = PendingScan(batch.id, value)
    savedState["pending_scan_batch"] = pending.batchId
    savedState["pending_scan_value"] = pending.value
    mutableState.value = current.copy(pendingScan = pending)
  }

  fun cancelScan() {
    val current = mutableState.value ?: return
    if (current.working) return
    clearSavedScan()
    mutableState.value = current.copy(pendingScan = null)
    emit(RecorderEvent.Notice(R.string.scan_cancelled))
  }

  fun confirmScan() {
    val current = requireNotNull(mutableState.value)
    val pending = current.pendingScan
    val recorded = current.snapshot
    if (current.working || pending == null || recorded == null) return
    if (!recorded.batch.allowDuplicates && recorded.duplicateRows(pending.value).isNotEmpty()) {
      clearSavedScan()
      mutableState.value = current.copy(pendingScan = null)
      emit(RecorderEvent.Duplicate)
    } else {
      submit {
        val (updated, saved) = store.save(pending.batchId, pending.value)
        val event = if (saved) RecorderEvent.Saved(updated.records.size, true) else RecorderEvent.Duplicate
        publish(updated, event, clearPending = true)
      }
    }
  }

  fun newBatch(name: String) = submit {
    publish(store.newBatch(name), RecorderEvent.Notice(R.string.batch_created))
  }

  fun selectBatch(id: String) = submit { publish(store.select(id)) }

  fun renameBatch(id: String, name: String) = submit {
    publish(store.renameBatch(id, name), RecorderEvent.Notice(R.string.batch_renamed))
  }

  fun deleteAllBatches() = submit {
    val replacementName = getApplication<Application>().getString(R.string.replacement_batch_name, defaultBatchName())
    val updated = store.deleteAllBatches(replacementName)
    publish(updated, RecorderEvent.Notice(R.string.all_batches_deleted), clearPending = true)
  }

  fun deleteBatch(id: String) = submit {
    val replacementName = getApplication<Application>().getString(R.string.replacement_batch_name, defaultBatchName())
    publish(store.deleteBatch(id, replacementName), RecorderEvent.Notice(R.string.batch_deleted))
  }

  fun setDuplicates(id: String, allowed: Boolean) = submit { publish(store.setDuplicates(id, allowed)) }

  fun save(id: String, value: String, editingId: String? = null, scanned: Boolean = false) = submit {
    val (updated, saved) = store.save(id, value, editingId)
    val event = when {
      !saved -> RecorderEvent.Duplicate
      editingId != null -> RecorderEvent.Notice(R.string.record_updated)
      else -> RecorderEvent.Saved(updated.records.size, scanned)
    }
    publish(updated, event)
  }

  fun delete(batchId: String, recordId: String) = submit {
    val (updated, deleted) = store.delete(batchId, recordId)
    publish(updated, RecorderEvent.Deleted(deleted))
  }

  fun restore(record: DeletedRecord) = submit {
    store.restore(record)
    publish(store.load(defaultBatchName()), RecorderEvent.Notice(R.string.record_restored))
  }

  fun clear(batchId: String) = submit { publish(store.clear(batchId), RecorderEvent.Notice(R.string.clear_done)) }

  fun prepareTxtExport() = submit {
    val current = requireNotNull(snapshot)
    if (current.records.isEmpty()) {
      emit(RecorderEvent.Notice(R.string.no_records))
      return@submit
    }
    val output = pendingExport.startWrite()
    try {
      val writer = OutputStreamWriter(output, Charsets.UTF_8)
      RecordExport.write(current.records.map { it.value }, writer)
      writer.flush()
      pendingExport.finishWrite(output)
    } catch (exception: Exception) {
      pendingExport.failWrite(output)
      throw exception
    }
    val safeName = current.batch.name.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").take(60)
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
    emit(RecorderEvent.SaveDocument("${safeName}_$timestamp.txt"))
  }

  fun finishTxtExport(uri: Uri?) = submit(R.string.export_failed) {
    if (uri == null) {
      pendingExport.delete()
      return@submit
    }
    val app = getApplication<Application>()
    val count = pendingExport.openRead().bufferedReader(Charsets.UTF_8).use { it.lineSequence().count() }
    app.contentResolver.openOutputStream(uri, "wt")?.use { destination ->
      pendingExport.openRead().use { it.copyTo(destination) }
    } ?: throw IOException("无法写入所选文件")
    pendingExport.delete()
    emit(RecorderEvent.Exported(count, false))
  }

  fun exportLog() = submit(R.string.export_failed) {
    val current = requireNotNull(snapshot)
    val lines = current.records.map { RecordExport.line(it.value) }
    if (lines.isEmpty()) {
      emit(RecorderEvent.Notice(R.string.no_records))
      return@submit
    }
    if (lines.any { it.toByteArray(Charsets.UTF_8).size > 3500 }) {
      throw IOException("本批含过长内容，请使用 TXT 导出；未输出任何记录")
    }
    val exportId = UUID.randomUUID().toString()
    Log.i("SCAN_INFO", "BEGIN export=$exportId batch=${current.batch.id} count=${lines.size}")
    for (line in lines) Log.i("SCAN_EXPORT", line)
    Log.i("SCAN_INFO", "END export=$exportId count=${lines.size}")
    emit(RecorderEvent.Exported(lines.size, true))
  }

  private fun defaultBatchName(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())

  private fun submit(errorResource: Int = R.string.storage_failed, operation: () -> Unit) {
    pendingOperations++
    mutableState.value = requireNotNull(mutableState.value).copy(working = true)
    worker.execute {
      try {
        operation()
      } catch (exception: Exception) {
        emit(RecorderEvent.Failure(errorResource, exception.localizedMessage ?: exception.javaClass.simpleName))
      } finally {
        main.post {
          pendingOperations--
          mutableState.value = requireNotNull(mutableState.value).copy(working = pendingOperations > 0)
        }
      }
    }
  }

  private fun publish(updated: RecorderSnapshot, event: RecorderEvent? = null, clearPending: Boolean = false) {
    snapshot = updated
    main.post {
      val current = requireNotNull(mutableState.value)
      if (clearPending) clearSavedScan()
      mutableState.value = current.copy(
        snapshot = updated,
        working = true,
        pendingScan = if (clearPending) null else current.pendingScan
      )
      if (event != null) mutableEvents.value = Once(event)
    }
  }

  private fun restoredScan(): PendingScan? {
    val batch = savedState.get<String>("pending_scan_batch")
    val value = savedState.get<String>("pending_scan_value")
    return if (batch != null && value != null) PendingScan(batch, value) else null
  }

  private fun clearSavedScan() {
    savedState.remove<String>("pending_scan_batch")
    savedState.remove<String>("pending_scan_value")
  }

  private fun emit(event: RecorderEvent) {
    main.post { mutableEvents.value = Once(event) }
  }

  override fun onCleared() {
    worker.execute { store.close() }
    worker.shutdown()
    super.onCleared()
  }
}