# Request validation

Every write endpoint validates its raw smithy request into a **refined domain model** — a case class whose fields are newtypes with iron refined types (see [scala.md § Iron new types](scala.md#iron-new-types)). Validation is the single boundary where untrusted `String`/`UUID`/`Option` input from the wire becomes a value the service layer can trust; past that boundary the types make illegal states unrepresentable.

The service handler receives the generated `smithy.<Operation>Request`, calls the feature's validator to get the domain model, and only then does its work. A validation failure is a `400` `ValidationError` that lists **every** invalid field at once (errors accumulate, never fail-fast).

## The two layers

- **Domain model** — `backend/domain/src/main/scala/io/mesazon/domain/gateway/<Feature>.scala`. Plain case classes of newtypes (plus `Option`/`List` of them). No smithy, no ZIO — just the shape the service wants. One case class per request payload, named after the item it carries (`InsertCustomerIndividual`, `UpdateCustomerBusiness`, …). See [smithy.md § Item structures](smithy.md#4-item-structures-and-lists) — the domain models mirror the smithy item structures one-for-one.
- **Request validator** — `backend/gateway/core/src/main/scala/io/mesazon/gateway/validation/service/<Feature>RequestValidator.scala`. One class per feature, with one public function per smithy request that can fail. It turns `smithy.<Operation>Request` into the domain model.

## Building blocks (shared, in `validation/`)

Reuse these — do not reinvent them per feature:

- `validation.scala` (package `io.mesazon.gateway.validation.service`) holds the `private[validation]` helpers:
  - `validateRequiredField(fieldName, value, constructor)` — validate one required field, `constructor` is the newtype's `.either` (`A => Either[String, T]`). Returns `ValidatedNec[InvalidFieldError, T]`.
  - `validateOptionalField(fieldName, value: Option[A], constructor)` — same for an optional field, returns `ValidatedNec[..., Option[T]]`.
  - `toValidatedRequestIO(validatedFields: UIO[ValidatedNec[InvalidFieldError, B]]): IO[ValidationError, B]` — collapses the accumulated `ValidatedNec` into the endpoint's `IO`, mapping the error chain to a single `ServiceError.BadRequestError.ValidationError`.
  - `validateAll(items)(validate)` — validate every item of a list, accumulating all failures and stamping each failed item's errors with its `index` in the list (`InvalidFieldError.index`), so the caller learns exactly which items failed. For nested lists (a batch of customers each holding email lists) the outer index wins.
  - `validateSingleDefault(fieldName, items)(isDefault)` — for lists of "defaultable" entries (e.g. emails/phones with an `isDefault` flag): a non-empty list must mark **exactly one** entry as default; chain it after the per-entry validation with `.andThen`.
- **Domain validators** (`validation/domain/`) for fields whose validation is effectful or non-trivial — `EmailValidator` (JMail; generic, takes the target newtype's `.either` and returns that newtype), `PhoneNumberDomainValidator` (libphonenumber). Inject these into the feature validator; wrap their output into the feature's newtype (`CustomerPhoneNumber(phoneNumber)`).
- `ServiceValidator[A, B]` / `DomainValidator[A, B]` — the older per-request validator traits (`CreateOrganizationPostRequestServiceValidator`, the onboarding validators). Still valid, but new features use the single **feature request validator** below.

## The feature request validator pattern

One class per feature. Each public function is named **`validated` + the smithy request name** and returns `IO[ServiceError.BadRequestError.ValidationError, <DomainModel>]`. The per-item/per-field validation is shared privately so the singular, batch and combined forms reuse it.

```scala
final class CustomerBookRequestValidator(
    emailValidator: EmailValidator,
    phoneNumberDomainValidator: PhoneNumberDomainValidator,
) {

  def validatedInsertCustomerIndividualPostRequest(
      request: smithy.InsertCustomerIndividualPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, InsertCustomerIndividual] =
    toValidatedRequestIO(validateInsertCustomerIndividual(request))

  // batch reuses the item validator; validateAll accumulates + tags each failure with its index
  def validatedInsertCustomerIndividualsPostRequest(
      request: smithy.InsertCustomerIndividualsPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, InsertCustomerIndividuals] =
    toValidatedRequestIO(
      validateInsertCustomerIndividuals(request.customerIndividuals).map(_.map(InsertCustomerIndividuals.apply))
    )

  // list field with a per-entry isDefault flag: validate entries, then require exactly one default
  private def validateCustomerEmails(
      emails: List[smithy.CustomerEmailRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[CustomerEmailEntry]]] =
    validateAll(emails)(email =>
      emailValidator
        .validate(email.email, CustomerEmail.either)
        .map(_.map(validated => CustomerEmailEntry(validated, email.isDefault)))
    ).map(_.andThen(entries => validateSingleDefault("emails", entries)(_.isDefault)))

  private def validateInsertCustomerIndividual(
      request: smithy.InsertCustomerIndividualPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, InsertCustomerIndividual]] =
    validateCustomerEmails(request.emails)
      .zip(validateCustomerPhoneNumbers(request.phoneNumbers))
      .map((emailsValidated, phoneNumbersValidated) =>
        (
          validateRequiredField("fullName", request.fullName, CustomerFullName.either),
          emailsValidated,
          phoneNumbersValidated,
          validateOptionalField("addressLine1", request.addressLine1, CustomerAddressLine1.either),
          // … one entry per field, in field order …
        ).mapN(InsertCustomerIndividual.apply)
      )

  private def validateInsertCustomerIndividuals(
      requests: List[smithy.InsertCustomerIndividualPostRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[InsertCustomerIndividual]]] =
    validateAll(requests)(validateInsertCustomerIndividual)
}

object CustomerBookRequestValidator {
  val live = ZLayer.derive[CustomerBookRequestValidator]
}
```

### Rules

1. **One function per fallible request, named `validated<SmithyRequestName>`.** Returns `IO[ServiceError.BadRequestError.ValidationError, B]`.
2. **No validator for a request that cannot fail.** If a request carries only inputs that are already the right type at the wire boundary (e.g. a payload of only `alloy#UUID` ids, which smithy has already parsed), there is nothing to validate — do **not** write a `validated…` function for it. The service handler translates those directly into their newtypes (`CustomerID(request.customerID)`, `CustomerBusinessContactID(...)`). Example: `RemoveCustomerBusinessContactsPutRequest` has no validator.
3. **Accumulate, never fail-fast.** Compose fields with `ValidatedNec` + `mapN`; compose lists with the shared `validateAll`, which also tags each failed item's errors with its list `index`. A caller sees *all* the bad fields in one `ValidationError`, in field order.
4. **Share the item validator across singular/batch/combined.** The batch (`…s`) and combined (`InsertCustomers`) forms call the same private per-item validator; don't duplicate the field list. Keep the pervasive field helpers (`validateOptionalCustomerEmail`, `validateOptionalCustomerPhoneNumber`) private and shared too.
5. **Effectful fields go through a domain validator**, then get wrapped into the feature newtype. Pure fields use `validate{Required,Optional}Field` with the newtype's `.either`.
6. **`fieldName` matches the smithy member name** (`"fullName"`, `"addressLine1"`) — it is what the client sees in the `InvalidFieldError`.
7. Provide a `val live = ZLayer.derive[…]` and wire it where the service layer is assembled.

## Tests

`backend/gateway/core/src/test/scala/io/mesazon/gateway/unit/validation/service/<Feature>RequestValidatorSpec.scala`, `ZWordSpecBase`. **Two tests per public function**, each isolated via `arbitrarySample` (no shared fixtures — see [acceptance-tests.md](acceptance-tests.md) and the arbitraries below):

- **success = round-trip.** Sample the *domain* model, transform it into the smithy request (chimney), validate, and assert the result equals the original domain model:
  ```scala
  val individual = arbitrarySample[InsertCustomerIndividual]
  validator
    .validatedInsertCustomerIndividualPostRequest(individual.transformInto[smithy.InsertCustomerIndividualPostRequest])
    .zioValue shouldBe individual
  ```
- **failure = accumulated errors.** Sample a valid smithy request, `.copy` the tested fields to invalid values, and assert the exact `List[InvalidFieldError]` (in field order):
  ```scala
  val request = arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(fullName = "", email = Some("invalid-email"))
  validator.validatedInsertCustomerIndividualPostRequest(request).zioError shouldBe
    ServiceError.BadRequestError.ValidationError(invalidFields = List(/* fullName then email */))
  ```

The round-trip works because the smithy arbitrary is *derived from* the domain arbitrary (see [adding-a-feature.md](adding-a-feature.md)), so the sample is always internally consistent and always valid.

## Key files

- Shared helpers: `validation/service/validation.scala`, `validation/service/ServiceValidator.scala`, `validation/domain/{DomainValidator,EmailDomainValidator,PhoneNumberDomainValidator}.scala`
- Example: `validation/service/CustomerBookRequestValidator.scala` + `domain/gateway/CustomerBook.scala`
