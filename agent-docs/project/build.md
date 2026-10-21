# Build changes

For dependencies, modules, tasks, plugins, Docker packaging, aliases, Scala/JDK versions, or CI build wiring, read [sbt](../standards/sbt.md) and this file.

Versions: sbt 2.0.11; Scala 3.9.0 application (sbt 2.0.11 uses a Scala 3.8.4 metabuild); Temurin JDK 25.

sbt 2.0.11 includes the upstream [SemanticDB cache-restoration fix](https://github.com/sbt/sbt/releases/tag/v2.0.11), which was initially verified here on 2.1.0-RC1 and was absent from 2.0.10. Run `sbt "clean; runLint"` once after upgrading from an affected version to remove old linked outputs; see [known issues](../known-issues.md#sbt-2-compile-cannot-overwrite-a-cache-linked-semanticdb-file).

## Dependency update batch

The current update takes every open dependency PR reviewed on 2026-10-21 into this branch. Versions remain centralized in `project/Dependencies.scala`, with the packaging plugin in `project/plugins.sbt`.

| Dependency | Version | Source PR |
|---|---|---|
| Iron integrations | `3.4.1` | [#581](https://github.com/mesazon-org/app/pull/581) |
| jsoniter-scala | `2.41.7` | [#579](https://github.com/mesazon-org/app/pull/579) |
| Simple Java Mail | `9.3.6` | [#578](https://github.com/mesazon-org/app/pull/578) |
| STTP client4 | `4.1.0` | [#576](https://github.com/mesazon-org/app/pull/576) |
| PureConfig | `0.17.11` | [#575](https://github.com/mesazon-org/app/pull/575) |
| Scrimage | `4.6.9` | [#573](https://github.com/mesazon-org/app/pull/573) |
| AWS SDK S3 | `2.55.16` | [#572](https://github.com/mesazon-org/app/pull/572) |
| ZIO | `2.1.27` | [#571](https://github.com/mesazon-org/app/pull/571) |
| Tapir | `1.13.33` | [#563](https://github.com/mesazon-org/app/pull/563) |
| libphonenumber | `9.0.41` | [#562](https://github.com/mesazon-org/app/pull/562) |
| sbt-native-packager | `1.13.0` | [#561](https://github.com/mesazon-org/app/pull/561) |
| Circe | `0.14.17` | [#559](https://github.com/mesazon-org/app/pull/559) |
| Chimney | `2.1.0` | [#556](https://github.com/mesazon-org/app/pull/556) |

STTP AI stays at `0.11.4` ([#580](https://github.com/mesazon-org/app/pull/580) not taken): 0.12.0 needs sttp-apispec 0.12.0 and `tapir-apispec-docs` 1.14.0, while Tapir 1.13.33 asks for sttp-apispec 0.11.10, so taking it would require a dependency override. Updates that need an override of other libraries are held back. Tapir 1.14.0 would remove that conflict.

Verification of this dependency set: `sbt "clean; compile"`, `sbt "Test/compile; gateway-it/Test/compile"`, `runLint`, the gateway functional tests (`gateway-core/testOnly io.mesazon.gateway.fun.*`), and `show gateway-core/evicted` (no override needed) passed. `gateway-core/evicted` resolves sttp-apispec at 0.11.10 with no conflict; it does report `sttp.client4:zio` 4.0.26 (requested by sttp-ai 0.11.4) evicted by 4.1.0 as suspected binary-incompatible, which resolution accepts without an override. `gateway-build` and `waha-build` passed: 512 gateway core tests, 282 gateway HTTP tests, and seven Waha tests. Fourteen manual live-AI golden cases were cancelled because no API key was configured; they are not passing evidence. The reported Catalogue batch non-member EOF did not reproduce in that single gateway run, which does not prove it fixed; its [investigation remains open](../known-issues.md#catalogue-batch-rejection-closes-the-connection-before-an-http-response).

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
- Infrastructure modules: `domain` (Iron/Cats/ZIO core for non-empty domain collections), `clock`, `generator`, `test-kit` (all test libraries), `postgresql-test`, `s3-test`, `wiremock`, `schemas` (Flyway only).
- `.dependsOn` wires modules (one per line; `% Test` where appropriate); `.withDependencies(Dependencies.x, ...)` adds libraries. Version vals are `<lib>V`, grouped by ecosystem comment in `Dependencies.scala`.
- `Test / fork := true`; forked suites run in parallel except gateway-it's explicitly sequential shared stack.

## sbt 2 invariants

- Top-level bare settings apply to every subproject; never reintroduce `ThisBuild /`.
- `test` is incremental; `testFull` is uncached. In sbt 2.1, `clean` also invalidates the scoped cache for the next command. Never replace `test` with `Def.sequential`.
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

Known harmless stderr: Testcontainers 2.0.5 may emit post-suite `NoClassDefFoundError: ...PathUtils` through sbt 2's closed forked classloader while tests exit 0. `DockerComposeBase` drops only this exception. Do not “fix” it with sbt/env/log settings, `Tests.Setup`, classloader layering, preload, or disabled forking. See the [known-issue diagnosis and fix](../known-issues.md#testcontainers-shutdown-hook-cannot-load-pathutils).

Validate the changed task/alias directly, then `sbt "checkLint"` and the affected `<service>-build`.
