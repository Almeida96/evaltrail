package io.github.almeida96.evaltrail;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

final class Results {
    static final String GRADER_VERSION = "1";
    private Results() {}
    enum Status { PASS, FAIL, ERROR }
    enum ErrorCode { TIMEOUT, HTTP_STATUS, RESPONSE_TOO_LARGE, INVALID_RESPONSE, TRANSPORT_ERROR, MISSING_OBSERVATION }
    enum CheckCode { MATCH, MISMATCH, WRONG_OUTPUT_TYPE, USAGE_MISSING }
    record Check(int index, String type, boolean passed, CheckCode code, int matches, List<String> evidence) {}
    record Failure(ErrorCode code, Integer httpStatus) {}
    record Metrics(int toolCalls, int citations, Long totalTokens) {}
    record CaseResult(String id, String digest, Status status, long latencyMs, Metrics metrics, List<Check> checks, Failure error) {
        static CaseResult error(Suite.Case test, long latency, ErrorCode code, Integer status) {
            return new CaseResult(test.id(), test.digest(), Status.ERROR, latency, null, List.of(), new Failure(code, status));
        }
    }
    record Report(int schemaVersion, String graderVersion, String suiteId, String suiteDigest, String label, String createdAt,
                  int timeoutMs, int parallelism, List<CaseResult> cases) {
        int count(Status status) { return (int) cases.stream().filter(test -> test.status() == status).count(); }
        int exitCode() { return count(Status.ERROR) > 0 ? 3 : count(Status.FAIL) > 0 ? 2 : 0; }
        void validate() {
            Json.require(schemaVersion == 1 && GRADER_VERSION.equals(graderVersion), "Unsupported report version");
            validId(suiteId); validId(label); validDigest(suiteDigest);
            Json.require(createdAt != null, "Report timestamp missing"); Instant.parse(createdAt);
            Json.require(timeoutMs >= 0 && timeoutMs <= 120_000 && parallelism >= 1 && parallelism <= 8, "Invalid report execution settings");
            Json.require(cases != null && !cases.isEmpty() && cases.size() <= 200, "Invalid report case count");
            var ids = new HashSet<String>();
            for (CaseResult test : cases) {
                Json.require(test != null && ids.add(test.id()), "Duplicate or null report case");
                validId(test.id()); validDigest(test.digest());
                Json.require(test.status() != null && test.latencyMs() >= 0 && test.latencyMs() <= 86_400_000, "Invalid report outcome");
                Json.require(test.checks() != null && test.checks().size() <= 50, "Invalid report checks");
                if (test.status() == Status.ERROR) {
                    Json.require(test.error() != null && test.error().code() != null && test.checks().isEmpty() && test.metrics() == null, "Invalid error result");
                    Integer http = test.error().httpStatus();
                    Json.require(test.error().code() == ErrorCode.HTTP_STATUS ? http != null && http >= 100 && http <= 599 && (http < 200 || http >= 300) : http == null, "Invalid HTTP error status");
                } else {
                    Json.require(test.error() == null && !test.checks().isEmpty(), "Invalid graded result");
                    Metrics metrics = test.metrics();
                    Json.require(metrics != null && metrics.toolCalls() >= 0 && metrics.toolCalls() <= 1000 && metrics.citations() >= 0 && metrics.citations() <= 1000, "Invalid report metrics");
                    Json.require(metrics.totalTokens() == null || (metrics.totalTokens() >= 0 && metrics.totalTokens() <= 2_000_000_000_000L), "Invalid token metrics");
                    boolean allPassed = true;
                    for (int index = 0; index < test.checks().size(); index++) {
                        Check check = test.checks().get(index);
                        Json.require(check != null && check.index() == index && Suite.CHECK_TYPES.contains(check.type()), "Invalid check metadata");
                        Json.require(check.code() != null && check.passed() == (check.code() == CheckCode.MATCH), "Inconsistent check status");
                        Json.require(check.matches() >= 0 && check.matches() <= 1000 && check.evidence() != null && check.evidence().size() <= 10, "Invalid check evidence");
                        for (String location : check.evidence()) Json.require(location != null && location.length() <= 1100 && location.startsWith("/"), "Invalid evidence pointer");
                        allPassed &= check.passed();
                    }
                    Json.require((test.status() == Status.PASS) == allPassed, "Report status contradicts its checks");
                }
            }
        }
    }
    static void validId(String value) { Json.require(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"), "Invalid report identifier"); }
    static void validDigest(String value) { Json.require(value != null && value.matches("[0-9a-f]{64}"), "Invalid report digest"); }
}
