package com.stellarite.stellardash

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.util.Collections

class Sfx(context: Context) {

    enum class Sound { JUMP, DOUBLE, SHARD, DEATH, WARN, DASH, SLIDE, BOUNCE, BREAK }

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val loaded: MutableSet<Int> = Collections.synchronizedSet(HashSet())
    private val ids = IntArray(Sound.values().size)

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded.add(sampleId)
        }
        ids[Sound.JUMP.ordinal] = pool.load(context, R.raw.jump, 1)
        ids[Sound.DOUBLE.ordinal] = pool.load(context, R.raw.double_jump, 1)
        ids[Sound.SHARD.ordinal] = pool.load(context, R.raw.shard, 1)
        ids[Sound.DEATH.ordinal] = pool.load(context, R.raw.death, 1)
        ids[Sound.WARN.ordinal] = pool.load(context, R.raw.warn, 1)
        ids[Sound.DASH.ordinal] = pool.load(context, R.raw.dash, 1)
        ids[Sound.SLIDE.ordinal] = pool.load(context, R.raw.slide, 1)
        ids[Sound.BOUNCE.ordinal] = pool.load(context, R.raw.bounce, 1)
        ids[Sound.BREAK.ordinal] = pool.load(context, R.raw.crack, 1)
    }

    fun play(sound: Sound) {
        val id = ids[sound.ordinal]
        if (id != 0 && loaded.contains(id)) {
            val vol = if (sound == Sound.WARN) 0.6f else 0.9f
            pool.play(id, vol, vol, 1, 0, 1f)
        }
    }

    fun release() {
        pool.release()
    }
}
