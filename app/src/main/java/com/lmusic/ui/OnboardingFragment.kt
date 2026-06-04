package com.lmusic.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.fragment.app.Fragment
import com.lmusic.data.Settings
import com.lmusic.databinding.FragmentOnboardingBinding

class OnboardingFragment : Fragment() {

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!
    private val animators = mutableListOf<ValueAnimator>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        startPulse()
        binding.btnGetStarted.setOnClickListener { onFinishOnboarding() }
    }

    private fun startPulse() {
        // Pulse ring: scale up + fade out, loop
        val scaleX = ObjectAnimator.ofFloat(binding.ivPulse, "scaleX", 0.85f, 1.15f)
        val scaleY = ObjectAnimator.ofFloat(binding.ivPulse, "scaleY", 0.85f, 1.15f)
        val alpha = ObjectAnimator.ofFloat(binding.ivPulse, "alpha", 0.4f, 0f)
        listOf(scaleX, scaleY, alpha).forEach {
            it.duration = 1800
            it.repeatCount = ValueAnimator.INFINITE
            it.interpolator = AccelerateDecelerateInterpolator()
            it.start()
            animators += it
        }

        // Subtle phone illustration scale bob
        val bob = ObjectAnimator.ofFloat(binding.ivIllustration, "scaleY", 1.0f, 1.02f)
        bob.duration = 1200
        bob.repeatCount = ValueAnimator.INFINITE
        bob.repeatMode = ValueAnimator.REVERSE
        bob.interpolator = AccelerateDecelerateInterpolator()
        bob.start()
        animators += bob
    }

    private fun onFinishOnboarding() {
        Settings(requireContext()).hasSeenOnboarding = true
        requireActivity().recreate()  // simplest way to re-route to wizard / history
    }

    override fun onDestroyView() {
        animators.forEach { it.cancel() }
        animators.clear()
        super.onDestroyView()
        _binding = null
    }
}
