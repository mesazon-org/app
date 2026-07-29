# Scala — Mesazon specifics

Concrete values that fill in the placeholders in [scala-new.md](scala-new.md) — real identifiers from this codebase illustrating rules the generic doc demonstrates with a placeholder domain. Not a standard on its own.

- Concept-first naming in practice: `UserOtpRepository`, `UserOtpRow`, `UserOtpQueries` (same base concept, role suffix distinguishes layer); `otpNew`, `userOtpRowUpdated` (state suffix); `userDetailsRowOpt` (optional).
- `Impl` suffix: used only to distinguish a concrete class from a trait it implements, never the trait itself — `trait UserOtpRepository` + private `UserOtpRepositoryImpl`. Never `trait FooRepositoryImpl` or a bare `FooImplementation`.
- Acronyms in practice: `UserID`, `OtpID`, `IDGenerator`, `JwtService`, `WahaClient` — `ID` uppercase throughout, `Jwt`/`Waha` treated as words.
- `Opt` suffix in practice: `emailRawOpt`, `userDetailsRowOpt`, `phoneNumberRawOpt`.
- Domain type naming in practice: `PaymentSchedule`-shaped concepts in this codebase are e.g. `OtpType`, `TokenType`, `OrganizationUserRole` — named after the domain concept, never `OtpTypeString` or `OrganizationUserRoleColumn`.
