# Request validation

Request validation is the single boundary where an untrusted transport request — the shape a client actually sends over the wire — becomes a **refined domain model** the rest of the service can trust. Past that boundary, the types make illegal states unrepresentable: nothing downstream needs to re-check that a field was present, well-formed, or in range. This document defines the project-agnostic standard for that boundary: how validation errors accumulate, how validators are scoped per feature, which pieces of validation logic are shared instead of reinvented, and how the pattern is tested. It applies regardless of the transport contract, the refinement library, or the effect system a given project uses.

## Scope

This document owns the **project-agnostic validation-boundary standard**:

- accumulating validation errors instead of failing fast;
- scoping one validator per feature, with one function per fallible request;
- the shared field-level and list-level validation helpers every feature reuses;
- the validated-request pattern itself (domain model in, transport request out — or rather, the reverse: transport request in, domain model out);
- how a validator is tested (round-trip success, accumulated-error failure).

It does **not** own:

- **Refined newtype naming and where those types live** — see [stack/iron-new.md](stack/iron-new.md).
- **Generated transport request types, and how a generated request name is disambiguated from the domain model it validates into** — see [stack/smithy-new.md](stack/smithy-new.md).
- **General Scala and test-writing naming conventions** — see [stack/scala-new.md](stack/scala-new.md).
- **The service layer that calls the validator** — it is not documented as its own boundary; treat it as prose context here (the handler receives the generated request, calls the feature's validator, binds the result to the domain model, then does its work).

## Table of contents

- [Scope](#scope)
- [The two layers](#the-two-layers)
- [Building blocks (shared)](#building-blocks-shared)
- [The feature request validator pattern](#the-feature-request-validator-pattern)
  - [Rules](#rules)
- [Tests](#tests)
- [Key files](#key-files)

## The two layers

Every write endpoint validates its raw generated transport request into a refined domain model — a case class whose fields are newtypes with refined types (see [stack/iron-new.md](stack/iron-new.md)). Validation is the single boundary where untrusted `String`/`UUID`/`Option` input from the wire becomes a value the service layer can trust.

The service handler receives the generated transport request — named after the domain concept it represents, qualified so it is never confused with the domain model itself (see [stack/smithy-new.md](stack/smithy-new.md) for the naming/disambiguation convention) — calls the feature's validator, and binds the domain model under the plain domain-type name, then does its work. A validation failure is a single error that lists **every** invalid field at once: errors accumulate, they never fail fast.

- **Domain model** — a plain case class of newtypes (plus `Option`/`List` of them). No transport framework, no effect-system dependency — just the shape the service wants. One case class per request payload, named **exactly** after the transport request structure it validates from. The generated request name is the gold standard for this name — see [stack/smithy-new.md](stack/smithy-new.md#3-requestresponse-structures): the domain model and the generated transport shape are distinguished purely by qualification, never by inventing a second name for the same concept.
  - *Concretely in this codebase:* `backend/domain/src/main/scala/io/mesazon/domain/gateway/<Feature>.scala`, e.g. `InsertCustomerIndividualPostRequest`, `UpdateCustomerBusinessPutRequest`.
- **Request validator** — one class per feature, with one public function per generated transport request that can fail. It turns the generated transport request into the domain model.
  - *Concretely in this codebase:* `backend/gateway/core/src/main/scala/io/mesazon/gateway/validation/service/<Feature>RequestValidator.scala`.

## Building blocks (shared)

Reuse these — do not reinvent them per feature. Keep them in one place shared across the whole project:

- `validateRequiredField(fieldName, value, constructor)` — validate one required field, `constructor` is the newtype's `.either` (`A => Either[String, T]`). Returns a `ValidatedNec[InvalidFieldError, T]` (or the project's equivalent error-accumulating applicative).
- `validateOptionalField(fieldName, value: Option[A], constructor)` — same for an optional field, returns `ValidatedNec[..., Option[T]]`.
- `toValidatedRequestIO(validatedFields: UIO[ValidatedNec[InvalidFieldError, B]]): IO[ValidationError, B]` — collapses the accumulated `ValidatedNec` into the endpoint's effect type, mapping the error chain to a single validation error.
- `validateAll(items)(validate)` — validate every item of a *flat* list, accumulating all failures and stamping each failed item's errors with its `index` in the list, so the caller learns exactly which items failed.
- `validateAllNested(fieldName, items)(validate)` — for lists of composite items whose own errors already carry indexes (e.g. a batch of customers each holding email lists). Re-indexing would be misleading, so each failed item collapses into one error on `fieldName` whose message lists the item's invalid fields (inner indexes intact) and whose `index` points at the item in this list.
- `validateSingleDefault(fieldName, items)(isDefault)` — for lists of "defaultable" entries (e.g. emails/phones with an `isDefault` flag): a non-empty list must mark **exactly one** entry as default; chain it after the per-entry validation with `.andThen`.
- **Domain validators** for fields whose validation is effectful or non-trivial — illustrated in this codebase by `EmailValidator` (JMail; generic, takes the target newtype's `.either` and returns that newtype) and `PhoneNumberDomainValidator` (libphonenumber). Inject these into the feature validator; wrap their output into the feature's newtype (e.g. `CustomerPhoneNumber(phoneNumber)`).
- **Legacy per-request validator traits** (a `ServiceValidator[A, B]` / `DomainValidator[A, B]` style, one class per request) may still exist from before this pattern was adopted. New features use the single **feature request validator** pattern below; old features migrate to it when touched — see [adding-a-feature-new.md § Consolidating](adding-a-feature-new.md#consolidating-an-existing-feature-into-this-layout).

## The feature request validator pattern

One class per feature. Each public function is named **`validated` + the generated request's name** and returns an effect of `ValidationError` or the domain model. The per-item/per-field validation is shared privately so the singular, batch, and combined forms reuse it.

The example below (`CustomerBookRequestValidator`) is a concrete illustration from this codebase — the transport package is aliased `smithy` here because this project's contracts are smithy-first (see [stack/smithy-new.md](stack/smithy-new.md)); on another project the generated package would have a different name, but the shape of the validator class is the same:

```scala
final class CustomerBookRequestValidator(
    emailValidator: EmailValidator,
    phoneNumberDomainValidator: PhoneNumberDomainValidator,
) {

  def validatedInsertCustomerIndividualPostRequest(
      request: smithy.InsertCustomerIndividualPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, InsertCustomerIndividualPostRequest] =
    toValidatedRequestIO(validateInsertCustomerIndividual(request))

  // batch reuses the item validator; validateAllNested wraps each failed customer's errors under the batch field
  def validatedInsertCustomerIndividualsPostRequest(
      request: smithy.InsertCustomerIndividualsPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, InsertCustomerIndividualsPostRequest] =
    toValidatedRequestIO(
      validateInsertCustomerIndividuals(request.customerIndividuals).map(_.map(InsertCustomerIndividualsPostRequest.apply))
    )

  // list field with a per-entry isDefault flag: validate entries, then require exactly one default
  private def validateCustomerEmails(
      emails: List[smithy.CustomerEmailEntryRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[CustomerEmailEntryRequest]]] =
    validateAll(emails)(email =>
      emailValidator
        .validate(email.email, CustomerEmail.either)
        .map(_.map(validated => CustomerEmailEntryRequest(validated, email.isDefault)))
    ).map(_.andThen(entries => validateSingleDefault("emails", entries)(_.isDefault)))

  private def validateInsertCustomerIndividual(
      request: smithy.InsertCustomerIndividualPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, InsertCustomerIndividualPostRequest]] =
    validateCustomerEmails(request.emails)
      .zip(validateCustomerPhoneNumbers(request.phoneNumbers))
      .map((emailsValidated, phoneNumbersValidated) =>
        (
          validateRequiredField("fullName", request.fullName, CustomerFullName.either),
          emailsValidated,
          phoneNumbersValidated,
          validateOptionalField("addressLine1", request.addressLine1, CustomerAddressLine1.either),
          // … one entry per field, in field order …
        ).mapN(InsertCustomerIndividualPostRequest.apply)
      )

  private def validateInsertCustomerIndividuals(
      requests: List[smithy.InsertCustomerIndividualPostRequest]
  ): UIO[ValidatedNec[InvalidFieldError, List[InsertCustomerIndividualPostRequest]]] =
    validateAllNested("customerIndividuals", requests)(validateInsertCustomerIndividual)
}

object CustomerBookRequestValidator {
  val live = ZLayer.derive[CustomerBookRequestValidator]
}
```

### Rules

1. **One function per fallible request, named `validated<GeneratedRequestName>`.** Returns an effect parameterized by the validation-error type and the domain model.
2. **No validator for a request that cannot fail.** If a request carries only inputs that are already the right type at the transport boundary (e.g. a payload of only already-parsed identifiers), there is nothing to validate — do **not** write a `validated…` function for it. The service handler translates those directly into their newtypes (e.g. `CustomerID(request.customerID)`, `CustomerBusinessContactID(...)`). Example from this codebase: `RemoveCustomerBusinessContactsPutRequest` has no validator.
3. **Accumulate, never fail-fast.** Compose fields with an error-accumulating applicative (`ValidatedNec` + `mapN` in this codebase) and compose lists with the shared `validateAll`, which also tags each failed item's errors with its list `index`. A caller sees *all* the bad fields in one validation error, in field order.
4. **Share the item validator across singular/batch/combined.** The batch (`…s`) and combined forms call the same private per-item validator; don't duplicate the field list. Keep the pervasive field helpers (e.g. `validateOptionalCustomerEmail`, `validateOptionalCustomerPhoneNumber`) private and shared too.
5. **Effectful fields go through a domain validator**, then get wrapped into the feature newtype. Pure fields use `validate{Required,Optional}Field` with the newtype's `.either`.
6. **`fieldName` matches the wire member name** (`"fullName"`, `"addressLine1"`) — it is what the client sees in the invalid-field error.
7. **Provide a `live` layer** (`val live = ZLayer.derive[…]` in this codebase, or the project's equivalent dependency-injection wiring) and wire it where the service layer is assembled.

## Tests

One spec per feature validator, isolated per test via an independently-generated sample (no shared fixtures — see [acceptance-tests-new.md](acceptance-tests-new.md) and the arbitraries below). **Two tests per public function:**

- **success = round-trip.** Sample the *domain* model, transform it into the generated transport request, validate, and assert the result equals the original domain model:
  ```scala
  val individual = arbitrarySample[InsertCustomerIndividualPostRequest]
  validator
    .validatedInsertCustomerIndividualPostRequest(individual.transformInto[smithy.InsertCustomerIndividualPostRequest])
    .zioValue shouldBe individual
  ```
- **failure = accumulated errors.** Sample a valid transport request, `.copy` the tested fields to invalid values, and assert the exact list of invalid-field errors (in field order):
  ```scala
  val request = arbitrarySample[smithy.InsertCustomerIndividualPostRequest].copy(fullName = "", email = Some("invalid-email"))
  validator.validatedInsertCustomerIndividualPostRequest(request).zioError shouldBe
    ServiceError.BadRequestError.ValidationError(invalidFields = List(/* fullName then email */))
  ```

The round-trip works because the generated-transport-request arbitrary is *derived from* the domain arbitrary (see [adding-a-feature-new.md](adding-a-feature-new.md)), so the sample is always internally consistent and always valid.

*Concretely in this codebase:* specs live at `backend/gateway/core/src/test/scala/io/mesazon/gateway/unit/validation/service/<Feature>RequestValidatorSpec.scala`, extending `ZWordSpecBase`.

## Key files

The project-agnostic pattern above maps to these files in this codebase:

- Shared helpers: `validation/service/validation.scala`, `validation/service/ServiceValidator.scala`, `validation/domain/{DomainValidator,EmailValidator,PhoneNumberDomainValidator}.scala`
- Example: `validation/service/CustomerBookRequestValidator.scala` + `domain/gateway/CustomerBook.scala`
