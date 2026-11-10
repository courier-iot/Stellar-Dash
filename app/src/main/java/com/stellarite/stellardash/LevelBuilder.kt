package com.stellarite.stellardash

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ======================= World objects =======================
enum class PlatKind { SOLID, FLOATING, COLLAPSE, CRACKED, MOVING }

class Platform(
    val x: Float,
    val top0: Float,
    val w: Float,
    val enc: Encounter?,
    val kind: PlatKind = PlatKind.SOLID,
    val amp: Float = 0f,
    val freq: Float = 0f,
    val phase: Float = 0f,
    val delay: Float = 0f
) {
    var top = top0
    var touched = false
    var timer = 0f
    var broken = false
    var drop = 0f

    fun update(runT: Float, dt: Float) {
        if (kind == PlatKind.MOVING) top = top0 + sin(runT * freq + phase) * amp
        if (broken) drop += dt * 900f
    }
}

class Spike(
    val x: Float, val w: Float, val base: Float,
    val popUp: Boolean, val lead: Float, val enc: Encounter?,
    /** false = raised by something else (signal, gate, fake-out). */
    val auto: Boolean = true
) {
    var triggered = !popUp
    var warn = 0f
    var raise = if (popUp) 0f else 1f
}

class Decoy(val x: Float, val w: Float, val base: Float, val lead: Float, val enc: Encounter) {
    var triggered = false
    var warn = 0f
}

enum class CeilKind { HOP, CRUSHER, LOW }

/** Block hanging from the top of the screen down to [bottom]. */
class Ceiling(val x: Float, val w: Float, val bottom: Float, val kind: CeilKind, val enc: Encounter?) {
    /** 0..1 visible amount (drops in when revealed). */
    var shown = 1f
    var active = true
    /** 0..1 lifted away (fake-outs). */
    var retract = 0f
}

class Mine(val x: Float, val baseY: Float, val amp: Float, val freq: Float, val phase: Float, val enc: Encounter?) {
    var y = baseY
}

class Meteor(val x: Float, val base: Float, val lead: Float, val fallDur: Float, val enc: Encounter) {
    var triggered = false
    var t = 0f
    var y = -120f
    val size = 44f
}

class Fence(val x: Float, val base: Float, val period: Float, val phase: Float, val enc: Encounter) {
    fun on(runT: Float): Boolean {
        val f = ((runT / period + phase) % 1f + 1f) % 1f
        return f < 0.5f
    }
}

class PhaseWall(val x: Float, val enc: Encounter?)

class Door(val x: Float, val base: Float, val lead: Float, val closeDur: Float, val enc: Encounter) {
    var triggered = false
    var entered = false
    var t = 0f
    fun bottom(): Float = if (!triggered) -60f else -60f + (base + 60f) * (t / closeDur).coerceIn(0f, 1f)
}

class Pad(val x: Float, val w: Float, val base: Float, val enc: Encounter)

enum class ZoneKind { WIND, SURGE, BLACKOUT, MIRROR, DISTRACT, CHASER }

class Zone(
    val kind: ZoneKind, val x0: Float, val x1: Float,
    val factor: Float, val dur: Float, val enc: Encounter
) {
    var triggered = false
    var t = 0f
    var wallX = 0f
    var done = false
}

enum class RevealKind { SIGNAL_JUMP, SIGNAL_SLIDE, GATE, FAKEOUT }

class Reveal(
    val kind: RevealKind, val x: Float, val base: Float,
    val showLead: Float, val revealLead: Float,
    val spike: Spike?, val ceiling: Ceiling?, val enc: Encounter
) {
    var shown = false
    var revealed = false
}

class Shard(val x: Float, val y: Float) {
    var taken = false
}

class LevelConfig(
    val seed: Long,
    val speed: Float,
    val d: FloatArray,
    val w: FloatArray,
    /** Level length in seconds of running; <= 0 for endless. */
    val lengthSec: Float,
    val expert: Boolean,
    val endless: Boolean,
    /** Hazards introduced on this level: guaranteed to appear (twice each). */
    val intro: List<Skill> = emptyList()
)

class World {
    val platforms = ArrayList<Platform>()
    val spikes = ArrayList<Spike>()
    val decoys = ArrayList<Decoy>()
    val ceilings = ArrayList<Ceiling>()
    val mines = ArrayList<Mine>()
    val meteors = ArrayList<Meteor>()
    val fences = ArrayList<Fence>()
    val walls = ArrayList<PhaseWall>()
    val doors = ArrayList<Door>()
    val pads = ArrayList<Pad>()
    val zones = ArrayList<Zone>()
    val reveals = ArrayList<Reveal>()
    val shards = ArrayList<Shard>()
    val encounters = ArrayList<Encounter>()
    var genX = 0f
    var lastTop = 520f
    var segCount = 0
    var levelEndX = Float.MAX_VALUE
    var finishX = Float.MAX_VALUE

    fun clear() {
        platforms.clear(); spikes.clear(); decoys.clear(); ceilings.clear()
        mines.clear(); meteors.clear(); fences.clear(); walls.clear(); doors.clear()
        pads.clear(); zones.clear(); reveals.clear(); shards.clear(); encounters.clear()
        segCount = 0
        finishX = Float.MAX_VALUE
        levelEndX = Float.MAX_VALUE
    }
}

/**
 * Builds the course a segment at a time. Each segment is built around one skill.
 * Seeded, so a level replays identically on retry.
 */
class LevelBuilder(private val world: World) {

    private var rng = Random(1)
    private lateinit var cfg: LevelConfig
    private val ph = 40f
    private var s = 400f
    private var endlessD = 0f
    private val forced = ArrayDeque<Skill>()

    fun start(config: LevelConfig, startX: Float) {
        cfg = config
        rng = Random(config.seed)
        world.clear()
        world.lastTop = 520f
        world.platforms.add(Platform(startX - 400f, world.lastTop, 1700f, null))
        world.genX = startX + 1300f
        forced.clear()
        for (round in 0 until 2) for (sk in config.intro) forced.addLast(sk)
        if (!config.endless) world.levelEndX = world.genX + config.speed * config.lengthSec
    }

    // ---------------- helpers ----------------
    private fun rf(a: Float, b: Float) = a + rng.nextFloat() * (b - a)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
    private fun clampTop(t: Float) = t.coerceIn(330f, 610f)

    private fun allowed(sk: Skill): Boolean {
        if (sk.expertOnly && !cfg.expert) return false
        if (cfg.endless) return sk.introLevel <= (endlessD * 16f).toInt() + 1
        return cfg.w[sk.ordinal] > 0f
    }

    private fun weight(sk: Skill): Float {
        if (!allowed(sk)) return 0f
        return if (cfg.endless) 1f else cfg.w[sk.ordinal]
    }

    private fun pickSkill(): Skill? {
        var total = 0f
        for (sk in Skill.ALL) total += weight(sk)
        if (total <= 0f) return null
        var r = rng.nextFloat() * total
        for (sk in Skill.ALL) {
            r -= weight(sk)
            if (r <= 0f && weight(sk) > 0f) return sk
        }
        return Skill.GAP
    }

    private fun dOf(sk: Skill) = if (cfg.endless) endlessD else cfg.d[sk.ordinal]

    private fun enc(sk: Skill, ref: Float, end: Float, expect: Action? = null): Encounter {
        val e = Encounter(sk, ref, end)
        e.expect = expect
        e.cueY = (world.lastTop - 190f).coerceAtLeast(110f)
        world.encounters.add(e)
        return e
    }

    private fun plat(
        x: Float, top: Float, w: Float, e: Encounter?,
        kind: PlatKind = PlatKind.SOLID, amp: Float = 0f, freq: Float = 0f, phase: Float = 0f, delay: Float = 0f
    ): Platform {
        val p = Platform(x, top, w, e, kind, amp, freq, phase, delay)
        world.platforms.add(p)
        if (kind != PlatKind.FLOATING) {
            world.lastTop = top
            world.genX = x + w
        }
        return p
    }

    /** Easy filler gap. Returns the x where the next platform starts; updates nothing else. */
    private fun incidentalGap(): Float {
        val gt = rf(0.22f, 0.3f) * (if (cfg.expert) 0.9f else 1f)
        val gap = s * gt
        val x = world.genX + gap
        if (cfg.expert && gt > 0.24f && rng.nextFloat() < 0.3f) {
            val e = enc(Skill.MINE, world.genX, x + 60f)
            world.mines.add(Mine(world.genX + gap * 0.5f, world.lastTop - 140f, 55f, 2.4f, rf(0f, 6.28f), e))
        } else if (rng.nextFloat() < 0.3f) {
            val by = world.lastTop - 110f
            for (i in 0 until 3) {
                val t = (i + 1f) / 4f
                world.shards.add(Shard(world.genX + gap * t, by - 4f * t * (1f - t) * 50f))
            }
        }
        return x
    }

    private fun filler(mul: Float = 1f): Float = s * rf(0.55f, 0.9f) * mul * (if (cfg.expert) 0.8f else 1f)

    private fun shardLine(x: Float, top: Float, n: Int) {
        for (i in 0 until n) world.shards.add(Shard(x + i * 45f, top - 70f))
    }

    // ---------------- main entry ----------------
    /** Append one segment. [speed] = base run speed; [eD] = 0..1 difficulty in endless. */
    fun generate(speed: Float, eD: Float) {
        s = speed
        endlessD = eD
        val w = world
        w.segCount++

        if (!cfg.endless && w.genX >= w.levelEndX) {
            val x = w.genX + s * 0.25f
            val e = enc(Skill.GAP, w.genX, x + 60f, Action.JUMP)
            w.platforms.add(Platform(x, w.lastTop, 100_000f, e))
            w.finishX = x + 260f
            w.genX = Float.MAX_VALUE / 4f
            return
        }

        val warm = w.segCount <= 2
        val sk = when {
            warm -> Skill.GAP
            // New hazards on their intro level: every other segment until each has shown up twice.
            forced.isNotEmpty() && w.segCount % 2 == 1 -> forced.removeFirst()
            else -> pickSkill() ?: Skill.GAP
        }
        val d = dOf(sk)
        when (sk) {
            Skill.GAP -> buildGap(d, null)
            Skill.STEP -> buildStep(d)
            Skill.DOUBLE -> buildDouble(d)
            Skill.MINE -> buildMine(d)
            Skill.WIDE -> buildWide(d)
            Skill.BOUNCE -> buildBounce(d)
            Skill.SPIKE -> buildSpike(d)
            Skill.POPUP -> buildPopup(d)
            Skill.HOP -> buildHop(d)
            Skill.DECOY -> buildDecoy(d)
            Skill.BAR -> buildBar(d)
            Skill.DOOR -> buildDoor(d)
            Skill.METEOR -> buildMeteor(d)
            Skill.COLLAPSE -> buildCollapse(d)
            Skill.CRUMBLE -> buildCrumble(d)
            Skill.MOVING -> buildMoving(d)
            Skill.WIND -> buildWind(d)
            Skill.SURGE -> buildSurge(d)
            Skill.BLACKOUT -> buildBlackout(d, false)
            Skill.DISTRACT -> buildBlackout(d, true)
            Skill.SPLIT -> buildSplit(d)
            Skill.CHASER -> buildChaser(d)
            Skill.COMBO -> buildCombo(d)
            Skill.RHYTHM -> buildRhythm(d)
            Skill.LASER -> buildLaser(d)
            Skill.CHAIN -> buildChain(d)
            Skill.TUNNEL -> buildTunnel(d)
            Skill.FAKEOUT -> buildFakeout(d)
            Skill.GATE -> buildGate(d, false)
            Skill.SIGNAL -> buildGate(d, true)
            Skill.MIRROR -> buildMirror(d)
            Skill.PHASE -> buildPhase(d)
        }
    }

    // ---------------- gap skills ----------------
    private fun buildGap(d: Float, forcedDy: Float?) {
        val w = world
        val gt = lerp(0.24f, 0.52f, d) * rf(0.9f, 1.1f)
        val dy = forcedDy ?: if (w.lastTop < 380f) rf(20f, 110f) else rf(-90f, 80f)
        val top = clampTop(w.lastTop + dy)
        val gap = s * gt
        val x = w.genX + gap
        val e = enc(Skill.GAP, w.genX, x + 60f, Action.JUMP)
        if (rng.nextFloat() < 0.4f) {
            val by = min(w.lastTop, top) - 110f
            for (i in 0 until 3) {
                val t = (i + 1f) / 4f
                w.shards.add(Shard(w.genX + gap * t, by - 4f * t * (1f - t) * 50f))
            }
        }
        plat(x, top, filler(), e)
    }

    private fun buildStep(d: Float) {
        val w = world
        if (w.lastTop - 80f < 340f) { buildGap(d, rf(40f, 120f)); return }
        val gt = lerp(0.16f, 0.3f, d)
        val top = clampTop(w.lastTop - lerp(80f, 145f, d))
        val x = w.genX + s * gt
        val e = enc(Skill.STEP, w.genX, x + 60f, Action.JUMP)
        plat(x, top, filler(), e)
    }

    private fun buildDouble(d: Float) {
        val w = world
        val gt = lerp(0.62f, 0.9f, d)
        val top = clampTop(w.lastTop + rf(-40f, 40f))
        val x = w.genX + s * gt
        val e = enc(Skill.DOUBLE, w.genX, x + 60f, Action.JUMP)
        plat(x, top, filler(), e)
    }

    private fun buildMine(d: Float) {
        val w = world
        val gt = lerp(0.38f, 0.52f, d)
        val top = clampTop(w.lastTop + rf(-50f, 50f))
        val gap = s * gt
        val x = w.genX + gap
        val e = enc(Skill.MINE, w.genX, x + 60f, Action.JUMP)
        w.mines.add(
            Mine(w.genX + gap * 0.5f, min(w.lastTop, top) - 140f, lerp(50f, 90f, d), lerp(2.0f, 3.4f, d), rf(0f, 6.28f), e)
        )
        plat(x, top, filler(), e)
    }

    private fun buildWide(d: Float) {
        val w = world
        val gt = lerp(1.28f, 1.5f, d)
        val top = clampTop(w.lastTop + rf(-30f, 20f))
        val x = w.genX + s * gt
        val e = enc(Skill.WIDE, w.genX, x + 60f, Action.DASH)
        plat(x, top, filler(1.2f), e)
    }

    private fun buildBounce(d: Float) {
        val w = world
        val x0 = incidentalGap()
        val pw = s * 0.9f + 100f
        plat(x0, w.lastTop, pw, null)
        val padX = x0 + pw - 100f
        val gt = lerp(1.05f, 1.2f, d)
        val top = clampTop(w.lastTop + rf(-40f, 0f))
        val x = w.genX + s * gt
        val e = enc(Skill.BOUNCE, padX, x + 60f, Action.JUMP)
        w.pads.add(Pad(padX, 80f, w.lastTop, e))
        plat(x, top, filler(1.2f), e)
    }

    // ---------------- on-platform hazards ----------------
    /** Incidental gap + a platform long enough for a feature starting [off] in, [len] long. Returns platform x. */
    private fun featurePlatform(off: Float, len: Float, after: Float = 0.45f): Float {
        val x = incidentalGap()
        val top = clampTop(world.lastTop + rf(-40f, 40f))
        val pw = max(off + len + s * after, filler())
        plat(x, top, pw, null)
        return x
    }

    private fun buildSpike(d: Float) {
        val sw = lerp(40f, 100f, d)
        val off = max(s * 0.4f, 140f)
        val x = featurePlatform(off, sw)
        val top = world.lastTop
        val hx = x + off
        val e = enc(Skill.SPIKE, hx, hx + sw + 20f, Action.JUMP)
        world.spikes.add(Spike(hx, sw, top, false, 0f, e))
    }

    private fun buildPopup(d: Float) {
        val sw = rf(40f, 70f)
        val off = max(s * 0.9f, 220f)
        val x = featurePlatform(off, sw)
        val top = world.lastTop
        val hx = x + off
        val e = enc(Skill.POPUP, hx, hx + sw + 20f, Action.JUMP)
        world.spikes.add(Spike(hx, sw, top, true, lerp(0.8f, 0.45f, d), e))
    }

    private fun buildHop(d: Float) {
        val sw = rf(36f, 56f)
        val pre = lerp(80f, 140f, d)
        val post = lerp(60f, 110f, d)
        val off = s * 0.5f + 160f
        val x = featurePlatform(off, pre + sw + post)
        val top = world.lastTop
        val cx = x + off
        val e = enc(Skill.HOP, cx, cx + pre + sw + post, Action.JUMP)
        world.ceilings.add(Ceiling(cx, pre + sw + post, top - ph - lerp(150f, 136f, d), CeilKind.HOP, e))
        world.spikes.add(Spike(cx + pre, sw, top, false, 0f, e))
    }

    private fun buildDecoy(d: Float) {
        val len = lerp(220f, 340f, d)
        val off = s * 0.5f + 160f
        val x = featurePlatform(off, len)
        val top = world.lastTop
        val cx = x + off
        val e = enc(Skill.DECOY, cx, cx + len)
        world.ceilings.add(Ceiling(cx, len, top - ph - 35f, CeilKind.CRUSHER, e))
        world.decoys.add(Decoy(cx + 50f, 60f, top, lerp(0.8f, 0.45f, d), e))
    }

    private fun buildBar(d: Float) {
        val len = lerp(110f, 220f, d)
        val off = s * 0.5f + 120f
        val x = featurePlatform(off, len)
        val top = world.lastTop
        val bx = x + off
        val e = enc(Skill.BAR, bx, bx + len, Action.SLIDE)
        world.ceilings.add(Ceiling(bx, len, top - 28f, CeilKind.LOW, e))
    }

    private fun buildDoor(d: Float) {
        // Door takes 1.5 s to drop at easy, 1.0 s at hard. It shuts at head height ~94% of the way down.
        val closeDur = lerp(1.5f, 1.0f, d)
        val shutT = closeDur * 0.94f
        // Without a dash you reach the door 0.12 s after it shuts; a dash (~0.26 s gained) gets you in ~0.14 s early.
        val lead = shutT + 0.12f + 20f / s
        val off = s * (lead + 0.3f) + 80f
        val x = featurePlatform(off, 40f, 0.6f)
        val top = world.lastTop
        val dx = x + off
        val e = enc(Skill.DOOR, dx, dx + 40f, Action.DASH)
        world.doors.add(Door(dx, top, lead, closeDur, e))
    }

    private fun buildMeteor(d: Float) {
        val lead = lerp(1.0f, 0.7f, d)
        val off = s * (lead + 0.3f) + 60f
        val x = featurePlatform(off, 50f, 0.6f)
        val top = world.lastTop
        val mx = x + off
        val e = enc(Skill.METEOR, mx, mx + 64f, Action.JUMP)
        world.meteors.add(Meteor(mx, top, lead, lead - lerp(0.35f, 0.25f, d), e))
    }

    private fun buildCollapse(d: Float) {
        val w = world
        val x = incidentalGap()
        val delay = lerp(0.55f, 0.3f, d)
        val top = clampTop(w.lastTop + rf(-30f, 30f))
        val cw = s * (delay + 0.3f)
        val nextX = x + cw + s * 0.25f
        val e = enc(Skill.COLLAPSE, x, nextX + 60f, Action.JUMP)
        plat(x, top, cw, e, PlatKind.COLLAPSE, delay = delay)
        plat(nextX, clampTop(top + rf(-20f, 30f)), filler(), e)
    }

    private fun buildCrumble(d: Float) {
        val w = world
        val x = incidentalGap()
        val top = clampTop(w.lastTop + rf(-30f, 30f))
        val a = s * 0.5f + 80f
        plat(x, top, a, null)
        val cw = s * lerp(0.9f, 1.3f, d)
        val e = enc(Skill.CRUMBLE, x + a, x + a + cw, Action.SLIDE)
        plat(x + a, top, cw, e, PlatKind.CRACKED)
        plat(x + a + cw, top, s * 0.6f, null)
    }

    private fun buildMoving(d: Float) {
        val w = world
        val gt = lerp(0.35f, 0.5f, d)
        val amp = lerp(50f, 100f, d)
        val top0 = (w.lastTop + rf(-30f, 30f)).coerceIn(330f + amp, 640f - amp)
        val x = w.genX + s * gt
        val mw = s * 0.8f
        val e = enc(Skill.MOVING, w.genX, x + mw + s * 0.3f + 60f, Action.JUMP)
        w.platforms.add(Platform(x, top0, mw, e, PlatKind.MOVING, amp, lerp(1.6f, 2.6f, d), rf(0f, 6.28f)))
        w.lastTop = top0
        w.genX = x + mw
        plat(w.genX + s * 0.3f, top0, filler(), e)
    }

    private fun buildWind(d: Float) {
        val w = world
        val x0 = incidentalGap()
        plat(x0, clampTop(w.lastTop + rf(-30f, 30f)), s * 0.9f, null)
        val factor = if (rng.nextBoolean()) lerp(0.8f, 0.65f, d) else lerp(1.25f, 1.4f, d)
        val se = s * factor
        val gap = se * lerp(0.3f, 0.48f, d)
        val x = w.genX + gap
        val e = enc(Skill.WIND, w.genX, x + 60f, Action.JUMP)
        w.zones.add(Zone(ZoneKind.WIND, w.genX - se * 0.6f, x + se * 0.3f, factor, 0f, e))
        plat(x, clampTop(w.lastTop + rf(-40f, 40f)), filler(), e)
    }

    private fun buildSurge(d: Float) {
        val w = world
        val x = incidentalGap()
        val top = clampTop(w.lastTop + rf(-30f, 30f))
        val f = lerp(1.3f, 1.5f, d)
        val se = s * f
        val trig = x + s * 0.35f
        val pw = trig - x + se * 0.85f
        plat(x, top, pw, null)
        val landX = w.genX + se * 0.33f
        val e = enc(Skill.SURGE, trig, landX + 60f, Action.JUMP)
        w.zones.add(Zone(ZoneKind.SURGE, trig, landX + 60f, f, 1.3f, e))
        w.spikes.add(Spike(trig + se * 0.4f, 50f, top, false, 0f, e))
        plat(landX, clampTop(top + rf(-30f, 30f)), filler(), e)
    }

    private fun buildBlackout(d: Float, distract: Boolean) {
        val w = world
        val x = incidentalGap()
        val top = clampTop(w.lastTop + rf(-30f, 30f))
        val trig = x + s * 0.35f
        val pw = trig - x + s * 0.95f
        plat(x, top, pw, null)
        val landX = w.genX + s * 0.3f
        val sk = if (distract) Skill.DISTRACT else Skill.BLACKOUT
        val e = enc(sk, trig, landX + 60f, Action.JUMP)
        if (distract) {
            w.zones.add(Zone(ZoneKind.DISTRACT, trig - s * 0.3f, landX + 60f, 1f, 0f, e))
            w.spikes.add(Spike(trig + s * 0.55f, 50f, top, true, lerp(0.7f, 0.5f, d), e))
        } else {
            w.zones.add(Zone(ZoneKind.BLACKOUT, trig, landX + 60f, 1f, lerp(0.8f, 1.3f, d), e))
            w.spikes.add(Spike(trig + s * 0.55f, 50f, top, false, 0f, e))
        }
        plat(landX, clampTop(top + rf(-30f, 30f)), filler(), e)
    }

    private fun buildSplit(d: Float) {
        val w = world
        val x = incidentalGap()
        val top = clampTop(max(w.lastTop + rf(-20f, 20f), 480f))
        val len = s * 2.6f
        plat(x, top, len + s * 0.4f, null)
        val ledgeY = top - 150f
        val lx = x + s * 0.45f
        val lw = s * 0.85f
        val e = enc(Skill.SPLIT, lx, x + len)
        e.refY = ledgeY + 70f
        w.platforms.add(Platform(lx, ledgeY, lw, e, PlatKind.FLOATING))
        w.platforms.add(Platform(lx + lw + s * 0.25f, ledgeY - 20f, lw, e, PlatKind.FLOATING))
        for (i in 0 until 5) w.shards.add(Shard(lx + 40f + i * 50f, ledgeY - 40f))
        for (i in 0 until 5) w.shards.add(Shard(lx + lw + s * 0.25f + 40f + i * 50f, ledgeY - 60f))
        w.mines.add(Mine(lx + lw + s * 0.12f, ledgeY - 50f, lerp(40f, 60f, d), lerp(2f, 3f, d), rf(0f, 6.28f), e))
        w.spikes.add(Spike(x + s * 1.3f, 50f, top, false, 0f, e))
    }

    private fun buildChaser(d: Float) {
        val w = world
        val x0 = incidentalGap()
        val len = s * lerp(3f, 4.5f, d)
        val e = enc(Skill.CHASER, x0, x0 + len, Action.DASH)
        w.zones.add(Zone(ZoneKind.CHASER, x0, x0 + len, lerp(1.12f, 1.2f, d), 0f, e))
        var x = x0
        var top = clampTop(w.lastTop + rf(-30f, 30f))
        while (x < x0 + len) {
            val pw = s * rf(0.8f, 1.2f)
            plat(x, top, pw, e)
            x += pw + s * rf(0.22f, 0.28f)
            top = clampTop(top + rf(-40f, 40f))
        }
    }

    private fun buildCombo(d: Float) {
        val w = world
        val gap = s * lerp(0.3f, 0.4f, d)
        val x = w.genX + gap
        val wallOff = s * 0.5f
        val barOff = wallOff + s * lerp(0.6f, 0.5f, d)
        val top = clampTop(w.lastTop + rf(-30f, 30f))
        val e = enc(Skill.COMBO, w.genX, x + barOff + 130f, Action.JUMP)
        plat(x, top, barOff + 130f + s * 0.45f, e)
        w.walls.add(PhaseWall(x + wallOff, e))
        w.ceilings.add(Ceiling(x + barOff, 130f, top - 28f, CeilKind.LOW, e))
    }

    private fun buildRhythm(d: Float) {
        val beat = s * lerp(0.8f, 0.6f, d)
        val n = 4
        val off = s * 0.5f + 60f
        val x = featurePlatform(off, beat * (n - 1) + 100f)
        val top = world.lastTop
        val x0 = x + off
        val e = enc(Skill.RHYTHM, x0 - s * 0.4f, x0 + beat * (n - 1) + 100f)
        var prev = -1
        for (i in 0 until n) {
            val ox = x0 + i * beat
            var t = rng.nextInt(3)
            if (t == prev) t = (t + 1) % 3
            prev = t
            when (t) {
                0 -> world.spikes.add(Spike(ox, 44f, top, false, 0f, e))
                1 -> world.ceilings.add(Ceiling(ox, 80f, top - 28f, CeilKind.LOW, e))
                else -> world.walls.add(PhaseWall(ox, e))
            }
        }
    }

    private fun buildLaser(d: Float) {
        val sp = s * 0.7f
        val off = s * 0.5f + 60f
        val x = featurePlatform(off, sp * 2f + 20f)
        val top = world.lastTop
        val x0 = x + off
        val e = enc(Skill.LASER, x0, x0 + sp * 2f + 20f, Action.JUMP)
        val period = lerp(1.2f, 0.8f, d)
        for (i in 0 until 3) world.fences.add(Fence(x0 + i * sp, top, period, rng.nextFloat(), e))
    }

    private fun buildChain(d: Float) {
        val w = world
        val n = lerp(4f, 7f, d).toInt()
        val pw = lerp(110f, 70f, d)
        val gt = lerp(0.25f, 0.32f, d)
        val baseTop = clampTop(w.lastTop + rf(-20f, 20f))
        val startRef = w.genX
        var x = w.genX
        val endGuess = x + n * (s * gt + pw) + s * gt + 60f
        val e = enc(Skill.CHAIN, startRef, endGuess, Action.JUMP)
        for (i in 0 until n) {
            x += s * gt
            plat(x, clampTop(baseTop + rf(-10f, 10f)), pw, e)
            x += pw
        }
        val lx = x + s * gt
        e.endX = lx + 60f
        plat(lx, baseTop, filler(), e)
    }

    private fun buildTunnel(d: Float) {
        val w = world
        val x = incidentalGap()
        val top = clampTop(w.lastTop + rf(-30f, 30f))
        val entry = s * 0.5f + 60f
        var cx = x + entry
        val segLen = s * lerp(0.5f, 0.35f, d)
        val e = enc(Skill.TUNNEL, cx, cx, Action.SLIDE)
        var platStart = x
        for (i in 0 until 3) {
            w.ceilings.add(Ceiling(cx, segLen, top - 28f, CeilKind.LOW, e))
            cx += segLen
            if (i < 2) {
                val pre = s * 0.2f
                val gap = s * lerp(0.22f, 0.28f, d)
                val post = s * 0.45f
                plat(platStart, top, cx + pre - platStart, if (i == 0) null else e)
                platStart = cx + pre + gap
                cx = platStart + post
            }
        }
        e.endX = cx
        plat(platStart, top, cx - platStart + s * 0.5f, e)
    }

    private fun buildFakeout(d: Float) {
        val off = s * 0.9f + 100f
        val x = featurePlatform(off, 100f)
        val top = world.lastTop
        val bx = x + off
        val lifts = rng.nextBoolean()
        val e = enc(Skill.FAKEOUT, bx, bx + 100f, if (lifts) Action.JUMP else Action.SLIDE)
        val c = Ceiling(bx, 90f, top - 28f, CeilKind.LOW, e)
        world.ceilings.add(c)
        if (lifts) {
            val sp = Spike(bx + 15f, 60f, top, true, 0f, e, auto = false)
            world.spikes.add(sp)
            world.reveals.add(Reveal(RevealKind.FAKEOUT, bx, top, 0f, lerp(0.45f, 0.3f, d), sp, c, e))
        }
    }

    private fun buildGate(d: Float, signal: Boolean) {
        val off = s * 1.1f + 120f
        val x = featurePlatform(off, 100f)
        val top = world.lastTop
        val gx = x + off
        val high = rng.nextBoolean() // high barrier = slide under
        val sk = if (signal) Skill.SIGNAL else Skill.GATE
        val e = enc(sk, gx, gx + 100f, if (high) Action.SLIDE else Action.JUMP)
        var sp: Spike? = null
        var c: Ceiling? = null
        if (high) {
            c = Ceiling(gx, 100f, top - 28f, CeilKind.LOW, e).also { it.shown = 0f; it.active = false }
            world.ceilings.add(c)
        } else {
            sp = Spike(gx + 20f, 60f, top, true, 0f, e, auto = false)
            world.spikes.add(sp)
        }
        val kind = when {
            !signal -> RevealKind.GATE
            high -> RevealKind.SIGNAL_SLIDE
            else -> RevealKind.SIGNAL_JUMP
        }
        val show = if (signal) lerp(1.1f, 0.85f, d) else 99f
        val reveal = if (signal) lerp(0.45f, 0.3f, d) else lerp(0.6f, 0.38f, d)
        world.reveals.add(Reveal(kind, gx, top, show, reveal, sp, c, e))
    }

    private fun buildMirror(d: Float) {
        val w = world
        val x = incidentalGap()
        val top = clampTop(max(w.lastTop, 470f))
        val pre = 110f
        val sw = 44f
        val post = 90f
        val off = s * 0.6f + 160f
        val pw = off + pre + sw + post + s * 0.4f
        plat(x, top, pw, null)
        val cx = x + off
        val stepX = w.genX + s * 0.25f
        val stepTop = clampTop(top - lerp(90f, 130f, d))
        val e = enc(Skill.MIRROR, x, stepX + 60f, Action.JUMP)
        w.zones.add(Zone(ZoneKind.MIRROR, x, stepX + 60f, 1f, 0f, e))
        w.ceilings.add(Ceiling(cx, pre + sw + post, top - ph - 145f, CeilKind.HOP, e))
        w.spikes.add(Spike(cx + pre, sw, top, false, 0f, e))
        plat(stepX, stepTop, filler(), e)
    }

    private fun buildPhase(d: Float) {
        val off = s * lerp(0.9f, 0.7f, d) + 100f
        val x = featurePlatform(off, 20f)
        val wx = x + off
        val e = enc(Skill.PHASE, wx, wx + 30f, Action.DASH)
        world.walls.add(PhaseWall(wx, e))
    }

    fun cleanup(edge: Float) {
        val w = world
        w.platforms.removeAll { it.x + it.w < edge }
        w.spikes.removeAll { it.x + it.w < edge }
        w.decoys.removeAll { it.x + it.w < edge }
        w.ceilings.removeAll { it.x + it.w < edge }
        w.mines.removeAll { it.x + 30f < edge }
        w.meteors.removeAll { it.x + it.size < edge }
        w.fences.removeAll { it.x + 10f < edge }
        w.walls.removeAll { it.x + 20f < edge }
        w.doors.removeAll { it.x + 40f < edge }
        w.pads.removeAll { it.x + it.w < edge }
        w.zones.removeAll { it.x1 < edge && (it.kind != ZoneKind.SURGE || it.done) }
        w.reveals.removeAll { it.x + 120f < edge }
        w.shards.removeAll { it.x < edge || it.taken }
        w.encounters.removeAll { it.resolved && it.endX < edge }
    }
}
