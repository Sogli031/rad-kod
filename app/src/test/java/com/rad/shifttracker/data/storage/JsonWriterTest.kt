package com.rad.shifttracker.data.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TreeMap

class JsonWriterTest {
    @Test
    fun `write keeps stable ordering and escapes strings`() {
        val value = TreeMap<String, Any?>().apply {
            put("message", "Smena \"treca\"\nred")
            put("number", 3)
            put("items", listOf("A", true, null))
        }

        val json = JsonWriter.write(value)

        assertEquals(
            """
            {
              "items": [
                "A",
                true,
                null
              ],
              "message": "Smena \"treca\"\nred",
              "number": 3
            }
            """.trimIndent(),
            json
        )
    }
}
