package tsuki

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tsuki.site.en.getScalarString

class MangaDotNetJsonContractTest {

    @Test
    fun `chapter id accepts current numeric API value`() {
        assertEquals("1091387", JSONObject("""{"id":1091387}""").getScalarString("id"))
    }

    @Test
    fun `chapter id preserves legacy string API value`() {
        assertEquals("1091387", JSONObject("""{"id":"1091387"}""").getScalarString("id"))
    }
}
