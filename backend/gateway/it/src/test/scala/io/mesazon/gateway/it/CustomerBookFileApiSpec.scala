package io.mesazon.gateway.it

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.given
import io.mesazon.gateway.it.harness.GatewayAcceptanceTest
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.*
import org.scalatest.DoNotDiscover
import sttp.model.*
import zio.*
import zio.stream.ZStream

@DoNotDiscover
class CustomerBookFileApiSpec extends GatewayAcceptanceTest, RepositoryArbitraries {

  private val organizationMediaBucket = "organization-media"

  "Customer Book File Service API" when {
    "/customer-book/file/ai/extract" should {
      "extract customer candidates from an image without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue
        val customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
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

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
        customerBookFileAiExtractPostResponse.body shouldBe Right(customerBookFileAiExtractPostResponseExpected)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "extract customer candidates from CSV without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName =
          CustomerBookFileExtractFileName.assume("contact-book-spreadsheet-it-1.csv")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue
        val customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
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

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
        customerBookFileAiExtractPostResponse.body shouldBe Right(customerBookFileAiExtractPostResponseExpected)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "extract customer candidates from Excel without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName =
          CustomerBookFileExtractFileName.assume("contact-book-spreadsheet-it-2.xlsx")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue
        val customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
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

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
        customerBookFileAiExtractPostResponse.body shouldBe Right(customerBookFileAiExtractPostResponseExpected)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "extract customer candidates from XLS without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName =
          CustomerBookFileExtractFileName.assume("contact-book-spreadsheet-it-3.xls")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue
        val customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
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

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
        customerBookFileAiExtractPostResponse.body shouldBe Right(customerBookFileAiExtractPostResponseExpected)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "accept processing guidance at the length limit after trimming" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue
        val processingMessageMaxLength                    = 2000
        val customerBookFileProcessingMessage             = s"  ${"a" * processingMessageMaxLength}  "
        val customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
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

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            Some(customerBookFileProcessingMessage),
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
        customerBookFileAiExtractPostResponse.body shouldBe Right(customerBookFileAiExtractPostResponseExpected)

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "extract customer candidates from a CSV read in several groups, compacting the repeated general notes, without storing data" in withContext {
        context =>
          import context.*

          val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )
          val customerBookFileExtractFileName =
            CustomerBookFileExtractFileName.assume("contact-book-spreadsheet-it-4.csv")
          val customerBookFileExtractFileBytes =
            ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue
          val customerBookFileProcessingMessage             = "AI_CLIENT_SPEC_BATCH_NOTES"
          val customerBookFileAiExtractPostResponseExpected = CustomerBookFileAiExtractPostResponse(
            individualCandidates = List.empty,
            businessCandidates = List.empty,
            infoNotes = List("Processed 0 of 0 entries"),
            extractionNotes = List("Column Balance was ignored"),
          )

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookFileAiExtractPostResponse = gatewayClient
            .customerBookFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(customerBookFileExtractFileName),
              customerBookFileExtractFileBytes,
              Some(accessJwt.accessToken),
              Some(customerBookFileProcessingMessage),
            )
            .zioValue

          customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Ok
          customerBookFileAiExtractPostResponse.body shouldBe Right(customerBookFileAiExtractPostResponseExpected)

          postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe List.empty
          s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with a BadRequest when the file name header is missing without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            None,
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
        customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with a BadRequest when the organization id header is missing without storing data" in withContext {
        context =>
          import context.*

          val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )
          val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
          val customerBookFileExtractFileBytes =
            ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookFileAiExtractPostResponse = gatewayClient
            .customerBookFileAiExtractPost[smithy.BadRequest](
              None,
              Some(customerBookFileExtractFileName),
              customerBookFileExtractFileBytes,
              Some(accessJwt.accessToken),
              None,
            )
            .zioValue

          customerBookFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
          customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

          postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe List.empty
          s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with a BadRequest when processing guidance is too long without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue
        val processingMessageOverMaxLength    = 2001
        val customerBookFileProcessingMessage = "a" * processingMessageOverMaxLength

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            Some(customerBookFileProcessingMessage),
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
        customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with a BadRequest when the file name has no extension without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource("assets/contact-book-image-it-1.jpeg").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
        customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with a BadRequest when the file extension is unsupported without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image.pdf")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource("assets/contact-book-image-it-1.jpeg").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
        customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with a BadRequest when the filename and content disagree without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image.png")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource("assets/contact-book-image-it-1.jpeg").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
        customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with an Unauthorized when the access token is missing without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.Unauthorized](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            None,
            None,
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Unauthorized
        customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with an Unauthorized when the access token is invalid without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.Unauthorized](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(AccessToken("invalidtoken")),
            None,
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Unauthorized
        customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with a Forbidden when the organization role is disallowed without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.Forbidden](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Forbidden
        customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with a Forbidden when onboarding is incomplete without storing data" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.Forbidden](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.Forbidden
        customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with an InternalServerError when user details are missing without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
        customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with an InternalServerError when organization membership is missing without storing data" in withContext {
        context =>
          import context.*

          val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )
          val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
          val customerBookFileExtractFileBytes =
            ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookFileAiExtractPostResponse = gatewayClient
            .customerBookFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(customerBookFileExtractFileName),
              customerBookFileExtractFileBytes,
              Some(accessJwt.accessToken),
              None,
            )
            .zioValue

          customerBookFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
          customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe List.empty
          s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with an InternalServerError when the Excel upload is not a workbook without storing data" in withContext {
        context =>
          import context.*

          val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )
          val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book.xlsx")
          val customerBookFileExtractFileBytes =
            ZStream.fromResource("assets/contact-book-compressed-it-1.zip").runCollect.zioValue

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookFileAiExtractPostResponse = gatewayClient
            .customerBookFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(customerBookFileExtractFileName),
              customerBookFileExtractFileBytes,
              Some(accessJwt.accessToken),
              None,
            )
            .zioValue

          customerBookFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
          customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe List.empty
          s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with an InternalServerError when image AI extraction fails without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
        val customerBookFileExtractFileBytes =
          ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileAiExtractPostResponse = gatewayClient
          .customerBookFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(customerBookFileExtractFileName),
            customerBookFileExtractFileBytes,
            Some(accessJwt.accessToken),
            Some("AI_CLIENT_SPEC_ERROR"),
          )
          .zioValue

        customerBookFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
        customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
          .zioValue shouldBe List.empty
        postgresClient
          .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
          .zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with an InternalServerError when the AI result is malformed without storing data" in withContext {
        context =>
          import context.*

          val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )
          val customerBookFileExtractFileName  = CustomerBookFileExtractFileName.assume("contact-book-image-it-1.jpeg")
          val customerBookFileExtractFileBytes =
            ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookFileAiExtractPostResponse = gatewayClient
            .customerBookFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(customerBookFileExtractFileName),
              customerBookFileExtractFileBytes,
              Some(accessJwt.accessToken),
              Some("AI_CLIENT_SPEC_MALFORMED"),
            )
            .zioValue

          customerBookFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
          customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe List.empty
          s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with an InternalServerError when spreadsheet AI extraction fails without storing data" in withContext {
        context =>
          import context.*

          val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )
          val customerBookFileExtractFileName =
            CustomerBookFileExtractFileName.assume("contact-book-spreadsheet-it-1.csv")
          val customerBookFileExtractFileBytes =
            ZStream.fromResource(s"assets/${customerBookFileExtractFileName.value}").runCollect.zioValue

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val customerBookFileAiExtractPostResponse = gatewayClient
            .customerBookFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(customerBookFileExtractFileName),
              customerBookFileExtractFileBytes,
              Some(accessJwt.accessToken),
              Some("AI_CLIENT_SPEC_ERROR"),
            )
            .zioValue

          customerBookFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
          customerBookFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerIndividualDetailsRowsTesting)
            .zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessDetailsRowsTesting)
            .zioValue shouldBe List.empty
          postgresClient
            .executeQuery(customerBookQueries.getAllCustomerBusinessContactRowsTesting)
            .zioValue shouldBe List.empty
          s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }
    }
  }
}
