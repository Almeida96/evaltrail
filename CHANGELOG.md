# Changelog

## 0.1.0 — 2026-10-31

- Run JSON suites against agent HTTP adapters, with bounded concurrency, request deadlines, and response size limits.
- Grade recorded observations without invoking an agent.
- Check text, JSON output, tool arguments and order, forbidden calls, citation IDs, and resource budgets.
- Compare baseline and candidate reports using case fingerprints; distinguish regressions, recoveries, persistent failures, and changed case sets.
- Export JSON, Markdown, and JUnit XML while omitting raw inputs, answers, tool arguments, credentials, and endpoint URLs.
- Include a five-case local HTTP demo, recorded examples, and Java 21 tests on Linux and Windows.
