package io.github.almeida96.evaltrail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import static io.github.almeida96.evaltrail.Results.*;

record Comparison(int schemaVersion, String suiteId, String baselineLabel, String candidateLabel,
                  Map<Kind, Integer> counts, List<Change> changes) {
    enum Kind { REGRESSION, RECOVERED, PERSISTENT_FAILURE, UNCHANGED, ADDED, REMOVED, CHANGED_CASE }
    record Change(String caseId, Kind kind, Status before, Status after, String baselineEvidence, String candidateEvidence) {}

    static Comparison compare(Report baseline, Report candidate) {
        baseline.validate(); candidate.validate();
        Json.require(baseline.suiteId().equals(candidate.suiteId()), "Reports belong to different suites");
        var before = baseline.cases().stream().collect(Collectors.toMap(CaseResult::id, Function.identity()));
        var after = candidate.cases().stream().collect(Collectors.toMap(CaseResult::id, Function.identity()));
        var ids = new TreeSet<>(before.keySet()); ids.addAll(after.keySet());
        var counts = new LinkedHashMap<Kind, Integer>(); for (Kind kind : Kind.values()) counts.put(kind, 0);
        var changes = new ArrayList<Change>();
        for (String id : ids) {
            CaseResult a = before.get(id), b = after.get(id); Kind kind;
            if (a == null) kind = Kind.ADDED;
            else if (b == null) kind = Kind.REMOVED;
            else if (!a.digest().equals(b.digest())) kind = Kind.CHANGED_CASE;
            else if ((a.status() == Status.PASS && b.status() != Status.PASS) || (a.status() == Status.FAIL && b.status() == Status.ERROR)) kind = Kind.REGRESSION;
            else if (a.status() != Status.PASS && b.status() == Status.PASS) kind = Kind.RECOVERED;
            else if (b.status() != Status.PASS) kind = Kind.PERSISTENT_FAILURE;
            else kind = Kind.UNCHANGED;
            counts.put(kind, counts.get(kind) + 1);
            changes.add(new Change(id, kind, a == null ? null : a.status(), b == null ? null : b.status(),
                a == null ? null : "/cases/" + baseline.cases().indexOf(a), b == null ? null : "/cases/" + candidate.cases().indexOf(b)));
        }
        return new Comparison(1, baseline.suiteId(), baseline.label(), candidate.label(), counts, List.copyOf(changes));
    }

    int exitCode() {
        if (counts.get(Kind.REGRESSION) > 0) return 2;
        if (counts.get(Kind.ADDED) + counts.get(Kind.REMOVED) + counts.get(Kind.CHANGED_CASE) > 0) return 3;
        return 0;
    }
}
