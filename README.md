# The Pipeline (No AI. You can read pure human output :>

> Download `phonemate.apk` to feel the APP. The APP is fully test only on Samsung S24 Ultra and only Android phone is supported.

> **I write it on my own. No AI. You can read pure human output :)**

> Google AI Edge framework used: MediaPipe LLM Inference API. Model used: Gemma3n E2B; Whisper tiny.

### Once installed:

- Automatically download the Gemma3n E3B model hosted [here](https://www.modelscope.cn/models/google/gemma-3n-E2B-it-litert-preview/file/view/master/README.md?status=1).
- Automatically ask for permissions like storage, audio input.

### Everytime the user launches the APP

- Ask for permission to record the screen for MediaProjection API
- Once the permission is granted, the APP will render the floating window and the MediaProjection module will start to record the screen
- The APP then will try to load to model on the GPU and initialize the inference session and engine
- All recorded frames will be discarded if the user does not click the "ScreenShot Ask" button

### Everytime the user takes a screenshot

- The screenshot will be immediately sent for LLM prefilling
- The APP will listen what the user is talking
- Once the user stops talking, the user can click the "Recording, tap to stop" button (same button as the screenshot button).
- Once the button is clicked, the whiper tiny model will do ASR on the recorded audio wave. (See design choices section for more details)
- If no words are detected, the model will not do inference.
- If words are detected, the model will concatenate the tokenized text with the prefilled image tokens and do inference
- Only audio output is supporte. Android TTS API is used.

### Close

- The user can close the app by clicking another button (there are only two buttons on the floating window).
- The app will ask for a second confirmation before closing to prevent accidental taps.

# Design Choices

### Q1: Gemma3n has its audio encoder and adapter, why use whisper for ASR?

Because the audio encoder part is not supported in the MediaPipe framework and it is also not included in the launched .tflite or .task model files on huggingface or modelscope. One developer in the community launched an issue for this feature but it is not supported until the submission deadline. See issue [here](https://github.com/google-ai-edge/ai-edge-torch/issues/754).

Acutally, the native audio encoder is one of my favourite Gemma3n features. I have tested the audio encoder on my laptop and I was totally impressed. I understand that stiching a whisper model with the Gemma3n backbone is an ugly solution which my lead to huge accumulated error, and a native audio encoder is a much better way to support features of this app.

This is only a temp solution. Once the audio modality is supported by the official model files and frameworks, I will discard the whisper totally.

### Q2: How do you do context management?

To maximally overlap the prefill time with other time costs, once the user takes a screenshot, it will be sent for prefilling. Typically this will totally overlap the image prefilling time with users' speaking time and ASR time. So we can prefill one image for free (I have tested that two images will not be that smooth).

Therefore, the context management is done as follows: all previous ASR result and the current ASR results will be included in the context, and only the current screenshot will be included in the context. The prompt is f“{screenshot} {previous turns} {current turn} {fixed prompt}". Of course we can move the fixed prompt and previous turns before the screenshot and we can get even more free prefilling time.

Moreover, for most scenarios, including one image and all past user audio input is enough. Most of the user's intent is included in the past audio input and the model should memorize that. Consider the following conversation.

```
# Conversation happens when the user wants to book an online appointment
User: hey, I'd like to book an appointment at this hospital
<screenshot>
Model: ...

# After the user clicks the button
User: What should I do now?
<screenshot>
Model: ...
```

In this scenario, previous audio input can help the model memorize the user's intent, and current screenshot can let the model give immediate instruction.

Of course, one case that may need more image context window is failure recovery. The model gives some wrong instructions and the user does that. The model may need more previous  images to enable it to give instructions like "go back and click...".

### Q3: Why give audio instructions rather than deploy a fully automated agent to click for the user?

That can be dangerous and not easy to realize technically, for now.

### Q4: Why edge model for this scenario?

- Always-there. This grants 100% uptime. For the elderly people, this can be very important.
- Low cost. No API calls. Just electricity used.
- Privacy. Screenshot may include sensitive info.
