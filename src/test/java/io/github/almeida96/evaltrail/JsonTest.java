package io.github.almeida96.evaltrail;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void rejectsUnpairedSurrogateEscapesBeforeFingerprintingOrGrading() {
        for (String escape : new String[] {"\\ud800", "\\udbff", "\\udc00", "\\udfff", "\\ud800x", "\\ud800\\ud800"}) {
            assertInvalidJson("{\"output\":\"" + escape + "\"}");
            assertInvalidJson("{\"nested\":[{\"" + escape + "\":true}]}");
        }
        assertThrows(Json.InputException.class, () -> Json.digest(Json.MAPPER.createObjectNode().put("text", "\ud800")));
        assertThrows(Json.InputException.class, () -> Json.digest(Json.MAPPER.createObjectNode().put("\udfff", true)));
    }

    @Test
    void validSupplementaryCharactersHaveStableDistinctFingerprints() throws Exception {
        var escaped = Json.parse("{\"text\":\"\\ud83d\\ude80\"}".getBytes(UTF_8));
        var literal = Json.parse("{\"text\":\"🚀\"}".getBytes(UTF_8));
        assertEquals(Json.digest(escaped), Json.digest(literal));
        assertNotEquals(Json.digest(escaped), Json.digest(Json.parse("{\"text\":\"?\"}".getBytes(UTF_8))));
        assertEquals("🚀", escaped.get("text").textValue());
    }

    private static void assertInvalidJson(String input) {
        Exception failure = assertThrows(Exception.class, () -> Json.parse(input.getBytes(UTF_8)));
        // Jackson rejects some malformed object keys before tree validation runs.
        assertTrue(failure instanceof IOException || failure instanceof Json.InputException);
    }
}
