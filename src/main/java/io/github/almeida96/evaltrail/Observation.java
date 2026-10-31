package io.github.almeida96.evaltrail;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

record Observation(JsonNode output, JsonNode toolCalls, JsonNode citations, Long tokens) {
    static Observation parse(JsonNode node) {
        Json.fields(node, Set.of("schemaVersion", "output", "toolCalls", "citations", "usage"), Set.of("schemaVersion", "output", "toolCalls", "citations"));
        Json.integer(node.get("schemaVersion"), 1, 1);
        JsonNode tools = node.get("toolCalls"), citations = node.get("citations");
        Json.require(tools.isArray() && tools.size() <= 1000, "Expected at most 1000 tool calls");
        Json.require(citations.isArray() && citations.size() <= 1000, "Expected at most 1000 citations");
        for (JsonNode tool : tools) {
            Json.fields(tool, Set.of("name", "arguments"), Set.of("name", "arguments"));
            Suite.toolName(tool.get("name")); Json.require(tool.get("arguments").isObject(), "Tool arguments must be an object");
        }
        for (JsonNode citation : citations) {
            Json.fields(citation, Set.of("sourceId"), Set.of("sourceId")); Json.text(citation.get("sourceId"), 4096);
        }
        Long tokens = null;
        if (node.has("usage")) {
            JsonNode usage = node.get("usage");
            Json.fields(usage, Set.of("inputTokens", "outputTokens"), Set.of("inputTokens", "outputTokens"));
            tokens = Json.integer(usage.get("inputTokens"), 0, 1_000_000_000_000L) + Json.integer(usage.get("outputTokens"), 0, 1_000_000_000_000L);
        }
        return new Observation(node.get("output"), tools, citations, tokens);
    }
}
