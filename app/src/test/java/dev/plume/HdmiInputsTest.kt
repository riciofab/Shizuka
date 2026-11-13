package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdmiInputsTest {
    @Test fun icons() {
        assertEquals("playstation", hdmiIcon("PlayStation 4", true))
        assertEquals("xbox", hdmiIcon("Xbox Series X", true))
        assertEquals("nintendoswitch", hdmiIcon("NintendoSwitch", true))
        assertEquals("appletv", hdmiIcon("Apple TV", true))
        assertEquals("nvidia", hdmiIcon("SHIELD", true))
        assertEquals("chromecast", hdmiIcon("Chromecast", true))
        assertEquals("chromecast", hdmiIcon("Google TV Streamer", true))
        assertEquals("settop", hdmiIcon("Sky Q", true))
        assertEquals("playback", hdmiIcon("BD Player", true))
        assertEquals("hdmi", hdmiIcon("HDMI 2", false))
        assertEquals("xbox", hdmiIcon("xbox", false)) // a port renamed in TV settings
        assertEquals("settop", hdmiIcon("Cable box", false))
        assertTrue(isAudioDevice("Sonos Arc"))
        assertFalse(isAudioDevice("PlayStation 5"))
    }

    @Test fun fireOsPortIsTheHardwareNumber() {
        assertEquals(2, Remap.hardwareNumber("com.mediatek.tvinput/.hdmi.HDMIInputService/HW2"))
        assertEquals(10, Remap.hardwareNumber("com.mediatek.tvinput/.hdmi.HDMIInputService/HW10"))
        assertEquals(null, Remap.hardwareNumber("com.example.tuner/.TunerService"))
        assertEquals(null, Remap.hardwareNumber("com.example/.Service/HW2/HDMI2050")) // a CEC device's id, not its port's
    }

    @Test fun theBarFollowsTheTvsPorts() {
        val tv = listOf("hdmi:tv/HW1" to "HDMI 1", "hdmi:tv/HW2" to "Xbox", "hdmi:tv/HW3" to "Philips box")
        // Nothing saved (or a list saved while the TV had no inputs to report yet): every port comes in.
        val first = TopInputManager.merge(emptyList(), tv, emptySet())
        assertEquals(listOf("tv/HW1", "tv/HW2", "tv/HW3"), first.map { it.port })
        assertEquals(listOf("hdmi", "xbox", "hdmi"), first.map { it.icon }) // "box" is no Freebox, "ps" no PlayStation
        // What the user made of an input stays as it is; a port plugged in later joins at the end, once.
        val mine = listOf(first[1].copy(name = "Console", icon = "playstation"), first[0])
        val later = TopInputManager.merge(mine, tv, emptySet())
        assertEquals(mine + first[2], later)
        assertEquals(later, TopInputManager.merge(later, tv, emptySet()))
        // A port the user deleted stays out, and an input with no port (added by hand, once) is kept.
        val byHand = TopInput("input_1", "Wii", "wii")
        assertEquals(listOf(byHand, first[0]), TopInputManager.merge(listOf(byHand), tv, setOf("tv/HW2", "tv/HW3")))
    }

    @Test fun row() {
        assertTrue(inRow("com.app", emptySet()))
        assertFalse(inRow("com.app", setOf("com.app")))
    }

    @Test fun theUpdateKeepsTheInputsThatWereInTheRow() {
        val inputs = listOf("hdmi:1" to false, "hdmi:2" to true, "hdmi:3" to true, "hdmi:4" to false)
        // 1 has a signal; 2 has none; 3 has none but was pinned; 4 has a signal but was taken out of the row
        assertEquals(setOf("hdmi:1", "hdmi:3"), pinsAfterSourcesUpdate(inputs, setOf("hdmi:4"), setOf("hdmi:3")))
    }
}
