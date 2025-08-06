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
    
    private val model: Model = GEMMA3N_E2B_MODEL
    private val ttsManager = TTSManager(context)
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    
    private var isModelInitialized = false
    private var isInferenceInProgress = false
    private var currentInferenceJob: Job? = null
    
    // 存储当前的截图和ASR结果
    private var currentScreenshot: Bitmap? = null
    private var currentASRResult: String? = null
    
    interface ScreenshotInferenceListener {
        fun onModelInitialized()
        fun onInferenceStart()
        fun onInferenceProgress(partialText: String)
        fun onInferenceComplete(fullText: String)
        fun onInferenceError(error: String)
        fun onTTSStart()
        fun onTTSComplete()
        fun onTTSError(error: String)
        fun onSessionResetComplete()
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
                            listener?.onInferenceError(getErrorMessage("model_init_failed") + ": $error")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during model initialization", e)
                withContext(Dispatchers.Main) {
                    listener?.onInferenceError(getErrorMessage("model_init_exception") + ": ${e.message}")
                }
            }
        }
    }
    
    // 保存截图，等待ASR结果
    fun saveScreenshot(screenshot: Bitmap) {
        currentScreenshot = screenshot
        Log.d(TAG, "Screenshot saved, waiting for ASR result")
    }
    
    // 处理ASR结果并开始推理
    fun processWithASRResult(asrResult: String) {
        currentASRResult = asrResult
        
        // 确保TTS使用正确的语言
        ttsManager.updateLanguage()
        
        val screenshot = currentScreenshot
        if (screenshot == null) {
            listener?.onInferenceError(getErrorMessage("no_screenshot"))
            return
        }
        
        if (isInferenceInProgress) {
            Log.w(TAG, "Inference already in progress, ignoring new request")
            return
        }
        
        if (!isModelInitialized) {
            listener?.onInferenceError(getErrorMessage("model_not_initialized"))
            return
        }
        
        // 构造动态prompt
        val dynamicPrompt = getDynamicPrompt(asrResult)
        
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
                
                // 安全地重置会话，确保每次都是新的对话
                try {
                    LlmInferenceManager.resetSession(model)
                    delay(200) // 减少延迟时间
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to reset session before inference, continuing anyway", e)
                }
                
                var fullResponse = ""
                var lastTTSText = ""
                
                LlmInferenceManager.runInference(
                    model = model,
                    prompt = dynamicPrompt,
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
                            
                            // 清理当前的截图和ASR结果
                            currentScreenshot = null
                            currentASRResult = null
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
                    listener?.onInferenceError(getErrorMessage("inference_error") + ": ${e.message}")
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
    
    private fun getCurrentLanguage(): String {
        val sharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return sharedPreferences.getString("language", "zh") ?: "zh"
    }
    
    private fun getDynamicPrompt(asrResult: String): String {
        return when (getCurrentLanguage()) {
            "en" -> "User question: ${asrResult}. Please answer the user's question concisely or provide screen operation guidance based on the user's screen state and question above. Your answer should be concise and accurate."
            else -> "用户提问：${asrResult}。请根据以上给你的用户屏幕状态和用户提问，简洁地回答用户的问题或者提供屏幕操作指引。你的回答需要简洁、精确。"
        }
    }
    
    private fun getErrorMessage(errorType: String): String {
        return when (getCurrentLanguage()) {
            "en" -> when (errorType) {
                "no_screenshot" -> "No screenshot available"
                "model_not_initialized" -> "Model not initialized yet"
                "model_init_failed" -> "Model initialization failed"
                "model_init_exception" -> "Model initialization exception"
                "inference_error" -> "Inference error"
                else -> "Unknown error"
            }
            else -> when (errorType) {
                "no_screenshot" -> "没有可用的截图"
                "model_not_initialized" -> "模型尚未初始化完成"
                "model_init_failed" -> "模型初始化失败"
                "model_init_exception" -> "模型初始化异常"
                "inference_error" -> "推理过程出错"
                else -> "未知错误"
            }
        }
    }
    
    fun updateLanguageSettings() {
        // 更新TTS语言设置
        ttsManager.updateLanguage()
        Log.d(TAG, "Language settings updated")
    }
    
    fun stopInference() {
        Log.d(TAG, "Stopping inference...")
        
        // 立即设置标志位，停止推理
        isInferenceInProgress = false
        
        // 取消当前推理任务
        currentInferenceJob?.cancel()
        
        // 停止TTS
        ttsManager.stop()
        
        // 清理当前的截图和ASR结果
        currentScreenshot = null
        currentASRResult = null
        
        Log.d(TAG, "Inference stopped")
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