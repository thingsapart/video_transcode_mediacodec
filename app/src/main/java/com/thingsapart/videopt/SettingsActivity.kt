package com.thingsapart.videopt

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity // Changed from android.app.Activity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings) // Contains the FrameLayout

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        // Add a toolbar/action bar if desired, and handle up navigation
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.popBackStackImmediate()) {
            return true
        }
        finish() // Or navigate up if part of a larger task stack
        return true
    }
}
