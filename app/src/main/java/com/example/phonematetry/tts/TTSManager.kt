package com.example.phonematetry.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "TTSManager"

class TTSManager(private val context: Context) {
    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false
    private val utteranceIdCounter = AtomicInteger(0)
    private val pendingTexts = mutableListOf<String>()
    
    interface TTSListener {
        fun onTTSStart()
        fun onTTSComplete()
        fun onTTSError(error: String)
    }
    
    private var ttsListener: TTSListener? = null
    
    fun initialize(listener: TTSListener? = null) {
        this.ttsListener = listener
        
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Chinese language is not supported")
                    ttsListener?.onTTSError("不支持中文语音")
                } else {
                    isInitialized = true
                    Log.d(TAG, "TTS initialized successfully")
                    
                    // 设置语音参数
                    textToSpeech?.setSpeechRate(1.0f) // 正常语速
                    textToSpeech?.setPitch(1.0f) // 正常音调
                    
                    // 设置进度监听器
                    textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.d(TAG, "TTS started for utterance: $utteranceId")
                            ttsListener?.onTTSStart()
                        }
                        
                        override fun onDone(utteranceId: String?) {
                            Log.d(TAG, "TTS completed for utterance: $utteranceId")
                            ttsListener?.onTTSComplete()
                        }
                        
                        override fun onError(utteranceId: String?) {
                            Log.e(TAG, "TTS error for utterance: $utteranceId")
                            ttsListener?.onTTSError("语音播放出错")
                        }
                    })
                    
                    // 处理待播放的文本
                    processPendingTexts()
                }
            } else {
                Log.e(TAG, "TTS initialization failed")
                ttsListener?.onTTSError("语音初始化失败")
            }
        }
    }
    
    fun speak(text: String) {
        if (!isInitialized) {
            // 如果TTS还未初始化，将文本加入待播放队列
            pendingTexts.add(text)
            return
        }
        
        if (text.trim().isEmpty()) {
            return
        }
        
        val utteranceId = "utterance_${utteranceIdCounter.incrementAndGet()}"
        val result = textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "Failed to speak text: $text")
            ttsListener?.onTTSError("语音播放失败")
        } else {
            Log.d(TAG, "Speaking text: $text")
        }
    }
    
    fun speakImmediately(text: String) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot speak immediately")
            return
        }
        
        if (text.trim().isEmpty()) {
            return
        }
        
        // 停止当前播放，立即播放新文本
        stop()
        
        val utteranceId = "utterance_immediate_${utteranceIdCounter.incrementAndGet()}"
        val result = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "Failed to speak text immediately: $text")
            ttsListener?.onTTSError("语音播放失败")
        } else {
            Log.d(TAG, "Speaking text immediately: $text")
        }
    }
    
    fun stop() {
        if (isSpeaking()) {
            textToSpeech?.stop()
            // 手动触发完成回调，因为stop()不会自动触发onDone
            ttsListener?.onTTSComplete()
        }
    }
    
    fun isSpeaking(): Boolean {
        return textToSpeech?.isSpeaking ?: false
    }
    
    fun setSpeechRate(rate: Float) {
        textToSpeech?.setSpeechRate(rate)
    }
    
    fun setPitch(pitch: Float) {
        textToSpeech?.setPitch(pitch)
    }
    
    private fun processPendingTexts() {
        if (pendingTexts.isNotEmpty()) {
            val textsToSpeak = pendingTexts.toList()
            pendingTexts.clear()
            
            for (text in textsToSpeak) {
                speak(text)
            }
        }
    }
    
    fun destroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isInitialized = false
        pendingTexts.clear()
        Log.d(TAG, "TTS destroyed")
    }
}