package io.github.almeida96.evaltrail;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

record Suite(String id, String digest, List<Case> cases) {
    record Case(String id, String digest, JsonNode input, List<JsonNode> checks) {}
    static final Set<String> CHECK_TYPES = Set.of("text_contains", "text_excludes", "json_equals", "tool_called", "tool_forbidden",
        "tool_sequence", "citation", "max_tool_calls", "max_tokens", "max_latency_ms");

    static Suite load(Path path) throws IOException { return parse(Json.read(path, 2 * 1024 * 1024)); }

    static Suite parse(JsonNode node) {
        Json.fields(node, Set.of("schemaVersion", "id", "cases"), Set.of("schemaVersion", "id", "cases"));
        Json.integer(node.get("schemaVersion"), 1, 1);
        String id = Json.id(node.get("id")); JsonNode rows = node.get("cases");
        Json.require(rows.isArray() && !rows.isEmpty() && rows.size() <= 200, "Suite must contain 1–200 cases");
        var ids = new HashSet<String>(); var cases = new ArrayList<Case>();
        for (JsonNode row : rows) {
            Json.fields(row, Set.of("id", "input", "checks"), Set.of("id", "input", "checks"));
            String caseId = Json.id(row.get("id")); Json.require(ids.add(caseId), "Duplicate case ID");
            JsonNode checks = row.get("checks");
            Json.require(checks.isArray() && !checks.isEmpty() && checks.size() <= 50, "Case must contain 1–50 checks");
            Json.require(Json.canonical(row.get("input")).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 65_536, "Case input exceeds 64 KiB");
            var validated = new ArrayList<JsonNode>();
            for (JsonNode check : checks) { validateCheck(check); validated.add(check.deepCopy()); }
            cases.add(new Case(caseId, Json.digest(row), row.get("input").deepCopy(), List.copyOf(validated)));
        }
        return new Suite(id, Json.digest(node), List.copyOf(cases));
    }

    private static void validateCheck(JsonNode check) {
        Json.require(check.isObject(), "Check must be an object");
        String type = Json.text(check.get("type"), 40);
        switch (type) {
            case "text_contains", "text_excludes", "citation" -> {
                exactFields(check, "value"); Json.text(check.get("value"), 4096);
            }
            case "json_equals" -> {
                exactFields(check, "path", "value"); JsonNode path = check.get("path");
                Json.require(path.isTextual() && path.textValue().length() <= 1024, "Invalid JSON pointer");
                Json.require(path.textValue().matches("(?:/(?:[^~]|~[01])*)*"), "Invalid JSON pointer escape");
                JsonPointer.compile(path.textValue());
            }
            case "tool_called" -> {
                Json.fields(check, Set.of("type", "name", "arguments", "min", "max"), Set.of("type", "name"));
                toolName(check.get("name"));
                if (check.has("arguments")) Json.require(check.get("arguments").isObject(), "Expected tool argument object");
                long min = check.has("min") ? Json.integer(check.get("min"), 1, 1000) : 1;
                if (check.has("max")) Json.require(Json.integer(check.get("max"), 1, 1000) >= min, "Tool count bounds conflict");
            }
            case "tool_forbidden" -> { exactFields(check, "name"); toolName(check.get("name")); }
            case "tool_sequence" -> {
                exactFields(check, "names"); JsonNode names = check.get("names");
                Json.require(names.isArray() && !names.isEmpty() && names.size() <= 50, "Expected 1–50 tool names");
                names.forEach(Suite::toolName);
            }
            case "max_tool_calls" -> { exactFields(check, "value"); Json.integer(check.get("value"), 0, 1000); }
            case "max_tokens" -> { exactFields(check, "value"); Json.integer(check.get("value"), 0, 1_000_000_000); }
            case "max_latency_ms" -> { exactFields(check, "value"); Json.integer(check.get("value"), 0, 120_000); }
            default -> throw new IllegalArgumentException("Unknown check type");
        }
    }

    static String toolName(JsonNode node) {
        String value = Json.text(node, 128);
        Json.require(value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"), "Invalid tool name"); return value;
    }
    private static void exactFields(JsonNode check, String... names) {
        var fields = new HashSet<>(List.of(names)); fields.add("type"); Json.fields(check, fields, fields);
    }
}
