package com.example.phonematetry.asr;

import android.content.Context;
import android.util.Log;

import com.example.phonematetry.engine.WhisperEngine;

import java.io.File;
import java.io.IOException;

public class ASRManager implements AudioRecorder.AudioRecordingListener {
    private static final String TAG = "ASRManager";
    
    private Context context;
    private WhisperEngine whisperEngine;
    private AudioRecorder audioRecorder;
    private ASRListener listener;
    private boolean isInitialized = false;
    
    public interface ASRListener {
        void onASRResult(String transcription);
        void onASRError(String error);
        void onRecordingStarted();
        void onRecordingFinished();
        void onSilenceDetected();
        void onSoundDetected();
    }
    
    public ASRManager(Context context, ASRListener listener) {
        this.context = context;
        this.listener = listener;
        this.audioRecorder = new AudioRecorder(this);
    }
    
    public boolean initialize(boolean useMultilingual) {
        try {
            // Initialize Whisper engine
            whisperEngine = new WhisperTensorflowLite(context);
            
            // Get model and vocab paths
            String assetsDir = context.getFilesDir().getAbsolutePath() + "/assets";
            
            String modelPath;
            String vocabPath;
            
            if (useMultilingual) {
                modelPath = assetsDir + "/whisper-tiny.tflite";
                vocabPath = assetsDir + "/filters_vocab_multilingual.bin";
            } else {
                modelPath = assetsDir + "/whisper-tiny.en.tflite";
                vocabPath = assetsDir + "/filters_vocab_en.bin";
            }
            
            // Check if files exist
            if (!new File(modelPath).exists()) {
                Log.e(TAG, "Model file not found: " + modelPath);
                return false;
            }
            
            if (!new File(vocabPath).exists()) {
                Log.e(TAG, "Vocab file not found: " + vocabPath);
                return false;
            }
            
            // Initialize the engine
            if (!whisperEngine.initialize(modelPath, vocabPath, useMultilingual)) {
                Log.e(TAG, "Failed to initialize Whisper engine");
                return false;
            }
            
            isInitialized = true;
            Log.d(TAG, "ASR Manager initialized successfully");
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "Error initializing ASR Manager", e);
            return false;
        }
    }
    
    public boolean initializeWithAssetFiles(boolean useMultilingual) {
        try {
            // Initialize Whisper engine
            whisperEngine = new WhisperTensorflowLite(context);
            
            // Get model and vocab paths from assets
            String modelPath;
            String vocabPath;
            
            if (useMultilingual) {
                modelPath = copyAssetToFile("whisper-tiny.tflite");
                vocabPath = copyAssetToFile("filters_vocab_multilingual.bin");
            } else {
                modelPath = copyAssetToFile("whisper-tiny.en.tflite");
                vocabPath = copyAssetToFile("filters_vocab_en.bin");
            }
            
            if (modelPath == null || vocabPath == null) {
                Log.e(TAG, "Failed to copy asset files");
                return false;
            }
            
            // Initialize the engine
            if (!whisperEngine.initialize(modelPath, vocabPath, useMultilingual)) {
                Log.e(TAG, "Failed to initialize Whisper engine");
                return false;
            }
            
            isInitialized = true;
            Log.d(TAG, "ASR Manager initialized successfully with asset files");
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "Error initializing ASR Manager with asset files", e);
            return false;
        }
    }
    
    private String copyAssetToFile(String assetName) {
        try {
            File outputFile = new File(context.getFilesDir(), assetName);
            if (outputFile.exists()) {
                return outputFile.getAbsolutePath();
            }
            
            java.io.InputStream inputStream = context.getAssets().open(assetName);
            java.io.FileOutputStream outputStream = new java.io.FileOutputStream(outputFile);
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            
            inputStream.close();
            outputStream.close();
            
            Log.d(TAG, "Copied asset " + assetName + " to " + outputFile.getAbsolutePath());
            return outputFile.getAbsolutePath();
            
        } catch (IOException e) {
            Log.e(TAG, "Error copying asset " + assetName, e);
            return null;
        }
    }
    
    public void startVoiceRecognition() {
        if (!isInitialized) {
            Log.e(TAG, "ASR Manager not initialized");
            if (listener != null) {
                listener.onASRError("ASR Manager not initialized");
            }
            return;
        }
        
        if (audioRecorder.isRecording()) {
            Log.w(TAG, "Already recording");
            return;
        }
        
        Log.d(TAG, "Starting voice recognition");
        audioRecorder.startRecording();
    }
    
    public void stopVoiceRecognition() {
        if (audioRecorder.isRecording()) {
            Log.d(TAG, "Stopping voice recognition");
            audioRecorder.stopRecording();
        }
    }
    
    public void destroy() {
        if (audioRecorder.isRecording()) {
            audioRecorder.stopRecording();
        }
        
        if (whisperEngine != null) {
            whisperEngine.deinitialize();
            whisperEngine = null;
        }
        
        isInitialized = false;
        Log.d(TAG, "ASR Manager destroyed");
    }
    
    public boolean isInitialized() {
        return isInitialized;
    }
    
    public boolean isRecording() {
        return audioRecorder.isRecording();
    }
    
    // AudioRecorder.AudioRecordingListener implementation
    @Override
    public void onRecordingStarted() {
        Log.d(TAG, "Recording started");
        if (listener != null) {
            listener.onRecordingStarted();
        }
    }
    
    @Override
    public void onRecordingFinished(float[] audioSamples) {
        Log.d(TAG, "Recording finished, processing " + audioSamples.length + " samples");
        
        if (listener != null) {
            listener.onRecordingFinished();
        }
        
        // Process audio in background thread
        new Thread(() -> {
            try {
                String transcription = whisperEngine.transcribeBuffer(audioSamples);
                
                // Post result back to main thread
                if (listener != null) {
                    // Assuming we're running on main thread context
                    listener.onASRResult(transcription);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error during transcription", e);
                if (listener != null) {
                    listener.onASRError("Transcription error: " + e.getMessage());
                }
            }
        }).start();
    }
    
    @Override
    public void onRecordingError(String error) {
        Log.e(TAG, "Recording error: " + error);
        if (listener != null) {
            listener.onASRError(error);
        }
    }
    
    @Override
    public void onSilenceDetected() {
        Log.d(TAG, "Silence detected");
        if (listener != null) {
            listener.onSilenceDetected();
        }
    }
    
    @Override
    public void onSoundDetected() {
        Log.d(TAG, "Sound detected");
        if (listener != null) {
            listener.onSoundDetected();
        }
    }
}