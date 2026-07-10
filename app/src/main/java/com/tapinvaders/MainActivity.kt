package com.tapinvaders

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.tapinvaders.audio.Sfx
import com.tapinvaders.audio.Voice
import com.tapinvaders.engine.Game
import com.tapinvaders.engine.GameHost
import com.tapinvaders.gl.GLRenderer
import kotlin.math.abs
import kotlin.math.max

/**
 * TapInvaders. Two gestures, no settings menu:
 *  - HOLD-AND-DRAG = movement: the cannon moves only while the finger is on
 *    the pad past a small dead-zone, and STOPS THE INSTANT it lifts. Drag
 *    back through center to reverse without lifting. On the title /
 *    game-over screens a swipe navigates instead. (Raw dx sign is inverted
 *    vs the physical gesture on this hardware — the established mapping.)
 *  - TAP (arrives as a KEY on the glasses) = fire / select / restart.
 */
class MainActivity : Activity(), GameHost {

    private lateinit var store: SettingsStore
    private lateinit var sfx: Sfx
    private lateinit var voice: Voice
    private lateinit var game: Game
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GLRenderer

    // De-dupe one physical press that may arrive as both KEY and touch.
    private var lastAction = 0L
    private var downX = 0f
    private var downY = 0f
    private var maxDisp = 0f      // farthest the finger strayed (tap classifier)
    private var heldDir = 0       // movement currently held by this touch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        sfx = Sfx(this).also { it.loadAsync() }
        voice = Voice(this).also { it.load() }
        sfx.duckProvider = { voice.isSpeaking } // effects duck while the sweeper talks
        game = Game(store, this)
        renderer = GLRenderer(game).also { it.sbs = store.sbs }

        glView = object : GLSurfaceView(this) {}.apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        game.boot()
    }

    // ------------------------------------------------------------ GameHost

    override fun sfx(id: Int, pitch: Float, vol: Float) = sfx.play(id, pitch, vol)
    override fun startUfoLoop() = sfx.startUfoLoop()
    override fun stopUfoLoop() = sfx.stopUfoLoop()
    override fun say(id: String, urgent: Boolean) = voice.say(id, urgent)

    // --------------------------------------------------------------- input

    private fun act(run: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAction < 25) return // KEY+touch echo of one physical press
        lastAction = now
        glView.queueEvent(run)
    }

    private fun tap() = act { game.tap() }
    private fun hold(dir: Int) {
        if (dir == heldDir) return
        heldDir = dir
        glView.queueEvent { game.holdMove(dir) }
    }
    private fun select(d: Int) = act { game.select(d) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                if (event.action == KeyEvent.ACTION_UP) tap()
                return true
            }
            // Keys mirror the pad: move while held, stop on release.
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) hold(-1)
                if (event.action == KeyEvent.ACTION_UP) hold(0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) hold(1)
                if (event.action == KeyEvent.ACTION_UP) hold(0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_UP) select(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_UP) select(1); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Ignore the left temple volume pad.
        if (ev.device?.name?.contains("cyttsp6", ignoreCase = true) == true) return true
        val dead = max(16f, 0.02f * resources.displayMetrics.widthPixels)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                maxDisp = 0f
                heldDir = 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                maxDisp = max(maxDisp, max(abs(dx), abs(dy)))
                // Live movement while the finger is down: direction from the
                // current pull (inverted sign per this pad), dead-zone centered
                // on the touch-down point. Dragging back through center stops;
                // through to the other side reverses — all without lifting.
                if (abs(dx) >= dead && abs(dx) >= abs(dy)) hold(if (dx < 0) -1 else 1)
                else hold(0)
            }
            MotionEvent.ACTION_UP -> {
                // Finger off = full stop, immediately.
                hold(0)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (maxDisp < dead) { tap(); return true }
                // A real swipe still navigates the menus.
                if (abs(dy) > abs(dx)) select(if (dy < 0) -1 else 1)
                else act { game.select(if (dx < 0) -1 else 1) }
            }
            MotionEvent.ACTION_CANCEL -> hold(0)
        }
        return true
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView.onResume()
    }

    override fun onPause() {
        sfx.stopUfoLoop()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sfx.release()
        voice.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
