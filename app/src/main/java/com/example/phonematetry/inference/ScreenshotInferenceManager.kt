package com.example.phonematetry.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.phonematetry.data.GEMMA3N_E2B_MODEL
import com.example.phonematetry.data.Model
import com.example.phonematetry.inference.TTSManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ScreenshotInferenceManager"

class ScreenshotInferenceManager(private val context: Context) {
    
    // 固定的prompt
    private val FIXED_PROMPT = "用户现在想要在手机里的微信APP中进行线上门诊预约，请根据你刚刚看到的截图，输出用户需要做的动作，比如点击某个按钮。请确保你的输出简洁。"
    
    private val model: Model = GEMMA3N_E2B_MODEL
    private val ttsManager = TTSManager(context)
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    
    private var isModelInitialized = false
    private var isInferenceInProgress = false
    private var currentInferenceJob: Job? = null
    
    interface ScreenshotInferenceListener {
        fun onModelInitialized()
        fun onInferenceStart()
        fun onInferenceProgress(partialText: String)
        fun onInferenceComplete(fullText: String)
        fun onInferenceError(error: String)
        fun onTTSStart()
        fun onTTSComplete()
        fun onTTSError(error: String)
    }
    
    private var listener: ScreenshotInferenceListener? = null
    
    fun initialize(listener: ScreenshotInferenceListener? = null) {
        this.listener = listener
        
        // 初始化TTS
        ttsManager.initialize(object : TTSManager.TTSListener {
            override fun onTTSStart() {
                listener?.onTTSStart()
            }
            
            override fun onTTSComplete() {
                listener?.onTTSComplete()
            }
            
            override fun onTTSError(error: String) {
                listener?.onTTSError(error)
            }
        })
        
        // 初始化模型
        initializeModel()
    }
    
    private fun initializeModel() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Initializing model...")
                LlmInferenceManager.initialize(context, model) { error ->
                    coroutineScope.launch(Dispatchers.Main) {
                        if (error.isEmpty()) {
                            isModelInitialized = true
                            Log.d(TAG, "Model initialized successfully")
                            listener?.onModelInitialized()
                        } else {
                            Log.e(TAG, "Model initialization failed: $error")
                            listener?.onInferenceError("模型初始化失败: $error")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during model initialization", e)
                withContext(Dispatchers.Main) {
                    listener?.onInferenceError("模型初始化异常: ${e.message}")
                }
            }
        }
    }
    
    fun processScreenshot(screenshot: Bitmap) {
        if (isInferenceInProgress) {
            Log.w(TAG, "Inference already in progress, ignoring new screenshot")
            return
        }
        
        if (!isModelInitialized) {
            listener?.onInferenceError("模型尚未初始化完成")
            return
        }
        
        currentInferenceJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                isInferenceInProgress = true
                
                withContext(Dispatchers.Main) {
                    listener?.onInferenceStart()
                }
                
                // 等待模型实例准备就绪
                while (model.instance == null) {
                    delay(100)
                }
                
                // 重置会话
                LlmInferenceManager.resetSession(model)
                delay(500)
                
                var fullResponse = ""
                var lastTTSText = ""
                
                LlmInferenceManager.runInference(
                    model = model,
                    prompt = FIXED_PROMPT,
                    image = screenshot,
                    resultListener = { partialResult, done ->
                        fullResponse += partialResult
                        
                        // 更新UI
                        coroutineScope.launch(Dispatchers.Main) {
                            listener?.onInferenceProgress(fullResponse)
                        }
                        
                        // 流式TTS：当累积足够的文本时进行语音播放
                        if (shouldPlayTTS(fullResponse, lastTTSText)) {
                            val newText = fullResponse.substring(lastTTSText.length)
                            ttsManager.speak(newText)
                            lastTTSText = fullResponse
                        }
                        
                        if (done) {
                            // 播放剩余的文本
                            if (fullResponse.length > lastTTSText.length) {
                                val remainingText = fullResponse.substring(lastTTSText.length)
                                ttsManager.speak(remainingText)
                            }
                            
                            coroutineScope.launch(Dispatchers.Main) {
                                listener?.onInferenceComplete(fullResponse)
                            }
                            
                            isInferenceInProgress = false
                        }
                    },
                    cleanUpListener = {
                        isInferenceInProgress = false
                    }
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during inference", e)
                isInferenceInProgress = false
                withContext(Dispatchers.Main) {
                    listener?.onInferenceError("推理过程出错: ${e.message}")
                }
            }
        }
    }
    
    private fun shouldPlayTTS(fullText: String, lastPlayedText: String): Boolean {
        val newTextLength = fullText.length - lastPlayedText.length
        
        // 当新增文本达到一定长度时播放，或者遇到句号、问号、感叹号时播放
        return newTextLength >= 10 || 
               (newTextLength > 0 && (fullText.endsWith("。") || fullText.endsWith("？") || fullText.endsWith("！")))
    }
    
    fun stopInference() {
        currentInferenceJob?.cancel()
        LlmInferenceManager.stopInference(model)
        ttsManager.stop()
        isInferenceInProgress = false
    }
    
    fun stopTTS() {
        ttsManager.stop()
    }
    
    fun isTTSSpeaking(): Boolean {
        return ttsManager.isSpeaking()
    }
    
    fun isInferenceInProgress(): Boolean {
        return isInferenceInProgress
    }
    
    fun destroy() {
        stopInference()
        LlmInferenceManager.cleanUp(model)
        ttsManager.destroy()
    }
}