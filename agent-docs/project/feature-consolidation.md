# Existing feature consolidation

Read only when reorganizing an older feature into current per-feature files. This is behavior-neutral: no Smithy, SQL, response, or assertion change.

1. Move entry/value and request case classes into `domain/gateway/<Feature>.scala`, entries first then requests. Keep newtypes in shared `Newtypes.scala`; keep broadly used enums/models in their own files. Align request class names to canonical Smithy names.
2. Replace per-request validators with one `<Feature>RequestValidator`: `validated<Request>` public functions delegating to private accumulated validation. Update service, application layers, and spec wiring.
3. Move domain `Arbitrary` givens from generic traits to `<Feature>DomainArbitraries`; name every given. Shared generators remain protected in generic traits.
4. Move feature Smithy arbitraries/transformers to `<Feature>SmithyArbitraries`, deriving requests from domain values.
5. Update every consumer mixin, including `RepositoryArbitraries` and indirect specs.
6. Rename validator spec to `<Feature>RequestValidatorSpec`.
7. Search all docs for renamed identifiers and update the feature doc's key files.
8. Run full `Test/compile`, gateway-core tests, and gateway-it tests. Assertions remain unchanged.

Already consolidated: Customer Book, Organization Management, User Onboard, User Sign Up, User Sign In, User Forgot Password, and User Token.
