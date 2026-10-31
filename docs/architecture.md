# Architecture and limits

EvalTrail is a command-line application with one runtime library family: Jackson for strict JSON parsing and serialization. Java's HTTP client handles requests; a bounded pool of virtual threads controls concurrency. Gradle builds the application and JUnit runs its tests.

```text
Suite JSON ──> validation + case fingerprints
                         │
              ┌──────────┴───────────┐
              │                      │
         HTTP runner          Recorded observations
              │                      │
              └─────> Grader <────────┘
                         │
                    Case outcomes
                         │
               JSON / Markdown / JUnit
                         │
              Baseline ↔ candidate comparison
```

## Responsibilities

| Component | Responsibility |
| --- | --- |
| `Main` | CLI options, exit codes, sanitized errors |
| `Json`, `Suite`, `Observation` | Strict input validation, canonical fingerprints |
| `AgentClient` | HTTP policy, credentials, deadline, bounded response body |
| `Runner` | Concurrency, stable case order, offline observation matching |
| `Grader` | Pure deterministic assertions |
| `Results`, `Comparison` | Outcome validation and regression classification |
| `Reports` | New-directory report bundles and JUnit serialization |
| `Demo` | Local scripted HTTP fixture with deliberate regressions |

The CLI validates the suite before issuing requests. Up to the configured number of cases run concurrently; reports retain suite order. Workers are cancelled when execution is interrupted or a worker fails unexpectedly. Normal transport errors are recorded per case, allowing remaining cases to finish.

## Limits

| Resource | Limit |
| --- | --- |
| Suite file | 2 MiB |
| Cases per suite | 1–200 |
| Checks per case | 1–50 |
| Canonical case input | 64 KiB |
| Concurrent requests | 1–8, default 2 |
| Request deadline | 100–120,000 ms, default 10,000 |
| HTTP response body | 1 MiB, including chunked responses |
| Tool calls / citations per observation | 1000 each |
| Individual reported input/output token count | 0–1,000,000,000,000 |
| Recording / input report file | 8 MiB |
| Recorded latency | 0–86,400,000 ms |
| JSON nesting depth | 48 |
| JSON string / number length | 65,536 characters / 128 characters |
| Evidence pointers per check | 10, with total match count retained |
| Each generated report file | 8 MiB |

Files, parsed strings, and observations have separate bounds. A document can be within its byte limit and still fail a field or parser limit. Limits are deliberate to keep evaluations bounded; they are not configurable in version 0.1.0.

## Network and data handling

Remote endpoints require HTTPS. Plain HTTP is accepted only for the exact host names `localhost`, `127.0.0.1`, and IPv6 loopback. URI user information and fragments are rejected. EvalTrail follows no redirects and performs no application retries.

The bearer token is read from a named environment variable and used only for the configured endpoint. EvalTrail does not log request/response bodies or persist credentials. Reports contain IDs, labels, hashes, outcomes, evidence locations, timings, and numeric metrics. A case fingerprint is not encryption; avoid sensitive values in public suites and identifiers. Keep recordings private when they contain user data.

Evaluation requests can cause side effects in the target application. Use a test deployment with isolated data, restricted tools, and its own authorization checks. Cancellation stops the client request; it cannot undo work that the remote agent already performed.

## Filesystem behavior

Output directories must not exist, including symlinks. Each report bundle is serialized in a new temporary sibling directory, then moved into place without replacing an existing destination. Parent directories may be created. A failed demo may leave completed sub-reports; inspect them and choose a fresh output directory for the next run. There is no overwrite flag or automatic deletion of old runs.

## Tests

The test suite includes local HTTP servers for response limits, redirects, authentication, deadlines after headers, and concurrency. CLI tests start separate Java processes and assert real exit statuses. Report tests check round trips, status tampering, missing data, and omission of raw payloads. These tests use fixtures rather than live model providers.

The release workflow packages the same distribution exercised by the Linux job and waits for both Linux and Windows checks. Downloadable checksums allow file-integrity verification; they are not signatures or independent provenance attestations.
