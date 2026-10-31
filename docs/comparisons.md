# Comparing agent versions

Run the same suite against a baseline and a candidate, using different output directories and labels:

```sh
bin/evaltrail run --suite suite.json --endpoint https://baseline.example.com/evaluate --out results/baseline --label baseline
bin/evaltrail run --suite suite.json --endpoint https://candidate.example.com/evaluate --out results/candidate --label candidate
bin/evaltrail compare --baseline results/baseline/report.json --candidate results/candidate/report.json --out results/comparison
```

Use your own endpoints. A run with failures still writes reports. If a shell or CI step stops on nonzero exits, preserve the run's status while arranging for report collection and comparison to execute afterward.

## Case identity

Both reports must use the same suite ID and a supported report/grader version. Cases are paired by ID. A SHA-256 fingerprint covers each case's ID, input, and ordered checks. Object key order and equivalent numeric representations do not change the fingerprint; array order does.

Changing a prompt, expected value, or check makes the case `CHANGED_CASE`. Removing a failing case does not turn it into a recovery. Suite reordering alone does not prevent comparisons: the case fingerprints remain comparable.

Reports validate their structure and internal status consistency before comparison. Fingerprints identify definitions; they are not signatures, and they do not make edited reports trustworthy. Keep report files as artifacts from a trusted build.

## Outcomes

| Before | After | Classification |
| --- | --- | --- |
| `PASS` | `FAIL` or `ERROR` | `REGRESSION` |
| `FAIL` | `ERROR` | `REGRESSION` |
| `FAIL` or `ERROR` | `PASS` | `RECOVERED` |
| Any non-pass | Any non-pass, except `FAIL` → `ERROR` | `PERSISTENT_FAILURE` |
| `PASS` | `PASS` | `UNCHANGED` |
| Missing | Present | `ADDED` |
| Present | Missing | `REMOVED` |
| Same ID, different fingerprint | Any status | `CHANGED_CASE` |

Case-set and fingerprint classifications take precedence over status transitions. An error becoming an ordinary failed assertion remains a persistent failure, since the case still does not pass.

Comparison writes `comparison.json` and `comparison.md`. JSON includes case counts and pointers to the corresponding case entries in each input report.

## Exit codes

- `2`: at least one regression, including when other cases changed or disappeared.
- `3`: no regression, but at least one case was added, removed, or changed.
- `0`: neither condition applies. Persistent failures may still exist.
- `1`: invalid inputs or a file operation failed.

To require a fully passing candidate, gate on `run`/`grade` as well as `compare`. When changing the suite intentionally, review its changed cases before establishing a new baseline.

## Variability

A report contains one observation per case. Model output, retrieval state, latency, and external tools can vary. A single detected regression is a concrete failing observation, not a statistical estimate of overall model quality. Repeat relevant cases in fresh runs and inspect the agent trace before drawing broader conclusions. Repeated-trial aggregation is outside version 0.1.0.
