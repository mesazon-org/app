# Repository setup

Everything a new engineer needs to get Mesazon building, running locally, and (optionally) working with the `/feature` agent pipeline. macOS only for now.

## 1. Toolchain (macOS)

This should help you set up your laptop for building and executing Scala code on your machine. Once it's running, pick a code editor of your choice to start tackling problems.

**Install asdf**

```sh
brew install asdf
```

**Install the Java plugin for asdf**

```sh
asdf plugin add java https://github.com/halcyon/asdf-java.git
```

**Set `JAVA_HOME` in your shell's initialization** — add the following to your `~/.zshrc`:

```sh
. ~/.asdf/plugins/java/set-java-home.zsh
```

**Install the asdf Java version pinned in `.tool-versions`**

```sh
asdf install
```

`.tool-versions` currently pins `java temurin-jre-25.0.3+9.0.LTS`. Note this is a **JRE**, not a full JDK — CI (`.github/workflows/job-scala-build.yml`) actually provisions a full JDK (`actions/setup-java`, `java-version: 25`, distribution `temurin`). A JRE may not be enough for sbt to fork the compiler; if you hit JDK-related sbt errors, install the JDK build of the same version instead.

**Install sbt**

```sh
brew install sbt
```

SBT (Simple Build Tool) is Scala's build tool ([docs](https://www.scala-sbt.org/documentation.html)). The version itself is pinned in `project/build.properties` (currently `2.0.3`) and sbt bootstraps to it automatically — you don't need to match the Homebrew version to it.

**Docker** is also required — the local dev stack and the acceptance tests (testcontainers) both depend on it.

## 2. Local services

`docker compose -f compose/compose.yaml up -d` brings up everything the gateway needs to run locally:

| Service | Image | Ports | Purpose |
|---|---|---|---|
| `postgres` | `postgres:17.5-alpine` | 5432 | Primary DB. `POSTGRES_DB=local_db`, user/pass `postgres`/`postgres`. Bootstraps `local_schema` + roles once via `backend/schemas/local/postgres/init.sql` (only runs on first volume creation). |
| `flyway` | `flyway/flyway:12.0.0-alpine` | — | Applies migrations from `backend/schemas/migrations` against `local_schema`. Depends on `postgres` but there's no startup healthcheck, so if it races Postgres on a cold start, just re-run it (`docker compose -f compose/compose.yaml up flyway`). |
| `gateway` | `local/gateway-core:latest` | 8080/8081/8082/8083 | The actual service. **Not pulled from a registry** — build it first with `sbt "gatewayCore/Docker/publishLocal"`. |
| `wiremock` | `local/wiremock:latest` | (internal only) | Stubs external HTTP deps. Also built locally, not pulled — same `sbt` step covers it if you run the full `gateway-build`. |
| `mailhog` | `mailhog/mailhog:v1.0.1` | 1025 (SMTP), 8025 (UI) | Local email catcher — `application.conf` already points email at it by default. |
| `s3` | `adobe/s3mock:5.0.0` | 9090 | S3-compatible mock; auto-creates `organization-logo-bucket`. `application.conf`'s S3 client defaults to mock mode. |
| `waha` | `devlikeapro/waha-plus:gows-arm` | 3000 | Real WhatsApp HTTP API (not a mock/stub). Needs a WhatsApp session (QR scan) to actually send messages; the API key it uses is a fixed dev value already committed in `compose/waha-data/waha-env` — **that file is a committed secret**, don't treat it as sensitive or try to rotate it without checking with the team first. |

Bring up just the DB layer first if you want to run the gateway from sbt directly rather than in Docker:

```sh
docker compose -f compose/compose.yaml up -d postgres
docker compose -f compose/compose.yaml up flyway
docker compose -f compose/compose.yaml up -d mailhog s3 waha
```

## 3. Building & running

Module layout (`build.sbt`): the root aggregates `backend` (domain/clock/generator/test-kit/postgresql-test/s3-test/schemas/wiremock + the real service `backend-gateway-core`, plus acceptance-only `backend-gateway-it`) and `backend-waha` (`waha-core`, `waha-it`).

```sh
sbt compile              # compiles everything; smithy4s codegen runs automatically as part of this
sbt smithy4sCodegen       # standalone Smithy contract check (see docs-claude/features/flow/01-endpoints.md)
sbt "checkLint"           # scalafix + scalafmt, check only
sbt "runLint"             # scalafix + scalafmt, applies fixes
sbt "gateway-build"       # the CI alias: clean -> project backend -> checkLint -> testFull
sbt "waha-build"          # same shape, for the waha module
sbt "gateway-it/test"     # acceptance tests — auto-publishes the wiremock + gateway-core Docker images first; needs Docker running
```

**Run the gateway as a container** (closest to how it runs in prod):

```sh
sbt "gatewayCore/Docker/publishLocal"
docker compose -f compose/compose.yaml up
```

**Run the gateway straight from sbt** (faster inner loop, not containerized): bring up `postgres` + `flyway` + `mailhog` + `s3` + `waha` via compose (compose maps Postgres to `localhost:5432`), then `sbt "gatewayCore/run"` with `DATABASE_HOST`/etc. pointed at `localhost`. This path isn't documented anywhere else in the repo — treat it as a starting point, not a guarantee, if you hit issues.

## 4. External integrations — what's real vs. mocked locally

No real third-party credentials are needed to boot the gateway locally:

- **Email** → MailHog (`EMAIL_PROVIDER_HOST=mailhog:1025`), dev default already set in `application.conf`.
- **S3** → `adobe/s3mock`, mock mode on by default (`organization-logos-s3-client.use-mock = true`).
- **WhatsApp** → the real `waha` container, using a fixed dev API key — no external account needed, but sending actually requires a scanned WhatsApp session.
- **Twilio SMS** → **unclear / not mocked**. `application.conf`'s `twilio-client` defaults to `host=localhost, port=8080` with placeholder `account-sid`/`auth-token`, which looks like it's meant to be proxied through `wiremock`, but `wiremock` isn't exposed on a host port in `compose.yaml` and this isn't documented anywhere. If you need SMS locally, confirm the intended setup with the team rather than assuming.
- **OpenAI** → `open-ai-client.api-key` reads `OPENAI_API_KEY`, empty by default. Only needed for the AI-reply feature; not required to boot.

Every config value and its env-var override lives in `backend/gateway/core/src/main/resources/application.conf`.

## 5. Database migrations

Flyway migrations live in `backend/schemas/migrations/V<date>__<name>.sql` and are applied in lexical order — **not automatic on app startup**. Locally, the `flyway` compose service applies them (config: `backend/schemas/local/flyway/flyway.config`). In prod/dev environments, migrations ship via a separate path (`.github/workflows/job-flyway.yml`, a one-shot Docker/terraform job) — unrelated to local dev, which always goes through the compose service.

## 6. CI, for cross-reference

`.github/workflows/pipeline-gateway-ci.yml` and `pipeline-waha-ci.yml` both call the reusable `job-scala-build.yml`: checkout → `actions/setup-java` (temurin 25, full JDK) → `sbt/setup-sbt` → `sbt gateway-build` (or `waha-build`) → upload the built Docker image (gateway build only). CI's `gateway-it` tests spin up Postgres/wiremock via testcontainers rather than `docker compose`, so `compose.yaml` itself is purely a local-dev convenience, never invoked by CI.

## 7. Agent pipeline (optional)

This repo also has a 4-role Claude Code subagent pipeline (Product Owner → Engineering Manager → Lead Engineer → Senior Engineer) for working feature requests, invoked via `/feature "<description>"`. It runs on top of [OmniRoute](https://github.com/diegosouzapw/OmniRoute), a local AI gateway that routes each role's model calls across tiers (your Claude subscription, paid API keys, free providers) instead of hard-coding one model for everything — each engineer installs and configures their own local OmniRoute instance, it's not shared infrastructure. Full one-time setup (installing Node via nvm, installing OmniRoute, a known upstream CLI bug to avoid, dashboard/provider configuration, wiring Claude Code's env vars) is in [agent-pipeline-setup.md](../docs-claude/agent-pipeline-setup.md) — separate from everything above.
