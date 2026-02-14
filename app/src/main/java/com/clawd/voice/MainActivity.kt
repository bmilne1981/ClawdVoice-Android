package com.clawd.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.util.Log
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
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
    private var isFinishing = false
    private var wakeWordTriggered = false
    
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
        
        // Handle wake word trigger from service
        handleWakeWordIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleWakeWordIntent(it) }
    }
    
    private fun handleWakeWordIntent(intent: Intent) {
        if (intent.getBooleanExtra("wake_word_triggered", false)) {
            intent.removeExtra("wake_word_triggered")
            wakeWordTriggered = true
            
            // Haptic feedback
            vibrateShort()
            
            // Auto-start listening
            binding.root.post {
                startListening()
            }
        }
    }
    
    private fun vibrateShort() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(100)
                }
            }
        } catch (_: Exception) {}
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
            
            override fun onRmsChanged(rmsdB: Float) {}
            
            override fun onBufferReceived(buffer: ByteArray?) {}
            
            override fun onEndOfSpeech() {
                updateStatus("Processing...")
                binding.micButton.setBackgroundResource(R.drawable.mic_button_processing)
            }
            
            override fun onError(error: Int) {
                if (isFinishing) {
                    isListening = false
                    isFinishing = false
                    resetUI()
                    resumeWakeWordIfNeeded()
                    return
                }
                
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
                resumeWakeWordIfNeeded()
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
                    resumeWakeWordIfNeeded()
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
        
        audioPlayer.stop()
        
        // Pause wake word service to release the microphone
        if (settings.isWakeWordEnabled()) {
            WakeWordService.stop(this)
        }
        
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
            
            // If triggered by wake word, auto-stop after trailing delay
            if (wakeWordTriggered) {
                wakeWordTriggered = false
                // Let speech recognizer handle end-of-speech naturally
                // It has its own silence detection; we add a trailing delay
                binding.root.postDelayed({
                    if (isListening && !isFinishing) {
                        stopListening()
                    }
                }, 10000) // 10s max listen time for wake word trigger
            }
        } catch (e: Exception) {
            updateStatus("Error starting speech recognition")
            isListening = false
            resumeWakeWordIfNeeded()
        }
    }
    
    private fun stopListening() {
        if (!isListening) return
        
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
                                    resumeWakeWordIfNeeded()
                                }
                            }
                        } else {
                            updateStatus("Ready")
                            resetUI()
                            resumeWakeWordIfNeeded()
                        }
                    }
                    is ApiResponse.Error -> {
                        updateStatus("Error: ${response.message}")
                        resetUI()
                        resumeWakeWordIfNeeded()
                    }
                }
            } catch (e: Exception) {
                updateStatus("Error: ${e.message}")
                resetUI()
                resumeWakeWordIfNeeded()
            }
        }
    }
    
    private fun resumeWakeWordIfNeeded() {
        if (settings.isWakeWordEnabled()) {
            // Delay restart to ensure the service has fully stopped
            // and the microphone is released
            binding.root.postDelayed({
                WakeWordService.start(this)
                Log.d("MainActivity", "Wake word service restarted")
            }, 500)
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
        val permissions = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions required for full functionality", Toast.LENGTH_LONG).show()
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
        private const val PERMISSION_REQUEST_CODE = 1
    }
}
