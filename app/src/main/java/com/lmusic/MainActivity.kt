package com.lmusic

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lmusic.databinding.ActivityMainBinding
import com.lmusic.ui.DownloadHistoryFragment
import com.lmusic.ui.PermissionWizardFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            showCorrectFragment()
        }
    }

    override fun onResume() {
        super.onResume()
        showCorrectFragment()
    }

    private fun showCorrectFragment() {
        val allGranted = PermissionWizardFragment.allPermissionsGranted(this)
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)

        if (allGranted && currentFragment !is DownloadHistoryFragment) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DownloadHistoryFragment())
                .commit()
        } else if (!allGranted && currentFragment !is PermissionWizardFragment) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, PermissionWizardFragment())
                .commit()
        }
    }
}
