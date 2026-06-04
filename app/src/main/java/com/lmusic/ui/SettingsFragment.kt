package com.lmusic.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.lmusic.R
import com.lmusic.data.BackupManager
import com.lmusic.data.DownloadDatabase
import com.lmusic.util.CrashLogger
import com.lmusic.worker.BatchRestoreWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : PreferenceFragmentCompat() {

    // Backup import: open a .lmusicbackup file
    private val openBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        handleImport(uri)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("pref_view_crash_log")?.setOnPreferenceClickListener {
            showCrashLog(); true
        }
        findPreference<Preference>("pref_clear_crash_log")?.setOnPreferenceClickListener {
            CrashLogger.clear(requireContext())
            Toast.makeText(requireContext(), "Crash log cleared", Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<Preference>("pref_plugins")?.setOnPreferenceClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_up, R.anim.fade_out,
                                     R.anim.fade_in, R.anim.slide_down)
                .replace(R.id.fragment_container, PluginsFragment())
                .addToBackStack("plugins")
                .commit()
            true
        }

        findPreference<Preference>("pref_backup_export")?.setOnPreferenceClickListener {
            exportBackup(); true
        }
        findPreference<Preference>("pref_backup_import")?.setOnPreferenceClickListener {
            openBackupLauncher.launch(arrayOf("*/*"))
            true
        }
    }

    // -------------------------------------------------------------------------
    // Backup export
    // -------------------------------------------------------------------------

    private fun exportBackup() {
        lifecycleScope.launch {
            val ctx   = requireContext().applicationContext
            val dao   = DownloadDatabase.get(ctx).downloadDao()
            val records = withContext(Dispatchers.IO) { dao.getAll() }

            if (records.isEmpty()) {
                Toast.makeText(requireContext(), "No downloads to back up yet.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val uri = withContext(Dispatchers.IO) { BackupManager.export(ctx, records) }

            // Share the file so the user can save it wherever they want (Drive, Files, etc.)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Lmusic library backup")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Save backup to…"))
        }
    }

    // -------------------------------------------------------------------------
    // Backup import / restore
    // -------------------------------------------------------------------------

    private fun handleImport(uri: android.net.Uri) {
        lifecycleScope.launch {
            val ctx = requireContext().applicationContext

            val entries = withContext(Dispatchers.IO) {
                BackupManager.parseBackup(ctx, uri)
            }

            if (entries == null) {
                AlertDialog.Builder(requireContext(), R.style.LmusicDialog)
                    .setTitle("Invalid backup file")
                    .setMessage("The selected file could not be read. Make sure it is a valid Lmusic backup (.lmusicbackup).")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            if (entries.isEmpty()) {
                Toast.makeText(requireContext(), "Backup file contains no tracks.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Count how many are already present so the dialog is informative
            val dao = DownloadDatabase.get(ctx).downloadDao()
            val alreadyHave = withContext(Dispatchers.IO) {
                entries.count { dao.countByUrl(it.youtubeUrl) > 0 }
            }
            val toDownload = entries.size - alreadyHave

            val msg = buildString {
                append("Found ${entries.size} track${if (entries.size != 1) "s" else ""} in backup.\n\n")
                if (alreadyHave > 0) append("$alreadyHave already in your library — will be skipped.\n")
                append("$toDownload will be downloaded.")
                if (toDownload > 5) append("\n\nThis may take a while. Progress is shown in the notification shade.")
            }

            AlertDialog.Builder(requireContext(), R.style.LmusicDialog)
                .setTitle("Restore library?")
                .setMessage(msg)
                .setPositiveButton("Start restore") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { BackupManager.writeRestoreQueue(ctx, entries) }
                        BatchRestoreWorker.enqueue(ctx)
                        Toast.makeText(
                            requireContext(),
                            "Restore started — watch the notification for progress.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showCrashLog() {
        val log = CrashLogger.read(requireContext())
        if (log.isNullOrBlank()) {
            AlertDialog.Builder(requireContext(), R.style.LmusicDialog)
                .setTitle("Crash log")
                .setMessage("No errors recorded.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(requireContext(), R.style.LmusicDialog)
            .setTitle("Crash log")
            .setMessage(log)
            .setPositiveButton("Copy") { _, _ ->
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Lmusic crash log", log))
                Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

}
