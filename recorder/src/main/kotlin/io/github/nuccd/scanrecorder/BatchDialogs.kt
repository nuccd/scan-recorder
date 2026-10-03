package io.github.nuccd.scanrecorder

import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.nuccd.scanrecorder.databinding.DialogBatchActionsBinding
import io.github.nuccd.scanrecorder.databinding.DialogTextInputBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class BatchDialogs(
  private val activity: AppCompatActivity,
  private val model: RecorderViewModel,
  private val beforeDeletion: () -> Unit
) {
  fun show(current: RecorderSnapshot) {
    val names = current.batches.map {
      if (it.id == current.batch.id) activity.getString(R.string.current_batch_label, it.name) else it.name
    }
    val actions = DialogBatchActionsBinding.inflate(activity.layoutInflater)
    val dialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.batches)
      .setItems(names.toTypedArray()) { _, position -> model.selectBatch(current.batches[position].id) }
      .setView(actions.root).setNegativeButton(R.string.close, null).create()
    dialog.setOnShowListener {
      // Keep the management and closing actions visible; additional batches scroll inside the list.
      val metrics = activity.resources.displayMetrics
      val height = minOf((names.size * 48 * metrics.density).toInt(), (metrics.heightPixels * 0.15f).toInt())
      dialog.listView.layoutParams = dialog.listView.layoutParams.apply { this.height = height }
    }
    actions.newBatchButton.setOnClickListener {
      dialog.dismiss()
      nameBatch(R.string.new_batch, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date()), model::newBatch)
    }
    actions.renameBatchButton.setOnClickListener {
      dialog.dismiss()
      nameBatch(R.string.rename_current_batch, current.batch.name) { model.renameBatch(current.batch.id, it) }
    }
    actions.deleteBatchButton.setOnClickListener {
      dialog.dismiss()
      deleteBatch(current)
    }
    actions.deleteAllBatchesButton.setOnClickListener {
      dialog.dismiss()
      deleteAllBatches(current)
    }
    dialog.show()
  }

  private fun nameBatch(title: Int, initial: String, save: (String) -> Unit) {
    val content = DialogTextInputBinding.inflate(activity.layoutInflater)
    val input = content.dialogInput.apply {
      hint = activity.getString(R.string.batch_name)
      inputType = InputType.TYPE_CLASS_TEXT
      setText(initial)
      selectAll()
    }
    val dialog = MaterialAlertDialogBuilder(activity).setTitle(title).setView(content.root)
      .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
    dialog.setOnShowListener {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val name = requireNotNull(input.text).toString().trim()
        if (name.isEmpty()) {
          input.error = activity.getString(R.string.batch_name_required)
        } else {
          save(name)
          dialog.dismiss()
        }
      }
    }
    dialog.show()
  }

  private fun deleteBatch(current: RecorderSnapshot) {
    val description = activity.getString(R.string.delete_batch_confirm, current.batch.name, current.records.size)
    val consequence = activity.getString(
      if (current.batches.size == 1) R.string.delete_last_batch_description else R.string.delete_batch_description
    )
    MaterialAlertDialogBuilder(activity).setTitle(R.string.delete_current_batch)
      .setMessage("$description\n\n$consequence").setNegativeButton(R.string.cancel, null)
      .setPositiveButton(R.string.delete_batch) { _, _ ->
        beforeDeletion()
        model.deleteBatch(current.batch.id)
      }.show()
  }

  private fun deleteAllBatches(current: RecorderSnapshot) {
    val description = activity.getString(
      R.string.delete_all_batches_confirm,
      current.batches.size,
      current.totalRecordCount
    )
    MaterialAlertDialogBuilder(activity).setTitle(R.string.delete_all_batches)
      .setMessage(description)
      .setNegativeButton(R.string.cancel, null)
      .setPositiveButton(R.string.delete_all_batches) { _, _ ->
        beforeDeletion()
        model.deleteAllBatches()
      }.show()
  }
}