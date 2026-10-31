package io.github.almeida96.evaltrail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import static io.github.almeida96.evaltrail.Fixtures.*;
import static io.github.almeida96.evaltrail.Results.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportsTest {
    @TempDir Path dir;
    private Suite testSuite() throws Exception { return suite("{\"type\":\"text_contains\",\"value\":\"world\"}"); }

    @Test void bundlesRoundTripAndJUnitSeparatesAssertionFailuresFromErrors() throws Exception {
        Suite suite = Demo.suite();
        var results = suite.cases().stream().map(test -> Grader.grade(test, Observation.parse(Demo.response(test.id(), true)), 25)).toList();
        var cases = new java.util.ArrayList<>(results);
        cases.set(4, CaseResult.error(suite.cases().get(4), 5000, ErrorCode.TIMEOUT, null));
        Report report = report(suite, cases.toArray(CaseResult[]::new));
        Path out = dir.resolve("report"); Reports.write(out, report); assertEquals(report, Reports.read(out.resolve("report.json")));
        var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var root = factory.newDocumentBuilder().parse(out.resolve("junit.xml").toFile()).getDocumentElement();
        assertEquals("5", root.getAttribute("tests")); assertEquals("2", root.getAttribute("failures")); assertEquals("1", root.getAttribute("errors"));
        assertEquals(2, root.getElementsByTagName("failure").getLength()); assertEquals(1, root.getElementsByTagName("error").getLength());
        assertTrue(Files.readString(out.resolve("report.md")).contains("2 passed, 2 failed, 1 errors"));
        assertEquals(3, report.exitCode());
    }
    @Test void existingOutputIsNeverOverwritten() throws Exception {
        Suite suite = testSuite(); var report = report(suite, Grader.grade(suite.cases().getFirst(), Observation.parse(response()), 1));
        Path out = dir.resolve("existing"); Files.createDirectory(out); Files.writeString(out.resolve("keep.txt"), "untouched");
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> Reports.write(out, report));
        assertEquals("untouched", Files.readString(out.resolve("keep.txt"))); assertFalse(Files.exists(out.resolve("report.json")));
    }
    @Test void reportsDoNotRetainInputsAnswersArgumentsOrExpectedText() throws Exception {
        var suite = suite("{\"type\":\"text_contains\",\"value\":\"PRIVATE_EXPECTATION\"}");
        var response = response().put("output", "PRIVATE_ANSWER");
        response.withArray("toolCalls").addObject().put("name", "search").putObject("arguments").put("token", "PRIVATE_ARGUMENT");
        var report = report(suite, Grader.grade(suite.cases().getFirst(), Observation.parse(response), 10));
        Reports.write(dir.resolve("private"), report);
        for (String name : new String[]{"report.json", "report.md", "junit.xml"}) {
            String contents = Files.readString(dir.resolve("private").resolve(name));
            assertFalse(contents.contains("PRIVATE_")); assertFalse(contents.contains("hello"));
        }
    }
    @Test void corruptedStatusesAndMissingPrimitiveFieldsAreRejected() throws Exception {
        Suite suite = testSuite(); var failed = Grader.grade(suite.cases().getFirst(), Observation.parse(response().put("output", "wrong")), 10);
        var tree = Json.MAPPER.valueToTree(report(suite, failed));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("cases").get(0)).put("status", "PASS");
        Path file = dir.resolve("corrupt.json"); Files.write(file, Json.MAPPER.writeValueAsBytes(tree));
        assertThrows(IllegalArgumentException.class, () -> Reports.read(file));
        tree = Json.MAPPER.valueToTree(report(suite, failed));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("cases").get(0)).remove("latencyMs");
        Files.write(file, Json.MAPPER.writeValueAsBytes(tree));
        assertThrows(java.io.IOException.class, () -> Reports.read(file));
    }
    @Test void comparisonFindsRegressionsRecoveriesAndPersistentFailures() throws Exception {
        Suite suite = testSuite(); var test = suite.cases().getFirst();
        var pass = Grader.grade(test, Observation.parse(response()), 10);
        var fail = Grader.grade(test, Observation.parse(response().put("output", "wrong")), 10);
        var error = CaseResult.error(test, 50, ErrorCode.TIMEOUT, null);
        assertEquals(Comparison.Kind.REGRESSION, Comparison.compare(report(suite, pass), report(suite, fail)).changes().getFirst().kind());
        assertEquals(2, Comparison.compare(report(suite, fail), report(suite, error)).exitCode());
        assertEquals(Comparison.Kind.RECOVERED, Comparison.compare(report(suite, error), report(suite, pass)).changes().getFirst().kind());
        assertEquals(Comparison.Kind.PERSISTENT_FAILURE, Comparison.compare(report(suite, error), report(suite, fail)).changes().getFirst().kind());
        assertEquals(0, Comparison.compare(report(suite, fail), report(suite, fail)).exitCode());
    }
    @Test void changingOrRemovingAFailingCaseCannotBecomeARecovery() throws Exception {
        Suite oldSuite = testSuite(); var old = oldSuite.cases().getFirst();
        var failed = Grader.grade(old, Observation.parse(response().put("output", "wrong")), 1);
        Suite newSuite = suite("{\"type\":\"text_contains\",\"value\":\"wrong\"}");
        var passed = Grader.grade(newSuite.cases().getFirst(), Observation.parse(response().put("output", "wrong")), 1);
        var changed = Comparison.compare(report(oldSuite, failed), report(newSuite, passed));
        assertEquals(Comparison.Kind.CHANGED_CASE, changed.changes().getFirst().kind()); assertEquals(3, changed.exitCode());
        var renamed = new CaseResult("renamed", passed.digest(), passed.status(), passed.latencyMs(), passed.metrics(), passed.checks(), passed.error());
        var removed = Comparison.compare(report(oldSuite, failed), report(newSuite, renamed));
        assertEquals(1, removed.counts().get(Comparison.Kind.REMOVED)); assertEquals(1, removed.counts().get(Comparison.Kind.ADDED));
        assertEquals(0, removed.counts().get(Comparison.Kind.RECOVERED)); assertEquals(3, removed.exitCode());
    }
    @Test void offlineGradingKeepsMissingObservationsAsErrorsAndRejectsDuplicates() throws Exception {
        Suite suite = testSuite(); Path file = dir.resolve("observations.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"observations\":[]}");
        Report report = Runner.grade(suite, file, "recorded");
        assertEquals(3, report.exitCode()); assertEquals(ErrorCode.MISSING_OBSERVATION, report.cases().getFirst().error().code());
        var root = Json.MAPPER.createObjectNode().put("schemaVersion", 1); var rows = root.putArray("observations");
        var row = rows.addObject().put("caseId", "case").put("latencyMs", 5); row.set("response", response()); rows.add(row);
        Files.write(file, Json.MAPPER.writeValueAsBytes(root)); assertThrows(IllegalArgumentException.class, () -> Runner.grade(suite, file, "recorded"));
        rows.remove(1); row.put("caseId", "unknown");
        Files.write(file, Json.MAPPER.writeValueAsBytes(root)); assertThrows(IllegalArgumentException.class, () -> Runner.grade(suite, file, "recorded"));
    }
    @Test void offlineMalformedResponseIsAnErrorInsteadOfAnAssertionFailure() throws Exception {
        Path file = dir.resolve("observations.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"observations\":[{\"caseId\":\"case\",\"latencyMs\":5,\"response\":{}}]}");
        var result = Runner.grade(testSuite(), file, "recorded");
        assertEquals(ErrorCode.INVALID_RESPONSE, result.cases().getFirst().error().code()); assertEquals(3, result.exitCode());
    }
}
