package io.github.nuccd.scanrecorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import io.github.nuccd.scanrecorder.databinding.RecordRowBinding

internal class RecordAdapter : BaseAdapter() {
  private var records = emptyList<ScanRecord>()

  fun submit(updated: List<ScanRecord>) {
    if (records == updated) return
    records = updated
    notifyDataSetChanged()
  }

  override fun getCount(): Int = records.size
  override fun getItem(position: Int): ScanRecord = records[position]
  override fun getItemId(position: Int): Long = position.toLong()

  override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
    val binding = if (convertView == null) {
        RecordRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    } else {
        RecordRowBinding.bind(convertView)
    }
    binding.sequence.text = parent.context.getString(R.string.record_sequence, position + 1)
    binding.content.text = records[position].value
    return binding.root
  }
}