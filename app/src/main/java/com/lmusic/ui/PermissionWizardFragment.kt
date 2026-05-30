package com.lmusic.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
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

        fun isStorageGranted(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ uses MediaStore — no user-granted permission needed
                true
            } else {
                ContextCompat.checkSelfPermission(
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                )
            } else {
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100
                )
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
        val notifListener = isNotificationListenerEnabled(ctx)
        val accessibility = isAccessibilityEnabled(ctx)
        val storage = isStorageGranted(ctx)
        val postNotif = isPostNotificationsGranted(ctx)

        val count = listOf(notifListener, accessibility, storage, postNotif).count { it }
        binding.tvProgress.text = "$count / 4 permissions granted"

        applyStepState(binding.ivStep1, binding.btnNotificationAccess, notifListener)
        applyStepState(binding.ivStep2, binding.btnAccessibility, accessibility)
        applyStepState(binding.ivStep3, binding.btnStorage, storage)
        applyStepState(binding.ivStep4, binding.btnNotifications, postNotif)
    }

    private fun applyStepState(icon: View, button: View, granted: Boolean) {
        icon.alpha = if (granted) 1f else 0.35f
        button.visibility = if (granted) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
