package com.relay.app.notifications

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.relay.app.model.MessageType

/**
 * Morse code as vibration timings (ITU timing): dot = 1 unit, dash = 3 units, gap inside a
 * letter = 1 unit, gap between letters = 3 units, gap between words = 7 units.
 * Output is Android's waveform format: [wait, on, off, on, off, ...], starting with a 0 wait.
 * Pure logic, unit tested.
 */
object MorsePattern {
    private val CODE = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.",
        'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.",
        'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-", 'U' to "..-",
        'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--", 'Z' to "--..",
    )

    fun timings(text: String, unitMs: Long, repeats: Int = 1): LongArray {
        val out = mutableListOf(0L)
        repeat(repeats) { r ->
            if (r > 0) out[out.lastIndex] = 7 * unitMs // word gap between repeats
            text.uppercase().filter { it in CODE }.forEachIndexed { li, letter ->
                if (li > 0) out[out.lastIndex] = 3 * unitMs // letter gap
                CODE.getValue(letter).forEach { symbol ->
                    out += if (symbol == '.') unitMs else 3 * unitMs // vibrate
                    out += unitMs // gap inside the letter
                }
            }
        }
        out.removeAt(out.lastIndex) // no trailing pause
        return out.toLongArray()
    }
}

/** Vibration pattern per message type, so people can tell what arrived without looking. */
object VibrationPatterns {
    /** ··· ––– ··· twice (about 9 s). */
    val SOS: LongArray = MorsePattern.timings("SOS", unitMs = 150, repeats = 2)

    /** Three long pulses. */
    val URGENT: LongArray = longArrayOf(0, 600, 250, 600, 250, 600)

    /** Three short taps. */
    val COORDINATION: LongArray = longArrayOf(0, 120, 120, 120, 120, 120)

    /** Two short taps. */
    val NORMAL: LongArray = longArrayOf(0, 120, 120, 120)

    fun forType(type: MessageType): LongArray = when (type) {
        MessageType.SOS -> SOS
        MessageType.URGENT -> URGENT
        MessageType.COORDINATION -> COORDINATION
        MessageType.NORMAL -> NORMAL
    }
}

/**
 * Plays the vibration for an incoming message. SOS uses the ALARM usage so it gets through
 * silent mode on most phones; other types use the normal notification usage. A newer vibration
 * replaces an older one, except that an SOS is never cut short by a normal message.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    @Volatile private var sosPlayingUntil = 0L

    fun isSupported(): Boolean = vibrator?.hasVibrator() == true

    fun forIncoming(type: MessageType) {
        val now = System.currentTimeMillis()
        if (type != MessageType.SOS && now < sosPlayingUntil) return // never interrupt an SOS
        val pattern = VibrationPatterns.forType(type)
        if (type == MessageType.SOS) sosPlayingUntil = now + pattern.sum()
        play(pattern, alarm = type == MessageType.SOS)
    }

    /** "Feel it" button in Settings. */
    fun preview(type: MessageType) = play(VibrationPatterns.forType(type), alarm = type == MessageType.SOS)

    private fun play(pattern: LongArray, alarm: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            val effect = VibrationEffect.createWaveform(pattern, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val usage = if (alarm) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION
                v.vibrate(effect, VibrationAttributes.createForUsage(usage))
            } else {
                val usage = if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION
                @Suppress("DEPRECATION")
                v.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
            }
        }
    }
}
