package com.stellarite.stellardash

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

enum class Slot(val label: String) {
    BODY("Body"), EYES("Eyes"), COLOR("Colour"), TRAIL("Trail"), SPACE("Space")
}

/** A cosmetic. Either costs shards, or is unlocked only by [achievement]. */
class Item(
    val id: String,
    val slot: Slot,
    val name: String,
    val cost: Int,
    val achievement: Achievement? = null,
    val color: Int = 0
)

enum class Achievement(val title: String, val desc: String) {
    FIRST("First Light", "Clear your first Orbit or Deep Space level"),
    CALIBRATED("Calibrated", "Finish the 25-level Orbit test"),
    GRADUATE("Graduate", "Finish 100 training levels"),
    STAGE2("Second Orbit", "Complete a retest"),
    IMPROVER("Sharper", "Raise your reflex rating by 10+ in a retest"),
    QUICKDRAW("Quick Draw", "React to a warning in under 250 ms"),
    LIGHTNING("Lightning", "Average under 300 ms reaction on a level"),
    COLLECTOR("Collector", "Collect 100 shards"),
    HOARDER("Hoarder", "Collect 1,000 shards"),
    FAROUT("Far Out", "Score 2,000 in Drift"),
    LIGHTYEAR("Light Year", "Score 5,000 in Drift"),
    FLAWLESS("Flawless", "Clear a level first try, every hazard passed"),
    SURVIVOR("Survivor", "Clear a Deep Space checkpoint without losing a life"),
    DEEPDIVER("Deep Diver", "Clear 25 Deep Space levels"),
    COMMANDER("Commander", "Finish the Deep Space test"),
    NERVE("Nerves of Steel", "Hold your nerve on 10 decoys"),
    RHYTHM("On the Beat", "Finish a rhythm run within ±40 ms"),
    PHASER("Phase Shifter", "Dash through 20 phase walls"),
    GRAVITY("Gravity Defier", "Clear 10 reverse-gravity sections"),
    FLOATER("Zero-G Ace", "Clear 10 zero-gravity sections"),
    PERSISTENT("Never Give Up", "Crash 100 times. Keep going!")
}

object Catalog {
    val items: List<Item> = listOf(
        // Bodies
        Item("body_cube", Slot.BODY, "Cube", 0),
        Item("body_orb", Slot.BODY, "Orb", 0, Achievement.CALIBRATED),
        Item("body_diamond", Slot.BODY, "Diamond", 120),
        Item("body_capsule", Slot.BODY, "Capsule", 180),
        Item("body_tri", Slot.BODY, "Arrowhead", 0, Achievement.COMMANDER),
        Item("body_hex", Slot.BODY, "Hexcore", 0, Achievement.LIGHTYEAR),
        Item("body_star", Slot.BODY, "Starform", 0, Achievement.HOARDER),
        // Eyes
        Item("eyes_visor", Slot.EYES, "Visor", 0),
        Item("eyes_dot", Slot.EYES, "Dot", 0, Achievement.FIRST),
        Item("eyes_twin", Slot.EYES, "Twin", 0, Achievement.FLAWLESS),
        Item("eyes_slit", Slot.EYES, "Slit", 0, Achievement.QUICKDRAW),
        Item("eyes_cyclops", Slot.EYES, "Cyclops", 100),
        Item("eyes_shades", Slot.EYES, "Shades", 150),
        Item("eyes_x", Slot.EYES, "Steel X", 0, Achievement.NERVE),
        // Colours
        Item("col_teal", Slot.COLOR, "Teal", 0, color = Color.rgb(0x20, 0xE3, 0xC2)),
        Item("col_lime", Slot.COLOR, "Lime", 0, Achievement.COLLECTOR, Color.rgb(0xB6, 0xFF, 0x3B)),
        Item("col_sky", Slot.COLOR, "Sky", 60, color = Color.rgb(0x5A, 0xC8, 0xFF)),
        Item("col_magenta", Slot.COLOR, "Magenta", 60, color = Color.rgb(0xFF, 0x4F, 0xD8)),
        Item("col_amber", Slot.COLOR, "Amber", 60, color = Color.rgb(0xFF, 0xB0, 0x3B)),
        Item("col_white", Slot.COLOR, "Starlight", 80, color = Color.rgb(0xF2, 0xF6, 0xFF)),
        Item("col_violet", Slot.COLOR, "Violet", 0, Achievement.PHASER, Color.rgb(0xA8, 0x5C, 0xFF)),
        Item("col_crimson", Slot.COLOR, "Crimson", 0, Achievement.SURVIVOR, Color.rgb(0xE8, 0x2B, 0x4A)),
        Item("col_gold", Slot.COLOR, "Gold", 0, Achievement.IMPROVER, Color.rgb(0xFF, 0xD7, 0x3B)),
        // Trails
        Item("trail_spark", Slot.TRAIL, "Sparks", 0),
        Item("trail_dots", Slot.TRAIL, "Dots", 80),
        Item("trail_ribbon", Slot.TRAIL, "Ribbon", 0, Achievement.LIGHTNING),
        Item("trail_stars", Slot.TRAIL, "Stardust", 0, Achievement.GRAVITY),
        Item("trail_pixels", Slot.TRAIL, "Pixels", 0, Achievement.RHYTHM),
        Item("trail_bubbles", Slot.TRAIL, "Bubbles", 0, Achievement.FLOATER),
        Item("trail_comet", Slot.TRAIL, "Comet", 0, Achievement.GRADUATE),
        // Backgrounds
        Item("bg_deep", Slot.SPACE, "Deep Space", 0),
        Item("bg_nebula", Slot.SPACE, "Nebula", 150),
        Item("bg_ocean", Slot.SPACE, "Ocean World", 200),
        Item("bg_aurora", Slot.SPACE, "Aurora", 0, Achievement.STAGE2),
        Item("bg_red", Slot.SPACE, "Red Giant", 0, Achievement.FAROUT),
        Item("bg_binary", Slot.SPACE, "Binary Suns", 0, Achievement.DEEPDIVER),
        Item("bg_void", Slot.SPACE, "The Void", 0, Achievement.PERSISTENT)
    )

    fun byId(id: String): Item? = items.firstOrNull { it.id == id }
    fun slot(s: Slot) = items.filter { it.slot == s }
    fun rewardFor(a: Achievement): Item? = items.firstOrNull { it.achievement == a }
    fun default(s: Slot): Item = items.first { it.slot == s && it.cost == 0 && it.achievement == null }
}

/** Everything that persists across modes: shards wallet, cosmetics, achievements and counters. */
class Meta {
    var wallet = 0
    var lifetimeShards = 0
    var deaths = 0
    var decoys = 0
    var phases = 0
    var flips = 0
    var floats = 0
    var deepClears = 0
    val owned = HashSet<String>()
    val equipped = HashMap<Slot, String>()
    val achieved = HashSet<Achievement>()

    init {
        for (s in Slot.values()) {
            val d = Catalog.default(s)
            owned.add(d.id)
            equipped[s] = d.id
        }
    }

    fun equippedItem(s: Slot): Item = Catalog.byId(equipped[s] ?: "") ?: Catalog.default(s)

    fun isOwned(i: Item) = owned.contains(i.id)

    /** @return the reward item if this is a new achievement, else null. */
    fun award(a: Achievement): Item? {
        if (!achieved.add(a)) return null
        val r = Catalog.rewardFor(a)
        if (r != null) owned.add(r.id)
        return r ?: Catalog.default(Slot.BODY)
    }

    fun buy(i: Item): Boolean {
        if (isOwned(i) || i.achievement != null || wallet < i.cost) return false
        wallet -= i.cost
        owned.add(i.id)
        equipped[i.slot] = i.id
        return true
    }

    fun toJson(): String {
        val j = JSONObject()
        j.put("w", wallet).put("ls", lifetimeShards).put("d", deaths).put("dc", decoys)
        j.put("ph", phases).put("fl", flips).put("zg", floats).put("dp", deepClears)
        j.put("own", JSONArray().also { a -> owned.forEach { a.put(it) } })
        j.put("ach", JSONArray().also { a -> achieved.forEach { a.put(it.name) } })
        val eq = JSONObject()
        for ((k, v) in equipped) eq.put(k.name, v)
        j.put("eq", eq)
        return j.toString()
    }

    fun load(json: String?) {
        if (json.isNullOrEmpty()) return
        try {
            val j = JSONObject(json)
            wallet = j.optInt("w"); lifetimeShards = j.optInt("ls"); deaths = j.optInt("d")
            decoys = j.optInt("dc"); phases = j.optInt("ph"); flips = j.optInt("fl")
            floats = j.optInt("zg"); deepClears = j.optInt("dp")
            j.optJSONArray("own")?.let { a -> for (i in 0 until a.length()) owned.add(a.optString(i)) }
            j.optJSONArray("ach")?.let { a ->
                for (i in 0 until a.length()) {
                    val n = a.optString(i)
                    Achievement.values().firstOrNull { it.name == n }?.let { achieved.add(it) }
                }
            }
            j.optJSONObject("eq")?.let { eq ->
                for (s in Slot.values()) {
                    val id = eq.optString(s.name, "")
                    if (id.isNotEmpty() && owned.contains(id)) equipped[s] = id
                }
            }
        } catch (e: Exception) {
            // corrupt save: keep defaults
        }
    }
}
