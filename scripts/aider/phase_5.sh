#!/usr/bin/env bash
# Voice Feature: Speech-to-Text + Voice Commands (Reply by voice)
# =============================================================================
# Feature: Say "Respond" -> SilentPulse listens -> dictate reply -> sends
#   - DEFAULT STT: Android built-in (no setup, may use Google servers)
#   - OPTIONAL STT: Vosk (100% offline, model downloaded when user selects it
#                   in settings, multi-language support)
#   - All processing ON-DEVICE when Vosk is selected
# =============================================================================
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
SCRIPTS="$REPO/scripts/aider"

echo "-> Phase 5: Voice STT + Voice Commands..."

bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/build.gradle \
  --message "Add to presentation/build.gradle dependencies:
implementation 'com.alphacephei:vosk-android:0.3.47'
implementation 'androidx.work:work-runtime-ktx:2.9.1'
Also add to android/defaultConfig: ndk { abiFilters 'arm64-v8a', 'x86_64' }
Add maven { url 'https://jitpack.io' } to repositories if not present."

bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  domain/src/main/java/com/moez/QKSMS/util/Preferences.kt \
  --message "Add these preference keys to Preferences.kt if not already present:
val driveModeEnabled = rxPrefs.getBoolean('drive_mode_enabled', false)
val driveModeReadSms = rxPrefs.getBoolean('drive_mode_read_sms', true)
val driveModeReadAllNotifications = rxPrefs.getBoolean('drive_mode_read_all_notif', false)
val driveModeTtsEngine = rxPrefs.getString('drive_mode_tts_engine', 'android')
val driveModeVoiceReplyEnabled = rxPrefs.getBoolean('drive_mode_voice_reply', false)
val driveModeReplyTimeoutSecs = rxPrefs.getInteger('drive_mode_reply_timeout', 30)
val driveModeSttEngine = rxPrefs.getString('drivemode_stt_engine', 'android')
val driveModeVoskLanguage = rxPrefs.getString('drivemode_vosk_language', 'en-us')
val driveModeVoskModelPath = rxPrefs.getString('drivemode_vosk_model_path', '')"

bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  --message "Create presentation/src/main/java/com/moez/QKSMS/feature/drivemode/SttEngine.kt:
interface SttEngine {
    fun startListening(onResult: (String) -> Unit, onError: (Exception) -> Unit)
    fun stopListening()
    fun isListening(): Boolean
    fun shutdown()
}

Create AndroidSttEngine.kt in same package:
- Uses android.speech.SpeechRecognizer
- RecognizerIntent.ACTION_RECOGNIZE_SPEECH, LANGUAGE_MODEL_CONVERSATIONAL
- Comment: 'WARNING: May stream audio to Google servers'

Create VoskSttEngine.kt in same package:
- Constructor: VoskSttEngine(private val modelPath: String)
- If modelPath empty/missing directory: calls onError(Exception('Vosk model not installed'))
- Uses vosk.Model(modelPath) and vosk.Recognizer(model, 16000.0f)
- AudioRecord: SAMPLE_RATE_IN_HZ=16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT
- Background thread with Executors.newSingleThreadExecutor()
- Read 4096 byte chunks, feed to recognizer
- Calls onResult with final result text when stopListening() called
- Fully offline, no network required

Create SttEngineFactory.kt in same package:
- fun create(context: Context, prefs: Preferences): SttEngine
- If prefs.driveModeSttEngine.get() == 'vosk' AND model path exists: return VoskSttEngine(path)
- Otherwise: return AndroidSttEngine(context)
- Document: default is AndroidSttEngine"

bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  --message "Create presentation/src/main/java/com/moez/QKSMS/feature/drivemode/VoskModelDownloader.kt:

This downloads Vosk language models triggered ONLY when user explicitly selects
Vosk in settings (never automatic).

data class VoskLanguage(val label: String, val modelId: String)

companion object {
    val AVAILABLE_LANGUAGES = listOf(
        VoskLanguage('English (US)', 'vosk-model-small-en-us-0.15'),
        VoskLanguage('English (UK)', 'vosk-model-small-en-in-0.4'),
        VoskLanguage('French', 'vosk-model-small-fr-0.22'),
        VoskLanguage('German', 'vosk-model-small-de-0.15'),
        VoskLanguage('Spanish', 'vosk-model-small-es-0.42'),
        VoskLanguage('Portuguese', 'vosk-model-small-pt-0.3'),
        VoskLanguage('Italian', 'vosk-model-small-it-0.4'),
        VoskLanguage('Russian', 'vosk-model-small-ru-0.22'),
        VoskLanguage('Chinese', 'vosk-model-small-cn-0.22'),
        VoskLanguage('Japanese', 'vosk-model-small-ja-0.22')
    )
    fun modelUrl(language: VoskLanguage) =
        'https://alphacephei.com/vosk/models/\${language.modelId}.zip'
}

fun downloadModel(language: VoskLanguage,
                  onProgress: (Int) -> Unit,
                  onComplete: (modelPath: String) -> Unit,
                  onError: (Exception) -> Unit)
// Downloads to context.getExternalFilesDir('vosk_models')/{modelId}/
// Uses OkHttp (already in project) for streaming download
// Unzips with ZipInputStream after download
// Runs on background thread, callbacks on main thread via Handler

fun isModelDownloaded(language: VoskLanguage): Boolean
fun getModelPath(language: VoskLanguage): String"

bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  --message "Create presentation/src/main/java/com/moez/QKSMS/feature/drivemode/VoiceCommandProcessor.kt:

class VoiceCommandProcessor @Inject constructor(
    private val ttsEngine: TtsEngine,
    private val sendMessage: SendMessage,
    private val prefs: Preferences
) {
    var sttEngine: SttEngine? = null
    var lastNotificationContext: NotificationContext? = null

    data class NotificationContext(
        val senderName: String,
        val threadId: Long,
        val address: String,
        val app: String
    )

    fun handleCommand(spokenText: String)
    // Match: 'respond'/'reply' -> startReplyFlow()
    // Match: 'ignore'/'dismiss' -> clear context
    // Match: 'call [name]' -> launch dial intent

    private fun startReplyFlow()
    // 1. TTS: 'What would you like to say to [senderName]?'
    // 2. Listen for prefs.driveModeReplyTimeoutSecs
    // 3. TTS: 'Sending to [name]: [text]. Say confirm or cancel.'
    // 4. Listen: 'confirm' -> SendMessage interactor, 'cancel' -> TTS 'Cancelled'
}"

bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/drivemode/DriveModeService.kt \
  --message "Update DriveModeService to integrate STT and VoiceCommandProcessor:
1. Inject VoiceCommandProcessor
2. After TTS reads each message: store NotificationContext in VoiceCommandProcessor
3. If driveModeVoiceReplyEnabled: TTS says 'Say respond to reply'
4. Start SttEngine listening after TTS finishes (onDone callback)
5. Pass recognized speech to VoiceCommandProcessor.handleCommand()
6. Add notification action button labeled 'Voice Reply'
7. Check RECORD_AUDIO permission before starting STT"

bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/AndroidManifest.xml \
  presentation/src/main/java/com/moez/QKSMS/feature/settings/SettingsController.kt \
  --message "1. Add permissions to AndroidManifest.xml:
   <uses-permission android:name='android.permission.RECORD_AUDIO' />
   <uses-permission android:name='android.permission.INTERNET' />

2. In SettingsController Drive Mode section:
   Add 'STT Engine' preference with two options:
   a) 'Android Built-in (Default)' - shows warning 'May use Google servers'
   b) 'Vosk - 100% Offline (Recommended for privacy)'
   Default is 'android'.

3. When user selects Vosk:
   - Show AlertDialog with language spinner populated from VoskModelDownloader.AVAILABLE_LANGUAGES
   - On confirm: show ProgressDialog with download percentage using VoskModelDownloader.downloadModel()
   - On complete: save model path to prefs.driveModeVoskModelPath, set driveModeSttEngine='vosk'
   - On error: toast error message, revert driveModeSttEngine='android'
   - If already downloaded: skip download, just set preference

4. Show current model status in preference subtitle

5. When voice replies enabled: check RECORD_AUDIO permission via ActivityCompat.requestPermissions"

echo "-> Phase 5 COMPLETE - Voice STT + command system with Vosk multi-language"
