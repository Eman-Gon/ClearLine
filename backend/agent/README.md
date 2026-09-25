# Session 3: local agent and sponsor integrations

The worker imports `create_agent_runner()` and `create_exporter()` from
`backend.agent`. `advance(checkpoint)` proposes one validated Liquid tool call;
the worker commits its plan/action ID before `execute(action, checkpoint)`.
SQLite owns action recovery, checkpoints, and outbox transactions. RawTree stores
approved history and evidence; it is not the queue.

## Start the real local model

Install a current compatible [llama.cpp build](https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md), then:

```sh
llama-server \
  -hf LiquidAI/LFM2.5-2.6B-GGUF:Q4_K_M \
  --alias clearline-liquid \
  --host 127.0.0.1 --port 8080 \
  -c 4096 --jinja
```

This downloads model weights on first use. No Liquid sponsor key is needed for
this loopback server. Keep `LIQUID_MODEL` equal to the server alias. The adapter
requires `/v1/models`, `/apply-template`, `/tokenize`, and `/v1/chat/completions`.
It rejects chat-only responses, malformed calls, multiple calls in a turn, or
missing tokenization support. It never executes generated code or substitutes
another planner.

Copy the root `.env.example` to `.env`, configure the two sponsor keys and intended
RawTree database, and configure the backend pairing code/audio dependencies.
Do not commit `.env`. Start the application from the repository root:

```sh
python -m uvicorn backend.main:app --env-file .env --host 127.0.0.1 --port 3000
```

## Tests

Offline tests use HTTP mocks and do **not** demonstrate a successful sponsor call:

```sh
python -m pytest backend/smoke_tests -q
```

Run real sponsor probes explicitly:

```sh
python -m backend.smoke_tests.run all --env-file .env
# Or select liquid, rawtree, or nimble individually.
python -m backend.smoke_tests.run nimble --env-file .env --city Oakland
```

The RawTree probe writes one labeled synthetic event with a new UUID, then queries
that exact event from the configured database. The Nimble probe searches the
specified public category/city and extracts one returned source; those calls use
the configured account. The Liquid probe requires an actual `echo_nonce` call,
generates the nonce only after that call, returns it as a tool message, and checks
that the next model response uses it. Output is redacted of credentials. Exit
status 2 means a probe failed or is unavailable, never success.

At initial implementation verification, no local llama-server was running and
neither sponsor key was configured. Live probes reported all three unavailable.
No actual model build, quantization, latency, or token usage was measured then.
The requested model above is configuration, not a claim of successful execution.

## Durable integration contract

Checkpoints include session/profile identity, input revision, workflow scope,
phase, approved resource request, permitted tools, current metrics, baseline,
search candidates (`sources`), verified resources (`resources`), and evidence refs.
The worker also persists the preceding `model_call` and `last_tool_result`; the
next activation reconstructs a matching assistant/tool exchange after restart.
Unknown interrupted reads may repeat; committed successful actions are reused.

Only the current projection and selected excerpts enter the model prompt. Event
history, full pages, transcripts, and audio do not. The local byte guard is labeled
as bytes; actual token limits use the runtime's rendered template and tokenizer,
including function schemas, with at most 3072 input tokens and 768 output tokens
within a 4096-token context. Oversized obligations fail visibly instead of being
silently dropped. Actual completion usage is preserved when returned by the server.

Public sources are untrusted text. Search candidates cannot be treated as verified
contact information. Extracted facts are copied conservatively from explicit
labeled passages; unknown fields remain null and availability is never claimed to
be independently verified. URL validation blocks private literals and local DNS
targets; Nimble performs the remote fetch, so local checks cannot pin its remote
DNS/redirect behavior. Returned source redirects are checked again.

RawTree requests use allowlisted tables and fields; only fixed bounded SELECT
templates are available. Summary queries select latest versions before matching
task/method/provenance and choosing the latest five eligible sessions. Cloud export
consent is asserted by the trusted worker, validated again by the adapter, and
not included in the cloud payload. Duplicate event deliveries retain the same ID
and are deduplicated by history queries.

## Live interruption acceptance

After all three live probes pass, create a consented session, finish a real complete
clip, and request an approved resource search. Pause after the Search result is
committed and before Extract. Terminate the backend process, restart on the same
SQLite database, and resume that session once. Verify the accepted clip ID and
completed Search action ID are unchanged, then verify the next Extract action and
source-backed result. A mocked reconstruction test covers this transaction boundary;
it is not evidence that the live sponsor/phone interruption demo passed.

## Verified API references

- [Liquid tool use](https://docs.liquid.ai/lfm/key-concepts/tool-use)
- [Official Liquid model](https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF)
- [llama.cpp server API](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)
- [RawTree API](https://rawtree.com/docs/reference/api) and [query guide](https://rawtree.com/docs/guides/query-data)
- [Nimble Search](https://docs.nimbleway.com/api-reference/search/search) and [Extract](https://docs.nimbleway.com/api-reference/extract/extract)
