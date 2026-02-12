package com.clawd.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.clawd.voice.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var apiClient: ApiClient
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var settings: SettingsManager
    
    private var isListening = false
    private var isFinishing = false  // Suppress errors during trailing delay
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        
        settings = SettingsManager(this)
        apiClient = ApiClient()
        audioPlayer = AudioPlayer()
        
        setupSpeechRecognizer()
        setupUI()
        checkPermissions()
    }
    
    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_LONG).show()
            return
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                updateStatus("Listening...")
                binding.micButton.setBackgroundResource(R.drawable.mic_button_active)
            }
            
            override fun onBeginningOfSpeech() {}
            
            override fun onRmsChanged(rmsdB: Float) {
                // Could animate based on volume level
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {}
            
            override fun onEndOfSpeech() {
                updateStatus("Processing...")
                binding.micButton.setBackgroundResource(R.drawable.mic_button_processing)
            }
            
            override fun onError(error: Int) {
                // Ignore errors during the trailing delay period
                if (isFinishing) return
                
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    else -> "Unknown error"
                }
                updateStatus(message)
                resetUI()
                isListening = false
            }
            
            override fun onResults(results: Bundle?) {
                isFinishing = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    binding.transcriptText.text = text
                    sendToServer(text)
                } else {
                    updateStatus("No speech recognized")
                    resetUI()
                }
                isListening = false
            }
            
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    binding.transcriptText.text = matches[0]
                }
            }
            
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }
    
    private fun setupUI() {
        binding.micButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startListening()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopListening()
                    true
                }
                else -> false
            }
        }
    }
    
    private fun startListening() {
        if (isListening) return
        
        // Stop any playing audio
        audioPlayer.stop()
        
        isListening = true
        isFinishing = false
        binding.transcriptText.text = ""
        binding.responseText.text = ""
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        
        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            updateStatus("Error starting speech recognition")
            isListening = false
        }
    }
    
    private fun stopListening() {
        if (!isListening) return
        
        // Add 2-second delay to capture trailing words
        // Keep isFinishing true until results come in (clears in onResults)
        isFinishing = true
        updateStatus("Finishing...")
        binding.root.postDelayed({
            speechRecognizer.stopListening()
        }, 2000)
    }
    
    private fun sendToServer(text: String) {
        updateStatus("Sending to Clawd...")
        
        lifecycleScope.launch {
            try {
                val serverUrl = settings.getServerUrl()
                val response = apiClient.sendVoiceRequest(serverUrl, text)
                
                when (response) {
                    is ApiResponse.Success -> {
                        binding.responseText.text = response.text
                        updateStatus("Playing response...")
                        
                        if (response.audioData != null) {
                            audioPlayer.play(response.audioData) {
                                runOnUiThread {
                                    updateStatus("Ready")
                                    resetUI()
                                }
                            }
                        } else {
                            updateStatus("Ready")
                            resetUI()
                        }
                    }
                    is ApiResponse.Error -> {
                        updateStatus("Error: ${response.message}")
                        resetUI()
                    }
                }
            } catch (e: Exception) {
                updateStatus("Error: ${e.message}")
                resetUI()
            }
        }
    }
    
    private fun updateStatus(status: String) {
        runOnUiThread {
            binding.statusText.text = status
        }
    }
    
    private fun resetUI() {
        runOnUiThread {
            binding.micButton.setBackgroundResource(R.drawable.mic_button_normal)
        }
    }
    
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_RECORD_AUDIO
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        audioPlayer.release()
    }
    
    companion object {
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 1
    }
}
