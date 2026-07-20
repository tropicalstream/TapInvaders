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
class InvMissile(var x: Float, var z: Float, val spawnZ: Float, val spawnY: Float) {
    var dead = false
    var vx = 0f              // aimed/fanned shots drift sideways as they fall
}
class PowerDrop(var x: Float, var z: Float, val type: Int) { var dead = false }

/** A rack invader gone rogue: loops off its slot, strafes the cannon
 *  Galaga-style, and — if it lives — swings home to march again. */
class Diver(val idx: Int, val type: Int, var x: Float, var z: Float) {
    val sx = x; val sz = z
    var t = 0f
    var weave = 0f
    var fireT = 0.7f
    val loopDir = if (x > 0f) -1f else 1f
}

/** The minelayer cruiser's parting gifts: armed, drifting, shootable. */
class Mine(var x: Float, var z: Float) { var age = 0f }

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

        // The imperial cruiser's arsenal (REMIX): a different weapon each
        // wave, cycling — and each full cycle it grows a little hastier.
        const val WPN_SCATTER = 0    // fans of falling fire mid-crossing
        const val WPN_TRACTOR = 1    // drags the cannon toward its shadow
        const val WPN_NECRO = 2      // resurrects the fallen rack, slot by slot
        const val WPN_MAGNET = 3     // bends and swallows bolts; pierce flies true
        const val WPN_MINE = 4       // seeds drifting proximity mines
        const val WPN_LANCE = 5      // telegraphs your column, then burns it
        val WPN_NAMES = arrayOf(
            "SCATTERBOMB", "TRACTOR BEAM", "NECRO RAY",
            "MAGNET MAW", "MINELAYER", "COLUMN LANCE")
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
    var roll = 0f; private set               // FPS ship banks into a strafe
    var muzzle = 0f; private set             // FPS muzzle flash timer
    private var incomingT = 0f               // FPS proximity-dread ticker

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

    // --- the cruiser's arsenal (remix) ---
    var cruiserWeapon = -1; private set
    private var wpnT = 0f
    val mines = ArrayList<Mine>()
    var lanceX = 0f; private set
    var lanceT = 0f; private set             // telegraph countdown
    var strikeT = 0f; private set            // beam afterglow
    val tractorActive get() =
        cruiserWeapon == WPN_TRACTOR && ufoDir != 0f && playerAlive && abs(px - ufoX) < 8f

    // --- galaga divers (remix, wave 6 onward) ---
    val divers = ArrayList<Diver>()
    val divingMask = BooleanArray(ROWS * COLS)
    private var diveT = 6f

    /** Desk-test hook: start at a chosen wave (adb --ei wave N). */
    var debugWave = 0

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
     * Hold-to-move: the cannon moves ONLY while the finger is on the pad
     * (dir -1/+1 while held past the dead-zone, 0 re-centers or lifts).
     * The Activity calls this continuously during a touch and with 0 on
     * finger-up — release means an immediate, dead stop.
     */
    fun holdMove(dir: Int) {
        if (state != GameState.PLAYING && state != GameState.WAVE_CLEAR) return
        if (!playerAlive) { moveDir = 0; return }
        if (dir != 0 && moveDir == 0) host.sfx(Sfx.MOVE, if (dir < 0) 0.9f else 1.1f, 0.35f)
        moveDir = dir
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
        muzzle = 0.09f
        host.sfx(Sfx.FIRE, if (pierce) 0.8f else 1f, 0.75f)
    }

    // ----------------------------------------------------------------- flow

    private fun startGame(m: Mode) {
        mode = m
        selMode = m.ordinal
        score = 0
        lives = 3
        wave = if (debugWave > 0) debugWave else 1
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
        divers.clear(); java.util.Arrays.fill(divingMask, false)
        mines.clear(); lanceT = 0f; strikeT = 0f; cruiserWeapon = -1
        diveT = 10f + rng.nextFloat() * 3f
        ufoDir = 0f
        ufoTimer = 12f + rng.nextFloat() * 10f
        if (debugWave > 0) { diveT = 1.2f; ufoTimer = 2.5f }   // desk-test: everything sooner
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
        if (mode == Mode.REMIX) {
            stepDivers(dt, slow)
            stepMines(dt, slow)
            stepLance(dt)
        }
    }

    // ---------------------------------------- galaga divers (remix, wave 6+)

    /** The front survivor of a random column peels off to strafe the cannon. */
    private fun launchDiver() {
        var tries = 0
        while (tries++ < 16) {
            val c = rng.nextInt(COLS)
            for (r in ROWS - 1 downTo 0) {
                val idx = r * COLS + c
                if (!alive[idx]) continue
                if (!divingMask[idx]) {
                    divingMask[idx] = true
                    divers.add(Diver(idx, rowType(r), colX(c), rowZ(r)))
                    host.sfx(Sfx.WARP, 1.5f, 0.5f)
                    return
                }
                break   // the column's front survivor is already out — try another
            }
        }
    }

    private fun stepDivers(dt: Float, slow: Float) {
        if (wave >= 6 && state == GameState.PLAYING) {
            diveT -= dt * slow
            val cap = (1 + (wave - 6) / 2).coerceAtMost(3)
            if (diveT <= 0f && divers.size < cap && aliveCount > divers.size) {
                diveT = 10f + rng.nextFloat() * 3f   // a diver every 10-13 s on average
                launchDiver()
            }
        }
        var i = divers.size - 1
        while (i >= 0) {
            val d = divers[i]
            d.t += dt * slow
            if (d.t < 0.85f) {
                // The peel-off: a tight loop flourish above its slot.
                val a = d.t / 0.85f * 6.2832f
                d.x = d.sx + d.loopDir * (1f - cos(a)) * 1.6f
                d.z = d.sz - sin(a) * 1.9f
            } else {
                // The swoop: weaving descent that leans toward the cannon.
                d.weave += dt * slow * (5f + wave * 0.1f)
                d.z += (7.5f + wave * 0.35f).coerceAtMost(13f) * slow * dt
                d.x += (sin(d.weave) * 4.2f + (px - d.x) * 0.55f) * slow * dt
                d.x = d.x.coerceIn(-halfW + 1f, halfW - 1f)
                d.fireT -= dt * slow
                if (d.fireT <= 0f && d.z < playerZ - 4f) {
                    d.fireT = 0.75f + rng.nextFloat() * 0.5f
                    val m = InvMissile(d.x, d.z + 0.8f, d.z + 0.8f, 1.5f)
                    m.vx = ((px - d.x) / 3f).coerceIn(-4f, 4f)
                    missiles.add(m)
                    host.sfx(Sfx.INV_FIRE, 1.25f, 0.5f)
                }
            }
            // Body-checks the cannon on the way past.
            if (playerAlive && invuln <= 0f && abs(d.x - px) < 1.35f && abs(d.z - playerZ) < 1.1f) {
                diverKilled(i)
                playerHit()
                i--; continue
            }
            // Past the line: swing wide and warp home to march again.
            if (d.z > playerZ + 2.6f) {
                divingMask[d.idx] = false
                explode(colX(d.idx % COLS), 0.4f, rowZ(d.idx / COLS), 0.55f, 8, 2f)
                divers.removeAt(i)
            }
            i--
        }
    }

    /** A diver picked off mid-swoop pays double — the Galaga bargain. */
    private fun diverKilled(i: Int) {
        val d = divers.removeAt(i)
        divingMask[d.idx] = false
        alive[d.idx] = false
        aliveCount--
        addScore(2 * when (d.type) { 0 -> 30; 1 -> 20; else -> 10 })
        explode(d.x, 1f, d.z, rng.nextFloat(), 30, 5f)
        host.sfx(Sfx.INV_DIE, 1.3f)
        if (rng.nextFloat() < 0.18f) {
            drops.add(PowerDrop(d.x, d.z, rng.nextInt(5)))
            host.sfx(Sfx.PWR_DROP)
        }
    }

    private fun recallDivers() {
        for (d in divers) divingMask[d.idx] = false
        divers.clear()
    }

    // ------------------------------------------- the cruiser's arsenal aides

    private fun stepMines(dt: Float, slow: Float) {
        var i = mines.size - 1
        while (i >= 0) {
            val m = mines[i]
            m.age += dt * slow
            m.z += 2.1f * slow * dt
            if (m.age > 8f || m.z > playerZ + 2f) {
                explode(m.x, 0.8f, m.z, 0.08f, 10, 3f)   // fizzles out
                mines.removeAt(i); i--; continue
            }
            // Armed and near: burst into a shrapnel fan.
            if (m.age > 0.6f && playerAlive &&
                abs(m.x - px) < 3.4f && abs(m.z - playerZ) < 4.2f
            ) {
                for (k in -1..1) {
                    val sm = InvMissile(m.x, m.z, m.z, 1.2f)
                    sm.vx = k * 3.2f
                    missiles.add(sm)
                }
                explode(m.x, 0.8f, m.z, 0.05f, 22, 5f)
                host.sfx(Sfx.UFO_DIE, 1.4f, 0.7f)
                mines.removeAt(i); i--; continue
            }
            i--
        }
    }

    private fun stepLance(dt: Float) {
        strikeT = maxOf(0f, strikeT - dt)
        if (lanceT <= 0f) return
        lanceT -= dt
        if (lanceT > 0f) return
        // The burn: everything down the marked column, bunker cells included.
        strikeT = 0.18f
        host.sfx(Sfx.UFO_DIE, 0.6f, 0.9f)
        if (playerAlive && invuln <= 0f && abs(px - lanceX) < 1.25f) playerHit()
        for (b in 0 until BUNKERS) for (i2 in 0 until BW) for (j in 0 until BH) {
            val idx = (b * BW + i2) * BH + j
            if (shieldHp[idx] <= 0) continue
            cellPos(b, i2, j, cellScratch)
            if (abs(cellScratch[0] - lanceX) < 0.9f) shieldHp[idx] = 0
        }
        explode(lanceX, 0.8f, playerZ - 4f, 0.62f, 18, 4f)
    }

    private fun stepPlayer(dt: Float) {
        if (!playerAlive) return
        invuln = maxOf(0f, invuln - dt)
        if (activePower == PWR_SHIELD) invuln = maxOf(invuln, 0.05f)
        fireCooldown = maxOf(0f, fireCooldown - dt)
        muzzle = maxOf(0f, muzzle - dt)
        if (moveDir != 0) {
            px += moveDir * PLAYER_SPEED * dt
            val bound = halfW - 1.4f
            px = px.coerceIn(-bound, bound)
        }
        // FPS ship banks into the strafe and levels out on release.
        val targetRoll = -moveDir * 0.42f
        roll += (targetRoll - roll) * (1f - kotlin.math.exp(-9f * dt))
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
                val idx = r * COLS + c
                if (alive[idx] && !divingMask[idx]) {   // a diving slot fires mid-swoop, not from the rack
                    val y = 1.5f + (ROWS - 1 - r) * 1.4f // FPS render height at spawn
                    missiles.add(InvMissile(colX(c), rowZ(r) + 1f, rowZ(r) + 1f, y))
                    host.sfx(Sfx.INV_FIRE, 0.9f + rng.nextFloat() * 0.2f, 0.5f)
                    return
                }
            }
        }
    }

    /** How far along its dive a missile is: 0 at spawn, 1 at the player line. */
    fun missileProx(m: InvMissile): Float =
        (1f - (playerZ - m.z) / (playerZ - m.spawnZ)).coerceIn(0f, 1f)

    private fun stepMissiles(dt: Float, slow: Float) {
        val speed = (6.2f + wave * 0.45f).coerceAtMost(12f) * slow
        var nearest = -1f
        var i = missiles.size - 1
        while (i >= 0) {
            val m = missiles[i]
            m.z += speed * dt
            if (m.vx != 0f) {
                m.x += m.vx * slow * dt
                if (abs(m.x) > halfW - 0.5f) m.vx = -m.vx   // fan shots ricochet off the rails
            }
            if (m.z > playerZ + 3f) { missiles.removeAt(i); i--; continue }
            // vs shields
            if (hitShield(m.x, m.z)) { missiles.removeAt(i); i--; continue }
            // vs player
            if (playerAlive && invuln <= 0f && abs(m.x - px) < 1.15f && abs(m.z - playerZ) < 1.0f) {
                missiles.removeAt(i)
                playerHit()
                i--; continue
            }
            if (isFps) nearest = maxOf(nearest, missileProx(m))
            i--
        }

        // FPS dread: a blip that quickens and rises as the closest fireball
        // bears down — the sound of incoming before it fills the screen.
        if (isFps && nearest >= 0f && playerAlive) {
            incomingT -= dt
            if (incomingT <= 0f) {
                incomingT = 0.55f - 0.46f * nearest
                host.sfx(Sfx.INCOMING, 0.75f + nearest * 0.9f, 0.25f + 0.55f * nearest)
            }
        }
    }

    private fun stepBolts(dt: Float) {
        var i = bolts.size - 1
        while (i >= 0) {
            val b = bolts[i]
            b.z -= BOLT_SPEED * dt
            var consumed = b.z < rackStartZ - 8f
            // MAGNET MAW: the cruiser bends ordinary bolts into its mouth and
            // swallows them whole. Piercing bolts fly true — its one weakness.
            if (!consumed && !b.pierce && cruiserWeapon == WPN_MAGNET && ufoDir != 0f &&
                abs(b.z - ufoZ) < 7f
            ) {
                val dx = ufoX - b.x
                if (abs(dx) < 6f) {
                    b.x += (if (dx > 0) 1f else -1f) * 14f * dt * (1f - abs(dx) / 6f)
                    if (abs(ufoX - b.x) < 1.4f && abs(b.z - ufoZ) < 1.6f) {
                        consumed = true
                        explode(b.x, 1.2f, b.z, 0.9f, 6, 2f)
                        host.sfx(Sfx.SHIELD_HIT, 1.6f, 0.4f)
                    }
                }
            }
            // vs shields (your own shots chew your own cover — tradition)
            if (!consumed && !b.pierce && hitShield(b.x, b.z)) consumed = true
            // vs galaga divers — mid-swoop kills pay double
            if (!consumed && divers.isNotEmpty()) {
                var di = divers.size - 1
                while (di >= 0) {
                    val d = divers[di]
                    if (abs(b.x - d.x) < 1.15f && abs(b.z - d.z) < 0.95f) {
                        diverKilled(di)
                        if (!b.pierce) { consumed = true; break }
                    }
                    di--
                }
            }
            // vs mines — popping one safely pays a little
            if (!consumed && mines.isNotEmpty()) {
                var mi = mines.size - 1
                while (mi >= 0) {
                    val m = mines[mi]
                    if (abs(b.x - m.x) < 1.0f && abs(b.z - m.z) < 1.0f) {
                        mines.removeAt(mi)
                        addScore(25)
                        explode(m.x, 0.8f, m.z, 0.08f, 16, 4f)
                        host.sfx(Sfx.POP, 1.1f)
                        if (!b.pierce) { consumed = true; break }
                    }
                    mi--
                }
            }
            // FPS: enemy fire can be shot down — the fireball is the target.
            if (!consumed && isFps) {
                var mi = missiles.size - 1
                while (mi >= 0) {
                    val m = missiles[mi]
                    val rad = 0.8f + missileProx(m) * 0.9f   // bigger = easier, as it grows
                    if (abs(b.x - m.x) < rad && abs(b.z - m.z) < 1.3f) {
                        missiles.removeAt(mi)
                        addScore(10)
                        explode(m.x, 0.9f + (m.spawnY - 0.9f) * missileProx(m).let { 1f - it },
                            m.z, 0.05f, 14, 3f)
                        host.sfx(Sfx.POP, 0.9f + rng.nextFloat() * 0.2f)
                        if (!b.pierce) { consumed = true; break }
                    }
                    mi--
                }
            }
            // vs invaders
            if (!consumed || b.pierce) {
                for (r in ROWS - 1 downTo 0) {
                    var hit = false
                    for (c in 0 until COLS) {
                        val idx = r * COLS + c
                        if (!alive[idx] || divingMask[idx]) continue   // its body is out diving
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
                // In REMIX the cruiser arrives ARMED — a new trick every wave.
                cruiserWeapon = if (mode == Mode.REMIX && wave >= 2) (wave - 2) % 6 else -1
                wpnT = 1.1f
                if (cruiserWeapon >= 0) flash("CRUISER: ${WPN_NAMES[cruiserWeapon]}", 1.8f)
                else flash("IMPERIAL CRUISER", 1.4f)
            }
            return
        }
        ufoT += dt
        ufoX += ufoDir * 5f * slow * dt
        if (ufoX * ufoDir > halfW + 2.5f) {
            ufoDir = 0f
            ufoTimer = 14f + rng.nextFloat() * 12f
            cruiserWeapon = -1
            lanceT = 0f
            host.stopUfoLoop()
        }

        // The arsenal at work while it crosses. Each completed cycle of six
        // waves returns a familiar weapon, hastier than before.
        if (cruiserWeapon >= 0 && ufoDir != 0f && state == GameState.PLAYING) {
            val haste = 1f + 0.25f * ((wave - 2) / 6)
            wpnT -= dt * slow * haste
            when (cruiserWeapon) {
                WPN_SCATTER -> if (wpnT <= 0f) {
                    wpnT = 1.7f
                    for (k in -2..2) {
                        val m = InvMissile(ufoX, ufoZ + 1f, ufoZ + 1f, 9f)
                        m.vx = k * 1.7f
                        missiles.add(m)
                    }
                    host.sfx(Sfx.INV_FIRE, 0.7f, 0.7f)
                }
                WPN_TRACTOR -> {
                    if (tractorActive && invuln <= 0f) {
                        val pull = if (ufoX > px) 1f else -1f
                        px = (px + pull * 4.8f * slow * dt).coerceIn(-(halfW - 1.4f), halfW - 1.4f)
                        if (wpnT <= 0f) { wpnT = 0.28f; host.sfx(Sfx.INCOMING, 0.6f, 0.35f) }
                    }
                }
                WPN_NECRO -> if (wpnT <= 0f) {
                    wpnT = 2.1f
                    // Breathe a fallen invader back into the rack, deepest first.
                    outer@ for (r in 0 until ROWS) for (c in 0 until COLS) {
                        val idx = r * COLS + c
                        if (!alive[idx]) {
                            alive[idx] = true
                            aliveCount++
                            explode(colX(c), 0.6f, rowZ(r), 0.33f, 16, 3f)
                            host.sfx(Sfx.WARP, 1.7f, 0.6f)
                            break@outer
                        }
                    }
                }
                WPN_MAGNET -> {}   // passive: the maw lives in stepBolts
                WPN_MINE -> if (wpnT <= 0f && mines.size < 4) {
                    wpnT = 1.5f
                    mines.add(Mine(ufoX, ufoZ + 1.6f))
                    host.sfx(Sfx.PWR_DROP, 0.7f, 0.6f)
                }
                WPN_LANCE -> if (wpnT <= 0f && lanceT <= 0f) {
                    wpnT = 2.8f
                    lanceX = px
                    lanceT = 0.95f   // the telegraph: dodge or die
                    host.sfx(Sfx.WARP, 0.5f, 0.7f)
                }
            }
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
            recallDivers()   // the strafers warp home while the broom respawns
            lanceT = 0f
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
