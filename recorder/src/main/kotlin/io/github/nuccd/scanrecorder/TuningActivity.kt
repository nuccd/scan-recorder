package io.github.nuccd.scanrecorder

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.g00fy2.quickie.ScanTuningOptions
import io.github.nuccd.scanrecorder.databinding.ActivityTuningBinding

/** Draft edits are applied explicitly, and remain local when the user leaves this screen. */
internal class TuningActivity : AppCompatActivity() {
  private lateinit var form: TuningForm

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val binding = ActivityTuningBinding.inflate(layoutInflater)
    setContentView(binding.root)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
      val padding = (16 * resources.displayMetrics.density).toInt()
      view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
      insets
    }
    val store = TuningSettingsStore(this)
    form = TuningForm(binding)
    form.populate(store.load())
    savedInstanceState?.getBundle("tuning_draft")?.let(form::restoreDraft)
    binding.tuningCancel.setOnClickListener { finish() }
    binding.tuningReset.setOnClickListener {
      form.populate(ScanTuningOptions())
      binding.tuningNotice.setText(R.string.tuning_baseline_draft)
    }
    binding.tuningApply.setOnClickListener {
      form.read()?.let { options ->
        store.save(options)
        setResult(RESULT_OK)
        finish()
      }
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    if (::form.isInitialized) outState.putBundle("tuning_draft", form.saveDraft())
    super.onSaveInstanceState(outState)
  }
}