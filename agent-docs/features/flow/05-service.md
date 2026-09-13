# Slice 5 — Service implementation

Combine the endpoint, validation, schema, and repository layers into the working service. Add orchestration, endpoint implementation/wiring, functional tests, and black-box acceptance tests. Read [Scala](../../standards/scala.md), the feature doc, and the preceding applicable flow guides.

## Implementation boundary

- `service/<Feature>Service.scala` implements the generated Smithy service; a Tapir feature supplies its handler/security wiring through its endpoint module.
- Normal authenticated handler flow: `AuthState.get → validate → map domain request to repository input → repository/client calls → map Rows/domain values to Smithy response`.
- Auth/role/onboard checks belong to middleware/security declarations and `AuthState`, not hand-written credential checks in handlers. For auth machinery changes read [Authentication](../../project/authentication.md).
- Use `.local` for the raw `ServiceTask` implementation. `.observed` translates/logs `ServiceError` as Smithy errors; `.live` is production wiring.
- Wire the service, validator, repository/queries, clients, and generated routes into the application graph. Preserve exact organization scoping and `OptUpdate` semantics.
- Every declared contract error must map to the intended HTTP status. Missing referenced rows follow existing feature policy; record non-obvious behavior in the feature doc.

## Functional tests: one service, dependencies mocked

Follow [Functional testing](../../project/functional-testing.md), the single source of truth for functional-test boundary, harness/setup, structure/naming, expectation/sequencing rules, config/time pinning, coverage expectations (including which branches count as "service-owned" versus behavior that only lives inside a mocked dependency), review, and verification.

## Acceptance tests: real app over HTTP

Follow [Acceptance testing](../../project/acceptance-testing.md), the single source of truth for acceptance structure, endpoint matrices, middleware cases, naming/layout, harness wiring, clients/codecs, assertions, review, and verification.

The service slice is complete only when every endpoint's applicable acceptance matrix passes against the real gateway and dependencies. Update the feature doc with exact completed and remaining cases; never remove or falsely complete its status.

If the feature added an external dependency, credential, or required env var (a new bucket, third-party API key, etc.), also update terraform in this PR — see [Terraform](../../project/terraform.md).
