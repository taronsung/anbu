package app.anbu.car.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * 말하기와 듣기.
 *
 *  - 말하기: Pleos TextToSpeech SDK(Mode.ON_DEVICE)를 먼저 쓰고,
 *    초기화나 재생이 실패하면 안드로이드 기본 TextToSpeech로 넘어간다.
 *  - 듣기: Pleos SpeechToText SDK(Mode.ON_DEVICE). 대신할 엔진은 두지 않았고,
 *    듣기가 안 되는 환경에서는 화면 버튼만으로 같은 동작을 할 수 있다.
 *
 * ON_DEVICE 모드는 서버를 쓰지 않으므로 앱 등록(자격 증명)이 필요 없다.
 */
class VoiceEngine(private val context: Context, private val onChanged: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())

    private var pleosTts: TextToSpeech? = null
    private var pleosTtsReady = false
    private var androidTts: AndroidTts? = null
    private var androidTtsReady = false
    private var pendingFallbackText: String? = null
    private var lastSpoken: String? = null

    private var pleosStt: SpeechToText? = null
    private var pleosSttReady = false
    private var onHeard: ((String, Boolean) -> Unit)? = null
    private var afterSpeech: (() -> Unit)? = null

    var listening: Boolean = false; private set
    var ttsBackend: String = "준비 중"; private set
    var sttBackend: String = "준비 중"; private set
    var lastHeard: String = ""; private set

    private val ttsEvents = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() {
            Log.i(TAG, "Pleos TTS onStart")
        }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onStop() = Unit
        override fun onDone() {
            Log.i(TAG, "Pleos TTS onDone")
            main.post { runAfterSpeech() }
        }

        override fun onError(errorType: TtsError) {
            Log.i(TAG, "Pleos TTS error: $errorType")
            main.post {
                // 이번 문장은 기본 엔진으로 다시 읽는다.
                lastSpoken?.let { speakWithAndroid(it) }
            }
        }
    }

    private val sttResults = object : ResultListener {
        override fun onReady() {
            Log.i(TAG, "Pleos STT onReady")
        }
        override fun onStartedRecognition() {
            Log.i(TAG, "Pleos STT onStartedRecognition")
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onUpdated(stt: String, completed: Boolean) {
            // 인식된 말 자체는 기록에 남기지 않는다. 길이와 완료 여부만 남긴다.
            Log.i(TAG, "Pleos STT onUpdated length=${stt.length} completed=$completed")
            main.post {
                lastHeard = stt
                onHeard?.invoke(stt, completed)
                onChanged()
            }
        }

        override fun onEndedRecognition() {
            Log.i(TAG, "Pleos STT onEndedRecognition")
            main.post { listening = false; onChanged() }
        }

        override fun onError(errorType: SttError) {
            Log.i(TAG, "Pleos STT error: $errorType")
            main.post { listening = false; onChanged() }
        }
    }

    fun start() {
        startPleosTts()
        startPleosStt()
    }

    private fun startPleosTts() {
        try {
            val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
            pleosTts = tts
            tts.initialize(object : TtsInit {
                override fun onSuccess() {
                    main.post {
                        runCatching {
                            tts.addEventListener(ttsEvents)
                            tts.setLocale(Locale.KOREA)
                        }
                        pleosTtsReady = true
                        ttsBackend = "Pleos TextToSpeech (ON_DEVICE)"
                        onChanged()
                    }
                }

                override fun onFailure(errorType: TtsError) {
                    Log.i(TAG, "Pleos TTS init failed: $errorType")
                    main.post { startAndroidTts("Pleos TTS 초기화 실패($errorType)") }
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "Pleos TTS unavailable", t)
            startAndroidTts("Pleos TTS 없음")
        }
    }

    private fun startAndroidTts(reason: String) {
        if (androidTts != null) return
        ttsBackend = "안드로이드 기본 TTS 준비 중 ($reason)"
        androidTts = AndroidTts(context.applicationContext) { status ->
            main.post {
                androidTtsReady = status == AndroidTts.SUCCESS
                if (androidTtsReady) {
                    runCatching { androidTts?.language = Locale.KOREA }
                    ttsBackend = "안드로이드 기본 TTS ($reason)"
                    pendingFallbackText?.let { speakWithAndroid(it) }
                } else {
                    ttsBackend = "음성 안내 없음 ($reason)"
                }
                pendingFallbackText = null
                onChanged()
            }
        }
        onChanged()
    }

    private fun startPleosStt() {
        try {
            val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
            pleosStt = stt
            stt.initialize(object : SttInit {
                override fun onSuccess() {
                    main.post {
                        runCatching {
                            stt.addListener(sttResults)
                            stt.setLocale(Locale.KOREA)
                        }
                        pleosSttReady = true
                        sttBackend = "Pleos SpeechToText (ON_DEVICE)"
                        onChanged()
                    }
                }

                override fun onFailure(errorType: SttError) {
                    Log.i(TAG, "Pleos STT init failed: $errorType")
                    main.post {
                        sttBackend = "음성 듣기 없음 (Pleos STT 초기화 실패: $errorType)"
                        onChanged()
                    }
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "Pleos STT unavailable", t)
            sttBackend = "음성 듣기 없음 (Pleos STT 없음)"
        }
    }

    /**
     * 문장을 읽는다. [then]은 읽기가 끝난 뒤(끝을 알 수 없으면 길이에 맞춰 잠시 뒤) 부른다.
     * 읽는 중에 듣기를 켜면 자기 목소리를 듣게 되므로, 듣기는 [then]에서 시작한다.
     */
    fun speak(text: String, then: (() -> Unit)? = null) {
        lastSpoken = text
        afterSpeech = then
        val tts = pleosTts
        if (pleosTtsReady && tts != null) {
            try {
                tts.speak(text, true)
                // onDone이 오지 않는 경우에 대비한 보험
                if (then != null) main.postDelayed({ runAfterSpeech() }, estimateMs(text) + 1_500)
                return
            } catch (t: Throwable) {
                Log.i(TAG, "Pleos TTS speak failed: ${t.javaClass.simpleName}")
            }
        }
        speakWithAndroid(text)
    }

    private fun speakWithAndroid(text: String) {
        if (androidTts == null) {
            pendingFallbackText = text
            startAndroidTts("Pleos TTS 재생 실패")
        } else if (androidTtsReady) {
            runCatching { androidTts?.speak(text, AndroidTts.QUEUE_FLUSH, null, "anbu") }
        }
        if (afterSpeech != null) main.postDelayed({ runAfterSpeech() }, estimateMs(text))
    }

    private fun runAfterSpeech() {
        val f = afterSpeech ?: return
        afterSpeech = null
        f()
    }

    fun stopSpeaking() {
        afterSpeech = null
        runCatching { pleosTts?.stop() }
        runCatching { androidTts?.stop() }
    }

    /**
     * 듣기 시작. 인식 중간 결과도 [onResult]로 전달하므로 "보내지 마"를 말이 끝나기 전에 잡을 수 있다.
     * @return 듣기를 시작했으면 true
     */
    fun listen(timeoutMs: Long, onResult: (text: String, completed: Boolean) -> Unit): Boolean {
        val stt = pleosStt
        if (!pleosSttReady || stt == null) return false
        return try {
            onHeard = onResult
            stt.request()
            listening = true
            onChanged()
            main.postDelayed({ stopListening() }, timeoutMs)
            true
        } catch (t: Throwable) {
            Log.i(TAG, "Pleos STT request failed: ${t.javaClass.simpleName}")
            listening = false
            false
        }
    }

    fun stopListening() {
        onHeard = null
        if (listening) runCatching { pleosStt?.stop() }
        listening = false
        onChanged()
    }

    fun release() {
        runCatching { pleosTts?.removeEventListener(ttsEvents) }
        runCatching { pleosTts?.release() }
        runCatching { pleosStt?.removeListener(sttResults) }
        runCatching { pleosStt?.release() }
        runCatching { androidTts?.shutdown() }
    }

    private fun estimateMs(text: String): Long = 600L + text.length * 170L

    private companion object {
        const val TAG = "AnbuVoice"
    }
}
