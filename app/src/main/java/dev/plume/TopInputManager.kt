package dev.plume

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class TopInput(
    val id: String,
    val name: String,
    val icon: String, // nintendoswitch, wii, freebox, pc, playstation, xbox, appletv, hdmi
    val port: String = "", // e.g. "HW0", "hdmi1", or empty
    val hidden: Boolean = false
)

object TopInputManager {
    const val PREF_TOP_INPUTS = "top_bar_inputs"

    fun loadInputs(prefs: SharedPreferences, detectedHdmi: List<Pair<String, String>> = emptyList()): List<TopInput> {
        val raw = prefs.getString(PREF_TOP_INPUTS, null)
        if (!raw.isNullOrEmpty()) {
            return try {
                val array = JSONArray(raw)
                val list = mutableListOf<TopInput>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        TopInput(
                            id = obj.optString("id", "input_$i"),
                            name = obj.optString("name", "Input ${i + 1}"),
                            icon = obj.optString("icon", "hdmi"),
                            port = obj.optString("port", ""),
                            hidden = obj.optBoolean("hidden", false)
                        )
                    )
                }
                list
            } catch (_: Exception) {
                defaultInputs(detectedHdmi)
            }
        }
        val def = defaultInputs(detectedHdmi)
        saveInputs(prefs, def)
        return def
    }

    fun defaultInputs(detectedHdmi: List<Pair<String, String>> = emptyList()): List<TopInput> {
        if (detectedHdmi.isNotEmpty()) {
            return detectedHdmi.mapIndexed { idx, (pkg, label) ->
                val port = pkg.removePrefix("input:").removePrefix("hdmi:")
                val icon = when {
                    label.contains("switch", true) -> "nintendoswitch"
                    label.contains("wii", true) -> "wii"
                    label.contains("freebox", true) || label.contains("box", true) -> "freebox"
                    label.contains("pc", true) || label.contains("ordinateur", true) -> "pc"
                    label.contains("playstation", true) || label.contains("ps", true) -> "playstation"
                    label.contains("xbox", true) -> "xbox"
                    label.contains("apple", true) -> "appletv"
                    else -> "hdmi"
                }
                TopInput(
                    id = "input_${System.currentTimeMillis()}_$idx",
                    name = label,
                    icon = icon,
                    port = port,
                    hidden = false
                )
            }
        }
        return emptyList()
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
