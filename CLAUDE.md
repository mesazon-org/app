# Mesazon App

Mesazon is a Business Management Platform. Its goal is to enhance businesses with
powerful tools to orchestrate their workflows, taking a pragmatic approach: features are derived from real business
needs.

Toolchain, local services, and how to build/run/test the gateway: [Repository setup](docs/repository-setup.md).

## Tech stack

Each entry links to the coding standards for that technology — **follow them when writing code**.

- [scala](docs-claude/scala.md) — programming language
- [sbt](docs-claude/sbt.md) — build definition (sbt 2.x rules, module structure/naming, dependency management, CI wiring)
- [smithy](docs-claude/smithy.md) — API contract definitions (naming conventions, coding standards, custom traits)
- [postgres](docs-claude/postgres.md) — PostgreSQL schema & persistence (Flyway migrations, table/column naming, the Row→Queries→Repository stack)
- [repository](docs-claude/repository.md) — the repository layer architecture (Row→Queries→Repository responsibilities, input models vs API requests, transactions, id/timestamp generation, error mapping, wiring, testing)

## Architecture

- [HTTP middleware](docs-claude/middleware.md) — how authentication and authorization wrap every endpoint (basic/bearer auth, `@completedOnboardStage`, organization role permissions via the `X-Organization-ID` header)
- [Request validation](docs-claude/validators.md) — how a raw smithy request is validated into a refined domain model (feature request validators, shared helpers, error accumulation, tests)
- [Adding a feature](docs-claude/adding-a-feature.md) — the per-feature file layout (domain models, validator, arbitrary traits, specs) and the order of work

## Agent pipeline

Feature requests can be run through a 4-role Claude Code subagent pipeline (Product Owner → Engineering Manager → Lead Engineer → Senior Engineer) via `/feature "<description>"`. The roles are checked into `.claude/agents/` and `.claude/commands/feature.md`; the local model-routing gateway (OmniRoute) each engineer needs is not — see [Agent pipeline setup](docs-claude/agent-pipeline-setup.md) for the one-time per-machine setup (macOS).

## Features completed

**Documentation rule (for Claude): every new feature ships with its doc, from the very first request.** The moment a new feature is requested — even if you only build the first slice (e.g. tables + schemas) — create `docs-claude/features/<feature-name>.md` and link it in the list below. The doc must always carry a **Status** section that splits the work into **what is done** and **what remains to be completed**, so a partially-built feature is legible to future sessions: update that section as each subsequent part lands, and only drop it once the feature is fully implemented and tested. You are generating context for your own future sessions, so capture what the code alone won't tell you. When you change an existing feature's behavior, update its doc.

**Rename rule (for Claude): docs reference code by name.** Whenever you rename an identifier that could be named in prose (service errors, types, endpoints, config keys, files), grep `docs-claude/` (and this `CLAUDE.md`) for the old name and update every match in the same change — the same way you would for the code and tests.

**Doc-currency rule (for Claude): every change re-checks the docs.** For *anything* you change — a feature, a test harness, a build setting, a naming or testing convention — before you call the work done, scan `docs-claude/` (and this `CLAUDE.md`) for statements your change made inaccurate and fix them in the same change; a stale doc misleads future sessions worse than a missing one. When a change establishes a new convention or standard, add it to the relevant doc so it is applied consistently thereafter. This rule is broader than the Documentation rule (new features) and Rename rule (renamed identifiers): it covers every change, not only those two.

Follow the structure of the existing docs:

- scope paragraph: what the feature owns, what it deliberately excludes, links to the owning features at each boundary
- endpoints table with auth + required onboard stages
- flow, including security/abuse defenses and other non-obvious design decisions
- key files and config
- tests: acceptance in `backend/gateway/it` (see [acceptance-tests.md](docs-claude/acceptance-tests.md)) plus functional (see [functional-tests.md](docs-claude/functional-tests.md)), unit, and integration

- [User Onboarding](docs-claude/features/user-onboarding.md)
- [User Sign in](docs-claude/features/user-signin.md)
- [User Sign up](docs-claude/features/user-signup.md)
- [User Forgot Password](docs-claude/features/user-forgot-password.md)
- [User Token Management](docs-claude/features/user-token-management.md)
- [Organization Management](docs-claude/features/organization-management.md)
- [Files Management](docs-claude/features/files-management.md)
- [Customer Book](docs-claude/features/customer-book.md)
- [Catalogue](docs-claude/features/catalogue.md) — **in progress** (part 1: tables + schemas; part 2: repository layer)