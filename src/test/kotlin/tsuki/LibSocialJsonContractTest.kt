package tsuki

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tsuki.site.ru.libsocial.parseLibSocialSummary

class LibSocialJsonContractTest {

    @Test
    fun `parses current structured summary`() {
        val manga = JSONObject(
            """
            {
              "summary": {
                "type": "doc",
                "content": [
                  {
                    "type": "paragraph",
                    "content": [
                      {"type": "text", "text": "First line"},
                      {"type": "hardBreak"},
                      {"type": "text", "text": "Second line"}
                    ]
                  },
                  {
                    "type": "paragraph",
                    "content": [{"type": "text", "text": "Next paragraph"}]
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals("First line\nSecond line\nNext paragraph", manga.parseLibSocialSummary())
    }

    @Test
    fun `keeps legacy string summary contract`() {
        val manga = JSONObject("""{"summary":"Plain description"}""")

        assertEquals("Plain description", manga.parseLibSocialSummary())
    }
}
