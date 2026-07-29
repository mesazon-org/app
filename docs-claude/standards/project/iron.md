# Refined newtypes — Mesazon specifics

Read with the [agnostic refined-newtype rules](../agnostic/iron.md).

- Newtypes live in the single `backend/domain/.../Newtypes.scala` (package `io.mesazon.domain.gateway`), imported everywhere via `io.mesazon.domain.gateway.*`, grouped under `// <Feature Name>` comment headers.
- Shared enums broader than one feature — `OnboardStage`, `OrganizationUserRole` — get their own files rather than living in `Newtypes.scala`.
- See [adding-a-feature.md § Where shared types live](../../adding-a-feature.md) for the full placement rule new features follow.
