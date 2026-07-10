package com.tapinvaders.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlin.random.Random

/**
 * The space sweeper's voice. Preferred source: pre-generated fish.audio
 * S2.1-Pro MP3s (voice model 1864d40339ae4dbabf832f844c8d1d6f) baked by
 * tools/generate_tts.py into assets/tts/<clipId>.mp3.
 *
 * CRITICAL PERFORMANCE RULE — no speech synthesis at run time, ever.
 * Android TTS runs in a separate service process; synthesizing a line pegs
 * this device's little cores and stutters the GL thread no matter which of
 * OUR threads asks for it. So the Android-TTS fallback is baked ONCE on
 * first boot: every line missing a fish clip is rendered to a WAV in
 * filesDir/ttsfb/, then the TTS engine is shut down for good. During play,
 * only cached files are played (MediaPlayer, on a dedicated voice thread).
 *
 * A phrase id may map to several variant lines (a JSON array) — one is
 * picked at random per utterance so frequent lines don't repeat.
 */
class Voice(private val context: Context) {

    private val TAG = "TapInvadersVoice"

    @Volatile var volume = 1.0f

    /** True while a line is actually sounding — lets Sfx duck around it. */
    @Volatile var isSpeaking = false
        private set

    private val phrases = HashMap<String, List<String>>()
    private val queue = ArrayDeque<String>()          // resolved clipIds; voice thread only
    private var player: MediaPlayer? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val rng = Random(System.nanoTime())
    private val fbDir: File by lazy { File(context.filesDir, "ttsfb").apply { mkdirs() } }
    private var baker: TextToSpeech? = null

    fun load() {
        thread = HandlerThread("tapmeteors-voice").apply { start() }
        handler = Handler(thread!!.looper)
        handler?.post {
            loadPhrases()
            bakeMissingFallbacks()
        }
    }

    private fun loadPhrases() {
        runCatching {
            val txt = context.assets.open("phrases.json").bufferedReader().use { it.readText() }
            val o = JSONObject(txt)
            for (k in o.keys()) {
                val v = o.get(k)
                phrases[k] = if (v is JSONArray) List(v.length()) { i -> v.getString(i) } else listOf(v.toString())
            }
        }.onFailure { Log.e(TAG, "phrases.json", it) }
    }

    /** clipId for a phrase id + variant index ("id" for single, "id_n" for variants). */
    private fun clipId(id: String, idx: Int, count: Int) = if (count > 1) "${id}_$idx" else id

    private fun hasAsset(clipId: String): Boolean =
        runCatching { context.assets.openFd("tts/$clipId.mp3").use { }; true }.getOrDefault(false)

    private fun fallbackFile(clipId: String) = File(fbDir, "$clipId.wav")

    // ------------------------------------------------- one-time fallback bake

    private fun bakeMissingFallbacks() {
        val jobs = ArrayList<Pair<String, String>>() // clipId -> text
        for ((id, variants) in phrases) {
            for (i in variants.indices) {
                val cid = clipId(id, i, variants.size)
                if (hasAsset(cid)) continue
                val f = fallbackFile(cid)
                if (f.exists() && f.length() > 44) continue
                jobs.add(cid to variants[i])
            }
        }
        if (jobs.isEmpty()) return
        Log.i(TAG, "baking ${jobs.size} fallback lines (first boot only)")
        baker = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) { baker = null; return@TextToSpeech }
            handler?.post {
                val t = baker ?: return@post
                t.language = Locale.UK
                t.setPitch(0.78f)          // fallback melancholy
                t.setSpeechRate(0.92f)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        handler?.post {
                            utteranceId?.let {
                                val tmp = File(fbDir, "$it.wav.tmp")
                                if (tmp.exists()) tmp.renameTo(fallbackFile(it))
                            }
                        }
                        // Breathe between jobs: even the one-time bake must not
                        // sustain CPU pressure if the player starts immediately.
                        handler?.postDelayed({ bakeNext() }, 250)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler?.post { utteranceId?.let { File(fbDir, "$it.wav.tmp").delete() } }
                        handler?.postDelayed({ bakeNext() }, 250)
                    }
                })
                bakeQueue.addAll(jobs)
                bakeNext()
            }
        }
    }

    private val bakeQueue = ArrayDeque<Pair<String, String>>()

    private fun bakeNext() {
        val t = baker ?: return
        val job = bakeQueue.pollFirst()
        if (job == null) {
            runCatching { t.shutdown() }   // engine gone for good: zero runtime synthesis
            baker = null
            Log.i(TAG, "fallback bake complete")
            return
        }
        val (cid, text) = job
        val tmp = File(fbDir, "$cid.wav.tmp")
        val params = android.os.Bundle()
        @Suppress("DEPRECATION")
        val r = t.synthesizeToFile(text, params, tmp, cid)
        if (r != TextToSpeech.SUCCESS) handler?.post { bakeNext() }
    }

    // --------------------------------------------------------------- playback

    /** Called from the render thread — returns instantly (just posts). */
    fun say(id: String, urgent: Boolean = false) {
        if (volume <= 0.01f) return
        val h = handler ?: return
        h.post { sayOnThread(id, urgent) }
    }

    private fun sayOnThread(id: String, urgent: Boolean) {
        val variants = phrases[id] ?: return
        if (variants.isEmpty()) return
        val idx = if (variants.size > 1) rng.nextInt(variants.size) else 0
        val cid = clipId(id, idx, variants.size)
        if (urgent) {
            queue.clear()
            stopCurrent()
            queue.add(cid)
        } else {
            // One pending mutter at most; the sweeper doesn't backlog complaints.
            if (isSpeaking || queue.isNotEmpty()) return
            queue.add(cid)
        }
        pump()
    }

    private fun pump() {
        if (isSpeaking) return
        val cid = queue.pollFirst() ?: return
        // Cached sources only. If neither exists (first-boot bake still running),
        // the line is silently skipped — never synthesized on the spot.
        if (hasAsset(cid)) {
            runCatching { context.assets.openFd("tts/$cid.mp3") }.getOrNull()?.let { playFd(it); return }
        }
        val f = fallbackFile(cid)
        if (f.exists()) playFile(f)
    }

    private fun playFd(fd: android.content.res.AssetFileDescriptor) {
        startPlayer { mp -> mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length); fd.close() }
    }

    private fun playFile(f: File) {
        startPlayer { mp -> mp.setDataSource(f.absolutePath) }
    }

    private fun startPlayer(source: (MediaPlayer) -> Unit) {
        runCatching {
            stopPlayer()
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            source(mp)
            mp.setVolume(volume, volume)
            mp.setOnCompletionListener { isSpeaking = false; stopPlayer(); pump() }
            mp.setOnErrorListener { _, _, _ -> isSpeaking = false; stopPlayer(); pump(); true }
            mp.prepare()   // local file header parse, on the voice thread
            mp.start()
            isSpeaking = true
            player = mp
        }.onFailure { isSpeaking = false; Log.w(TAG, "clip failed", it) }
    }

    private fun stopCurrent() {
        stopPlayer()
        isSpeaking = false
    }

    private fun stopPlayer() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
    }

    fun release() {
        handler?.post {
            stopCurrent()
            runCatching { baker?.shutdown() }
            baker = null
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }
}
