# Build changes

Read [agnostic sbt](../standards/sbt.md) plus this file only when changing dependencies, modules, tasks, plugins, Docker packaging, aliases, Scala/JDK versions, or CI build wiring.

Versions: sbt 2.0.2; Scala 3 metabuild; application Scala 3.8.4; Temurin JDK 25.

## Ownership

| File | Owns |
|---|---|
| `build.sbt` | bare common settings, module graph |
| `project/build.properties` | sbt |
| `project/plugins.sbt` | `_sbt2_3` plugins |
| `project/Dependencies.scala` | all `<lib>V` versions and `ModuleID`s; no inline build dependencies |
| `project/Settings.scala` | compiler flags (`-no-indent`, `-old-syntax`, `-experimental`, `--preview`, `-Wunused:all`, test suppressions), test-after-Docker wrapper |
| `project/Projects.scala` | `createBackendModule`, `.withDependencies` |
| `project/Aliases.scala` | lint/build aliases |
| `project/DockerSettings.scala` | gateway distroless Java 25 packaging |
| `project/DockerWiremockSettings.scala` | Wiremock image with `backend/wiremock/mappings/` baked in |
| `.sbtopts` | sbt JVM memory/GC and `-Dsbt.color=always` |

## Modules

- Location `backend/<module>` or `backend/<root>/<sub>`; ID `<root>-<sub>`; always use `createBackendModule(root)(subOpt)`.
- Service pattern: aggregate `<name>` → dockerized `<name>-core` + acceptance-only `<name>-it`.
- Lazy vals: `backend<Name>Module` or `backend<Service>Module{Root,Core,It}`.
- Infrastructure modules: `domain` (pure Iron/Cats), `clock`, `generator`, `test-kit` (all test libraries), `postgresql-test`, `s3-test`, `wiremock`, `schemas` (Flyway only).
- `.dependsOn` wires modules (one per line; `% Test` where appropriate); `.withDependencies(Dependencies.x, ...)` adds libraries. Version vals are `<lib>V`, grouped by ecosystem comment in `Dependencies.scala`.
- `Test / fork := true`; forked suites run in parallel except gateway-it's explicitly sequential shared stack.

## sbt 2 invariants

- Top-level bare settings apply to every subproject; never reintroduce `ThisBuild /`.
- `test` is incremental; `testFull` is uncached and bypasses the cache that survives `clean`. Never replace `test` with `Def.sequential`.
- Docker-dependent tests use `Settings.testAfterDockerPublish`, wrapping `test`, `testQuick`, `testOnly`, `testFull` through `.dependsOn`. Preserve input completion with `InputTask.mapTask`; `Def.task`/`Def.sequential` self-reference causes `RuntimeUndefined`; keep `testFull` `Def.uncached`.
- Side-effecting tasks are `Def.uncached`.
- `Classpath`/`mappings` contain `xsbti.HashedVirtualFileRef`, not `File`; use `fileConverter.value.toVirtualFile(path)`.
- Outputs use unified `target/out/jvm/scala-<ver>/<module>/`.
- Do not restate sbt 2 defaults: build auto-reload, test parallel execution, forked parallel tests.
- Pipelining spans `domain → generator → waha-core → gateway-core → gateway-it`. If a module defines macros, disable its `exportPipelining` or global pipelining; library inline/derivation/codegen does not count.
- Plugins must publish `_sbt2_3`; keep sbt-twirl `2.1.0-M9` until stable 2.1.0; Smithy plugin version equals `smithy4sV`.
- Multi-command CI uses one quoted string: `sbt "a; b"`.
- Test-name completion needs scoped `gateway-it / Test / testOnly` or selected project.
- Gateway Docker base is distroless `java25-debian13`; stage `2/` dependencies and `4/` app. Configure bare `java` through Terraform `JAVA_TOOL_OPTIONS`, not ignored `JAVA_OPTS`.

## Aliases and CI

| Alias | Purpose |
|---|---|
| `checkFmt` / `runFmt` | `scalafmtCheckAll; scalafmtSbtCheck` / `scalafmtAll; scalafmtSbt` |
| `checkFix` / `runFix` | `scalafixAll --check` / `scalafixAll` |
| `checkLint` / `runLint` | `checkFix; checkFmt` / `runFix; runFmt` |
| `<service>-build` | `clean; project <aggregate>; checkLint; testFull` |

Root registers `Aliases.all`. `.github/workflows/job-scala-build.yml` uses `<service>-build`, JDK 25, caches, and publishes Docker image via `DOCKER_IMAGE_TAG`/`DOCKER_REPOSITORY`; lint-on-compile is disabled because the alias runs it. A new service needs `pipeline-<name>-ci.yml`, its build alias, and a path filter modeled on `pipeline-waha-ci.yml`.

Known harmless stderr: Testcontainers 2.0.5 may emit post-suite `NoClassDefFoundError: ...PathUtils` through sbt 2's closed forked classloader while tests exit 0. `DockerComposeBase` drops only this exception. Do not “fix” it with sbt/env/log settings, `Tests.Setup`, classloader layering, preload, or disabled forking.

Validate the changed task/alias directly, then `sbt "checkLint"` and the affected `<service>-build`.
