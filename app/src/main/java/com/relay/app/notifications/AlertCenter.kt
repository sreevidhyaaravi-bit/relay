package com.relay.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.relay.app.data.SettingsStore
import com.relay.app.data.db.MessageEntity
import com.relay.app.model.MessageType
import com.relay.app.relayContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Sound alerts for incoming messages:
 *  - SOS alarm: the phone's own alarm sound, looping on the ALARM volume (rings on silent on most
 *    phones), stops by itself after [ALARM_MAX_MS] or when someone taps Stop.
 *  - Read aloud: offline text-to-speech ("SOS from Priya. Trapped in stairwell B.").
 * With both on, an SOS rings for [ALARM_BEFORE_SPEECH_MS], pauses while the message is read, then
 * rings again until stopped. Each feature has its own Settings switch.
 */
class AlertCenter(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val _ringing = MutableStateFlow<MessageEntity?>(null)

    /** The SOS currently ringing (for the in-app Stop banner), or null. */
    val ringing: StateFlow<MessageEntity?> = _ringing

    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var ttsInitDone = false
    private val pendingSpeech = mutableListOf<Triple<String, Boolean, (() -> Unit)?>>()
    private var alarmJob: Job? = null

    fun onIncoming(message: MessageEntity) {
        val s = settings.current
        val sos = message.messageType == MessageType.SOS
        val speak = s.readAloud && (sos || !s.readAloudSosOnly)
        scope.launch(Dispatchers.Main) {
            if (sos && s.sosAlarm) {
                startAlarm(message, speakAfter = speak)
            } else if (speak) {
                speak(speechFor(message), loud = sos)
            }
        }
    }

    /** Stops the alarm and any speech. */
    fun stop() {
        scope.launch(Dispatchers.Main) {
            alarmJob?.cancel()
            alarmJob = null
            releasePlayer()
            tts?.stop()
            _ringing.value = null
        }
    }

    /** Settings "Test" buttons. */
    fun testAlarm() = scope.launch(Dispatchers.Main) {
        alarmJob?.cancel()
        alarmJob = scope.launch(Dispatchers.Main) {
            if (startPlayer()) {
                delay(4_000L)
                releasePlayer()
            }
        }
    }

    fun testVoice() = speak("This is how RELAY reads an S O S. S O S from Phone A. Trapped in stairwell B.", loud = true)

    // ------------------------------------------------------------------ alarm

    private fun startAlarm(message: MessageEntity, speakAfter: Boolean) {
        alarmJob?.cancel()
        _ringing.value = message
        alarmJob = scope.launch(Dispatchers.Main) {
            if (!startPlayer()) {
                // No alarm sound on this phone: at least read it aloud if allowed.
                if (speakAfter) speak(speechFor(message), loud = true)
                _ringing.value = null
                return@launch
            }
            val startedAt = System.currentTimeMillis()
            if (speakAfter) {
                delay(ALARM_BEFORE_SPEECH_MS)
                player?.pause()
                speak(speechFor(message), loud = true) { scope.launch(Dispatchers.Main) { if (_ringing.value != null) player?.start() } }
            }
            delay((ALARM_MAX_MS - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L))
            releasePlayer()
            _ringing.value = null
        }
    }

    private fun startPlayer(): Boolean {
        releasePlayer()
        val uri: Uri = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
            .firstNotNullOfOrNull { type -> runCatching { RingtoneManager.getDefaultUri(type) }.getOrNull() }
            ?: return false
        return runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
            true
        }.getOrElse {
            releasePlayer()
            false
        }
    }

    private fun releasePlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    // ------------------------------------------------------------------ speech

    /** Starts the speech engine early so the first message is read without delay. */
    fun warmUp() {
        if (tts == null && settings.current.readAloud) scope.launch(Dispatchers.Main) { initTts() }
    }

    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            ttsInitDone = true
            if (ttsReady) {
                val lang = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale.ENGLISH)
            }
            val queued = synchronized(pendingSpeech) { pendingSpeech.toList().also { pendingSpeech.clear() } }
            queued.forEach { (text, loud, done) -> if (ttsReady) say(text, loud, done) else done?.invoke() }
        }
    }

    private fun speak(text: String, loud: Boolean, onDone: (() -> Unit)? = null) {
        if (tts == null) {
            synchronized(pendingSpeech) { pendingSpeech += Triple(text, loud, onDone) }
            initTts()
            return
        }
        if (!ttsReady) {
            if (ttsInitDone) onDone?.invoke() // no speech engine on this phone: skip, never block
            else synchronized(pendingSpeech) { pendingSpeech += Triple(text, loud, onDone) }
            return
        }
        say(text, loud, onDone)
    }

    private fun say(text: String, loud: Boolean, onDone: (() -> Unit)?) {
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        val id = "relay-${System.nanoTime()}"
        if (onDone != null) {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) onDone()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id) onDone()
                }
            })
        }
        engine.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
    }

    companion object {
        const val ALARM_MAX_MS = 60_000L
        const val ALARM_BEFORE_SPEECH_MS = 8_000L

        /** What the phone says. Emoji are removed (speech engines read them out literally). */
        fun speechFor(m: MessageEntity): String {
            val body = m.body.replace(Regex("[^\\p{L}\\p{N}\\p{P}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
            val who = m.senderName.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim()
            val prefix = when (m.messageType) {
                MessageType.SOS -> "S O S from $who."
                MessageType.URGENT -> "Urgent message from $who."
                else -> "Message from $who."
            }
            val place = m.locationLabel?.takeIf { it.isNotBlank() }?.let { " Location: $it." }
                ?: if (m.latitude != null) " Location attached." else ""
            return "$prefix $body.$place".replace("..", ".")
        }
    }
}

/** "Stop alarm" action on the SOS notification. */
class AlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.relayContainer.alerts.stop()
    }
}
