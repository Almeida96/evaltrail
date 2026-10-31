# EvalTrail

[![CI](https://github.com/Almeida96/evaltrail/actions/workflows/ci.yml/badge.svg)](https://github.com/Almeida96/evaltrail/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-437291)](https://openjdk.org/projects/jdk/21/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Contract checks for agent APIs. Catch wrong tool calls, missing citations, broken JSON responses, and regressions before deploying a prompt or model change.

An agent can say “Request refused” after calling a tool it should never have used. EvalTrail checks the reported tool trace alongside the final answer. It runs a JSON test suite against an HTTP adapter, or grades recorded observations without making network requests.

Written in Java 21. No model subscription is needed to run the included demo.

## Try it

Download and extract [evaltrail-0.1.0.zip](https://github.com/Almeida96/evaltrail/releases/download/v0.1.0/evaltrail-0.1.0.zip) from the [release](https://github.com/Almeida96/evaltrail/releases/tag/v0.1.0). Java 21 or later must be on your path.

From the extracted directory:

```sh
bin/evaltrail demo --out results/demo
```

On Windows, use `bin\evaltrail.bat` in place of `bin/evaltrail`.

The demo starts a local HTTP fixture, evaluates two versions, and compares their reports. The responses are scripted; it does not call a model or execute the reported tools.

| Demo version | Passed | Failed | What changed |
| --- | ---: | ---: | --- |
| Baseline | 5 | 0 | Expected answers and tool traces |
| Candidate | 3 | 2 | Wrong citation; forbidden `support.export` call |

Open `results/demo/comparison/comparison.md` to see both regressions. The demo exits successfully when it detects the expected failures. Report folders must be new on each run.

To build from source:

```sh
git clone https://github.com/Almeida96/evaltrail.git
cd evaltrail
./gradlew test installDist
build/install/evaltrail/bin/evaltrail demo --out results/demo
```

On Windows, use `gradlew.bat` and `build\install\evaltrail\bin\evaltrail.bat`. The wrapper downloads Gradle; the first build also downloads Maven dependencies.

## Define a case

Save this as `suite.json`:

```json
{
  "schemaVersion": 1,
  "id": "support-agent",
  "cases": [
    {
      "id": "returns-policy",
      "input": {"query": "How long do I have to return an unused item?"},
      "checks": [
        {"type": "text_contains", "value": "30 days"},
        {"type": "tool_called", "name": "knowledge.search", "arguments": {"query": "returns"}},
        {"type": "citation", "value": "returns-policy"},
        {"type": "tool_forbidden", "name": "support.export"},
        {"type": "max_tokens", "value": 400}
      ]
    }
  ]
}
```

`input` is passed unchanged to your adapter. The [agent contract](docs/agent-contract.md) defines the response envelope. This is a dedicated evaluation endpoint, not a direct replacement for a model provider's API.

```sh
bin/evaltrail run --suite suite.json --endpoint http://localhost:8080/evaluate --out results/baseline --label baseline
```

For a remote endpoint, use HTTPS. Add `--token-env AGENT_EVAL_TOKEN` if the adapter requires a bearer token; set that environment variable in your shell or CI secret store. Tune concurrency with `--parallel 1` through `8`, and the per-request deadline with `--timeout-ms 10000`.

## Grade recordings and compare runs

The included [recorded observations](examples/recorded-observations.json) pass the five-case [support suite](examples/support-suite.json):

```sh
bin/evaltrail grade --suite examples/support-suite.json --observations examples/recorded-observations.json --out results/recorded
bin/evaltrail compare --baseline results/demo/baseline/report.json --candidate results/demo/candidate/report.json --out results/comparison
```

The second command exits with code `2`: the candidate has two regressions. Case fingerprints prevent edits to the input or checks from being counted as recoveries. [Comparison rules](docs/comparisons.md) explain changed cases, missing cases, and persistent failures.

## What gets checked

Ten deterministic checks cover text inclusion/exclusion, exact JSON values, tool names and arguments, forbidden tools, call order, citation IDs, and tool/token/latency limits. See the [check reference](docs/checks.md) for matching rules and examples.

Each run writes:

- `report.json`: case outcomes, check codes, counts, timings, and evidence pointers.
- `report.md`: a readable case summary.
- `junit.xml`: one test per case, with failed assertions separated from unavailable observations.

Reports omit input text, answers, tool arguments, endpoint URLs, and credentials. Keep the original observations privately if you need to inspect a failed evidence pointer. Case IDs and labels are retained, so use descriptive identifiers without personal data.

| Command | `0` | `1` | `2` | `3` |
| --- | --- | --- | --- | --- |
| `run`, `grade` | Every case passed | Invalid configuration/file operation | Failed checks | At least one unavailable or invalid observation |
| `compare` | No new regression or changed case set | Invalid reports/file operation | Regression | Added, removed, or changed cases |
| `demo` | Expected fixture results | Demo could not complete as expected | — | — |

For mixed outcomes, `run`/`grade` prioritize `3`; `compare` prioritizes `2`. A successful comparison can still contain persistent failures. Gate on the candidate run as well when every case must pass.

## Scope

EvalTrail evaluates observations reported by your adapter. It cannot prove that a tool ran, enforce permissions inside an agent, or determine whether a cited document supports an answer. It does not use an LLM judge or score open-ended writing quality. Record actual application events rather than asking the model to invent a trace.

Each case runs once. Repeating a suite in fresh output directories helps investigate variability, but this version does not aggregate repeated trials or estimate statistical significance. Tests can trigger real actions through your endpoint; use isolated test data and a test deployment.

## Development

```sh
./gradlew test distZip
```

Tests exercise the checks, malformed inputs, HTTP deadlines, response size limits, concurrency, report privacy, comparison transitions, and CLI exit codes. CI runs on Linux and Windows with Java 21. Coverage reports are generated under `build/reports/jacoco/test/html`.

- [Agent adapter contract](docs/agent-contract.md)
- [Check reference](docs/checks.md)
- [Comparison rules](docs/comparisons.md)
- [Architecture and limits](docs/architecture.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

The evaluation approach focuses on observable behavior such as tool selection and arguments, as discussed in [OpenAI's evaluation guidance](https://developers.openai.com/api/docs/guides/evaluation-best-practices). EvalTrail is an independent project and does not depend on a hosted evaluation API.

## License

[MIT](LICENSE).
