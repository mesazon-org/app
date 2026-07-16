# sbt Build Definition

The build runs on **sbt 2.0.2** (Scala 3 metabuild — everything under `project/` and `build.sbt` is Scala 3 syntax, formatted with the `scala3` scalafmt dialect). Scala version for all modules: **3.8.4**, JDK 21 (temurin, see `.tool-versions`).

## File map

| File | Owns |
|---|---|
| `build.sbt` | Common (bare) settings, module graph, `testAfterDockerPublish` helper |
| `project/build.properties` | sbt version |
| `project/plugins.sbt` | Plugins (all must publish `_sbt2_3` artifacts) |
| `project/Dependencies.scala` | Every library version (`lazy val fooV`) and `ModuleID` — **all dependency changes happen here, never inline in build.sbt** |
| `project/Settings.scala` | `Settings.ScalaCompiler`: tpolecat scalac options (`-no-indent`, `-old-syntax`, `-experimental`, `--preview`, `-Wunused:all`) + test-only warning suppressions |
| `project/Projects.scala` | `ProjectOps.withDependencies(deps*)` extension used instead of raw `libraryDependencies ++=` |
| `project/Aliases.scala` | Command aliases: `checkLint`/`runLint` (fix+fmt), `gateway-build`, `waha-build` (CI entrypoints: `clean; project <x>; checkLint; test`) |
| `project/DockerSettings.scala` | `DockerSettings.compileScope`: distroless java21 image for gateway-core (native-packager stage layers `2/` = dependency jars, `4/` = app jars) |
| `project/DockerWiremockSettings.scala` | Wiremock image with `backend/wiremock/mappings/` baked in |
| `.sbtopts` | sbt server JVM memory/GC only |

## sbt 2 specifics baked into this build (do not regress)

- **Bare settings replace `ThisBuild`**: top-of-`build.sbt` bare settings are injected into *all* subprojects. Never reintroduce `ThisBuild /`.
- **`test` is an `InputKey[TestResult]`** and incremental (skips unchanged passing suites; `testFull` forces all; the incremental result cache lives outside `target/`, so it survives `clean`). You cannot `test := Def.sequential(...).value`. Modules needing docker images before tests use `Settings.testAfterDockerPublish(<publishLocal tasks>*)` (in `project/Settings.scala`), which wraps the executors `testQuick`, `testOnly`, and `testFull` via `.dependsOn` so the images publish before tests run through any entrypoint. `test` is deliberately left un-wrapped: it delegates to `testQuick` (`test := testQuick.evaluated`), so wrapping it too would publish twice. `testFull` is wrapped in `Def.uncached` (mirroring sbt's default) because it is a plain, non-cached task. Key gotcha: augment these keys only via the canonical `key := (Test / key).dependsOn(...).evaluated` / `.value` form, which resolves to the key's previous value; wrapping the self-reference inside `Def.sequential`/`Def.task` instead is treated as a real cycle and fails at runtime with `RuntimeUndefined`.
- **`Classpath`/`mappings` carry `xsbti.HashedVirtualFileRef`, not `File`** — convert with `fileConverter.value.toVirtualFile(path)` (see `DockerWiremockSettings`).
- **All tasks are cached by default**; side-effecting custom tasks must be wrapped in `Def.uncached(...)`.
- **Unified target**: outputs live in `target/out/jvm/scala-<ver>/<module>/`, not `<module>/target/`.
- Settings intentionally *not* set because they are sbt 2 defaults: auto-reload on build change, `Test / parallelExecution`, `Test / testForkedParallel`.
- `usePipelining := true` speeds up multi-module compile; it is safe only while no module defines macros — if a module ever defines a macro, revisit.
- Plugins must have sbt 2 (`_sbt2_3`) artifacts. sbt-twirl must stay on the 2.1.x line (2.0.x has no sbt 2 build). smithy4s plugin version must equal `smithy4sV` in `Dependencies.scala`.
- CI multi-command invocations must be a single quoted string: `sbt "a; b"` (old `sbt a b` form fails).

## Module structure & naming conventions

- Directory layout: `backend/<module>` or `backend/<root>/<sub>`; project ID = `<root>-<sub>` (e.g. dir `backend/gateway/core` → id `gateway-core`). Enforced by `createBackendModule(root)(subModuleOpt)` in `build.sbt` — always create new modules through it (it also applies `Settings.ScalaCompiler`).
- Service roots follow the pattern `<name>` aggregate → `<name>-core` (implementation, dockerized) + `<name>-it` (acceptance tests only). Existing: `gateway`, `waha`.
- `lazy val` names in build.sbt: `backend<Name>Module` / `backend<Service>Module{Root,Core,It}`.
- Shared infrastructure modules: `domain` (pure types, iron+cats only), `clock`, `generator`, `test-kit` (all test libs; other test modules depend on it), `postgresql-test`, `s3-test`, `wiremock` (dockerized stub server + mappings), `schemas` (flyway SQL, no Scala).
- Dependency style: `.dependsOn(x)` for module wiring (one per line), `% Test` for test-only wiring, `.withDependencies(Dependencies.foo, ...)` for libraries. Version vals in `Dependencies.scala` are named `<lib>V` and grouped by ecosystem with a comment header.
- Test forking is on (`Test / fork := true`); forked suites run in parallel.

## CI

`.github/workflows/job-scala-build.yml` runs `sbt <module>-build` (alias) on JDK 21 with `setup-java` sbt caching (covers coursier) and uploads the docker image built by `Docker / publishLocal` (tag from `DOCKER_IMAGE_TAG` env, repo from `DOCKER_REPOSITORY`). Lint-on-compile is env-gated via `ENABLE_SCALA_LINT_ON_COMPILE` (off in CI compile; `checkLint` runs explicitly in the alias). New services need: a `pipeline-<name>-ci.yml`, a `<name>-build` alias in `Aliases.scala`, and path filters mirroring `pipeline-waha-ci.yml`.

## Common commands

- `sbt "gateway-build"` / `sbt "waha-build"` — what CI runs
- `sbt "checkLint"` / `sbt "runLint"` — scalafix + scalafmt check/apply (includes `*.sbt` via `scalafmtSbt`)
- `sbt "gateway-it/test"` — acceptance tests (auto-publishes wiremock + gateway-core docker images first; needs Docker running)
- `sbt "testFull"` — force-run tests skipped by incremental `test`
