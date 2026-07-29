# Feature flow

Use this router for a new feature or endpoint. Read only the current slice guide, its linked agnostic standards, and the feature doc. Each PR is independently reviewable and includes its applicable tests; never defer a slice's tests.

## PR sequence

| PR | Guide | Output | Required proof |
|---|---|---|---|
| 1 | [Endpoints](01-endpoints.md) | Smithy (default) or Tapir endpoint contract plus transport models; feature doc created | codegen/compile and contract docs |
| 2 | [Validation](02-validation.md) | validated domain models, newtypes, arbitraries, validator | success + accumulated-error unit tests per validator function |
| 3 | [Schema](03-schema.md) | Flyway DDL, constraints/indexes, table config only | migration/schema smoke |
| 4 | [Repository](04-repository.md) | persistence types, Row, Queries, Repository, codecs, layer definitions | real-Postgres tests for every query/repository method and DB constraint |
| 5 | [Service](05-service.md) | orchestration and full endpoint implementation/wiring | functional branches + acceptance happy/error matrix per endpoint |

Skip an inapplicable slice (e.g. read-only/no schema) and record `N/A` in the feature doc. Do not combine slices merely to reduce PR count; combine only when a slice would otherwise be non-functional noise.

Domain types are not a separate PR. Add each type in the earliest slice whose code needs it:

- transport request/response/error models: endpoint PR;
- validated request models/newtypes and types shared with the repository: validation PR;
- persistence-only Row/input/projection types: repository PR;
- schema PR contains DDL/config only, no Scala persistence layer.

Keep code grouped by feature and concern: one class/trait per file except the intentional `<Feature>.scala` request-model group. Use feature validators/arbitrary traits; do not grow generic kitchen-sink validators or arbitrary traits.

## Feature doc: mandatory in PR 1

Create `docs-claude/features/<feature-name>.md` before or with the first endpoint slice, link it in `AGENTS.md`, and update it in every PR. Never wait for the final service PR.

Minimum structure:

```markdown
# <Feature>

**Status**

| Slice | Done | Remaining |
|---|---|---|
| Endpoints | ... | ... |
| Validation | ... | ... |
| Schema | ... | ... |
| Repository | ... | ... |
| Service | ... | ... |

## Scope
Owns / excludes / boundary links.

## Endpoints
Method, URI, auth, onboard stage, organization roles.

## Flow and decisions
Security/abuse defenses and non-obvious decisions.

## Key files and config

## Tests
Unit, functional, integration, acceptance.
```

Keep `Status` until every slice is shipped and tested; then replace it with a concise completed statement. A rename of any documented error/type/endpoint/config/file updates the feature doc, all `docs-claude/`, and `AGENTS.md` in the same PR.

## Shared file placement

- Endpoint contract/models: Smithy by default (`backend/gateway/core/src/main/smithy/<Feature>Service.smithy` + `smithy/domain/<Feature>.smithy`); Tapir only when Smithy cannot express the transport.
- Request domain models: `backend/domain/src/main/scala/io/mesazon/domain/gateway/<Feature>.scala`.
- All refined newtypes: shared `Newtypes.scala`, grouped under a feature comment.
- Enums/models broader than one request: own file named after the type.
- Validator: `validation/service/<Feature>RequestValidator.scala`.
- Service: `service/<Feature>Service.scala`.
- Repository: `repository/domain/*Row.scala`, `repository/queries/*Queries.scala`, `repository/<Feature>Repository.scala`.
- Feature arbitraries: `<Feature>DomainArbitraries` in test-kit and `<Feature>SmithyArbitraries` in gateway-core test utilities; never add feature-specific givens to the shared generic traits.

## Every PR

1. Apply the current slice guide and linked agnostic standards.
2. Add/update the slice's tests in the same PR.
3. Update the feature doc status, files, config, decisions, and tests.
4. Run `sbt "runLint"` then the slice-specific command.
