package com.clawd.voice

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.clawd.voice.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
        
        settings = SettingsManager(this)
        
        // Load current settings
        binding.serverUrlInput.setText(settings.getServerUrl())
        binding.wakeWordSwitch.isChecked = settings.isWakeWordEnabled()
        binding.accessKeyInput.setText(settings.getPorcupineAccessKey())
        binding.sensitivitySlider.value = settings.getWakeWordSensitivity()
        
        binding.saveButton.setOnClickListener {
            saveSettings()
        }
        
        binding.testButton.setOnClickListener {
            testConnection()
        }
    }
    
    private fun saveSettings() {
        val url = binding.serverUrlInput.text.toString().trim()
        val wakeWordEnabled = binding.wakeWordSwitch.isChecked
        val accessKey = binding.accessKeyInput.text.toString().trim()
        val sensitivity = binding.sensitivitySlider.value
        
        if (url.isEmpty()) {
            Toast.makeText(this, "Server URL cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (wakeWordEnabled && accessKey.isEmpty()) {
            Toast.makeText(this, "Picovoice Access Key required for wake word", Toast.LENGTH_LONG).show()
            return
        }
        
        val wasEnabled = settings.isWakeWordEnabled()
        
        lifecycleScope.launch {
            settings.setServerUrl(url)
            settings.setWakeWordEnabled(wakeWordEnabled)
            settings.setPorcupineAccessKey(accessKey)
            settings.setWakeWordSensitivity(sensitivity)
            
            // Start or stop wake word service
            if (wakeWordEnabled && !wasEnabled) {
                requestBatteryOptimizationExemption()
                WakeWordService.start(this@SettingsActivity)
            } else if (!wakeWordEnabled && wasEnabled) {
                WakeWordService.stop(this@SettingsActivity)
            } else if (wakeWordEnabled) {
                // Restart to pick up new settings
                WakeWordService.stop(this@SettingsActivity)
                WakeWordService.start(this@SettingsActivity)
            }
            
            Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "Please disable battery optimization for ClawdVoice manually in Settings",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    private fun testConnection() {
        val url = binding.serverUrlInput.text.toString().trim()
        
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter a server URL first", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.testButton.isEnabled = false
        binding.testButton.text = "Testing..."
        
        lifecycleScope.launch {
            val client = ApiClient()
            val response = client.sendVoiceRequest(url, "test connection")
            
            runOnUiThread {
                binding.testButton.isEnabled = true
                binding.testButton.text = getString(R.string.test_connection)
                
                when (response) {
                    is ApiResponse.Success -> {
                        Toast.makeText(this@SettingsActivity, "✓ Connection successful!", Toast.LENGTH_SHORT).show()
                    }
                    is ApiResponse.Error -> {
                        Toast.makeText(this@SettingsActivity, "✗ Error: ${response.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
