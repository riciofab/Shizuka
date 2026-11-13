package dev.plume

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class TopInput(
    val id: String,
    val name: String,
    val icon: String, // a key of HDMI_ICONS: nintendoswitch, wii, freebox, pc, playstation, xbox, appletv, hdmi…
    val port: String = "", // the TV input id of its HDMI port; empty for an input added by hand before 1.4.2
    val hidden: Boolean = false
)

/**
 * The inputs in the top bar: what the user made of them (name, icon, order, hidden), kept in step with the TV's HDMI
 * ports. A port the TV reports that the bar doesn't have yet is added at the end, whenever it shows up: a TV often
 * reports its inputs only a while after the launcher starts, and devices get plugged in later. A port the user
 * deleted stays out until they add it again.
 */
object TopInputManager {
    const val PREF_TOP_INPUTS = "top_bar_inputs"
    const val PREF_REMOVED = "top_bar_inputs_removed" // ports the user deleted from the bar
    private const val PREF_SYNCED = "top_bar_inputs_synced" // the saved list has been through [merge] once

    /** The TV input id in a detected input's key ("hdmi:<id>"). */
    fun portOf(pkg: String) = pkg.removePrefix("input:").removePrefix("hdmi:")

    /** [detectedHdmi]: the TV's HDMI inputs now, as key ("hdmi:<TV input id>") to name. */
    fun loadInputs(prefs: SharedPreferences, detectedHdmi: List<Pair<String, String>> = emptyList()): List<TopInput> {
        val raw = prefs.getString(PREF_TOP_INPUTS, null)
        val saved = parse(raw)
        var removed = prefs.getStringSet(PREF_REMOVED, emptySet()) ?: emptySet()
        // A list saved before the bar followed the TV: the ports it lacks were deleted by hand, so they stay out.
        // An empty one deleted nothing: the TV just had no inputs to report yet, the day it was saved.
        if (!prefs.getBoolean(PREF_SYNCED, false) && detectedHdmi.isNotEmpty()) {
            if (saved.isNotEmpty()) removed = removed + detectedHdmi.map { portOf(it.first) }.filter { p -> saved.none { it.port == p } }
            prefs.edit().putBoolean(PREF_SYNCED, true).putStringSet(PREF_REMOVED, removed).apply()
        }
        val merged = merge(saved, detectedHdmi, removed)
        if (raw == null || merged != saved) saveInputs(prefs, merged)
        return merged
    }

    private fun parse(raw: String?): List<TopInput> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                TopInput(
                    id = obj.optString("id", "input_$i"),
                    name = obj.optString("name", "Input ${i + 1}"),
                    icon = obj.optString("icon", "hdmi"),
                    port = obj.optString("port", ""),
                    hidden = obj.optBoolean("hidden", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** [saved] as it is, then an input for each detected port it lacks, unless the user deleted that port ([removed]). */
    internal fun merge(saved: List<TopInput>, detectedHdmi: List<Pair<String, String>>, removed: Set<String>): List<TopInput> {
        val known = saved.mapTo(HashSet()) { it.port }
        val fresh = detectedHdmi.map { (pkg, label) -> portOf(pkg) to label }
            .filter { (port, _) -> port.isNotEmpty() && port !in known && port !in removed }
            .distinctBy { it.first }
        return saved + fresh.map { (port, label) -> newInput(port, label) }
    }

    /** The bar's input for a port: named as the TV names it, with the icon its name suggests (plain HDMI otherwise). */
    internal fun newInput(port: String, label: String) =
        TopInput(id = "input_" + Integer.toHexString(port.hashCode()), name = label, icon = hdmiIcon(label, named = false), port = port)

    /** Deletes an input from the bar. Its port stays out of the bar until [restore]. */
    fun delete(prefs: SharedPreferences, inputs: List<TopInput>, input: TopInput) {
        if (input.port.isNotEmpty()) {
            prefs.edit().putStringSet(PREF_REMOVED, (prefs.getStringSet(PREF_REMOVED, emptySet()) ?: emptySet()) + input.port).apply()
        }
        saveInputs(prefs, inputs.filter { it.id != input.id })
    }

    /** Lets a deleted port back in: the next [loadInputs] adds it. */
    fun restore(prefs: SharedPreferences, port: String) {
        prefs.edit().putStringSet(PREF_REMOVED, (prefs.getStringSet(PREF_REMOVED, emptySet()) ?: emptySet()) - port).apply()
    }

    fun saveInputs(prefs: SharedPreferences, inputs: List<TopInput>) {
        val array = JSONArray()
        for (item in inputs) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("icon", item.icon)
            obj.put("port", item.port)
            obj.put("hidden", item.hidden)
            array.put(obj)
        }
        prefs.edit().putString(PREF_TOP_INPUTS, array.toString()).apply()
    }
}
