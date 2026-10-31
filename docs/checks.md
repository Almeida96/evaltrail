# Check reference

A suite contains `schemaVersion: 1`, an `id`, and 1–200 `cases`. Each case requires `id`, `input`, and 1–50 `checks`. Suite IDs, case IDs, and command labels use 1–80 characters: letters, digits, dots, underscores, or hyphens, starting with a letter or digit. Duplicate IDs and unknown configuration fields are rejected.

A case passes only when every check passes. An unavailable or invalid observation is an error, not a failed assertion. All checks are deterministic for the same observation and latency.

## Text

```json
{"type": "text_contains", "value": "30 days"}
{"type": "text_excludes", "value": "PRIVATE_CUSTOMER_RECORD"}
```

Match a nonempty, case-sensitive substring of a string `output`. Both checks fail with `WRONG_OUTPUT_TYPE` if the output is an object, array, number, boolean, or null. No tokenization, case folding, fuzzy matching, or semantic similarity is applied. An exclusion check only detects the supplied text; it is not a general data-leak detector.

## JSON equality

```json
{"type": "json_equals", "path": "/refund/amount", "value": 42}
{"type": "json_equals", "path": "/submitted", "value": false}
```

`path` is a JSON Pointer into `output`, not the whole observation. An empty string selects the output root. Escape `/` as `~1` and `~` as `~0` in property names. Arrays use zero-based indexes.

Objects match exactly, including their key sets; key order does not matter. Arrays match exactly and preserve order. Numbers compare by value (`1` equals `1.0`), with no string coercion (`"1"` differs from `1`). A missing path differs from an explicit `null`. A string containing JSON is still a string: parse it in your adapter if structured output is intended.

## Tool calls

```json
{"type": "tool_called", "name": "knowledge.search", "arguments": {"filters": {"language": "en"}}, "min": 1, "max": 2}
{"type": "tool_forbidden", "name": "support.export"}
```

`tool_called` counts calls with the exact name and, when present, matching arguments. Argument objects use recursive subset matching: extra actual object keys are allowed. Arrays require exact values and order, including exact nested objects. Numbers follow the JSON equality rules.

`min` defaults to `1` and `max` to `1000`. Both are integers from 1–1000, and `max` cannot be lower than `min`. These bounds count only matching calls. To cap all calls, add `max_tool_calls`; to disallow all calls of a given name, use `tool_forbidden`.

Tool names are case-sensitive, 1–128 characters, starting with a letter or digit and otherwise allowing letters, digits, `.`, `_`, `:`, `/`, and `-`. A forbidden call fails even if the final answer claims the request was refused. EvalTrail checks the trace supplied by the adapter and does not block execution itself.

## Tool order

```json
{"type": "tool_sequence", "names": ["knowledge.search", "knowledge.read"]}
```

The names must appear as an ordered subsequence. Other calls may occur before, between, or after them. Repeated names require separate calls. This does not imply adjacency, successful completion, or matching arguments; combine it with other checks when needed. One sequence accepts 1–50 names.

## Citations

```json
{"type": "citation", "value": "returns-policy"}
```

At least one citation must have exactly this `sourceId`. The check does not fetch the document or verify whether it supports the answer. Use a stable ID from your retrieval layer rather than a model-generated label.

## Budgets

```json
{"type": "max_tool_calls", "value": 3}
{"type": "max_tokens", "value": 400}
{"type": "max_latency_ms", "value": 5000}
```

All upper bounds are inclusive. Tool calls count every entry in the trace. Tokens are the sum of reported `inputTokens` and `outputTokens`; absent usage fails with `USAGE_MISSING`. Latency is measured by the HTTP runner or supplied by an offline recording. These checks assess a completed observation; the HTTP deadline separately limits request duration.

Allowed values: 0–1000 tool calls, 0–1,000,000,000 tokens, and 0–120,000 milliseconds. A token count is a resource metric, not a monetary cost estimate.

## Reading evidence

Each JSON check result includes its zero-based `index`, `type`, `passed`, `code`, `matches`, and an `evidence` array. Codes are `MATCH`, `MISMATCH`, `WRONG_OUTPUT_TYPE`, and `USAGE_MISSING`.

Evidence entries are pointers into the normalized observation, such as `/output`, `/toolCalls/0`, or `/citations/0`. Timing points to `/latencyMs`, the runner's measurement or the recording field. At most ten pointers are retained per check; `matches` can be higher. An empty array can mean that no matching event exists. Evidence pointers do not embed raw text or tool arguments, and the observation itself is not copied into the report.
