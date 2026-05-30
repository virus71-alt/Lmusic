package com.lmusic.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lmusic.data.DownloadDatabase
import com.lmusic.data.DownloadRecord
import com.lmusic.databinding.FragmentDownloadHistoryBinding
import com.lmusic.databinding.ItemDownloadBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadHistoryFragment : Fragment() {

    private var _binding: FragmentDownloadHistoryBinding? = null
    private val binding get() = _binding!!

    private val adapter = DownloadAdapter { record -> deleteRecord(record) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDownloadHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        // Swipe-to-delete
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                deleteRecord(adapter.getItem(vh.adapterPosition))
            }
        }).attachToRecyclerView(binding.recyclerView)

        viewLifecycleOwner.lifecycleScope.launch {
            DownloadDatabase.get(requireContext()).downloadDao().getAllFlow().collectLatest { list ->
                adapter.submitList(list)
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun deleteRecord(record: DownloadRecord) {
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadDatabase.get(requireContext()).downloadDao().delete(record)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class DownloadAdapter(
    private val onDelete: (DownloadRecord) -> Unit
) : RecyclerView.Adapter<DownloadAdapter.VH>() {

    private var items: List<DownloadRecord> = emptyList()
    private val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    fun submitList(list: List<DownloadRecord>) {
        items = list
        notifyDataSetChanged()
    }

    fun getItem(position: Int) = items[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val record = items[position]
        holder.binding.tvTitle.text = record.title
        holder.binding.tvDate.text = fmt.format(Date(record.downloadedAt))
        holder.binding.tvUrl.text = record.youtubeUrl
        holder.itemView.setOnLongClickListener { onDelete(record); true }
    }

    override fun getItemCount() = items.size

    class VH(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)
}
