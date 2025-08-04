package com.example.phonematetry.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.phonematetry.data.Model
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession

private const val TAG = "LlmInferenceManager"

typealias InferenceResultListener = (partialResult: String, done: Boolean) -> Unit
typealias InferenceCleanUpListener = () -> Unit

data class LlmModelInstance(val engine: LlmInference, var session: LlmInferenceSession)

object LlmInferenceManager {
    // Indexed by model name.
    private val cleanUpListeners: MutableMap<String, InferenceCleanUpListener> = mutableMapOf()
    
    // 默认推理参数
    private const val DEFAULT_MAX_TOKENS = 2048
    private const val DEFAULT_TOP_K = 40
    private const val DEFAULT_TOP_P = 0.95f
    private const val DEFAULT_TEMPERATURE = 0.8f
    private const val MAX_IMAGE_COUNT = 10

    fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
        Log.d(TAG, "Initializing model: ${model.name}")
        
        // 首先尝试GPU后端，如果失败则回退到CPU后端
        var llmInference: LlmInference? = null
        
        // 尝试GPU后端
        try {
            Log.d(TAG, "Attempting to initialize with GPU backend")
            val gpuOptionsBuilder = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(model.getPath(context))
                .setMaxTokens(DEFAULT_MAX_TOKENS)
                .setPreferredBackend(LlmInference.Backend.GPU)
                .setMaxNumImages(MAX_IMAGE_COUNT)
            
            val gpuOptions = gpuOptionsBuilder.build()
            llmInference = LlmInference.createFromOptions(context, gpuOptions)
            Log.d(TAG, "GPU backend initialization successful")
        } catch (e: Exception) {
            Log.w(TAG, "GPU backend initialization failed, falling back to CPU: ${e.message}")
            
            // 回退到CPU后端
            try {
                Log.d(TAG, "Attempting to initialize with CPU backend")
                val cpuOptionsBuilder = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(model.getPath(context))
                    .setMaxTokens(DEFAULT_MAX_TOKENS)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .setMaxNumImages(MAX_IMAGE_COUNT)
                
                val cpuOptions = cpuOptionsBuilder.build()
                llmInference = LlmInference.createFromOptions(context, cpuOptions)
                Log.d(TAG, "CPU backend initialization successful")
            } catch (cpuException: Exception) {
                Log.e(TAG, "Both GPU and CPU backend initialization failed", cpuException)
                onDone("模型初始化失败: GPU和CPU后端都无法初始化 - ${cpuException.message}")
                return
            }
        }

        try {

            val session = LlmInferenceSession.createFromOptions(
                llmInference,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(DEFAULT_TOP_K)
                    .setTopP(DEFAULT_TOP_P)
                    .setTemperature(DEFAULT_TEMPERATURE)
                    .setGraphOptions(
                        GraphOptions.builder()
                            .setEnableVisionModality(true)
                            .build()
                    )
                    .build()
            )
            
            model.instance = LlmModelInstance(engine = llmInference, session = session)
            Log.d(TAG, "Model initialized successfully: ${model.name}")
            onDone("")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize model: ${model.name}", e)
            onDone("模型初始化失败: ${e.message}")
        }
    }

    fun resetSession(model: Model) {
        try {
            Log.d(TAG, "Resetting session for model '${model.name}'")

            val instance = model.instance as LlmModelInstance? ?: return
            val session = instance.session
            session.close()

            val inference = instance.engine
            val newSession = LlmInferenceSession.createFromOptions(
                inference,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(DEFAULT_TOP_K)
                    .setTopP(DEFAULT_TOP_P)
                    .setTemperature(DEFAULT_TEMPERATURE)
                    .setGraphOptions(
                        GraphOptions.builder()
                            .setEnableVisionModality(true)
                            .build()
                    )
                    .build()
            )
            instance.session = newSession
            Log.d(TAG, "Session reset successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset session", e)
        }
    }

    fun cleanUp(model: Model) {
        if (model.instance == null) {
            return
        }

        val instance = model.instance as LlmModelInstance

        try {
            instance.session.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close the LLM Inference session: ${e.message}")
        }

        try {
            instance.engine.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close the LLM Inference engine: ${e.message}")
        }

        val onCleanUp = cleanUpListeners.remove(model.name)
        onCleanUp?.invoke()
        model.instance = null
        Log.d(TAG, "Clean up done.")
    }

    fun runInference(
        model: Model,
        prompt: String,
        image: Bitmap?,
        resultListener: InferenceResultListener,
        cleanUpListener: InferenceCleanUpListener
    ) {
        val instance = model.instance as LlmModelInstance

        // Set listener.
        if (!cleanUpListeners.containsKey(model.name)) {
            cleanUpListeners[model.name] = cleanUpListener
        }

        try {
            val session = instance.session
            
            // Add text prompt
            if (prompt.trim().isNotEmpty()) {
                session.addQueryChunk(prompt)
            }
            
            // Add image if provided
            image?.let {
                Log.d(TAG, "Adding image to inference session")
                session.addImage(BitmapImageBuilder(it).build())
            }
            
            // Start async inference
            // Note: In this MediaPipe version, generateResponseAsync doesn't accept parameters
            // We'll use synchronous generation for now
            try {
                val response = session.generateResponse()
                resultListener(response, true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate response", e)
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run inference", e)
            cleanUpListener.invoke()
        }
    }
    
    fun stopInference(model: Model) {
        try {
            val instance = model.instance as LlmModelInstance?
            // Note: cancelGenerateResponseAsync may not be available in this MediaPipe version
            // For now, we'll rely on session cleanup in resetSession or cleanUp methods
            Log.d(TAG, "Stop inference requested for model: ${model.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop inference", e)
        }
    }
}