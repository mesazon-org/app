# Customer Book

Owns the client's address book of the people and companies they do business with. Two entities and one link:

- **Customer** (`customer_details`) — a *person* the client deals with (name, contact, address).
- **Counterparty** (`counterparty_details`) — the *party an order is billed to*: either a single individual or a shared B2B account. Every order (a later feature) targets a `counterparty_id`, never a `customer_id`.
- The link — each customer points at **exactly one** counterparty via `customer_details.counterparty_id` (a `not null` FK). One-to-many: a counterparty holds one-or-more customers.

**Vocabulary (read this first).** "Organization" in this codebase is the **Mesazon tenant** (the client themselves — see [Organization Management](organization-management.md)); it is *not* the customer's company. The customer's company is a **Counterparty**. Every row here is scoped by `organization_id` (the tenant), carried in the `X-Organization-ID` header on every endpoint.

**Scope**: customer + counterparty rows, their one-to-many link, the `INDIVIDUAL`/`BUSINESS` distinction, and the archival lifecycle. **Excludes** orders (a future feature that will FK `counterparty_id` and snapshot buyer details — see the order-snapshot rules in [postgres.md](../postgres.md#soft-delete--archival)) and the tenant/membership/role model ([Organization Management](organization-management.md)).

## Data model

```
counterparty_details (organization_id, counterparty_id)  PK
  counterparty_type  INDIVIDUAL | BUSINESS
  name               null for INDIVIDUAL, set for BUSINESS
  status             ACTIVE | ARCHIVED
        ▲
        │ counterparty_id  (not null FK, one-to-many)
        │
customer_details (organization_id, customer_id)  PK
  counterparty_id    → counterparty_details
  full_name          the person's name (an INDIVIDUAL's identity lives here)
  status             ACTIVE | ARCHIVED
```

### Individual vs business counterparty

A counterparty is one of two kinds, discriminated by `counterparty_type` (see [postgres.md § Counterparty type](../postgres.md#counterparty-type--individual-vs-business)):

- **`INDIVIDUAL`** — auto-created to wrap exactly **one** standalone customer so that person has an order target. It has no business `name` (null); the human's identity is the linked customer's `full_name`.
- **`BUSINESS`** — a named account the client created explicitly; **one-or-more** customers point at it.

"Is this customer a single entity?" is **not stored** — it's derived: a customer is a single entity iff its counterparty is `INDIVIDUAL`.

### Why one FK column, not a join table

The link is one-to-many and **mandatory** — every customer must have an order target. `customer_details.counterparty_id not null` enforces *exactly one* structurally; a many-to-many join table (even with a `unique` constraint) would allow *zero*. It also makes reassignment a single `UPDATE` and the grouped read a `GROUP BY`. This replaced an earlier `counterparty_customer` join table.

## Endpoints

All under `CustomerBookService`, `@completedOnboardStage` (Bearer + completed onboarding), org-scoped via `X-Organization-ID`.

| Method | Path | Roles | Purpose |
|---|---|---|---|
| GET | `/get/customer/{customerID}` | OWNER, ADMIN, USER | Fetch one customer (`GetCustomerGet`) |
| GET | `/get/customers` | OWNER, ADMIN, USER | List customers **grouped by counterparty** (`GetCustomersGet`) |
| POST | `/insert/customers` | OWNER, ADMIN | Batch-add customers (`InsertCustomersPost`) |
| PUT | `/update/customers` | OWNER, ADMIN | Batch-edit customers, incl. reassignment (`UpdateCustomersPut`) |
| POST | `/delete/customers` | OWNER, ADMIN | Batch-**archive** customers (`DeleteCustomersPost`) |

Smithy: `backend/gateway/core/src/main/smithy/CustomerBookService.smithy` (+ `domain/CustomerBook.smithy`). Reads are open to every member; writes are `OWNER`/`ADMIN` only (per `@organizationUserRolesAllowed`).

`GetCustomersGetResponse.customers` is a `GetCustomersMap` keyed by `CounterpartyID` → the list of that counterparty's customers, so the single list call renders both lone individuals (a counterparty with one, unnamed) and business accounts (a counterparty with several) in one view.

## Lifecycle

### Create a customer (`InsertCustomer`)
`InsertCustomer.counterpartyID` is optional and drives the branch:

- **Provided** (an existing `BUSINESS`) → insert the customer pointing straight at it. No `INDIVIDUAL` is created.
- **Absent** (standalone person) → create an `INDIVIDUAL` counterparty (`name` null), then insert the customer pointing at it.

Both write in **one transaction**; customer inserted `ACTIVE`.

### Reassign a customer (`UpdateCustomer`)
Set `UpdateCustomer.counterpartyID` to move the customer. In one transaction:

- **Off an `INDIVIDUAL`** (standalone → business): repoint, then **archive the now-orphaned `INDIVIDUAL`** (`status → ARCHIVED`). An `INDIVIDUAL` has exactly one customer, so it's unreferenced after the move — archived (not deleted) so any orders already placed against it keep resolving.
- **Between `BUSINESS` accounts**: repoint only; never archive the old one (other customers may remain).

### Archive a customer (`DeleteCustomersPost`)
"Delete" is a **soft delete** — flip `customer_details.status → ARCHIVED`, never a row removal, because orders reference the counterparty and history must survive. Reads default to `status = 'ACTIVE'`; archived rows sort last via `ORDER BY (status <> 'ACTIVE'), ...`. See [postgres.md § Soft-delete & archival](../postgres.md#soft-delete--archival).

## Security / design decisions

- **Org isolation via composite keys.** Both tables are PK'd `(organization_id, <entity>_id)` and the customer→counterparty FK is the composite `(organization_id, counterparty_id)` — Postgres matches an FK by column set, so a customer can only ever link to a counterparty **in the same tenant**. A caller cannot reference another org's counterparty.
- **Never hard-delete an entity that can carry orders.** Both `customer_details` and `counterparty_details` are archived, not deleted; orders (future) FK the counterparty with `on delete restrict` as a belt-and-suspenders guard.
- **`INDIVIDUAL.name` nullability is an app-level invariant** (`BUSINESS` ⇒ name present, `INDIVIDUAL` ⇒ name null), not a DB `check` — consistent with this schema staying permissive at the DB and enforcing shape in the service.

## Key files

- Smithy: `smithy/CustomerBookService.smithy`, `smithy/domain/CustomerBook.smithy`
- Service: `service/CustomerBookService.scala`
- Migration: `backend/schemas/migrations/V2025.05.27__init.sql` (`counterparty_details`, `customer_details`, `idx_customer_details_counterparty`)

## Implementation status

Schema and smithy contract are in place; the rest is **not built yet**:

- `CustomerBookService` operations are stubbed (`ZIO.fail(... is not implemented yet)`).
- The `Row → Queries → Repository` + `RepositoryConfig` + `application.conf` stack for both tables is unwritten (see the [Adding a table checklist](../postgres.md#adding-a-table--checklist)).
- **Counterparty CRUD endpoints** (create/edit a `BUSINESS`, list counterparties, archive/restore) are not in the smithy yet — the current surface is customer-centric only.
- **Open contract mismatch**: `CounterpartyID` is declared `@unwrapped string` while the `counterpartyID` members are `UUID` and `GetCustomersMap`'s key is `CounterpartyID` — reconcile to `UUID` when wiring the endpoints.

## Tests

None yet. When implemented, follow the pattern of [Organization Management](organization-management.md): acceptance in `backend/gateway/it` (see [acceptance-tests.md](../acceptance-tests.md)) covering create-standalone (auto `INDIVIDUAL`), create-into-business, reassign-with-orphan-archive, and soft-delete; plus functional (`fun/CustomerBookServiceSpec`) and repository integration specs.
