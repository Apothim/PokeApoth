package com.pokedaisey.app.companion

import android.content.Context
import com.pokedaisey.app.companion.data.SnapshotView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A shiny the tracker saw: which Pokémon, the reset count when it showed up, and where ([wild] = in a wild battle). */
data class ShinyFind(val species: Int, val atResets: Int, val wild: Boolean)

/** A Pokémon the check looked at (wild, or new in the party), so HUNT shows the tracker is alive. */
data class LastSeen(val species: Int, val value: Int, val wild: Boolean)

/**
 * HUNT's state. [resets] counts this hunt, [totalResets] every reset the
 * tracker ever saw. [modOdds] picks the shiny cutoff (see [SHINY_CUTOFF_MOD]).
 * [alert] is set while the "shiny found" banner is up.
 */
data class HuntState(
    val resets: Int = 0,
    val totalResets: Int = 0,
    val modOdds: Boolean = true,
    val lastShiny: ShinyFind? = null,
    val lastSeen: LastSeen? = null,
    /** The lowest shiny number this hunt has seen (0 = shiny), so the player can tell how close they got. */
    val closest: LastSeen? = null,
    val alert: Boolean = false,
)

/** Stock Gen 3: shiny when TID ^ SID ^ PID-high ^ PID-low is below 8 (1 in 8192). */
const val SHINY_CUTOFF_STOCK = 8

/** The modded ROM's check compares against 255 instead of 7: below 256 (1 in 256). */
const val SHINY_CUTOFF_MOD = 256

/** TID ^ SID ^ PID-high ^ PID-low, the number the game's shiny check compares. */
fun shinyValue(personality: Long, otId: Long): Int =
    ((otId and 0xFFFFL) xor ((otId shr 16) and 0xFFFFL) xor (personality and 0xFFFFL) xor ((personality shr 16) and 0xFFFFL)).toInt()

/**
 * The soft-reset shiny tracker. [onSample] (the emulator thread, ~1x/sec, via
 * TelemetryStore) counts a reset whenever gMain's frame counter drops back to
 * a few seconds after boot - a soft reset, RESTART GAME - and checks every
 * party Pokémon and the wild foe for a shiny. Each shiny is announced once
 * (its personality + OT id is remembered, so a caught one isn't announced
 * twice). Counts persist; the player's own +1 / -1 fix any reset it missed.
 *
 * Limits: two resets inside one sample count once, and a wild foe is only
 * checked while a sample lands in its battle.
 */
object HuntTracker {
    private val _state = MutableStateFlow(HuntState())
    val state: StateFlow<HuntState> = _state

    private val lock = Any()
    private var prefs: android.content.SharedPreferences? = null
    private var lastFrame = 0L
    private var alerted = LinkedHashSet<String>()
    // Pokémon (personality:OT id) already counted towards CLOSEST, so one standing in
    // the party or in front of the player isn't counted again on every sample.
    private var known = LinkedHashSet<String>()

    /** Loads the saved counts; call once with the app context. */
    fun attach(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            val p = context.getSharedPreferences("pokedaisey_hunt", Context.MODE_PRIVATE)
            prefs = p
            alerted = LinkedHashSet(p.getString("alerted", null)?.split(",")?.filter { it.isNotBlank() }.orEmpty())
            known = LinkedHashSet(p.getString("known", null)?.split(",")?.filter { it.isNotBlank() }.orEmpty())
            val species = p.getInt("shiny_species", 0)
            val closestSpecies = p.getInt("closest_species", 0)
            _state.value = HuntState(
                closest = if (closestSpecies == 0) null else LastSeen(closestSpecies, p.getInt("closest_value", 0), p.getBoolean("closest_wild", false)),
                resets = p.getInt("resets", 0),
                totalResets = p.getInt("total", 0),
                modOdds = p.getBoolean("mod_odds", true),
                lastShiny = if (species == 0) null else ShinyFind(species, p.getInt("shiny_at", 0), p.getBoolean("shiny_wild", false)),
            )
        }
    }

    fun onSample(s: SnapshotView) {
        if (!s.connected) return
        synchronized(lock) {
            var st = _state.value
            val frame = s.frameCounter
            // 0 = the counter couldn't be read, not a reboot.
            if (frame > 0L) {
                if (lastFrame > 0L && frame < lastFrame && frame < RESET_FRAMES) {
                    st = st.copy(resets = st.resets + 1, totalResets = st.totalResets + 1)
                }
                lastFrame = frame
            }
            val cutoff = if (st.modOdds) SHINY_CUTOFF_MOD else SHINY_CUTOFF_STOCK
            val seen = buildList {
                s.wildFoe?.let { add(it to true) }
                s.party.filter { !it.isEgg }.forEach { add(it to false) }
            }
            for ((mon, wild) in seen) {
                if (mon.personality == 0L || mon.otId == 0L) continue
                val value = shinyValue(mon.personality, mon.otId)
                val key = "${mon.personality}:${mon.otId}"
                if (known.add(key)) {
                    st = st.copy(lastSeen = LastSeen(mon.species, value, wild))
                    if (st.closest == null || value < st.closest!!.value) st = st.copy(closest = LastSeen(mon.species, value, wild))
                    while (known.size > MAX_KNOWN) known.remove(known.first())
                }
                if (value < cutoff && alerted.add(key)) {
                    st = st.copy(alert = true, lastShiny = ShinyFind(mon.species, st.resets, wild))
                    while (alerted.size > MAX_ALERTED) alerted.remove(alerted.first())
                }
            }
            commit(st)
        }
    }

    fun dismissAlert() = update { it.copy(alert = false) }
    fun addReset(delta: Int) = update { it.copy(resets = (it.resets + delta).coerceAtLeast(0)) }
    fun newHunt() = update { it.copy(resets = 0, closest = null, alert = false) }
    fun setModOdds(on: Boolean) = update { it.copy(modOdds = on) }

    private fun update(change: (HuntState) -> HuntState) = synchronized(lock) { commit(change(_state.value)) }

    private fun commit(st: HuntState) {
        if (st == _state.value) return
        _state.value = st
        prefs?.edit()?.apply {
            putInt("resets", st.resets)
            putInt("total", st.totalResets)
            putBoolean("mod_odds", st.modOdds)
            putString("alerted", alerted.joinToString(","))
            putString("known", known.joinToString(","))
            st.closest?.let { putInt("closest_species", it.species); putInt("closest_value", it.value); putBoolean("closest_wild", it.wild) }
                ?: run { remove("closest_species"); remove("closest_value"); remove("closest_wild") }
            st.lastShiny?.let { putInt("shiny_species", it.species); putInt("shiny_at", it.atResets); putBoolean("shiny_wild", it.wild) }
        }?.apply()
    }

    /** ~2 minutes of game time: a counter that fell to less than this just booted. */
    private const val RESET_FRAMES = 7200L
    private const val MAX_ALERTED = 200
    private const val MAX_KNOWN = 300
}
