# Complexity contract

Classify after product unknowns resolve. Evaluate reasoning/novelty, affected layers/systems, risk/reversibility, data/compatibility, security/consistency, and blast radius—not LOC or typing time. Use the highest material trigger; ties choose higher and explain. Scope change ⇒ reclassify.

- `LOW`: bounded known-pattern change; one concern; no new contract/schema/state/security; low risk/blast radius.
- `MEDIUM`: contained new behavior through multi-layer feature; established architecture through new contract + persistence/orchestration; limited migration/integration through auth/roles, transactions, external client, or broad cross-module impact; moderate edge cases.
- `HIGH`: security/auth architecture; destructive/large data migration; financial/audit/history correctness; concurrency/distributed consistency; breaking/public contract; multi-service redesign; exceptional ambiguity or blast radius.

These are defaults, not exhaustive. Add stable examples/rules here as the taxonomy evolves.

## Stable examples

- **New external client instance, even one that mirrors an existing client's code 1:1** (e.g., a second S3 client for a new entity, modeled on an existing S3 client) → `MEDIUM`, not `LOW`. The "external client" trigger is about the client *instance* (its own config, DI wiring, credentials, and required test-infrastructure provisioning — a new mock bucket/stub), not about code novelty. Mirrored source is not proof the client works: a missing mock-bucket registration, missing env var, or missing DI layer only fails at acceptance-test runtime against real/mocked infra, never at compile time or in a functional (mocked-dependency) test. Do not downgrade because "it's just like the existing one" — that similarity reduces design risk, not integration risk.
