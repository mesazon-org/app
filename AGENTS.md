# Mesazon App

Mesazon is a business-management platform; features derive from real business needs.

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
Recurring and resolved failure modes: [Known issues](agent-docs/known-issues.md).

## Documentation router

Read only the documents needed for the current change. For any feature change, first read its file in `agent-docs/features/`.

### New feature or endpoint

Start with [Feature flow](agent-docs/features/flow/README.md), then read only the current PR slice:

| Guide | Read when |
|---|---|
| [1. Endpoints](agent-docs/features/flow/01-endpoints.md) | Adding/changing Smithy or Tapir endpoints and their transport models, auth traits, errors, or API docs |
| [2. Validation](agent-docs/features/flow/02-validation.md) | Adding/changing validated domain models, newtypes, arbitraries, validators, or unit tests |
| [3. Schema](agent-docs/features/flow/03-schema.md) | Adding/changing migrations, tables, constraints, indexes, or table config only |
| [4. Repository](agent-docs/features/flow/04-repository.md) | Adding/changing persisted types, Rows, Queries, Repositories, codecs, or DB tests |
| [5. Service](agent-docs/features/flow/05-service.md) | Combining all layers into orchestration, endpoint implementation/wiring, functional tests, and acceptance tests |

Each slice guide links its technology standards.

### Product epics

`pages/epics/` holds the business-facing spec for each area of the product, published via GitHub Pages. An epic says what a user can do and why; `agent-docs/features/` stays the engineering source of truth for endpoints, types, files, and tests.

| Guide | Read when |
|---|---|
| [Epic template](pages/epics/TEMPLATE.md) | Writing a new epic or restructuring an existing one |
| The epic listed under [Epics](#epics) | Starting any feature request, or changing behavior a user can observe |

Two rules govern every epic:

- **Plain, simple English.** The audience is non-engineers. Short sentences, everyday words, no Scala/type/class/file names, no internal jargon, no unexplained abbreviations. Describe behavior the user can observe, not the implementation that produces it.
- **Always true of the code.** Stage names, error codes, field shapes, limits, and business rules in an epic must match what the code actually does. Verify against the feature doc and the implementation before publishing — a confidently wrong epic is worse than a missing one.

A mermaid diagram after the overview is optional — add one only when a journey is tangled enough that a picture beats the prose, and keep it small. A diagram that restates steps the reader is about to read costs more space than it earns. Fenced ` ```mermaid ` blocks are rendered by `pages/_layouts/epic.html`, which epics get automatically from the `defaults` in `pages/_config.yml`.

Every epic needs YAML front matter with a `title:` — Jekyll skips files without it, and the page never renders.

### Exceptional changes

| Guide | Read when |
|---|---|
| [Authentication](agent-docs/project/authentication.md) | Changing middleware, auth/onboard/organization-role policy, `AuthState`, or transport security |
| [Alternate HTTP](agent-docs/project/alternate-http.md) | Changing Tapir/streaming endpoints, their docs, limits, or error model |
| [Streaming uploads](agent-docs/project/streaming-uploads.md) | Changing `FileScanner`/`ImageProcessing` byte handling, upload cap enforcement, or `EntityLimiter` interaction |
| [External client](agent-docs/project/external-client.md) | Adding/changing SMTP, S3, or outbound HTTP clients and dependency integration tests |
| [Database runtime](agent-docs/project/database-runtime.md) | Changing datasource/pool, transactor, SQL logging, or shared PostgreSQL test-client mechanics |
| [Build](agent-docs/project/build.md) | Changing sbt, dependencies, modules, tasks, Docker packaging, Scala/JDK, or CI |
| [Feature consolidation](agent-docs/project/feature-consolidation.md) | Moving an old feature to the current layout without behavior changes |
| [Acceptance testing](agent-docs/project/acceptance-testing.md) | Adding/reviewing real-gateway HTTP acceptance specs, `GatewayClient` test methods/codecs, shared acceptance harness wiring, organization middleware matrices, or rejected-side-effect assertions |
| [Terraform](agent-docs/project/terraform.md) | Changing `terraform/`, or finishing a feature that added an external dependency, credential, or required env var |

### Diagnostics

| Guide | Read when |
|---|---|
| [Known issues](agent-docs/known-issues.md) | Diagnosing CI, local runtime, container, HTTP transport, or flaky test failures; update it when a reusable failure signature and fix are established |

### Technology standards

Read every standard whose trigger matches the task:

| Standard | Read when |
|---|---|
| [Scala](agent-docs/standards/scala.md) | Writing, changing, reviewing, or testing Scala code, including naming and refactoring |
| [Smithy](agent-docs/standards/smithy.md) | Adding/changing Smithy endpoints, operations, transport models, traits, errors, or generated contracts |
| [Tapir](agent-docs/standards/tapir.md) | Adding/changing Tapir endpoints, streaming inputs, security, errors, or OpenAPI docs |
| [Iron](agent-docs/standards/iron.md) | Adding/changing refined newtypes, domain constraints, validation boundaries, or refined conversions/codecs |
| [PostgreSQL](agent-docs/standards/postgres.md) | Adding/changing migrations, tables, columns, types, constraints, indexes, or stored-data lifecycle |
| [Doobie](agent-docs/standards/doobie.md) | Adding/changing SQL queries, fragments, row codecs, repository DB effects, transactions, or pool wiring |
| [sbt](agent-docs/standards/sbt.md) | Adding/changing dependencies, modules, build settings/tasks/plugins, Scala/JDK versions, Docker build wiring, or CI commands |

### Agent configuration ownership

`.agents/` is canonical for shared agents, contracts, commands, and skills. `.claude/` is a real Claude-specific directory; each shared file inside it is a relative symlink to `.agents/`. Edit shared files in `.agents/`. Claude-only files/config stay directly under `.claude/`; to diverge one shared file, replace only its symlink. `.claude/settings.local.json` and `.claude/worktrees/` are local/gitignored.

## Validation flow (rules, run in order, every change)

1. **Feature doc first** — read the relevant `agent-docs/features/` file before coding. For a new feature, create `agent-docs/features/<feature-name>.md` in PR 1, link it under [Features](#features), and update its status/content in every slice. Never wait until the final PR. Required structure: scope/boundaries; endpoint auth/onboard/roles; flow/security/decisions; key files/config; unit/functional/integration/acceptance tests. See [Feature flow](agent-docs/features/flow/README.md).
2. **Epic in sync** — every feature request and every change to observable behavior updates the epic it belongs to in `pages/epics/`, in the same PR. Find the related epic first; if none fits, create one from the [epic template](pages/epics/TEMPLATE.md) and link it under [Epics](#epics). Epics are written in plain, simple English and must always match the code — see [Product epics](#product-epics).
3. **Standards compliance** — read the current slice or exceptional-change guide from the [documentation router](#documentation-router) and its linked technology standards.
4. **Tests in every PR** — write and pass the current slice's applicable tests; never defer them:
   - **Unit** `gateway/core/.../unit` — validators, pure helpers.
   - **Functional** `gateway/core/.../fun` — one service, effectful dependencies mocked.
   - **Integration** `gateway/core/.../it` — one repository/client vs a real dependency; no application HTTP.
   - **Acceptance** `gateway/it` — real gateway and dependencies over HTTP.
5. **Lint** — run `sbt "runLint"` before done. `checkLint` is the read-only CI gate.
6. **Docs currency** — every task fixes affected stale statements in `agent-docs/`, `pages/`, and this file, including renamed errors, types, endpoints, config keys, and files. Document new conventions. A stale doc is worse than no doc.

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
- `docs/` — human-facing setup docs and diagram assets; not published
- `pages/` — the GitHub Pages site: business-facing product epics in `pages/epics/` (template at `pages/epics/TEMPLATE.md`), plus the Jekyll config and homepage
- `agent-docs/` — concise LLM-facing feature, project, standards, and diagnostic instructions
- `.agents/{agents,contracts,commands,skills}/` — shared agent sources; `.claude/` holds per-file symlinks plus Claude-specific files/config

## Epics

Business-facing product specs in `pages/epics/`, published via GitHub Pages and written for non-engineers. See [Product epics](#product-epics) for the two rules every epic follows.

New epic → copy the [epic template](pages/epics/TEMPLATE.md), list it here, and add it to `pages/index.md` (both indexes are updated together).

- [1. User Sign Up](pages/epics/1-user-sign-up.md) — sign up, verify email, set a password, add details, verify phone

## Features

Engineering-facing feature docs. New feature → [Feature flow](agent-docs/features/flow/README.md); create/link its feature doc in PR 1 per [Validation flow §1](#validation-flow-rules-run-in-order-every-change).

- [User Onboarding](agent-docs/features/user-onboarding.md)
- [User Sign in](agent-docs/features/user-signin.md)
- [User Sign up](agent-docs/features/user-signup.md)
- [User Forgot Password](agent-docs/features/user-forgot-password.md)
- [User Token Management](agent-docs/features/user-token-management.md)
- [Organization Management](agent-docs/features/organization-management.md)
- [Customer Book](agent-docs/features/customer-book.md)
- [Catalogue](agent-docs/features/catalogue.md) — in progress; see its five-slice status

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

`/feature "<description>"` — Product Owner → Engineering Manager → complexity-selected Lead Engineer (`LOW|MEDIUM|HIGH`). PO owns product requirements, asking the user directly for anything it can't derive itself; EM challenges the spec, routes remaining product ambiguity back to PO, maps docs/outcome slices, and applies the [complexity contract](.agents/contracts/complexity.md); the selected Lead follows the [Lead contract](.agents/contracts/lead-engineer.md) to design, implement, and verify. Sources: `.agents/`; Claude: per-file `.claude/` symlinks; Codex: `.codex/agents/`. Setup: [Agent pipeline](agent-docs/agent-pipeline-setup.md).
