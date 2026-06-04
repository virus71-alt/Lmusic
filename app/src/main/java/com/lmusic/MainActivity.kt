package com.lmusic

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lmusic.data.Settings
import com.lmusic.databinding.ActivityMainBinding
import com.lmusic.ui.MainNavigationFragment
import com.lmusic.ui.OnboardingFragment
import com.lmusic.ui.PermissionWizardFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()   // must come before super.onCreate()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (savedInstanceState == null) showCorrectFragment()
    }

    override fun onResume() {
        super.onResume()
        showCorrectFragment()
    }

    private fun showCorrectFragment() {
        val s = Settings(this)
        val seenOnboarding = s.hasSeenOnboarding
        val allGranted = PermissionWizardFragment.allPermissionsGranted(this)
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)

        when {
            !seenOnboarding && current !is OnboardingFragment -> swap(OnboardingFragment())
            seenOnboarding && !allGranted && current !is PermissionWizardFragment ->
                swap(PermissionWizardFragment())
            seenOnboarding && allGranted &&
                    (current == null || current is PermissionWizardFragment || current is OnboardingFragment) ->
                swap(MainNavigationFragment())
        }
    }

    private fun swap(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commitAllowingStateLoss()
    }
}
