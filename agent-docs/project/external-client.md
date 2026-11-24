# External client changes

Read for a new/changed SMTP, S3, or outbound HTTP client and its integration test. Business orchestration is tested in the service functional spec; this guide proves one client against a real/protocol-compatible dependency, without the full app or HTTP entrypoint.

## Integration spec

File: `gateway/core/.../it/<Client>ClientSpec`. Extend `ZWordSpecBase`, needed feature arbitraries, and `DockerComposeBase`. One compose dependency per suite:

| Compose | Dependency |
|---|---|
| `email.yaml` | MailHog/SMTP |
| `s3.yaml` | s3mock/S3 |
| `wiremock.yaml` | Wiremock/outbound HTTP |

Override the compose file and matching test client's `ExposedServices`; `DockerComposeBase` starts it once. Prefer `withContext` with container-derived config for a client without per-test mocks. Use a fresh `TestContext` only when fresh mocks are required.

Assert the dependency's observable state, not merely effect success:

- MailHog inbox count/content;
- S3 object bytes in order;
- Wiremock request method, URL, body as applicable, and count;
- exact `ServiceError`, including message, for failures.

Reset dependency state between tests (`clearInbox`, `emptyAllBuckets`, `wiremockClient.reset`). Tests are order-independent and use fresh arbitrary values.

Run:

```sh
sbt "gateway-core/testOnly *<Client>ClientSpec"
```

If endpoint-visible behavior changes, also update the service functional and acceptance tests plus feature doc.

## Sttp backend in a spec

Every `gateway-core`/`waha` spec-side sttp backend — the client under test and the test clients that inspect the dependency (`WiremockClient`, `MailHogClient`) — comes from the shared `io.mesazon.testkit.base.SttpTestBackend.live` layer. Never build one with `HttpClientZioBackend.layer()` in a test:

- `layer()` is `ZLayer.scoped`, and `ZIOTestOps`'s `zioValue`/`zioEither` run an `IO` with no `Scope`, so the layer's finalizer runs the moment the backend is constructed — before the test body uses it. The lifetime of a backend a test holds must not depend on a scope that has already closed.
- `layer()` builds a new JDK `HttpClient` per call site. With `withContext` creating one per `beforeAll`/`afterEach`/test, a single suite produces dozens of clients, each with its own selector thread and connection pool, and none of them is ever closed explicitly (`HttpClientBackend.close()` is a no-op unless the backend owns a `ThreadPoolExecutor`), so their shutdown is left to the JDK reclaiming each unreachable client.

`SttpTestBackend` has two layers with the same `WebSocketStreamBackend[Task, ZioStreams]` output, so callers can swap them:

- `live` creates one `HttpClient` per JVM (`lazy val`), wraps it with `HttpClientZioBackend.usingClient(...)` so `close()` can never tear it down, and exposes it as a plain `ZLayer.succeed(...)` with no finalizer. `gateway-core` and `waha` specs use it. It keeps the JDK's default HTTP version, so specs exercise the same negotiation a real client does.
- `liveScoped` acquires one `HttpClient` per scope with `ZIO.acquireRelease`, built with `.executor(zioExecutor.asJava)` so its worker tasks run on ZIO's executor and it creates no thread pool of its own (default HTTP version kept), wraps it with `HttpClientZioBackend.usingClient`, and calls `HttpClient.close()` on release itself, because the sttp backend's `close()` never closes a client it was handed; `close()` stops the client's per-client selector thread, which no builder setting removes. Gateway acceptance specs use it through `GatewayItContext.live`, which feeds it to both `GatewayClient` and `MailHogClient`; the client lives as long as one `withContext` call, which provides the layer to that test or `beforeEach` and closes it when the call ends ([Acceptance testing](acceptance-testing.md#harness)).

Production wiring keeps its own `SttpBackend.live` (`usingClient` plus pinned HTTP/1.1 and config-driven timeouts) and is unaffected.

## OpenAI Responses endpoint

`AIClientDataExtraction` sends every extraction call to `/v1/responses` through sttp-ai 0.12.0 `createModelResponse` (issue 607: image, CSV batches and note compaction moved from chat completions, which has no file input; the legacy `OpenAIClient` stays on chat completions). One private send-and-decode function serves `extractFromImage`, `extractFromPdf`, `extractFromCsv` and `noteCompaction`; each passes its own configured model, reasoning effort and request timeout (`image-*`, `pdf-*`, `csv-*`, `note-compaction-*`).

- **Request.** `ResponsesRequestBody` with the model, the prompt as `instructions`, one user `InputMessage` holding the source, the processing message as a second user `InputMessage` (`InputText`) when present, `reasoning.effort`, `store: false` (user decision, issue 607: OpenAI keeps no stored copy of the request or answer) and `text.format = Format.JsonSchema(name = "ai_client_response", schema, strict = true)` from the registered `OpenAIJsonSchema` (the format name is one shared constant). The source is `InputImage(detail = "auto", imageUrl = data:<mime>;base64,…)` for an image (agreed, not built: `detail = "high"`, issue 607 reading-consistency round, so dense photos are not downscaled) (`auto` is what the former chat request got by omitting `detail`), `InputFile` for a PDF, and `InputText` for a CSV batch or the JSON array of notes.
- **Response.** Only when `status` is `completed`, the first `message` output item's first `output_text` is decoded as `A`. No `message` item, no `output_text`, a `Refusal`, another `status`, or JSON that does not decode is a non-retried `UnexpectedError` whose message starts with "Failed to parse AI response". An image path that cannot be read fails with "Failed to read image for AI extraction" before any request.
- **Send policy.** The retry predicate, `sendMaxRetries`, exponential `sendRetryDelay`, `readTimeout(requestTimeout)` and the "Unable to send message to AI" mapping (error statuses, transport failures, exhausted retries and timeouts) live in one private `sendWithRetryPolicy`. Logs never contain the request body or base64 data.
- **Library facts (WireMock spike, issue 607).** The Responses request encoder applies `SchemaSupport.normalizeForStrict` itself when `strict` is set, so the client passes the raw registered schema; `createModelResponse` uses the same response handler as chat completions, so `isRetryableSendError` is unchanged; the read timeout is honoured; a body whose `reasoning` item precedes the `message` decodes.
- **PDF.** `extractFromPdf` calls the same shared function with one `InputFile` (a `data:application/pdf;base64,…` URI and, as `filename`, the caller's declared file name passed in as `pdfFileNameDeclared`, sent unchanged: user decision, issue 607; for Catalogue it is the validated `X-File-Name`, 1–255 trimmed characters whose extension `FileScanner` has matched to PDF ignoring case), its own `pdf-model`/`pdf-reasoning-effort` config and `request-timeout`. A PDF path that cannot be read fails with "Failed to read PDF for AI extraction" before any request. Catalogue `aiExtract` calls it for PDF uploads, passing the validated `X-File-Name` (see [Catalogue PDF extraction](../features/catalogue.md#pdf-extraction)).
- **Stubs.** Every AI WireMock mapping matches `urlPath` `/v1/responses` and returns a Responses `output` body. Client-spec mappings live in `backend/wiremock/mappings/ai-client-responses.json`, chosen by `AI_CLIENT_SPEC_*`, `AI_CLIENT_SPEC_PDF_*` and `NOTE_COMPACTION_SPEC_*` instruction markers; acceptance mappings are the `ai-client-responses-<feature>-file-*-acceptance.json` files, matched on prompt phrases. Catalogue's PDF mapping (`ai-client-responses-catalogue-file-pdf-acceptance.json`, priority 1) also requires the PDF prompt phrase `one or more pages, typed or scanned` and `input_file`, so it answers PDF requests ahead of the shared priority-2 Catalogue mapping. Because all calls share one path, stubs are told apart only by body patterns: keep markers free of substring overlap. Rebuild the WireMock image after changing them.
- **Live proof.** WireMock proves request shape only; the user-run golden check after this migration is tracked in [Acceptance test gaps](../acceptance-test-gaps.md) entry 17.

## Mockability of the client's own trait

`AIClient` requires explicit `OpenAIJsonSchema[A]` registrations from `io.mesazon.gateway.json.ai`; ordinary transport callers import `io.mesazon.gateway.json.tapir`. `ai.fromTapir` adapts an existing ordinary schema once for each registered AI response. Do not derive the same model again, normalize schemas inside `AIClient`, or introduce a blanket conversion from every `Schema[A]`. The legacy `OpenAIClient` remains unchanged with its ordinary `Schema[A]` contract; `ai` also supplies its AssistantResponse schema/codec.

Before assuming the service functional spec can `mock[Client]` this trait, check whether any of its methods pairs a type parameter with a typeclass `using` bound (e.g. `def m[A](...)(using OpenAIJsonSchema[A], JsonValueCodec[A]): F[A]`, as `AIClient` does (`OpenAIClient` has the same generic-method shape with ordinary `Schema[A]`)). ScalaMock's `mock[T]` macro cannot correctly mock that shape — see [Functional testing](functional-testing.md)'s "Known limitation" section for the confirmed root cause and the hand-written-double resolution. Check for this before writing the client, not after the functional spec fails to compile.
