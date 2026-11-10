package com.stellarite.stellardash

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

interface GameHost {
    fun playSound(sound: Sfx.Sound)
    fun buzz()
    fun loadBest(): Int
    fun saveBest(value: Int)
    fun loadString(key: String): String?
    fun saveString(key: String, value: String)
}

private class Particle {
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
    var life = 0f; var maxLife = 1f; var size = 4f; var color = Color.WHITE
    var gravity = 0f
    /** 0 circle, 1 square, 2 star, 3 ring */
    var shape = 0
}

private class FloatText(val text: String, var x: Float, var y: Float, val color: Int) {
    var life = 1.1f
}

/** Harmless screen-space flash used by distraction zones (never ▲ ▼ or !). */
private class Flash(val x: Float, val y: Float, val color: Int, val star: Boolean, val size: Float) {
    var life = 0.45f
}

private class InEvent(val type: Int, val id: Int, val x: Float, val y: Float)

private class Ptr(val x0: Float, val y0: Float, val t0: Float, var role: Int) {
    var swiped = false
}

/**
 * Stellar Dash: reaction platformer with Drift (endless), Orbit (training) and Deep Space (hard training) modes.
 *
 * Coordinates are "virtual units": the screen is always 720 units tall and as wide
 * as its aspect ratio needs. Y grows downwards. The player is tracked by feet position.
 */
class Game(private val host: GameHost) {

    enum class Screen { MENU, PROFILE, SETTINGS, HANGAR, AWARDS, MAP, INTRO, PLAYING, PAUSED, DEAD, CLEAR }
    enum class Mode { ENDLESS, TRAINER, EXPERT }

    // ---- Tuning ----
    private val viewH = 720f
    private val gravity = 2600f
    private val jumpV = 980f
    private val doubleV = 860f
    private val bounceV = 1300f
    private val releaseCap = -380f
    private val minJumpTime = 0.08f
    private val startSpeed = 400f
    private val maxSpeed = 860f
    private val accel = 9f
    private val pw = 40f
    private val standH = 40f
    private val slideH = 20f
    private val coyote = 0.09f
    private val bufferTime = 0.12f
    private val spikeH = 28f
    private val mineR = 18f
    private val dashDur = 0.22f
    private val dashMul = 2.2f
    private val dashCooldown = 0.9f
    /** Slide meter: 3 s of sliding, refills fully in 1 s when not sliding. Jumping ends a slide press. */
    private val slideMax = 2.0f
    private val slideRecharge = 2.0f
    private val slamDelay = 0.12f

    // ---- Screen ----
    @Volatile private var screenW = 1280
    @Volatile private var screenH = 720
    private var viewW = 1280f

    // ---- Input ----
    private val events = ConcurrentLinkedQueue<InEvent>()
    private val ptrs = HashMap<Int, Ptr>()
    @Volatile private var pauseRequested = false
    @Volatile private var backRequested = false
    private var jumpHeld = false
    private var slideHeld = false
    private var wasJumpHeld = false

    // ---- Settings ----
    private var buttonsLayout = host.loadString("ctl") == "buttons"
    private var soundOn = host.loadString("snd") != "off"
    private var vibOn = host.loadString("vib") != "off"
    private var hintsOn = host.loadString("hints") != "off"
    private var settingsFromPause = false
    private val meta = Meta().also { it.load(host.loadString("meta")) }
    private val toasts = ArrayList<String>()
    private var toastT = 0f
    private var hangarSlot = Slot.BODY
    private var buyConfirm: String? = null
    private var bgId = ""
    private var confirmReset = -1
    private var confirmT = 0f

    // ---- State ----
    @Volatile var screen = Screen.MENU
        private set
    private var mode = Mode.ENDLESS
    private var stateT = 0f
    private var animT = 0f
    private var runT = 0f
    private var speed = startSpeed
    private var effSpeed = startSpeed
    private var camX = 0f
    private var shake = 0f

    // ---- Player ----
    private var px = 0f
    private var fy = 0f
    private var vy = 0f
    private var ph = 40f
    private var onGround = true
    private var sliding = false
    private var sinceGround = 0f
    private var buffer = 0f
    private var jumps = 0
    private var jumpT = 0f
    private var spin = 0f
    private var squash = 0f
    private var trailT = 0f
    private var dashT = 0f
    private var slideEnergy = 2.0f
    private var slideEmpty = false
    private var slideLocked = true
    private var slamPending = 0f
    private var dashCd = 0f
    private var airDashUsed = false
    private var pressT = 0f
    private var hopEnc: Encounter? = null
    private var groundPlat: Platform? = null
    private var mirror = false
    private var flipped = false
    private var zeroG = false
    private var gravEnc: Encounter? = null
    private var darkness = 0f
    private var flashT = 0f
    private var surgeFlash = 0f

    private var startX = 0f
    private var shardBonus = 0
    private var score = 0
    private var best = host.loadBest()
    private var newBest = false

    // ---- Trainer ----
    private var profile: TrainerProfile? = null
    private var trainerProfile: TrainerProfile? = null
    private var expertProfile: TrainerProfile? = null
    private var profileTabExpert = false
    private var profileCat = -1
    private var mapPage = 0
    private var mapAnimate = false
    private var mapLinkAnim = 1f
    private var cfg = endlessConfig()
    private var deathReason = ""
    private var knockedBack = false
    private var phaseEvent: PhaseEvent? = null
    private var clearReactAvg = -1f
    private var clearPassed = 0
    private var clearTotal = 0
    private var clearAttempts = 0
    private var runPassed = 0
    private var runTotal = 0
    private var runReactSum = 0f
    private var runReactN = 0
    private var introSkills: List<Skill> = emptyList()

    private val world = World()
    private val builder = LevelBuilder(world)
    private val particles = ArrayList<Particle>()
    private val particlePool = ArrayList<Particle>()
    private val floats = ArrayList<FloatText>()
    private val flashes = ArrayList<Flash>()
    private var cleanupT = 0f

    // ---- Background ----
    private val starX = FloatArray(160)
    private val starY = FloatArray(160)
    private val starS = FloatArray(160)
    private val starFieldW = 2400f

    // ---- Colours & paints ----
    private val teal = Color.rgb(0x20, 0xE3, 0xC2)
    private val lime = Color.rgb(0xB6, 0xFF, 0x3B)
    private val danger = Color.rgb(0xFF, 0x3D, 0x6E)
    private val amber = Color.rgb(0xFF, 0xB0, 0x3B)
    private val blue = Color.rgb(0x3B, 0x8C, 0xFF)
    private val violet = Color.rgb(0xA8, 0x5C, 0xFF)
    private val textDim = Color.rgb(190, 215, 230)
    private val muted = Color.rgb(110, 140, 160)

    private val bgPaint = Paint().apply {
        shader = LinearGradient(
            0f, 0f, 0f, 720f,
            intArrayOf(Color.rgb(3, 6, 16), Color.rgb(8, 22, 42), Color.rgb(14, 30, 52)),
            null, Shader.TileMode.CLAMP
        )
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val planetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            -30f, -30f, 130f,
            intArrayOf(Color.rgb(40, 110, 130), Color.rgb(12, 40, 64), Color.rgb(6, 16, 30)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = teal; alpha = 110
    }
    private val bodyPaint = Paint().apply {
        shader = LinearGradient(
            0f, 330f, 0f, 720f,
            intArrayOf(Color.rgb(16, 44, 62), Color.rgb(8, 20, 34)),
            null, Shader.TileMode.CLAMP
        )
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val seamPaint = Paint().apply { color = Color.argb(70, 0, 0, 0); strokeWidth = 2f }
    private val edgePaint = Paint().apply { color = teal }
    private val glowPaint = Paint().apply { color = teal; alpha = 50 }
    private val dimPaint = Paint().apply { color = Color.argb(160, 2, 6, 14) }
    private val darkPaint = Paint().apply { color = Color.rgb(1, 2, 6) }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 8, 22, 40) }
    private val btnFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 12, 36, 54) }
    private val btnStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 40, 58) }

    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private fun textPaint(size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER, b: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; textAlign = align; if (b) typeface = bold
        }

    private val hudPaint = textPaint(34f, Color.WHITE, Paint.Align.LEFT, true)
    private val hudSmall = textPaint(20f, Color.rgb(190, 215, 230), Paint.Align.LEFT, true)
    private val hudRight = textPaint(26f, Color.rgb(0xB6, 0xFF, 0x3B), Paint.Align.RIGHT, true)
    private val titlePaint = textPaint(84f, Color.rgb(0x20, 0xE3, 0xC2), b = true).apply { letterSpacing = 0.08f }
    private val headPaint = textPaint(54f, Color.rgb(0x20, 0xE3, 0xC2), b = true).apply { letterSpacing = 0.06f }
    private val bigPaint = textPaint(36f, Color.WHITE, b = true)
    private val smallPaint = textPaint(23f, Color.rgb(190, 215, 230))
    private val leftPaint = textPaint(21f, Color.rgb(190, 215, 230), Paint.Align.LEFT)
    private val btnText = textPaint(30f, Color.WHITE, b = true)
    private val btnSub = textPaint(17f, Color.rgb(0xB6, 0xFF, 0x3B))
    private val creditPaint = textPaint(18f, Color.rgb(110, 140, 160))
    private val symPaint = textPaint(42f, Color.rgb(0xFF, 0x3D, 0x6E), b = true)
    private val floatPaint = textPaint(24f, Color.WHITE, b = true)
    private val nodeText = textPaint(22f, Color.rgb(4, 12, 22), b = true)

    private val path = Path()
    private val rect = RectF()

    init {
        val r = Random(7)
        for (i in starX.indices) {
            starX[i] = r.nextFloat() * starFieldW
            starY[i] = r.nextFloat() * 520f
            starS[i] = when (i % 3) { 0 -> 1.2f; 1 -> 2f; else -> 3f }
        }
        resetRun()
    }

    // ================= Input (UI thread) =================
    /** type: 0 = down, 1 = move, 2 = up/cancel. Coordinates in pixels. */
    fun touch(type: Int, id: Int, x: Float, y: Float) {
        val s = screenH / viewH
        val vx = x / s
        val vyy = y / s
        if (type == 0 && screen == Screen.PLAYING && vx > screenW / s - 100f && vyy < 90f) {
            pauseRequested = true
            return
        }
        events.add(InEvent(type, id, vx, vyy))
    }

    /** @return false if the app should close (back pressed on the home menu). */
    fun back(): Boolean {
        if (screen == Screen.MENU) return false
        backRequested = true
        return true
    }

    fun setScreen(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            screenW = w
            screenH = h
        }
    }

    fun pause() {
        saveMeta()
        if (screen == Screen.PLAYING) {
            screen = Screen.PAUSED
            stateT = 0f
        }
    }

    private fun sound(s: Sfx.Sound) {
        if (soundOn) host.playSound(s)
    }

    // ================= Mode / flow =================
    private fun endlessConfig() = LevelConfig(
        seed = Random.nextLong(), speed = startSpeed,
        d = FloatArray(Skill.COUNT), w = FloatArray(Skill.COUNT) { 1f },
        lengthSec = 0f, expert = false, endless = true
    )

    private fun profileKey(expert: Boolean) = if (expert) "profile2_expert" else "profile2_trainer"

    private fun getProfile(expert: Boolean): TrainerProfile {
        val cached = if (expert) expertProfile else trainerProfile
        if (cached != null) return cached
        val p = TrainerProfile(expert)
        p.load(host.loadString(profileKey(expert)))
        if (expert) expertProfile = p else trainerProfile = p
        return p
    }

    private fun saveProfile(p: TrainerProfile? = profile) {
        if (p == null) return
        host.saveString(profileKey(p.expert), p.toJson())
    }

    private fun go(s: Screen) {
        screen = s
        stateT = 0f
        ptrs.clear()
        jumpHeld = false
        slideHeld = false
    }

    private fun startEndless() {
        mode = Mode.ENDLESS
        profile = null
        cfg = endlessConfig()
        resetRun()
        go(Screen.PLAYING)
    }

    private fun startTrainer(expert: Boolean) {
        mode = if (expert) Mode.EXPERT else Mode.TRAINER
        val p = getProfile(expert)
        profile = p
        openMap(false)
    }

    private fun prepareLevel() {
        val p = profile ?: return
        p.ensureSpec()
        cfg = p.toConfig()
        introSkills = p.newSkillsThisLevel()
        resetRun()
        go(Screen.INTRO)
    }

    private fun toMenu() {
        saveProfile()
        saveMeta()
        go(Screen.MENU)
    }

    private fun openMap(animateLink: Boolean) {
        val p = profile ?: return
        mapPage = p.level / MAP_PAGE
        mapAnimate = animateLink && p.level % MAP_PAGE != 0
        mapLinkAnim = if (mapAnimate) 0f else 1f
        saveProfile()
        go(Screen.MAP)
    }

    /** "Menu" from inside a trainer run goes back to the star map. */
    private fun leaveRun() {
        if (profile != null && mode != Mode.ENDLESS) openMap(false) else toMenu()
    }

    private fun resetRun() {
        particles.forEach { particlePool.add(it) }
        particles.clear()
        floats.clear()
        flashes.clear()
        px = 0f
        builder.start(cfg, px)
        fy = world.lastTop
        vy = 0f
        ph = standH
        onGround = true
        sliding = false
        jumps = 0
        sinceGround = 0f
        buffer = 0f
        spin = 0f
        squash = 0f
        dashT = 0f
        dashCd = 0f
        slideEnergy = slideMax
        slideEmpty = false
        slideLocked = true
        slamPending = 0f
        airDashUsed = false
        hopEnc = null
        groundPlat = null
        mirror = false
        darkness = 0f
        surgeFlash = 0f
        runPassed = 0; runTotal = 0; runReactSum = 0f; runReactN = 0
        speed = cfg.speed
        effSpeed = speed
        runT = 0f
        startX = px
        shardBonus = 0
        score = 0
        newBest = false
        camX = px - viewW * 0.28f
    }

    // ================= Control layout =================
    // All three control buttons are the same size (200 x 150).
    private fun slideBtn() = RectF(30f, viewH - 180f, 230f, viewH - 30f)
    private fun dashBtn() = RectF(250f, viewH - 180f, 450f, viewH - 30f)
    private fun jumpBtn() = RectF(viewW - 230f, viewH - 180f, viewW - 30f, viewH - 30f)

    private fun roleFor(x: Float, y: Float): Int {
        if (!buttonsLayout) return if (x >= viewW / 2f) ROLE_JUMP else ROLE_SLIDE
        fun inside(r: RectF) = x >= r.left - 25f && x <= r.right + 25f && y >= r.top - 25f && y <= r.bottom + 25f
        return when {
            inside(jumpBtn()) -> ROLE_JUMP
            inside(dashBtn()) -> ROLE_DASH
            inside(slideBtn()) -> ROLE_SLIDE
            else -> ROLE_NONE
        }
    }

    // ================= Update =================
    fun update(dt: Float) {
        viewW = screenW.toFloat() / (screenH.toFloat() / viewH)
        stateT += dt
        animT += dt
        if (confirmReset >= 0) { confirmT -= dt; if (confirmT <= 0f) confirmReset = -1 }
        if (toasts.isNotEmpty()) {
            toastT += dt
            if (toastT > 3.2f) { toasts.removeAt(0); toastT = 0f }
        }
        updateParticles(dt)
        updateFloats(dt)
        if (shake > 0f) shake = max(0f, shake - dt * 40f)

        // ---- input ----
        var tap: FloatArray? = null
        var jumpPressed = false
        var slidePressed = false
        var dashPressed = false
        while (true) {
            val e = events.poll() ?: break
            when (e.type) {
                0 -> {
                    if (screen != Screen.PLAYING) {
                        tap = floatArrayOf(e.x, e.y)
                        ptrs[e.id] = Ptr(e.x, e.y, animT, ROLE_NONE)
                    } else {
                        val role = roleFor(e.x, e.y)
                        ptrs[e.id] = Ptr(e.x, e.y, animT, role)
                        when (role) {
                            ROLE_JUMP -> jumpPressed = true
                            ROLE_SLIDE -> slidePressed = true
                            ROLE_DASH -> dashPressed = true
                        }
                    }
                }
                1 -> {
                    val p = ptrs[e.id]
                    if (p != null && screen == Screen.PLAYING && !buttonsLayout && !p.swiped &&
                        p.x0 < viewW / 2f &&
                        e.x - p.x0 > 55f && abs(e.y - p.y0) < 80f && animT - p.t0 < 0.35f
                    ) {
                        p.swiped = true
                        dashPressed = true
                        if (p.role == ROLE_SLIDE) p.role = ROLE_NONE
                    }
                }
                else -> ptrs.remove(e.id)
            }
        }
        jumpHeld = ptrs.values.any { it.role == ROLE_JUMP }
        slideHeld = ptrs.values.any { it.role == ROLE_SLIDE }

        if (backRequested) {
            backRequested = false
            when {
                screen == Screen.PLAYING -> go(Screen.PAUSED)
                screen == Screen.PROFILE && profileCat >= 0 -> profileCat = -1
                screen == Screen.MENU -> {}
                screen == Screen.SETTINGS && settingsFromPause -> { settingsFromPause = false; go(Screen.PAUSED) }
                screen == Screen.PROFILE || screen == Screen.SETTINGS || screen == Screen.MAP ||
                    screen == Screen.HANGAR || screen == Screen.AWARDS -> toMenu()
                else -> leaveRun()
            }
            return
        }
        if (pauseRequested) {
            pauseRequested = false
            if (screen == Screen.PLAYING) { go(Screen.PAUSED); return }
        }

        when (screen) {
            Screen.MENU -> if (tap != null) menuTap(tap)
            Screen.PROFILE -> if (tap != null) profileTap(tap)
            Screen.SETTINGS -> if (tap != null) settingsTap(tap)
            Screen.HANGAR -> if (tap != null) hangarTap(tap)
            Screen.AWARDS -> if (tap != null && hit(backBtnRect(), tap)) go(Screen.MENU)
            Screen.MAP -> {
                mapLinkAnim = min(1f, mapLinkAnim + dt * 1.6f)
                if (tap != null && stateT > 0.2f) mapTap(tap)
            }
            Screen.INTRO -> if (tap != null && stateT > 0.3f) {
                if (hit(menuBtnRect(), tap)) leaveRun() else go(Screen.PLAYING)
            }
            Screen.PAUSED -> if (tap != null) pauseTap(tap)
            Screen.DEAD -> if (tap != null && stateT > 0.5f) {
                when {
                    hit(menuBtnRect(), tap) -> leaveRun()
                    mode == Mode.ENDLESS -> startEndless()
                    knockedBack -> openMap(false)
                    else -> { resetRun(); go(Screen.PLAYING) }
                }
            }
            Screen.CLEAR -> if (tap != null && stateT > 0.6f) {
                if (hit(menuBtnRect(), tap)) leaveRun() else openMap(true)
            }
            Screen.PLAYING -> step(dt, jumpPressed, slidePressed, dashPressed)
        }
        wasJumpHeld = jumpHeld
    }

    private fun step(dt: Float, jumpPressed: Boolean, slidePressed: Boolean, dashPressed: Boolean) {
        runT += dt
        if (cfg.endless) speed = min(maxSpeed, startSpeed + accel * runT)
        updateFlashes(dt)
        surgeFlash = max(0f, surgeFlash - dt)

        // ---- zones that change speed / rules ----
        var windMul = 1f
        var surgeMul = 1f
        mirror = false
        val wasFlipped = flipped
        flipped = false
        zeroG = false
        var darkTarget = 0f
        for (z in world.zones) {
            when (z.kind) {
                ZoneKind.WIND -> if (px >= z.x0 && px <= z.x1) windMul = z.factor
                ZoneKind.MIRROR -> if (px >= z.x0 && px <= z.x1) mirror = true
                ZoneKind.SURGE -> {
                    if (!z.triggered && px >= z.x0) {
                        z.triggered = true; z.t = 0f; surgeFlash = 0.5f
                        z.enc.triggerT = runT
                    }
                    if (z.triggered && !z.done) {
                        z.t += dt
                        if (z.t < z.dur) surgeMul = z.factor else z.done = true
                    }
                }
                ZoneKind.BLACKOUT -> {
                    if (!z.triggered && px >= z.x0) { z.triggered = true; z.t = 0f }
                    if (z.triggered && !z.done) {
                        z.t += dt
                        if (z.t < z.dur) darkTarget = 1f else z.done = true
                    }
                }
                ZoneKind.DISTRACT -> if (px >= z.x0 && px <= z.x1) {
                    flashT -= dt
                    if (flashT <= 0f) { flashT = 0.18f; spawnFlash() }
                }
                ZoneKind.CHASER -> {}
                ZoneKind.FLIP -> if (px >= z.x0 && px <= z.x1) { flipped = true; gravEnc = z.enc }
                ZoneKind.ZEROG -> if (px >= z.x0 && px <= z.x1) { zeroG = true; gravEnc = z.enc }
            }
        }
        effSpeed = speed * windMul * surgeMul
        if (flipped != wasFlipped) {
            onGround = false
            sliding = false
            ph = standH
            if (flipped) { jumps = 1; sound(Sfx.Sound.BOUNCE) }
        }
        val gs = if (flipped) -1f else 1f
        // Blackouts fade in over ~0.25 s and out over ~0.4 s.
        darkness = if (darkTarget > darkness) min(1f, darkness + dt * 4f) else max(0f, darkness - dt * 2.5f)

        // ---- actions ----
        if (dashPressed) tryDash()
        if (slidePressed && zeroG) {
            vy = 420f
            onAction(Action.SLIDE)
        } else if (slidePressed && flipped) {
            onAction(Action.SLIDE)
        } else if (slidePressed) {
            slideLocked = false
            if (!onGround) {
                // Half-screen: wait a moment so a swipe (air-dash) isn't mistaken for a slam.
                if (buttonsLayout) slam() else slamPending = slamDelay
            }
            onAction(Action.SLIDE)
        }
        if (slamPending > 0f) {
            slamPending -= dt
            if (!slideHeld || onGround) slamPending = 0f
            else if (slamPending <= 0f) slam()
        }
        buffer = if (jumpPressed) bufferTime else max(0f, buffer - dt)
        sinceGround = if (onGround) 0f else sinceGround + dt
        if (zeroG) {
            buffer = 0f
            if (jumpPressed) {
                vy = -420f
                onGround = false
                onAction(Action.JUMP)
                sound(Sfx.Sound.JUMP)
                burst(px, fy, 6, bodyColor(), 140f, 0.3f)
            }
        } else if (buffer > 0f) {
            if (onGround || (sinceGround < coyote && jumps == 0)) {
                vy = -jumpV * gs
                jumps = 1
                jumpT = 0f
                onGround = false
                sliding = false
                slideLocked = true
                ph = standH
                sinceGround = coyote
                buffer = 0f
                squash = -0.25f
                onTakeoff()
                onAction(Action.JUMP)
                sound(Sfx.Sound.JUMP)
                burst(px, fy, 6, teal, 160f, 0.35f)
            } else if (jumps < 2) {
                vy = -doubleV * gs
                jumps = 2
                jumpT = 0f
                buffer = 0f
                spin = 360f
                dashT = 0f
                onAction(Action.JUMP)
                sound(Sfx.Sound.DOUBLE)
                burst(px, fy - ph * 0.5f, 14, lime, 260f, 0.4f)
            }
        }

        // ---- hold duration (short hops / mirror) ----
        if (wasJumpHeld && !jumpHeld) {
            hopEnc?.let { e -> if (e.holdMs < 0f) e.holdMs = (runT - pressT) * 1000f }
            hopEnc = null
        }

        // ---- horizontal ----
        if (dashT > 0f) dashT -= dt
        if (dashCd > 0f) dashCd -= dt
        val mul = if (dashT > 0f) dashMul else 1f
        px += effSpeed * mul * dt

        // ---- vertical ----
        jumpT += dt
        val capNow = if (mirror) jumpHeld else !jumpHeld
        if (!zeroG && capNow && vy * gs < releaseCap && jumpT > minJumpTime) vy = releaseCap * gs
        if (dashT > 0f && !onGround) {
            vy = 0f
        } else if (zeroG) {
            vy += gravity * 0.12f * dt
            vy *= (1f - 0.6f * dt)
        } else {
            vy += gravity * gs * (if (vy * gs > 0f) 1.15f else 1f) * dt
        }
        vy = vy.coerceIn(-1500f, 1500f)
        if (spin > 0f) spin = max(0f, spin - 900f * dt)
        squash += (0f - squash) * min(1f, dt * 12f)

        val prevFeet = fy
        val wasGround = onGround
        fy += vy * dt
        onGround = false

        val left = px - pw / 2f
        val right = px + pw / 2f

        // ---- platforms ----
        for (p in world.platforms) {
            p.update(runT, dt)
            if (p.broken) continue
            if (right <= p.x || left >= p.x + p.w) continue
            val top = p.top
            if (p.kind == PlatKind.FLOATING) {
                if (vy >= 0f && prevFeet <= top + 1f && fy >= top) land(p, wasGround)
                continue
            }
            if (fy < top) continue
            if (vy >= 0f && prevFeet <= top + 1f) land(p, wasGround)
            else if (vy >= 0f && fy - top < 16f) land(p, wasGround)
            else if (p.kind == PlatKind.MOVING && fy - top < 26f) land(p, wasGround)
            else { die(p.enc, "Hit a wall"); return }
        }

        // ---- roofs (reverse gravity) ----
        if (flipped) {
            for (r in world.roofs) {
                if (right <= r.x || left >= r.x + r.w) continue
                val headNow = fy - ph
                if (headNow > r.bottom) continue
                val prevHead = prevFeet - ph
                if (vy <= 0f && prevHead >= r.bottom - 1f) landRoof(r, wasGround)
                else if (vy <= 0f && r.bottom - headNow < 16f) landRoof(r, wasGround)
                else { die(r.enc, "Hit the edge of the roof"); return }
            }
            if (fy - ph < -120f) { die(gravEnc, "Fell up into space"); return }
        }
        if (zeroG && fy - ph < 0f) { die(gravEnc, "Drifted off the track"); return }

        // ---- sliding state ----
        sliding = slideHeld && onGround && !slideLocked && !slideEmpty && !flipped && !zeroG
        if (sliding) {
            slideEnergy -= dt
            if (slideEnergy <= 0f) {
                slideEnergy = 0f
                slideEmpty = true
                slideLocked = true
                sliding = false
                addFloat("SLIDE OUT", px, fy - 60f, blue)
            }
        } else {
            slideEnergy = min(slideMax, slideEnergy + dt * slideMax / slideRecharge)
            if (slideEmpty && slideEnergy >= slideMax) slideEmpty = false
        }
        val newH = if (sliding) slideH else standH
        if (newH != ph) {
            if (sliding) sound(Sfx.Sound.SLIDE)
            ph = newH
        }
        val headY = fy - ph

        // ---- breakable floors ----
        for (p in world.platforms) {
            if (p.broken) continue
            if (p.kind == PlatKind.COLLAPSE && p.touched) {
                p.timer += dt
                if (p.timer > p.delay) { p.broken = true; sound(Sfx.Sound.BREAK) }
            } else if (p.kind == PlatKind.CRACKED && onGround && groundPlat === p && !sliding) {
                p.timer += dt
                if (p.timer > 0.1f) { p.broken = true; sound(Sfx.Sound.BREAK); burst(px, p.top, 16, amber, 220f, 0.5f) }
            }
        }

        // ---- bounce pads ----
        if (onGround) {
            for (pad in world.pads) {
                if (right > pad.x && left < pad.x + pad.w && abs(fy - pad.base) < 2f) {
                    vy = -bounceV
                    onGround = false
                    sliding = false
                    ph = standH
                    jumps = 1
                    jumpT = -10f // no early-release cut on a bounce
                    airDashUsed = false
                    pad.enc.triggerT = runT
                    sound(Sfx.Sound.BOUNCE)
                    burst(px, fy, 12, lime, 260f, 0.4f)
                    break
                }
            }
        }

        if (fy > viewH + 120f) {
            val gp = groundPlat
            val killer = if (gp != null && gp.broken && gp.enc != null) gp.enc
            else world.platforms.firstOrNull { it.x > left && !it.broken }?.enc
            die(killer, "Fell into the void"); return
        }

        // ---- ceilings ----
        for (c in world.ceilings) {
            if (c.shown < 0.8f || c.retract > 0.3f || !c.active) continue
            if (right > c.x + 4f && left < c.x + c.w - 4f && headY < c.bottom) {
                val why = when (c.kind) {
                    CeilKind.CRUSHER -> "Jumped into a crusher"
                    CeilKind.LOW -> "Hit a low barrier"
                    CeilKind.HOP -> "Hit the ceiling"
                }
                die(c.enc, why); return
            }
        }

        // ---- reveals (signals, gates, fake-outs) ----
        for (r in world.reveals) {
            val dist = r.x - px
            if (!r.shown && dist < effSpeed * r.showLead) {
                r.shown = true
                if (r.kind == RevealKind.SIGNAL_JUMP || r.kind == RevealKind.SIGNAL_SLIDE) {
                    r.enc.triggerT = runT
                    sound(Sfx.Sound.WARN)
                }
            }
            if (!r.revealed && dist < effSpeed * r.revealLead) {
                r.revealed = true
                if (r.kind == RevealKind.GATE || r.kind == RevealKind.FAKEOUT) {
                    r.enc.triggerT = runT
                    sound(Sfx.Sound.WARN)
                }
                r.spike?.let { it.triggered = true; it.warn = 0.28f }
                r.ceiling?.let { if (r.kind != RevealKind.FAKEOUT) it.active = true }
            }
            if (r.revealed) {
                r.ceiling?.let {
                    if (r.kind == RevealKind.FAKEOUT) it.retract = min(1f, it.retract + dt * 7f)
                    else it.shown = min(1f, it.shown + dt * 9f)
                }
            }
        }

        // ---- spikes ----
        for (s in world.spikes) {
            if (s.auto && !s.triggered && s.x - px < effSpeed * s.lead) {
                s.triggered = true
                s.enc?.triggerT = runT
                sound(Sfx.Sound.WARN)
            }
            if (s.triggered && s.raise < 1f) {
                s.warn += dt
                if (s.warn > 0.28f) s.raise = min(1f, s.raise + dt * 14f)
            }
            if (s.raise > 0.4f) {
                if (s.down) {
                    val tip = s.base + spikeH * s.raise
                    if (right > s.x + 6f && left < s.x + s.w - 6f && headY < tip - 8f && fy > s.base) {
                        die(s.enc, "Spiked from above"); return
                    }
                } else {
                    val top = s.base - spikeH * s.raise
                    if (right > s.x + 6f && left < s.x + s.w - 6f && fy > top + 8f && headY < s.base) {
                        die(s.enc, "Spiked"); return
                    }
                }
            }
        }

        // ---- decoys ----
        for (d in world.decoys) {
            if (!d.triggered && d.x - px < effSpeed * d.lead) {
                d.triggered = true
                d.enc.triggerT = runT
                sound(Sfx.Sound.WARN)
            }
            if (d.triggered) d.warn += dt
        }

        // ---- mines ----
        for (m in world.mines) {
            m.y = m.baseY + sin(runT * m.freq + m.phase) * m.amp
            val nx = m.x.coerceIn(left, right)
            val ny = m.y.coerceIn(headY, fy)
            val dx = m.x - nx
            val dy = m.y - ny
            if (dx * dx + dy * dy < (mineR - 3f) * (mineR - 3f)) { die(m.enc, "Hit a mine"); return }
        }

        // ---- meteors ----
        for (m in world.meteors) {
            if (!m.triggered && m.x - px < effSpeed * m.lead) {
                m.triggered = true
                m.enc.triggerT = runT
                sound(Sfx.Sound.WARN)
            }
            if (m.triggered) {
                m.t += dt
                val k = (m.t / m.fallDur).coerceIn(0f, 1f)
                m.y = -120f + (m.base - m.size + 120f) * k * k
                if (right > m.x + 4f && left < m.x + m.size - 4f && fy > m.y + 6f && headY < m.y + m.size) {
                    die(m.enc, "Crushed by a meteor"); return
                }
            }
        }

        // ---- zero-g beam gates ----
        for (b in world.beams) {
            if (right > b.x && left < b.x + 12f && headY < b.y1 && fy > b.y0) {
                die(b.enc, "Hit a laser gate"); return
            }
        }

        // ---- laser fences ----
        for (f in world.fences) {
            if (f.on(runT) && right > f.x - 6f && left < f.x + 10f && fy > f.base - 55f) {
                die(f.enc, "Zapped by a laser fence"); return
            }
        }

        // ---- phase walls ----
        for (w in world.walls) {
            if (right > w.x && left < w.x + 16f) {
                if (dashT <= 0f) { die(w.enc, "Hit a phase wall (dash through!)"); return }
            }
        }

        // ---- doors ----
        for (d in world.doors) {
            if (!d.triggered && d.x - px < effSpeed * d.lead) {
                d.triggered = true
                d.enc.triggerT = runT
                sound(Sfx.Sound.WARN)
            }
            if (d.triggered) d.t += dt
            // Only the moment you reach the doorway counts; once you're in, you're through.
            if (!d.entered && right > d.x) {
                d.entered = true
                if (d.bottom() > headY + 4f) { die(d.enc, "Door shut"); return }
            }
        }

        // ---- chasers ----
        for (z in world.zones) {
            if (z.kind != ZoneKind.CHASER || z.done) continue
            if (!z.triggered && px >= z.x0) {
                z.triggered = true
                z.wallX = px - speed * 0.55f
                z.enc.triggerT = runT
                sound(Sfx.Sound.WARN)
            }
            if (z.triggered) {
                z.wallX += speed * z.factor * dt
                if (px > z.x1) z.done = true
                else if (z.wallX > left) { die(z.enc, "Caught by the chaser"); return }
            }
        }

        // ---- shards ----
        val cy = fy - ph / 2f
        for (s in world.shards) {
            if (s.taken) continue
            val dx = s.x - px
            val dy = s.y - cy
            if (dx * dx + dy * dy < 38f * 38f) {
                s.taken = true
                shardBonus += 25
                meta.wallet++
                meta.lifetimeShards++
                if (meta.lifetimeShards >= 100) unlock(Achievement.COLLECTOR)
                if (meta.lifetimeShards >= 1000) unlock(Achievement.HOARDER)
                sound(Sfx.Sound.SHARD)
                burst(s.x, s.y, 10, lime, 220f, 0.45f)
            }
        }

        // ---- encounters ----
        for (e in world.encounters) {
            if (e.resolved) continue
            if (e.skill == Skill.SPLIT && !e.checked && px > (e.refX + e.endX) / 2f) {
                e.checked = true
                e.upper = fy < e.refY
            }
            if (left > e.endX) resolve(e, passed = true, died = false)
        }

        // ---- trail ----
        trailT += dt
        val trail = meta.equippedItem(Slot.TRAIL).id
        val every = when {
            dashT > 0f -> 0.008f
            trail == "trail_dots" -> 0.07f
            trail == "trail_ribbon" -> 0.008f
            trail == "trail_comet" -> 0.012f
            else -> 0.025f
        }
        if (trailT > every) {
            trailT = 0f
            emitTrail(trail, px - pw / 2f, fy - ph * (0.2f + Random.nextFloat() * 0.7f), when {
                dashT > 0f -> Color.WHITE
                jumps == 2 -> lime
                else -> bodyColor()
            })
        }

        score = ((px - startX) / 10f).toInt() + shardBonus
        camX = px - viewW * 0.28f

        if (!cfg.endless && px > world.finishX) { levelClear(); return }

        val endlessD = ((speed - startSpeed) / (maxSpeed - startSpeed)).coerceIn(0f, 1f)
        var guard = 0
        while (world.genX < camX + viewW + 900f && guard < 20) {
            builder.generate(speed, endlessD)
            guard++
        }
        cleanupT += dt
        if (cleanupT > 0.5f) { cleanupT = 0f; builder.cleanup(camX - 300f) }
    }

    private fun slam() {
        slamPending = 0f
        if (onGround) return
        if (vy < 1100f) vy = 1100f
        if (dashT > 0f) dashT = 0f
    }

    private fun tryDash() {
        if (dashCd > 0f) return
        if (!onGround && airDashUsed) return
        dashT = dashDur
        dashCd = dashCooldown
        slamPending = 0f
        if (!onGround) { airDashUsed = true; vy = 0f }
        sound(Sfx.Sound.DASH)
        burst(px, fy - ph / 2f, 10, Color.WHITE, 200f, 0.3f)
        onAction(Action.DASH)
    }

    private fun onAction(a: Action) {
        for (e in world.encounters) {
            if (e.resolved) continue
            when (e.skill) {
                Skill.CHASER -> if (a == Action.DASH && px >= e.refX && px <= e.endX) e.dashes++
                Skill.RHYTHM -> if (px >= e.refX && px <= e.endX) e.times.add(runT)
                else -> {}
            }
            if (e.triggerT < 0f) continue
            if (e.skill == Skill.DECOY) {
                if (a == Action.JUMP && px < e.endX) e.falseStart = true
                continue
            }
            if (e.reactMs < 0f && e.skill.cat != Category.TIMING && e.skill != Skill.CHASER) {
                e.reactMs = (runT - e.triggerT) * 1000f
                val ex = e.expect
                if (ex != null && ex != a) e.wrong = true
                if (e.skill.cat == Category.REACTION && !e.wrong && e.reactMs < 250f) unlock(Achievement.QUICKDRAW)
                if (e.skill.cat == Category.REACTION || e.skill == Skill.SIGNAL || e.skill == Skill.GATE) {
                    addFloat("${e.reactMs.roundToInt()} ms", px, fy - ph - 30f, if (e.wrong) danger else lime)
                }
            }
        }
    }

    private fun onTakeoff() {
        pressT = runT
        hopEnc = null
        val left = px - pw / 2f
        for (e in world.encounters) {
            if (e.resolved) continue
            if (e.skill == Skill.CHAIN && px >= e.refX - 60f && px <= e.endX) e.times.add(runT)
        }
        for (e in world.encounters) {
            if (e.resolved || !e.takeoffLead.isNaN()) continue
            if (e.skill == Skill.DECOY || e.skill == Skill.CHASER || e.skill == Skill.RHYTHM) continue
            if (e.endX < left) continue
            if (e.refX - px > effSpeed * 0.9f) break
            e.takeoffLead = (e.refX - px) / effSpeed
            if (e.skill == Skill.HOP || e.skill == Skill.MIRROR) hopEnc = e
            break
        }
    }

    private fun resolve(e: Encounter, passed: Boolean, died: Boolean) {
        e.resolved = true
        e.passed = passed
        runTotal++
        if (passed) runPassed++
        if (e.reactMs >= 0f) { runReactSum += e.reactMs; runReactN++ }
        if (passed) {
            when (e.skill) {
                Skill.DECOY -> addFloat("HELD!", px, fy - 70f, amber)
                Skill.HOP -> addFloat("CLEAN HOP", px, fy - 70f, teal)
                Skill.CHAIN, Skill.RHYTHM -> {
                    val sd = e.intervalStdMs()
                    if (sd >= 0f) addFloat("±${sd.roundToInt()} ms", px, fy - 70f, teal)
                    if (e.skill == Skill.RHYTHM && sd in 0f..40f) unlock(Achievement.RHYTHM)
                }
                else -> {}
            }
            when (e.skill) {
                Skill.DECOY -> { meta.decoys++; if (meta.decoys >= 10) unlock(Achievement.NERVE) }
                Skill.PHASE -> { meta.phases++; if (meta.phases >= 20) unlock(Achievement.PHASER) }
                Skill.REVERSE -> { meta.flips++; if (meta.flips >= 10) unlock(Achievement.GRAVITY) }
                Skill.ZEROG -> { meta.floats++; if (meta.floats >= 10) unlock(Achievement.FLOATER) }
                else -> {}
            }
        }
        profile?.recordEncounter(e, died)
    }

    private fun landRoof(r: Roof, wasGround: Boolean) {
        fy = r.bottom + ph
        vy = 0f
        onGround = true
        jumps = 0
        airDashUsed = false
        groundPlat = null
        if (!wasGround) {
            squash = 0.3f
            burst(px, r.bottom, 5, bodyColor(), 120f, 0.3f)
        }
    }

    // ---------- meta / achievements / cosmetics ----------
    private fun saveMeta() = host.saveString("meta", meta.toJson())

    private fun unlock(a: Achievement) {
        val item = meta.award(a) ?: return
        toasts.add("★ ${a.title}  —  unlocked ${item.name} (${item.slot.label})")
        saveMeta()
    }

    private fun bodyColor(): Int = meta.equippedItem(Slot.COLOR).color

    private fun land(p: Platform, wasGround: Boolean) {
        fy = p.top
        vy = 0f
        onGround = true
        jumps = 0
        airDashUsed = false
        groundPlat = p
        if (p.kind == PlatKind.COLLAPSE && !p.touched) {
            p.touched = true
            p.enc?.let { if (it.triggerT < 0f) it.triggerT = runT }
        }
        if (!wasGround) {
            squash = 0.3f
            burst(px, p.top, 5, teal, 120f, 0.3f)
        }
    }

    private fun die(killerIn: Encounter?, reason: String) {
        var killer = killerIn
        if (killer == null && profile != null) {
            // Ordinary filler gap/wall: count it as a gap-timing miss.
            killer = Encounter(Skill.GAP, px, px)
        }
        if (killer != null && !killer.resolved) resolve(killer, passed = false, died = true)
        deathReason = if (killerIn != null) "$reason  •  ${killerIn.skill.label}" else reason
        shake = 16f
        sound(Sfx.Sound.DEATH)
        if (vibOn) host.buzz()
        burst(px, fy - ph / 2f, 40, teal, 520f, 0.9f)
        burst(px, fy - ph / 2f, 20, danger, 380f, 0.7f)
        knockedBack = false
        meta.deaths++
        if (meta.deaths >= 100) unlock(Achievement.PERSISTENT)
        val p = profile
        if (p != null) {
            knockedBack = p.recordDeath()
            saveProfile()
        } else {
            if (score >= 2000) unlock(Achievement.FAROUT)
            if (score >= 5000) unlock(Achievement.LIGHTYEAR)
            if (score > best) {
                best = score
                newBest = true
                host.saveBest(best)
            }
        }
        saveMeta()
        go(Screen.DEAD)
    }

    private fun levelClear() {
        val p = profile ?: return
        clearReactAvg = if (runReactN > 0) runReactSum / runReactN else -1f
        clearPassed = runPassed
        clearTotal = runTotal
        clearAttempts = p.levelAttempts + 1
        val levelBefore = p.level
        val livesBefore = p.lives
        val ev = p.completeLevel()
        phaseEvent = ev
        saveProfile()
        // achievements
        unlock(Achievement.FIRST)
        if (clearAttempts == 1 && runTotal >= 8 && runPassed == runTotal) unlock(Achievement.FLAWLESS)
        if (runReactN >= 3 && clearReactAvg < 300f) unlock(Achievement.LIGHTNING)
        if (p.expert) {
            meta.deepClears++
            if (meta.deepClears >= 25) unlock(Achievement.DEEPDIVER)
            if ((levelBefore + 1) % TrainerProfile.CHECKPOINT == 0 && livesBefore == TrainerProfile.MAX_LIVES) {
                unlock(Achievement.SURVIVOR)
            }
        }
        if (ev != null) {
            when (ev.title) {
                "CALIBRATION COMPLETE" -> unlock(if (p.expert) Achievement.COMMANDER else Achievement.CALIBRATED)
                "TRAINING COMPLETE" -> unlock(Achievement.GRADUATE)
                "RETEST COMPLETE" -> {
                    unlock(Achievement.STAGE2)
                    if (ev.prevRating >= 0f && ev.rating - ev.prevRating >= 10f) unlock(Achievement.IMPROVER)
                }
            }
        }
        saveMeta()
        sound(Sfx.Sound.SHARD)
        burst(px, fy, 30, lime, 400f, 0.8f)
        go(Screen.CLEAR)
    }

    // ================= Menu / profile / settings taps =================
    private fun menuButton(i: Int): RectF {
        val w = 440f
        val h = 72f
        val cx = viewW / 2f
        val y = 236f + i * 84f
        return RectF(cx - w / 2f, y, cx + w / 2f, y + h)
    }

    /** Row of 4 equal buttons under the modes: PROFILE, HANGAR, AWARDS, SETTINGS. */
    private fun smallButton(i: Int): RectF {
        val w = 190f
        val gap = 14f
        val x0 = viewW / 2f - (4 * w + 3 * gap) / 2f + i * (w + gap)
        return RectF(x0, 512f, x0 + w, 570f)
    }

    // ---- pause layout ----
    private fun resumeRect(): RectF {
        val side = 260f
        val cx = viewW / 2f
        val cy = viewH / 2f - 10f
        return RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
    }

    private fun pauseItems(): List<String> =
        if (profile != null && mode != Mode.ENDLESS) listOf("HOME", "MAP", "SETTINGS") else listOf("HOME", "SETTINGS")

    private fun pauseBtn(i: Int): RectF {
        val y = viewH / 2f - 140f + i * 96f
        return RectF(60f, y, 320f, y + 78f)
    }

    private fun pauseTap(t: FloatArray) {
        if (hit(resumeRect(), t)) { go(Screen.PLAYING); return }
        val items = pauseItems()
        for (i in items.indices) {
            if (!hit(pauseBtn(i), t)) continue
            when (items[i]) {
                "HOME" -> toMenu()
                "MAP" -> leaveRun()
                "SETTINGS" -> { settingsFromPause = true; confirmReset = -1; go(Screen.SETTINGS) }
            }
            return
        }
    }

    private fun menuBtnRect(): RectF = RectF(24f, viewH - 84f, 184f, viewH - 24f)
    private fun backBtnRect(): RectF = RectF(24f, 24f, 164f, 80f)
    private fun tabRect(expert: Boolean): RectF {
        val cx = viewW / 2f
        return if (expert) RectF(cx + 10f, 92f, cx + 230f, 140f) else RectF(cx - 230f, 92f, cx - 10f, 140f)
    }

    private fun catRect(i: Int): RectF {
        val colW = (viewW - 200f) / 2f
        val col = i % 2
        val row = i / 2
        val x = 80f + col * (colW + 40f)
        val y = 420f + row * 76f
        return RectF(x, y, x + colW, y + 64f)
    }

    private fun settingRow(i: Int): Float = 120f + i * 104f

    private fun settingBtn(row: Int, slot: Int): RectF {
        val x = viewW / 2f + 20f + slot * 240f
        val y = settingRow(row)
        return RectF(x, y, x + 220f, y + 64f)
    }

    private fun hit(r: RectF, p: FloatArray) = r.contains(p[0], p[1])

    private fun menuTap(t: FloatArray) {
        when {
            hit(menuButton(0), t) -> startEndless()
            hit(menuButton(1), t) -> startTrainer(false)
            hit(menuButton(2), t) -> startTrainer(true)
            hit(smallButton(0), t) -> {
                getProfile(false); getProfile(true)
                profileCat = -1
                go(Screen.PROFILE)
            }
            hit(smallButton(1), t) -> { buyConfirm = null; go(Screen.HANGAR) }
            hit(smallButton(2), t) -> go(Screen.AWARDS)
            hit(smallButton(3), t) -> { confirmReset = -1; settingsFromPause = false; go(Screen.SETTINGS) }
        }
    }

    private fun profileTap(t: FloatArray) {
        if (hit(backBtnRect(), t)) {
            if (profileCat >= 0) profileCat = -1 else go(Screen.MENU)
            return
        }
        if (profileCat >= 0) { profileCat = -1; return }
        when {
            hit(tabRect(false), t) -> profileTabExpert = false
            hit(tabRect(true), t) -> profileTabExpert = true
            else -> for (i in Category.values().indices) if (hit(catRect(i), t)) { profileCat = i; break }
        }
    }

    private fun settingsTap(t: FloatArray) {
        when {
            hit(backBtnRect(), t) -> {
                if (settingsFromPause) { settingsFromPause = false; go(Screen.PAUSED) } else go(Screen.MENU)
            }
            hit(settingBtn(0, 0), t) -> { buttonsLayout = false; host.saveString("ctl", "half") }
            hit(settingBtn(0, 1), t) -> { buttonsLayout = true; host.saveString("ctl", "buttons") }
            hit(settingBtn(1, 0), t) -> { soundOn = !soundOn; host.saveString("snd", if (soundOn) "on" else "off") }
            hit(settingBtn(2, 0), t) -> {
                vibOn = !vibOn
                host.saveString("vib", if (vibOn) "on" else "off")
                if (vibOn) host.buzz()
            }
            hit(settingBtn(3, 0), t) -> { hintsOn = !hintsOn; host.saveString("hints", if (hintsOn) "on" else "off") }
            hit(settingBtn(4, 0), t) -> resetTap(0)
            hit(settingBtn(4, 1), t) -> resetTap(1)
        }
    }

    private fun resetTap(which: Int) {
        if (confirmReset == which) {
            val p = getProfile(which == 1)
            p.reset()
            saveProfile(p)
            confirmReset = -1
            addFloat("Progress reset", viewW / 2f, 600f, amber)
        } else {
            confirmReset = which
            confirmT = 3f
        }
    }

    // ================= Particles, floating text, flashes =================
    private fun emitTrail(style: String, x: Float, y: Float, color: Int) {
        if (particles.size > 500) return
        val p = obtainParticle()
        p.x = x; p.y = y; p.vx = -60f; p.vy = 0f; p.gravity = 0f
        p.color = color; p.shape = 0
        p.life = 0.3f; p.size = 5f
        when (style) {
            "trail_dots" -> { p.vx = 0f; p.size = 6f; p.life = 0.5f }
            "trail_ribbon" -> { p.y = fy - ph * 0.5f; p.vx = 0f; p.size = 4f; p.life = 0.45f }
            "trail_stars" -> { p.shape = 2; p.size = 7f; p.vy = rf(-40f, 40f); p.life = 0.5f }
            "trail_pixels" -> { p.shape = 1; p.size = 6f; p.life = 0.4f }
            "trail_bubbles" -> { p.shape = 3; p.size = rf(4f, 9f); p.vy = -50f; p.life = 0.7f }
            "trail_comet" -> { p.y = fy - ph * 0.5f; p.size = 12f; p.vx = -20f; p.life = 0.35f }
        }
        p.maxLife = p.life
        particles.add(p)
    }

    private fun obtainParticle(): Particle =
        if (particlePool.isNotEmpty()) particlePool.removeAt(particlePool.size - 1) else Particle()

    private fun burst(x: Float, y: Float, n: Int, color: Int, power: Float, life: Float) {
        for (i in 0 until n) {
            if (particles.size > 500) return
            val p = obtainParticle()
            val a = Random.nextFloat() * 6.2832f
            val sp = power * (0.3f + Random.nextFloat() * 0.7f)
            p.x = x; p.y = y
            p.shape = 0
            p.vx = cos(a) * sp
            p.vy = sin(a) * sp
            p.gravity = 600f
            p.maxLife = life * (0.6f + Random.nextFloat() * 0.4f)
            p.life = p.maxLife
            p.size = 3f + Random.nextFloat() * 4f
            p.color = color
            particles.add(p)
        }
    }

    private fun updateParticles(dt: Float) {
        var i = particles.size - 1
        while (i >= 0) {
            val p = particles[i]
            p.life -= dt
            if (p.life <= 0f) {
                particles.removeAt(i)
                particlePool.add(p)
            } else {
                p.vy += p.gravity * dt
                p.x += p.vx * dt
                p.y += p.vy * dt
            }
            i--
        }
    }

    private fun addFloat(text: String, x: Float, y: Float, color: Int) {
        if (floats.size < 12) floats.add(FloatText(text, x, y, color))
    }

    private fun updateFloats(dt: Float) {
        var i = floats.size - 1
        while (i >= 0) {
            val f = floats[i]
            f.life -= dt
            f.y -= 50f * dt
            if (screen == Screen.PLAYING) f.x += effSpeed * dt * 0.9f
            if (f.life <= 0f) floats.removeAt(i)
            i--
        }
    }

    private fun spawnFlash() {
        val edge = Random.nextInt(4)
        val x = when (edge) { 0 -> rf(20f, 160f); 1 -> rf(viewW - 160f, viewW - 20f); else -> rf(40f, viewW - 40f) }
        val y = when (edge) { 2 -> rf(20f, 140f); 3 -> rf(viewH - 140f, viewH - 20f); else -> rf(40f, viewH - 40f) }
        val colors = intArrayOf(violet, Color.WHITE, amber, Color.rgb(255, 120, 220))
        if (flashes.size < 20) flashes.add(Flash(x, y, colors[Random.nextInt(colors.size)], Random.nextBoolean(), rf(16f, 40f)))
    }

    private fun updateFlashes(dt: Float) {
        var i = flashes.size - 1
        while (i >= 0) {
            flashes[i].life -= dt
            if (flashes[i].life <= 0f) flashes.removeAt(i)
            i--
        }
    }

    // ================= Drawing =================
    private fun rf(a: Float, b: Float) = a + Random.nextFloat() * (b - a)

    fun draw(c: Canvas) {
        val s = screenH / viewH
        c.save()
        c.scale(s, s)

        drawBackground(c)

        val inRun = screen != Screen.PROFILE && screen != Screen.SETTINGS && screen != Screen.MAP &&
            screen != Screen.HANGAR && screen != Screen.AWARDS
        if (inRun) {
            c.save()
            val sx = if (shake > 0f) rf(-shake, shake) else 0f
            val sy = if (shake > 0f) rf(-shake, shake) else 0f
            c.translate(-camX + sx, sy)
            drawWorld(c)
            c.restore()
            if (darkness > 0f) {
                darkPaint.alpha = (245 * darkness).toInt()
                c.drawRect(0f, 0f, viewW, viewH, darkPaint)
            }
            c.save()
            c.translate(-camX, 0f)
            drawPlayer(c)
            c.restore()
            drawFlashes(c)
        }

        when (screen) {
            Screen.MENU -> drawMenu(c)
            Screen.MAP -> drawMap(c)
            Screen.HANGAR -> drawHangar(c)
            Screen.AWARDS -> drawAwards(c)
            Screen.PROFILE -> drawProfile(c)
            Screen.SETTINGS -> drawSettings(c)
            Screen.INTRO -> { drawHud(c); drawIntro(c) }
            Screen.PLAYING -> { drawHud(c); drawControls(c) }
            Screen.PAUSED -> { drawHud(c); drawPaused(c) }
            Screen.DEAD -> { drawHud(c); drawDead(c) }
            Screen.CLEAR -> { drawHud(c); drawClear(c) }
        }
        if (screen == Screen.SETTINGS) drawFloatsScreen(c)
        drawToasts(c)
        c.restore()
    }

    private fun drawBackground(c: Canvas) {
        applyBackground()
        c.drawRect(0f, 0f, viewW, viewH, bgPaint)
        when (bgId) {
            "bg_nebula" -> {
                for (k in 0 until 4) {
                    val bx = ((k * 520f - camX * 0.03f) % (viewW + 600f) + viewW + 600f) % (viewW + 600f) - 300f
                    fill.color = if (k % 2 == 0) Color.rgb(160, 60, 200) else Color.rgb(230, 80, 160)
                    fill.alpha = 26
                    c.drawCircle(bx, 180f + k * 90f, 220f, fill)
                }
            }
            "bg_aurora" -> {
                stroke.strokeWidth = 18f
                for (k in 0 until 3) {
                    stroke.color = if (k == 1) Color.rgb(90, 230, 160) else Color.rgb(60, 200, 220)
                    stroke.alpha = 40
                    path.reset()
                    var x = 0f
                    while (x <= viewW) {
                        val y = 120f + k * 50f + sin(x * 0.006f + animT * 0.6f + k) * 30f
                        if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                        x += 40f
                    }
                    c.drawPath(path, stroke)
                }
            }
        }
        for (i in starX.indices) {
            if (bgId == "bg_void" && i % 3 != 0) continue
            val f = when (i % 3) { 0 -> 0.05f; 1 -> 0.12f; else -> 0.22f }
            var x = (starX[i] - camX * f) % starFieldW
            if (x < 0f) x += starFieldW
            if (x > viewW) continue
            val tw = 0.6f + 0.4f * sin(animT * 2f + i)
            starPaint.alpha = (255 * tw * (0.4f + 0.2f * (i % 3))).toInt().coerceIn(0, 255)
            c.drawCircle(x, starY[i], starS[i], starPaint)
        }
        if (bgId == "bg_void") return
        val span = viewW + 500f
        var plx = (viewW * 0.8f - camX * 0.02f) % span
        if (plx < -250f) plx += span
        c.save()
        c.translate(plx, 170f)
        when (bgId) {
            "bg_red" -> {
                fill.color = Color.rgb(240, 80, 50); fill.alpha = 40
                c.drawCircle(0f, 0f, 190f, fill)
                c.drawCircle(0f, 0f, 140f, planetPaint)
            }
            "bg_binary" -> {
                fill.color = Color.rgb(255, 180, 70); fill.alpha = 50
                c.drawCircle(0f, 0f, 120f, fill)
                c.drawCircle(0f, 0f, 70f, planetPaint)
                fill.color = Color.rgb(255, 245, 220); fill.alpha = 230
                c.drawCircle(-170f, -40f, 34f, fill)
                fill.alpha = 50
                c.drawCircle(-170f, -40f, 60f, fill)
            }
            else -> {
                c.drawCircle(0f, 0f, 90f, planetPaint)
                c.rotate(-18f)
                rect.set(-150f, -28f, 150f, 28f)
                c.drawOval(rect, ringPaint)
            }
        }
        c.restore()
    }

    private fun drawWorld(c: Canvas) {
        val viewL = camX - 50f
        val viewR = camX + viewW + 50f

        // Zones (background tints)
        for (z in world.zones) {
            if (z.x0 > viewR || z.x1 < viewL) continue
            when (z.kind) {
                ZoneKind.MIRROR -> {
                    fill.color = violet; fill.alpha = 40
                    c.drawRect(z.x0, 0f, z.x1, viewH, fill)
                    symPaint.color = violet; symPaint.textSize = 30f
                    c.drawText("MIRROR", z.x0 + 110f, 120f, symPaint)
                    symPaint.textSize = 42f
                }
                ZoneKind.WIND -> {
                    stroke.color = Color.WHITE; stroke.alpha = 70; stroke.strokeWidth = 2f
                    val dir = if (z.factor > 1f) 1f else -1f
                    val off = (animT * 400f * dir) % 120f
                    var yy = 160f
                    while (yy < 640f) {
                        var xx = z.x0 + ((off + yy * 0.7f) % 120f + 120f) % 120f
                        while (xx < z.x1) {
                            c.drawLine(xx, yy, xx + 40f, yy, stroke)
                            xx += 120f
                        }
                        yy += 70f
                    }
                    symPaint.color = Color.WHITE; symPaint.textSize = 28f
                    c.drawText(if (dir > 0f) "TAILWIND »" else "« HEADWIND", z.x0 + 120f, 130f, symPaint)
                    symPaint.textSize = 42f
                }
                ZoneKind.FLIP -> {
                    fill.color = teal; fill.alpha = 26
                    c.drawRect(z.x0, 0f, z.x1, viewH, fill)
                    fill.alpha = 200
                    c.drawRect(z.x0 - 3f, 0f, z.x0 + 3f, viewH, fill)
                    c.drawRect(z.x1 - 3f, 0f, z.x1 + 3f, viewH, fill)
                    symPaint.color = teal; symPaint.textSize = 30f
                    c.drawText("⇅ GRAVITY FLIP", z.x0 + 150f, 300f, symPaint)
                    c.drawText("⇅ NORMAL", z.x1 + 100f, 300f, symPaint)
                    symPaint.textSize = 42f
                }
                ZoneKind.ZEROG -> {
                    fill.color = blue; fill.alpha = 24
                    c.drawRect(z.x0, 0f, z.x1, viewH, fill)
                    fill.alpha = 200
                    c.drawRect(z.x0 - 3f, 0f, z.x0 + 3f, viewH, fill)
                    c.drawRect(z.x1 - 3f, 0f, z.x1 + 3f, viewH, fill)
                    symPaint.color = blue; symPaint.textSize = 30f
                    c.drawText("ZERO-G", z.x0 + 100f, 80f, symPaint)
                    symPaint.textSize = 42f
                }
                ZoneKind.CHASER -> if (z.triggered && !z.done) {
                    fill.color = danger; fill.alpha = 70
                    c.drawRect(z.wallX - 300f, 0f, z.wallX, viewH, fill)
                    fill.alpha = 220
                    c.drawRect(z.wallX - 10f, 0f, z.wallX, viewH, fill)
                }
                else -> {}
            }
        }

        // Platforms
        for (p in world.platforms) {
            if (p.x > viewR || p.x + p.w < viewL) continue
            val l = max(p.x, viewL)
            val r = min(p.x + p.w, viewR)
            val top = p.top + p.drop
            if (p.kind == PlatKind.FLOATING) {
                fill.color = Color.rgb(20, 60, 80)
                c.drawRect(l, top, r, top + 18f, fill)
                c.drawRect(l, top, r, top + 4f, edgePaint)
                continue
            }
            c.drawRect(l, top, r, viewH + 40f, bodyPaint)
            var sxl = p.x + 60f
            if (sxl < viewL) sxl += ((viewL - sxl) / 80f).toInt() * 80f
            while (sxl < r - 20f) {
                c.drawLine(sxl, top + 14f, sxl, viewH + 40f, seamPaint)
                sxl += 80f
            }
            val edgeColor = when (p.kind) {
                PlatKind.COLLAPSE -> if (p.touched && ((animT * 18f).toInt() % 2 == 0)) Color.WHITE else amber
                PlatKind.CRACKED -> amber
                PlatKind.MOVING -> violet
                else -> teal
            }
            glowPaint.color = edgeColor; glowPaint.alpha = 50
            c.drawRect(l - 2f, top - 4f, r + 2f, top + 10f, glowPaint)
            edgePaint.color = edgeColor
            c.drawRect(l, top, r, top + 5f, edgePaint)
            edgePaint.color = teal
            if (p.kind == PlatKind.CRACKED) {
                stroke.color = amber; stroke.alpha = 200; stroke.strokeWidth = 2f
                var cx = p.x + 20f
                while (cx < p.x + p.w - 20f) {
                    c.drawLine(cx, top + 2f, cx + 14f, top + 16f, stroke)
                    c.drawLine(cx + 14f, top + 16f, cx + 6f, top + 30f, stroke)
                    cx += 46f
                }
            }
        }

        // Roofs (reverse gravity)
        for (r in world.roofs) {
            if (r.x > viewR || r.x + r.w < viewL) continue
            val l = max(r.x, viewL)
            val rr = min(r.x + r.w, viewR)
            fill.color = Color.rgb(12, 34, 50); fill.alpha = 255
            c.drawRect(l, -40f, rr, r.bottom, fill)
            fill.color = teal; fill.alpha = 50
            c.drawRect(l - 2f, r.bottom - 10f, rr + 2f, r.bottom + 4f, fill)
            fill.alpha = 255
            c.drawRect(l, r.bottom - 5f, rr, r.bottom, fill)
        }

        // Zero-G laser gates
        for (b in world.beams) {
            if (b.x > viewR || b.x < viewL) continue
            fill.color = danger; fill.alpha = 230
            c.drawRect(b.x + 3f, b.y0, b.x + 9f, b.y1, fill)
            fill.alpha = 60
            c.drawRect(b.x - 4f, b.y0, b.x + 16f, b.y1, fill)
            fill.color = Color.rgb(60, 70, 90); fill.alpha = 255
            if (b.y0 > 0f) c.drawRect(b.x - 4f, b.y0 - 6f, b.x + 16f, b.y0 + 2f, fill)
            if (b.y1 < viewH) c.drawRect(b.x - 4f, b.y1 - 2f, b.x + 16f, b.y1 + 6f, fill)
        }

        // Bounce pads
        for (pad in world.pads) {
            if (pad.x > viewR || pad.x + pad.w < viewL) continue
            fill.color = lime
            val sq = 4f * sin(animT * 10f)
            rect.set(pad.x, pad.base - 12f - sq, pad.x + pad.w, pad.base)
            c.drawRoundRect(rect, 6f, 6f, fill)
        }

        // Finish gate
        if (world.finishX < viewR && world.finishX > viewL) {
            val gx = world.finishX
            val top = world.lastTop
            val pulse = 0.5f + 0.5f * sin(animT * 6f)
            fill.color = lime; fill.alpha = (140 + 115 * pulse).toInt()
            c.drawRect(gx - 6f, top - 220f, gx + 6f, top, fill)
            c.drawRect(gx + 54f, top - 220f, gx + 66f, top, fill)
            c.drawRect(gx - 6f, top - 232f, gx + 66f, top - 220f, fill)
        }

        // Ceilings
        for (cl in world.ceilings) {
            if (cl.x > viewR || cl.x + cl.w < viewL) continue
            if (cl.shown <= 0.01f) continue
            val yOff = -(1f - cl.shown) * 240f - cl.retract * 320f
            val bottom = cl.bottom + yOff
            fill.color = when (cl.kind) {
                CeilKind.LOW -> Color.rgb(50, 22, 40)
                else -> Color.rgb(40, 18, 34)
            }
            fill.alpha = 255
            c.drawRect(cl.x, -40f, cl.x + cl.w, bottom, fill)
            stroke.color = danger; stroke.alpha = 90; stroke.strokeWidth = 6f
            var sx = cl.x + 10f
            while (sx < cl.x + cl.w - 10f) {
                c.drawLine(sx, bottom - 22f, sx + 16f, bottom - 6f, stroke)
                sx += 28f
            }
            fill.color = danger
            c.drawRect(cl.x, bottom - 5f, cl.x + cl.w, bottom, fill)
        }

        // Reveal symbols
        for (r in world.reveals) {
            if (r.x > viewR || r.x < viewL) continue
            val cx = r.x + 50f
            when (r.kind) {
                RevealKind.GATE -> if (!r.revealed) {
                    fill.color = amber; fill.alpha = 60
                    c.drawRect(r.x, r.base - 140f, r.x + 100f, r.base, fill)
                    symPaint.color = amber
                    c.drawText("?", cx, r.base - 60f, symPaint)
                }
                RevealKind.SIGNAL_JUMP -> if (r.shown) {
                    symPaint.color = danger
                    c.drawText("▲", cx, r.base - 150f, symPaint)
                }
                RevealKind.SIGNAL_SLIDE -> if (r.shown) {
                    symPaint.color = blue
                    c.drawText("▼", cx, r.base - 150f, symPaint)
                }
                RevealKind.FAKEOUT -> {}
            }
        }

        // Spikes
        for (s in world.spikes) {
            if (s.x > viewR || s.x + s.w < viewL) continue
            if (s.auto && s.triggered && s.raise < 1f) drawWarning(c, s.x, s.w, s.base, s.warn)
            if (s.raise > 0f) drawSpikes(c, s.x, s.w, s.base, if (s.down) -spikeH * s.raise else spikeH * s.raise)
        }

        // Decoys
        for (d in world.decoys) {
            if (d.x > viewR || d.x + d.w < viewL) continue
            if (d.triggered && d.warn < 0.6f) drawWarning(c, d.x, d.w, d.base, d.warn)
        }

        // Laser fences
        for (f in world.fences) {
            if (f.x > viewR || f.x < viewL) continue
            fill.color = Color.rgb(60, 70, 90)
            c.drawRect(f.x - 4f, f.base - 60f, f.x + 8f, f.base - 52f, fill)
            c.drawRect(f.x - 4f, f.base - 8f, f.x + 8f, f.base, fill)
            if (f.on(runT)) {
                fill.color = danger; fill.alpha = 230
                c.drawRect(f.x - 1f, f.base - 54f, f.x + 5f, f.base - 6f, fill)
                fill.alpha = 70
                c.drawRect(f.x - 8f, f.base - 54f, f.x + 12f, f.base - 6f, fill)
            } else {
                fill.color = danger; fill.alpha = 50
                c.drawRect(f.x + 1f, f.base - 54f, f.x + 3f, f.base - 6f, fill)
            }
        }

        // Phase walls
        for (w in world.walls) {
            if (w.x > viewR || w.x < viewL) continue
            val pulse = 0.5f + 0.5f * sin(animT * 9f + w.x)
            fill.color = Color.rgb(90, 220, 255); fill.alpha = (110 + 90 * pulse).toInt()
            c.drawRect(w.x, 0f, w.x + 16f, viewH, fill)
            fill.alpha = 40
            c.drawRect(w.x - 14f, 0f, w.x + 30f, viewH, fill)
        }

        // Doors
        for (d in world.doors) {
            if (d.x > viewR || d.x < viewL) continue
            fill.color = Color.rgb(70, 80, 100); fill.alpha = 255
            c.drawRect(d.x - 6f, -40f, d.x + 36f, 40f, fill)
            val b = d.bottom()
            fill.color = Color.rgb(140, 60, 80)
            c.drawRect(d.x, -40f, d.x + 30f, b, fill)
            fill.color = danger
            c.drawRect(d.x, b - 6f, d.x + 30f, b, fill)
            if (d.triggered) {
                val k = 1f - (d.t / d.closeDur).coerceIn(0f, 1f)
                c.drawRect(d.x - 30f, d.base - 230f, d.x + 60f, d.base - 222f, barBg)
                fill.color = if (k < 0.35f) danger else amber
                c.drawRect(d.x - 30f, d.base - 230f, d.x - 30f + 90f * k, d.base - 222f, fill)
            }
        }

        // Meteors
        for (m in world.meteors) {
            if (m.x > viewR || m.x + m.size < viewL) continue
            if (!m.triggered) continue
            val k = (m.t / m.fallDur).coerceIn(0f, 1f)
            fill.color = Color.BLACK; fill.alpha = (60 + 120 * k).toInt()
            rect.set(m.x - 10f + 10f * k, m.base - 6f, m.x + m.size + 10f - 10f * k, m.base + 4f)
            c.drawOval(rect, fill)
            if (k < 1f) {
                fill.color = amber; fill.alpha = 120
                c.drawRect(m.x + 10f, m.y - 90f, m.x + m.size - 10f, m.y, fill)
            }
            fill.color = Color.rgb(120, 90, 80); fill.alpha = 255
            rect.set(m.x, m.y, m.x + m.size, m.y + m.size)
            c.drawRoundRect(rect, 12f, 12f, fill)
            fill.color = Color.rgb(80, 60, 55)
            c.drawCircle(m.x + 14f, m.y + 16f, 5f, fill)
            c.drawCircle(m.x + 30f, m.y + 28f, 4f, fill)
        }

        // Mines
        for (m in world.mines) {
            if (m.x > viewR || m.x < viewL) continue
            val pulse = 0.5f + 0.5f * sin(animT * 8f)
            c.save()
            c.translate(m.x, m.y)
            c.rotate(animT * 120f)
            stroke.color = danger; stroke.alpha = 255; stroke.strokeWidth = 4f
            for (k in 0 until 8) {
                c.drawLine(0f, mineR - 2f, 0f, mineR + 8f, stroke)
                c.rotate(45f)
            }
            c.restore()
            fill.color = danger; fill.alpha = 255
            c.drawCircle(m.x, m.y, mineR, fill)
            fill.color = Color.rgb(255, 210, 220)
            c.drawCircle(m.x, m.y, 5f + 3f * pulse, fill)
        }

        // Shards
        fill.color = lime; fill.alpha = 255
        for (s in world.shards) {
            if (s.taken || s.x > viewR || s.x < viewL) continue
            val bob = sin(animT * 4f + s.x * 0.01f) * 5f
            c.save()
            c.translate(s.x, s.y + bob)
            c.rotate(animT * 90f)
            path.reset()
            path.moveTo(0f, -12f); path.lineTo(8f, 0f); path.lineTo(0f, 12f); path.lineTo(-8f, 0f); path.close()
            c.drawPath(path, fill)
            c.restore()
        }

        drawCues(c)

        for (p in particles) drawParticle(c, p)

        for (f in floats) {
            floatPaint.color = f.color
            floatPaint.alpha = (255 * (f.life / 1.1f)).toInt().coerceIn(0, 255)
            c.drawText(f.text, f.x, f.y, floatPaint)
        }
    }

    private fun drawParticle(c: Canvas, p: Particle) {
        val a = (p.life / p.maxLife).coerceIn(0f, 1f)
        val r = p.size * (0.4f + 0.6f * a)
        when (p.shape) {
            1 -> { fill.color = p.color; fill.alpha = (255 * a).toInt(); c.drawRect(p.x - r, p.y - r, p.x + r, p.y + r, fill) }
            2 -> { fill.color = p.color; fill.alpha = (255 * a).toInt(); drawStar(c, p.x, p.y, r * 1.4f, p.color) }
            3 -> { stroke.color = p.color; stroke.alpha = (255 * a).toInt(); stroke.strokeWidth = 2f; c.drawCircle(p.x, p.y, r, stroke) }
            else -> { fill.color = p.color; fill.alpha = (255 * a).toInt(); c.drawCircle(p.x, p.y, r, fill) }
        }
    }

    private fun drawSpikes(c: Canvas, x: Float, w: Float, base: Float, h: Float) {
        val n = max(1, (w / 20f).toInt())
        val tw = w / n
        path.reset()
        for (k in 0 until n) {
            val bx = x + k * tw
            path.moveTo(bx, base)
            path.lineTo(bx + tw / 2f, base - h)
            path.lineTo(bx + tw, base)
            path.close()
        }
        fill.color = danger; fill.alpha = 255
        c.drawPath(path, fill)
    }

    private fun drawWarning(c: Canvas, x: Float, w: Float, base: Float, t: Float) {
        val on = ((t * 20f).toInt() % 2) == 0
        fill.color = danger
        fill.alpha = if (on) 255 else 90
        c.drawRect(x - 4f, base - 3f, x + w + 4f, base + 6f, fill)
        symPaint.color = danger
        c.drawText("!", x + w / 2f, base - 40f, symPaint)
    }

    private fun drawPlayer(c: Canvas) {
        if (screen == Screen.DEAD) return
        val bobY = if (screen == Screen.MENU) sin(animT * 3f) * 4f else 0f
        val sqX = 1f + squash * 0.6f
        val sqY = 1f - squash * 0.6f
        c.save()
        if (flipped) {
            // Upside down: anchored at the head, which touches the roof.
            c.translate(px, fy - ph)
            c.scale(sqX, -sqY)
        } else {
            c.translate(px, fy + bobY)
            c.scale(sqX, sqY)
        }
        if (spin > 0f) {
            c.translate(0f, -ph / 2f)
            c.rotate(360f - spin)
            c.translate(0f, ph / 2f)
        }
        if (slideEnergy < slideMax && !flipped) {
            // slide meter under the player (grey while recharging from empty)
            val k = (slideEnergy / slideMax).coerceIn(0f, 1f)
            fill.color = Color.rgb(20, 40, 58); fill.alpha = 200
            c.drawRect(-24f, 6f, 24f, 11f, fill)
            fill.color = if (slideEmpty) muted else blue; fill.alpha = 220
            c.drawRect(-24f, 6f, -24f + 48f * k, 11f, fill)
        }
        val w = if (sliding) pw * 1.25f else pw
        val col = if (dashT > 0f) Color.WHITE else bodyColor()
        drawBody(c, meta.equippedItem(Slot.BODY).id, w, ph, col)
        drawEyes(c, meta.equippedItem(Slot.EYES).id, w, ph, eyeColor(col))
        c.restore()
    }

    private fun eyeColor(body: Int): Int = when {
        mirror -> violet
        body == lime -> Color.rgb(4, 40, 30)
        else -> lime
    }

    /** Draw a player figure centred on x, feet at y. Used by the Hangar previews. */
    private fun drawAvatar(c: Canvas, x: Float, y: Float, scale: Float, body: String, eyes: String, color: Int, locked: Boolean) {
        c.save()
        c.translate(x, y)
        c.scale(scale, scale)
        drawBody(c, body, pw, standH, if (locked) Color.rgb(60, 76, 92) else color)
        drawEyes(c, eyes, pw, standH, if (locked) Color.rgb(90, 106, 122) else eyeColor(color))
        c.restore()
    }

    private fun drawBody(c: Canvas, id: String, w: Float, h: Float, color: Int) {
        fill.color = color
        fill.alpha = 255
        val l = -w / 2f
        val r = w / 2f
        val t = -h
        path.reset()
        when (id) {
            "body_orb" -> { rect.set(l, t, r, 0f); c.drawOval(rect, fill); return }
            "body_capsule" -> { rect.set(l, t, r, 0f); c.drawRoundRect(rect, w / 2f, w / 2f, fill); return }
            "body_diamond" -> { path.moveTo(0f, t); path.lineTo(r, t / 2f); path.lineTo(0f, 0f); path.lineTo(l, t / 2f) }
            "body_tri" -> { path.moveTo(l, t); path.lineTo(r + 4f, t / 2f); path.lineTo(l, 0f) }
            "body_hex" -> {
                for (k in 0 until 6) {
                    val a = k * 1.0472f
                    val hx = cos(a) * w * 0.55f
                    val hy = t / 2f + sin(a) * h * 0.55f
                    if (k == 0) path.moveTo(hx, hy) else path.lineTo(hx, hy)
                }
            }
            "body_star" -> {
                for (k in 0 until 10) {
                    val a = -1.5708f + k * 0.6283f
                    val rr = if (k % 2 == 0) h * 0.62f else h * 0.28f
                    val sx = cos(a) * rr
                    val sy = t / 2f + sin(a) * rr
                    if (k == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                }
            }
            else -> { rect.set(l, t, r, 0f); c.drawRoundRect(rect, 10f, 10f, fill); return }
        }
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawEyes(c: Canvas, id: String, w: Float, h: Float, color: Int) {
        val dark = Color.rgb(4, 12, 22)
        val ey = -h * 0.62f
        fill.alpha = 255
        when (id) {
            "eyes_dot" -> { fill.color = color; c.drawCircle(w * 0.18f, ey, 5f, fill) }
            "eyes_twin" -> {
                fill.color = color
                c.drawCircle(w * 0.02f, ey, 4f, fill)
                c.drawCircle(w * 0.28f, ey, 4f, fill)
            }
            "eyes_slit" -> { fill.color = color; c.drawRect(0f, ey - 2f, w * 0.42f, ey + 2f, fill) }
            "eyes_cyclops" -> {
                fill.color = color; c.drawCircle(w * 0.12f, ey, 8f, fill)
                fill.color = dark; c.drawCircle(w * 0.18f, ey, 3.5f, fill)
            }
            "eyes_shades" -> {
                fill.color = dark; rect.set(-w * 0.12f, ey - 6f, w * 0.46f, ey + 5f); c.drawRoundRect(rect, 3f, 3f, fill)
                fill.color = color; c.drawRect(w * 0.26f, ey - 4f, w * 0.34f, ey - 1f, fill)
            }
            "eyes_x" -> {
                stroke.color = color; stroke.alpha = 255; stroke.strokeWidth = 3f
                val cx = w * 0.18f
                c.drawLine(cx - 6f, ey - 6f, cx + 6f, ey + 6f, stroke)
                c.drawLine(cx - 6f, ey + 6f, cx + 6f, ey - 6f, stroke)
            }
            else -> {
                fill.color = color
                rect.set(2f, -h + h * 0.25f, w / 2f - 4f, -h + h * 0.25f + 10f)
                c.drawRoundRect(rect, 4f, 4f, fill)
            }
        }
    }

    private fun drawTrailSample(c: Canvas, id: String, x: Float, y: Float, color: Int) {
        for (k in 0 until 6) {
            val tx = x - 40f - k * 22f
            val a = 1f - k / 6f
            fill.color = color; fill.alpha = (200 * a).toInt()
            when (id) {
                "trail_dots" -> if (k % 2 == 0) c.drawCircle(tx, y, 5f, fill)
                "trail_ribbon" -> c.drawRect(tx - 11f, y - 2f, tx + 11f, y + 2f, fill)
                "trail_stars" -> drawStar(c, tx, y + (if (k % 2 == 0) -5f else 5f), 7f * a + 2f, color)
                "trail_pixels" -> c.drawRect(tx - 5f * a, y - 5f * a, tx + 5f * a, y + 5f * a, fill)
                "trail_bubbles" -> {
                    stroke.color = color; stroke.alpha = (220 * a).toInt(); stroke.strokeWidth = 2f
                    c.drawCircle(tx, y - k * 4f, 4f + k, stroke)
                }
                "trail_comet" -> c.drawCircle(tx, y, 11f * a + 2f, fill)
                else -> c.drawCircle(tx, y + (if (k % 2 == 0) -3f else 3f), 4f * a + 1f, fill)
            }
        }
    }

    /** Small preview of a background. */
    private fun drawSpaceSwatch(c: Canvas, id: String, l: Float, t: Float, r: Float, b: Float) {
        val cols = spaceColors(id)
        fill.shader = LinearGradient(0f, t, 0f, b, intArrayOf(cols[0], cols[1], cols[2]), null, Shader.TileMode.CLAMP)
        fill.alpha = 255
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, 12f, 12f, fill)
        fill.shader = null
        if (id != "bg_void") {
            fill.color = cols[3]; fill.alpha = 255
            c.drawCircle(r - (r - l) * 0.25f, t + (b - t) * 0.35f, (b - t) * 0.2f, fill)
            if (id == "bg_binary") { fill.color = Color.rgb(255, 245, 220); c.drawCircle(r - (r - l) * 0.45f, t + (b - t) * 0.25f, (b - t) * 0.1f, fill) }
        }
        fill.color = Color.WHITE
        for (k in 0 until 8) {
            fill.alpha = 150
            c.drawCircle(l + (r - l) * ((k * 37 % 100) / 100f), t + (b - t) * ((k * 53 % 90) / 100f), 1.5f, fill)
        }
    }

    /** [top, middle, bottom, planet] colours for each background. */
    private fun spaceColors(id: String): IntArray = when (id) {
        "bg_nebula" -> intArrayOf(Color.rgb(14, 4, 26), Color.rgb(44, 12, 60), Color.rgb(70, 20, 70), Color.rgb(220, 90, 200))
        "bg_ocean" -> intArrayOf(Color.rgb(2, 10, 26), Color.rgb(6, 30, 60), Color.rgb(10, 50, 90), Color.rgb(40, 140, 230))
        "bg_aurora" -> intArrayOf(Color.rgb(2, 12, 14), Color.rgb(6, 34, 34), Color.rgb(10, 50, 44), Color.rgb(90, 230, 160))
        "bg_red" -> intArrayOf(Color.rgb(20, 3, 6), Color.rgb(50, 8, 14), Color.rgb(80, 16, 18), Color.rgb(240, 80, 50))
        "bg_binary" -> intArrayOf(Color.rgb(4, 8, 22), Color.rgb(16, 22, 48), Color.rgb(34, 30, 60), Color.rgb(255, 180, 70))
        "bg_void" -> intArrayOf(Color.rgb(0, 0, 0), Color.rgb(2, 2, 6), Color.rgb(4, 4, 10), Color.BLACK)
        else -> intArrayOf(Color.rgb(3, 6, 16), Color.rgb(8, 22, 42), Color.rgb(14, 30, 52), Color.rgb(40, 110, 130))
    }

    private fun applyBackground() {
        val id = meta.equippedItem(Slot.SPACE).id
        if (id == bgId) return
        bgId = id
        val cols = spaceColors(id)
        bgPaint.shader = LinearGradient(0f, 0f, 0f, 720f, intArrayOf(cols[0], cols[1], cols[2]), null, Shader.TileMode.CLAMP)
        val pc = cols[3]
        val pr = Color.red(pc); val pg = Color.green(pc); val pb = Color.blue(pc)
        planetPaint.shader = RadialGradient(
            -30f, -30f, 130f,
            intArrayOf(pc, Color.rgb(pr / 3, pg / 3, pb / 3), Color.rgb(pr / 8, pg / 8, pb / 8)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun drawFlashes(c: Canvas) {
        for (f in flashes) {
            fill.color = f.color
            fill.alpha = (255 * (f.life / 0.45f)).toInt().coerceIn(0, 255)
            if (f.star) {
                path.reset()
                for (k in 0 until 8) {
                    val a = k * 0.785f + animT
                    val r = if (k % 2 == 0) f.size else f.size * 0.4f
                    val x = f.x + cos(a) * r
                    val y = f.y + sin(a) * r
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, fill)
            } else {
                c.drawCircle(f.x, f.y, f.size, fill)
            }
        }
    }

    private fun drawFloatsScreen(c: Canvas) {
        for (f in floats) {
            floatPaint.color = f.color
            floatPaint.alpha = (255 * (f.life / 1.1f)).toInt().coerceIn(0, 255)
            c.drawText(f.text, f.x, f.y, floatPaint)
        }
    }

    // ---------- HUD & overlays ----------
    private fun drawHud(c: Canvas) {
        val p = profile
        if (mode == Mode.ENDLESS || p == null) {
            c.drawText("$score", 28f, 52f, hudPaint)
            c.drawText("BEST $best", viewW - 110f, 46f, hudRight)
        } else {
            c.drawText("${p.phaseName()}  ${p.level + 1}/${p.phaseLength()}", 28f, 46f, hudPaint)
            val sub = (if (p.expert) "DEEP SPACE • " else "ORBIT • ") + "Stage ${p.cycle + 1}"
            c.drawText(sub, 28f, 74f, hudSmall)
            if (p.expert) {
                for (i in 0 until TrainerProfile.MAX_LIVES) {
                    fill.color = danger
                    fill.alpha = if (i < p.lives) 255 else 60
                    drawHeart(c, viewW - 150f - i * 34f, 40f)
                }
            }
            val prog = if (world.finishX < Float.MAX_VALUE / 8f)
                ((px - startX) / (world.finishX - startX)).coerceIn(0f, 1f)
            else ((px - startX) / (world.levelEndX + cfg.speed * 0.6f - startX)).coerceIn(0f, 0.98f)
            val bl = viewW * 0.3f
            val br = viewW * 0.62f
            rect.set(bl, 30f, br, 40f)
            c.drawRoundRect(rect, 5f, 5f, barBg)
            fill.color = teal; fill.alpha = 255
            rect.set(bl, 30f, bl + (br - bl) * prog, 40f)
            c.drawRoundRect(rect, 5f, 5f, fill)
        }
        if (flipped || zeroG) {
            symPaint.color = if (flipped) teal else blue; symPaint.textSize = 26f
            c.drawText(if (flipped) "⇅ REVERSE GRAVITY: JUMP PUSHES DOWN" else "ZERO-G: JUMP = UP • SLIDE = DOWN", viewW / 2f, 76f, symPaint)
            symPaint.textSize = 42f
        }
        if (surgeFlash > 0f) {
            symPaint.color = amber
            c.drawText("SURGE!", viewW / 2f, 110f, symPaint)
        }
        if (screen == Screen.PLAYING) {
            fill.color = teal; fill.alpha = 160
            c.drawRect(viewW - 62f, 26f, viewW - 52f, 58f, fill)
            c.drawRect(viewW - 42f, 26f, viewW - 32f, 58f, fill)
        }
    }

    private fun drawControls(c: Canvas) {
        val ready = dashCd <= 0f
        if (buttonsLayout) {
            fun pad(r: RectF, label: String, color: Int, on: Boolean) {
                fill.color = color; fill.alpha = if (on) 70 else 30
                c.drawRoundRect(r, 24f, 24f, fill)
                btnStroke.color = color; btnStroke.alpha = if (on) 200 else 90
                c.drawRoundRect(r, 24f, 24f, btnStroke)
                btnText.textSize = 26f; btnText.alpha = if (on) 230 else 120
                c.drawText(label, r.centerX(), r.centerY() + 9f, btnText)
                btnText.textSize = 30f; btnText.alpha = 255
            }
            pad(slideBtn(), "SLIDE", blue, slideHeld)
            pad(dashBtn(), if (ready) "DASH" else "…", Color.WHITE, ready)
            pad(jumpBtn(), "JUMP", teal, jumpHeld)
            btnStroke.alpha = 255
        } else {
            leftPaint.color = muted; leftPaint.alpha = 110
            c.drawText("hold: SLIDE   •   swipe →: DASH", 28f, viewH - 24f, leftPaint)
            leftPaint.textAlign = Paint.Align.RIGHT
            c.drawText("tap: JUMP", viewW - 28f, viewH - 24f, leftPaint)
            leftPaint.textAlign = Paint.Align.LEFT
            leftPaint.alpha = 255
            stroke.color = muted; stroke.alpha = 40; stroke.strokeWidth = 2f
            c.drawLine(viewW / 2f, viewH - 60f, viewW / 2f, viewH - 10f, stroke)
            // dash readiness pip
            fill.color = if (ready) Color.WHITE else Color.rgb(60, 80, 100)
            fill.alpha = if (ready) 200 else 120
            c.drawCircle(36f, viewH - 56f, 7f, fill)
        }
    }

    private fun drawHeart(c: Canvas, x: Float, y: Float) {
        path.reset()
        path.moveTo(x, y + 12f)
        path.cubicTo(x - 18f, y, x - 10f, y - 14f, x, y - 5f)
        path.cubicTo(x + 10f, y - 14f, x + 18f, y, x, y + 12f)
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawButton(c: Canvas, r: RectF, label: String, sub: String?, accent: Int) {
        c.drawRoundRect(r, 18f, 18f, btnFill)
        btnStroke.color = accent
        btnStroke.alpha = 255
        c.drawRoundRect(r, 18f, 18f, btnStroke)
        if (sub == null) {
            c.drawText(label, r.centerX(), r.centerY() + btnText.textSize * 0.35f, btnText)
        } else {
            c.drawText(label, r.centerX(), r.centerY() + 2f, btnText)
            c.drawText(sub, r.centerX(), r.centerY() + 26f, btnSub)
        }
    }

    private fun drawMenuButton(c: Canvas) {
        btnText.textSize = 24f
        drawButton(c, menuBtnRect(), if (profile != null && mode != Mode.ENDLESS) "MAP" else "MENU", null, teal)
        btnText.textSize = 30f
    }

    private fun progressLabel(p: TrainerProfile) =
        "Stage ${p.cycle + 1} • ${p.phaseName()} ${p.level + 1}/${p.phaseLength()}"

    private fun drawMenu(c: Canvas) {
        c.drawRect(0f, 0f, viewW, viewH, dimPaint)
        c.drawText("STELLAR DASH", viewW / 2f, 150f, titlePaint)
        c.drawText("Reaction training in deep space", viewW / 2f, 194f, smallPaint)
        val tp = getProfile(false)
        val ep = getProfile(true)
        drawButton(c, menuButton(0), "DRIFT", if (best > 0) "Endless • Best $best" else "Endless • How far can you go?", teal)
        drawButton(c, menuButton(1), "ORBIT", "Training • " + progressLabel(tp), teal)
        drawButton(c, menuButton(2), "DEEP SPACE", "Hard training • " + progressLabel(ep) + " • 3 lives", danger)
        btnText.textSize = 24f
        drawButton(c, smallButton(0), "PROFILE", null, lime)
        drawButton(c, smallButton(1), "HANGAR", null, lime)
        drawButton(c, smallButton(2), "AWARDS", null, amber)
        drawButton(c, smallButton(3), "SETTINGS", null, textDim)
        btnText.textSize = 30f
        drawWallet(c)
        c.drawText("Courier, of Stellarite", viewW / 2f, viewH - 24f, creditPaint)
    }

    private fun drawWallet(c: Canvas) {
        hudRight.color = lime
        c.drawText("◆ ${meta.wallet}", viewW - 30f, 46f, hudRight)
        hudSmall.textAlign = Paint.Align.RIGHT
        c.drawText("${meta.achieved.size}/${Achievement.values().size} awards", viewW - 30f, 72f, hudSmall)
        hudSmall.textAlign = Paint.Align.LEFT
    }

    private fun drawToasts(c: Canvas) {
        val t = toasts.firstOrNull() ?: return
        val a = when {
            toastT < 0.25f -> toastT / 0.25f
            toastT > 2.8f -> ((3.2f - toastT) / 0.4f).coerceIn(0f, 1f)
            else -> 1f
        }
        floatPaint.textSize = 24f
        val w = floatPaint.measureText(t) + 60f
        val y = 22f + 14f * (1f - a)
        rect.set(viewW / 2f - w / 2f, y, viewW / 2f + w / 2f, y + 52f)
        fill.color = Color.rgb(16, 30, 20); fill.alpha = (235 * a).toInt()
        c.drawRoundRect(rect, 26f, 26f, fill)
        btnStroke.color = amber; btnStroke.alpha = (255 * a).toInt()
        c.drawRoundRect(rect, 26f, 26f, btnStroke)
        btnStroke.alpha = 255
        floatPaint.color = amber; floatPaint.alpha = (255 * a).toInt()
        c.drawText(t, viewW / 2f, y + 34f, floatPaint)
    }

    private val pauseTips = listOf(
        "Hold jump for a full jump. A quick tap gives a short hop.",
        "You can double jump once in the air, and air-dash once per jump.",
        "Slide while airborne to slam down fast.",
        "The slide meter lasts 2 s and refills in 2 s. Run it dry and you wait for a full refill.",
        "Jumping ends a slide. Press slide again after you land.",
        "Dashing only protects you against phase walls.",
        "Red ▲ means jump, blue ▼ means slide.",
        "A ! under a crusher ceiling is a decoy. Don't jump.",
        "Some low bars lift away to reveal spikes. Be ready to switch to a jump.",
        "In reverse gravity you run on the roof, and jump pushes you down.",
        "In zero-G, tap jump to float up and slide to sink.",
        "Blackouts fade in. Memorise the track before the lights go.",
        "Every 10 training levels Orbit re-evaluates you and re-aims your focus.",
        "Shards unlock cosmetics in the Hangar. Some items come only from awards.",
        "Every attempt counts as data. Deaths teach the trainer what to focus on."
    )

    private fun drawPaused(c: Canvas) {
        c.drawRect(0f, 0f, viewW, viewH, dimPaint)
        val cx = viewW / 2f
        headPaint.textSize = 46f
        c.drawText("PAUSED", cx, 96f, headPaint)
        headPaint.textSize = 54f

        // Big resume square
        val r = resumeRect()
        val pulse = 0.5f + 0.5f * sin(animT * 3f)
        fill.color = teal; fill.alpha = (30 + 25 * pulse).toInt()
        rect.set(r.left - 10f, r.top - 10f, r.right + 10f, r.bottom + 10f)
        c.drawRoundRect(rect, 40f, 40f, fill)
        c.drawRoundRect(r, 34f, 34f, btnFill)
        btnStroke.color = teal; btnStroke.alpha = 255; btnStroke.strokeWidth = 4f
        c.drawRoundRect(r, 34f, 34f, btnStroke)
        btnStroke.strokeWidth = 3f
        path.reset()
        path.moveTo(cx - 34f, r.centerY() - 52f)
        path.lineTo(cx + 50f, r.centerY() - 6f)
        path.lineTo(cx - 34f, r.centerY() + 40f)
        path.close()
        fill.color = teal; fill.alpha = 255
        c.drawPath(path, fill)
        btnText.textSize = 28f
        c.drawText("RESUME", cx, r.bottom - 26f, btnText)

        // Home / Map / Settings stack
        val items = pauseItems()
        for (i in items.indices) {
            val col = when (items[i]) { "HOME" -> teal; "MAP" -> lime; else -> textDim }
            drawButton(c, pauseBtn(i), items[i], null, col)
        }
        btnText.textSize = 30f

        // Rotating tips
        val period = 4.5f
        val idx = ((stateT / period).toInt()) % pauseTips.size
        val ph2 = stateT % period
        val a = when {
            ph2 < 0.4f -> ph2 / 0.4f
            ph2 > period - 0.4f -> (period - ph2) / 0.4f
            else -> 1f
        }
        smallPaint.alpha = (255 * a).toInt()
        c.drawText("TIP: " + pauseTips[idx], cx, viewH - 34f, smallPaint)
        smallPaint.alpha = 255
    }

    // ---------- Hangar ----------
    private fun hangarTab(i: Int): RectF {
        val w = 150f
        val gap = 10f
        val x0 = viewW / 2f - (5 * w + 4 * gap) / 2f + i * (w + gap)
        return RectF(x0, 96f, x0 + w, 142f)
    }

    private fun hangarCols(): Int = ((viewW - 40f - 400f + 16f) / 196f).toInt().coerceAtLeast(2)

    private fun hangarCard(i: Int): RectF {
        val cols = hangarCols()
        val x = 400f + (i % cols) * 196f
        val y = 168f + (i / cols) * 146f
        return RectF(x, y, x + 180f, y + 132f)
    }

    private fun hangarTap(t: FloatArray) {
        if (hit(backBtnRect(), t)) { go(Screen.MENU); return }
        val slots = Slot.values()
        for (i in slots.indices) if (hit(hangarTab(i), t)) { hangarSlot = slots[i]; buyConfirm = null; return }
        val items = Catalog.slot(hangarSlot)
        for (i in items.indices) {
            if (!hit(hangarCard(i), t)) continue
            val it = items[i]
            when {
                meta.isOwned(it) -> { meta.equipped[it.slot] = it.id; buyConfirm = null; saveMeta() }
                it.achievement != null -> toasts.add("Locked: earn \"${it.achievement.title}\" — ${it.achievement.desc}")
                meta.wallet < it.cost -> toasts.add("Need ${it.cost - meta.wallet} more ◆ shards for ${it.name}")
                buyConfirm == it.id -> {
                    if (meta.buy(it)) { saveMeta(); toasts.add("Unlocked ${it.name}!"); sound(Sfx.Sound.SHARD) }
                    buyConfirm = null
                }
                else -> buyConfirm = it.id
            }
            if (toasts.size > 3) toasts.removeAt(0)
            return
        }
    }

    private fun drawHangar(c: Canvas) {
        val cx = viewW / 2f
        headPaint.textSize = 44f
        c.drawText("HANGAR", cx, 70f, headPaint)
        headPaint.textSize = 54f
        btnText.textSize = 24f
        drawButton(c, backBtnRect(), "‹ BACK", null, teal)
        val slots = Slot.values()
        btnText.textSize = 22f
        for (i in slots.indices) drawButton(c, hangarTab(i), slots[i].label.uppercase(), null, if (slots[i] == hangarSlot) lime else muted)
        btnText.textSize = 30f
        drawWallet(c)

        // Preview
        rect.set(40f, 168f, 370f, 660f)
        c.drawRoundRect(rect, 22f, 22f, panelPaint)
        val bg = meta.equippedItem(Slot.SPACE)
        drawSpaceSwatch(c, bg.id, 60f, 188f, 350f, 330f)
        drawTrailSample(c, meta.equippedItem(Slot.TRAIL).id, 205f, 470f, bodyColor())
        drawAvatar(c, 205f, 500f, 2.6f, meta.equippedItem(Slot.BODY).id, meta.equippedItem(Slot.EYES).id, bodyColor(), false)
        leftPaint.color = textDim
        leftPaint.textAlign = Paint.Align.CENTER
        c.drawText(meta.equippedItem(Slot.BODY).name + " • " + meta.equippedItem(Slot.EYES).name, 205f, 560f, leftPaint)
        c.drawText(meta.equippedItem(Slot.COLOR).name + " • " + meta.equippedItem(Slot.TRAIL).name, 205f, 590f, leftPaint)
        c.drawText(bg.name, 205f, 620f, leftPaint)
        leftPaint.textAlign = Paint.Align.LEFT

        // Cards
        val items = Catalog.slot(hangarSlot)
        for (i in items.indices) {
            val it = items[i]
            val r = hangarCard(i)
            val equipped = meta.equipped[it.slot] == it.id
            val owned = meta.isOwned(it)
            c.drawRoundRect(r, 16f, 16f, btnFill)
            btnStroke.color = when {
                equipped -> lime
                buyConfirm == it.id -> amber
                owned -> teal
                else -> Color.rgb(50, 70, 90)
            }
            c.drawRoundRect(r, 16f, 16f, btnStroke)
            val px2 = r.centerX()
            val py2 = r.top + 70f
            when (it.slot) {
                Slot.BODY -> drawAvatar(c, px2, py2, 1.3f, it.id, meta.equippedItem(Slot.EYES).id, bodyColor(), !owned)
                Slot.EYES -> drawAvatar(c, px2, py2, 1.3f, meta.equippedItem(Slot.BODY).id, it.id, bodyColor(), !owned)
                Slot.COLOR -> drawAvatar(c, px2, py2, 1.3f, meta.equippedItem(Slot.BODY).id, meta.equippedItem(Slot.EYES).id, it.color, !owned)
                Slot.TRAIL -> drawTrailSample(c, it.id, px2 + 30f, py2 - 22f, bodyColor())
                Slot.SPACE -> drawSpaceSwatch(c, it.id, r.left + 14f, r.top + 12f, r.right - 14f, r.top + 82f)
            }
            leftPaint.textAlign = Paint.Align.CENTER
            leftPaint.color = Color.WHITE; leftPaint.textSize = 18f
            c.drawText(it.name, px2, r.bottom - 32f, leftPaint)
            leftPaint.textSize = 15f
            val ach = it.achievement
            val (status, col) = when {
                equipped -> "EQUIPPED" to lime
                owned -> "TAP TO EQUIP" to teal
                ach != null -> "★ ${ach.title}" to amber
                buyConfirm == it.id -> "TAP AGAIN: ◆ ${it.cost}" to amber
                else -> "◆ ${it.cost}" to (if (meta.wallet >= it.cost) Color.WHITE else muted)
            }
            leftPaint.color = col
            c.drawText(status, px2, r.bottom - 12f, leftPaint)
            leftPaint.textSize = 21f
            leftPaint.textAlign = Paint.Align.LEFT
        }
    }

    // ---------- Awards ----------
    private fun drawAwards(c: Canvas) {
        val cx = viewW / 2f
        headPaint.textSize = 44f
        c.drawText("AWARDS", cx, 70f, headPaint)
        headPaint.textSize = 54f
        btnText.textSize = 24f
        drawButton(c, backBtnRect(), "‹ BACK", null, teal)
        btnText.textSize = 30f
        drawWallet(c)
        val list = Achievement.values()
        val rows = (list.size + 1) / 2
        val colW = (viewW - 120f) / 2f
        val rowH = min(52f, (viewH - 150f) / rows)
        for (i in list.indices) {
            val a = list[i]
            val col = i / rows
            val row = i % rows
            val x = 50f + col * (colW + 20f)
            val y = 118f + row * rowH
            val got = meta.achieved.contains(a)
            fill.alpha = 255
            drawStar(c, x + 14f, y + 16f, 12f, if (got) amber else Color.rgb(50, 66, 84))
            leftPaint.color = if (got) Color.WHITE else muted
            leftPaint.textSize = 19f
            c.drawText(a.title, x + 36f, y + 16f, leftPaint)
            leftPaint.textSize = 14f
            leftPaint.color = muted
            val reward = Catalog.rewardFor(a)
            c.drawText(a.desc + (if (reward != null) "  →  ${reward.name}" else ""), x + 36f, y + 35f, leftPaint)
            leftPaint.textSize = 21f
        }
    }

    private fun panel(c: Canvas, w: Float, h: Float) {
        c.drawRect(0f, 0f, viewW, viewH, dimPaint)
        rect.set(viewW / 2f - w / 2f, viewH / 2f - h / 2f, viewW / 2f + w / 2f, viewH / 2f + h / 2f)
        c.drawRoundRect(rect, 24f, 24f, panelPaint)
    }

    private fun pulseAlpha() = (120 + 135 * (0.5f + 0.5f * sin(stateT * 4f))).toInt()

    private fun controlHint(uses: Int): String = when {
        uses == 1 && buttonsLayout -> "Hold SLIDE (bottom-left). 3 s meter, refills in 1 s; jumping ends a slide."
        uses == 1 -> "Hold the LEFT half to slide (3 s meter, refills in 1 s; jumping ends a slide)."
        uses == 2 && buttonsLayout -> "Press DASH (next to SLIDE, bottom-left)."
        uses == 2 -> "Swipe RIGHT on the left (slide) side to dash. Works in mid-air too."
        else -> ""
    }

    private fun drawIntro(c: Canvas) {
        val p = profile ?: return
        val hasNew = introSkills.isNotEmpty()
        val lines = introSkills.size * 2
        panel(c, min(viewW - 60f, 980f), if (hasNew) 300f + lines * 30f else 300f)
        val cx = viewW / 2f
        var y = viewH / 2f - (if (hasNew) 110f + lines * 15f else 90f)
        headPaint.color = if (p.expert) danger else teal
        c.drawText("${p.phaseName()} ${p.level + 1}", cx, y, headPaint)
        headPaint.color = teal
        y += 42f
        val desc = when (p.phase) {
            TrainerProfile.PHASE_TEST -> "Calibration: measuring your reactions"
            TrainerProfile.PHASE_TRAIN -> "Training your weak spots"
            else -> "Retest: checking your progress"
        }
        c.drawText(desc, cx, y, smallPaint)
        if (p.expert) {
            y += 32f
            c.drawText("Lives ${p.lives} • checkpoint every ${TrainerProfile.CHECKPOINT} levels", cx, y, smallPaint)
        }
        if (p.phase == TrainerProfile.PHASE_TRAIN) {
            val focus = Skill.ALL.sortedByDescending { cfg.w[it.ordinal] }.take(2)
            y += 32f
            smallPaint.color = lime
            c.drawText("Focus: " + focus.joinToString(" & ") { it.label }, cx, y, smallPaint)
            smallPaint.color = textDim
        }
        if (hasNew) {
            y += 50f
            bigPaint.color = amber
            c.drawText("NEW HAZARDS", cx, y, bigPaint)
            bigPaint.color = Color.WHITE
            for (s in introSkills) {
                y += 34f
                smallPaint.color = Color.WHITE
                c.drawText("${s.label}: ${s.tip}", cx, y, smallPaint)
                smallPaint.color = textDim
                val hint = controlHint(s.uses)
                y += 28f
                if (hint.isNotEmpty()) c.drawText(hint, cx, y, smallPaint)
            }
        }
        bigPaint.alpha = pulseAlpha()
        c.drawText("TAP TO START", cx, y + 64f, bigPaint)
        bigPaint.alpha = 255
        drawMenuButton(c)
    }


    private fun drawDead(c: Canvas) {
        val k = min(1f, stateT * 3f)
        dimPaint.alpha = (160 * k).toInt()
        c.drawRect(0f, 0f, viewW, viewH, dimPaint)
        dimPaint.alpha = 160
        val cx = viewW / 2f
        titlePaint.color = danger
        c.drawText("SIGNAL LOST", cx, 230f, titlePaint)
        titlePaint.color = teal
        c.drawText(deathReason, cx, 280f, smallPaint)
        val p = profile
        if (p == null) {
            c.drawText("SCORE  $score", cx, 350f, bigPaint)
            if (newBest) {
                hudRight.textAlign = Paint.Align.CENTER
                c.drawText("NEW BEST!", cx, 396f, hudRight)
                hudRight.textAlign = Paint.Align.RIGHT
            } else {
                c.drawText("BEST  $best", cx, 396f, smallPaint)
            }
        } else {
            c.drawText("Attempt ${p.levelAttempts} on ${p.phaseName()} ${p.level + 1}", cx, 350f, bigPaint)
            if (p.expert) {
                if (knockedBack) {
                    smallPaint.color = danger
                    c.drawText("Out of lives! Back to checkpoint: level ${p.level + 1}", cx, 396f, smallPaint)
                    smallPaint.color = textDim
                } else {
                    c.drawText("Lives left: ${p.lives}", cx, 396f, smallPaint)
                }
            } else {
                c.drawText("Every attempt is data. Restarting the level.", cx, 396f, smallPaint)
            }
        }
        if (stateT > 0.5f) {
            smallPaint.alpha = pulseAlpha()
            c.drawText("Tap to retry", cx, 470f, smallPaint)
            smallPaint.alpha = 255
        }
        drawMenuButton(c)
    }

    private fun drawClear(c: Canvas) {
        val ev = phaseEvent
        val cx = viewW / 2f
        panel(c, min(viewW - 60f, 900f), if (ev != null) 470f else 340f)
        var y = if (ev != null) 190f else 250f
        headPaint.color = lime
        c.drawText(ev?.title ?: "LEVEL CLEAR", cx, y, headPaint)
        headPaint.color = teal
        y += 50f
        c.drawText("Attempts $clearAttempts  •  Hazards passed $clearPassed/$clearTotal", cx, y, smallPaint)
        if (clearReactAvg >= 0f) {
            y += 34f
            c.drawText("Average reaction ${clearReactAvg.roundToInt()} ms", cx, y, smallPaint)
        }
        if (ev != null) {
            if (ev.rating >= 0f) {
                y += 58f
                val r = ev.rating.roundToInt()
                val txt = if (ev.prevRating >= 0f) {
                    val d = (ev.rating - ev.prevRating).roundToInt()
                    "Reflex rating ${ev.prevRating.roundToInt()} → $r  (${if (d >= 0) "+" else ""}$d)"
                } else "Reflex rating $r"
                c.drawText(txt, cx, y, bigPaint)
            }
            for (line in ev.lines) {
                y += 34f
                c.drawText(line, cx, y, smallPaint)
            }
            y += 40f
            if (ev.focus.isNotEmpty()) {
                smallPaint.color = lime
                c.drawText(ev.focusLabel + ev.focus.joinToString(" & ") { it.label }, cx, y, smallPaint)
                smallPaint.color = textDim
            } else {
                c.drawText("Time to retest: 25 levels to measure your improvement", cx, y, smallPaint)
            }
        }
        if (stateT > 0.6f) {
            bigPaint.alpha = pulseAlpha()
            c.drawText("TAP TO CONTINUE", cx, viewH / 2f + (if (ev != null) 200f else 135f), bigPaint)
            bigPaint.alpha = 255
        }
        drawMenuButton(c)
    }

    // ---------- Training hint badges ----------
    private fun hintsActive(): Boolean {
        val p = profile ?: return false
        return hintsOn && !p.expert && mode == Mode.TRAINER
    }

    private fun cueText(s: Skill): String = when (s) {
        Skill.GAP -> "JUMP"
        Skill.SPIKE -> "JUMP"
        Skill.STEP -> "HOLD JUMP"
        Skill.POPUP -> "WATCH FOR !"
        Skill.BAR -> "SLIDE"
        Skill.DOUBLE -> "DOUBLE JUMP"
        Skill.HOP -> "QUICK TAP"
        Skill.DOOR -> "DASH"
        Skill.MINE -> "TIME IT"
        Skill.DECOY -> "DON'T JUMP"
        Skill.METEOR -> "WATCH ABOVE"
        Skill.COLLAPSE -> "JUMP OFF FAST"
        Skill.SIGNAL -> "READ ▲ / ▼"
        Skill.GATE -> "READ THE ?"
        Skill.LASER -> "JUMP LIT FENCES"
        Skill.CHAIN -> "EVEN RHYTHM"
        Skill.WIDE -> "JUMP + DASH"
        Skill.BOUNCE -> "DOUBLE LATE"
        Skill.TUNNEL -> "SLIDE • HOP • SLIDE"
        Skill.CRUMBLE -> "SLIDE ACROSS"
        Skill.MOVING -> "TIME THE LANDING"
        Skill.WIND -> "ADJUST TIMING"
        Skill.FAKEOUT -> "WATCH THE BAR"
        Skill.PHASE -> "DASH THROUGH"
        Skill.BLACKOUT -> "MEMORIZE AHEAD"
        Skill.DISTRACT -> "IGNORE FLASHES"
        Skill.SURGE -> "SPEED BURST"
        Skill.SPLIT -> "PICK A ROAD"
        Skill.CHASER -> "DASH TO ESCAPE"
        Skill.COMBO -> "JUMP • DASH • SLIDE"
        Skill.RHYTHM -> "ON THE BEAT"
        Skill.MIRROR -> "CONTROLS FLIPPED"
        Skill.REVERSE -> "GRAVITY FLIP: JUMP = DOWN"
        Skill.ZEROG -> "TAP UP • SLIDE DOWN"
    }

    private fun cueColor(e: Encounter): Int = when (e.expect) {
        Action.JUMP -> teal
        Action.SLIDE -> blue
        Action.DASH -> Color.WHITE
        null -> amber
    }

    /** Badges above upcoming hazards: in training levels, and on the test level that introduces a hazard. */
    private fun drawCues(c: Canvas) {
        if (!hintsActive()) return
        val p = profile ?: return
        var shown = 0
        for (e in world.encounters) {
            if (shown >= 2) break
            if (e.resolved) continue
            val training = p.phase == TrainerProfile.PHASE_TRAIN
            if (!training && e.skill !in introSkills) continue
            val dist = e.refX - px
            if (dist < -30f || dist > effSpeed * 1.8f) continue
            // Signals/gates/fake-outs keep their answer hidden: the badge says what to watch, not what to do.
            val hideAnswer = e.skill == Skill.SIGNAL || e.skill == Skill.GATE || e.skill == Skill.FAKEOUT
            val color = if (hideAnswer) amber else cueColor(e)
            val a = when {
                dist > effSpeed * 1.5f -> ((effSpeed * 1.8f - dist) / (effSpeed * 0.3f)).coerceIn(0f, 1f)
                dist < 0f -> (1f + dist / 30f).coerceIn(0f, 1f)
                else -> 1f
            }
            val text = cueText(e.skill)
            floatPaint.textSize = 20f
            val tw = floatPaint.measureText(text) + 28f
            val bx = e.refX
            val by = e.cueY
            rect.set(bx - tw / 2f, by - 26f, bx + tw / 2f, by + 8f)
            fill.color = Color.rgb(6, 16, 30); fill.alpha = (220 * a).toInt()
            c.drawRoundRect(rect, 17f, 17f, fill)
            btnStroke.color = color; btnStroke.alpha = (255 * a).toInt()
            c.drawRoundRect(rect, 17f, 17f, btnStroke)
            btnStroke.alpha = 255
            floatPaint.color = color; floatPaint.alpha = (255 * a).toInt()
            c.drawText(text, bx, by - 3f, floatPaint)
            floatPaint.textSize = 24f
            // pointer
            path.reset()
            path.moveTo(bx - 9f, by + 8f); path.lineTo(bx + 9f, by + 8f); path.lineTo(bx, by + 20f); path.close()
            fill.color = color; fill.alpha = (255 * a).toInt()
            c.drawPath(path, fill)
            shown++
        }
    }

    // ---------- Star map ----------
    private fun mapStars(p: TrainerProfile): Array<FloatArray> {
        val r = Random(p.cycle * 1000L + p.phase * 97L + mapPage * 13L + (if (p.expert) 5000L else 0L))
        val left = 150f
        val right = viewW - 150f
        val top = 175f
        val bottom = 575f
        return Array(MAP_PAGE) { i ->
            val row = i / 5
            val colRaw = i % 5
            val col = if (row % 2 == 0) colRaw else 4 - colRaw
            val x = left + (right - left) * col / 4f + (r.nextFloat() - 0.5f) * 80f
            val y = top + (bottom - top) * row / 4f + (r.nextFloat() - 0.5f) * 50f
            floatArrayOf(x, y)
        }
    }

    private fun mapPages(p: TrainerProfile) = (p.phaseLength() + MAP_PAGE - 1) / MAP_PAGE
    private fun playBtnRect() = RectF(viewW - 270f, viewH - 96f, viewW - 30f, viewH - 26f)
    private fun pagePrevRect() = RectF(viewW / 2f - 230f, viewH - 90f, viewW / 2f - 130f, viewH - 32f)
    private fun pageNextRect() = RectF(viewW / 2f + 130f, viewH - 90f, viewW / 2f + 230f, viewH - 32f)

    private fun mapTap(t: FloatArray) {
        val p = profile ?: return
        if (hit(backBtnRect(), t)) { toMenu(); return }
        if (hit(playBtnRect(), t)) { prepareLevel(); return }
        val pages = mapPages(p)
        if (pages > 1 && hit(pagePrevRect(), t)) { mapPage = (mapPage - 1).coerceAtLeast(0); return }
        if (pages > 1 && hit(pageNextRect(), t)) { mapPage = (mapPage + 1).coerceAtMost(pages - 1); return }
        if (mapPage == p.level / MAP_PAGE) {
            val st = mapStars(p)[p.level % MAP_PAGE]
            val dx = t[0] - st[0]
            val dy = t[1] - st[1]
            if (dx * dx + dy * dy < 60f * 60f) prepareLevel()
        }
    }

    private fun drawMap(c: Canvas) {
        val p = profile ?: return
        val cx = viewW / 2f
        val stars = mapStars(p)
        val base = mapPage * MAP_PAGE
        val count = min(MAP_PAGE, p.phaseLength() - base)
        val curPage = p.level / MAP_PAGE
        val train = p.phase == TrainerProfile.PHASE_TRAIN

        headPaint.textSize = 40f
        headPaint.color = if (p.expert) danger else teal
        c.drawText((if (p.expert) "DEEP SPACE" else "ORBIT") + "  •  STAGE ${p.cycle + 1}", cx, 66f, headPaint)
        headPaint.color = teal
        headPaint.textSize = 54f
        val sector = if (mapPages(p) > 1) "  •  Sector ${mapPage + 1}/${mapPages(p)}" else ""
        c.drawText("${p.phaseName()}  —  level ${p.level + 1} of ${p.phaseLength()}$sector", cx, 104f, smallPaint)
        btnText.textSize = 24f
        drawButton(c, backBtnRect(), "‹ BACK", null, teal)
        btnText.textSize = 30f

        // links
        for (i in 0 until count - 1) {
            val g = base + i
            val a = stars[i]
            val b = stars[i + 1]
            when {
                g + 1 < p.level -> {
                    stroke.color = lime; stroke.alpha = 200; stroke.strokeWidth = 4f
                    c.drawLine(a[0], a[1], b[0], b[1], stroke)
                }
                g + 1 == p.level -> {
                    val k = if (mapAnimate && mapPage == curPage) mapLinkAnim else 1f
                    stroke.color = lime; stroke.alpha = 230; stroke.strokeWidth = 5f
                    c.drawLine(a[0], a[1], a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, stroke)
                }
                else -> {
                    stroke.color = muted; stroke.alpha = 60; stroke.strokeWidth = 2f
                    c.drawLine(a[0], a[1], b[0], b[1], stroke)
                }
            }
        }

        // stars
        for (i in 0 until count) {
            val g = base + i
            val x = stars[i][0]
            val y = stars[i][1]
            val done = g < p.level
            val current = g == p.level
            val appear = if (current && mapAnimate && mapPage == curPage) ((mapLinkAnim - 0.7f) / 0.3f).coerceIn(0f, 1f) else 1f
            if (train && (g + 1) % TrainerProfile.REEVAL_EVERY == 0) {
                stroke.color = amber; stroke.alpha = if (done) 220 else 120; stroke.strokeWidth = 2f
                path.reset()
                path.moveTo(x, y - 22f); path.lineTo(x + 22f, y); path.lineTo(x, y + 22f); path.lineTo(x - 22f, y); path.close()
                c.drawPath(path, stroke)
            }
            if (p.expert && g % TrainerProfile.CHECKPOINT == 0) {
                stroke.color = danger; stroke.alpha = if (done || current) 220 else 110; stroke.strokeWidth = 2f
                c.drawCircle(x, y, 20f, stroke)
            }
            when {
                done -> {
                    fill.color = teal; fill.alpha = 60
                    c.drawCircle(x, y, 16f, fill)
                    fill.alpha = 255
                    drawStar(c, x, y, 10f, teal)
                }
                current -> {
                    val pulse = 0.5f + 0.5f * sin(animT * 4f)
                    val col = if (p.expert) danger else lime
                    // expanding ripples
                    for (k in 0 until 3) {
                        val ph3 = (animT * 0.7f + k / 3f) % 1f
                        stroke.color = col; stroke.alpha = ((1f - ph3) * 170 * appear).toInt(); stroke.strokeWidth = 3f
                        c.drawCircle(x, y, (18f + 70f * ph3) * appear, stroke)
                    }
                    fill.color = col; fill.alpha = ((60 + 80 * pulse) * appear).toInt()
                    c.drawCircle(x, y, (26f + 8f * pulse) * appear, fill)
                    fill.alpha = (255 * appear).toInt()
                    drawStar(c, x, y, (15f + 3f * pulse) * appear, col)
                    // orbiting moon
                    val oa = animT * 2.6f
                    fill.color = Color.WHITE; fill.alpha = (230 * appear).toInt()
                    c.drawCircle(x + cos(oa) * 34f, y + sin(oa) * 14f, 4f, fill)
                    // bobbing "you are here" marker
                    val by = y - 58f - 8f * sin(animT * 4f)
                    path.reset()
                    path.moveTo(x - 12f, by - 10f); path.lineTo(x + 12f, by - 10f); path.lineTo(x, by + 6f); path.close()
                    fill.color = col; fill.alpha = (255 * appear).toInt()
                    c.drawPath(path, fill)
                    creditPaint.color = col
                    c.drawText("YOU ARE HERE", x, by - 18f, creditPaint)
                }
                else -> {
                    fill.color = muted; fill.alpha = 140
                    c.drawCircle(x, y, 4f, fill)
                }
            }
            creditPaint.color = if (current) Color.WHITE else muted
            c.drawText("${g + 1}", x, y + 38f, creditPaint)
        }
        creditPaint.color = muted

        // legend
        leftPaint.color = muted; leftPaint.textSize = 17f
        val legend = when {
            p.expert -> "★ cleared   ○ checkpoint (lives refill)" + if (train) "   ◇ re-evaluation" else ""
            train -> "★ cleared   ◇ re-evaluation every 10 levels"
            else -> "★ cleared   Clear a star to light the link to the next"
        }
        c.drawText(legend, 28f, viewH - 20f, leftPaint)
        leftPaint.textSize = 21f

        if (mapPages(p) > 1) {
            btnText.textSize = 28f
            if (mapPage > 0) drawButton(c, pagePrevRect(), "‹", null, teal)
            if (mapPage < mapPages(p) - 1) drawButton(c, pageNextRect(), "›", null, teal)
            btnText.textSize = 30f
        }
        // PLAY button: breathing glow + sliding arrow so the eye lands on it
        val pr = playBtnRect()
        val pulse = 0.5f + 0.5f * sin(animT * 3.5f)
        val pc = if (p.expert) danger else lime
        for (k in 0 until 2) {
            val g = 6f + k * 8f + 6f * pulse
            fill.color = pc; fill.alpha = (70 - k * 30 + 40 * pulse).toInt().coerceIn(0, 255)
            rect.set(pr.left - g, pr.top - g, pr.right + g, pr.bottom + g)
            c.drawRoundRect(rect, 22f + g, 22f + g, fill)
        }
        btnText.textSize = 28f
        drawButton(c, pr, "PLAY ${p.level + 1}", null, pc)
        val ax = pr.right - 34f + 8f * sin(animT * 6f)
        path.reset()
        path.moveTo(ax - 8f, pr.centerY() - 12f); path.lineTo(ax + 8f, pr.centerY()); path.lineTo(ax - 8f, pr.centerY() + 12f); path.close()
        fill.color = pc; fill.alpha = 255
        c.drawPath(path, fill)
        btnText.textSize = 30f
        // dashed guide from the current star to PLAY
        if (mapPage == curPage) {
            val st = stars[p.level % MAP_PAGE]
            val dxl = pr.left - st[0]
            val dyl = pr.centerY() - st[1]
            val len = kotlin.math.sqrt(dxl * dxl + dyl * dyl)
            if (len > 120f) {
                fill.color = pc
                val n = (len / 26f).toInt()
                val off = (animT * 1.2f) % 1f
                for (k in 2 until n - 1) {
                    val t = (k + off) / n
                    fill.alpha = (110 * (1f - t * 0.5f)).toInt()
                    c.drawCircle(st[0] + dxl * t, st[1] + dyl * t, 2.5f, fill)
                }
            }
        }
    }

    private fun drawStar(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        if (r <= 0.5f) return
        path.reset()
        for (k in 0 until 8) {
            val a = -1.5708f + k * 0.7854f
            val rr = if (k % 2 == 0) r else r * 0.38f
            val px2 = x + cos(a) * rr
            val py2 = y + sin(a) * rr
            if (k == 0) path.moveTo(px2, py2) else path.lineTo(px2, py2)
        }
        path.close()
        val keep = fill.alpha
        fill.color = color
        fill.alpha = keep
        c.drawPath(path, fill)
    }

    // ---------- Settings ----------
    private fun drawSettings(c: Canvas) {
        val cx = viewW / 2f
        headPaint.textSize = 44f
        c.drawText("SETTINGS", cx, 70f, headPaint)
        headPaint.textSize = 54f
        btnText.textSize = 24f
        drawButton(c, backBtnRect(), "‹ BACK", null, teal)

        val labelX = viewW / 2f - 380f
        fun label(row: Int, text: String, sub: String) {
            leftPaint.color = Color.WHITE; leftPaint.textSize = 26f
            c.drawText(text, labelX, settingRow(row) + 30f, leftPaint)
            leftPaint.color = muted; leftPaint.textSize = 18f
            c.drawText(sub, labelX, settingRow(row) + 56f, leftPaint)
            leftPaint.textSize = 21f
        }
        label(0, "Controls", if (buttonsLayout) "Slide + dash buttons left, jump button right" else "Left: hold slide, swipe → dash • Right: jump")
        drawButton(c, settingBtn(0, 0), "HALF-SCREEN", null, if (!buttonsLayout) lime else muted)
        drawButton(c, settingBtn(0, 1), "BUTTONS", null, if (buttonsLayout) lime else muted)

        label(1, "Sound", "Effects")
        drawButton(c, settingBtn(1, 0), if (soundOn) "ON" else "OFF", null, if (soundOn) lime else muted)

        label(2, "Vibration", "Buzz when you crash")
        drawButton(c, settingBtn(2, 0), if (vibOn) "ON" else "OFF", null, if (vibOn) lime else muted)

        label(3, "Hazard hints", "Action badges above hazards in Orbit training levels")
        drawButton(c, settingBtn(3, 0), if (hintsOn) "ON" else "OFF", null, if (hintsOn) lime else muted)

        label(4, "Reset progress", "Tap twice to confirm. Can't be undone.")
        drawButton(c, settingBtn(4, 0), if (confirmReset == 0) "SURE?" else "ORBIT", null, danger)
        drawButton(c, settingBtn(4, 1), if (confirmReset == 1) "SURE?" else "DEEP SPACE", null, danger)
        btnText.textSize = 30f
    }

    // ---------- Profile ----------
    private fun drawProfile(c: Canvas) {
        val p = getProfile(profileTabExpert)
        val cx = viewW / 2f
        headPaint.textSize = 44f
        c.drawText("PROFILE", cx, 70f, headPaint)
        headPaint.textSize = 54f

        btnText.textSize = 24f
        drawButton(c, backBtnRect(), "‹ BACK", null, teal)
        drawButton(c, tabRect(false), "ORBIT", null, if (!profileTabExpert) lime else Color.rgb(60, 90, 110))
        drawButton(c, tabRect(true), "DEEP SPACE", null, if (profileTabExpert) danger else Color.rgb(60, 90, 110))
        btnText.textSize = 30f

        drawReflexMap(c, p, 156f, 396f)
        drawCategories(c, p)

        val first = p.history.firstOrNull()
        val last = p.history.lastOrNull()
        val sb = StringBuilder("Levels cleared ${p.totalClears}  •  Deaths ${p.totalDeaths}")
        if (first != null && last != null && first.reactMs > 0f && last.reactMs > 0f && p.history.size > 1) {
            sb.append("  •  Pop-up reaction ${first.reactMs.roundToInt()} → ${last.reactMs.roundToInt()} ms")
        } else if (last != null && last.reactMs > 0f) {
            sb.append("  •  Pop-up reaction ${last.reactMs.roundToInt()} ms")
        }
        c.drawText(sb.toString(), cx, viewH - 16f, creditPaint)

        if (profileCat >= 0) drawCategoryDetail(c, p, Category.values()[profileCat])
    }

    /** Nodes for each test/retest, climbing higher as the rating improves. */
    private fun drawReflexMap(c: Canvas, p: TrainerProfile, top: Float, bottom: Float) {
        val left = 80f
        val right = viewW - 80f
        rect.set(left - 40f, top, right + 40f, bottom)
        c.drawRoundRect(rect, 20f, 20f, panelPaint)
        leftPaint.color = textDim
        c.drawText("REFLEX MAP", left - 16f, top + 30f, leftPaint)

        fun yFor(r: Float) = bottom - 70f - (bottom - top - 120f) * (r / 100f).coerceIn(0f, 1f)

        val hist = p.history.takeLast(6)
        val nodes = hist.size + 1
        val stepX = if (nodes > 1) (right - left - 60f) / (nodes - 1) else 0f
        var prevX = -1f
        var prevY = -1f
        for (i in 0 until nodes) {
            val x = left + 30f + i * stepX
            val isCurrent = i == hist.size
            val rating = if (isCurrent) {
                val live = p.liveRating()
                if (p.isTestPhase() && live >= 0f) live else hist.lastOrNull()?.rating ?: 0f
            } else hist[i].rating
            val y = yFor(rating)
            if (prevX >= 0f) {
                stroke.color = if (isCurrent) Color.rgb(60, 90, 110) else teal
                stroke.alpha = 255; stroke.strokeWidth = 4f
                c.drawLine(prevX, prevY, x, y, stroke)
                val label = if (isCurrent && p.phase == TrainerProfile.PHASE_TRAIN)
                    "training ${p.level}/100" else "100 training"
                creditPaint.color = muted
                c.drawText(label, (prevX + x) / 2f, (prevY + y) / 2f - 14f, creditPaint)
            }
            if (isCurrent) {
                val pulse = 0.5f + 0.5f * sin(animT * 4f)
                fill.color = if (p.expert) danger else lime
                fill.alpha = (90 + 120 * pulse).toInt()
                c.drawCircle(x, y, 30f + 6f * pulse, fill)
                fill.alpha = 255
                c.drawCircle(x, y, 22f, fill)
                c.drawText("YOU", x, y + 8f, nodeText)
                smallPaint.color = Color.WHITE
                c.drawText("${p.phaseName()} ${p.level + 1}/${p.phaseLength()}", x, y + 56f, smallPaint)
                smallPaint.color = textDim
            } else {
                val r = hist[i]
                fill.color = teal; fill.alpha = 255
                c.drawCircle(x, y, 28f, fill)
                c.drawText("${r.rating.roundToInt()}", x, y + 8f, nodeText)
                c.drawText(if (r.retest) "Retest ${r.cycle + 1}" else "Test", x, y + 54f, smallPaint)
            }
            prevX = x
            prevY = y
        }
        if (p.history.isEmpty()) {
            c.drawText("Finish the 25 test levels to place your first node", viewW / 2f, top + 34f, smallPaint)
        }
    }

    private fun statsFor(p: TrainerProfile): Array<SkillStat> = if (p.history.isNotEmpty()) p.lastTest else p.block

    private fun barColor(score: Float) = when {
        score >= 75f -> lime
        score >= 45f -> teal
        else -> danger
    }

    private fun drawCategories(c: Canvas, p: TrainerProfile) {
        val stats = statsFor(p)
        val first = if (p.history.size > 1) p.history.first() else null
        leftPaint.color = textDim
        c.drawText(
            if (p.history.isNotEmpty()) "SKILL GROUPS (latest test, | = first test) — tap for details"
            else "SKILL GROUPS (live, this test) — tap for details",
            80f, 412f, leftPaint
        )
        val cats = Category.values()
        for (i in cats.indices) {
            val r = catRect(i)
            c.drawRoundRect(r, 14f, 14f, btnFill)
            val score = p.categoryScore(stats, cats[i])
            leftPaint.color = Color.WHITE
            c.drawText(cats[i].label, r.left + 16f, r.top + 28f, leftPaint)
            val bx = r.left + 16f
            val bw = r.width() - 90f
            rect.set(bx, r.top + 40f, bx + bw, r.top + 54f)
            c.drawRoundRect(rect, 7f, 7f, barBg)
            if (score >= 0f) {
                fill.color = barColor(score); fill.alpha = 255
                rect.set(bx, r.top + 40f, bx + bw * score / 100f, r.top + 54f)
                c.drawRoundRect(rect, 7f, 7f, fill)
                leftPaint.color = textDim
                c.drawText("${score.roundToInt()}", bx + bw + 14f, r.top + 54f, leftPaint)
            } else {
                leftPaint.color = muted
                c.drawText("—", bx + bw + 14f, r.top + 54f, leftPaint)
            }
            if (first != null) {
                val f = p.categoryScoreFromResult(first, cats[i])
                if (f >= 0f) {
                    fill.color = Color.WHITE
                    c.drawRect(bx + bw * f / 100f - 2f, r.top + 34f, bx + bw * f / 100f + 2f, r.top + 60f, fill)
                }
            }
        }
    }

    private fun drawCategoryDetail(c: Canvas, p: TrainerProfile, cat: Category) {
        val skills = Skill.ALL.filter { it.cat == cat && p.allowed(it) }
        val stats = statsFor(p)
        val first = if (p.history.size > 1) p.history.first() else null
        val h = 120f + skills.size * 62f
        panel(c, min(viewW - 80f, 1000f), h)
        val left = viewW / 2f - min(viewW - 80f, 1000f) / 2f + 30f
        var y = viewH / 2f - h / 2f + 50f
        headPaint.textSize = 34f
        headPaint.textAlign = Paint.Align.LEFT
        c.drawText(cat.label.uppercase(), left, y, headPaint)
        headPaint.textAlign = Paint.Align.CENTER
        headPaint.textSize = 54f
        y += 30f
        val bx = left + 230f
        val bw = min(viewW - 80f, 1000f) - 300f - 230f
        for (s in skills) {
            val st = stats[s.ordinal]
            val score = st.score(s)
            leftPaint.color = Color.WHITE
            c.drawText(s.label, left, y + 18f, leftPaint)
            rect.set(bx, y + 4f, bx + bw, y + 20f)
            c.drawRoundRect(rect, 8f, 8f, barBg)
            if (score >= 0f) {
                fill.color = barColor(score); fill.alpha = 255
                rect.set(bx, y + 4f, bx + bw * score / 100f, y + 20f)
                c.drawRoundRect(rect, 8f, 8f, fill)
                leftPaint.color = textDim
                c.drawText("${score.roundToInt()}", bx + bw + 12f, y + 20f, leftPaint)
            } else {
                leftPaint.color = muted
                c.drawText(if (s.introLevel > p.level && p.history.isEmpty()) "not yet" else "—", bx + bw + 12f, y + 20f, leftPaint)
            }
            if (first != null) {
                val f = first.skills.getOrElse(s.ordinal) { -1f }
                if (f >= 0f) {
                    fill.color = Color.WHITE
                    c.drawRect(bx + bw * f / 100f - 2f, y, bx + bw * f / 100f + 2f, y + 24f, fill)
                }
            }
            val detail = st.detail(s)
            if (detail.isNotEmpty()) {
                leftPaint.color = muted; leftPaint.textSize = 16f
                c.drawText(detail, bx, y + 42f, leftPaint)
                leftPaint.textSize = 21f
            }
            y += 62f
        }
        smallPaint.alpha = 160
        c.drawText("Tap anywhere to close", viewW / 2f, viewH / 2f + h / 2f - 16f, smallPaint)
        smallPaint.alpha = 255
    }

    companion object {
        private const val ROLE_NONE = 0
        private const val ROLE_JUMP = 1
        private const val ROLE_SLIDE = 2
        private const val ROLE_DASH = 3
        private const val MAP_PAGE = 25
    }
}
