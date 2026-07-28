# Integration tests

One level up from unit tests, one level down from acceptance. An integration test proves **one repository or one external-service client against the real dependency it talks to**, brought up via a container — no full app, no HTTP surface, but nothing mocked either. They exist to catch what a functional spec (every dependency mocked) can't: that the actual SQL executes against the real schema, that codecs round-trip, that DB-enforced constraints fire, that a byte stream really lands in a bucket, that an email really reaches an inbox. This document defines the project-agnostic standard for that tier: how specs are scoped, how a container stack is wired per dependency, the two accepted shapes for turning a running container into test config, and what an integration spec must assert. It applies regardless of the container/orchestration tool, the test framework, or which dependencies a given project integrates with.

## Scope

This document owns the **project-agnostic real-dependency, single-component test tier**:

- the scope boundary of an integration spec — one repository or one external-service client, against its real dependency, with no app/HTTP layer in between;
- one container (or container stack) per dependency, brought up once per suite;
- the two accepted shapes for wiring container-derived config into a spec;
- asserting against the dependency's own observable state, not merely "no exception was thrown";
- resetting dependency state between tests so specs stay order-independent.

It does **not** own:

- **General test naming and assertion conventions** (test structure, naming test values, determinism) — see [stack/scala-new.md § Testing standards](stack/scala-new.md#testing-standards).
- **The repository-spec-specific recipe** — id/timestamp mocking, constraint-violation assertions, arbitraries for rows/inputs — see [repository-new.md § Testing](repository-new.md#testing--repository-integration-specs-with-testcontainers).
- **The mocked-dependency service tier** (one service, every dependency mocked) — see [functional-tests-new.md](functional-tests-new.md).
- **The whole-app tier** (real app + real dependencies, driven over HTTP) — see [acceptance-tests-new.md](acceptance-tests-new.md).

This document sits at the project's top-level standards directory (`docs-claude/`), alongside those other tiers — not nested under a technology-specific stack folder.

## Table of contents

- [Scope](#scope)
- [Naming & scope](#naming--scope)
- [Container stacks — one config per dependency](#container-stacks--one-config-per-dependency)
- [Two accepted shapes for wiring container-derived config](#two-accepted-shapes-for-wiring-container-derived-config)
- [What to assert](#what-to-assert)
- [Running them](#running-them)

## Naming & scope

Two spec families make up this tier:

- **A repository-integration spec** — a persistence stack (row model → query layer → repository) against a real instance of the database. The simplest example is the spec for a trivial health-check repository.
- **An external-service-client spec** — a client from the project's client layer against its real (or protocol-compatible) dependency: an email client against a real SMTP catcher, an outbound-HTTP client against a request-recording mock server, an object-storage client against a real (or emulated) storage API.

See [repository-new.md § Testing](repository-new.md#testing--repository-integration-specs-with-testcontainers) for the full repository-specific recipe (id/timestamp mocking, constraint-violation assertions, arbitraries) — this document covers only the shape shared by every spec in the tier, repository or client.

*Concretely in this codebase:* `PingRepositorySpec` (repository family, the simplest example); `EmailClientSpec` (SMTP via MailHog), `TwilioClientSpec` (HTTP via Wiremock), `OrganizationLogosS3ClientSpec` (S3 API via s3mock) (client family). Both families live under `backend/gateway/core/src/test/scala/io/mesazon/gateway/it`.

One spec per repository or client.

## Container stacks — one config per dependency

Each spec brings up only the dependency it needs, via its own container configuration — never a shared stack that couples unrelated specs together, and never the whole application. The dependency is started **once per suite** by the container-base trait and torn down after; a helper resolves the running container's exposed host/port into test config.

*Concretely in this codebase:* one Docker Compose file per dependency, under `src/test/resources/compose/`:

| Compose file | Brings up | Used by |
|---|---|---|
| `repository.yaml` | `postgres` + a one-shot `flyway migrate` | any `<Entity>RepositorySpec`, `PingRepositorySpec` |
| `s3.yaml` | s3mock | `OrganizationLogosS3ClientSpec` |
| `email.yaml` | MailHog | `EmailClientSpec` |
| `wiremock.yaml` | Wiremock | `TwilioClientSpec` (and any future outbound-HTTP client) |

A spec extends the project's shared spec base (sample generation, ZIO-effect run helpers, mock-stubbing helpers), the feature's arbitraries trait, and the container-base trait — override the file/stack pointer to name the one config above, and the exposed-service descriptor to match the dependency's test client. The container-base trait starts that stack once per suite, shared across every test in the suite; a `withContainers`-style helper resolves the exposed host/port into config for use inside a test.

*Concretely in this codebase:* the base traits are `ZWordSpecBase` (`arbitrarySample`, `.zioValue`/`.zioError`/`.zioEither`, scalamock ZIO stubs) and `DockerComposeBase` — override `dockerComposeFile` to point at the one compose file above and `exposedServices` to the matching `PostgreSQLTestClient.ExposedServices` / `<Thing>TestClient.ExposedServices` / `WiremockClient.ExposedServices`. `withContainers { container => ... }` resolves the exposed host/port into a config.

## Two accepted shapes for wiring container-derived config

- **A fresh-per-test context trait**, opened anew in every test — rebuilds config/mocks/service graph from scratch each time. This is **mandatory** whenever the spec mocks the time source or id generator (true of every repository spec) — those mocks must be fresh per test to keep minted ids/timestamps deterministic and test-local. This is the shape [repository-new.md § The TestContext pattern](repository-new.md#the-testcontext-pattern) documents in full.
- **A `withContext` function** that closes over the running container and hands each test a bundle of the resolved config plus any client the assertions need, instead of instantiating a trait per test. **Prefer this shape for a new client spec with no per-test mocks.**

*Concretely in this codebase:* the first shape is `trait TestContext`, opened fresh with `new TestContext {}`. The second is `def withContext[A](f: Context => A): A = withContainers { container => ... f(Context(...)) }`, used by `EmailClientSpec` and `TwilioClientSpec`: a `case class Context(...)` bundles the resolved config plus any client the assertions need (`mailHogClient`, `wiremockClient`), and `beforeAll`/`beforeEach`/`afterEach` reach into the same container-derived state through the same function instead of instantiating a trait. `OrganizationLogosS3ClientSpec` still uses the `TestContext` trait and is the exception to follow when in doubt, not the pattern to copy for a new client.

## What to assert

- **Repository specs** — see [repository-new.md § What to assert](repository-new.md#what-to-assert-and-the-shape-of-a-test): whole-row equality read back from the table, exact minted ids/timestamps, DB-enforced constraint failures mapped to the right domain error.
- **Client specs** — assert against the dependency's own observable state, not just "no exception was thrown":
  - `mailHogClient.readInbox().zioValue.total shouldBe 1` — an email was really sent.
  - `s3TestClient.getObject(bucket, key).zioValue should contain theSameElementsInOrderAs originalStream.runCollect.zioValue` — the exact bytes were really stored, byte-for-byte.
  - `wiremockClient.requestsDetails.zioValue.filter(_.count > 0)` asserted on method + URL + count — the outbound HTTP call really had the right shape.
  - Failure paths assert the exact domain error (message included), not just its type.
- **Reset dependency state between tests** so specs stay order-insensitive: `s3TestClient.emptyAllBuckets()`, `mailHogClient.clearInbox()`, `wiremockClient.reset`, or table truncation for repositories (see [repository-new.md](repository-new.md#testing--repository-integration-specs-with-testcontainers)) — same isolation requirement as [acceptance-tests-new.md](acceptance-tests-new.md).

## Running them

They need a container runtime. Running a single spec starts and stops its own stack around the suite. Slower than the mocked-dependency tier (containers to boot), faster than the whole-app tier (one dependency, not the whole app).

*Concretely in this codebase:* they need Docker; `sbt "gateway-core/testOnly *<Entity>RepositorySpec"` (or `*<Client>ClientSpec`) runs one spec.
