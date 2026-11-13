package dev.plume

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The organiser's server answering real requests, as a phone on the Wi-Fi sends them. */
class PhoneServerTest {
    private val texts = ManageTexts("Organize <home>", "intro", "Category's name", "Add", "Rest", "Move to…", "Save", "Saved.", "Failed.",
        "Loading…", "Unreachable", "Empty", "Not found.")

    private fun call(url: String, body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        return code to (if (code == 200) c.inputStream else c.errorStream).bufferedReader().readText()
    }

    @Test fun theOrganiserAnswersOnlyUnderItsCode() {
        var saved: String? = null
        val server = ManageServer("4821", texts, state = { """{"cats":[],"apps":{}}""" }) { body -> saved = body; "cats" in body }
        try {
            val base = "http://127.0.0.1:${server.port}"
            val (ok, page) = call("$base/4821")
            assertEquals(200, ok)
            assertTrue("Organize &lt;home&gt;" in page) // a text is text, never markup
            assertTrue("name:'Category\\'s name'" in page) // and stays inside its script string
            File("build").mkdirs()
            File("build/organiser-page.html").writeText(page) // for a look in a browser, and the script check

            assertEquals(200 to """{"cats":[],"apps":{}}""", call("$base/4821/state"))
            assertEquals(200 to "Saved.", call("$base/4821/state", """{"cats":[{"id":"a","name":"Télé","apps":[]}]}"""))
            assertEquals("""{"cats":[{"id":"a","name":"Télé","apps":[]}]}""", saved)
            assertEquals(400 to "Failed.", call("$base/4821/state", "nonsense"))

            // No code, another code, or a path the page doesn't have: nothing to see.
            assertEquals(404 to "Not found.", call("$base/"))
            assertEquals(404 to "Not found.", call("$base/1234/state"))
            assertEquals(404 to "Not found.", call("$base/48210"))
            assertEquals(404 to "Not found.", call("$base/4821/other"))
        } finally {
            server.stop()
        }
    }
}
