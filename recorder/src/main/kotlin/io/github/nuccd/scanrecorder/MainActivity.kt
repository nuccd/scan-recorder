package io.github.nuccd.scanrecorder

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.nuccd.scanrecorder.databinding.ActivityMainBinding
import io.github.nuccd.scanrecorder.databinding.DialogTextInputBinding

// Keeps screen actions and the Android lifecycle in one controller.
@Suppress("TooManyFunctions")
internal class MainActivity : AppCompatActivity() {
  private lateinit var binding: ActivityMainBinding
  private lateinit var model: RecorderViewModel
  private val adapter = RecordAdapter()
  private var snapshot: RecorderSnapshot? = null
  private var rendering = false
  private var scanRequested = false
  private var cameraActive = false
  private var foreground = false
  private var snackbar: Snackbar? = null
  private lateinit var review: ScanReviewDialog
  private lateinit var cameraUi: ScannerCameraUi
  private var squareFrame = false
  private val resizeFrame = Runnable { resizeScanWindow() }

  private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) {
      scanRequested = true
      startCameraIfReady()
    } else {
      pauseScanning()
      snackbar = Snackbar.make(binding.root, R.string.camera_permission, Snackbar.LENGTH_LONG)
        .setAction(R.string.open_settings) {
          startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }.also { it.show() }
    }
  }

  private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
    model.finishTxtExport(uri)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val padding = (16 * resources.displayMetrics.density).toInt()
    ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
      view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
      insets
    }
    model = ViewModelProvider(this)[RecorderViewModel::class.java]
    review = ScanReviewDialog(this, model::confirmScan, model::cancelScan)
    cameraUi = ScannerCameraUi(binding, savedInstanceState)
    binding.scanner.setDiagnosticsListener(ScanDiagnosticsLog::write)
    scanRequested = savedInstanceState?.getBoolean("scanning") ?: false
    squareFrame = savedInstanceState?.getBoolean("square_frame") ?: false
    setupFrameSelector()
    setupActions()
    model.state.observe(this, ::render)
    model.events.observe(this) { event -> event.take()?.let(::handleEvent) }
  }

  private fun setupActions() {
    binding.records.adapter = adapter
    binding.records.emptyView = binding.emptyRecords
    binding.records.setOnItemClickListener { _, _, position, _ -> editRecord(adapter.getItem(position)) }
    binding.scanButton.setOnClickListener {
      if (scanRequested) {
          pauseScanning()
      } else if (hasCameraPermission()) {
        scanRequested = true
        startCameraIfReady()
      } else {
          cameraPermission.launch(Manifest.permission.CAMERA)
      }
    }
    binding.torchButton.isCheckable = true
    binding.torchButton.setOnClickListener { binding.scanner.setTorch(binding.torchButton.isChecked) }
    binding.duplicatesSwitch.setOnCheckedChangeListener { _, checked ->
      if (!rendering) snapshot?.let { model.setDuplicates(it.batch.id, checked) }
    }
    binding.addButton.setOnClickListener { editRecord(null) }
    binding.batchButton.setOnClickListener { chooseBatch() }
    binding.clearButton.setOnClickListener { clearBatch() }
    binding.exportButton.setOnClickListener { exportTxt() }
    binding.logButton.setOnClickListener { exportLog() }
  }

  private fun setupFrameSelector() {
    binding.frameSelector.check(if (squareFrame) R.id.qr_frame else R.id.barcode_frame)
    binding.frameSelector.setOnCheckedChangeListener { _, checkedId ->
      squareFrame = checkedId == R.id.qr_frame
      resizeScanWindow()
    }
    binding.scanner.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
      // Resize the sibling after this layout pass so its new size gets measured on the next pass.
      binding.scanner.removeCallbacks(resizeFrame)
      binding.scanner.post(resizeFrame)
    }
    binding.scanFrame.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
      binding.scanner.setScanWindow(
        RectF(
          (left - binding.scanner.left).toFloat(),
          (top - binding.scanner.top).toFloat(),
          (right - binding.scanner.left).toFloat(),
          (bottom - binding.scanner.top).toFloat()
        )
      )
    }
    binding.scanner.setAmbiguityListener { ambiguous ->
      if (cameraActive && model.state.value?.pendingScan == null) {
        binding.status.setText(if (ambiguous) R.string.multiple_codes else R.string.scanning)
      }
    }
  }

  private fun resizeScanWindow() {
    val density = resources.displayMetrics.density
    val availableWidth = (binding.scanner.width - 32 * density).toInt().coerceAtLeast(0)
    val availableHeight = (binding.scanner.height - 32 * density).toInt().coerceAtLeast(0)
    val frameWidth = if (squareFrame) {
      minOf((180 * density).toInt(), availableWidth, availableHeight)
    } else {
      minOf((260 * density).toInt(), availableWidth)
    }
    val frameHeight = if (squareFrame) frameWidth else minOf((88 * density).toInt(), availableHeight)
    val params = binding.scanFrame.layoutParams
    if (params.width != frameWidth || params.height != frameHeight) {
      params.width = frameWidth
      params.height = frameHeight
      binding.scanFrame.layoutParams = params
    }
  }

  private fun render(state: RecorderState) {
    val firstSnapshot = snapshot == null && state.snapshot != null
    snapshot = state.snapshot
    val current = state.snapshot
    val ready = current != null && !state.working && state.pendingScan == null
    renderReview(state)
    binding.scanButton.isEnabled = ready
    binding.addButton.isEnabled = ready
    binding.batchButton.isEnabled = ready
    binding.duplicatesSwitch.isEnabled = ready
    binding.records.isEnabled = ready
    binding.barcodeFrame.isEnabled = ready
    binding.qrFrame.isEnabled = ready
    val hasRecords = current?.records?.isNotEmpty() == true
    binding.exportButton.isEnabled = ready && hasRecords
    binding.logButton.isEnabled = ready && hasRecords
    binding.clearButton.isEnabled = ready && hasRecords
    if (current != null) {
      if (firstSnapshot && !scanRequested && state.pendingScan == null) binding.status.setText(R.string.paused)
      binding.batchName.text = getString(R.string.batch, current.batch.name)
      binding.counts.text = getString(R.string.counts, current.records.size, current.distinctCount)
      rendering = true
      binding.duplicatesSwitch.isChecked = current.batch.allowDuplicates
      rendering = false
      adapter.submit(current.records)
      if (ready) startCameraIfReady()
    }
    cameraUi.setActive(cameraActive && foreground && ready)
  }

  private fun renderReview(state: RecorderState) {
    binding.scanner.setResultDeliveryEnabled(state.pendingScan == null)
    val hadReview = review.isShowing
    review.render(state)
    if (state.pendingScan != null) binding.status.setText(R.string.awaiting_confirmation)
    if (state.pendingScan == null && hadReview) {
      binding.status.setText(if (cameraActive && scanRequested) R.string.scanning else R.string.paused)
    }
  }

  private fun hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

  private fun startCameraIfReady() {
    val canStart = foreground && scanRequested && !cameraActive
    if (!canStart || snapshot == null) return
    if (!hasCameraPermission()) {
      pauseScanning()
      return
    }
    cameraActive = true
    cameraUi.setActive(model.state.value?.let { !it.working && it.pendingScan == null } == true)
    binding.scanButton.setText(R.string.pause_scan)
    binding.status.setText(R.string.camera_starting)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    binding.scanner.start(
      this,
      onReady = { flashAvailable ->
        binding.status.setText(
          if (model.state.value?.pendingScan != null) R.string.awaiting_confirmation else R.string.scanning
        )
        binding.torchButton.isEnabled = flashAvailable
      },
      onScan = { value ->
        if (scanRequested && foreground) model.offerScan(value)
      },
      onError = { exception ->
        pauseScanning()
        binding.status.text = getString(
          R.string.camera_failed,
          exception.localizedMessage ?: exception.javaClass.simpleName
        )
      }
    )
  }

  private fun pauseScanning() {
    scanRequested = false
    stopCamera()
    binding.status.setText(R.string.paused)
  }

  private fun stopCamera() {
    cameraUi.setActive(false)
    binding.scanner.stop()
    cameraActive = false
    binding.torchButton.isEnabled = false
    binding.torchButton.isChecked = false
    binding.scanButton.setText(R.string.start_scan)
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  private fun editRecord(record: ScanRecord?) {
    val current = snapshot ?: return
    pauseScanning()
    val content = DialogTextInputBinding.inflate(layoutInflater)
    val input = content.dialogInput.apply {
      hint = getString(R.string.record_content)
      inputType =
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
      minLines = 2
      maxLines = 6
      setText(record?.value.orEmpty())
      setSelection(requireNotNull(text).length)
    }
    val builder = MaterialAlertDialogBuilder(this)
      .setTitle(if (record == null) R.string.add_record else R.string.edit_record)
      .setMessage(R.string.record_hint)
      .setView(content.root)
      .setNegativeButton(R.string.cancel, null)
      .setPositiveButton(R.string.save, null)
    if (record != null) builder.setNeutralButton(R.string.delete) { _, _ -> model.delete(current.batch.id, record.id) }
    val dialog = builder.create()
    dialog.setOnShowListener {
      dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val value = requireNotNull(input.text).toString()
        when {
          value.isEmpty() -> input.error = getString(R.string.empty_content)
          !RecordRules.canStore(current.records, value, current.batch.allowDuplicates, record?.id) ->
            input.error = getString(R.string.duplicate_record)
          else -> {
            model.save(current.batch.id, value, record?.id)
            dialog.dismiss()
          }
        }
      }
    }
    dialog.show()
  }

  private fun chooseBatch() {
    val current = snapshot ?: return
    pauseScanning()
    BatchDialogs(this, model) { snackbar?.dismiss() }.show(current)
  }

  private fun clearBatch() {
    val current = snapshot ?: return
    pauseScanning()
    MaterialAlertDialogBuilder(this).setTitle(R.string.clear_batch)
      .setMessage(getString(R.string.clear_confirm, current.batch.name, current.records.size))
      .setNegativeButton(R.string.cancel, null)
      .setPositiveButton(R.string.clear_batch) { _, _ -> model.clear(current.batch.id) }.show()
  }

  private fun exportLog() {
    pauseScanning()
    MaterialAlertDialogBuilder(this).setTitle(R.string.log_export_title).setMessage(R.string.log_export_instructions)
      .setNegativeButton(R.string.cancel, null)
      .setPositiveButton(R.string.print_records) { _, _ -> model.exportLog() }.show()
  }

  private fun exportTxt() {
    pauseScanning()
    val specialContent = snapshot?.records?.any { record ->
      record.value.any { it == '\n' || it == '\r' || it == '\\' }
    } == true
    if (specialContent) {
      MaterialAlertDialogBuilder(this).setTitle(R.string.export_txt).setMessage(R.string.export_description)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.save) { _, _ -> model.prepareTxtExport() }.show()
    } else {
        model.prepareTxtExport()
    }
  }

  private fun handleEvent(event: RecorderEvent) {
    when (event) {
      is RecorderEvent.Saved -> {
        binding.status.text = getString(R.string.saved_record, event.count)
        if (event.scanned) {
          binding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
          binding.records.smoothScrollToPosition(event.count - 1)
          notice(getString(R.string.saved_record, event.count))
        }
      }
      RecorderEvent.Duplicate -> binding.status.setText(R.string.duplicate_record)
      is RecorderEvent.Deleted -> {
        snackbar?.dismiss()
        snackbar = Snackbar.make(binding.root, R.string.record_deleted, Snackbar.LENGTH_LONG)
          .setAction(R.string.undo) { model.restore(event.record) }.also { it.show() }
      }
      is RecorderEvent.Notice -> notice(getString(event.resource))
      is RecorderEvent.Failure -> {
        if (model.state.value?.pendingScan != null) {
          review.showFailure(event.detail)
        } else {
          pauseScanning()
          notice(getString(event.resource, event.detail))
        }
      }
      is RecorderEvent.SaveDocument -> {
        try {
          createDocument.launch(event.filename)
        } catch (_: ActivityNotFoundException) {
          model.finishTxtExport(null)
          notice(getString(R.string.export_unavailable))
        }
      }
      is RecorderEvent.Exported -> notice(
        getString(if (event.log) R.string.log_export_done else R.string.export_done, event.count)
      )
    }
  }

  private fun notice(text: String) {
    snackbar?.dismiss()
    snackbar = Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).also { it.show() }
  }

  override fun onStart() {
    super.onStart()
    foreground = true
    if (snapshot != null) {
      binding.status.setText(R.string.paused)
      startCameraIfReady()
    }
  }

  override fun onStop() {
    foreground = false
    stopCamera()
    super.onStop()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putBoolean("scanning", scanRequested)
    outState.putBoolean("square_frame", squareFrame)
    cameraUi.saveState(outState)
    super.onSaveInstanceState(outState)
  }

  override fun onDestroy() {
    review.dismiss()
    binding.scanner.removeCallbacks(resizeFrame)
    binding.scanner.release()
    super.onDestroy()
  }
}