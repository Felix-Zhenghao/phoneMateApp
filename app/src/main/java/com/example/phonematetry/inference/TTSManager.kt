package com.example.phonematetry.inference

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

private const val TAG = "TTSManager"

class TTSManager(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var listener: TTSListener? = null
    
    interface TTSListener {
        fun onTTSStart()
        fun onTTSComplete()
        fun onTTSError(error: String)
    }
    
    fun initialize(listener: TTSListener, onInitComplete: ((Boolean) -> Unit)? = null) {
        this.listener = listener
        
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Chinese language is not supported")
                    isInitialized = false
                    onInitComplete?.invoke(false)
                } else {
                    isInitialized = true
                    setupUtteranceListener()
                    Log.d(TAG, "TTS initialized successfully")
                    onInitComplete?.invoke(true)
                }
            } else {
                Log.e(TAG, "TTS initialization failed")
                isInitialized = false
                onInitComplete?.invoke(false)
            }
        }
    }
    
    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started for utterance: $utteranceId")
                listener?.onTTSStart()
            }
            
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS completed for utterance: $utteranceId")
                listener?.onTTSComplete()
            }
            
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error for utterance: $utteranceId")
                listener?.onTTSError("TTS播放出错")
            }
        })
    }
    
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD): Boolean {
        if (!isInitialized || tts == null) {
            Log.e(TAG, "TTS not initialized")
            listener?.onTTSError("TTS未初始化")
            return false
        }
        
        if (text.trim().isEmpty()) {
            Log.w(TAG, "Empty text provided for TTS")
            return false
        }
        
        val utteranceId = "utterance_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        
        val result = tts?.speak(text, queueMode, params, utteranceId)
        return result == TextToSpeech.SUCCESS
    }
    
    fun speakImmediately(text: String): Boolean {
        return speak(text, TextToSpeech.QUEUE_FLUSH)
    }
    
    fun stop() {
        tts?.stop()
    }
    
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking ?: false
    }
    
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }
    
    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
    }
    
    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        listener = null
        Log.d(TAG, "TTS destroyed")
    }
}