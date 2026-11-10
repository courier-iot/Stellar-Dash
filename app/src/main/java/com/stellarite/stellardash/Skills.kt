package com.stellarite.stellardash

import org.json.JSONObject
import kotlin.math.sqrt

enum class Action { JUMP, SLIDE, DASH }

enum class Category(val label: String) {
    REACTION("Reaction"),
    CHOICE("Decisions"),
    TIMING("Timing & rhythm"),
    CONTROL("Jump control"),
    MOVEMENT("Slide & dash"),
    ATTENTION("Focus & memory")
}

/** Uses: 0 = jump only, 1 = needs slide, 2 = needs dash. */
enum class Skill(
    val label: String,
    val tip: String,
    val cat: Category,
    val introLevel: Int,
    val uses: Int = 0,
    val expertOnly: Boolean = false
) {
    GAP("Gap timing", "Jump right at the edge of each gap.", Category.TIMING, 0),
    SPIKE("Spikes", "Jump over spikes on the ground.", Category.TIMING, 0),
    STEP("Step-ups", "Tall wall ahead: HOLD jump for full height.", Category.CONTROL, 1),
    POPUP("Pop-up spikes", "Red ! = spikes about to rise. Jump fast!", Category.REACTION, 1),
    BAR("Low barriers", "Bar at head height: SLIDE under it.", Category.MOVEMENT, 2, uses = 1),
    DOUBLE("Double gaps", "Wide gap: jump again in mid-air.", Category.CONTROL, 2),
    HOP("Short hops", "Low ceiling over spikes: a QUICK tap only.", Category.CONTROL, 3),
    DOOR("Closing doors", "Door slams shut on a timer. DASH through!", Category.REACTION, 3, uses = 2),
    MINE("Mine timing", "Time your jump through the mine's swing.", Category.TIMING, 4),
    DECOY("Decoys", "A ! under a crusher ceiling is a trap. Don't jump!", Category.CHOICE, 4),
    METEOR("Meteors", "A shadow appears, then a rock lands. Jump it.", Category.REACTION, 5),
    COLLAPSE("Collapsing floors", "Flickering platform falls soon after you land. Jump off!", Category.REACTION, 5),
    SIGNAL("Signals", "Red ▲ = jump. Blue ▼ = slide.", Category.CHOICE, 6, uses = 1),
    GATE("Mystery gates", "A ? turns into a high or low barrier at the last second.", Category.CHOICE, 6, uses = 1),
    LASER("Laser fences", "Fences blink on a beat. Jump only the ones that are on.", Category.TIMING, 7),
    CHAIN("Stepping stones", "Tiny platforms in a row: keep your rhythm even.", Category.TIMING, 7),
    WIDE("Air-dash gaps", "Too wide to jump: jump, then DASH in mid-air.", Category.CONTROL, 8, uses = 2),
    BOUNCE("Bounce pads", "Pads launch you high. Double jump late to stretch it.", Category.TIMING, 8),
    TUNNEL("Slide tunnels", "Slide, hop the floor gaps, slide again.", Category.MOVEMENT, 9, uses = 1),
    CRUMBLE("Cracked floors", "Cracked floor breaks if you run on it. SLIDE across.", Category.MOVEMENT, 9, uses = 1),
    MOVING("Moving platforms", "Platforms rise and fall. Jump when it lines up.", Category.CONTROL, 10),
    WIND("Wind zones", "Wind changes your speed. Adjust your jump timing.", Category.CONTROL, 10),
    FAKEOUT("Fake-outs", "A low bar may lift away to reveal spikes. Switch to a jump!", Category.REACTION, 11, uses = 1),
    PHASE("Phase walls", "Energy walls: DASH through them. Only dashing makes you safe.", Category.TIMING, 11, uses = 2),
    BLACKOUT("Blackouts", "Lights go out. Remember what was ahead.", Category.ATTENTION, 12),
    DISTRACT("Distractions", "Ignore the flashes. Watch the track.", Category.ATTENTION, 12),
    SURGE("Speed surges", "Sudden burst of speed. Re-time everything.", Category.ATTENTION, 13),
    SPLIT("Split paths", "High road: more shards, more danger. Low road: safer.", Category.CHOICE, 13),
    CHASER("Chasers", "An energy wall chases you. Dash to stay ahead, don't waste it.", Category.MOVEMENT, 14, uses = 2),
    COMBO("Combos", "Jump, dash, slide: plan the chain.", Category.MOVEMENT, 14, uses = 2),
    RHYTHM("Rhythm runs", "Obstacles on a beat. Keep the rhythm.", Category.TIMING, 15, uses = 2),
    MIRROR("Mirror runs", "MIRROR zone: quick tap = big jump, hold = small hop.", Category.CHOICE, 15, expertOnly = true),
    REVERSE("Reverse gravity", "Gravity flips: you run on the roof. Jump pushes you DOWN.", Category.CONTROL, 16),
    ZEROG("Zero gravity", "No gravity: tap jump to float up, slide to sink. Fly through the gaps.", Category.CONTROL, 16, uses = 1);

    companion object {
        val ALL = values()
        val COUNT = ALL.size
    }
}

/** One hazard the player meets in a run. Measurements are filled in as it happens. */
class Encounter(val skill: Skill, val refX: Float, var endX: Float) {
    var resolved = false
    var passed = false
    /** Seconds before refX the player took off (+ = earlier). NaN = none recorded. */
    var takeoffLead = Float.NaN
    var triggerT = -1f
    var reactMs = -1f
    var holdMs = -1f
    var expect: Action? = null
    var wrong = false
    var falseStart = false
    var upper = false
    var checked = false
    var refY = 0f
    /** Y position for the training hint badge. */
    var cueY = 200f
    var dashes = 0
    val times = ArrayList<Float>()

    /** Standard deviation of the intervals between recorded actions, in ms; -1 if too few. */
    fun intervalStdMs(): Float {
        if (times.size < 3) return -1f
        val iv = FloatArray(times.size - 1) { times[it + 1] - times[it] }
        val mean = iv.average().toFloat()
        var v = 0f
        for (x in iv) v += (x - mean) * (x - mean)
        return sqrt(v / iv.size) * 1000f
    }
}

/** Aggregated stats for one skill over a block of levels. */
class SkillStat {
    var tries = 0
    var passes = 0
    var deaths = 0
    var reactSum = 0f
    var reactN = 0
    var errSum = 0f
    var errN = 0
    var holdSum = 0f
    var holdN = 0
    var wrongN = 0
    var consSum = 0f
    var consN = 0
    var upperN = 0
    var dashSum = 0

    fun add(e: Encounter, died: Boolean) {
        tries++
        if (e.passed) passes++ else if (died) deaths++
        if (e.reactMs >= 0f) { reactSum += e.reactMs; reactN++ }
        if (e.holdMs >= 0f) { holdSum += e.holdMs; holdN++ }
        if (e.wrong || e.falseStart) wrongN++
        if (e.upper) upperN++
        dashSum += e.dashes
        val c = e.intervalStdMs()
        if (c >= 0f) { consSum += c; consN++ }
        if (!e.takeoffLead.isNaN()) {
            val ideal = when (e.skill) {
                Skill.GAP -> 0.06f
                Skill.SPIKE -> 0.24f
                else -> Float.NaN
            }
            if (!ideal.isNaN()) { errSum += (e.takeoffLead - ideal) * 1000f; errN++ }
        }
    }

    fun passRate(): Float = if (tries == 0) -1f else passes.toFloat() / tries
    fun meanReact(): Float = if (reactN == 0) -1f else reactSum / reactN
    fun meanErr(): Float = if (errN == 0) 0f else errSum / errN
    fun meanHold(): Float = if (holdN == 0) -1f else holdSum / holdN
    fun meanCons(): Float = if (consN == 0) -1f else consSum / consN

    /** 0..100 skill score, or -1 if the skill wasn't seen. */
    fun score(skill: Skill): Float {
        if (tries == 0) return -1f
        val base = passRate() * 100f
        return when (skill.cat) {
            Category.REACTION -> if (reactN > 0) {
                val r = ((750f - meanReact()) / 500f).coerceIn(0f, 1f)
                0.65f * base + 0.35f * r * 100f
            } else base
            Category.CHOICE -> {
                val acc = 1f - wrongN.toFloat() / tries
                0.6f * base + 0.4f * acc.coerceIn(0f, 1f) * 100f
            }
            Category.TIMING -> if (consN > 0) {
                val c = ((160f - meanCons()) / 140f).coerceIn(0f, 1f)
                0.7f * base + 0.3f * c * 100f
            } else base
            else -> base
        }
    }

    fun copyFrom(o: SkillStat) {
        tries = o.tries; passes = o.passes; deaths = o.deaths
        reactSum = o.reactSum; reactN = o.reactN
        errSum = o.errSum; errN = o.errN
        holdSum = o.holdSum; holdN = o.holdN
        wrongN = o.wrongN; consSum = o.consSum; consN = o.consN
        upperN = o.upperN; dashSum = o.dashSum
    }

    fun clear() = copyFrom(SkillStat())

    fun toJson(): JSONObject = JSONObject()
        .put("t", tries).put("p", passes).put("d", deaths)
        .put("rs", reactSum.toDouble()).put("rn", reactN)
        .put("es", errSum.toDouble()).put("en", errN)
        .put("hs", holdSum.toDouble()).put("hn", holdN)
        .put("w", wrongN).put("cs", consSum.toDouble()).put("cn", consN)
        .put("u", upperN).put("ds", dashSum)

    fun fromJson(j: JSONObject) {
        tries = j.optInt("t"); passes = j.optInt("p"); deaths = j.optInt("d")
        reactSum = j.optDouble("rs", 0.0).toFloat(); reactN = j.optInt("rn")
        errSum = j.optDouble("es", 0.0).toFloat(); errN = j.optInt("en")
        holdSum = j.optDouble("hs", 0.0).toFloat(); holdN = j.optInt("hn")
        wrongN = j.optInt("w"); consSum = j.optDouble("cs", 0.0).toFloat(); consN = j.optInt("cn")
        upperN = j.optInt("u"); dashSum = j.optInt("ds")
    }

    /** Short human-readable detail line for the profile. */
    fun detail(skill: Skill): String {
        if (tries == 0) return ""
        val parts = ArrayList<String>()
        if (reactN > 0) parts.add("react ${meanReact().toInt()} ms")
        if (errN > 0) {
            val e = meanErr().toInt()
            parts.add(if (e >= 0) "${e} ms early" else "${-e} ms late")
        }
        if (holdN > 0) parts.add("taps ${meanHold().toInt()} ms")
        if (consN > 0) parts.add("±${meanCons().toInt()} ms rhythm")
        if (skill.cat == Category.CHOICE && skill != Skill.SPLIT) parts.add("${(100 * wrongN / tries)}% wrong picks")
        if (skill == Skill.SPLIT) parts.add("${(100 * upperN / tries)}% high road")
        if (skill == Skill.CHASER) parts.add("${"%.1f".format(dashSum.toFloat() / tries)} dashes")
        if (deaths > 0) parts.add("$deaths deaths")
        return parts.take(3).joinToString("  •  ")
    }
}
