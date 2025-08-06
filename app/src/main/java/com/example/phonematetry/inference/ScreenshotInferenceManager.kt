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
    
    // 存储历史ASR输入记录
    private val asrHistory = mutableListOf<String>()
    
    // 两阶段推理状态
    private var isImagePrefilled = false
    private var pendingASRResult: String? = null
    
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
        Log.d(TAG, "Screenshot saved")
        
        // 立即进行图片预填充
        prefillImageAsync(screenshot)
    }
    
    private fun prefillImageAsync(screenshot: Bitmap) {
        if (!isModelInitialized) {
            Log.w(TAG, "Model not initialized, skipping image prefill")
            return
        }
        
        coroutineScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting image prefill...")
                
                // 等待模型实例准备就绪
                while (model.instance == null) {
                    delay(100)
                }
                
                // 进行图片预填充（不重置session，保留图片在session中）
                val success = LlmInferenceManager.prefillImage(
                    model = model,
                    image = screenshot,
                    cleanUpListener = {
                        isImagePrefilled = false
                    }
                )
                
                if (success) {
                    isImagePrefilled = true
                    Log.d(TAG, "Image prefill completed successfully")
                    
                    // 如果有待处理的ASR结果，立即进行文本推理
                    pendingASRResult?.let { asrResult ->
                        Log.d(TAG, "Processing pending ASR result after prefill")
                        pendingASRResult = null
                        processASRResultInternal(asrResult)
                    }
                } else {
                    Log.w(TAG, "Image prefill failed")
                    isImagePrefilled = false
                }
                
            } catch (e: Exception) {
                 Log.e(TAG, "Error during image prefill", e)
                 isImagePrefilled = false
             }
         }
     }
    
    // 处理ASR结果并开始推理
    fun processWithASRResult(asrResult: String) {
        Log.d(TAG, "Processing ASR result: $asrResult")
        
        if (asrResult.trim().isEmpty()) {
            Log.w(TAG, "ASR result is empty, ignoring")
            return
        }
        
        currentASRResult = asrResult
        
        // 将当前ASR结果添加到历史记录中
        asrHistory.add(asrResult)
        
        // 如果历史记录过长，移除最旧的记录
        if (asrHistory.size > 10) {
            asrHistory.removeAt(0)
        }
        
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
        
        // 检查图片是否已经预填充
        if (isImagePrefilled) {
            Log.d(TAG, "Image already prefilled, starting text inference immediately")
            processASRResultInternal(asrResult)
        } else {
            Log.d(TAG, "Image not prefilled yet, storing ASR result for later processing")
            pendingASRResult = asrResult
        }
    }
    
    private fun processASRResultInternal(asrResult: String) {
        // 构造动态prompt，包含历史对话
        val dynamicPrompt = getDynamicPrompt(asrHistory)
        
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
                
                var fullResponse = ""
                var lastTTSText = ""
                
                // 使用文本推理方法，因为图片已经在预填充阶段处理过了
                LlmInferenceManager.runTextInference(
                    model = model,
                    prompt = dynamicPrompt,
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
                            
                            // 重置session以准备下次推理
                            try {
                                LlmInferenceManager.resetSession(model)
                                Log.d(TAG, "Session reset after inference completion")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to reset session after inference", e)
                            }
                            
                            // 清理状态，但保留ASR历史记录
                            currentScreenshot = null
                            currentASRResult = null
                            isImagePrefilled = false
                        }
                    },
                    cleanUpListener = {
                        isInferenceInProgress = false
                        isImagePrefilled = false
                    }
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during text inference", e)
                isInferenceInProgress = false
                isImagePrefilled = false
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
        return sharedPreferences.getString("language", "en") ?: "en"
    }
    
    private fun getDynamicPrompt(asrHistory: List<String>): String {
        if (asrHistory.isEmpty()) {
            return when (getCurrentLanguage()) {
                "en" -> "Please analyze the current screen state and provide guidance."
                else -> "请分析当前屏幕状态并提供指引。"
            }
        }
        
        return when (getCurrentLanguage()) {
            "en" -> {
                val historyText = asrHistory.mapIndexed { index, input ->
                    "User input round ${index + 1}: $input"
                }.joinToString("; ")
                "$historyText. Based on the user's past and current multi-round inputs, comprehensively determine what the user **currently** wants to do, and provide concise answers to the user's questions or screen operation guidance based on the current screen state. You should only output your answer or guidance. If you want the user to click a certain button on the screen, describe the location, shape or the color of the button concisely to help the user to distinguish that button from other buttons on the screen. You should always answer in a concise and accurate manner."
            }
            else -> {
                val historyText = asrHistory.mapIndexed { index, input ->
                    "用户第${index + 1}轮输入：$input"
                }.joinToString("；")
                "$historyText。请根据用户过去和现在的多轮输入的内容，综合判断用户**现在**想要做什么，根据当下的屏幕状态简洁地回答用户的问题或者提供屏幕操作指引。你应该只输出你的回答或者指引。如果用户想点击屏幕上的某个按钮，简洁地描述按钮的位置、形状或颜色，以帮助用户区分该按钮与屏幕上的其他按钮。你应该总是以简洁、精确的方式回答。"
            }
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
    
    fun clearConversationHistory() {
        asrHistory.clear()
        Log.d(TAG, "Conversation history cleared")
    }
    
    fun getConversationHistorySize(): Int {
        return asrHistory.size
    }
    
    fun stopInference() {
        Log.d(TAG, "Stopping inference...")
        
        // 立即设置标志位，停止推理
        isInferenceInProgress = false
        
        // 取消当前推理任务
        currentInferenceJob?.cancel()
        
        // 停止TTS
        ttsManager.stop()
        
        // 清理当前的截图和ASR结果，但保留历史记录
        currentScreenshot = null
        currentASRResult = null
        
        // 清理两阶段推理状态
        isImagePrefilled = false
        pendingASRResult = null
        
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