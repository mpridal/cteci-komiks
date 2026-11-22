package io.github.mpridal.ctecikomiks.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.SoundPool

/** Krátké zvuky z assets/sfx. Vrací délku zvuku, aby šlo ignorovat ozvěnu z reproduktoru. */
class Sfx(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        .build()
    private val ids = mutableMapOf<String, Int>()

    init {
        for (name in DURATIONS.keys) {
            runCatching { context.assets.openFd("sfx/$name.wav").use { ids[name] = pool.load(it, 1) } }
        }
    }

    /** Přehraje zvuk; vrátí jeho délku v ms (0, když neexistuje). */
    fun play(name: String): Long {
        val id = ids[name] ?: return 0
        pool.play(id, 1f, 1f, 1, 0, 1f)
        return DURATIONS[name] ?: 0
    }

    /** Hraje zvuk z reproduktoru (ne do sluchátek)? Pak je potřeba ignorovat ozvěnu. */
    fun speakerActive(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none {
        it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
    }

    fun release() = pool.release()

    companion object {
        val DURATIONS = mapOf(
            "boing" to 350L, "splash" to 600L, "pop" to 120L, "sparkle" to 460L, "cink" to 360L, "fanfare" to 1050L,
        )

        /** Prototyp: zvuk podle vrstvy, na kterou značka míří (v MVP přijde audio z balíčku). */
        fun forLayer(layer: String?): String = when (layer) {
            "splash" -> "splash"
            "shell" -> "sparkle"
            "croc", "cookie" -> "pop"
            else -> "boing"
        }
    }
}
