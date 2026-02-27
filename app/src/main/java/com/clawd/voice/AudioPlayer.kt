package com.clawd.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import java.io.FileOutputStream

class AudioPlayer {
    
    private var mediaPlayer: MediaPlayer? = null
    private var queuedAudio: ByteArray? = null
    private var queuedOnComplete: (() -> Unit)? = null
    private var isPlayingAck = false
    
    fun play(audioData: ByteArray, onComplete: () -> Unit) {
        stop()
        playInternal(audioData, onComplete)
    }
    
    /**
     * Play an ack audio clip. If the main response arrives while the ack is playing,
     * it will be queued and played automatically after the ack finishes.
     */
    fun playAck(audioData: ByteArray, onAckComplete: () -> Unit) {
        stop()
        isPlayingAck = true
        queuedAudio = null
        queuedOnComplete = null
        
        playInternal(audioData) {
            isPlayingAck = false
            // Check if a response was queued while ack was playing
            val queued = queuedAudio
            val queuedComplete = queuedOnComplete
            queuedAudio = null
            queuedOnComplete = null
            
            if (queued != null && queuedComplete != null) {
                playInternal(queued, queuedComplete)
            } else {
                onAckComplete()
            }
        }
    }
    
    /**
     * Queue audio to play after the current ack finishes.
     * If no ack is playing, plays immediately.
     */
    fun playAfterAck(audioData: ByteArray, onComplete: () -> Unit) {
        if (isPlayingAck) {
            // Ack is still playing — queue this for after
            queuedAudio = audioData
            queuedOnComplete = onComplete
        } else {
            // No ack playing — play immediately
            play(audioData, onComplete)
        }
    }
    
    val isAckPlaying: Boolean get() = isPlayingAck
    
    private fun playInternal(audioData: ByteArray, onComplete: () -> Unit) {
        try {
            val tempFile = File.createTempFile("clawd_audio", ".mp3")
            tempFile.deleteOnExit()
            
            FileOutputStream(tempFile).use { fos ->
                fos.write(audioData)
            }
            
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener {
                    tempFile.delete()
                    onComplete()
                }
                setOnErrorListener { _, _, _ ->
                    tempFile.delete()
                    onComplete()
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            onComplete()
        }
    }
    
    fun stop() {
        isPlayingAck = false
        queuedAudio = null
        queuedOnComplete = null
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
    }
    
    fun release() {
        stop()
    }
}
