package com.tapinvaders.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.tapinvaders.engine.Game
import com.tapinvaders.engine.GameState
import com.tapinvaders.engine.Mode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * OpenGL ES 3.0 renderer for TapInvaders. One scene, two cameras:
 *
 *  - CLASSIC / REMIX / title: the straight-on 58°-tilted neon table view
 *    (the whole screen is the battlefield; bounds are viewport-derived).
 *  - FPS 3D: a perspective camera standing at the cannon, looking up at the
 *    rack — the formation is a wall of glowing sprites leaning in over you.
 *
 * Additive blend, hue-cycled vector sprites, black = transparent waveguide.
 * On the X3 the frame renders once per eye into side-by-side viewports.
 */
class GLRenderer(private val game: Game) : GLSurfaceView.Renderer {

    var sbs = false

    private var program = 0
    private var aPos = 0; private var aColor = 0
    private var uMVP = 0; private var uPointSize = 0; private var uPoint = 0
    private var width = 1; private var height = 1
    private var lastNanos = 0L

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val ortho = FloatArray(16)
    private val rgb = FloatArray(3)

    private val lines = Batch(24000)
    private val fx = Batch(6000)
    private val hud = Batch(5000)

    // Fixed starfield (hue drifts slowly), generous span for both cameras.
    private val stars: FloatArray = Random(3).let { r ->
        FloatArray(90 * 3) { i ->
            when (i % 3) {
                0 -> r.nextFloat() * 90f - 45f
                1 -> r.nextFloat() * 14f - 7f
                else -> r.nextFloat() * 80f - 48f
            }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram(VERT, FRAG)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aColor = GLES30.glGetAttribLocation(program, "aColor")
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        uPointSize = GLES30.glGetUniformLocation(program, "uPointSize")
        uPoint = GLES30.glGetUniformLocation(program, "uPoint")
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        Matrix.orthoM(ortho, 0, 0f, 640f, 480f, 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now

        val eyes = if (sbs) 2 else 1
        val vw = if (sbs) width / 2 else width
        val aspect = vw.toFloat() / height.toFloat()
        game.setHalfSize(V * aspect, V / TILT_SIN)

        game.update(dt)

        buildScene(); buildHud()

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        val fpsCam = game.mode == Mode.FPS && game.state != GameState.TITLE
        if (fpsCam) {
            Matrix.perspectiveM(proj, 0, 58f, aspect, 0.3f, 240f)
            Matrix.setLookAtM(
                view, 0,
                game.px, 1.7f, game.playerZ + 0.5f,
                game.px, 3.6f, game.playerZ - 22f,
                0f, 1f, 0f
            )
        } else {
            Matrix.orthoM(proj, 0, -V * aspect, V * aspect, -V, V, 1f, 300f)
            Matrix.setLookAtM(view, 0, 0f, CAM_D * TILT_SIN, CAM_D * TILT_COS, 0f, 0f, 0f, 0f, 1f, 0f)
        }
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        for (e in 0 until eyes) {
            GLES30.glViewport(e * vw, 0, vw, height)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            GLES30.glUniform1f(uPoint, 0f)
            lines.draw(GLES30.GL_LINES)
            GLES30.glUniform1f(uPoint, 1f)
            GLES30.glUniform1f(uPointSize, 10f); fx.draw(GLES30.GL_POINTS)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, ortho, 0)
            GLES30.glUniform1f(uPoint, 0f)
            hud.draw(GLES30.GL_LINES)
        }
    }

    // ------------------------------------------------------- scene build

    private fun buildScene() {
        lines.reset(); fx.reset()
        buildStars()
        buildFloor()
        if (game.state == GameState.TITLE) { buildTitleParade(); return }

        buildRack()
        if (!game.isFps) buildShields()
        if (game.playerAlive && !game.isFps) buildCannon()
        buildBolts()
        buildMissiles()
        buildDrops()
        if (game.ufoDir != 0f) buildUfo()
        val ps = game.particles
        for (i in 0 until ps.size) {
            val p = ps[i]
            val k = (p.life / p.maxLife).coerceIn(0f, 1f)
            hsv(p.hue, if (game.mode == Mode.CLASSIC) 0.25f else 1f, 1f)
            fx.v(p.x, p.y, p.z, rgb[0], rgb[1], rgb[2], k)
        }
    }

    private fun buildStars() {
        val h = game.time * 0.03f
        for (i in 0 until stars.size / 3) {
            hsv((h + i * 0.013f) % 1f, 0.45f, 0.8f)
            val tw = 0.3f + 0.28f * sin(game.time * 1.7f + i)
            fx.v(stars[i * 3], stars[i * 3 + 1] - 8f, stars[i * 3 + 2], rgb[0], rgb[1], rgb[2], tw)
        }
    }

    /** Endless constant-brightness floor grid running off every screen edge. */
    private fun buildFloor() {
        hsv((game.time * 0.05f + 0.55f) % 1f, 0.7f, 0.4f)
        val a = 0.14f
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        var gx = -45f
        while (gx <= 45f) { lines.line(gx, 0f, -48f, gx, 0f, 32f, r, g, b, a); gx += 5f }
        var gz = -48f
        while (gz <= 32f) { lines.line(-45f, 0f, gz, 45f, 0f, gz, r, g, b, a); gz += 5f }
    }

    /** Title: three ranks of sprites marching gently, showing off the cast. */
    private fun buildTitleParade() {
        val sway = sin(game.time * 0.9f) * 4f
        val frame = ((game.time * 2f).toInt()) and 1
        for (row in 0 until 3) {
            val segs = sprite(row, frame)
            invaderColor(row, row * 2)
            val z = -8f + row * 3.2f
            for (k in 0 until 6) {
                val x = (k - 2.5f) * 4.2f + sway * (if (row == 1) -0.6f else 1f)
                drawSprite(segs, x, 0.2f, z, 1.15f, rgb[0], rgb[1], rgb[2], 0.9f)
            }
        }
    }

    private fun buildRack() {
        val frame = game.marchFrame
        for (r in 0 until Game.ROWS) {
            val type = game.rowType(r)
            val segs = sprite(type, frame)
            invaderColor(type, r)
            val cr = rgb[0]; val cg = rgb[1]; val cb = rgb[2]
            val baseY = if (game.isFps) 1.5f + (Game.ROWS - 1 - r) * 1.4f else 0.15f
            val scale = if (game.isFps) 0.8f else 0.95f
            for (c in 0 until Game.COLS) {
                if (!game.alive[r * Game.COLS + c]) continue
                drawSprite(segs, game.colX(c), baseY, game.rowZ(r), scale, cr, cg, cb, 0.95f)
            }
        }
    }

    private fun invaderColor(type: Int, row: Int) {
        when (game.mode) {
            Mode.CLASSIC -> { rgb[0] = 0.35f; rgb[1] = 1f; rgb[2] = 0.45f }
            Mode.REMIX -> hsv((row * 0.13f + game.time * 0.1f) % 1f, 0.85f, 1f)
            Mode.FPS -> when (type) {
                0 -> hsv(0.86f, 0.8f, 1f)   // magenta command row
                1 -> hsv(0.52f, 0.8f, 1f)   // cyan mid ranks
                else -> hsv(0.34f, 0.8f, 1f) // green front line
            }
        }
    }

    /** Flat neon sprite standing upright in the x/y plane at depth z. */
    private fun drawSprite(segs: FloatArray, x: Float, baseY: Float, z: Float, s: Float, r: Float, g: Float, b: Float, a: Float) {
        var i = 0
        while (i < segs.size) {
            lines.line(
                x + segs[i] * s, baseY + segs[i + 1] * s, z,
                x + segs[i + 2] * s, baseY + segs[i + 3] * s, z,
                r, g, b, a
            )
            i += 4
        }
    }

    private fun sprite(type: Int, frame: Int): FloatArray = when (type) {
        0 -> if (frame == 0) SQUID_A else SQUID_B
        1 -> if (frame == 0) CRAB_A else CRAB_B
        else -> if (frame == 0) OCTO_A else OCTO_B
    }

    private fun buildCannon() {
        val x = game.px; val z = game.playerZ
        val blink = if (game.invuln > 0f) (0.45f + 0.55f * sin(game.time * 20f)) else 1f
        val r: Float; val g: Float; val b: Float
        if (game.mode == Mode.CLASSIC) { r = 0.9f; g = 1f; b = 0.9f } else { r = 0.35f; g = 0.95f; b = 1f }
        lines.line(x - 1.3f, 0.12f, z, x + 1.3f, 0.12f, z, r, g, b, blink)
        lines.line(x - 1.3f, 0.12f, z, x - 0.7f, 0.75f, z, r, g, b, blink)
        lines.line(x + 1.3f, 0.12f, z, x + 0.7f, 0.75f, z, r, g, b, blink)
        lines.line(x - 0.7f, 0.75f, z, x + 0.7f, 0.75f, z, r, g, b, blink)
        lines.line(x, 0.75f, z, x, 1.5f, z, r, g, b, blink)
        fx.v(x, 1.55f, z, 1f, 1f, 1f, blink)
        if (game.invuln > 0f && game.activePower == Game.PWR_SHIELD) {
            hsv((game.time * 0.5f) % 1f, 0.6f, 1f)
            ring(x, 0.8f, z, 2.1f, 12, rgb[0], rgb[1], rgb[2], 0.4f * blink)
        }
    }

    private fun buildShields() {
        if (game.mode == Mode.CLASSIC) { rgb[0] = 0.3f; rgb[1] = 0.85f; rgb[2] = 0.35f }
        else hsv((game.time * 0.04f + 0.3f) % 1f, 0.6f, 0.9f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val cell = Game.CELL
        val pos = FloatArray(2)
        for (bk in 0 until Game.BUNKERS) {
            for (i in 0 until Game.BW) for (j in 0 until Game.BH) {
                val hp = game.shieldHp[(bk * Game.BW + i) * Game.BH + j]
                if (hp <= 0) continue
                game.cellPos(bk, i, j, pos)
                val a = if (hp >= 2) 0.85f else 0.35f
                val x = pos[0]; val z = pos[1]; val h = cell * 0.85f
                lines.line(x - cell / 2, 0.05f, z, x + cell / 2, 0.05f, z, r, g, b, a)
                lines.line(x - cell / 2, h, z, x + cell / 2, h, z, r, g, b, a)
                lines.line(x - cell / 2, 0.05f, z, x - cell / 2, h, z, r, g, b, a)
                lines.line(x + cell / 2, 0.05f, z, x + cell / 2, h, z, r, g, b, a)
                lines.line(x - cell / 2, 0.05f, z, x + cell / 2, h, z, r, g, b, a * 0.7f)
            }
        }
    }

    private fun buildBolts() {
        val bs = game.bolts
        for (i in 0 until bs.size) {
            val bo = bs[i]
            val r: Float; val g: Float; val b: Float
            if (bo.pierce) { hsv(0.85f, 0.8f, 1f); r = rgb[0]; g = rgb[1]; b = rgb[2] }
            else if (game.mode == Mode.CLASSIC) { r = 1f; g = 1f; b = 0.9f }
            else { r = 0.5f; g = 1f; b = 1f }
            if (game.isFps) {
                val y = 1.2f + (game.playerZ - bo.z) * 0.09f
                lines.line(bo.x, y, bo.z, bo.x, y - 0.35f, bo.z + 1.4f, r, g, b, 0.9f)
                fx.v(bo.x, y, bo.z, r, g, b, 1f)
            } else {
                lines.line(bo.x, 0.9f, bo.z, bo.x, 0.9f, bo.z + 1.1f, r, g, b, 0.9f)
                fx.v(bo.x, 0.9f, bo.z, r, g, b, 1f)
            }
        }
    }

    private fun buildMissiles() {
        val ms = game.missiles
        for (i in 0 until ms.size) {
            val m = ms[i]
            val wig = sin(game.time * 24f + i) * 0.15f
            if (game.mode == Mode.REMIX) hsv((game.time * 0.6f + i * 0.2f) % 1f, 0.85f, 1f)
            else if (game.isFps) { rgb[0] = 1f; rgb[1] = 0.3f; rgb[2] = 0.25f }
            else { rgb[0] = 1f; rgb[1] = 1f; rgb[2] = 0.55f }
            val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
            if (game.isFps) {
                val ratio = ((m.z - game.playerZ) / (m.spawnZ - game.playerZ)).coerceIn(0f, 1f)
                val y = 0.9f + (m.spawnY - 0.9f) * ratio
                lines.line(m.x + wig, y + 0.3f, m.z - 0.9f, m.x - wig, y, m.z, r, g, b, 0.95f)
                fx.v(m.x, y, m.z, r, g, b, 1f)
            } else {
                lines.line(m.x + wig, 0.95f, m.z - 0.45f, m.x - wig, 0.45f, m.z + 0.45f, r, g, b, 0.95f)
                fx.v(m.x, 0.7f, m.z, r, g, b, 0.9f)
            }
        }
    }

    private fun buildDrops() {
        val ds = game.drops
        for (i in 0 until ds.size) {
            val d = ds[i]
            hsv(powerHue(d.type), 0.85f, 1f)
            val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
            val pulse = 0.6f + 0.4f * sin(game.time * 6f + i)
            val rot = game.time * 3f
            val s = 0.6f
            var px0 = d.x + cos(rot) * s; var pz0 = d.z + sin(rot) * s
            for (k in 1..4) {
                val a2 = rot + k * 1.5708f
                val vx = d.x + cos(a2) * s; val vz = d.z + sin(a2) * s
                lines.line(px0, 0.8f, pz0, vx, 0.8f, vz, r, g, b, pulse)
                px0 = vx; pz0 = vz
            }
            fx.v(d.x, 0.8f, d.z, 1f, 1f, 1f, pulse)
        }
    }

    private fun powerHue(type: Int): Float = when (type) {
        Game.PWR_RAPID -> 0.08f
        Game.PWR_TRIPLE -> 0.5f
        Game.PWR_SHIELD -> 0.33f
        Game.PWR_PIERCE -> 0.85f
        else -> 0.72f
    }

    private fun buildUfo() {
        val x = game.ufoX; val z = game.ufoZ
        val y = if (game.isFps) 9f else 1.3f
        val bob = 0.12f * sin(game.ufoT * 5f)
        val sc = if (game.isFps) 1.6f else 1.2f
        if (game.mode == Mode.CLASSIC) { rgb[0] = 1f; rgb[1] = 0.35f; rgb[2] = 0.3f }
        else hsv((game.time * 0.5f) % 1f, 0.9f, 1f)
        ring(x, y + bob, z, 1.7f * sc, 12, rgb[0], rgb[1], rgb[2], 0.95f)
        if (game.mode == Mode.CLASSIC) { rgb[0] = 1f; rgb[1] = 0.5f; rgb[2] = 0.4f }
        else hsv((game.time * 0.5f + 0.33f) % 1f, 0.9f, 1f)
        ring(x, y + 0.55f * sc + bob, z, 0.9f * sc, 10, rgb[0], rgb[1], rgb[2], 0.95f)
        fx.v(x, y + 0.9f * sc + bob, z, 1f, 1f, 1f, 0.8f + 0.2f * sin(game.ufoT * 9f))
    }

    private fun ring(x: Float, y: Float, z: Float, rad: Float, seg: Int, r: Float, g: Float, b: Float, a: Float) {
        var px0 = x + rad; var pz0 = z
        for (i in 1..seg) {
            val ang = i * (6.2832f / seg)
            val vx = x + cos(ang) * rad
            val vz = z + sin(ang) * rad
            lines.line(px0, y, pz0, vx, y, vz, r, g, b, a)
            px0 = vx; pz0 = vz
        }
    }

    // -------------------------------------------------------------- hud

    private val sink = object : StrokeFont.LineSink {
        var cr = 1f; var cg = 1f; var cb = 1f; var ca = 1f
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float) { hud.line(x0, y0, 0f, x1, y1, 0f, cr, cg, cb, ca) }
    }

    private fun text(s: String, cx: Float, y: Float, scale: Float, r: Float, g: Float, b: Float, a: Float = 1f, center: Boolean = true) {
        val x = if (center) cx - StrokeFont.width(s, scale) / 2f else cx
        sink.cr = r; sink.cg = g; sink.cb = b; sink.ca = a
        StrokeFont.draw(s, x, y, scale, sink)
    }

    private fun buildHud() {
        hud.reset()
        val pulse = 0.55f + 0.45f * sin(game.time * 4f)
        hsv(game.time * 0.05f, 0.8f, 1f)
        val hr = rgb[0]; val hg = rgb[1]; val hb = rgb[2]

        when (game.state) {
            GameState.TITLE -> {
                text("TAPINVADERS", 320f, 96f, 4.2f, hr, hg, hb)
                text("THE SWEEPER'S SECOND SHIFT", 320f, 140f, 1.4f, 0.7f, 0.9f, 1f)
                for ((i, m) in Mode.entries.withIndex()) {
                    val sel = i == game.selMode
                    val y = 210f + i * 46f
                    if (sel) {
                        hsv((game.time * 0.3f) % 1f, 0.8f, 1f)
                        text("> ${m.label} <", 320f, y, 2.4f, rgb[0], rgb[1], rgb[2])
                    } else {
                        text(m.label, 320f, y, 2f, 0.55f, 0.6f, 0.7f)
                    }
                }
                val m = Mode.entries[game.selMode]
                text(m.blurb, 320f, 360f, 1.3f, 0.75f, 0.9f, 1f, pulse * 0.7f + 0.3f)
                if (game.highScore > 0) text("HI ${game.highScore}", 320f, 396f, 1.5f, 0.6f, 1f, 0.7f)
                text("SWIPE TO CHOOSE - TAP TO CLOCK IN", 320f, 440f, 1.5f, 1f, 1f, 1f, pulse)
            }
            GameState.GAME_OVER -> {
                bar()
                text("SHIFT TERMINATED", 320f, 200f, 3.2f, 1f, 0.4f, 0.35f)
                text("SCORE ${game.score}", 320f, 256f, 2.2f, 1f, 1f, 1f)
                text("WAVE ${game.wave} - HI ${game.highScore}", 320f, 296f, 1.6f, 0.7f, 0.9f, 1f)
                text("TAP TO CLOCK BACK IN", 320f, 356f, 1.9f, 0.5f, 1f, 0.6f, pulse)
                text("SWIPE FOR MODE SELECT", 320f, 392f, 1.4f, 0.7f, 0.85f, 1f)
            }
            GameState.LIFE_LOST -> {
                bar()
                text("REBOOTING THE BROOM", 320f, 250f, 2f, 1f, 0.7f, 0.4f, pulse)
            }
            else -> {
                bar()
                if (game.isFps) crosshair()
            }
        }

        game.message?.let {
            hsv((game.messageHue + game.time * 0.4f) % 1f, 0.85f, 1f)
            text(it, 320f, 172f, 2.4f, rgb[0], rgb[1], rgb[2], 0.6f + 0.4f * pulse)
        }
    }

    private fun bar() {
        text("${game.score}", 16f, 40f, 2.2f, 1f, 1f, 1f, 1f, center = false)
        val wv = "WAVE ${game.wave} - ${game.mode.label}"
        text(wv, 320f - StrokeFont.width(wv, 1.5f) / 2f, 40f, 1.5f, 0.7f, 0.85f, 1f, 1f, center = false)
        for (i in 0 until game.lives.coerceAtMost(6)) {
            val cx = 624f - i * 26f
            hud.line(cx, 20f, 0f, cx - 8f, 38f, 0f, 0.35f, 0.95f, 1f, 1f)
            hud.line(cx, 20f, 0f, cx + 8f, 38f, 0f, 0.35f, 0.95f, 1f, 1f)
        }
        if (game.activePower >= 0) {
            hsv(powerHue(game.activePower), 0.85f, 1f)
            val name = Game.POWER_NAMES[game.activePower].trimEnd('!')
            text(name, 320f - StrokeFont.width(name, 1.2f) / 2f, 62f, 1.2f, rgb[0], rgb[1], rgb[2], 0.9f, center = false)
            val frac = (game.powerT / Game.POWER_DURATION).coerceIn(0f, 1f)
            hud.line(260f, 70f, 0f, 260f + 120f * frac, 70f, 0f, rgb[0], rgb[1], rgb[2], 0.9f)
        }
    }

    private fun crosshair() {
        val a = 0.8f
        hud.line(320f, 226f, 0f, 320f, 236f, 0f, 0.5f, 1f, 1f, a)
        hud.line(320f, 244f, 0f, 320f, 254f, 0f, 0.5f, 1f, 1f, a)
        hud.line(306f, 240f, 0f, 316f, 240f, 0f, 0.5f, 1f, 1f, a)
        hud.line(324f, 240f, 0f, 334f, 240f, 0f, 0.5f, 1f, 1f, a)
    }

    // ------------------------------------------------------- gl helpers

    private fun hsv(hh: Float, s: Float, v: Float) {
        val h6 = ((hh % 1f + 1f) % 1f) * 6f
        val i = h6.toInt(); val f = h6 - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        when (i % 6) {
            0 -> { rgb[0] = v; rgb[1] = t; rgb[2] = p }
            1 -> { rgb[0] = q; rgb[1] = v; rgb[2] = p }
            2 -> { rgb[0] = p; rgb[1] = v; rgb[2] = t }
            3 -> { rgb[0] = p; rgb[1] = q; rgb[2] = v }
            4 -> { rgb[0] = t; rgb[1] = p; rgb[2] = v }
            else -> { rgb[0] = v; rgb[1] = p; rgb[2] = q }
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("TapInvaders", "link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("TapInvaders", "compile: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    inner class Batch(maxVerts: Int) {
        private val fb: FloatBuffer = ByteBuffer.allocateDirect(maxVerts * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val cap = maxVerts
        var count = 0; private set
        fun reset() { fb.position(0); count = 0 }
        fun v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
            if (count >= cap) return
            fb.put(x); fb.put(y); fb.put(z); fb.put(r); fb.put(g); fb.put(b); fb.put(a); count++
        }
        fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, g: Float, b: Float, a: Float) {
            v(x0, y0, z0, r, g, b, a); v(x1, y1, z1, r, g, b, a)
        }
        fun draw(mode: Int) {
            if (count == 0) return
            fb.position(0); GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 28, fb); GLES30.glEnableVertexAttribArray(aPos)
            fb.position(3); GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, 28, fb); GLES30.glEnableVertexAttribArray(aColor)
            GLES30.glDrawArrays(mode, 0, count)
        }
    }

    companion object {
        // Straight-on tilted table camera (2D modes + title).
        private const val V = 17f
        private const val TILT_SIN = 0.8480f   // sin 58°
        private const val TILT_COS = 0.5299f   // cos 58°
        private const val CAM_D = 60f

        // --- the cast, two frames each: line segments (x0,y0,x1,y1)* ---

        val SQUID_A = floatArrayOf(
            -0.2f, 1.6f, 0.2f, 1.6f,
            -0.4f, 1.3f, -0.2f, 1.6f, 0.4f, 1.3f, 0.2f, 1.6f,
            -0.4f, 1.3f, -0.4f, 0.9f, 0.4f, 1.3f, 0.4f, 0.9f,
            -0.4f, 0.9f, 0.4f, 0.9f,
            -0.25f, 0.9f, -0.45f, 0.45f, 0.25f, 0.9f, 0.45f, 0.45f,
            -0.1f, 0.9f, -0.1f, 0.5f, 0.1f, 0.9f, 0.1f, 0.5f,
        )
        val SQUID_B = floatArrayOf(
            -0.2f, 1.6f, 0.2f, 1.6f,
            -0.4f, 1.3f, -0.2f, 1.6f, 0.4f, 1.3f, 0.2f, 1.6f,
            -0.4f, 1.3f, -0.4f, 0.9f, 0.4f, 1.3f, 0.4f, 0.9f,
            -0.4f, 0.9f, 0.4f, 0.9f,
            -0.25f, 0.9f, -0.1f, 0.45f, 0.25f, 0.9f, 0.1f, 0.45f,
            -0.1f, 0.9f, -0.3f, 0.5f, 0.1f, 0.9f, 0.3f, 0.5f,
        )
        val CRAB_A = floatArrayOf(
            -0.7f, 1.2f, 0.7f, 1.2f,
            -0.7f, 1.2f, -0.7f, 0.7f, 0.7f, 1.2f, 0.7f, 0.7f,
            -0.7f, 0.7f, 0.7f, 0.7f,
            -0.3f, 1.2f, -0.5f, 1.6f, 0.3f, 1.2f, 0.5f, 1.6f,
            -0.7f, 1.0f, -1.0f, 1.35f, 0.7f, 1.0f, 1.0f, 1.35f,
            -0.35f, 0.7f, -0.45f, 0.3f, 0.35f, 0.7f, 0.45f, 0.3f,
        )
        val CRAB_B = floatArrayOf(
            -0.7f, 1.2f, 0.7f, 1.2f,
            -0.7f, 1.2f, -0.7f, 0.7f, 0.7f, 1.2f, 0.7f, 0.7f,
            -0.7f, 0.7f, 0.7f, 0.7f,
            -0.3f, 1.2f, -0.5f, 1.6f, 0.3f, 1.2f, 0.5f, 1.6f,
            -0.7f, 1.0f, -1.0f, 0.55f, 0.7f, 1.0f, 1.0f, 0.55f,
            -0.35f, 0.7f, -0.2f, 0.3f, 0.35f, 0.7f, 0.2f, 0.3f,
        )
        val OCTO_A = floatArrayOf(
            -0.85f, 1.45f, 0.85f, 1.45f,
            -0.85f, 1.45f, -0.85f, 0.75f, 0.85f, 1.45f, 0.85f, 0.75f,
            -0.85f, 0.75f, 0.85f, 0.75f,
            -0.3f, 1.45f, -0.3f, 1.2f, 0.3f, 1.45f, 0.3f, 1.2f,
            -0.35f, 0.75f, -0.35f, 0.35f, 0.35f, 0.75f, 0.35f, 0.35f,
        )
        val OCTO_B = floatArrayOf(
            -0.85f, 1.45f, 0.85f, 1.45f,
            -0.85f, 1.45f, -0.85f, 0.75f, 0.85f, 1.45f, 0.85f, 0.75f,
            -0.85f, 0.75f, 0.85f, 0.75f,
            -0.3f, 1.45f, -0.3f, 1.2f, 0.3f, 1.45f, 0.3f, 1.2f,
            -0.35f, 0.75f, -0.6f, 0.35f, 0.35f, 0.75f, 0.6f, 0.35f,
        )

        private const val VERT = """#version 300 es
        in vec3 aPos; in vec4 aColor; uniform mat4 uMVP; uniform float uPointSize; out vec4 vColor;
        void main() { gl_Position = uMVP * vec4(aPos, 1.0); gl_PointSize = uPointSize; vColor = aColor; }"""
        private const val FRAG = """#version 300 es
        precision mediump float; in vec4 vColor; uniform float uPoint; out vec4 fragColor;
        void main() {
            if (uPoint > 0.5) { vec2 d = gl_PointCoord - vec2(0.5); float r2 = dot(d, d); if (r2 > 0.25) discard; fragColor = vec4(vColor.rgb, vColor.a * (1.0 - r2 * 4.0)); }
            else { fragColor = vColor; }
        }"""
    }
}
