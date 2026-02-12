package com.clawd.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import java.io.FileOutputStream

class AudioPlayer {
    
    private var mediaPlayer: MediaPlayer? = null
    
    fun play(audioData: ByteArray, onComplete: () -> Unit) {
        stop()
        
        try {
            // Write audio data to temp file
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
