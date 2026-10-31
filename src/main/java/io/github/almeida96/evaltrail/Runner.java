package io.github.almeida96.evaltrail;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static io.github.almeida96.evaltrail.Results.*;

final class Runner {
    private Runner() {}

    static Report run(Suite suite, AgentClient client, String label, int timeoutMs, int parallelism) throws InterruptedException, IOException {
        validId(label); Json.require(parallelism >= 1 && parallelism <= 8, "Parallelism must be 1–8");
        String at = Instant.now().toString();
        var executor = Executors.newFixedThreadPool(parallelism, Thread.ofVirtual().name("evaltrail-case-", 0).factory());
        var futures = new ArrayList<Future<CaseResult>>();
        try {
            for (Suite.Case test : suite.cases()) futures.add(executor.submit(() -> client.evaluate(test)));
            var results = new ArrayList<CaseResult>();
            for (Future<CaseResult> future : futures) results.add(future.get());
            return new Report(1, GRADER_VERSION, suite.id(), suite.digest(), label, at, timeoutMs, parallelism, List.copyOf(results));
        } catch (ExecutionException exception) { throw new IOException("Evaluation worker failed", exception); }
        finally { futures.forEach(future -> future.cancel(true)); executor.shutdownNow(); }
    }

    static Report grade(Suite suite, Path observations, String label) throws IOException {
        validId(label); JsonNode root = Json.read(observations, 8 * 1024 * 1024);
        Json.fields(root, Set.of("schemaVersion", "observations"), Set.of("schemaVersion", "observations"));
        Json.integer(root.get("schemaVersion"), 1, 1);
        JsonNode rows = root.get("observations");
        Json.require(rows.isArray() && rows.size() <= 200, "Expected at most 200 observations");
        var byId = new HashMap<String, JsonNode>();
        var knownIds = suite.cases().stream().map(Suite.Case::id).collect(java.util.stream.Collectors.toSet());
        for (JsonNode row : rows) {
            Json.fields(row, Set.of("caseId", "response", "latencyMs"), Set.of("caseId", "response", "latencyMs"));
            String id = Json.id(row.get("caseId"));
            Json.require(knownIds.contains(id), "Observation references an unknown case");
            Json.require(byId.put(id, row) == null, "Duplicate observation case ID");
            Json.integer(row.get("latencyMs"), 0, 86_400_000);
        }
        var results = new ArrayList<CaseResult>();
        for (Suite.Case test : suite.cases()) {
            JsonNode row = byId.get(test.id());
            if (row == null) { results.add(CaseResult.error(test, 0, ErrorCode.MISSING_OBSERVATION, null)); continue; }
            long latency = row.get("latencyMs").longValue();
            try { results.add(Grader.grade(test, Observation.parse(row.get("response")), latency)); }
            catch (IllegalArgumentException exception) { results.add(CaseResult.error(test, latency, ErrorCode.INVALID_RESPONSE, null)); }
        }
        return new Report(1, GRADER_VERSION, suite.id(), suite.digest(), label, Instant.now().toString(), 0, 1, List.copyOf(results));
    }
}
