# Refined newtypes — Mesazon specifics

Concrete values that fill in the placeholders in [iron-new.md](iron-new.md). Not a standard on its own — read each fact against the rule it instantiates there.

- Newtypes live in the single `backend/domain/.../Newtypes.scala` (package `io.mesazon.domain.gateway`), imported everywhere via `io.mesazon.domain.gateway.*`, grouped under `// <Feature Name>` comment headers.
- Shared enums broader than one feature — `OnboardStage`, `OrganizationUserRole` — get their own files rather than living in `Newtypes.scala`.
- See [adding-a-feature-new.md § Where shared types live](../adding-a-feature-new.md) for the full placement rule new features follow.
