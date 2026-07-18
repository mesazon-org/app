# Scala

## Coding Standards

### Comments

- Don't add comments that restate what the code already says. Well-named identifiers and types are the primary documentation — a comment that paraphrases the next line is noise.
- Only add a comment when the code does something **non-obvious**: a surprising decision, a workaround, an invariant the compiler can't express, or context a reader can't recover from the code alone. The test is "would a competent reader be confused or surprised without this?" — if not, leave it out.
- This applies to section-header/banner comments too (e.g. `// -- Helpers --`); prefer structuring the code well over labelling it.

### Naming rules

**Recommended name convention**

- `{{ Owner }} {{ Entity }} {{ Optional }} {{ Behavior }}`
- The name should be descriptive and indicate the purpose of the variable, method, class, etc.
- Owner examples: `User`, `Organization`, `Contact`, `Order`
- Entity examples: `Details`, `Otp`, `Name`, `Email`
- Optional examples: empty or `Opt`
- Behavior examples: `Update`, `Expected`, `New`, `Deleted`, `Existing`, `Raw`

#### General Scala naming rules

##### 1. values, defs, parameters

- Should use `camelCase`
- ✅ `userDetailsRowOpt`, `otpNew`, `userOtpRowUpdated`, `uploadOrganizationLogo`
- ❌ `user_details_row_opt`, `OtpNew`

##### 2. classes, traits, objects, enums, type aliases, constants

- Should use `PascalCase`
- ✅ `UserSignUpService`, `UserOtpRepository`, `UserOtpRow`, `UserOtpQueries`, `UserID`, `MaxDimensionPixels`
- ❌ `userSignUpService`, `userOtpRepository`, `userOtpRow`, `userOtpQueries`, `userID`, `maxDimensionPixels`

##### 3. acronyms

- Should be used consistently in PascalCase or camelCase, never mixed
- `ID` stays fully uppercase everywhere; all other acronyms are PascalCased as words
- ✅ `UserID`, `OtpID`, `getUserOtpByUserID`, `IDGenerator`, `JwtService`, `OtpType`, `WahaClient`
- ❌ `UserId`, `IdGenerator`, `JWTService`, `OTPType`

##### 4. descriptive names

- Names are fully spelled out — no abbreviations, except for well-known acronyms and `Impl` for implementation classes
- ✅ `userDetailsRepository`, `UserOtpRepositoryImpl`
- ❌ `userDetailsRepo`, `udr`

##### 5. optionals

- An `Option`-typed value, parameter or field is always suffixed with `Opt`, so the type is legible from the name
- `Opt` is the **last** suffix — any other modifier (e.g. `Raw`) comes before it: an optional raw email is `emailRawOpt`, not `emailOptRaw`
- ✅ `emailRawOpt: Option[String]`, `userDetailsRowOpt`, `phoneNumberRawOpt`
- ❌ `emailRaw: Option[String]`, `emailOptRaw`, `maybeEmail`, `optionalEmail`

#### Iron new types

##### 1. Naming convention for iron new types

- Should use `PascalCase` and follow `{{ Owner }} {{ Entity }}`
- Should be used instead of raw primitives to provide type safety and avoid confusion with other types
- Types representing unique identifiers should be named after the entity they identify, suffixed with `ID`
- ✅ `UserID`, `PhoneRegion`, `OrganizationLogoOriginalFileName`, `PhoneNumberE164`, `UpdatedAt`
- ❌ `userID`, `organizationId`, `contactId`, `id`

#### Repository naming rules

##### 1. Repository classes

- Should be named after the entity they manage, suffixed with `Repository`
- ✅ `UserOtpRepository`, `OrganizationRepository`
- ❌ `UserOtpQueries`, `UserOtpDao`

##### 2. Repository methods

- Should be prefixed with `get`, `insert`, `create`, `upsert`, `is`, `update`, `delete`, `getAndIncrease` etc.
  depending on the operation
- Should use the entity name in the method name
- Should use plural form for methods that return multiple entities
- Should use suffix `ByUserID` (or similar) when multiple methods get the same entity with different parameters
- ✅ `getUserOtpByUserID`, `insertUserOtp`, `updateUserOtp`, `deleteUserOtp`, `getAllUserOtps`,
  `isOrganizationSlugExists`, `createOrganization`
- ❌ `insert`, `update`, `delete`, `getAll`, `getByUserID`

##### 3. Repository method parameters

- Should always use the recommended name convention for parameters with `ID`
- Should always use the recommended name convention for enum parameters
- Should in some cases omit the `{{ Owner }}` prefix when the repository is already named after the entity it manages
- Should include `Opt` when the parameter is optional
- Should include the behavior when the parameter is used to perform a specific action
- ✅ `userID`, `organizationID`, `addressLine1OptUpdate`, `organizationStageOptUpdate`, `phoneNumber`
- ❌ `userId`, `organizationId`, `id`, `addressLine1UpdateOpt`, `organizationStageUpdateOpt`, `stageOptUpdate`

##### 4. Repository domain models

- Should be named after the entity they represent, suffixed with `Row`
- ✅ `UserOtpRow`, `OrganizationRow`
- ❌ `UserOtp`, `Organization`

##### 5. Repository domain model parameters

- Should always use the recommended name convention for parameters with `ID`
- Should always use the recommended name convention for enum parameters
- Should follow the same principle as repository method parameters: the `{{ Owner }}` prefix can be omitted when the model is already named after the entity it represents
- Should include `Opt` when the parameter is optional
- ✅ `userID`, `organizationID`, `addressLine1Opt`, `organizationStage`, `phoneNumber`
- ❌ `userId`, `organizationId`, `id`

##### 6. Repository query classes

- Should be named after the table they manage, suffixed with `Queries`
- ✅ `UserOtpQueries`, `OrganizationQueries`
- ❌ `UserOtpRepository`, `OrganizationRepository`

##### 7. Repository query class methods

- Should be named as operations with `get`, `insert`, `create`, `upsert`, `is`, `update`, `delete`, `getAndIncrease` etc.
- Should not use the entity name in the method name, as the query class is already named after the entity it manages
- Should use plural form for methods that return multiple entities
- Should use suffix `ByUserID` (or similar) when multiple methods get the same entity with different parameters
- ✅ `getByUserID`, `insert`, `update`, `delete`, `getAll`, `isSlugExists`
- ❌ `getUserOtpByUserID`, `insertUserOtp`, `updateUserOtp`, `deleteUserOtp`, `getAllUserOtps`, `isOrganizationSlugExists`

##### 8. Repository query class methods for testing

- Should follow the same rules as regular query class methods (operation verbs, no entity name, plural form, `ByUserID` disambiguation)
- Should add `All` after the operation verb when returning all entities
- Should add the suffix `Testing` to indicate that the method exists for testing purposes only
- ✅ `getAllTesting`, `deleteTesting`, `insertTesting`, `updateTesting`, `getByUserIDTesting`
- ❌ `getAllUserOtpsTesting`, `insertUserActionAttemptTesting`, `getAllUserOtps`


### Testing

- **Keep every test isolated.** A test builds the data it needs and asserts against it — it does not depend on values, builders, or state shared with other tests. Prefer generating inputs per test with `arbitrarySample[X]` (feature arbitraries live in their own traits — see [adding-a-feature.md](adding-a-feature.md)) and `.copy(...)` only the fields the test is about.
- **Share only when really needed.** Extracting a common value or helper is the exception, justified when the alternative is genuinely worse — e.g. constructing the system under test (a layer/service), or a long error-message literal repeated verbatim across many assertions. Default to inline; reach for a shared binding only when inlining hurts more than it helps.
- **Don't share test *data* fixtures.** A pile of shared "valid X" / "expected Y" vals coupling many tests together is what this rule exists to prevent — each test samples its own.
- ✅ `val individual = arbitrarySample[InsertCustomerIndividual]` in each test; `arbitrarySample[smithy.XRequest].copy(fullName = "")` for the one bad field
- ❌ a class-level `validRequest` / `expectedResult` reused across tests; a helper that builds the "standard" entity for everyone
