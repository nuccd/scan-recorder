package io.github.nuccd.scanrecorder

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.nuccd.scanrecorder.databinding.DialogScanReviewBinding

/** Owns a dialog for the ViewModel's unsaved candidate; acknowledgement never happens on dismissal. */
internal class ScanReviewDialog(
  private val context: Context,
  private val onConfirm: () -> Unit,
  private val onCancel: () -> Unit
) {
  private var dialog: AlertDialog? = null
  private var displayed: PendingScan? = null
  private var failure: String? = null
  val isShowing: Boolean get() = dialog != null

  fun render(state: RecorderState) {
    val pending = state.pendingScan
    if (pending == null) {
      dismiss()
      return
    }
    if (displayed != pending) create(pending)
    val current = requireNotNull(dialog)
    val rows = state.snapshot?.duplicateRows(pending.value).orEmpty()
    val blocked = rows.isNotEmpty() && state.snapshot?.batch?.allowDuplicates == false
    val message = when {
      rows.isEmpty() -> context.getString(R.string.confirm_scan_description)
      blocked -> context.getString(R.string.duplicate_scan_blocked, rows.joinToString("、"))
      else -> context.getString(R.string.duplicate_scan_allowed, rows.joinToString("、"))
    }
    current.setTitle(if (rows.isEmpty()) R.string.confirm_scan_title else R.string.duplicate_scan_title)
    current.setMessage(failure ?: message)
    updateButtons(current, state, blocked)
  }

  private fun updateButtons(current: AlertDialog, state: RecorderState, blocked: Boolean) {
    current.setCancelable(!state.working && !blocked)
    current.setCanceledOnTouchOutside(false)
    current.getButton(AlertDialog.BUTTON_POSITIVE).apply {
      setText(if (blocked) R.string.confirm_continue else R.string.confirm_record)
      isEnabled = !state.working && state.snapshot != null
    }
    current.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
      visibility = if (blocked) View.GONE else View.VISIBLE
      isEnabled = !state.working
    }
  }

  private fun create(pending: PendingScan) {
    dismiss()
    displayed = pending
    val binding = DialogScanReviewBinding.inflate(LayoutInflater.from(context))
    binding.scanValue.text = pending.value
    val created = MaterialAlertDialogBuilder(context)
      .setTitle(R.string.confirm_scan_title)
      .setMessage(R.string.confirm_scan_description)
      .setView(binding.root)
      .setNegativeButton(R.string.cancel_record) { _, _ -> onCancel() }
      .setPositiveButton(R.string.confirm_record, null)
      .create()
    dialog = created
    created.setOnCancelListener { onCancel() }
    created.setOnShowListener {
      created.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        failure = null
        onConfirm()
      }
    }
    created.show()
  }

  fun showFailure(detail: String) {
    failure = context.getString(R.string.confirm_save_failed, detail)
    dialog?.setMessage(failure)
  }

  fun dismiss() {
    dialog?.dismiss()
    dialog = null
    displayed = null
    failure = null
  }
}