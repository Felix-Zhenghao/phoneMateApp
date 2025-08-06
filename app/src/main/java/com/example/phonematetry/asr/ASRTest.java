package com.example.phonematetry.asr;

import android.content.Context;
import android.util.Log;

import com.example.phonematetry.utils.WhisperUtil;

import java.io.IOException;
import java.io.InputStream;

public class ASRTest {
    private static final String TAG = "ASRTest";
    private Context context;
    
    public ASRTest(Context context) {
        this.context = context;
    }
    
    public void runAllTests() {
        Log.d(TAG, "Running all ASR tests");
        testWhisperUtil(context);
        testASRManager(context);
        Log.d(TAG, "All ASR tests completed");
    }
    
    public static void testWhisperUtil(Context context) {
        Log.d(TAG, "Starting Whisper utility test");
        
        try {
            WhisperUtil whisperUtil = new WhisperUtil();
            
            // Test loading vocabulary and filters
            String vocabPath = copyAssetToFile(context, "filters_vocab_en.bin");
            if (vocabPath != null) {
                boolean success = whisperUtil.loadFiltersAndVocab(false, vocabPath);
                Log.d(TAG, "Vocab loading result: " + success);
                
                if (success) {
                    // Test basic token functions
                    Log.d(TAG, "Token EOT: " + whisperUtil.getTokenEOT());
                    Log.d(TAG, "Token SOT: " + whisperUtil.getTokenSOT());
                    Log.d(TAG, "Token TRANSCRIBE: " + whisperUtil.getTokenTranscribe());
                    
                    // Test mel spectrogram generation with dummy data
                    float[] dummyAudio = new float[WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE];
                    for (int i = 0; i < dummyAudio.length; i++) {
                        dummyAudio[i] = (float) Math.sin(2 * Math.PI * 440 * i / WhisperUtil.WHISPER_SAMPLE_RATE) * 0.1f;
                    }
                    
                    float[] melSpec = whisperUtil.getMelSpectrogram(dummyAudio, dummyAudio.length, 1);
                    Log.d(TAG, "Mel spectrogram generated, length: " + melSpec.length);
                    Log.d(TAG, "Expected length: " + (WhisperUtil.WHISPER_N_MEL * WhisperUtil.WHISPER_MEL_LEN));
                }
            } else {
                Log.e(TAG, "Failed to copy vocab file");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error during Whisper utility test", e);
        }
    }
    
    public static void testASRManager(Context context) {
        Log.d(TAG, "Starting ASR Manager test");
        
        ASRManager asrManager = new ASRManager(context, new ASRManager.ASRListener() {
            @Override
            public void onASRResult(String transcription) {
                Log.d(TAG, "Test ASR Result: " + transcription);
            }
            
            @Override
            public void onASRError(String error) {
                Log.e(TAG, "Test ASR Error: " + error);
            }
            
            @Override
            public void onRecordingStarted() {
                Log.d(TAG, "Test Recording Started");
            }
            
            @Override
            public void onRecordingFinished() {
                Log.d(TAG, "Test Recording Finished");
            }
            
            @Override
            public void onSilenceDetected() {
                Log.d(TAG, "Test Silence Detected");
            }
            
            @Override
            public void onSoundDetected() {
                Log.d(TAG, "Test Sound Detected");
            }
        });
        
        // Test initialization
        boolean initSuccess = asrManager.initializeWithAssetFiles(false);
        Log.d(TAG, "ASR Manager initialization result: " + initSuccess);
        
        if (initSuccess) {
            Log.d(TAG, "ASR Manager is ready for use");
        }
        
        // Clean up
        asrManager.destroy();
    }
    
    private static String copyAssetToFile(Context context, String assetName) {
        try {
            java.io.File outputFile = new java.io.File(context.getFilesDir(), assetName);
            if (outputFile.exists()) {
                return outputFile.getAbsolutePath();
            }
            
            InputStream inputStream = context.getAssets().open(assetName);
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
}