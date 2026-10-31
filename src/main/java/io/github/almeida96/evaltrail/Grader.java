package io.github.almeida96.evaltrail;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import static io.github.almeida96.evaltrail.Results.*;

final class Grader {
    private Grader() {}

    static CaseResult grade(Suite.Case test, Observation observation, long latencyMs) {
        var checks = new ArrayList<Check>();
        for (int index = 0; index < test.checks().size(); index++) checks.add(check(index, test.checks().get(index), observation, latencyMs));
        Status status = checks.stream().allMatch(Check::passed) ? Status.PASS : Status.FAIL;
        return new CaseResult(test.id(), test.digest(), status, latencyMs,
            new Metrics(observation.toolCalls().size(), observation.citations().size(), observation.tokens()), List.copyOf(checks), null);
    }

    private static Check check(int index, JsonNode rule, Observation observation, long latency) {
        String type = rule.get("type").textValue(); boolean passed; int matches = 0;
        var evidence = new ArrayList<String>(); CheckCode special = null;
        switch (type) {
            case "text_contains", "text_excludes" -> {
                boolean text = observation.output().isTextual();
                boolean contains = text && observation.output().textValue().contains(rule.get("value").textValue());
                passed = text && (type.equals("text_contains") == contains);
                if (!text) special = CheckCode.WRONG_OUTPUT_TYPE;
                evidence.add("/output");
            }
            case "json_equals" -> {
                String path = rule.get("path").textValue();
                passed = same(observation.output().at(path), rule.get("value")); evidence.add("/output" + path);
            }
            case "tool_called", "tool_forbidden" -> {
                for (int position = 0; position < observation.toolCalls().size(); position++) {
                    JsonNode tool = observation.toolCalls().get(position);
                    if (tool.get("name").equals(rule.get("name")) && (!rule.has("arguments") || subset(tool.get("arguments"), rule.get("arguments")))) {
                        matches++; addEvidence(evidence, "/toolCalls/" + position);
                    }
                }
                int min = rule.path("min").asInt(1), max = rule.path("max").asInt(1000);
                passed = type.equals("tool_forbidden") ? matches == 0 : matches >= min && matches <= max;
            }
            case "tool_sequence" -> {
                JsonNode names = rule.get("names"); int next = 0;
                for (int position = 0; position < observation.toolCalls().size() && next < names.size(); position++) {
                    if (observation.toolCalls().get(position).get("name").equals(names.get(next))) {
                        next++; addEvidence(evidence, "/toolCalls/" + position);
                    }
                }
                matches = next; passed = next == names.size();
            }
            case "citation" -> {
                for (int position = 0; position < observation.citations().size(); position++) {
                    if (observation.citations().get(position).get("sourceId").equals(rule.get("value"))) {
                        matches++; addEvidence(evidence, "/citations/" + position);
                    }
                }
                passed = matches > 0;
            }
            case "max_tool_calls" -> { passed = observation.toolCalls().size() <= rule.get("value").longValue(); evidence.add("/toolCalls"); }
            case "max_tokens" -> {
                passed = observation.tokens() != null && observation.tokens() <= rule.get("value").longValue();
                if (observation.tokens() == null) special = CheckCode.USAGE_MISSING;
                evidence.add("/usage");
            }
            case "max_latency_ms" -> { passed = latency <= rule.get("value").longValue(); evidence.add("/latencyMs"); }
            default -> throw new IllegalStateException("Unvalidated check type");
        }
        return new Check(index, type, passed, special != null ? special : passed ? CheckCode.MATCH : CheckCode.MISMATCH, matches, List.copyOf(evidence));
    }

    private static void addEvidence(List<String> evidence, String pointer) { if (evidence.size() < 10) evidence.add(pointer); }
    static boolean same(JsonNode actual, JsonNode expected) {
        if (actual.isMissingNode()) return false;
        if (actual.isNumber() && expected.isNumber()) return Json.canonical(actual).equals(Json.canonical(expected));
        if (actual.isObject() && expected.isObject()) {
            if (actual.size() != expected.size()) return false;
            for (var entry : expected.properties()) if (!actual.has(entry.getKey()) || !same(actual.get(entry.getKey()), entry.getValue())) return false;
            return true;
        }
        if (actual.isArray() && expected.isArray()) {
            if (actual.size() != expected.size()) return false;
            for (int index = 0; index < actual.size(); index++) if (!same(actual.get(index), expected.get(index))) return false;
            return true;
        }
        return actual.equals(expected);
    }
    private static boolean subset(JsonNode actual, JsonNode expected) {
        if (!expected.isObject()) return same(actual, expected);
        if (!actual.isObject()) return false;
        for (var entry : expected.properties()) {
            if (!actual.has(entry.getKey()) || !subset(actual.get(entry.getKey()), entry.getValue())) return false;
        }
        return true;
    }
}
