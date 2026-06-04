package com.lmusic.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lmusic.R
import com.lmusic.databinding.FragmentPluginsBinding
import com.lmusic.databinding.ItemPluginBinding
import com.lmusic.plugin.PluginManager
import com.lmusic.plugin.StreamPlugin

/**
 * Settings screen that lists all active stream-extraction plugins.
 *
 * The "Refresh" button rescans for newly installed / uninstalled external plugin APKs.
 *
 * ── How to install an external plugin ───────────────────────────────────────
 *
 *   1. Build or download a plugin APK.
 *   2. Install it on the device (sideload or from a trusted source).
 *   3. Tap ↺ Refresh here — the plugin appears immediately.
 *
 *   An external plugin APK must declare the following in its AndroidManifest.xml:
 *
 *     <application>
 *       <meta-data
 *           android:name="lmusic.plugin.class"
 *           android:value="com.example.myplugin.MyPlugin" />
 *     </application>
 *
 *   The declared class must implement StreamPlugin and carry copies of the
 *   com.lmusic.plugin.* data classes at the identical package path.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class PluginsFragment : Fragment() {

    private var _binding: FragmentPluginsBinding? = null
    private val binding get() = _binding!!

    private val adapter = PluginAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPluginsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        binding.btnRefresh.setOnClickListener {
            PluginManager.refresh(requireContext().applicationContext)
            adapter.submitList(PluginManager.all)
            Toast.makeText(requireContext(), "Plugins refreshed", Toast.LENGTH_SHORT).show()
        }

        binding.recyclerPlugins.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerPlugins.adapter = adapter

        adapter.submitList(PluginManager.all)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Adapter
// ─────────────────────────────────────────────────────────────────────────────

private class PluginAdapter : RecyclerView.Adapter<PluginAdapter.VH>() {

    private var items: List<StreamPlugin> = emptyList()

    fun submitList(list: List<StreamPlugin>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemPluginBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    class VH(private val b: ItemPluginBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(p: StreamPlugin) {
            b.tvPluginName.text    = p.name
            b.tvPluginVersion.text = "Version: ${p.version}"
            b.tvPluginDomains.text = "Supports: ${p.supportedDomains.joinToString(", ")}"
            b.tvPluginBadge.text   = if (p.isBuiltIn) "Built-in" else "External"
        }
    }
}
