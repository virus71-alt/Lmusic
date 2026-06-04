package com.lmusic.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lmusic.databinding.BottomSheetSleepTimerBinding
import com.lmusic.player.SleepTimerManager

/**
 * Doodle-styled sleep-timer picker.
 * Replaces the system AlertDialog with a warm bottom sheet that matches the
 * rest of the app's palette.
 */
class SleepTimerBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSleepTimerBinding? = null
    private val binding get() = _binding!!

    /** Optional callback fired after the timer is set / cancelled. */
    var onTimerChanged: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSleepTimerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (SleepTimerManager.isActive) {
            binding.tvSleepSubtitle.text =
                "Stopping in ${formatTime(SleepTimerManager.remainingMs)}. Pick a new duration:"
        }

        binding.option15.setOnClickListener { pick(15) }
        binding.option30.setOnClickListener { pick(30) }
        binding.option45.setOnClickListener { pick(45) }
        binding.option60.setOnClickListener { pick(60) }
        binding.option90.setOnClickListener { pick(90) }

        binding.btnSleepCancel.text =
            if (SleepTimerManager.isActive) "Cancel current timer" else "Close"
        binding.btnSleepCancel.setOnClickListener {
            if (SleepTimerManager.isActive) {
                SleepTimerManager.cancel()
                onTimerChanged?.invoke()
            }
            dismiss()
        }
    }

    private fun pick(minutes: Int) {
        SleepTimerManager.schedule(minutes * 60_000L)
        onTimerChanged?.invoke()
        dismiss()
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000L
        val m = total / 60
        val s = total % 60
        return String.format(java.util.Locale.getDefault(), "%d:%02d", m, s)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
