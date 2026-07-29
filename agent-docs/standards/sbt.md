# sbt

Reusable Scala 3/sbt 2 multi-module rules. Mesazon values: [build](../project/build.md). Tests: [repository](../features/flow/04-repository.md), [service](../features/flow/05-service.md), [external clients](../project/external-client.md).

## Metabuild conventions

- Version-control the build-tool, application-language, and JDK versions.
- Format/lint build files, plugins, and helper sources with the application tooling/dialect.

## Dependency version centralization

- Put every third-party version in one dependency file; never inline versions in modules. Add libraries through a named wrapper such as `withDependencies(...)`.
- Give compiler flags, packaging/images, aliases, and dependencies one centralized owner file each.

## Module creation through a single helper

- Create every application/infrastructure module through one helper applying cross-cutting settings.
- Enforce directory, project-ID, and `lazy val` naming through that helper.

## Build-tool-version-specific rules

- Use the current version's all-subproject settings mechanism; never restore its deprecated predecessor.
- CI uses the non-incremental "run all tests" task; cached incremental tests are local-only.
- When tests require external artifacts/services, attach prerequisites to every test entrypoint. Verify shared prerequisites execute once and argument completion remains intact.
- Mark every side-effecting custom task uncached.
- Target tooling/ignores at the current version's real output tree.
- Do not explicitly set behaviors that are already defaults.
- Compilation pipelining is invalid for a module defining macros: disable that module's `exportPipelining` (or pipelining globally). Inline/derivation/codegen consumers are not macro definitions.
- Plugins must publish for the pinned build-tool major version. If only a prerelease is compatible, pin it and record why/when to upgrade.
- Pass multi-command CI invocations in the build tool's required form (e.g. one quoted `;`-separated string).
- If unscoped shell completion no longer delegates, use a fully scoped task key.

## Known harmless test noise

Document known harmless test noise with signature, cause, proof it is benign, working mitigation, and rejected fixes.

## Command aliases as CI/local parity

- CI invokes the same small-step alias used locally; no CI-only raw command sequence.
- Provide separate read-only check and mutating fix aliases.
- One CI alias per deployable: reset → scope → read-only quality gate → full non-incremental tests.

## CI wiring

- Run the deployable alias on the pinned JDK with dependency/artifact caching.
- Publish artifacts using environment-injected repository/tag values.
- Disable lint-on-compile in CI; the alias's explicit lint gate owns the signal.
- New deployable: pipeline + CI alias + path filters matching an existing pipeline.
