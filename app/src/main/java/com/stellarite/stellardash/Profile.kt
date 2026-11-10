package com.stellarite.stellardash

import org.json.JSONArray
import org.json.JSONObject

class TestResult(
    val cycle: Int,
    val retest: Boolean,
    val rating: Float,
    val skills: FloatArray,
    val reactMs: Float
)

class PhaseEvent(
    val title: String,
    val rating: Float,
    val prevRating: Float,
    val focus: List<Skill>,
    val lines: List<String> = emptyList(),
    val focusLabel: String = "Next 100 levels focus on: "
)

/**
 * Progress + reaction statistics for Trainer or Expert Trainer.
 *
 * Loop:  TEST(25) -> TRAIN(100) -> RETEST(25) -> TRAIN(100) -> RETEST(25) -> ...
 * Each completed test/retest becomes a node on the profile's reflex map.
 */
class TrainerProfile(val expert: Boolean) {

    var cycle = 0
    var phase = PHASE_TEST
    var level = 0
    var lives = MAX_LIVES
    var levelAttempts = 0
    var totalClears = 0
    var totalDeaths = 0

    /** Per-skill training difficulty 0..1 (staircase: pass -> harder, fail -> easier). */
    val diff = FloatArray(Skill.COUNT) { 0.3f }
    /** Stats for the phase currently being played. */
    val block = Array(Skill.COUNT) { SkillStat() }
    /** Stats from the most recently completed test/retest (drives training focus). */
    val lastTest = Array(Skill.COUNT) { SkillStat() }
    val history = ArrayList<TestResult>()
    /** Stats since the last re-evaluation (training only). */
    val recent = Array(Skill.COUNT) { SkillStat() }
    /** Training focus weights; recalculated after each test and every 10 training levels. */
    val focusW = FloatArray(Skill.COUNT) { 1f }

    // Frozen spec for the current level so retries replay the same layout.
    var specValid = false
    var specSeed = 0L
    var specSpeed = 400f
    val specD = FloatArray(Skill.COUNT)
    val specW = FloatArray(Skill.COUNT)

    fun phaseLength(): Int = if (phase == PHASE_TRAIN) TRAIN_LEVELS else TEST_LEVELS

    fun phaseName(): String = when (phase) {
        PHASE_TEST -> "TEST"
        PHASE_TRAIN -> "TRAINING"
        else -> "RETEST"
    }

    fun isTestPhase() = phase != PHASE_TRAIN

    /** Skills shown for the first time on this level (first test only). */
    fun newSkillsThisLevel(): List<Skill> =
        if (cycle == 0 && phase == PHASE_TEST) Skill.ALL.filter { it.introLevel == level && allowed(it) } else emptyList()

    fun allowed(s: Skill) = expert || !s.expertOnly

    fun ensureSpec() {
        if (specValid) return
        specSeed = (if (expert) 7_000_000L else 3_000_000L) + cycle * 100_000L + phase * 10_000L + level
        if (isTestPhase()) {
            // Same structure for every test/retest so results are comparable.
            val t = level / (TEST_LEVELS - 1f)
            for (k in 0 until Skill.COUNT) {
                val s = Skill.ALL[k]
                specD[k] = 0.1f + 0.6f * t
                specW[k] = when {
                    !allowed(s) -> 0f
                    s.introLevel > level -> 0f
                    s.introLevel == level -> 2.5f
                    else -> 1f
                }
            }
            specSpeed = 400f + 300f * t
        } else {
            var avg = 0f
            var n = 0
            for (k in 0 until Skill.COUNT) {
                specD[k] = diff[k]
                if (!allowed(Skill.ALL[k])) { specW[k] = 0f; continue }
                avg += diff[k]; n++
                specW[k] = focusW[k]
            }
            avg /= n.coerceAtLeast(1)
            specSpeed = 420f + 320f * avg + (level % 10) * 5f
        }
        if (expert) specSpeed *= 1.1f
        specValid = true
    }

    fun toConfig(): LevelConfig = LevelConfig(
        seed = specSeed,
        speed = specSpeed,
        d = specD.copyOf(),
        w = specW.copyOf(),
        lengthSec = 24f,
        expert = expert,
        endless = false,
        intro = if (isTestPhase()) Skill.ALL.filter { it.introLevel == level && allowed(it) } else emptyList()
    )

    fun recordEncounter(e: Encounter, died: Boolean) {
        val k = e.skill.ordinal
        block[k].add(e, died)
        if (phase == PHASE_TRAIN) {
            recent[k].add(e, died)
            diff[k] = (diff[k] + if (e.passed) 0.03f else -0.06f).coerceIn(0.05f, 1f)
        }
    }

    /** @return true if Expert ran out of lives and was sent back to the checkpoint. */
    fun recordDeath(): Boolean {
        totalDeaths++
        levelAttempts++
        if (!expert) return false
        lives--
        if (lives <= 0) {
            level = (level / CHECKPOINT) * CHECKPOINT
            lives = MAX_LIVES
            levelAttempts = 0
            specValid = false
            return true
        }
        return false
    }

    fun completeLevel(): PhaseEvent? {
        totalClears++
        level++
        levelAttempts = 0
        specValid = false
        if (expert && level % CHECKPOINT == 0) lives = MAX_LIVES
        if (level < phaseLength()) {
            return if (phase == PHASE_TRAIN && level % REEVAL_EVERY == 0) reevaluate() else null
        }

        level = 0
        lives = MAX_LIVES
        if (phase == PHASE_TRAIN) {
            phase = PHASE_RETEST
            clearBlock()
            return PhaseEvent("TRAINING COMPLETE", -1f, -1f, emptyList())
        }

        val wasRetest = phase == PHASE_RETEST
        val result = makeResult(wasRetest)
        val prev = history.lastOrNull()
        history.add(result)
        for (k in 0 until Skill.COUNT) lastTest[k].copyFrom(block[k])
        if (!wasRetest) initDiffFromTest()
        initFocusFromTest()
        for (r in recent) r.clear()
        if (wasRetest) cycle++
        phase = PHASE_TRAIN
        val focus = weakest(2)
        clearBlock()
        return PhaseEvent(
            if (wasRetest) "RETEST COMPLETE" else "CALIBRATION COMPLETE",
            result.rating,
            prev?.rating ?: -1f,
            focus
        )
    }

    fun weakest(n: Int): List<Skill> =
        Skill.ALL
            .filter { lastTest[it.ordinal].tries > 0 }
            .sortedBy { lastTest[it.ordinal].score(it) }
            .take(n)

    private fun initFocusFromTest() {
        val totalDeaths = lastTest.sumOf { it.deaths }.coerceAtLeast(1)
        for (k in 0 until Skill.COUNT) {
            val st = lastTest[k]
            val pr = st.passRate()
            val weakness = if (pr < 0f) 0.7f else 1f - pr
            focusW[k] = 0.4f + 2.4f * weakness + 1.5f * st.deaths / totalDeaths.toFloat()
        }
    }

    /** Every 10 training levels: compare recent play with the last test and re-aim the training. */
    private fun reevaluate(): PhaseEvent {
        val improved = ArrayList<Skill>()
        val struggling = ArrayList<Skill>()
        val recentDeaths = recent.sumOf { it.deaths }.coerceAtLeast(1)
        for (k in 0 until Skill.COUNT) {
            val s = Skill.ALL[k]
            if (!allowed(s)) continue
            val r = recent[k]
            if (r.tries >= 2) {
                val rp = r.passRate()
                val base = lastTest[k].passRate()
                if (base >= 0f && rp - base >= 0.15f) improved.add(s)
                if (rp < 0.6f) struggling.add(s)
                focusW[k] = 0.4f + 2.4f * (1f - rp) + 1.0f * r.deaths / recentDeaths.toFloat()
            } else {
                // Barely seen lately: drift toward average so it still comes up.
                focusW[k] = focusW[k] * 0.8f + 0.25f
            }
        }
        for (r in recent) r.clear()
        specValid = false
        val focus = Skill.ALL.filter { allowed(it) }.sortedByDescending { focusW[it.ordinal] }.take(2)
        val lines = ArrayList<String>()
        lines.add(if (improved.isEmpty()) "Improving: keep going, nothing has jumped ahead yet"
                  else "Improving: " + improved.take(3).joinToString(", ") { it.label })
        if (struggling.isNotEmpty()) lines.add("Needs work: " + struggling.take(3).joinToString(", ") { it.label })
        return PhaseEvent("RE-EVALUATION", -1f, -1f, focus, lines, "Next 10 levels focus on: ")
    }

    private fun initDiffFromTest() {
        for (k in 0 until Skill.COUNT) {
            val pr = lastTest[k].passRate()
            diff[k] = if (pr < 0f) 0.3f else (0.15f + 0.55f * pr).coerceIn(0.1f, 0.75f)
        }
    }

    private fun makeResult(retest: Boolean): TestResult {
        val scores = FloatArray(Skill.COUNT) { block[it].score(Skill.ALL[it]) }
        val seen = scores.filter { it >= 0f }
        val rating = if (seen.isEmpty()) 0f else seen.average().toFloat()
        val react = block[Skill.POPUP.ordinal].meanReact()
        return TestResult(cycle, retest, rating, scores, react)
    }

    private fun clearBlock() {
        for (s in block) s.clear()
    }

    /** Rating of the in-progress block (shown live on the profile). */
    fun liveRating(): Float {
        val seen = Skill.ALL.map { block[it.ordinal].score(it) }.filter { it >= 0f }
        return if (seen.isEmpty()) -1f else seen.average().toFloat()
    }

    /** Category score from a stat set (mean of seen skills), or -1. */
    fun categoryScore(stats: Array<SkillStat>, c: Category): Float {
        val seen = Skill.ALL.filter { it.cat == c }.map { stats[it.ordinal].score(it) }.filter { it >= 0f }
        return if (seen.isEmpty()) -1f else seen.average().toFloat()
    }

    fun categoryScoreFromResult(r: TestResult, c: Category): Float {
        val seen = Skill.ALL.filter { it.cat == c }.map { r.skills.getOrElse(it.ordinal) { -1f } }.filter { it >= 0f }
        return if (seen.isEmpty()) -1f else seen.average().toFloat()
    }

    fun reset() {
        cycle = 0; phase = PHASE_TEST; level = 0; lives = MAX_LIVES; levelAttempts = 0
        totalClears = 0; totalDeaths = 0
        diff.fill(0.3f)
        for (b in block) b.clear()
        for (b in lastTest) b.clear()
        for (b in recent) b.clear()
        focusW.fill(1f)
        history.clear()
        specValid = false
    }

    // ---------- persistence ----------
    fun toJson(): String {
        val j = JSONObject()
        j.put("cycle", cycle).put("phase", phase).put("level", level).put("lives", lives)
        j.put("att", levelAttempts).put("clears", totalClears).put("deaths", totalDeaths)
        j.put("diff", JSONArray().also { a -> diff.forEach { a.put(it.toDouble()) } })
        j.put("block", JSONArray().also { a -> block.forEach { a.put(it.toJson()) } })
        j.put("last", JSONArray().also { a -> lastTest.forEach { a.put(it.toJson()) } })
        j.put("recent", JSONArray().also { a -> recent.forEach { a.put(it.toJson()) } })
        j.put("fw", JSONArray().also { a -> focusW.forEach { a.put(it.toDouble()) } })
        val h = JSONArray()
        for (r in history) {
            h.put(
                JSONObject()
                    .put("c", r.cycle).put("re", r.retest).put("r", r.rating.toDouble())
                    .put("rt", r.reactMs.toDouble())
                    .put("s", JSONArray().also { a -> r.skills.forEach { a.put(it.toDouble()) } })
            )
        }
        j.put("hist", h)
        return j.toString()
    }

    fun load(json: String?) {
        if (json.isNullOrEmpty()) return
        try {
            val j = JSONObject(json)
            cycle = j.optInt("cycle"); phase = j.optInt("phase"); level = j.optInt("level")
            lives = j.optInt("lives", MAX_LIVES); levelAttempts = j.optInt("att")
            totalClears = j.optInt("clears"); totalDeaths = j.optInt("deaths")
            j.optJSONArray("diff")?.let { a ->
                for (k in 0 until minOf(a.length(), Skill.COUNT)) diff[k] = a.optDouble(k, 0.3).toFloat()
            }
            j.optJSONArray("block")?.let { a ->
                for (k in 0 until minOf(a.length(), Skill.COUNT)) a.optJSONObject(k)?.let { block[k].fromJson(it) }
            }
            j.optJSONArray("last")?.let { a ->
                for (k in 0 until minOf(a.length(), Skill.COUNT)) a.optJSONObject(k)?.let { lastTest[k].fromJson(it) }
            }
            j.optJSONArray("recent")?.let { a ->
                for (k in 0 until minOf(a.length(), Skill.COUNT)) a.optJSONObject(k)?.let { recent[k].fromJson(it) }
            }
            val fw = j.optJSONArray("fw")
            if (fw != null) {
                for (k in 0 until minOf(fw.length(), Skill.COUNT)) focusW[k] = fw.optDouble(k, 1.0).toFloat()
            } else {
                initFocusFromTest()
            }
            history.clear()
            j.optJSONArray("hist")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val sa = o.optJSONArray("s")
                    val sk = FloatArray(Skill.COUNT) { k -> sa?.optDouble(k, -1.0)?.toFloat() ?: -1f }
                    history.add(
                        TestResult(
                            o.optInt("c"), o.optBoolean("re"), o.optDouble("r", 0.0).toFloat(),
                            sk, o.optDouble("rt", -1.0).toFloat()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Corrupt save: start fresh rather than crash.
        }
        specValid = false
    }

    companion object {
        const val PHASE_TEST = 0
        const val PHASE_TRAIN = 1
        const val PHASE_RETEST = 2
        const val TEST_LEVELS = 25
        const val TRAIN_LEVELS = 100
        const val MAX_LIVES = 3
        const val CHECKPOINT = 5
        const val REEVAL_EVERY = 10
    }
}
