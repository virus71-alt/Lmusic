package com.lmusic.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.lmusic.R
import com.lmusic.databinding.FragmentPermissionWizardBinding

class PermissionWizardFragment : Fragment() {

    private var _binding: FragmentPermissionWizardBinding? = null
    private val binding get() = _binding!!

    companion object {
        fun allPermissionsGranted(ctx: Context): Boolean {
            return isNotificationListenerEnabled(ctx)
                    && isAccessibilityEnabled(ctx)
                    && isStorageGranted(ctx)
                    && isPostNotificationsGranted(ctx)
        }

        fun isNotificationListenerEnabled(ctx: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

        fun isAccessibilityEnabled(ctx: Context): Boolean {
            val component = ComponentName(
                ctx, com.lmusic.service.LmusicAccessibilityService::class.java
            ).flattenToString()
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains(component, ignoreCase = true)
        }

        fun isStorageGranted(ctx: Context): Boolean = when {
            // Android 13+: need READ_MEDIA_AUDIO to scan the music library.
            // Writes go through MediaStore (no permission needed).
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.READ_MEDIA_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            // Android 10–12: no permission required for app-scoped MediaStore writes.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> true
            // Android 9 and below: legacy storage permission.
            else -> ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun isPostNotificationsGranted(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPermissionWizardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnNotificationAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnStorage.setOnClickListener {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    ActivityCompat.requestPermissions(
                        requireActivity(),
                        arrayOf(Manifest.permission.READ_MEDIA_AUDIO), 100
                    )
                }
                else -> {
                    ActivityCompat.requestPermissions(
                        requireActivity(),
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100
                    )
                }
            }
        }
        binding.btnNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val ctx = requireContext()
        val notif = isNotificationListenerEnabled(ctx)
        val a11y = isAccessibilityEnabled(ctx)
        val storage = isStorageGranted(ctx)
        val postNotif = isPostNotificationsGranted(ctx)

        val count = listOf(notif, a11y, storage, postNotif).count { it }
        binding.tvProgressCount.text = "$count / 4"
        binding.tvProgress.text = when (count) {
            0 -> "Grant permissions to begin"
            4 -> "All set! Opening app…"
            else -> "${4 - count} more to go"
        }

        applyStep(binding.ivStep1, binding.btnNotificationAccess, notif)
        applyStep(binding.ivStep2, binding.btnAccessibility, a11y)
        applyStep(binding.ivStep3, binding.btnStorage, storage)
        applyStep(binding.ivStep4, binding.btnNotifications, postNotif)
    }

    private fun applyStep(icon: ImageView, button: View, granted: Boolean) {
        if (granted) {
            icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success))
            icon.alpha = 1f
            button.isVisible = false
        } else {
            icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.text_tertiary))
            icon.alpha = 0.6f
            button.isVisible = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
