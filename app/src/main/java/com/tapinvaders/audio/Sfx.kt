package com.tapinvaders.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesized SFX bank for TapInvaders (no audio binaries ship). The star is
 * the four-note descending march bass — BASS1..BASS4 — played one note per
 * rack step, so the tempo (and menace) rises as the formation thins. The UFO
 * gets the classic fast warble as a loop.
 */
class Sfx(private val context: Context) {

    companion object {
        const val BASS1 = 0        // the march: four descending thumps
        const val BASS2 = 1
        const val BASS3 = 2
        const val BASS4 = 3
        const val FIRE = 4
        const val INV_DIE = 5
        const val INV_FIRE = 6
        const val SHIELD_HIT = 7
        const val PLAYER_DIE = 8
        const val UFO_LOOP = 9
        const val UFO_DIE = 10
        const val WARP = 11
        const val WAVE = 12
        const val CLEAR = 13
        const val LIFE = 14
        const val SPAWN = 15
        const val START = 16
        const val GAMEOVER = 17
        const val HISCORE = 18
        const val PWR_DROP = 19
        const val PWR_GET = 20
        const val PWR_END = 21
        const val MOVE = 22
        private const val COUNT = 23
        private const val RATE = 22050
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = IntArray(COUNT)
    @Volatile private var loaded = false
    // Kept under the voice so the sweeper always reads; ducked further while speaking.
    @Volatile var volume = 0.6f
    /** Lets the host ask "is the voice speaking right now?" to duck around it. */
    @Volatile var duckProvider: (() -> Boolean)? = null
    private var ufoStream = 0
    private val rng = Random(5)

    // All SoundPool binder calls happen on this thread, never the GL thread.
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    fun loadAsync() {
        thread = HandlerThread("tapinvaders-sfx").apply { start() }
        handler = Handler(thread!!.looper)
        handler?.post {
            runCatching {
                val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
                // The four march notes: A1 G1 F#1 F1-ish, felt more than heard.
                ids[BASS1] = load(dir, "b1", synthBass(110f))
                ids[BASS2] = load(dir, "b2", synthBass(98f))
                ids[BASS3] = load(dir, "b3", synthBass(87.3f))
                ids[BASS4] = load(dir, "b4", synthBass(82.4f))
                ids[FIRE] = load(dir, "fire", buf(80) { t -> (saw(1500f - 900f * t, t) + 0.2f * noise()) * exp(-t * 30f) * 0.5f })
                ids[INV_DIE] = load(dir, "idie", buf(160) { t ->
                    val crush = if ((t * 46f).toInt() % 2 == 0) 1f else 0.5f
                    ((noise() * 0.5f + sq(420f - 260f * t, t) * 0.5f) * crush) * exp(-t * 14f)
                })
                ids[INV_FIRE] = load(dir, "ifire", buf(140) { t -> saw(300f + 500f * t, t) * exp(-t * 16f) * 0.4f })
                ids[SHIELD_HIT] = load(dir, "shld", buf(120) { t -> (noise() * 0.5f + sine(150f, t) * 0.5f) * exp(-t * 20f) })
                ids[PLAYER_DIE] = load(dir, "pdie", buf(850) { t ->
                    val f = 230f - t * 150f
                    val crush = if ((t * 28f).toInt() % 2 == 0) 1f else 0.4f
                    ((noise() * 0.55f + saw(f, t) * 0.45f) * crush) * exp(-t * 3.4f)
                })
                // Classic cruiser warble: fast flutter, loopable.
                ids[UFO_LOOP] = load(dir, "uloop", buf(1000) { t ->
                    val f = 980f + 160f * sin(2f * PI.toFloat() * 16f * t)
                    (sine(f, t) * 0.5f + sine(f * 0.5f, t) * 0.2f) * 0.8f
                })
                ids[UFO_DIE] = load(dir, "udie", buf(700) { t ->
                    val f = 900f - t * 750f
                    (saw(f, t) * 0.5f + sine(f * 0.5f, t) * 0.3f + noise() * 0.25f * exp(-t * 8f)) * exp(-t * 4f)
                })
                ids[WARP] = load(dir, "warp", buf(500) { t ->
                    sine(1600f - 1200f * t, t) * exp(-t * 4f) * 0.4f + sine(200f + 800f * t, t) * exp(-t * 5f) * 0.3f
                })
                ids[WAVE] = load(dir, "wave", arpeggio(intArrayOf(392, 523, 659, 784), 85, 0.7f))
                ids[CLEAR] = load(dir, "clear", arpeggio(intArrayOf(523, 659, 784, 1046, 1318), 80, 0.7f))
                ids[LIFE] = load(dir, "life", arpeggio(intArrayOf(784, 1046, 1318, 1568, 2093), 65, 0.7f))
                ids[SPAWN] = load(dir, "spawn", buf(320) { t -> sine(280f + 900f * t, t) * exp(-t * 6f) * 0.5f })
                ids[START] = load(dir, "start", arpeggio(intArrayOf(330, 440, 554, 659, 880), 75, 0.7f))
                ids[GAMEOVER] = load(dir, "over", buf(1000) { t ->
                    val f = if (t < 0.4f) 330f - t * 180f else 260f - (t - 0.4f) * 140f
                    (saw(f, t) * 0.4f + sine(f * 0.5f, t) * 0.4f) * exp(-t * 2f)
                })
                ids[HISCORE] = load(dir, "hi", arpeggio(intArrayOf(523, 659, 784, 1046, 1318, 1568, 2093), 80, 0.7f))
                ids[PWR_DROP] = load(dir, "pdrop", buf(420) { t ->
                    sine(500f + 1100f * t, t) * exp(-t * 5f) * 0.35f + sine(750f + 1100f * t, t) * exp(-t * 6f) * 0.2f
                })
                ids[PWR_GET] = load(dir, "pget", arpeggio(intArrayOf(659, 880, 1174, 1568), 55, 0.75f))
                ids[PWR_END] = load(dir, "pend", buf(260) { t -> sine(700f - 380f * t, t) * exp(-t * 8f) * 0.4f })
                ids[MOVE] = load(dir, "move", buf(35) { t -> sine(950f, t) * exp(-t * 60f) * 0.5f })
                loaded = true
            }
        }
    }

    /** The heartbeat of the game: a soft low knock with a felt-mallet attack. */
    private fun synthBass(f: Float) = buf(140) { t ->
        val body = sq(f, t) * 0.35f + sine(f, t) * 0.5f
        val knock = noise() * exp(-t * 260f) * 0.2f
        (body * exp(-t * 16f) + knock)
    }

    /** Safe from any thread; the actual SoundPool call runs on the sfx thread. */
    fun play(id: Int, pitch: Float = 1f, vol: Float = 1f) {
        if (!loaded || id < 0 || id >= COUNT) return
        handler?.post {
            val s = ids[id]
            if (s == 0) return@post
            val duckMul = if (duckProvider?.invoke() == true) 0.4f else 1f
            val v = (volume * vol * duckMul).coerceIn(0f, 1f)
            if (v <= 0f) return@post
            pool.play(s, v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
        }
    }

    fun startUfoLoop() {
        handler?.post {
            if (!loaded || ufoStream != 0) return@post
            val duckMul = if (duckProvider?.invoke() == true) 0.4f else 1f
            val v = (volume * 0.35f * duckMul).coerceIn(0f, 1f)
            ufoStream = pool.play(ids[UFO_LOOP], v, v, 0, -1, 1f)
        }
    }

    fun stopUfoLoop() {
        handler?.post {
            if (ufoStream != 0) { pool.stop(ufoStream); ufoStream = 0 }
        }
    }

    fun release() {
        handler?.post { runCatching { pool.release() } }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // ------------------------------------------------------------ synthesis

    private fun buf(ms: Int, gen: (Float) -> Float): ShortArray {
        val n = RATE * ms / 1000
        return ShortArray(n) { i -> (gen(i.toFloat() / RATE).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }

    private fun sine(f: Float, t: Float) = sin(2.0 * PI * f * t).toFloat()
    private fun saw(f: Float, t: Float): Float { val p = (f * t) % 1f; return 2f * p - 1f }
    private fun sq(f: Float, t: Float) = if ((f * t) % 1f < 0.5f) 1f else -1f
    private fun noise() = rng.nextFloat() * 2f - 1f

    private fun arpeggio(freqs: IntArray, noteMs: Int, amp: Float): ShortArray {
        val total = noteMs * freqs.size + 220
        return buf(total) { t ->
            var v = 0f
            for ((i, f) in freqs.withIndex()) {
                val start = i * noteMs / 1000f
                if (t >= start) {
                    val lt = t - start
                    v += (sine(f.toFloat(), lt) + 0.3f * sine(f * 2f, lt)) * exp(-lt * 5.5f) * amp * 0.4f
                }
            }
            v
        }
    }

    // ------------------------------------------------------------- wav

    private fun DataOutputStream.wInt(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF) }
    private fun DataOutputStream.wShort(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }

    private fun load(dir: File, name: String, pcm: ShortArray): Int {
        val f = File(dir, "$name.wav")
        val dataLen = pcm.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use { o ->
            o.writeBytes("RIFF"); o.wInt(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); o.wInt(16); o.wShort(1); o.wShort(1)
            o.wInt(RATE); o.wInt(RATE * 2); o.wShort(2); o.wShort(16)
            o.writeBytes("data"); o.wInt(dataLen)
            for (s in pcm) o.wShort(s.toInt())
        }
        return pool.load(f.absolutePath, 1)
    }
}
