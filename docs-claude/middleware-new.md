# HTTP middleware — authentication & authorization

How a service gets its auth without every endpoint re-implementing it. The rule of thumb: **handlers never check credentials** — by the time a service method runs, the caller is authenticated, authorized, and available via a request-scoped auth state. What gets checked is declared in the API contract and enforced centrally by the server middleware.

## Scope

**Owns** — the project-agnostic standard for centralized, contract-declared authentication and authorization: a single server middleware (or filter/interceptor, depending on the framework) that reads the auth metadata attached to an endpoint by the API contract and enforces it before the handler runs, so no handler re-implements a credential check. Specifically:

- dispatching between auth schemes (e.g. basic vs bearer) based on what the contract declares per service;
- the onboarding/lifecycle-stage gate that blocks an otherwise-authenticated request until a prerequisite stage is reached;
- the organization/tenant-role gate that restricts an operation to callers holding one of a declared set of roles within a scoped resource;
- the request-scoped auth-state mechanism a handler reads to learn "who is calling", instead of re-deriving it;
- keeping the same checks, and the same status codes, in parity across every transport a service exposes.

**Excludes**, with ownership handed to the doc that covers it:

- how auth requirements are *declared* in the API contract — trait/annotation choice, service-level vs operation-level placement, the role-policy convention for reads vs mutations → [stack/smithy-new.md](stack/smithy-new.md);
- the alternate transport that cannot hook into the shared middleware and must hand-mirror the same checks in its own security logic → [stack/tapir-new.md](stack/tapir-new.md);
- the acceptance-test error matrix that proves every gate end-to-end (every status code, every missing/invalid header, every disallowed role) → [acceptance-tests-new.md](acceptance-tests-new.md);
- general naming conventions for the services, types, and test data involved → [stack/scala-new.md](stack/scala-new.md);
- feature-specific token lifecycle details (issuance, refresh, revocation) → [features/user-token-management.md](features/user-token-management.md).

## Table of contents

- [Scope](#scope)
- [Where it hooks in](#where-it-hooks-in)
- [Dispatch table](#dispatch-table)
- [Basic auth — the authentication service (sign-in)](#basic-auth--the-authentication-service-sign-in)
- [Bearer auth — the authorization service](#bearer-auth--the-authorization-service)
- [Auth state — how handlers learn who is calling](#auth-state--how-handlers-learn-who-is-calling)
- [Organization permissions — role trait + scope header](#organization-permissions--role-trait--scope-header)
- [Known gaps](#known-gaps)

## Where it hooks in

The server middleware attaches to **every contract-generated route** in the framework's endpoint pipeline. For each endpoint the contract-codegen layer hands the middleware the auth traits declared on that service/operation as typed hints, and the middleware returns a wrapper that runs before the endpoint's handler.

A transport that cannot be routed through the generated pipeline (for example, a streaming file-upload endpoint built directly against the HTTP library) is **not** covered by this middleware: it must wire the same checks explicitly in its own security logic — the bearer token and the tenant-scope header are declared as typed security inputs, and each endpoint passes its own allowed roles; the decoded scope identifier becomes the security principal handed to the handler. **When a rule is added to the middleware, that hand-written security logic must be extended by hand to match.** See [stack/tapir-new.md](stack/tapir-new.md).

Cross-transport consistency, per header:

| Condition | Status (both transports) | Notes |
|---|---|---|
| Missing bearer credential | **401 Unauthorized** | The contract's own missing-auth-header error on the generated route; the alternate transport's default for a missing auth security input |
| Missing tenant-scope header (e.g. `X-Organization-ID`) | **400 Bad Request** | The contract's own missing-header error on the generated route; the alternate transport's default for a missing plain header |
| Disallowed role | 403 Forbidden | Same on both |
| Missing membership row | 500 | Same on both |
| Malformed tenant-scope header value (e.g. not a valid UUID) | **400** on the alternate transport, but **500** on the generated route | One remaining asymmetry — the alternate transport's decode-failure handler rejects it before the handler runs; the generated route's own decoding treats it as an unexpected internal error |

The alternate transport's decode-failure handler keeps its own chosen status as-is for header-shape errors — no adjustment needed to match the generated route.

## Dispatch table

The middleware branches on the auth traits declared on the service:

| Contract auth traits on the service | Behavior |
|---|---|
| Basic-auth trait only | The authentication service runs first (credentials sign-in) |
| Bearer-auth trait only | The authorization service runs first (token verification, onboard-stage gate, org-role gate) |
| Neither | Pass-through (public endpoints: sign-up, forgot-password, token refresh, health) |
| Both | Rejected at request time with an internal-server error (unsupported combination) |

Operation-level overrides that try to opt an individual operation out of its service's declared auth are unsupported and also produce an internal-server error — auth is declared per **service**, never per operation.

## Basic auth — the authentication service (sign-in)

Runs for services that declare basic auth (typically exactly one sign-in service). Full flow, in order:

1. Extract `Authorization: Basic` credentials → **401 Unauthorized** (missing-auth-header error) if absent; validate the email/password format.
2. Look up the user by email → unauthorized if unknown.
3. Onboard stage must be in the set of stages sign-in is allowed from.
4. Brute-force guard via a sign-in attempt repository (attempt type: sign-in): over a configured attempt threshold within a configured block duration → a dedicated "too many sign-in attempts" error.
5. Password verification (e.g. Argon2); success deletes the attempt counter.
6. Set the request-scoped auth state to the authenticated user.

## Bearer auth — the authorization service

Runs for services that declare bearer auth, and for the alternate transport's hand-mirrored security logic:

1. Extract the `Bearer` token → **401 Unauthorized** (missing-auth-header error, naming the `Authorization` header) if absent — **a missing bearer credential is 401 on both transports** (the alternate transport's default for a missing auth security input); 401 also covers credentials that are present but fail verification.
2. Verify the access token — signature, expiry, issuer (access tokens are stateless; see [features/user-token-management.md](features/user-token-management.md)).
3. If the service declares the completed-onboard-stage trait: load user details and require the stage to be in the set of "completed" stages, else a dedicated invalid-onboard-stage error → **403 Forbidden** (this applies to every onboard-stage verification call, including in-handler stage checks elsewhere in the flow — e.g. sign-up, onboarding, forgot-password — and the sign-in stage check in the authentication service above).
4. If the operation declares the organization/tenant-roles-allowed trait: run the organization role check (next section).
5. Set the request-scoped auth state to the authenticated user.

## Auth state — how handlers learn who is calling

A request-scoped auth-state mechanism (e.g. a fiber-local or request-local `Option[AuthedUser]`) holds the outcome. The middleware sets it after successful auth; service implementations read it as their first step. Reading it on an unauthenticated request path is a defect, not a recoverable error — it can only happen if a handler is reachable without the middleware in front of it, which is a wiring bug and should fail loudly (die/crash) rather than be handled as a normal error.

## Organization permissions — role trait + scope header

Each contract **operation** declares which tenant/organization roles may call it (an "allowed roles" trait, parameterized per operation) — operation-level, not service-level, so permissions can differ per endpoint within one service. The project-wide policy convention (owned by [stack/smithy-new.md](stack/smithy-new.md)) is: reads allow every role including the least-privileged one — a plain member may always view data — mutations allow only elevated roles, and deleting the scoped resource itself allows only the most-privileged role. Every tenant-scoped operation carries the tenant identifier in a **required scope header** (e.g. `X-Organization-ID`), declared once via a shared input mixin composed into each operation's input, never in the body or the URI path — a fixed header is readable by the middleware without parsing bodies, and it works identically for reads, JSON writes, and streaming uploads. The authorization service reads the header straight off the raw request, independent of the decoded operation input.

Enforcement, mirroring the completed-onboard-stage mechanism above:

1. The middleware reads the "roles allowed" hint from the **endpoint's hints**, maps the contract-level roles to the domain role enum, and passes them into the authorization service's role-check parameter.
2. The request-level overload parses the `Authorization` bearer into a typed access token and the scope header into a typed optional tenant identifier before delegating to the token-level overload; a malformed header value (e.g. not a valid UUID) fails as an internal-server error (**500**) on the generated route.
3. The role-verification step (run after token verification and onboard-stage verification) treats a header absent when roles are required as a missing-header error → **400 Bad Request**.
4. It loads the caller's membership in the scoped resource. No membership row found → internal-server error (**500** — the project's missing-referenced-entity convention). A member whose role is not among the declared allowed roles → a dedicated invalid-role error (carrying the caller's actual role) → **403 Forbidden** (authenticated but not allowed — distinct from 401).
5. Operations that do not declare the trait skip the check entirely.

Cover this with a unit spec against the authorization service (allowed role, missing header, invalid header, not a member, disallowed role) and with acceptance cases against a real tenant-scoped endpoint (missing scope header → 400, missing token → 401, non-member → 500, disallowed role → 403). See [acceptance-tests-new.md](acceptance-tests-new.md).

A streaming-upload endpoint on the alternate transport follows the same standard even though it cannot use the shared middleware: the tenant identifier moves from the path to the scope header, the hand-written security logic requires the same elevated roles as the equivalent write operation elsewhere in the contract, and the parsed tenant identifier is handed to the handler as the security principal. See [stack/tapir-new.md](stack/tapir-new.md).

## Known gaps

- The middleware itself commonly ends up with no direct tests of its own — worth flagging with a TODO and a tracked follow-up issue rather than leaving it silently uncovered; it is exercised indirectly by every acceptance spec's error matrix (see [acceptance-tests-new.md](acceptance-tests-new.md)). The organization-role logic itself should still be unit-tested directly against the authorization service.
