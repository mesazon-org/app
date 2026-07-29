# Complexity contract

Classify after product unknowns resolve. Evaluate reasoning/novelty, affected layers/systems, risk/reversibility, data/compatibility, security/consistency, and blast radius—not LOC or typing time. Use the highest material trigger; ties choose higher and explain. Scope change ⇒ reclassify.

- `LOW`: bounded known-pattern change; one concern; no new contract/schema/state/security; low risk/blast radius.
- `MEDIUM`: contained new behavior; one/few layers; established architecture; limited migration/integration; moderate edge cases.
- `HIGH`: multi-layer feature; new contract + persistence/orchestration; auth/roles, transactions, external client, or broad cross-module impact.
- `EXTREME`: security/auth architecture; destructive/large data migration; financial/audit/history correctness; concurrency/distributed consistency; breaking/public contract; multi-service redesign; exceptional ambiguity or blast radius.

These are defaults, not exhaustive. Add stable examples/rules here as the taxonomy evolves.
