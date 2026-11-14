package io.mesazon.gateway.it

import com.dimafeng.testcontainers.DockerComposeContainer
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.given
import io.mesazon.gateway.it.harness.GatewayAcceptanceTest
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import org.scalatest.DoNotDiscover
import sttp.model.*
import zio.*
import zio.stream.ZStream

@DoNotDiscover
class CustomerBookFileApiSpec(dockerComposeContainer: => DockerComposeContainer)
    extends GatewayAcceptanceTest(dockerComposeContainer),
      RepositoryArbitraries {

  private val organizationMediaBucket = "organization-media"

  override def beforeEach(): Unit = {
    super.beforeEach()

    withContext { context =>
      import context.*

      for {
        _ <- postgresClient.truncateTable(
          repositoryConfig.schema,
          repositoryConfig.userDetailsTable,
          repositoryConfig.organizationUserTable,
          repositoryConfig.customerTable,
          repositoryConfig.customerBusinessContactTable,
        )
        _ <- s3TestClient.emptyAllBuckets()
      } yield ()
    }
  }

  "Customer Book File Service API" when {
    "/customer-book/file/ai/extract" should {
      "successfully extract customer candidates from an image without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
          customerBookFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${customerBookFileExtractFileName.value}")
            .runCollect
          customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
            individualCandidates = List(
              CustomerIndividualCandidate(
                fullName = Some(CustomerFullName.assume("John Smith")),
                emails = List(
                  CustomerEmailEntry(email = CustomerEmail.assume("john.smith@example.com"), isDefault = true)
                ),
                phoneNumbers = List.empty,
                addresses = List.empty,
                inferredFields = List.empty,
                missingFields = List.empty,
                duplicateFields = List.empty,
                needsAttention = false,
                extractionNotes = List.empty,
              ),
              CustomerIndividualCandidate(
                fullName = Some(CustomerFullName.assume("Jane Doe")),
                emails = List(
                  CustomerEmailEntry(email = CustomerEmail.assume("jane.doe@example.com"), isDefault = true)
                ),
                phoneNumbers = List.empty,
                addresses = List.empty,
                inferredFields = List.empty,
                missingFields = List.empty,
                duplicateFields = List.empty,
                needsAttention = false,
                extractionNotes = List.empty,
              ),
            ),
            businessCandidates = List.empty,
            infoNotes = List("Processed 2 of 2 entries"),
            extractionNotes = List.empty,
          )

          customerBookFileAiExtractPostResponse <- gatewayClient
            .customerBookFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(customerBookFileExtractFileName),
              customerBookFileExtractFileBytes,
              Some(accessJwt.accessToken),
              None,
            )

          _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
          _ = customerBookFileAiExtractPostResponse.body.value shouldBe customerBookFileAiExtractPostResponseExpected

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe empty
          _ = customerIndividualDetailsRowsAll shouldBe empty
          _ = customerBusinessDetailsRowsAll shouldBe empty
          _ = customerBusinessContactRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "successfully extract customer candidates from an image whose extension is in capitals without storing data" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.JPEG")
            customerBookFileExtractFileBytes <- ZStream.fromResource("assets/contact-book-image-it-1.jpeg").runCollect
            customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
              individualCandidates = List(
                CustomerIndividualCandidate(
                  fullName = Some(CustomerFullName.assume("John Smith")),
                  emails = List(
                    CustomerEmailEntry(email = CustomerEmail.assume("john.smith@example.com"), isDefault = true)
                  ),
                  phoneNumbers = List.empty,
                  addresses = List.empty,
                  inferredFields = List.empty,
                  missingFields = List.empty,
                  duplicateFields = List.empty,
                  needsAttention = false,
                  extractionNotes = List.empty,
                ),
                CustomerIndividualCandidate(
                  fullName = Some(CustomerFullName.assume("Jane Doe")),
                  emails = List(
                    CustomerEmailEntry(email = CustomerEmail.assume("jane.doe@example.com"), isDefault = true)
                  ),
                  phoneNumbers = List.empty,
                  addresses = List.empty,
                  inferredFields = List.empty,
                  missingFields = List.empty,
                  duplicateFields = List.empty,
                  needsAttention = false,
                  extractionNotes = List.empty,
                ),
              ),
              businessCandidates = List.empty,
              infoNotes = List("Processed 2 of 2 entries"),
              extractionNotes = List.empty,
            )

            customerBookFileAiExtractPostResponse <- gatewayClient
              .customerBookFileAiExtractPost[smithy.InternalServerError](
                Some(organizationUserRow.organizationID),
                Some(customerBookFileExtractFileName),
                customerBookFileExtractFileBytes,
                Some(accessJwt.accessToken),
                None,
              )

            _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
            _ = customerBookFileAiExtractPostResponse.body.value shouldBe customerBookFileAiExtractPostResponseExpected

            customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIDsAll shouldBe empty
            _ = customerIndividualDetailsRowsAll shouldBe empty
            _ = customerBusinessDetailsRowsAll shouldBe empty
            _ = customerBusinessContactRowsAll shouldBe empty

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "successfully extract customer candidates from a WEBP image without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-2.webp")
          customerBookFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${customerBookFileExtractFileName.value}")
            .runCollect
          customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
            individualCandidates = List(
              CustomerIndividualCandidate(
                fullName = Some(CustomerFullName.assume("John Smith")),
                emails = List(
                  CustomerEmailEntry(email = CustomerEmail.assume("john.smith@example.com"), isDefault = true)
                ),
                phoneNumbers = List.empty,
                addresses = List.empty,
                inferredFields = List.empty,
                missingFields = List.empty,
                duplicateFields = List.empty,
                needsAttention = false,
                extractionNotes = List.empty,
              ),
              CustomerIndividualCandidate(
                fullName = Some(CustomerFullName.assume("Jane Doe")),
                emails = List(
                  CustomerEmailEntry(email = CustomerEmail.assume("jane.doe@example.com"), isDefault = true)
                ),
                phoneNumbers = List.empty,
                addresses = List.empty,
                inferredFields = List.empty,
                missingFields = List.empty,
                duplicateFields = List.empty,
                needsAttention = false,
                extractionNotes = List.empty,
              ),
            ),
            businessCandidates = List.empty,
            infoNotes = List("Processed 2 of 2 entries"),
            extractionNotes = List.empty,
          )

          customerBookFileAiExtractPostResponse <- gatewayClient
            .customerBookFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(customerBookFileExtractFileName),
              customerBookFileExtractFileBytes,
              Some(accessJwt.accessToken),
              None,
            )

          _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
          _ = customerBookFileAiExtractPostResponse.body.value shouldBe customerBookFileAiExtractPostResponseExpected

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe empty
          _ = customerIndividualDetailsRowsAll shouldBe empty
          _ = customerBusinessDetailsRowsAll shouldBe empty
          _ = customerBusinessContactRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "successfully extract customer candidates from CSV without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-spreadsheet-it-1.csv")
          customerBookFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${customerBookFileExtractFileName.value}")
            .runCollect
          customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
            individualCandidates = List(
              CustomerIndividualCandidate(
                fullName = Some(CustomerFullName.assume("John Smith")),
                emails = List(
                  CustomerEmailEntry(email = CustomerEmail.assume("john.smith@example.com"), isDefault = true)
                ),
                phoneNumbers = List.empty,
                addresses = List.empty,
                inferredFields = List.empty,
                missingFields = List.empty,
                duplicateFields = List.empty,
                needsAttention = false,
                extractionNotes = List.empty,
              ),
              CustomerIndividualCandidate(
                fullName = Some(CustomerFullName.assume("Jane Doe")),
                emails = List(
                  CustomerEmailEntry(email = CustomerEmail.assume("jane.doe@example.com"), isDefault = true)
                ),
                phoneNumbers = List.empty,
                addresses = List.empty,
                inferredFields = List.empty,
                missingFields = List.empty,
                duplicateFields = List.empty,
                needsAttention = false,
                extractionNotes = List.empty,
              ),
            ),
            businessCandidates = List.empty,
            infoNotes = List("Processed 2 of 2 entries"),
            extractionNotes = List.empty,
          )

          customerBookFileAiExtractPostResponse <- gatewayClient
            .customerBookFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(customerBookFileExtractFileName),
              customerBookFileExtractFileBytes,
              Some(accessJwt.accessToken),
              None,
            )

          _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
          _ = customerBookFileAiExtractPostResponse.body.value shouldBe customerBookFileAiExtractPostResponseExpected

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe empty
          _ = customerIndividualDetailsRowsAll shouldBe empty
          _ = customerBusinessDetailsRowsAll shouldBe empty
          _ = customerBusinessContactRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "successfully extract customer candidates from Excel without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-spreadsheet-it-2.xlsx")
          customerBookFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${customerBookFileExtractFileName.value}")
            .runCollect
          customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
            individualCandidates = List(
              CustomerIndividualCandidate(
                fullName = Some(CustomerFullName.assume("John Smith")),
                emails = List(
                  CustomerEmailEntry(email = CustomerEmail.assume("john.smith@example.com"), isDefault = true)
                ),
                phoneNumbers = List.empty,
                addresses = List.empty,
                inferredFields = List.empty,
                missingFields = List.empty,
                duplicateFields = List.empty,
                needsAttention = false,
                extractionNotes = List.empty,
              ),
              CustomerIndividualCandidate(
                fullName = Some(CustomerFullName.assume("Jane Doe")),
                emails = List(
                  CustomerEmailEntry(email = CustomerEmail.assume("jane.doe@example.com"), isDefault = true)
                ),
                phoneNumbers = List.empty,
                addresses = List.empty,
                inferredFields = List.empty,
                missingFields = List.empty,
                duplicateFields = List.empty,
                needsAttention = false,
                extractionNotes = List.empty,
              ),
            ),
            businessCandidates = List.empty,
            infoNotes = List("Processed 2 of 2 entries"),
            extractionNotes = List.empty,
          )

          customerBookFileAiExtractPostResponse <- gatewayClient
            .customerBookFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(customerBookFileExtractFileName),
              customerBookFileExtractFileBytes,
              Some(accessJwt.accessToken),
              None,
            )

          _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
          _ = customerBookFileAiExtractPostResponse.body.value shouldBe customerBookFileAiExtractPostResponseExpected

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe empty
          _ = customerIndividualDetailsRowsAll shouldBe empty
          _ = customerBusinessDetailsRowsAll shouldBe empty
          _ = customerBusinessContactRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "successfully extract customer candidates from XLS without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-spreadsheet-it-3.xls")
          customerBookFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${customerBookFileExtractFileName.value}")
            .runCollect
          customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
            individualCandidates = List(
              CustomerIndividualCandidate(
                fullName = Some(CustomerFullName.assume("John Smith")),
                emails = List(
                  CustomerEmailEntry(email = CustomerEmail.assume("john.smith@example.com"), isDefault = true)
                ),
                phoneNumbers = List.empty,
                addresses = List.empty,
                inferredFields = List.empty,
                missingFields = List.empty,
                duplicateFields = List.empty,
                needsAttention = false,
                extractionNotes = List.empty,
              ),
              CustomerIndividualCandidate(
                fullName = Some(CustomerFullName.assume("Jane Doe")),
                emails = List(
                  CustomerEmailEntry(email = CustomerEmail.assume("jane.doe@example.com"), isDefault = true)
                ),
                phoneNumbers = List.empty,
                addresses = List.empty,
                inferredFields = List.empty,
                missingFields = List.empty,
                duplicateFields = List.empty,
                needsAttention = false,
                extractionNotes = List.empty,
              ),
            ),
            businessCandidates = List.empty,
            infoNotes = List("Processed 2 of 2 entries"),
            extractionNotes = List.empty,
          )

          customerBookFileAiExtractPostResponse <- gatewayClient
            .customerBookFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(customerBookFileExtractFileName),
              customerBookFileExtractFileBytes,
              Some(accessJwt.accessToken),
              None,
            )

          _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
          _ = customerBookFileAiExtractPostResponse.body.value shouldBe customerBookFileAiExtractPostResponseExpected

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe empty
          _ = customerIndividualDetailsRowsAll shouldBe empty
          _ = customerBusinessDetailsRowsAll shouldBe empty
          _ = customerBusinessContactRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "successfully extract every candidate detail and processing note from an image without storing data" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
            customerBookFileExtractFileBytes <- ZStream
              .fromResource(s"assets/${customerBookFileExtractFileName.value}")
              .runCollect
            // Matched by the rich-candidate WireMock stub, which returns every candidate shape the service maps
            customerBookFileProcessingMessage     = "AI_CLIENT_SPEC_CUSTOMER_BOOK_FILE_RICH"
            customerIndividualCandidateAlexMorgan = CustomerIndividualCandidate(
              fullName = Some(CustomerFullName.assume("Alex Morgan")),
              emails = List(
                CustomerEmailEntry(email = CustomerEmail.assume("alex.morgan@example.com"), isDefault = true),
                CustomerEmailEntry(email = CustomerEmail.assume("alex@morgan-home.example.com"), isDefault = false),
              ),
              phoneNumbers = List(
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber = CustomerPhoneNumberCandidate(
                    phoneNationalNumber = PhoneNationalNumber.assume("99123456"),
                    phoneCountryCode = Some(PhoneCountryCode.assume("+357")),
                  ),
                  isDefault = true,
                )
              ),
              // A single kept address is always ShippingAndBilling, whatever the AI typed it as
              addresses = List(
                CustomerAddressEntry.assume(
                  AddressEntry(
                    address = Address(
                      addressLine1 = None,
                      addressLine2 = None,
                      city = Some(City.assume("Nicosia")),
                      postalCode = None,
                      country = Some(Country.assume("Cyprus")),
                    ),
                    addressType = AddressType.ShippingAndBilling,
                  )
                )
              ),
              inferredFields = List.empty,
              missingFields = List.empty,
              duplicateFields = List(FieldInfo("/fullName")),
              needsAttention = true,
              extractionNotes = List("Handwritten entry", "Street name could not be read"),
            )
            customerIndividualCandidateAlexMorganDuplicate = CustomerIndividualCandidate(
              fullName = Some(CustomerFullName.assume("Alex Morgan")),
              emails = List.empty,
              phoneNumbers = List.empty,
              addresses = List.empty,
              inferredFields = List.empty,
              missingFields = List.empty,
              duplicateFields = List(FieldInfo("/fullName")),
              needsAttention = true,
              extractionNotes = List.empty,
            )
            customerIndividualCandidateJaneSmithInferred = CustomerIndividualCandidate(
              fullName = Some(CustomerFullName.assume("Jane Smith")),
              emails = List(
                CustomerEmailEntry(email = CustomerEmail.assume("jane.smith@example.com"), isDefault = true)
              ),
              phoneNumbers = List.empty,
              addresses = List.empty,
              inferredFields = List(FieldInfo("/fullName")),
              missingFields = List.empty,
              duplicateFields = List.empty,
              needsAttention = false,
              extractionNotes = List("Name taken from the email address"),
            )
            customerIndividualCandidateNameless = CustomerIndividualCandidate(
              fullName = None,
              emails = List.empty,
              phoneNumbers = List(
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber = CustomerPhoneNumberCandidate(
                    phoneNationalNumber = PhoneNationalNumber.assume("99887766"),
                    phoneCountryCode = None,
                  ),
                  isDefault = true,
                ),
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber = CustomerPhoneNumberCandidate(
                    phoneNationalNumber = PhoneNationalNumber.assume("22334455"),
                    phoneCountryCode = Some(PhoneCountryCode.assume("+357")),
                  ),
                  isDefault = false,
                ),
              ),
              addresses = List.empty,
              inferredFields = List(FieldInfo("/phoneNumbers/1/phoneNumber/phoneCountryCode")),
              missingFields = List(FieldInfo("/fullName"), FieldInfo("/phoneNumbers/0/phoneNumber/phoneCountryCode")),
              duplicateFields = List.empty,
              needsAttention = true,
              extractionNotes = List("Phone country code is not shown"),
            )
            customerBusinessCandidateAcmeTools = CustomerBusinessCandidate(
              businessName = Some(CustomerBusinessName.assume("Acme Tools Ltd")),
              emails = List(
                CustomerEmailEntry(email = CustomerEmail.assume("orders@acme-tools.example.com"), isDefault = true)
              ),
              taxID = Some(CustomerTaxID.assume("CY10203040X")),
              phoneNumbers = List(
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber = CustomerPhoneNumberCandidate(
                    phoneNationalNumber = PhoneNationalNumber.assume("22445566"),
                    phoneCountryCode = Some(PhoneCountryCode.assume("+357")),
                  ),
                  isDefault = true,
                )
              ),
              // Only the first two addresses are kept; both were typed Billing, so they become Shipping then Billing
              addresses = List(
                CustomerAddressEntry.assume(
                  AddressEntry(
                    address = Address(
                      addressLine1 = Some(AddressLine1.assume("1 Main Street")),
                      addressLine2 = None,
                      city = Some(City.assume("Nicosia")),
                      postalCode = Some(PostalCode.assume("1010")),
                      country = Some(Country.assume("Cyprus")),
                    ),
                    addressType = AddressType.Shipping,
                  )
                ),
                CustomerAddressEntry.assume(
                  AddressEntry(
                    address = Address(
                      addressLine1 = Some(AddressLine1.assume("2 Harbour Road")),
                      addressLine2 = None,
                      city = Some(City.assume("Limassol")),
                      postalCode = Some(PostalCode.assume("3030")),
                      country = Some(Country.assume("Cyprus")),
                    ),
                    addressType = AddressType.Billing,
                  )
                ),
              ),
              customerBusinessContacts = List(
                CustomerBusinessContactCandidate(
                  fullName = None,
                  role = Some(CustomerBusinessContactRole.assume("Accounts")),
                  email = Some(CustomerEmail.assume("accounts@acme-tools.example.com")),
                  phoneNumber = Some(
                    CustomerPhoneNumberCandidate(
                      phoneNationalNumber = PhoneNationalNumber.assume("99001122"),
                      phoneCountryCode = Some(PhoneCountryCode.assume("+357")),
                    )
                  ),
                )
              ),
              inferredFields = List(
                FieldInfo("/phoneNumbers/0/phoneNumber/phoneCountryCode"),
                FieldInfo("/customerBusinessContacts/0/phoneNumber/phoneCountryCode"),
              ),
              missingFields = List(FieldInfo("/customerBusinessContacts/0/fullName")),
              duplicateFields = List.empty,
              needsAttention = true,
              extractionNotes = List(
                "Letterhead logo was not used",
                "Warehouse address",
                "Address left out: 3 Market Square",
                "Contact name is not shown",
              ),
            )
            customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
              individualCandidates = List(
                customerIndividualCandidateAlexMorgan,
                customerIndividualCandidateAlexMorganDuplicate,
                customerIndividualCandidateJaneSmithInferred,
                customerIndividualCandidateNameless,
              ),
              businessCandidates = List(customerBusinessCandidateAcmeTools),
              infoNotes = List("Processed 4 of 5 entries", "Candidates created 5", "Unidentified rows (1) at [6]"),
              extractionNotes = List("One entry at the bottom could not be read"),
            )

            customerBookFileAiExtractPostResponse <- gatewayClient
              .customerBookFileAiExtractPost[smithy.InternalServerError](
                Some(organizationUserRow.organizationID),
                Some(customerBookFileExtractFileName),
                customerBookFileExtractFileBytes,
                Some(accessJwt.accessToken),
                Some(customerBookFileProcessingMessage),
              )

            _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
            _ = customerBookFileAiExtractPostResponse.body.value shouldBe customerBookFileAiExtractPostResponseExpected

            customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIDsAll shouldBe empty
            _ = customerIndividualDetailsRowsAll shouldBe empty
            _ = customerBusinessDetailsRowsAll shouldBe empty
            _ = customerBusinessContactRowsAll shouldBe empty

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "successfully accept processing guidance at the length limit after trimming without storing data" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
            customerBookFileExtractFileBytes <- ZStream
              .fromResource(s"assets/${customerBookFileExtractFileName.value}")
              .runCollect
            processingMessageMaxLength                    = 2000
            customerBookFileProcessingMessage             = s"  ${"a" * processingMessageMaxLength}  "
            customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
              individualCandidates = List(
                CustomerIndividualCandidate(
                  fullName = Some(CustomerFullName.assume("John Smith")),
                  emails = List(
                    CustomerEmailEntry(email = CustomerEmail.assume("john.smith@example.com"), isDefault = true)
                  ),
                  phoneNumbers = List.empty,
                  addresses = List.empty,
                  inferredFields = List.empty,
                  missingFields = List.empty,
                  duplicateFields = List.empty,
                  needsAttention = false,
                  extractionNotes = List.empty,
                ),
                CustomerIndividualCandidate(
                  fullName = Some(CustomerFullName.assume("Jane Doe")),
                  emails = List(
                    CustomerEmailEntry(email = CustomerEmail.assume("jane.doe@example.com"), isDefault = true)
                  ),
                  phoneNumbers = List.empty,
                  addresses = List.empty,
                  inferredFields = List.empty,
                  missingFields = List.empty,
                  duplicateFields = List.empty,
                  needsAttention = false,
                  extractionNotes = List.empty,
                ),
              ),
              businessCandidates = List.empty,
              infoNotes = List("Processed 2 of 2 entries"),
              extractionNotes = List.empty,
            )

            customerBookFileAiExtractPostResponse <- gatewayClient
              .customerBookFileAiExtractPost[smithy.InternalServerError](
                Some(organizationUserRow.organizationID),
                Some(customerBookFileExtractFileName),
                customerBookFileExtractFileBytes,
                Some(accessJwt.accessToken),
                Some(customerBookFileProcessingMessage),
              )

            _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
            _ = customerBookFileAiExtractPostResponse.body.value shouldBe customerBookFileAiExtractPostResponseExpected

            customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIDsAll shouldBe empty
            _ = customerIndividualDetailsRowsAll shouldBe empty
            _ = customerBusinessDetailsRowsAll shouldBe empty
            _ = customerBusinessContactRowsAll shouldBe empty

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "successfully treat blank processing guidance as no guidance without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
          customerBookFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${customerBookFileExtractFileName.value}")
            .runCollect
          customerBookFileProcessingMessage             = "   "
          customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
            individualCandidates = List(
              CustomerIndividualCandidate(
                fullName = Some(CustomerFullName.assume("John Smith")),
                emails = List(
                  CustomerEmailEntry(email = CustomerEmail.assume("john.smith@example.com"), isDefault = true)
                ),
                phoneNumbers = List.empty,
                addresses = List.empty,
                inferredFields = List.empty,
                missingFields = List.empty,
                duplicateFields = List.empty,
                needsAttention = false,
                extractionNotes = List.empty,
              ),
              CustomerIndividualCandidate(
                fullName = Some(CustomerFullName.assume("Jane Doe")),
                emails = List(
                  CustomerEmailEntry(email = CustomerEmail.assume("jane.doe@example.com"), isDefault = true)
                ),
                phoneNumbers = List.empty,
                addresses = List.empty,
                inferredFields = List.empty,
                missingFields = List.empty,
                duplicateFields = List.empty,
                needsAttention = false,
                extractionNotes = List.empty,
              ),
            ),
            businessCandidates = List.empty,
            infoNotes = List("Processed 2 of 2 entries"),
            extractionNotes = List.empty,
          )

          customerBookFileAiExtractPostResponse <- gatewayClient
            .customerBookFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(customerBookFileExtractFileName),
              customerBookFileExtractFileBytes,
              Some(accessJwt.accessToken),
              Some(customerBookFileProcessingMessage),
            )

          _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
          _ = customerBookFileAiExtractPostResponse.body.value shouldBe customerBookFileAiExtractPostResponseExpected

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe empty
          _ = customerIndividualDetailsRowsAll shouldBe empty
          _ = customerBusinessDetailsRowsAll shouldBe empty
          _ = customerBusinessContactRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "successfully return the repeated general notes of a CSV read in several groups as one compacted note without storing data" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume(
              "contact-book-spreadsheet-it-4.csv"
            )
            customerBookFileExtractFileBytes <- ZStream
              .fromResource(s"assets/${customerBookFileExtractFileName.value}")
              .runCollect
            // Matched by the batch-notes WireMock stub, which returns the same general note for every group
            customerBookFileProcessingMessage             = "AI_CLIENT_SPEC_BATCH_NOTES"
            customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
              individualCandidates = List.empty,
              businessCandidates = List.empty,
              infoNotes = List("Processed 0 of 0 entries"),
              extractionNotes = List("Column Balance was ignored across the file"),
            )

            customerBookFileAiExtractPostResponse <- gatewayClient
              .customerBookFileAiExtractPost[smithy.InternalServerError](
                Some(organizationUserRow.organizationID),
                Some(customerBookFileExtractFileName),
                customerBookFileExtractFileBytes,
                Some(accessJwt.accessToken),
                Some(customerBookFileProcessingMessage),
              )

            _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
            _ = customerBookFileAiExtractPostResponse.body.value shouldBe customerBookFileAiExtractPostResponseExpected

            customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIDsAll shouldBe empty
            _ = customerIndividualDetailsRowsAll shouldBe empty
            _ = customerBusinessDetailsRowsAll shouldBe empty
            _ = customerBusinessContactRowsAll shouldBe empty

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with a BadRequest when the file name header is missing without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
          customerBookFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${customerBookFileExtractFileName.value}")
            .runCollect

          customerBookFileAiExtractPostResponse <- gatewayClient.customerBookFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            None,
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )

          _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
          _ = customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe empty
          _ = customerIndividualDetailsRowsAll shouldBe empty
          _ = customerBusinessDetailsRowsAll shouldBe empty
          _ = customerBusinessContactRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the file name is blank without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("   ")
          customerBookFileExtractFileBytes <- ZStream.fromResource("assets/contact-book-image-it-1.jpeg").runCollect

          customerBookFileAiExtractPostResponse <- gatewayClient.customerBookFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )

          _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
          _ = customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe empty
          _ = customerIndividualDetailsRowsAll shouldBe empty
          _ = customerBusinessDetailsRowsAll shouldBe empty
          _ = customerBusinessContactRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the file name is longer than allowed without storing data" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookFileExtractFileNameOverMaxLength = 256
            customerBookFileExtractFileName              = CustomerBookFileExtractFileName.assume(
              "a" * (customerBookFileExtractFileNameOverMaxLength - ".jpeg".length) + ".jpeg"
            )
            customerBookFileExtractFileBytes <- ZStream.fromResource("assets/contact-book-image-it-1.jpeg").runCollect

            customerBookFileAiExtractPostResponse <- gatewayClient.customerBookFileAiExtractPost[smithy.BadRequest](
              Some(organizationUserRow.organizationID),
              Some(customerBookFileExtractFileName),
              customerBookFileExtractFileBytes,
              Some(accessJwt.accessToken),
              None,
            )

            _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
            _ = customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

            customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIDsAll shouldBe empty
            _ = customerIndividualDetailsRowsAll shouldBe empty
            _ = customerBusinessDetailsRowsAll shouldBe empty
            _ = customerBusinessContactRowsAll shouldBe empty

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with a BadRequest when processing guidance is too long without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
          customerBookFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${customerBookFileExtractFileName.value}")
            .runCollect
          processingMessageOverMaxLength    = 2001
          customerBookFileProcessingMessage = "a" * processingMessageOverMaxLength

          customerBookFileAiExtractPostResponse <- gatewayClient.customerBookFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            Some(customerBookFileProcessingMessage),
          )

          _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
          _ = customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe empty
          _ = customerIndividualDetailsRowsAll shouldBe empty
          _ = customerBusinessDetailsRowsAll shouldBe empty
          _ = customerBusinessContactRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the file name has no extension without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image")
          customerBookFileExtractFileBytes <- ZStream.fromResource("assets/contact-book-image-it-1.jpeg").runCollect

          customerBookFileAiExtractPostResponse <- gatewayClient.customerBookFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )

          _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
          _ = customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe empty
          _ = customerIndividualDetailsRowsAll shouldBe empty
          _ = customerBusinessDetailsRowsAll shouldBe empty
          _ = customerBusinessContactRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the file extension is unsupported without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image.pdf")
          customerBookFileExtractFileBytes <- ZStream.fromResource("assets/contact-book-image-it-1.jpeg").runCollect

          customerBookFileAiExtractPostResponse <- gatewayClient.customerBookFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )

          _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
          _ = customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe empty
          _ = customerIndividualDetailsRowsAll shouldBe empty
          _ = customerBusinessDetailsRowsAll shouldBe empty
          _ = customerBusinessContactRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the filename and content disagree without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image.png")
          customerBookFileExtractFileBytes <- ZStream.fromResource("assets/contact-book-image-it-1.jpeg").runCollect

          customerBookFileAiExtractPostResponse <- gatewayClient.customerBookFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )

          _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
          _ = customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe empty
          _ = customerIndividualDetailsRowsAll shouldBe empty
          _ = customerBusinessDetailsRowsAll shouldBe empty
          _ = customerBusinessContactRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with an InternalServerError when the Excel upload is not a workbook without storing data" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book.xlsx")
            customerBookFileExtractFileBytes <- ZStream
              .fromResource("assets/contact-book-compressed-it-1.zip")
              .runCollect

            customerBookFileAiExtractPostResponse <- gatewayClient
              .customerBookFileAiExtractPost[smithy.InternalServerError](
                Some(organizationUserRow.organizationID),
                Some(customerBookFileExtractFileName),
                customerBookFileExtractFileBytes,
                Some(accessJwt.accessToken),
                None,
              )

            _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
            _ = customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

            customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIDsAll shouldBe empty
            _ = customerIndividualDetailsRowsAll shouldBe empty
            _ = customerBusinessDetailsRowsAll shouldBe empty
            _ = customerBusinessContactRowsAll shouldBe empty

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with an InternalServerError when image AI extraction fails without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
          customerBookFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${customerBookFileExtractFileName.value}")
            .runCollect
          customerBookFileProcessingMessage = "AI_CLIENT_SPEC_ERROR"

          customerBookFileAiExtractPostResponse <- gatewayClient
            .customerBookFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(customerBookFileExtractFileName),
              customerBookFileExtractFileBytes,
              Some(accessJwt.accessToken),
              Some(customerBookFileProcessingMessage),
            )

          _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
          _ = customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll shouldBe empty
          _ = customerIndividualDetailsRowsAll shouldBe empty
          _ = customerBusinessDetailsRowsAll shouldBe empty
          _ = customerBusinessContactRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with an InternalServerError when the AI result is malformed without storing data" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
            customerBookFileExtractFileBytes <- ZStream
              .fromResource(s"assets/${customerBookFileExtractFileName.value}")
              .runCollect
            customerBookFileProcessingMessage = "AI_CLIENT_SPEC_MALFORMED"

            customerBookFileAiExtractPostResponse <- gatewayClient
              .customerBookFileAiExtractPost[smithy.InternalServerError](
                Some(organizationUserRow.organizationID),
                Some(customerBookFileExtractFileName),
                customerBookFileExtractFileBytes,
                Some(accessJwt.accessToken),
                Some(customerBookFileProcessingMessage),
              )

            _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
            _ = customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

            customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIDsAll shouldBe empty
            _ = customerIndividualDetailsRowsAll shouldBe empty
            _ = customerBusinessDetailsRowsAll shouldBe empty
            _ = customerBusinessContactRowsAll shouldBe empty

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with an InternalServerError when spreadsheet AI extraction fails without storing data" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume(
              "contact-book-spreadsheet-it-1.csv"
            )
            customerBookFileExtractFileBytes <- ZStream
              .fromResource(s"assets/${customerBookFileExtractFileName.value}")
              .runCollect
            customerBookFileProcessingMessage = "AI_CLIENT_SPEC_ERROR"

            customerBookFileAiExtractPostResponse <- gatewayClient
              .customerBookFileAiExtractPost[smithy.InternalServerError](
                Some(organizationUserRow.organizationID),
                Some(customerBookFileExtractFileName),
                customerBookFileExtractFileBytes,
                Some(accessJwt.accessToken),
                Some(customerBookFileProcessingMessage),
              )

            _ = customerBookFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
            _ = customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

            customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIDsAll shouldBe empty
            _ = customerIndividualDetailsRowsAll shouldBe empty
            _ = customerBusinessDetailsRowsAll shouldBe empty
            _ = customerBusinessContactRowsAll shouldBe empty

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }
    }

    "middleware" should {
      "fail with a BadRequest when the organization id header is missing on every endpoint" in withContext { context =>
        import context.*

        for {
          organizationID               = arbitrarySample[OrganizationID]
          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(organizationID = organizationID, isArchived = false)
          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
            .copy(organizationID = organizationID, isArchived = false)
          customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
          )
          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
          )
          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow))
          )

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
          customerBookFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${customerBookFileExtractFileName.value}")
            .runCollect

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.customerBookFileAiExtractPost[smithy.BadRequest](
                None,
                Some(customerBookFileExtractFileName),
                customerBookFileExtractFileBytes,
                Some(accessJwt.accessToken),
                None,
              )
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.BadRequest)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.BadRequest()))

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll.toSet shouldBe Set(
            customerIndividualDetailsRow.customerID,
            customerBusinessDetailsRow.customerID,
          )
          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
          _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
          _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with an Unauthorized when the access token is missing on every endpoint" in withContext { context =>
        import context.*

        for {
          organizationID               = arbitrarySample[OrganizationID]
          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(organizationID = organizationID, isArchived = false)
          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
            .copy(organizationID = organizationID, isArchived = false)
          customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
          )
          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
          )
          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow))
          )

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            organizationID = organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
          customerBookFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${customerBookFileExtractFileName.value}")
            .runCollect

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.customerBookFileAiExtractPost[smithy.Unauthorized](
                Some(organizationID),
                Some(customerBookFileExtractFileName),
                customerBookFileExtractFileBytes,
                None,
                None,
              )
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.Unauthorized)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.Unauthorized()))

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll.toSet shouldBe Set(
            customerIndividualDetailsRow.customerID,
            customerBusinessDetailsRow.customerID,
          )
          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
          _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
          _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with an Unauthorized when the access token is invalid on every endpoint" in withContext { context =>
        import context.*

        for {
          organizationID               = arbitrarySample[OrganizationID]
          customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
            .copy(organizationID = organizationID, isArchived = false)
          customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
            .copy(organizationID = organizationID, isArchived = false)
          customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
            organizationID = organizationID,
            customerID = customerBusinessDetailsRow.customerID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
          )
          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
          )
          _ <- postgresClient.executeQuery(
            customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow))
          )

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            organizationID = organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
          customerBookFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${customerBookFileExtractFileName.value}")
            .runCollect

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.customerBookFileAiExtractPost[smithy.Unauthorized](
                Some(organizationID),
                Some(customerBookFileExtractFileName),
                customerBookFileExtractFileBytes,
                Some(AccessToken("invalidtoken")),
                None,
              )
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.Unauthorized)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.Unauthorized()))

          customerIDsAll                   <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
          customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
          )
          customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
          )
          customerBusinessContactRowsAll <- postgresClient.executeQuery(
            customerBookQueries.getAllCustomerBusinessContactRowsTesting
          )

          _ = customerIDsAll.toSet shouldBe Set(
            customerIndividualDetailsRow.customerID,
            customerBusinessDetailsRow.customerID,
          )
          _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
          _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
          _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a Forbidden when the user is not in a completed onboard stage on every endpoint" in withContext {
        context =>
          import context.*

          for {
            organizationID               = arbitrarySample[OrganizationID]
            customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
              .copy(organizationID = organizationID, isArchived = false)
            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
              .copy(organizationID = organizationID, isArchived = false)
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow))
            )

            onboardStageInvalid <- Random
              .shuffle(OnboardStage.values.toList diff OnboardStage.completedStages)
              .map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStageInvalid)
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              organizationID = organizationID,
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
            customerBookFileExtractFileBytes <- ZStream
              .fromResource(s"assets/${customerBookFileExtractFileName.value}")
              .runCollect

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.customerBookFileAiExtractPost[smithy.Forbidden](
                  Some(organizationID),
                  Some(customerBookFileExtractFileName),
                  customerBookFileExtractFileBytes,
                  Some(accessJwt.accessToken),
                  None,
                )
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.Forbidden)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.Forbidden()))

            customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIDsAll.toSet shouldBe Set(
              customerIndividualDetailsRow.customerID,
              customerBusinessDetailsRow.customerID,
            )
            _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with a Forbidden when the organization user role is not allowed on every endpoint" in withContext {
        context =>
          import context.*

          for {
            organizationID               = arbitrarySample[OrganizationID]
            customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
              .copy(organizationID = organizationID, isArchived = false)
            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
              .copy(organizationID = organizationID, isArchived = false)
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow))
            )

            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRoleInvalid <- Random
              .shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles)
              .map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              organizationID = organizationID,
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleInvalid,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
            customerBookFileExtractFileBytes <- ZStream
              .fromResource(s"assets/${customerBookFileExtractFileName.value}")
              .runCollect

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.customerBookFileAiExtractPost[smithy.Forbidden](
                  Some(organizationID),
                  Some(customerBookFileExtractFileName),
                  customerBookFileExtractFileBytes,
                  Some(accessJwt.accessToken),
                  None,
                )
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.Forbidden)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.Forbidden()))

            customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIDsAll.toSet shouldBe Set(
              customerIndividualDetailsRow.customerID,
              customerBusinessDetailsRow.customerID,
            )
            _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with an InternalServerError when the user details do not exist on every endpoint" in withContext {
        context =>
          import context.*

          for {
            organizationID               = arbitrarySample[OrganizationID]
            customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
              .copy(organizationID = organizationID, isArchived = false)
            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
              .copy(organizationID = organizationID, isArchived = false)
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow))
            )

            userID = arbitrarySample[UserID]
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              organizationID = organizationID,
              userID = userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userID)

            customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
            customerBookFileExtractFileBytes <- ZStream
              .fromResource(s"assets/${customerBookFileExtractFileName.value}")
              .runCollect

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.customerBookFileAiExtractPost[smithy.InternalServerError](
                  Some(organizationID),
                  Some(customerBookFileExtractFileName),
                  customerBookFileExtractFileBytes,
                  Some(accessJwt.accessToken),
                  None,
                )
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.InternalServerError)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.InternalServerError()))

            customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIDsAll.toSet shouldBe Set(
              customerIndividualDetailsRow.customerID,
              customerBusinessDetailsRow.customerID,
            )
            _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with an InternalServerError when the user is not a member of the organization on every endpoint" in withContext {
        context =>
          import context.*

          for {
            organizationID               = arbitrarySample[OrganizationID]
            customerIndividualDetailsRow = arbitrarySample[CustomerIndividualDetailsRow]
              .copy(organizationID = organizationID, isArchived = false)
            customerBusinessDetailsRow = arbitrarySample[CustomerBusinessDetailsRow]
              .copy(organizationID = organizationID, isArchived = false)
            customerBusinessContactRow = arbitrarySample[CustomerBusinessContactRow].copy(
              organizationID = organizationID,
              customerID = customerBusinessDetailsRow.customerID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerIndividualDetailsRow(customerIndividualDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessDetailsRow(customerBusinessDetailsRow)
            )
            _ <- postgresClient.executeQuery(
              customerBookQueries.insertCustomerBusinessContactRows(List(customerBusinessContactRow))
            )

            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            customerBookFileExtractFileName = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
            customerBookFileExtractFileBytes <- ZStream
              .fromResource(s"assets/${customerBookFileExtractFileName.value}")
              .runCollect

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.customerBookFileAiExtractPost[smithy.InternalServerError](
                  Some(organizationID),
                  Some(customerBookFileExtractFileName),
                  customerBookFileExtractFileBytes,
                  Some(accessJwt.accessToken),
                  None,
                )
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.InternalServerError)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.InternalServerError()))

            customerIDsAll <- postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting)
            customerIndividualDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerIndividualDetailsRowsTesting
            )
            customerBusinessDetailsRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessDetailsRowsTesting
            )
            customerBusinessContactRowsAll <- postgresClient.executeQuery(
              customerBookQueries.getAllCustomerBusinessContactRowsTesting
            )

            _ = customerIDsAll.toSet shouldBe Set(
              customerIndividualDetailsRow.customerID,
              customerBusinessDetailsRow.customerID,
            )
            _ = customerIndividualDetailsRowsAll shouldBe List(customerIndividualDetailsRow)
            _ = customerBusinessDetailsRowsAll shouldBe List(customerBusinessDetailsRow)
            _ = customerBusinessContactRowsAll shouldBe List(customerBusinessContactRow)

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }
    }
  }
}
