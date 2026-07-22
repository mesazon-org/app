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
- Behavior examples: `Update`, `Expected`, `New`, `Deleted`, `Existing`, `Raw`, `Validated`

**Smithy-generated types** are always referenced qualified — `smithy.CreateOrganizationPostRequest`, never a direct member import — because domain request models share the exact smithy names. See [smithy.md § Request/response structures](smithy.md#3-requestresponse-structures) for the rule. When a scope holds values of both shapes, the val named after the type is the **domain** one and the smithy one takes a `Smithy` suffix (`createOrganizationPostRequest` vs `createOrganizationPostRequestSmithy`) — the same disambiguation the arbitraries use. This applies everywhere the two coexist:

- **Service handler** (implementing the generated smithy trait): name the smithy request parameter after the full request type + `Smithy` (`createOrganizationPostRequestSmithy: smithy.CreateOrganizationPostRequest`), and the validator's output — the domain model — after the domain type (`createOrganizationPostRequest`). Do this for the whole feature's handlers, both the impl and the `observed` wrapper.
- **Tests**: a val holding a domain sample is named after its type (`insertCustomerIndividualPostRequest`), a val holding a smithy sample takes the `Smithy` suffix (`insertCustomerIndividualPostRequestSmithy`).

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
- A repository method **never takes a smithy-derived `...PostRequest`/`...PutRequest` domain model** — those carry HTTP/API vocabulary and the batch/combined grouping, which must not leak into the persistence boundary (`createOrganization` destructures `CreateOrganizationPostRequest` into flat params at the service; it never reaches the repo). Pass either flat params or a repository-owned **input model** (see §5b). Name an input parameter after its type in lower-camelCase — the full type name, never an abbreviation like `request`, `req`, or `input`.
- ✅ `userID`, `organizationID`, `addressLine1OptUpdate`, `organizationStageOptUpdate`, `phoneNumber`, `insertCustomerBusinessInput: InsertCustomerBusinessInput`
- ❌ `userId`, `organizationId`, `id`, `addressLine1UpdateOpt`, `organizationStageUpdateOpt`, `stageOptUpdate`, `insertCustomerBusinessPostRequest: InsertCustomerBusinessPostRequest` (a `...Request` type at the repo boundary)

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

##### 5b. Repository input models (decoupling the repo from the API contract)

When a repository operation needs a whole aggregate as input (many fields, or a nested/repeated child such as a list of contacts), the repository defines its **own input case classes** rather than accepting the API `...PostRequest`/`...PutRequest` models. The service maps the validated request → the input with **Chimney** (`io.scalaland.chimney`; refined types via `iron-chimney`) — usually a one-line `.transformInto[…]` since the field names line up.

Rules:

- **Naming & placement.** Suffix `Input` (`InsertCustomerBusinessInput`, `CustomerBusinessContactInput`, `CustomerEmailEntryInput`). Define them **inside the repository's companion object** (`object CustomerBookRepository { … }`), and mirror nested request structures with nested `…Input` classes — never reach back into the `io.mesazon.domain.gateway` request models from the repo. Input fields use the same refined-type conventions as `Row` models (§5), but an input is **not** a `Row`: it carries no generated id and no `CreatedAt`/`UpdatedAt` (the repository mints those).
- **An input class exists only to serve a batch (or a repeated child list).** If an operation has a batch form, define one input describing a **single** element; the batch takes `List[ThatInput]` and the **singular op reuses the same element class** (`insertCustomerIndividual(…, InsertCustomerIndividualInput)` and `insertCustomerIndividuals(…, List[InsertCustomerIndividualInput])`). A combined op (e.g. `insertCustomers`) reuses those same element lists.
- **Never create a class for a single-only operation.** An operation with no batch form (updates, `remove…`) takes **flat params** — `…OptUpdate` for updates (§3), bare ids/lists for removes — exactly like `updateOrganization`. Do not invent an `Update…Input`/`Remove…Input`.
- **`Input` reaches the `Row`, not just the method.** When a `Row`'s `jsonb`/repeated column stores a list of these small records, type that field with the `…Input` element (`emails: List[CustomerEmailEntryInput]`), not the smithy `…Request` — one type flows input → `Row` → jsonb codec, so the repository never converts `Input → Request`.
- ✅ `object CustomerBookRepository { case class InsertCustomerIndividualInput(…); case class CustomerEmailEntryInput(…) }`; service does `request.transformInto[InsertCustomerIndividualInput]`
- ❌ an `InsertCustomerIndividualInput` when there is no batch form; a `Row` used as insert input; the repo importing `InsertCustomerIndividualPostRequest`; an `Input` class defined as a top-level file instead of in the companion

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

##### 9. Values holding repository return values

- A repository returns `Row` models, so a val bound to a repository result is named after the row it holds — the entity name suffixed with `Row` (or `Rows` for a list). This holds in both service code and tests.
- When a result is `Option[…Row]` keep it optional-named with `RowOpt`; a value unwrapped from the option (e.g. via `.someOrFail`) is a plain `…Row`.
- When the same row appears twice in one scope (e.g. the fetched row and the updated row), qualify the second (`userDetailsRowUpdated`), never drop the `Row`.
- ✅ `userDetailsRow <- userDetailsRepository.getUserDetails(userID).someOrFail(...)`, `userOtpRowOpt <- userOtpRepository.getUserOtpByUserID(...)`, `userDetailsRowUpdated <- userDetailsRepository.updateUserDetails(...)`
- ❌ `userDetails <- userDetailsRepository.getUserDetails(...)`, `userOtp <- ...`


### Testing

- **Keep every test isolated.** A test builds the data it needs and asserts against it — it does not depend on values, builders, or state shared with other tests. Prefer generating inputs per test with `arbitrarySample[X]` (feature arbitraries live in their own traits — see [adding-a-feature.md](adding-a-feature.md)) and `.copy(...)` only the fields the test is about.
- **Share only when really needed.** Extracting a common value or helper is the exception, justified when the alternative is genuinely worse — e.g. constructing the system under test (a layer/service), or a long error-message literal repeated verbatim across many assertions. Default to inline; reach for a shared binding only when inlining hurts more than it helps.
- **Don't share test *data* fixtures.** A pile of shared "valid X" / "expected Y" vals coupling many tests together is what this rule exists to prevent — each test samples its own.
- ✅ `val individual = arbitrarySample[InsertCustomerIndividualPostRequest]` in each test; `arbitrarySample[smithy.XRequest].copy(fullName = "")` for the one bad field
- ❌ a class-level `validRequest` / `expectedResult` reused across tests; a helper that builds the "standard" entity for everyone
- **Name a value after its model, qualifier last.** A test binding's name is the model type in lower camelCase, not an abbreviation or a role word — `insertCustomerIndividualInput`, not `input`; `customerBusinessContactRow`, not `contact`. When you must disambiguate several of the same type, the name still **starts with the full model name** and the distinguishing suffix goes **at the end** — `customerRowIndividual`/`customerRowBusiness` (not `individualCustomerRow`), `customerID1`/`customerID2`, a derived `customerBusinessDetailsRowUpdated` — so bindings for one model sort and read together (see the [§9 result-value rule](#9-values-holding-repository-return-values)). This keeps the assertion readable as a sentence about the domain.
- **Assert the whole model, never a projection of it.** Compare the full `Row`/response value (`shouldBe expectedRow`, `should contain theSameElementsAs List(row1, row2)`), building the complete expected value with every field. Do **not** `.map` a read down to one or two fields (`.map(_.customerID)`, `.map(r => (r.customerID, r.fullName))`) and assert only those — an untested field is an untested column. (A `.map` to project is fine only when the field itself is the whole point, e.g. asserting *which ids survived a rollback*.)
- **Prove a "must differ" precondition with a not-equal assertion.** When a test's meaning depends on two values being distinct (two generated ids, a duplicate-name pair sharing a name but not an id), assert it in the test — `customerID1 shouldNot equal(customerID2)` — right where you set them up. Don't leave "they're different" as an unstated assumption riding on `arbitrarySample` happening not to collide.
- **When distinctness must be guaranteed, generate two arbitraries and modify one — never hard-code values, never sample-and-hope.** Some generators draw from a small fixed pool (e.g. `Arbitrary[PhoneNumber]` has a handful of numbers), so two samples collide often and the not-equal assertion above becomes a flaky failure. The fix is *not* to replace the arbitrary with hand-written literals (that bypasses the generator's realism and drifts from the domain); it is to sample both values and then **modify one** so the pair is distinct by construction, keeping the not-equal assertion as the documented proof.
  - ✅ `val customerPhoneNumber1 = arbitrarySample[CustomerPhoneNumber]` + a second sample with a digit appended to its `phoneNationalNumber`/`phoneNumberE164`; `CustomerEmail.assume(s"x${arbitrarySample[CustomerEmail].value}")` for a second distinct email
  - ❌ `customerPhoneNumberOf("7754767565")` / `CustomerEmail.assume("contact-one@example.com")` fixed literals; two raw `arbitrarySample` calls relying on luck against a low-cardinality generator
- **A random offset around a strict time/threshold comparison must exclude the boundary value.** Services compare with strict `isAfter`/`isBefore`/`>`; a test that builds its instant as `now ± Random.nextLongBetween(0, n)` lands exactly on the boundary once in `n` runs and flips the branch — a 1-in-`n` CI flake (`generateOtp` called where the resend branch was mocked). Start the range at `1` (`Random.nextLongBetween(1, n)`) whenever offset `0` makes the compared values equal; ranges where every generated value takes the same branch (e.g. `attempts = max - buffer` against a strict `> max`) may keep `0`.
- **Exactly one `should` block per operation — it holds that operation's happy *and* failure paths.** Each repository/service method (or endpoint) gets its own `"<operation>" should { ... }` section named after the function, and **every** test for that function — the happy path and each failure path — is an `in` inside it. Do **not** spin off a second section for an error scenario (`"<operation> with a duplicate X" should`), and do **not** merge two different operations into one section. A test exercises exactly one operation and arranges its preconditions directly (via the `Queries`/DB, bypassing the repo — see [repository.md § Testing](repository.md#testing--repository-integration-specs-with-testcontainers)), rather than calling operation A to set up operation B. A genuine cross-function invariant (e.g. "no `customer_id` is ever in both detail tables") is not one function's path, so it may have its own descriptively-named section.
  - ✅ `"insertCustomerIndividual" should { "insert …" in …; "fail with a UniqueConstraintViolation when the full name already exists …" in … }` — happy + failure under the one function section.
  - ❌ a separate `"insertCustomerIndividual with a duplicate full name" should { … }`; ❌ one `"addCustomerBusinessContacts / removeCustomerBusinessContacts" should` block covering two operations.
