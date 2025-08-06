package com.example.phonematetry.asr;

import android.content.Context;
import android.util.Log;

import com.example.phonematetry.engine.WhisperEngine;
import com.example.phonematetry.utils.WhisperUtil;

import org.tensorflow.lite.Interpreter;
// GPU delegate imports removed for compatibility

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WhisperTensorflowLite implements WhisperEngine {
    private static final String TAG = "WhisperTensorflowLite";
    
    private Interpreter interpreter;
    private WhisperUtil whisperUtil;
    private boolean isInitialized = false;
    
    private Context context;
    
    public WhisperTensorflowLite(Context context) {
        this.context = context;
        this.whisperUtil = new WhisperUtil();
    }
    
    @Override
    public boolean isInitialized() {
        return isInitialized;
    }
    
    @Override
    public boolean initialize(String modelPath, String vocabPath, boolean multilingual) throws IOException {
        try {
            // Load vocabulary and filters
            if (!whisperUtil.loadFiltersAndVocab(multilingual, vocabPath)) {
                Log.e(TAG, "Failed to load vocabulary and filters");
                return false;
            }
            
            // Load TensorFlow Lite model
            MappedByteBuffer modelBuffer = loadModelFile(modelPath);
            
            // Configure interpreter options
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            Log.d(TAG, "Using CPU for inference");
            
            interpreter = new Interpreter(modelBuffer, options);
            isInitialized = true;
            
            Log.d(TAG, "Whisper model initialized successfully");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Whisper model", e);
            return false;
        }
    }
    
    @Override
    public void deinitialize() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        isInitialized = false;
        Log.d(TAG, "Whisper model deinitialized");
    }
    
    @Override
    public String transcribeFile(String wavePath) {
        // This would require WAV file reading implementation
        // For now, return empty string
        Log.w(TAG, "transcribeFile not implemented yet");
        return "";
    }
    
    @Override
    public String transcribeBuffer(float[] samples) {
        if (!isInitialized) {
            Log.e(TAG, "Model not initialized");
            return "";
        }
        
        try {
            // Ensure we have exactly 30 seconds of audio (480000 samples at 16kHz)
            int targetSamples = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE;
            float[] processedSamples = new float[targetSamples];
            
            if (samples.length >= targetSamples) {
                // Take the first 30 seconds
                System.arraycopy(samples, 0, processedSamples, 0, targetSamples);
            } else {
                // Pad with zeros if shorter than 30 seconds
                System.arraycopy(samples, 0, processedSamples, 0, samples.length);
                Arrays.fill(processedSamples, samples.length, targetSamples, 0.0f);
            }
            
            // Convert audio to mel spectrogram
            float[] melSpectrogram = whisperUtil.getMelSpectrogram(processedSamples, targetSamples, 1);
            
            // Prepare input tensor (mel spectrogram)
            ByteBuffer inputBuffer = ByteBuffer.allocateDirect(4 * WhisperUtil.WHISPER_N_MEL * WhisperUtil.WHISPER_MEL_LEN);
            inputBuffer.order(ByteOrder.nativeOrder());
            
            // Fill input buffer with mel spectrogram data
            for (int i = 0; i < Math.min(melSpectrogram.length, WhisperUtil.WHISPER_N_MEL * WhisperUtil.WHISPER_MEL_LEN); i++) {
                inputBuffer.putFloat(melSpectrogram[i]);
            }
            
            // Get actual output tensor shape from the model
            int[] outputShape = interpreter.getOutputTensor(0).shape();
            int outputSize = 1;
            for (int dim : outputShape) {
                outputSize *= dim;
            }
            
            Log.d(TAG, "Output tensor shape: " + Arrays.toString(outputShape));
            Log.d(TAG, "Output tensor size: " + outputSize);
            
            // Prepare output tensor with correct size
            ByteBuffer outputBuffer = ByteBuffer.allocateDirect(4 * outputSize);
            outputBuffer.order(ByteOrder.nativeOrder());
            
            // Run inference
            Object[] inputs = {inputBuffer};
            Map<Integer, Object> outputs = new HashMap<>();
            outputs.put(0, outputBuffer);
            
            interpreter.runForMultipleInputsOutputs(inputs, outputs);
            
            // Process output tokens
            outputBuffer.rewind();
            List<Integer> tokens = new ArrayList<>();
            
            for (int i = 0; i < outputSize; i++) {
                int token = outputBuffer.getInt();
                if (token == whisperUtil.getTokenEOT()) {
                    break;
                }
                tokens.add(token);
            }
            
            // Convert tokens to text
            StringBuilder result = new StringBuilder();
            for (int token : tokens) {
                String word = whisperUtil.getWordFromToken(token);
                if (word != null && !word.startsWith("[_")) {
                    result.append(word);
                }
            }
            
            String transcription = result.toString().trim();
            Log.d(TAG, "Transcription result: " + transcription);
            return transcription;
            
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            return "";
        }
    }
    
    private MappedByteBuffer loadModelFile(String modelPath) throws IOException {
        FileInputStream inputStream = new FileInputStream(modelPath);
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = 0L;
        long declaredLength = fileChannel.size();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }
}