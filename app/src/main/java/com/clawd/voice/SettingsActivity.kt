package com.clawd.voice

import android.os.Bundle
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
        
        binding.saveButton.setOnClickListener {
            saveSettings()
        }
        
        binding.testButton.setOnClickListener {
            testConnection()
        }
    }
    
    private fun saveSettings() {
        val url = binding.serverUrlInput.text.toString().trim()
        
        if (url.isEmpty()) {
            Toast.makeText(this, "Server URL cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            settings.setServerUrl(url)
            Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
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
                binding.testButton.text = "Test Connection"
                
                when (response) {
                    is ApiResponse.Success -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            "✓ Connection successful!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is ApiResponse.Error -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            "✗ Error: ${response.message}",
                            Toast.LENGTH_LONG
                        ).show()
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
