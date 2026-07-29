# Scala — Mesazon specifics

Read with the [agnostic Scala rules](../agnostic/scala.md).

- Concept-first naming in practice: `UserOtpRepository`, `UserOtpRow`, `UserOtpQueries` (same base concept, role suffix distinguishes layer); `otpNew`, `userOtpRowUpdated` (state suffix); `userDetailsRowOpt` (optional).
- `Impl` suffix: used only to distinguish a concrete class from a trait it implements, never the trait itself — `trait UserOtpRepository` + private `UserOtpRepositoryImpl`. Never `trait FooRepositoryImpl` or a bare `FooImplementation`.
- Acronyms in practice: `UserID`, `OtpID`, `IDGenerator`, `JwtService`, `WahaClient` — `ID` uppercase throughout, `Jwt`/`Waha` treated as words.
- `Opt` suffix in practice: `emailRawOpt`, `userDetailsRowOpt`, `phoneNumberRawOpt`.
- Domain type naming in practice: `PaymentSchedule`-shaped concepts in this codebase are e.g. `OtpType`, `TokenType`, `OrganizationUserRole` — named after the domain concept, never `OtpTypeString` or `OrganizationUserRoleColumn`.

## Repository naming

- Repository: `<Entity>Repository`; implementation: private `<Entity>RepositoryImpl`. Methods include entity + operation (`get`, `insert`, `create`, `upsert`, `is`, `update`, `delete`, `getAndIncrease`), plural for multi-row, `By<Selector>` when needed.
- Repository params use full domain names (`userID`, enum name). Optional updates end `OptUpdate` (`addressLine1OptUpdate`), not `UpdateOpt`. Never accept an API/Smithy `...Request`; use flat params or a repository-owned input.
- Row/projection: `<Entity>Row`; fields follow domain naming, nullable fields end `Opt`.
- Repository input: `<Operation><Entity>Input`, defined in the repository companion. Name the parameter after the full type. Inputs exist only for batch elements or repeated/nested children; singular and batch forms reuse the same element input. Single-only updates/removes use flat params. Inputs contain no generated ID/audit fields. A repeated/jsonb `Row` field uses the repository `...Input` element, never an API request. Service maps validated request → input with Chimney/`iron-chimney` (`transformInto`).
- Queries: `<Table>Queries`; methods are bare verbs because the class supplies the entity (`getByUserID`, `insert`, `getAll`, `isSlugExists`). Multi-row methods are plural; all-row reads add `All`; test-only methods end `Testing` (`getAllTesting`).
- Bind repository results as their exact row shape: `userDetailsRow`, `userOtpRowOpt`, `userDetailsRows`, `userDetailsRowUpdated`; never drop `Row`.

Full layer behavior: [repository.md](../../repository.md).
