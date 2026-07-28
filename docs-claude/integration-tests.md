# Integration tests (`backend/gateway/core/src/test/scala/io/mesazon/gateway/it`)

One level up from unit tests, one level down from acceptance. These prove **one repository or one external-service client against the real dependency it talks to**, run via Testcontainers/Docker Compose — no full app, no HTTP surface, but nothing mocked either. They exist to catch what a functional spec (every dependency mocked) can't: that the actual SQL executes against the real Flyway schema, that jsonb/enum codecs round-trip, that DB constraints fire, that a byte stream really lands in a bucket, that an email really reaches an inbox. Business orchestration belongs in [functional specs](functional-tests.md); the full request lifecycle belongs in [acceptance specs](acceptance-tests.md) (separate `gateway-it` module, whole app over HTTP).

## Naming & scope

Two spec families live in this package:

- **`<Entity>RepositorySpec`** — a `Row` → `Queries` → `Repository` stack against real PostgreSQL. `PingRepositorySpec` (the health-check repository) is the simplest example. See [repository.md § Testing](repository.md#testing--repository-integration-specs-with-testcontainers) for the full repository-specific recipe (IDs/timestamps mocking, constraint-violation assertions, `RepositoryArbitraries`) — this doc covers only the shape shared by every spec in the package.
- **`<Client>ClientSpec`** — a `gateway/clients` client against its real (or protocol-compatible) dependency: `EmailClientSpec` (SMTP via MailHog), `TwilioClientSpec` (HTTP via Wiremock), `OrganizationLogosS3ClientSpec` (S3 API via s3mock).

One spec per repository or client.

## Container stacks — one Docker Compose file per dependency

Each spec brings up only the dependency it needs, via its own compose file under `src/test/resources/compose/`:

| Compose file | Brings up | Used by |
|---|---|---|
| `repository.yaml` | `postgres` + a one-shot `flyway migrate` | any `<Entity>RepositorySpec`, `PingRepositorySpec` |
| `s3.yaml` | s3mock | `OrganizationLogosS3ClientSpec` |
| `email.yaml` | MailHog | `EmailClientSpec` |
| `wiremock.yaml` | Wiremock | `TwilioClientSpec` (and any future outbound-HTTP client) |

A spec extends `ZWordSpecBase` (`arbitrarySample`, `.zioValue`/`.zioError`/`.zioEither`, scalamock ZIO stubs), the feature's arbitraries trait, and `DockerComposeBase` — override `dockerComposeFile` to point at the one compose file above and `exposedServices` to the matching `<Thing>TestClient.ExposedServices` / `WiremockClient.ExposedServices`. `DockerComposeBase` starts that stack **once per suite**; `withContainers { container => ... }` resolves the exposed host/port into a config.

## Two accepted shapes for wiring container-derived config

- **`trait TestContext`**, opened fresh with `new TestContext {}` in every test — rebuilds config/mocks/service graph from scratch each time. This is the shape [repository.md](repository.md#the-testcontext-pattern) documents in full, and it's mandatory whenever the spec mocks `TimeProvider`/`IDGenerator` (every repository spec) — those mocks must be fresh per test to keep minted ids/timestamps deterministic and test-local.
- **`def withContext[A](f: Context => A): A = withContainers { container => ... f(Context(...)) }`**, used by `EmailClientSpec` and `TwilioClientSpec`: a `case class Context(...)` bundles the resolved config plus any client the assertions need (`mailHogClient`, `wiremockClient`), and `beforeAll`/`beforeEach`/`afterEach` reach into the same container-derived state through the same function instead of instantiating a trait. **Prefer this shape for a new client spec with no per-test mocks** — `OrganizationLogosS3ClientSpec` still uses the `TestContext` trait and is the exception to follow when in doubt, not the pattern to copy for a new client.

## What to assert

- **Repository specs** — see [repository.md § What to assert](repository.md#what-to-assert-and-the-shape-of-a-test): whole-`Row` equality read back from the table, exact minted ids/timestamps, DB-enforced constraint failures mapped to the right `ServiceError`.
- **Client specs** — assert against the dependency's own observable state, not just "no exception was thrown":
  - `mailHogClient.readInbox().zioValue.total shouldBe 1` — an email was really sent.
  - `s3TestClient.getObject(bucket, key).zioValue should contain theSameElementsInOrderAs originalStream.runCollect.zioValue` — the exact bytes were really stored, byte-for-byte.
  - `wiremockClient.requestsDetails.zioValue.filter(_.count > 0)` asserted on method + URL + count — the outbound HTTP call really had the right shape.
  - Failure paths assert the exact `ServiceError` (message included), not just its type.
- **Reset dependency state between tests** so specs stay order-insensitive: `s3TestClient.emptyAllBuckets()`, `mailHogClient.clearInbox()`, `wiremockClient.reset`, or table truncation for repositories (see [repository.md](repository.md#testing--repository-integration-specs-with-testcontainers)) — same isolation requirement as [acceptance specs](acceptance-tests.md).

## Running them

They need Docker. `sbt "gateway-core/testOnly *<Entity>RepositorySpec"` (or `*<Client>ClientSpec`) runs one spec; its compose stack starts and stops around the suite. Slower than functional specs (containers to boot), faster than acceptance specs (one dependency, not the whole app).
