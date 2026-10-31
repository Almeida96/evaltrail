# Agent adapter contract

An adapter connects EvalTrail to your agent implementation. It accepts a case input, runs the agent, and returns a normalized observation. The evaluator does not need access to the agent's model provider or credentials.

## Request

EvalTrail sends one HTTP `POST` per case:

```http
POST /evaluate HTTP/1.1
Content-Type: application/json
Accept: application/json
```

```json
{
  "schemaVersion": 1,
  "caseId": "returns-policy",
  "input": {"query": "How long do I have to return an unused item?"}
}
```

`input` can be any JSON value. Define its shape in your own suite and adapter. `caseId` correlates the evaluation request with your private trace. Do not use it to choose a canned production answer; the bundled demo does that only to make its fixture repeatable.

With `--token-env AGENT_EVAL_TOKEN`, the request includes `Authorization: Bearer <environment value>`. An unset, blank, oversized, or non-printable token is rejected before requests are sent. Set secrets outside the command line and suite files.

## Response

Return a successful HTTP status with `Content-Type: application/json` (an optional charset parameter is accepted):

```json
{
  "schemaVersion": 1,
  "output": "Unused items can be returned within 30 days.",
  "toolCalls": [
    {"name": "knowledge.search", "arguments": {"query": "returns"}},
    {"name": "knowledge.read", "arguments": {"sourceId": "returns-policy"}}
  ],
  "citations": [{"sourceId": "returns-policy"}],
  "usage": {"inputTokens": 120, "outputTokens": 60}
}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `schemaVersion` | Yes | Integer `1` |
| `output` | Yes | Final answer, including a JSON object/array/value when applicable |
| `toolCalls` | Yes | Ordered array of `{name, arguments}` objects; arguments must be an object |
| `citations` | Yes | Array of `{sourceId}` objects with nonempty source IDs |
| `usage` | No | Both `inputTokens` and `outputTokens`, as nonnegative integers |

Return empty arrays when no tools or citations were used. Do not omit them or replace them with `null`. Omit `usage` when it is unavailable: missing usage fails a `max_tokens` check instead of silently counting as zero. Include usage across the whole agent run, including intermediate model calls. Counts are accepted as reported; EvalTrail does not measure provider billing.

Unknown envelope, tool, citation, or usage fields are rejected. Keep provider-specific metadata in your own trace store. The `output` and `arguments` values can contain arbitrary fields. Duplicate JSON object keys, trailing content, and numeric strings where integers are required are invalid.

## Instrument the application

1. Create an isolated session for each request. Inputs may run concurrently.
2. Start a trace collector at the tool dispatcher, before invoking each tool. Record every dispatched call, including failed attempts, in dispatch order.
3. Run your existing agent loop with the supplied input. Collect final output, citations, and aggregate model usage.
4. Return the envelope above. Keep full traces in your own controlled storage if needed for debugging.

`toolCalls` has no success field in version 1; it records attempts, not successful side effects. A forbidden call should be reported even when the tool throws or a policy layer blocks it. For parallel tool calls, use dispatch order consistently. Sequence checks assert that order, not completion order or causality.

Do not ask the model to summarize its own tool calls as the source of truth. The checks are only as reliable as the adapter's instrumentation. Configure authorization and action restrictions in the agent itself; an evaluation is not a runtime permission boundary.

## Failures and timing

| Condition | Case error |
| --- | --- |
| Deadline exceeded, including a stalled response body | `TIMEOUT` |
| Completed response with a non-2xx status | `HTTP_STATUS` |
| Body exceeds 1 MiB | `RESPONSE_TOO_LARGE` |
| Wrong content type, invalid JSON, or invalid envelope | `INVALID_RESPONSE` |
| Connection, TLS, or other transport failure | `TRANSPORT_ERROR` |

Errors do not run the checks and never count as passes. A body deadline or size failure can take precedence over a non-2xx status. Raw error bodies are not saved or printed. Redirects are treated as HTTP errors; they are never followed. There are no automatic retries, so each case sends at most one application request.

The default deadline is 10 seconds, configurable from 100 to 120,000 milliseconds. Latency measures the request and response parsing/check dispatch boundary, excluding time waiting for a worker. `max_latency_ms` checks this wall-clock measurement. Offline grading uses the latency supplied in the recording.

## Record observations instead

Use `grade` when your runner already captures traces, or when you want to evaluate the same observations repeatedly without invoking the agent again:

```json
{
  "schemaVersion": 1,
  "observations": [
    {
      "caseId": "returns-policy",
      "latencyMs": 340,
      "response": {
        "schemaVersion": 1,
        "output": "Unused items can be returned within 30 days.",
        "toolCalls": [{"name": "knowledge.search", "arguments": {"query": "returns"}}],
        "citations": [{"sourceId": "returns-policy"}],
        "usage": {"inputTokens": 120, "outputTokens": 60}
      }
    }
  ]
}
```

Missing suite cases produce `MISSING_OBSERVATION` errors. An invalid response produces an `INVALID_RESPONSE` case error. Duplicate or unknown case IDs, invalid collection fields, and invalid latency values reject the whole file. Recordings contain raw answers and arguments; treat them according to your application's data policy. EvalTrail's generated reports omit those values.

See the [complete example](../examples/recorded-observations.json) and [resource limits](architecture.md#limits).
