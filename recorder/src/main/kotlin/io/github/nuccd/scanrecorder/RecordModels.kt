package io.github.nuccd.scanrecorder

internal data class ScanRecord(
  val id: String,
  val value: String,
  val createdAt: Long,
  val delivery: KeyboardDelivery = KeyboardDelivery.NONE
)

internal data class ScanBatch(val id: String, val name: String, val createdAt: Long, val allowDuplicates: Boolean)

internal data class RecorderSnapshot(
  val batches: List<ScanBatch>,
  val batch: ScanBatch,
  val records: List<ScanRecord>,
  val totalRecordCount: Long = records.size.toLong()
) {
  val distinctCount: Int get() = records.map { it.value }.toSet().size
}

internal data class DeletedRecord(val batchId: String, val position: Long, val record: ScanRecord)

internal data class PendingScan(val batchId: String, val value: String)

internal fun RecorderSnapshot.duplicateRows(value: String): List<Int> =
  records.mapIndexedNotNull { index, record -> (index + 1).takeIf { record.value == value } }

internal object RecordRules {
  fun canStore(records: List<ScanRecord>, value: String, allowDuplicates: Boolean, editingId: String? = null): Boolean =
    value.isNotEmpty() && (
      allowDuplicates || records.any { it.id == editingId && it.value == value } ||
        records.none { it.id != editingId && it.value == value }
      )
}