package com.tapinvaders.engine

import com.tapinvaders.SettingsStore
import com.tapinvaders.audio.Sfx
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class GameState { TITLE, PLAYING, LIFE_LOST, WAVE_CLEAR, GAME_OVER }

/** The three faces of the invasion, chosen on the title screen. */
enum class Mode(val label: String, val blurb: String) {
    CLASSIC("CLASSIC", "THE 1978 SHIFT, BY THE BOOK"),
    REMIX("REMIX", "RAINBOW RACKS, POWER-UPS, A CHATTY SWEEPER"),
    FPS("FPS 3D", "STAND ON THE FIRING LINE"),
}

interface GameHost {
    fun sfx(id: Int, pitch: Float = 1f, vol: Float = 1f)
    fun startUfoLoop()
    fun stopUfoLoop()
    /** The space sweeper mutters a pre-generated line (audio only). */
    fun say(id: String, urgent: Boolean = false)
}

class Bolt(var x: Float, var z: Float, val pierce: Boolean) { var dead = false }
class InvMissile(var x: Float, var z: Float, val spawnZ: Float, val spawnY: Float) { var dead = false }
class PowerDrop(var x: Float, var z: Float, val type: Int) { var dead = false }

class Particle {
    var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var life = 0f; var maxLife = 1f; var hue = 0f
}

/**
 * TapInvaders — the space sweeper's second shift. One simulation, three
 * projections: CLASSIC plays it straight, REMIX turns on the color and the
 * commentary, FPS 3D puts you on the firing line looking up at the rack.
 *
 * Logical space is shared by all modes: x centered on 0, the rack marching
 * far away at -z and stepping toward the player line at +z. The renderer
 * decides what that looks like.
 */
class Game(private val store: SettingsStore, private val host: GameHost) {

    companion object {
        const val ROWS = 5
        const val COLS = 11
        const val SX = 2.2f              // rack column spacing
        const val SZ = 1.7f              // rack row spacing
        const val STEP_X = 0.55f         // lateral march per beat
        const val BOLT_SPEED = 26f
        const val PLAYER_SPEED = 10f
        const val EXTRA_LIFE_EVERY = 5000
        const val RESPAWN_INVULN = 2.2f

        // Power-ups (REMIX only), dropped by dying invaders.
        const val PWR_RAPID = 0
        const val PWR_TRIPLE = 1
        const val PWR_SHIELD = 2
        const val PWR_PIERCE = 3
        const val PWR_SLOW = 4
        const val POWER_DURATION = 10f
        val POWER_NAMES = arrayOf("RAPID FIRE!", "TRIPLE SHOT!", "SHIELD!", "PIERCING BOLTS!", "TIME WARP!")

        // Shields: 4 bunkers of 5x3 cells (classic + remix).
        const val BUNKERS = 4
        const val BW = 5
        const val BH = 3
        const val CELL = 0.8f
    }

    var state = GameState.TITLE; private set
    var mode = Mode.CLASSIC; private set
    var selMode = 0; private set             // title-screen highlight
    var time = 0f; private set

    // Viewport-derived logical bounds for the 2D modes (set by the renderer,
    // GL thread, same thread as update). FPS uses its own fixed stage.
    private var hw2d = 22f
    private var hh2d = 20f
    fun setHalfSize(w: Float, h: Float) {
        if (abs(w - hw2d) < 0.01f && abs(h - hh2d) < 0.01f) return
        hw2d = w; hh2d = h
    }

    // Stage geometry, fixed at wave start from the mode.
    var halfW = 22f; private set
    var playerZ = 16f; private set
    var ufoZ = -17f; private set
    private var rackStartZ = -13f
    private var zStep = 0.85f
    private var marchBound = 9f
    private var loseZ = 12f
    var shieldZ = 11f; private set
    val isFps get() = mode == Mode.FPS

    // --- rack ---
    val alive = BooleanArray(ROWS * COLS)
    var aliveCount = 0; private set
    var rackX = 0f; private set
    var rackZ = -13f; private set
    var marchFrame = 0; private set          // 2-frame sprite animation, flips per beat
    private var marchDir = 1
    private var beatT = 0.8f
    private var beatNote = 0
    private var missileT = 2f

    fun colX(c: Int) = rackX + (c - (COLS - 1) / 2f) * SX
    fun rowZ(r: Int) = rackZ + r * SZ
    /** 0 squid (30 pts, top), 1 crab (20), 2 octopus (10). */
    fun rowType(r: Int) = when (r) { 0 -> 0; 1, 2 -> 1; else -> 2 }

    // --- player ---
    var px = 0f; private set
    var moveDir = 0; private set
    var invuln = 0f; private set
    var playerAlive = false; private set
    private var fireCooldown = 0f

    // --- projectiles & pickups ---
    val bolts = ArrayList<Bolt>()
    val missiles = ArrayList<InvMissile>()
    val drops = ArrayList<PowerDrop>()

    // --- shields ---
    val shieldHp = IntArray(BUNKERS * BW * BH)
    fun bunkerX(b: Int) = halfW * (-0.57f + 0.38f * b)
    fun cellPos(b: Int, i: Int, j: Int, out: FloatArray) {
        out[0] = bunkerX(b) + (i - (BW - 1) / 2f) * CELL
        out[1] = shieldZ + (j - (BH - 1) / 2f) * CELL
    }

    // --- UFO ---
    var ufoX = 0f; private set
    var ufoDir = 0f; private set             // 0 = absent
    var ufoT = 0f; private set
    private var ufoTimer = 14f

    // --- power-ups (remix) ---
    var activePower = -1; private set
    var powerT = 0f; private set

    // --- score / lives / waves ---
    var wave = 1; private set
    var score = 0; private set
    var lives = 3; private set
    var highScore = 0; private set
    private var nextLifeAt = EXTRA_LIFE_EVERY
    private var stateT = 0f
    private var saidCloseCall = false

    // --- flourish ---
    var message: String? = null; private set
    var messageHue = 0f; private set
    private var messageUntil = 0f
    private val clearCries = arrayOf(
        "RACK: SWEPT", "PLANET SAVED. AGAIN.", "FORMATION FILED",
        "MOSTLY HARMLESS NOW", "PAPERWORK PENDING", "ANOTHER DAY, ANOTHER ARMADA",
        "DON'T PANIC. IT'S HANDLED.",
    )
    private var clearIdx = 0
    private var deathAlt = false

    val particles = ArrayList<Particle>()
    private val pool = ArrayDeque<Particle>()
    private val rng = Random(System.nanoTime())
    private val cellScratch = FloatArray(2)

    private val voiceFull get() = mode == Mode.REMIX
    private val voiceSparse get() = mode != Mode.CLASSIC

    fun boot() {
        highScore = store.highScore(0)
        state = GameState.TITLE
    }

    // ---------------------------------------------------------------- input

    /** Title: cycle the mode highlight. Game over: back to the title. */
    fun select(d: Int) {
        if (state == GameState.GAME_OVER) { toTitle(); return }
        if (state != GameState.TITLE) return
        selMode = (selMode + d + Mode.entries.size) % Mode.entries.size
        highScore = store.highScore(selMode)
        host.sfx(Sfx.MOVE, 1.2f, 0.6f)
    }

    /**
     * Latched movement: swipe once to start sliding, swipe against the motion
     * to stop, swipe again to go the other way. dir: -1 left, +1 right.
     */
    fun move(dir: Int) {
        when (state) {
            GameState.TITLE -> select(dir)
            GameState.GAME_OVER -> toTitle()
            GameState.PLAYING, GameState.WAVE_CLEAR -> {
                if (!playerAlive) return
                moveDir = when (moveDir) {
                    dir -> dir           // already going that way
                    0 -> dir             // start
                    else -> 0            // counter-swipe = stop
                }
                host.sfx(Sfx.MOVE, if (dir < 0) 0.9f else 1.1f, 0.4f)
            }
            else -> {}
        }
    }

    fun tap() {
        when (state) {
            GameState.TITLE -> startGame(Mode.entries[selMode])
            GameState.GAME_OVER -> startGame(mode)
            GameState.PLAYING, GameState.WAVE_CLEAR -> fire()
            else -> {}
        }
    }

    private fun fire() {
        if (!playerAlive || fireCooldown > 0f) return
        val cap = when {
            mode == Mode.CLASSIC -> 1
            activePower == PWR_RAPID -> 5
            mode == Mode.FPS -> 2
            else -> 3
        }
        var mine = 0
        for (i in 0 until bolts.size) if (!bolts[i].dead) mine++
        if (mine >= cap) return
        val pierce = activePower == PWR_PIERCE
        if (activePower == PWR_TRIPLE) {
            bolts.add(Bolt(px - 1.1f, playerZ - 1.2f, pierce))
            bolts.add(Bolt(px, playerZ - 1.2f, pierce))
            bolts.add(Bolt(px + 1.1f, playerZ - 1.2f, pierce))
        } else {
            bolts.add(Bolt(px, playerZ - 1.2f, pierce))
        }
        fireCooldown = if (activePower == PWR_RAPID) 0.07f else 0.14f
        host.sfx(Sfx.FIRE, if (pierce) 0.8f else 1f, 0.75f)
    }

    // ----------------------------------------------------------------- flow

    private fun startGame(m: Mode) {
        mode = m
        selMode = m.ordinal
        score = 0
        lives = 3
        wave = 1
        nextLifeAt = EXTRA_LIFE_EVERY
        activePower = -1
        highScore = store.highScore(m.ordinal)
        store.games++
        host.sfx(Sfx.START)
        if (voiceFull) host.say("start")
        else if (mode == Mode.FPS) host.say("warp_in")
        beginWave()
        spawnPlayer()
    }

    private fun beginWave() {
        // Stage geometry per mode. FPS uses a fixed deep stage; the 2D modes
        // hug the measured screen so the cannon's rails ARE the screen edges.
        if (isFps) {
            halfW = 15.5f   // strafe rails must reach the rack's widest column
            playerZ = 18f
            rackStartZ = -30f + (wave - 1) * 1.2f
            zStep = 1.3f
            marchBound = 3f
            loseZ = 9f
            ufoZ = -26f
            shieldZ = -999f     // no bunkers on the firing line
        } else {
            halfW = hw2d - 1f
            playerZ = hh2d - 3.5f
            rackStartZ = -(hh2d - 7f) + (wave - 1) * 0.9f
            zStep = 0.85f
            marchBound = halfW - (COLS - 1) / 2f * SX - 1.2f
            loseZ = playerZ - 3f
            ufoZ = -(hh2d - 2.8f)
            shieldZ = playerZ - 4.6f
        }
        rackStartZ = rackStartZ.coerceAtMost(loseZ - (ROWS + 2) * SZ)

        java.util.Arrays.fill(alive, true)
        aliveCount = ROWS * COLS
        rackX = 0f
        rackZ = rackStartZ
        marchDir = 1
        marchFrame = 0
        beatT = 0.5f
        beatNote = 0
        missileT = 2.2f
        bolts.clear(); missiles.clear(); drops.clear()
        ufoDir = 0f
        ufoTimer = 12f + rng.nextFloat() * 10f
        saidCloseCall = false

        // Fresh bunkers each wave (classic tradition), full hp.
        java.util.Arrays.fill(shieldHp, if (isFps) 0 else 2)

        state = GameState.PLAYING
        flash("WAVE $wave", 2f)
        host.sfx(Sfx.WAVE)
        if (voiceFull && wave > 1 && rng.nextFloat() < 0.35f) host.say("invasion")
        store.setBestWave(mode.ordinal, wave)
    }

    private fun spawnPlayer() {
        px = 0f
        moveDir = 0
        invuln = RESPAWN_INVULN
        playerAlive = true
        fireCooldown = 0.2f
        host.sfx(Sfx.SPAWN)
    }

    private fun flash(text: String, secs: Float) {
        message = text
        messageHue = rng.nextFloat()
        messageUntil = time + secs
    }

    fun toTitle() {
        state = GameState.TITLE
        host.stopUfoLoop()
        host.sfx(Sfx.MOVE, 0.8f)
    }

    // --------------------------------------------------------------- update

    fun update(dt: Float) {
        time += dt
        if (message != null && time > messageUntil) message = null
        updateParticles(dt)

        when (state) {
            GameState.TITLE, GameState.GAME_OVER -> {}
            GameState.PLAYING -> {
                stepWorld(dt)
                if (state == GameState.PLAYING && aliveCount == 0) {
                    score += 100 * wave
                    checkExtraLife()
                    state = GameState.WAVE_CLEAR
                    stateT = 2.4f
                    host.stopUfoLoop(); ufoDir = 0f
                    flash(clearCries[clearIdx % clearCries.size], 2.2f)
                    if (voiceFull) host.say("wave_${(clearIdx % 7) + 1}")
                    clearIdx++
                    host.sfx(Sfx.CLEAR)
                }
            }
            GameState.LIFE_LOST -> {
                stateT -= dt
                if (stateT <= 0f) { spawnPlayer(); state = GameState.PLAYING }
            }
            GameState.WAVE_CLEAR -> {
                stepPlayer(dt)
                stepBolts(dt)
                stateT -= dt
                if (stateT <= 0f) { wave++; beginWave() }
            }
        }
    }

    private fun stepWorld(dt: Float) {
        // active power (remix)
        if (activePower >= 0) {
            powerT -= dt
            if (powerT <= 0f) { activePower = -1; host.sfx(Sfx.PWR_END) }
        }
        val slow = if (activePower == PWR_SLOW) 0.55f else 1f

        stepPlayer(dt)
        stepRack(dt, slow)
        stepMissiles(dt, slow)
        stepBolts(dt)
        stepDrops(dt)
        stepUfo(dt, slow)
    }

    private fun stepPlayer(dt: Float) {
        if (!playerAlive) return
        invuln = maxOf(0f, invuln - dt)
        if (activePower == PWR_SHIELD) invuln = maxOf(invuln, 0.05f)
        fireCooldown = maxOf(0f, fireCooldown - dt)
        if (moveDir != 0) {
            px += moveDir * PLAYER_SPEED * dt
            val bound = halfW - 1.4f
            if (px <= -bound) { px = -bound; moveDir = 0 }
            if (px >= bound) { px = bound; moveDir = 0 }
        }
    }

    /** The rack marches on a beat that quickens as it thins — the classic pulse. */
    private fun stepRack(dt: Float, slow: Float) {
        beatT -= dt * slow
        if (beatT > 0f) return
        val aliveFrac = aliveCount / (ROWS * COLS).toFloat()
        val base = (0.82f - (wave - 1) * 0.04f).coerceAtLeast(0.55f)
        beatT = (0.07f + base * aliveFrac * aliveFrac.coerceAtLeast(0.12f)).coerceAtLeast(0.07f)

        marchFrame = marchFrame xor 1
        host.sfx(Sfx.BASS1 + beatNote, 1f, 0.8f)
        beatNote = (beatNote + 1) and 3

        val next = rackX + marchDir * STEP_X
        if (next > marchBound || next < -marchBound) {
            rackZ += zStep
            marchDir = -marchDir
            chewShields()
            checkOverrun()
        } else {
            rackX = next
        }

        // The sweeper notices them looming (remix only, once a wave).
        if (!saidCloseCall && voiceFull && frontRowZ() > loseZ - 4f * SZ) {
            saidCloseCall = true
            host.say("close_call")
        }

        // A random alive column takes a shot, from its lowest survivor.
        missileT -= beatT // scale firing with the march tempo
        if (missileT <= 0f && missiles.size < (2 + wave / 2).coerceAtMost(6)) {
            missileT = (1.35f - wave * 0.07f).coerceAtLeast(0.5f)
            fireMissile()
        }
    }

    private fun frontRowZ(): Float {
        for (r in ROWS - 1 downTo 0) for (c in 0 until COLS) if (alive[r * COLS + c]) return rowZ(r)
        return -999f
    }

    private fun checkOverrun() {
        if (frontRowZ() >= loseZ) {
            // They made it past the broom. The shift is over.
            lives = 0
            gameOver()
        }
    }

    private fun fireMissile() {
        // pick a random alive column
        var tries = 0
        while (tries++ < 20) {
            val c = rng.nextInt(COLS)
            for (r in ROWS - 1 downTo 0) {
                if (alive[r * COLS + c]) {
                    val y = 1.5f + (ROWS - 1 - r) * 1.4f // FPS render height at spawn
                    missiles.add(InvMissile(colX(c), rowZ(r) + 1f, rowZ(r) + 1f, y))
                    host.sfx(Sfx.INV_FIRE, 0.9f + rng.nextFloat() * 0.2f, 0.5f)
                    return
                }
            }
        }
    }

    private fun stepMissiles(dt: Float, slow: Float) {
        val speed = (6.2f + wave * 0.45f).coerceAtMost(12f) * slow
        var i = missiles.size - 1
        while (i >= 0) {
            val m = missiles[i]
            m.z += speed * dt
            if (m.z > playerZ + 3f) { missiles.removeAt(i); i--; continue }
            // vs shields
            if (hitShield(m.x, m.z)) { missiles.removeAt(i); i--; continue }
            // vs player
            if (playerAlive && invuln <= 0f && abs(m.x - px) < 1.15f && abs(m.z - playerZ) < 1.0f) {
                missiles.removeAt(i)
                playerHit()
                i--; continue
            }
            i--
        }
    }

    private fun stepBolts(dt: Float) {
        var i = bolts.size - 1
        while (i >= 0) {
            val b = bolts[i]
            b.z -= BOLT_SPEED * dt
            var consumed = b.z < rackStartZ - 8f
            // vs shields (your own shots chew your own cover — tradition)
            if (!consumed && !b.pierce && hitShield(b.x, b.z)) consumed = true
            // vs invaders
            if (!consumed || b.pierce) {
                for (r in ROWS - 1 downTo 0) {
                    var hit = false
                    for (c in 0 until COLS) {
                        val idx = r * COLS + c
                        if (!alive[idx]) continue
                        if (abs(b.x - colX(c)) < 1.05f && abs(b.z - rowZ(r)) < 0.85f) {
                            killInvader(r, c)
                            if (!b.pierce) consumed = true
                            hit = true
                            break
                        }
                    }
                    if (hit && !b.pierce) break
                }
            }
            // vs UFO
            if (!consumed && ufoDir != 0f && abs(b.x - ufoX) < 1.9f && abs(b.z - ufoZ) < 1.3f) {
                ufoKilled()
                consumed = true
            }
            if (consumed) bolts.removeAt(i)
            i--
        }
    }

    private fun killInvader(r: Int, c: Int) {
        alive[r * COLS + c] = false
        aliveCount--
        val pts = when (rowType(r)) { 0 -> 30; 1 -> 20; else -> 10 }
        addScore(pts)
        val x = colX(c); val z = rowZ(r)
        explode(x, if (isFps) 1.5f + (ROWS - 1 - r) * 1.4f else 1.0f, z,
            if (mode == Mode.REMIX) rng.nextFloat() else 0.33f,
            if (mode == Mode.REMIX) 26 else 10, 4f)
        host.sfx(Sfx.INV_DIE, 0.85f + rowType(r) * 0.18f)
        if (mode == Mode.REMIX && rng.nextFloat() < 0.09f) {
            drops.add(PowerDrop(x, z, rng.nextInt(5)))
            host.sfx(Sfx.PWR_DROP)
        }
    }

    private fun stepDrops(dt: Float) {
        var i = drops.size - 1
        while (i >= 0) {
            val d = drops[i]
            d.z += 5.5f * dt
            if (d.z > playerZ + 2.5f) { drops.removeAt(i); i--; continue }
            if (playerAlive && abs(d.x - px) < 1.6f && abs(d.z - playerZ) < 1.3f) {
                activePower = d.type
                powerT = POWER_DURATION
                flash(POWER_NAMES[d.type], 2f)
                host.sfx(Sfx.PWR_GET)
                if (voiceFull) host.say("power_up")
                drops.removeAt(i)
            }
            i--
        }
    }

    private fun stepUfo(dt: Float, slow: Float) {
        if (ufoDir == 0f) {
            ufoTimer -= dt
            if (ufoTimer <= 0f && aliveCount > 4) {
                ufoDir = if (rng.nextBoolean()) 1f else -1f
                ufoX = -ufoDir * (halfW + 2f)
                ufoT = 0f
                host.sfx(Sfx.WARP)
                host.startUfoLoop()
                if (voiceFull) host.say("saucer_big")
                flash("IMPERIAL CRUISER", 1.4f)
            }
            return
        }
        ufoT += dt
        ufoX += ufoDir * 5f * slow * dt
        if (ufoX * ufoDir > halfW + 2.5f) {
            ufoDir = 0f
            ufoTimer = 14f + rng.nextFloat() * 12f
            host.stopUfoLoop()
        }
    }

    private fun ufoKilled() {
        val bonus = 50 * (1 + rng.nextInt(6))
        addScore(bonus)
        explode(ufoX, if (isFps) 9f else 1.4f, ufoZ, rng.nextFloat(), 46, 6f)
        flash("CRUISER SWEPT +$bonus", 1.8f)
        host.sfx(Sfx.UFO_DIE)
        host.stopUfoLoop()
        if (voiceFull) host.say("saucer_down")
        ufoDir = 0f
        ufoTimer = 16f + rng.nextFloat() * 12f
    }

    // Shield cells absorb anything that crosses their band.
    private fun hitShield(x: Float, z: Float): Boolean {
        if (isFps) return false
        if (abs(z - shieldZ) > BH * CELL) return false
        for (b in 0 until BUNKERS) {
            if (abs(x - bunkerX(b)) > BW * CELL) continue
            for (i in 0 until BW) for (j in 0 until BH) {
                val idx = (b * BW + i) * BH + j
                if (shieldHp[idx] <= 0) continue
                cellPos(b, i, j, cellScratch)
                if (abs(x - cellScratch[0]) < CELL * 0.55f && abs(z - cellScratch[1]) < CELL * 0.55f) {
                    shieldHp[idx]--
                    host.sfx(Sfx.SHIELD_HIT, 0.9f + rng.nextFloat() * 0.2f, 0.6f)
                    return true
                }
            }
        }
        return false
    }

    /** Invaders grinding through the bunker line erase what they touch. */
    private fun chewShields() {
        if (isFps) return
        val fz = frontRowZ()
        if (abs(fz - shieldZ) > SZ) return
        for (c in 0 until COLS) {
            var occupied = false
            for (r in 0 until ROWS) if (alive[r * COLS + c]) { occupied = true; break }
            if (!occupied) continue
            val ix = colX(c)
            for (b in 0 until BUNKERS) for (i in 0 until BW) for (j in 0 until BH) {
                val idx = (b * BW + i) * BH + j
                if (shieldHp[idx] <= 0) continue
                cellPos(b, i, j, cellScratch)
                if (abs(ix - cellScratch[0]) < 1.3f) shieldHp[idx] = 0
            }
        }
    }

    private fun playerHit() {
        playerAlive = false
        moveDir = 0
        lives--
        explode(px, if (isFps) 1.2f else 0.8f, playerZ, 0.55f, 60, 7f)
        host.sfx(Sfx.PLAYER_DIE)
        if (lives <= 0) {
            gameOver()
        } else {
            state = GameState.LIFE_LOST
            stateT = 1.7f
            missiles.clear()
            deathAlt = !deathAlt
            if (voiceSparse) host.say(if (deathAlt) "death_1" else "death_2", urgent = true)
        }
    }

    private fun gameOver() {
        playerAlive = false
        state = GameState.GAME_OVER
        host.stopUfoLoop(); ufoDir = 0f
        host.sfx(Sfx.GAMEOVER)
        store.setHighScore(mode.ordinal, score)
        if (score >= highScore && score > 0) {
            highScore = score
            flash("NEW HIGH SCORE!", 3.5f)
            host.sfx(Sfx.HISCORE)
            if (voiceSparse) host.say("hiscore", urgent = true)
        } else if (voiceSparse) host.say("game_over", urgent = true)
    }

    private fun addScore(n: Int) {
        score += n
        checkExtraLife()
        if (score > highScore) { highScore = score; store.setHighScore(mode.ordinal, score) }
    }

    private fun checkExtraLife() {
        while (score >= nextLifeAt) {
            nextLifeAt += EXTRA_LIFE_EVERY
            lives++
            flash("1UP!", 1.8f)
            host.sfx(Sfx.LIFE)
            if (voiceFull) host.say("one_up")
        }
    }

    // ----------------------------------------------------------- particles

    private fun explode(x: Float, y: Float, z: Float, hue: Float, count: Int, power: Float) {
        repeat(count) {
            val p = pool.removeFirstOrNull() ?: Particle()
            p.x = x; p.y = y; p.z = z
            val a = rng.nextFloat() * 6.2832f
            val sp = rng.nextFloat() * power
            p.vx = cos(a) * sp; p.vz = sin(a) * sp; p.vy = 1f + rng.nextFloat() * 4f
            p.life = 0.5f + rng.nextFloat() * 0.6f; p.maxLife = p.life
            p.hue = (hue + rng.nextFloat() * 0.2f) % 1f
            particles.add(p)
        }
    }

    private fun updateParticles(dt: Float) {
        var i = particles.size - 1
        while (i >= 0) {
            val p = particles[i]
            p.life -= dt
            if (p.life <= 0f) { particles.removeAt(i); pool.addLast(p) }
            else {
                p.vy -= 7f * dt
                p.x += p.vx * dt
                p.z += p.vz * dt
                p.y = maxOf(0f, p.y + p.vy * dt)
                p.vx *= 0.97f; p.vz *= 0.97f
            }
            i--
        }
    }
}
