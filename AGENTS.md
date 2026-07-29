# Mesazon App

Mesazon is a Business Management Platform. Its goal is to enhance businesses with powerful tools to orchestrate their workflows, taking a pragmatic approach: features are derived from real business needs.

## Architecture

```mermaid
flowchart LR
    Client([Client apps]) -->|HTTP request| Gateway[Gateway service]
    Gateway --> DB[(Database)]
    Gateway --> Storage[[File storage]]
    Gateway --> Mail[[Mail server]]
    Gateway --> Messaging[[Messaging service]]
```

Internal request flow:

```mermaid
flowchart LR
    Request([HTTP request]) --> MW["Middleware\n(auth, onboard stage, org role)"]
    MW --> VAL[Request validator]
    VAL --> SVC[Feature service]
    SVC --> REPO[Repository]
    REPO --> DB[(Database)]
    SVC --> EXT[[External service clients]]
```

Local build/run/test setup: [Repository setup](docs/repository-setup.md).

## Documentation router

Read only the documents needed for the current change. For any feature change, first read its file in `docs-claude/features/`.

### New feature or endpoint

Start with [Feature flow](docs-claude/features/flow/README.md), then read only the current PR slice:

| Guide | Read when |
|---|---|
| [1. Endpoints](docs-claude/features/flow/01-endpoints.md) | Adding/changing Smithy or Tapir endpoints and their transport models, auth traits, errors, or API docs |
| [2. Validation](docs-claude/features/flow/02-validation.md) | Adding/changing validated domain models, newtypes, arbitraries, validators, or unit tests |
| [3. Schema](docs-claude/features/flow/03-schema.md) | Adding/changing migrations, tables, constraints, indexes, or table config only |
| [4. Repository](docs-claude/features/flow/04-repository.md) | Adding/changing persisted types, Rows, Queries, Repositories, codecs, or DB tests |
| [5. Service](docs-claude/features/flow/05-service.md) | Combining all layers into orchestration, endpoint implementation/wiring, functional tests, and acceptance tests |

Each slice guide links the agnostic technology standards it requires.

### Exceptional changes

| Guide | Read when |
|---|---|
| [Authentication](docs-claude/project/authentication.md) | Changing middleware, auth/onboard/organization-role policy, `AuthState`, or transport security |
| [Alternate HTTP](docs-claude/project/alternate-http.md) | Changing Tapir/streaming endpoints, their docs, limits, or error model |
| [External client](docs-claude/project/external-client.md) | Adding/changing SMTP, S3, or outbound HTTP clients and dependency integration tests |
| [Database runtime](docs-claude/project/database-runtime.md) | Changing datasource/pool, transactor, SQL logging, or shared PostgreSQL test-client mechanics |
| [Build](docs-claude/project/build.md) | Changing sbt, dependencies, modules, tasks, Docker packaging, Scala/JDK, or CI |
| [Feature consolidation](docs-claude/project/feature-consolidation.md) | Moving an old feature to the current layout without behavior changes |

### Technology standards

Read every standard whose trigger matches the task:

| Standard | Read when |
|---|---|
| [Scala](docs-claude/standards/scala.md) | Writing, changing, reviewing, or testing Scala code, including naming and refactoring |
| [Smithy](docs-claude/standards/smithy.md) | Adding/changing Smithy endpoints, operations, transport models, traits, errors, or generated contracts |
| [Tapir](docs-claude/standards/tapir.md) | Adding/changing Tapir endpoints, streaming inputs, security, errors, or OpenAPI docs |
| [Iron](docs-claude/standards/iron.md) | Adding/changing refined newtypes, domain constraints, validation boundaries, or refined conversions/codecs |
| [PostgreSQL](docs-claude/standards/postgres.md) | Adding/changing migrations, tables, columns, types, constraints, indexes, or stored-data lifecycle |
| [Doobie](docs-claude/standards/doobie.md) | Adding/changing SQL queries, fragments, row codecs, repository DB effects, transactions, or pool wiring |
| [sbt](docs-claude/standards/sbt.md) | Adding/changing dependencies, modules, build settings/tasks/plugins, Scala/JDK versions, Docker build wiring, or CI commands |

### Agent configuration ownership

`.agents/` is canonical only for agents, commands, and skills intentionally shared across tools. `.claude/` is a real Claude-specific directory; each shared file inside it is a relative symlink to its `.agents/` source. Edit shared files through `.agents/`. Claude-only files/config stay directly under `.claude/`; if one shared file must diverge for Claude, replace only that file's symlink with a real Claude-specific file rather than forking the whole tree. `.claude/settings.local.json` and `.claude/worktrees/` are local and gitignored.

## Validation flow (rules, run in order, every change)

1. **Feature doc first** — read the relevant `docs-claude/features/` file before coding. For a new feature, create `docs-claude/features/<feature-name>.md` in PR 1, link it under [Features](#features), and update its status/content in every slice. Never wait until the final PR. Required structure: scope/boundaries; endpoint auth/onboard/roles; flow/security/decisions; key files/config; unit/functional/integration/acceptance tests. See [Feature flow](docs-claude/features/flow/README.md).
2. **Standards compliance** — read the current slice or exceptional-change guide from the [documentation router](#documentation-router) and its linked agnostic standards.
3. **Tests in every PR** — write and pass the current slice's applicable tests; never defer them:
   - **Unit** `gateway/core/.../unit` — validators, pure helpers.
   - **Functional** `gateway/core/.../fun` — one service, effectful dependencies mocked.
   - **Integration** `gateway/core/.../it` — one repository/client vs a real dependency; no application HTTP.
   - **Acceptance** `gateway/it` — real gateway and dependencies over HTTP.
4. **Lint** — run `sbt "runLint"` before done. `checkLint` is the read-only CI gate.
5. **Docs currency** — every task scans `docs-claude/` + this file for now-inaccurate statements (incl. renamed identifiers: errors, types, endpoints, config keys, files) and fixes them in the same change. New convention → document it. Stale doc > missing doc, in badness.

## Project structure

- `backend/gateway/core` — gateway service: smithy, domain models, validators, services, repositories, unit+functional tests
- `backend/gateway/it` — acceptance tests (real gateway+Postgres, compose/testcontainers)
- `backend/domain` — shared domain models, newtypes (`Newtypes.scala`)
- `backend/test-kit` — shared test arbitraries, base specs
- `backend/schemas` — Flyway migrations (`migrations/`), local Postgres bootstrap (`local/`)
- `backend/clock`, `backend/generator` — shared utility modules
- `backend/postgresql-test`, `backend/s3-test`, `backend/wiremock` — test harnesses for their infra
- `backend/waha` — WhatsApp service (`core` + own `it` acceptance tests)
- `compose/` — local dev docker-compose stack
- `terraform/` — infra as code
- `docs/` — human-facing setup docs
- `docs-claude/` — standards + feature docs (this file's links)
- `.agents/{agents,commands,skills}/` — shared agent sources; `.claude/` holds per-file symlinks plus Claude-specific files/config

## Features

New feature → [Feature flow](docs-claude/features/flow/README.md); create/link its feature doc in PR 1 per [Validation flow §1](#validation-flow-rules-run-in-order-every-change).

- [User Onboarding](docs-claude/features/user-onboarding.md)
- [User Sign in](docs-claude/features/user-signin.md)
- [User Sign up](docs-claude/features/user-signup.md)
- [User Forgot Password](docs-claude/features/user-forgot-password.md)
- [User Token Management](docs-claude/features/user-token-management.md)
- [Organization Management](docs-claude/features/organization-management.md)
- [Files Management](docs-claude/features/files-management.md)
- [Customer Book](docs-claude/features/customer-book.md)
- [Catalogue](docs-claude/features/catalogue.md) — in progress; see its five-slice status

## Commands

Full detail: [Repository setup](docs/repository-setup.md).

```sh
sbt compile                                            # compiles all; smithy4s codegen auto-runs
sbt smithy4sCodegen                                     # smithy contract compiles standalone
sbt "checkLint"                                         # scalafix+scalafmt, check only
sbt "runLint"                                           # scalafix+scalafmt, autofix
sbt "gateway-build"                                     # CI alias: clean -> backend -> checkLint -> testFull
sbt "gateway-core/testOnly io.mesazon.gateway.fun.*"    # functional tests only, no containers
sbt "gateway-it/test"                                   # acceptance tests, needs Docker

sbt "gatewayCore/Docker/publishLocal"                   # build gateway image
docker compose -f compose/compose.yaml up -d            # local stack: postgres, flyway, gateway, mocks
```

`/feature "<description>"` — 4-role subagent pipeline (Product Owner → Engineering Manager → Lead Engineer → Senior Engineer), defined in `.agents/agents/` + `.agents/commands/feature.md` and exposed to Claude Code by per-file symlinks under `.claude/`. Requires OmniRoute (local model-routing gateway, per-machine setup, not shared infra): [Agent pipeline setup](docs-claude/agent-pipeline-setup.md).
