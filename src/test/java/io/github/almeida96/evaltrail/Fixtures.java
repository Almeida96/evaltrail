package io.github.almeida96.evaltrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import static java.nio.charset.StandardCharsets.UTF_8;
import static io.github.almeida96.evaltrail.Results.*;

final class Fixtures {
    private Fixtures() {}
    static JsonNode node(String json) throws IOException { return Json.parse(json.getBytes(UTF_8)); }
    static Suite suite(String... checks) throws IOException {
        return Suite.parse(node("{\"schemaVersion\":1,\"id\":\"suite\",\"cases\":[{\"id\":\"case\",\"input\":{\"query\":\"hello\"},\"checks\":[" + String.join(",", checks) + "]}]}"));
    }
    static ObjectNode response() {
        var response = Json.MAPPER.createObjectNode().put("schemaVersion", 1).put("output", "Hello world");
        response.putArray("toolCalls"); response.putArray("citations");
        response.putObject("usage").put("inputTokens", 100).put("outputTokens", 50); return response;
    }
    static Report report(Suite suite, CaseResult... cases) {
        return new Report(1, GRADER_VERSION, suite.id(), suite.digest(), "test", "2026-10-31T12:00:00Z", 5000, 2, List.of(cases));
    }
    static CaseResult grade(String check, ObjectNode response) throws IOException {
        return Grader.grade(suite(check).cases().getFirst(), Observation.parse(response), 20);
    }
}
