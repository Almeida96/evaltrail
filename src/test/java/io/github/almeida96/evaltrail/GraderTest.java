package io.github.almeida96.evaltrail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.almeida96.evaltrail.Fixtures.*;
import static io.github.almeida96.evaltrail.Results.*;

class GraderTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"text_contains\",\"value\":\"world\"}",
        "{\"type\":\"text_excludes\",\"value\":\"private\"}",
        "{\"type\":\"json_equals\",\"path\":\"\",\"value\":\"Hello world\"}",
        "{\"type\":\"tool_forbidden\",\"name\":\"send_email\"}",
        "{\"type\":\"max_tool_calls\",\"value\":0}",
        "{\"type\":\"max_tokens\",\"value\":150}",
        "{\"type\":\"max_latency_ms\",\"value\":20}"
    })
    void gradesExactBoundaries(String check) throws Exception { assertEquals(Status.PASS, grade(check, response()).status()); }

    @Test void textExclusionCannotPassWithNonTextOutput() throws Exception {
        var response = response(); response.putObject("output");
        var result = grade("{\"type\":\"text_excludes\",\"value\":\"secret\"}", response);
        assertEquals(Status.FAIL, result.status()); assertEquals(CheckCode.WRONG_OUTPUT_TYPE, result.checks().getFirst().code());
    }
    @Test void jsonEqualityIsDeepExactAndNumericButDoesNotCoerceStrings() throws Exception {
        var response = response(); response.set("output", node("{\"nested\":{\"value\":1.0,\"extra\":true}}"));
        assertEquals(Status.FAIL, grade("{\"type\":\"json_equals\",\"path\":\"\",\"value\":{\"nested\":{\"value\":1}}}", response).status());
        assertEquals(Status.PASS, grade("{\"type\":\"json_equals\",\"path\":\"/nested/value\",\"value\":1}", response).status());
        assertEquals(Status.FAIL, grade("{\"type\":\"json_equals\",\"path\":\"/nested/value\",\"value\":\"1\"}", response).status());
        assertEquals(Status.FAIL, grade("{\"type\":\"json_equals\",\"path\":\"/missing\",\"value\":null}", response).status());
    }
    @Test void jsonPointerEscapesResolveLiteralKeys() throws Exception {
        var response = response(); response.set("output", node("{\"a/b\":{\"~key\":null}}"));
        assertEquals(Status.PASS, grade("{\"type\":\"json_equals\",\"path\":\"/a~1b/~0key\",\"value\":null}", response).status());
    }
    @Test void toolArgumentsUseRecursiveObjectSubsetsAndExactArrays() throws Exception {
        var response = response(); response.set("toolCalls", node("[{\"name\":\"search\",\"arguments\":{\"filter\":{\"scope\":\"public\",\"limit\":10},\"tags\":[\"a\",\"b\"]}}]"));
        assertEquals(Status.PASS, grade("{\"type\":\"tool_called\",\"name\":\"search\",\"arguments\":{\"filter\":{\"scope\":\"public\"}}}", response).status());
        assertEquals(Status.FAIL, grade("{\"type\":\"tool_called\",\"name\":\"search\",\"arguments\":{\"tags\":[\"a\"]}}", response).status());
        assertEquals(Status.FAIL, grade("{\"type\":\"tool_forbidden\",\"name\":\"search\"}", response).status());
    }
    @Test void callCountChecksCannotHideAdditionalMatchingInvocations() throws Exception {
        var response = response(); var tools = response.withArray("toolCalls");
        for (int index = 0; index < 30; index++) tools.addObject().put("name", "search").putObject("arguments");
        var result = grade("{\"type\":\"tool_called\",\"name\":\"search\",\"min\":1,\"max\":2}", response);
        assertEquals(Status.FAIL, result.status()); assertEquals(30, result.checks().getFirst().matches());
        assertEquals(10, result.checks().getFirst().evidence().size());
    }
    @Test void sequenceMatchesAnOrderedSubsequenceAndKeepsRepeatedNamesDistinct() throws Exception {
        var response = response(); var tools = response.withArray("toolCalls");
        for (String name : new String[]{"search", "log", "read", "search"}) tools.addObject().put("name", name).putObject("arguments");
        assertEquals(Status.PASS, grade("{\"type\":\"tool_sequence\",\"names\":[\"search\",\"read\",\"search\"]}", response).status());
        assertEquals(Status.FAIL, grade("{\"type\":\"tool_sequence\",\"names\":[\"read\",\"search\",\"read\"]}", response).status());
    }
    @Test void citationsRequireAnExactReportedSourceId() throws Exception {
        var response = response(); response.withArray("citations").addObject().put("sourceId", "policy-v2");
        var result = grade("{\"type\":\"citation\",\"value\":\"policy-v2\"}", response);
        assertEquals(Status.PASS, result.status()); assertEquals("/citations/0", result.checks().getFirst().evidence().getFirst());
        assertEquals(Status.FAIL, grade("{\"type\":\"citation\",\"value\":\"policy\"}", response).status());
    }
    @Test void missingUsageFailsTheBudgetInsteadOfAssumingZero() throws Exception {
        var response = response(); response.remove("usage");
        var result = grade("{\"type\":\"max_tokens\",\"value\":200}", response);
        assertEquals(CheckCode.USAGE_MISSING, result.checks().getFirst().code()); assertEquals(Status.FAIL, result.status());
        assertNull(result.metrics().totalTokens());
    }
    @Test void fingerprintsIgnoreObjectKeyOrderAndNumberFormattingButKeepArrayOrder() throws Exception {
        assertEquals(Json.digest(node("{\"a\":1,\"b\":[2,3]}")), Json.digest(node("{\"b\":[2.0,3],\"a\":1.0}")));
        assertNotEquals(Json.digest(node("[2,3]")), Json.digest(node("[3,2]")));
        assertTrue(Json.canonical(node("1e1000000")).length() < 20);
    }
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"unknown\"}", "{\"type\":\"text_contains\",\"value\":\"ok\",\"typo\":true}",
        "{\"type\":\"max_tokens\",\"value\":-1}", "{\"type\":\"max_tokens\",\"value\":2.5}",
        "{\"type\":\"max_tokens\",\"value\":\"10\"}", "{\"type\":\"json_equals\",\"path\":\"/a~2\",\"value\":null}",
        "{\"type\":\"tool_called\",\"name\":\"search\",\"min\":3,\"max\":1}", "{\"type\":\"tool_sequence\",\"names\":[]}"
    })
    void rejectsInvalidCheckDefinitions(String check) { assertThrows(IllegalArgumentException.class, () -> suite(check)); }

    @Test void parserRejectsDuplicatesTrailingValuesAndExcessiveDepth() {
        assertThrows(Exception.class, () -> node("{\"a\":1,\"a\":2}"));
        assertThrows(Exception.class, () -> node("{} {}"));
        assertThrows(Exception.class, () -> node("[".repeat(60) + "0" + "]".repeat(60)));
    }
    @Test void suiteRejectsDuplicateIdsEmptyChecksAndUnknownFields() throws Exception {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) node("{\"schemaVersion\":1,\"id\":\"suite\",\"cases\":[]}");
        assertThrows(IllegalArgumentException.class, () -> Suite.parse(root));
        var valid = suite("{\"type\":\"max_tokens\",\"value\":200}");
        var item = Json.MAPPER.createObjectNode().put("id", "case"); item.set("input", valid.cases().getFirst().input());
        item.set("checks", Json.MAPPER.valueToTree(valid.cases().getFirst().checks()));
        root.withArray("cases").add(item).add(item);
        assertThrows(IllegalArgumentException.class, () -> Suite.parse(root));
        root.withArray("cases").remove(1); root.put("unknown", true);
        assertThrows(IllegalArgumentException.class, () -> Suite.parse(root));
    }
    @Test void malformedObservationsAreNotAcceptedAsEmptyTraces() {
        var response = response(); response.remove("toolCalls");
        assertThrows(IllegalArgumentException.class, () -> Observation.parse(response));
        var invalidUsage = response(); invalidUsage.withObject("/usage").put("inputTokens", "100");
        assertThrows(IllegalArgumentException.class, () -> Observation.parse(invalidUsage));
    }
}
