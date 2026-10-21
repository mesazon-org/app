package io.mesazon.gateway.it

import io.github.iltotore.iron.chimney.given
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.it.client.GatewayClient.given
import io.mesazon.gateway.it.harness.GatewayAcceptanceTest
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.transform.given
import io.mesazon.gateway.utils.*
import io.scalaland.chimney.dsl.*
import org.scalacheck.Gen
import org.scalatest.DoNotDiscover
import smithy4s.Nullable
import sttp.model.*
import zio.*

@DoNotDiscover
class CatalogueApiSpec extends GatewayAcceptanceTest, CatalogueSmithyArbitraries, RepositoryArbitraries {

  "Catalogue Service API" when {
    "POST /catalogue/item" should {
      "successfully insert a catalogue item with multiple variants" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemVariantSku1 = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("sku1"))))
        val catalogueItemVariantSku2 = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("sku2"))))

        val catalogueItemVariantInsert1 = arbitrarySample[CatalogueItemVariantInsert]
          .copy(sku = catalogueItemVariantSku1)
        val catalogueItemVariantInsert2 = arbitrarySample[CatalogueItemVariantInsert]
          .copy(sku = catalogueItemVariantSku2)

        val catalogueItemPostRequest = CatalogueItemPostRequest(
          name = arbitrarySample[CatalogueItemName],
          description = arbitrarySample[Option[CatalogueItemDescription]],
          variants = NonEmptyChunk.fromIterable(
            catalogueItemVariantInsert1,
            List(catalogueItemVariantInsert2),
          ),
        )

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemPostResponse = gatewayClient
          .catalogueItemPost[smithy.InternalServerError](
            catalogueItemPostRequest.transformInto[smithy.CatalogueItemPostRequest],
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPostResponse.code shouldBe StatusCode.Ok

        val catalogueItemRows =
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue
        val catalogueItemVariantRows =
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue

        catalogueItemRows should have size 1
        catalogueItemVariantRows should have size 2

        val catalogueItemRow = catalogueItemRows.head
        catalogueItemPostResponse.body.value shouldBe smithy.CatalogueItemPostResponse(
          catalogueItemID = catalogueItemRow.catalogueItemID.value
        )
        catalogueItemRow shouldBe CatalogueItemRow(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          name = catalogueItemPostRequest.name,
          description = catalogueItemPostRequest.description,
          imageAsset = None,
          status = CatalogueItemStatus.Active,
          isArchived = false,
          createdAt = catalogueItemRow.createdAt,
          updatedAt = catalogueItemRow.updatedAt,
        )
        catalogueItemRow.updatedAt.value shouldBe catalogueItemRow.createdAt.value

        val catalogueItemVariantRowsExpected = catalogueItemPostRequest.variants.toList.map {
          catalogueItemVariantInsert =>
            catalogueItemVariantRows.find { catalogueItemVariantRow =>
              catalogueItemVariantRow == CatalogueItemVariantRow(
                organizationID = organizationUserRow.organizationID,
                catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID,
                catalogueItemID = catalogueItemRow.catalogueItemID,
                sku = catalogueItemVariantInsert.sku,
                label = catalogueItemVariantInsert.label,
                description = catalogueItemVariantInsert.description,
                attributes = catalogueItemVariantInsert.attributes,
                price = catalogueItemVariantInsert.price,
                sellingUnit = catalogueItemVariantInsert.sellingUnit,
                status = CatalogueItemVariantStatus.Active,
                isArchived = false,
                createdAt = catalogueItemRow.createdAt,
                updatedAt = catalogueItemRow.updatedAt,
              )
            }.value
        }

        catalogueItemVariantRows should contain theSameElementsAs catalogueItemVariantRowsExpected
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt                 = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemPostResponse = gatewayClient
          .catalogueItemPost[smithy.BadRequest](
            arbitrarySample[smithy.CatalogueItemPostRequest],
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPostResponse.code shouldBe StatusCode.BadRequest
        catalogueItemPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a ValidationError when the catalogue item has no variants" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemPostRequest = smithy.CatalogueItemPostRequest(
          name = arbitrarySample[CatalogueItemName].value,
          description = arbitrarySample[Option[CatalogueItemDescription]].map(_.value),
          variants = Nil,
        )
        val accessJwt                 = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemPostResponse = gatewayClient
          .catalogueItemPost[smithy.ValidationError](
            catalogueItemPostRequest,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPostResponse.code shouldBe StatusCode.BadRequest
        catalogueItemPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("variant"))

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a ValidationError containing item and variant fields in declaration order" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemVariantInsert = arbitrarySample[CatalogueItemVariantInsert]
          .transformInto[smithy.CatalogueItemVariantInsert]
          .copy(sku = Some(""))
        val catalogueItemPostRequest = smithy.CatalogueItemPostRequest(
          name = "",
          description = arbitrarySample[Option[CatalogueItemDescription]].map(_.value),
          variants = List(catalogueItemVariantInsert),
        )
        val accessJwt                 = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemPostResponse = gatewayClient
          .catalogueItemPost[smithy.ValidationError](
            catalogueItemPostRequest,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPostResponse.code shouldBe StatusCode.BadRequest
        catalogueItemPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("name", "variant"))

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val organizationID            = arbitrarySample[OrganizationID]
        val catalogueItemPostResponse = gatewayClient
          .catalogueItemPost[smithy.Unauthorized](
            arbitrarySample[smithy.CatalogueItemPostRequest],
            Some(organizationID),
            None,
          )
          .zioValue

        catalogueItemPostResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val organizationID            = arbitrarySample[OrganizationID]
        val catalogueItemPostResponse = gatewayClient
          .catalogueItemPost[smithy.Unauthorized](
            arbitrarySample[smithy.CatalogueItemPostRequest],
            Some(organizationID),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        catalogueItemPostResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt                 = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemPostResponse = gatewayClient
          .catalogueItemPost[smithy.Forbidden](
            arbitrarySample[smithy.CatalogueItemPostRequest],
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPostResponse.code shouldBe StatusCode.Forbidden
        catalogueItemPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRoleInvalid =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRoleInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt                 = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemPostResponse = gatewayClient
          .catalogueItemPost[smithy.Forbidden](
            arbitrarySample[smithy.CatalogueItemPostRequest],
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPostResponse.code shouldBe StatusCode.Forbidden
        catalogueItemPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Conflict when the catalogue item name already exists in the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemPostRequest = arbitrarySample[CatalogueItemPostRequest]
        val catalogueItemRowExisting = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          name = catalogueItemPostRequest.name,
          isArchived = false,
        )
        val catalogueItemVariantRowExisting = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRowExisting.catalogueItemID,
          sku = None,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowExisting)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowExisting)))
          .zioValue

        val accessJwt                 = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemPostResponse = gatewayClient
          .catalogueItemPost[smithy.Conflict](
            catalogueItemPostRequest.transformInto[smithy.CatalogueItemPostRequest],
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPostResponse.code shouldBe StatusCode.Conflict
        catalogueItemPostResponse.body.left.value shouldBe smithy.Conflict(message =
          "A catalogue item with the given name already exists in this organization"
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowExisting)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowExisting)
      }

      "fail with a Conflict when the catalogue item variant SKU already exists in the organization" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val catalogueItemVariantSkuConflicting = arbitrarySample[CatalogueItemVariantSku]
          val catalogueItemNameMaxLength         = 255
          val catalogueItemNamePrefix            = arbitrarySample[CatalogueItemName].value
            .take(catalogueItemNameMaxLength - "-existing".length)
            .trim
          val catalogueItemName         = CatalogueItemName.assume(s"$catalogueItemNamePrefix-new")
          val catalogueItemNameExisting = CatalogueItemName.assume(s"$catalogueItemNamePrefix-existing")
          catalogueItemName should not be catalogueItemNameExisting
          val catalogueItemPostRequest = CatalogueItemPostRequest(
            name = catalogueItemName,
            description = arbitrarySample[Option[CatalogueItemDescription]],
            variants = NonEmptyChunk(
              arbitrarySample[CatalogueItemVariantInsert].copy(sku = Some(catalogueItemVariantSkuConflicting))
            ),
          )
          val catalogueItemRowExisting = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = catalogueItemNameExisting,
            isArchived = false,
          )
          val catalogueItemVariantRowExisting = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRowExisting.catalogueItemID,
            sku = Some(catalogueItemVariantSkuConflicting),
            isArchived = false,
          )

          postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowExisting)).zioValue
          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowExisting)))
            .zioValue

          val accessJwt                 = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
          val catalogueItemPostResponse = gatewayClient
            .catalogueItemPost[smithy.Conflict](
              catalogueItemPostRequest.transformInto[smithy.CatalogueItemPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          catalogueItemPostResponse.code shouldBe StatusCode.Conflict
          catalogueItemPostResponse.body.left.value shouldBe smithy.Conflict(message =
            "A catalogue item variant with the given SKU already exists in this organization"
          )

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
            List(catalogueItemRowExisting)
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
            List(catalogueItemVariantRowExisting)
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID                    = arbitrarySample[UserID]
        val organizationID            = arbitrarySample[OrganizationID]
        val accessJwt                 = jwtService.generateAccessToken(userID).zioValue
        val catalogueItemPostResponse = gatewayClient
          .catalogueItemPost[smithy.InternalServerError](
            arbitrarySample[smithy.CatalogueItemPostRequest],
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPostResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID            = arbitrarySample[OrganizationID]
        val accessJwt                 = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemPostResponse = gatewayClient
          .catalogueItemPost[smithy.InternalServerError](
            arbitrarySample[smithy.CatalogueItemPostRequest],
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPostResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }
    }

    "POST /catalogue/item/batch" should {
      "successfully insert catalogue items and return their IDs in request order" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemVariantSku1 =
          arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("batch-sku1"))))
        val catalogueItemVariantSku2 =
          arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("batch-sku2"))))
        val catalogueItemVariantSku3 =
          arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("batch-sku3"))))
        val catalogueItemVariantInsert1 = arbitrarySample[CatalogueItemVariantInsert]
          .copy(sku = catalogueItemVariantSku1)
        val catalogueItemVariantInsert2 = arbitrarySample[CatalogueItemVariantInsert]
          .copy(sku = catalogueItemVariantSku2)
        val catalogueItemVariantInsert3 = arbitrarySample[CatalogueItemVariantInsert]
          .copy(sku = catalogueItemVariantSku3)
        val catalogueItemPostRequest1 = CatalogueItemPostRequest(
          name = CatalogueItemName.assume("batch-item1"),
          description = arbitrarySample[Option[CatalogueItemDescription]],
          variants = NonEmptyChunk.fromIterable(
            catalogueItemVariantInsert1,
            List(catalogueItemVariantInsert2),
          ),
        )
        val catalogueItemPostRequest2 = CatalogueItemPostRequest(
          name = CatalogueItemName.assume("batch-item2"),
          description = arbitrarySample[Option[CatalogueItemDescription]],
          variants = NonEmptyChunk(catalogueItemVariantInsert3),
        )
        val catalogueItemBatchPostRequest = CatalogueItemBatchPostRequest(
          NonEmptyChunk.fromIterable(catalogueItemPostRequest1, List(catalogueItemPostRequest2))
        )

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemBatchPostResponse = gatewayClient
          .catalogueItemBatchPost[smithy.InternalServerError](
            catalogueItemBatchPostRequest.transformInto[smithy.CatalogueItemBatchPostRequest],
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemBatchPostResponse.code shouldBe StatusCode.Ok

        val catalogueItemRows =
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue
        val catalogueItemVariantRows =
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue

        catalogueItemRows should have size 2
        catalogueItemVariantRows should have size 3

        val catalogueItemRow1 = catalogueItemRows.find(_.name == catalogueItemPostRequest1.name).value
        val catalogueItemRow2 = catalogueItemRows.find(_.name == catalogueItemPostRequest2.name).value

        catalogueItemBatchPostResponse.body.value shouldBe smithy.CatalogueItemBatchPostResponse(
          catalogueItemIDs = List(catalogueItemRow1.catalogueItemID.value, catalogueItemRow2.catalogueItemID.value)
        )
        catalogueItemRow1 shouldBe CatalogueItemRow(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow1.catalogueItemID,
          name = catalogueItemPostRequest1.name,
          description = catalogueItemPostRequest1.description,
          imageAsset = None,
          status = CatalogueItemStatus.Active,
          isArchived = false,
          createdAt = catalogueItemRow1.createdAt,
          updatedAt = catalogueItemRow1.updatedAt,
        )
        catalogueItemRow2 shouldBe CatalogueItemRow(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow2.catalogueItemID,
          name = catalogueItemPostRequest2.name,
          description = catalogueItemPostRequest2.description,
          imageAsset = None,
          status = CatalogueItemStatus.Active,
          isArchived = false,
          createdAt = catalogueItemRow2.createdAt,
          updatedAt = catalogueItemRow2.updatedAt,
        )
        catalogueItemRow1.updatedAt.value shouldBe catalogueItemRow1.createdAt.value
        catalogueItemRow2.createdAt.value shouldBe catalogueItemRow1.createdAt.value
        catalogueItemRow2.updatedAt.value shouldBe catalogueItemRow1.createdAt.value

        val catalogueItemVariantInserts = List(
          (
            catalogueItemID = catalogueItemRow1.catalogueItemID,
            catalogueItemVariantInsert = catalogueItemVariantInsert1,
          ),
          (
            catalogueItemID = catalogueItemRow1.catalogueItemID,
            catalogueItemVariantInsert = catalogueItemVariantInsert2,
          ),
          (
            catalogueItemID = catalogueItemRow2.catalogueItemID,
            catalogueItemVariantInsert = catalogueItemVariantInsert3,
          ),
        )
        val catalogueItemVariantRowsExpected = catalogueItemVariantInserts.map {
          case (catalogueItemID, catalogueItemVariantInsert) =>
            catalogueItemVariantRows.find { catalogueItemVariantRow =>
              catalogueItemVariantRow == CatalogueItemVariantRow(
                organizationID = organizationUserRow.organizationID,
                catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID,
                catalogueItemID = catalogueItemID,
                sku = catalogueItemVariantInsert.sku,
                label = catalogueItemVariantInsert.label,
                description = catalogueItemVariantInsert.description,
                attributes = catalogueItemVariantInsert.attributes,
                price = catalogueItemVariantInsert.price,
                sellingUnit = catalogueItemVariantInsert.sellingUnit,
                status = CatalogueItemVariantStatus.Active,
                isArchived = false,
                createdAt = catalogueItemRow1.createdAt,
                updatedAt = catalogueItemRow1.updatedAt,
              )
            }.value
        }

        catalogueItemVariantRows should contain theSameElementsAs catalogueItemVariantRowsExpected
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt                      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemBatchPostResponse = gatewayClient
          .catalogueItemBatchPost[smithy.BadRequest](
            arbitrarySample[smithy.CatalogueItemBatchPostRequest],
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemBatchPostResponse.code shouldBe StatusCode.BadRequest
        catalogueItemBatchPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a ValidationError when the batch has no catalogue items" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt                      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemBatchPostResponse = gatewayClient
          .catalogueItemBatchPost[smithy.ValidationError](
            smithy.CatalogueItemBatchPostRequest(catalogueItems = Nil),
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemBatchPostResponse.code shouldBe StatusCode.BadRequest
        catalogueItemBatchPostResponse.body.left.value shouldBe smithy.ValidationError(
          fields = List("catalogueItem")
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a ValidationError containing accumulated catalogue item fields" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemPostRequestValid1    = arbitrarySample[smithy.CatalogueItemPostRequest]
        val catalogueItemPostRequestInvalid1  = arbitrarySample[smithy.CatalogueItemPostRequest].copy(name = "")
        val catalogueItemPostRequestValid2    = arbitrarySample[smithy.CatalogueItemPostRequest]
        val catalogueItemVariantInsertInvalid = arbitrarySample[smithy.CatalogueItemVariantInsert]
          .copy(sku = Some(""))
        val catalogueItemPostRequestInvalid2 = smithy.CatalogueItemPostRequest(
          name = arbitrarySample[CatalogueItemName].value,
          description = arbitrarySample[Option[CatalogueItemDescription]].map(_.value),
          variants = List(catalogueItemVariantInsertInvalid),
        )
        val catalogueItemBatchPostRequest = smithy.CatalogueItemBatchPostRequest(
          catalogueItems = List(
            catalogueItemPostRequestValid1,
            catalogueItemPostRequestInvalid1,
            catalogueItemPostRequestValid2,
            catalogueItemPostRequestInvalid2,
          )
        )

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemBatchPostResponse = gatewayClient
          .catalogueItemBatchPost[smithy.ValidationError](
            catalogueItemBatchPostRequest,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemBatchPostResponse.code shouldBe StatusCode.BadRequest
        catalogueItemBatchPostResponse.body.left.value shouldBe smithy.ValidationError(
          fields = List("catalogueItem", "catalogueItem")
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueItemBatchPostResponse = gatewayClient
          .catalogueItemBatchPost[smithy.Unauthorized](
            arbitrarySample[smithy.CatalogueItemBatchPostRequest],
            Some(organizationID),
            None,
          )
          .zioValue

        catalogueItemBatchPostResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemBatchPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueItemBatchPostResponse = gatewayClient
          .catalogueItemBatchPost[smithy.Unauthorized](
            arbitrarySample[smithy.CatalogueItemBatchPostRequest],
            Some(organizationID),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        catalogueItemBatchPostResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemBatchPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt                      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemBatchPostResponse = gatewayClient
          .catalogueItemBatchPost[smithy.Forbidden](
            arbitrarySample[smithy.CatalogueItemBatchPostRequest],
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemBatchPostResponse.code shouldBe StatusCode.Forbidden
        catalogueItemBatchPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRoleInvalid =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRoleInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt                      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemBatchPostResponse = gatewayClient
          .catalogueItemBatchPost[smithy.Forbidden](
            arbitrarySample[smithy.CatalogueItemBatchPostRequest],
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemBatchPostResponse.code shouldBe StatusCode.Forbidden
        catalogueItemBatchPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Conflict and roll back the batch when a catalogue item name already exists" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val catalogueItemRowExisting = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("existing-item"),
            isArchived = false,
          )
          val catalogueItemVariantRowExisting = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRowExisting.catalogueItemID,
            sku = Some(CatalogueItemVariantSku.assume("existing-sku")),
            isArchived = false,
          )

          postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowExisting)).zioValue
          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowExisting)))
            .zioValue

          val catalogueItemPostRequest1 = CatalogueItemPostRequest(
            name = CatalogueItemName.assume("batch-item1"),
            description = arbitrarySample[Option[CatalogueItemDescription]],
            variants = NonEmptyChunk(
              arbitrarySample[CatalogueItemVariantInsert]
                .copy(sku = Some(CatalogueItemVariantSku.assume("batch-sku1")))
            ),
          )
          val catalogueItemPostRequest2 = CatalogueItemPostRequest(
            name = catalogueItemRowExisting.name,
            description = arbitrarySample[Option[CatalogueItemDescription]],
            variants = NonEmptyChunk(
              arbitrarySample[CatalogueItemVariantInsert]
                .copy(sku = Some(CatalogueItemVariantSku.assume("batch-sku2")))
            ),
          )
          val catalogueItemBatchPostRequest = CatalogueItemBatchPostRequest(
            NonEmptyChunk.fromIterable(catalogueItemPostRequest1, List(catalogueItemPostRequest2))
          )

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemBatchPostResponse = gatewayClient
            .catalogueItemBatchPost[smithy.Conflict](
              catalogueItemBatchPostRequest.transformInto[smithy.CatalogueItemBatchPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          catalogueItemBatchPostResponse.code shouldBe StatusCode.Conflict
          catalogueItemBatchPostResponse.body.left.value shouldBe smithy.Conflict(message =
            "A catalogue item with the given name already exists in this organization"
          )

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
            List(catalogueItemRowExisting)
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
            List(catalogueItemVariantRowExisting)
      }

      "fail with a Conflict and roll back the batch when a variant SKU is duplicated across items" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val catalogueItemVariantSkuDuplicated = CatalogueItemVariantSku.assume("duplicated-sku")
          val catalogueItemPostRequest1         = CatalogueItemPostRequest(
            name = CatalogueItemName.assume("batch-item1"),
            description = arbitrarySample[Option[CatalogueItemDescription]],
            variants = NonEmptyChunk(
              arbitrarySample[CatalogueItemVariantInsert].copy(sku = Some(catalogueItemVariantSkuDuplicated))
            ),
          )
          val catalogueItemPostRequest2 = CatalogueItemPostRequest(
            name = CatalogueItemName.assume("batch-item2"),
            description = arbitrarySample[Option[CatalogueItemDescription]],
            variants = NonEmptyChunk(
              arbitrarySample[CatalogueItemVariantInsert].copy(sku = Some(catalogueItemVariantSkuDuplicated))
            ),
          )
          val catalogueItemBatchPostRequest = CatalogueItemBatchPostRequest(
            NonEmptyChunk.fromIterable(catalogueItemPostRequest1, List(catalogueItemPostRequest2))
          )

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemBatchPostResponse = gatewayClient
            .catalogueItemBatchPost[smithy.Conflict](
              catalogueItemBatchPostRequest.transformInto[smithy.CatalogueItemBatchPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          catalogueItemBatchPostResponse.code shouldBe StatusCode.Conflict
          catalogueItemBatchPostResponse.body.left.value shouldBe smithy.Conflict(message =
            "A catalogue item variant with the given SKU already exists in this organization"
          )

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID                         = arbitrarySample[UserID]
        val organizationID                 = arbitrarySample[OrganizationID]
        val accessJwt                      = jwtService.generateAccessToken(userID).zioValue
        val catalogueItemBatchPostResponse = gatewayClient
          .catalogueItemBatchPost[smithy.InternalServerError](
            arbitrarySample[smithy.CatalogueItemBatchPostRequest],
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemBatchPostResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemBatchPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID                 = arbitrarySample[OrganizationID]
        val accessJwt                      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemBatchPostResponse = gatewayClient
          .catalogueItemBatchPost[smithy.InternalServerError](
            arbitrarySample[smithy.CatalogueItemBatchPostRequest],
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemBatchPostResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemBatchPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }
    }

    "PUT /catalogue/item/{catalogueItemID}" should {
      "successfully update a catalogue item" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val catalogueItemPutRequest = CatalogueItemPutRequest(
          name = Some(arbitrarySample[CatalogueItemName]),
          description = None,
          status = None,
          variantsUpdate = Nil,
          variantsInsert = Nil,
        )

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemPutResponse = gatewayClient
          .catalogueItemPut[smithy.InternalServerError](
            catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
            catalogueItemRow.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPutResponse.code shouldBe StatusCode.NoContent
        catalogueItemPutResponse.body.value shouldBe ()

        val catalogueItemRowsAll =
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue

        catalogueItemRowsAll should have size 1

        val catalogueItemRowUpdated = catalogueItemRowsAll.head

        catalogueItemRowUpdated shouldBe catalogueItemRow.copy(
          name = catalogueItemPutRequest.name.value,
          updatedAt = catalogueItemRowUpdated.updatedAt,
        )
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRow)
      }

      "successfully clear a catalogue item's description" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          description = Some(arbitrarySample[CatalogueItemDescription]),
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val catalogueItemPutRequest = CatalogueItemPutRequest(
          name = None,
          description = Some(None),
          status = None,
          variantsUpdate = Nil,
          variantsInsert = Nil,
        )

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemPutResponse = gatewayClient
          .catalogueItemPut[smithy.InternalServerError](
            catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
            catalogueItemRow.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPutResponse.code shouldBe StatusCode.NoContent
        catalogueItemPutResponse.body.value shouldBe ()

        val catalogueItemRowsAll =
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue

        catalogueItemRowsAll should have size 1

        val catalogueItemRowUpdated = catalogueItemRowsAll.head

        catalogueItemRowUpdated shouldBe catalogueItemRow.copy(
          description = None,
          updatedAt = catalogueItemRowUpdated.updatedAt,
        )
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRow)
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemPutResponse = gatewayClient
          .catalogueItemPut[smithy.BadRequest](
            arbitrarySample[smithy.CatalogueItemPutRequest],
            catalogueItemID,
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPutResponse.code shouldBe StatusCode.BadRequest
        catalogueItemPutResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a ValidationError containing item and variant fields in declaration order" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          name = CatalogueItemName.assume("item-validation"),
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = Some(CatalogueItemVariantSku.assume("sku-validation")),
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val catalogueItemVariantUpdateInvalid = arbitrarySample[smithy.CatalogueItemVariantUpdate].copy(
          catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID.value,
          sku = Some(Nullable.Value("")),
          label = Some(Nullable.Value("")),
          description = Some(Nullable.Value("")),
          attributes = Some(Map("" -> "")),
          price = Some(
            Nullable.Value(
              smithy.CatalogueItemVariantPrice(
                BigDecimal("-1"),
                arbitrarySample[Currency].transformInto[smithy.Currency],
              )
            )
          ),
          sellingUnit = Some(
            smithy.CatalogueItemVariantSaleUnit(
              smithy.CatalogueItemVariantQuantity(
                BigDecimal(0),
                arbitrarySample[CatalogueItemVariantUnit].transformInto[smithy.CatalogueItemVariantUnit],
              ),
              None,
              None,
            )
          ),
          status = None,
        )
        val catalogueItemVariantInsertInvalid = arbitrarySample[smithy.CatalogueItemVariantInsert]
          .copy(sku = Some(""))
        val catalogueItemPutRequest = smithy.CatalogueItemPutRequest(
          name = Some(""),
          description = Some(Nullable.Value("")),
          variantsUpdate = List(catalogueItemVariantUpdateInvalid),
          variantsInsert = List(catalogueItemVariantInsertInvalid),
        )

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemPutResponse = gatewayClient
          .catalogueItemPut[smithy.ValidationError](
            catalogueItemPutRequest,
            catalogueItemRow.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPutResponse.code shouldBe StatusCode.BadRequest
        catalogueItemPutResponse.body.left.value shouldBe smithy.ValidationError(
          fields = List("name", "description", "variant", "variant")
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRow)
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        val catalogueItemPutResponse = gatewayClient
          .catalogueItemPut[smithy.Unauthorized](
            arbitrarySample[smithy.CatalogueItemPutRequest],
            catalogueItemID,
            Some(organizationID),
            None,
          )
          .zioValue

        catalogueItemPutResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemPutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        val catalogueItemPutResponse = gatewayClient
          .catalogueItemPut[smithy.Unauthorized](
            arbitrarySample[smithy.CatalogueItemPutRequest],
            catalogueItemID,
            Some(organizationID),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        catalogueItemPutResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemPutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemPutResponse = gatewayClient
          .catalogueItemPut[smithy.Forbidden](
            arbitrarySample[smithy.CatalogueItemPutRequest],
            catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPutResponse.code shouldBe StatusCode.Forbidden
        catalogueItemPutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRoleInvalid =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRoleInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemPutResponse = gatewayClient
          .catalogueItemPut[smithy.Forbidden](
            arbitrarySample[smithy.CatalogueItemPutRequest],
            catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPutResponse.code shouldBe StatusCode.Forbidden
        catalogueItemPutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Conflict and roll back the update when the catalogue item name already exists" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("item-name-update-target"),
            isArchived = false,
          )
          val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = Some(CatalogueItemVariantSku.assume("sku-name-update-target")),
            isArchived = false,
          )
          val catalogueItemRowConflicting = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("item-name-conflicting"),
            isArchived = false,
          )
          val catalogueItemVariantRowConflicting = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRowConflicting.catalogueItemID,
            sku = Some(CatalogueItemVariantSku.assume("sku-name-conflicting")),
            isArchived = false,
          )

          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowConflicting)))
            .zioValue
          postgresClient
            .executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowConflicting)
              )
            )
            .zioValue

          val catalogueItemVariantUpdate = CatalogueItemVariantUpdate(
            catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID,
            sku = None,
            label = Some(Some(CatalogueItemVariantLabel.assume("label-should-roll-back"))),
            description = None,
            attributes = None,
            price = None,
            sellingUnit = None,
            status = None,
          )
          val catalogueItemPutRequest = CatalogueItemPutRequest(
            name = Some(catalogueItemRowConflicting.name),
            description = None,
            status = None,
            variantsUpdate = List(catalogueItemVariantUpdate),
            variantsInsert = Nil,
          )

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemPutResponse = gatewayClient
            .catalogueItemPut[smithy.Conflict](
              catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
              catalogueItemRow.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          catalogueItemPutResponse.code shouldBe StatusCode.Conflict
          catalogueItemPutResponse.body.left.value shouldBe smithy.Conflict(message =
            "A catalogue item with the given name already exists in this organization"
          )

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
            Set(catalogueItemRow, catalogueItemRowConflicting)
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
            Set(catalogueItemVariantRow, catalogueItemVariantRowConflicting)
      }

      "fail with a Conflict and roll back the update when the catalogue item variant SKU already exists" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("item-sku-update-target"),
            isArchived = false,
          )
          val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = Some(CatalogueItemVariantSku.assume("sku-update-target")),
            isArchived = false,
          )
          val catalogueItemRowConflicting = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("item-sku-conflicting"),
            isArchived = false,
          )
          val catalogueItemVariantRowConflicting = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRowConflicting.catalogueItemID,
            sku = Some(CatalogueItemVariantSku.assume("sku-conflicting")),
            isArchived = false,
          )

          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowConflicting)))
            .zioValue
          postgresClient
            .executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowConflicting)
              )
            )
            .zioValue

          val catalogueItemVariantUpdate = CatalogueItemVariantUpdate(
            catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID,
            sku = Some(catalogueItemVariantRowConflicting.sku),
            label = None,
            description = None,
            attributes = None,
            price = None,
            sellingUnit = None,
            status = None,
          )
          val catalogueItemPutRequest = CatalogueItemPutRequest(
            name = Some(CatalogueItemName.assume("item-sku-parent-should-roll-back")),
            description = None,
            status = None,
            variantsUpdate = List(catalogueItemVariantUpdate),
            variantsInsert = Nil,
          )

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemPutResponse = gatewayClient
            .catalogueItemPut[smithy.Conflict](
              catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
              catalogueItemRow.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          catalogueItemPutResponse.code shouldBe StatusCode.Conflict
          catalogueItemPutResponse.body.left.value shouldBe smithy.Conflict(message =
            "A catalogue item variant with the given SKU already exists in this organization"
          )

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
            Set(catalogueItemRow, catalogueItemRowConflicting)
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
            Set(catalogueItemVariantRow, catalogueItemVariantRowConflicting)
      }

      "fail with an InternalServerError and roll back the update when a catalogue item variant does not exist" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("item-missing-variant"),
            isArchived = false,
          )
          val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = Some(CatalogueItemVariantSku.assume("sku-missing-variant-sibling")),
            isArchived = false,
          )

          postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
            .zioValue

          val catalogueItemVariantUpdate = arbitrarySample[CatalogueItemVariantUpdate]
          val catalogueItemPutRequest    = CatalogueItemPutRequest(
            name = Some(CatalogueItemName.assume("item-name-should-roll-back")),
            description = None,
            status = None,
            variantsUpdate = List(catalogueItemVariantUpdate),
            variantsInsert = Nil,
          )

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemPutResponse = gatewayClient
            .catalogueItemPut[smithy.InternalServerError](
              catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
              catalogueItemRow.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          catalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
          catalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
            List(catalogueItemRow)
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
            List(catalogueItemVariantRow)
      }

      "fail with an InternalServerError and roll back the update when a catalogue item variant is archived" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("item-archived-variant"),
            isArchived = false,
          )
          val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = Some(CatalogueItemVariantSku.assume("sku-archived-variant-sibling")),
            isArchived = false,
          )
          val catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = Some(CatalogueItemVariantSku.assume("sku-archived-variant")),
            isArchived = true,
          )

          postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
          postgresClient
            .executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowArchived)
              )
            )
            .zioValue

          val catalogueItemVariantUpdate = arbitrarySample[CatalogueItemVariantUpdate].copy(
            catalogueItemVariantID = catalogueItemVariantRowArchived.catalogueItemVariantID
          )
          val catalogueItemPutRequest = CatalogueItemPutRequest(
            name = Some(CatalogueItemName.assume("item-name-should-roll-back")),
            description = None,
            status = None,
            variantsUpdate = List(catalogueItemVariantUpdate),
            variantsInsert = Nil,
          )

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemPutResponse = gatewayClient
            .catalogueItemPut[smithy.InternalServerError](
              catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
              catalogueItemRow.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          catalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
          catalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
            List(catalogueItemRow)
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
            Set(catalogueItemVariantRow, catalogueItemVariantRowArchived)
      }

      "fail with an InternalServerError and roll back the update when a variant belongs to another catalogue item" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("item-variant-parent-target"),
            isArchived = false,
          )
          val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = Some(CatalogueItemVariantSku.assume("sku-variant-parent-target")),
            isArchived = false,
          )
          val catalogueItemRowOther = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("item-variant-parent-other"),
            isArchived = false,
          )
          val catalogueItemVariantRowOther = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRowOther.catalogueItemID,
            sku = Some(CatalogueItemVariantSku.assume("sku-variant-parent-other")),
            isArchived = false,
          )

          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowOther)))
            .zioValue
          postgresClient
            .executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowOther)
              )
            )
            .zioValue

          val catalogueItemVariantUpdate = arbitrarySample[CatalogueItemVariantUpdate].copy(
            catalogueItemVariantID = catalogueItemVariantRowOther.catalogueItemVariantID
          )
          val catalogueItemPutRequest = CatalogueItemPutRequest(
            name = Some(CatalogueItemName.assume("item-name-should-roll-back")),
            description = None,
            status = None,
            variantsUpdate = List(catalogueItemVariantUpdate),
            variantsInsert = Nil,
          )

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemPutResponse = gatewayClient
            .catalogueItemPut[smithy.InternalServerError](
              catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
              catalogueItemRow.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          catalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
          catalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
            Set(catalogueItemRow, catalogueItemRowOther)
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
            Set(catalogueItemVariantRow, catalogueItemVariantRowOther)
      }

      "fail with an InternalServerError and roll back the update when a variant belongs to another organization" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
          val organizationIDForeign = arbitrarySample[OrganizationID]

          organizationIDForeign shouldNot equal(organizationUserRow.organizationID)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("item-variant-organization-target"),
            isArchived = false,
          )
          val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = Some(CatalogueItemVariantSku.assume("sku-variant-organization-target")),
            isArchived = false,
          )
          val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationIDForeign,
            name = CatalogueItemName.assume("item-variant-organization-other"),
            isArchived = false,
          )
          val catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationIDForeign,
            catalogueItemID = catalogueItemRowForeign.catalogueItemID,
            sku = Some(CatalogueItemVariantSku.assume("sku-variant-organization-other")),
            isArchived = false,
          )

          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowForeign)))
            .zioValue
          postgresClient
            .executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowForeign)
              )
            )
            .zioValue

          val catalogueItemVariantUpdate = arbitrarySample[CatalogueItemVariantUpdate].copy(
            catalogueItemVariantID = catalogueItemVariantRowForeign.catalogueItemVariantID
          )
          val catalogueItemPutRequest = CatalogueItemPutRequest(
            name = Some(CatalogueItemName.assume("item-name-should-roll-back")),
            description = None,
            status = None,
            variantsUpdate = List(catalogueItemVariantUpdate),
            variantsInsert = Nil,
          )

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemPutResponse = gatewayClient
            .catalogueItemPut[smithy.InternalServerError](
              catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
              catalogueItemRow.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          catalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
          catalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
            Set(catalogueItemRow, catalogueItemRowForeign)
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
            Set(catalogueItemVariantRow, catalogueItemVariantRowForeign)
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID          = arbitrarySample[UserID]
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userID).zioValue

        val catalogueItemPutResponse = gatewayClient
          .catalogueItemPut[smithy.InternalServerError](
            arbitrarySample[smithy.CatalogueItemPutRequest],
            catalogueItemID,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemPutResponse = gatewayClient
          .catalogueItemPut[smithy.InternalServerError](
            arbitrarySample[smithy.CatalogueItemPutRequest],
            catalogueItemID,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }
    }

    "PUT /catalogue/item/{catalogueItemID}/archive" should {
      "successfully archive a catalogue item and its variants" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemArchivePutResponse = gatewayClient
          .catalogueItemArchivePut[smithy.InternalServerError](
            catalogueItemRow.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemArchivePutResponse.code shouldBe StatusCode.NoContent
        catalogueItemArchivePutResponse.body.value shouldBe ()

        val catalogueItemRowsAll =
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue
        val catalogueItemVariantRowsAll =
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue

        catalogueItemRowsAll should have size 1
        catalogueItemVariantRowsAll should have size 1

        val catalogueItemRowArchived        = catalogueItemRowsAll.head
        val catalogueItemVariantRowArchived = catalogueItemVariantRowsAll.head

        catalogueItemRowArchived shouldBe catalogueItemRow.copy(
          isArchived = true,
          updatedAt = catalogueItemRowArchived.updatedAt,
        )
        catalogueItemVariantRowArchived shouldBe catalogueItemVariantRow.copy(
          isArchived = true,
          updatedAt = catalogueItemVariantRowArchived.updatedAt,
        )
        catalogueItemVariantRowArchived.updatedAt shouldBe catalogueItemRowArchived.updatedAt
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemArchivePutResponse = gatewayClient
          .catalogueItemArchivePut[smithy.BadRequest](
            catalogueItemID,
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemArchivePutResponse.code shouldBe StatusCode.BadRequest
        catalogueItemArchivePutResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        val catalogueItemArchivePutResponse = gatewayClient
          .catalogueItemArchivePut[smithy.Unauthorized](
            catalogueItemID,
            Some(organizationID),
            None,
          )
          .zioValue

        catalogueItemArchivePutResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemArchivePutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        val catalogueItemArchivePutResponse = gatewayClient
          .catalogueItemArchivePut[smithy.Unauthorized](
            catalogueItemID,
            Some(organizationID),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        catalogueItemArchivePutResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemArchivePutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemArchivePutResponse = gatewayClient
          .catalogueItemArchivePut[smithy.Forbidden](
            catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemArchivePutResponse.code shouldBe StatusCode.Forbidden
        catalogueItemArchivePutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRoleInvalid =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRoleInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemArchivePutResponse = gatewayClient
          .catalogueItemArchivePut[smithy.Forbidden](
            catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemArchivePutResponse.code shouldBe StatusCode.Forbidden
        catalogueItemArchivePutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID          = arbitrarySample[UserID]
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userID).zioValue

        val catalogueItemArchivePutResponse = gatewayClient
          .catalogueItemArchivePut[smithy.InternalServerError](
            catalogueItemID,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemArchivePutResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemArchivePutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemArchivePutResponse = gatewayClient
          .catalogueItemArchivePut[smithy.InternalServerError](
            catalogueItemID,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemArchivePutResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemArchivePutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }
    }

    "PUT /catalogue/item/{catalogueItemID}/variant/{catalogueItemVariantID}/archive" should {
      "successfully archive a catalogue item variant" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          isArchived = false,
        )
        val catalogueItemVariantSku =
          arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("sku-archive"))))
        val catalogueItemVariantSkuSibling =
          arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("sku-archive-sibling"))))
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = catalogueItemVariantSku,
          isArchived = false,
        )
        val catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = catalogueItemVariantSkuSibling,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
            )
          )
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemVariantArchivePutResponse = gatewayClient
          .catalogueItemVariantArchivePut[smithy.InternalServerError](
            catalogueItemRow.catalogueItemID,
            catalogueItemVariantRow.catalogueItemVariantID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemVariantArchivePutResponse.code shouldBe StatusCode.NoContent
        catalogueItemVariantArchivePutResponse.body.value shouldBe ()

        val catalogueItemRowsAll =
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue
        val catalogueItemVariantRowsAll =
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue

        catalogueItemRowsAll shouldBe List(catalogueItemRow)
        catalogueItemVariantRowsAll should have size 2

        val catalogueItemVariantRowArchived = catalogueItemVariantRowsAll
          .find(_.catalogueItemVariantID == catalogueItemVariantRow.catalogueItemVariantID)
          .value
        val catalogueItemVariantRowSiblingUnchanged = catalogueItemVariantRowsAll
          .find(_.catalogueItemVariantID == catalogueItemVariantRowSibling.catalogueItemVariantID)
          .value

        catalogueItemVariantRowArchived shouldBe catalogueItemVariantRow.copy(
          isArchived = true,
          updatedAt = catalogueItemVariantRowArchived.updatedAt,
        )
        catalogueItemVariantRowSiblingUnchanged shouldBe catalogueItemVariantRowSibling
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val catalogueItemID        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID = arbitrarySample[CatalogueItemVariantID]
        val accessJwt              = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemVariantArchivePutResponse = gatewayClient
          .catalogueItemVariantArchivePut[smithy.BadRequest](
            catalogueItemID,
            catalogueItemVariantID,
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemVariantArchivePutResponse.code shouldBe StatusCode.BadRequest
        catalogueItemVariantArchivePutResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val organizationID         = arbitrarySample[OrganizationID]
        val catalogueItemID        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID = arbitrarySample[CatalogueItemVariantID]

        val catalogueItemVariantArchivePutResponse = gatewayClient
          .catalogueItemVariantArchivePut[smithy.Unauthorized](
            catalogueItemID,
            catalogueItemVariantID,
            Some(organizationID),
            None,
          )
          .zioValue

        catalogueItemVariantArchivePutResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemVariantArchivePutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val organizationID         = arbitrarySample[OrganizationID]
        val catalogueItemID        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID = arbitrarySample[CatalogueItemVariantID]

        val catalogueItemVariantArchivePutResponse = gatewayClient
          .catalogueItemVariantArchivePut[smithy.Unauthorized](
            catalogueItemID,
            catalogueItemVariantID,
            Some(organizationID),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        catalogueItemVariantArchivePutResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemVariantArchivePutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemID        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID = arbitrarySample[CatalogueItemVariantID]
        val accessJwt              = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemVariantArchivePutResponse = gatewayClient
          .catalogueItemVariantArchivePut[smithy.Forbidden](
            catalogueItemID,
            catalogueItemVariantID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemVariantArchivePutResponse.code shouldBe StatusCode.Forbidden
        catalogueItemVariantArchivePutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRoleInvalid =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRoleInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemID        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID = arbitrarySample[CatalogueItemVariantID]
        val accessJwt              = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemVariantArchivePutResponse = gatewayClient
          .catalogueItemVariantArchivePut[smithy.Forbidden](
            catalogueItemID,
            catalogueItemVariantID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemVariantArchivePutResponse.code shouldBe StatusCode.Forbidden
        catalogueItemVariantArchivePutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the variant is the last non-archived variant" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemVariantArchivePutResponse = gatewayClient
          .catalogueItemVariantArchivePut[smithy.Forbidden](
            catalogueItemRow.catalogueItemID,
            catalogueItemVariantRow.catalogueItemVariantID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemVariantArchivePutResponse.code shouldBe StatusCode.Forbidden
        catalogueItemVariantArchivePutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRow)
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID                 = arbitrarySample[UserID]
        val organizationID         = arbitrarySample[OrganizationID]
        val catalogueItemID        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID = arbitrarySample[CatalogueItemVariantID]
        val accessJwt              = jwtService.generateAccessToken(userID).zioValue

        val catalogueItemVariantArchivePutResponse = gatewayClient
          .catalogueItemVariantArchivePut[smithy.InternalServerError](
            catalogueItemID,
            catalogueItemVariantID,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemVariantArchivePutResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemVariantArchivePutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID         = arbitrarySample[OrganizationID]
        val catalogueItemID        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID = arbitrarySample[CatalogueItemVariantID]
        val accessJwt              = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemVariantArchivePutResponse = gatewayClient
          .catalogueItemVariantArchivePut[smithy.InternalServerError](
            catalogueItemID,
            catalogueItemVariantID,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemVariantArchivePutResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemVariantArchivePutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }
    }

    "GET /catalogue/item/{catalogueItemID}" should {
      "successfully return a catalogue item with its non-archived variants" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          imageAsset = None,
          isArchived = false,
        )
        val catalogueItemVariantSku =
          arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("sku-detail"))))
        val catalogueItemVariantSkuArchived =
          arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("sku-detail-archived"))))
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = catalogueItemVariantSku,
          isArchived = false,
        )
        val catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = catalogueItemVariantSkuArchived,
          isArchived = true,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRow, catalogueItemVariantRowArchived)
            )
          )
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemGetResponse = gatewayClient
          .catalogueItemGet[smithy.InternalServerError](
            catalogueItemRow.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemGetResponse.code shouldBe StatusCode.Ok
        catalogueItemGetResponse.body.value shouldBe smithy.CatalogueItemGetResponse(
          catalogueItemID = catalogueItemRow.catalogueItemID.value,
          name = catalogueItemRow.name.value,
          description = catalogueItemRow.description.map(_.value),
          status = catalogueItemRow.status.transformInto[smithy.CatalogueItemStatus],
          imageNormalizedUrl = None,
          variants = List(
            smithy.CatalogueItemVariant(
              catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID.value,
              sku = catalogueItemVariantRow.sku.map(_.value),
              label = catalogueItemVariantRow.label.map(_.value),
              description = catalogueItemVariantRow.description.map(_.value),
              attributes = catalogueItemVariantRow.attributes.view
                .map((catalogueItemVariantAttributeName, catalogueItemVariantAttributeValue) =>
                  catalogueItemVariantAttributeName.value -> catalogueItemVariantAttributeValue.value
                )
                .toMap,
              price = catalogueItemVariantRow.price.map(catalogueItemVariantPrice =>
                smithy.CatalogueItemVariantPrice(
                  amount = catalogueItemVariantPrice.value.amount.value,
                  currency = catalogueItemVariantPrice.value.currency.transformInto[smithy.Currency],
                )
              ),
              sellingUnit = smithy.CatalogueItemVariantSaleUnit(
                quantity = smithy.CatalogueItemVariantQuantity(
                  value = catalogueItemVariantRow.sellingUnit.quantity.value.value,
                  unit = catalogueItemVariantRow.sellingUnit.quantity.unit
                    .transformInto[smithy.CatalogueItemVariantUnit],
                ),
                perUnit = catalogueItemVariantRow.sellingUnit.perUnit.map(catalogueItemVariantPerUnit =>
                  smithy.CatalogueItemVariantPerUnit(
                    value = catalogueItemVariantPerUnit.value.value,
                    unit = catalogueItemVariantPerUnit.unit.transformInto[smithy.CatalogueItemVariantUnitPerUnit],
                  )
                ),
                unitSize = catalogueItemVariantRow.sellingUnit.unitSize.map(catalogueItemVariantUnitSize =>
                  smithy.CatalogueItemVariantUnitSize(
                    value = catalogueItemVariantUnitSize.value.value,
                    unit = catalogueItemVariantUnitSize.unit
                      .transformInto[smithy.CatalogueItemVariantUnitUnitSize],
                  )
                ),
              ),
              status = catalogueItemVariantRow.status.transformInto[smithy.CatalogueItemVariantStatus],
            )
          ),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRow, catalogueItemVariantRowArchived)
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemGetResponse = gatewayClient
          .catalogueItemGet[smithy.BadRequest](
            catalogueItemID,
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemGetResponse.code shouldBe StatusCode.BadRequest
        catalogueItemGetResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        val catalogueItemGetResponse = gatewayClient
          .catalogueItemGet[smithy.Unauthorized](
            catalogueItemID,
            Some(organizationID),
            None,
          )
          .zioValue

        catalogueItemGetResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemGetResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        val catalogueItemGetResponse = gatewayClient
          .catalogueItemGet[smithy.Unauthorized](
            catalogueItemID,
            Some(organizationID),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        catalogueItemGetResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemGetResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemGetResponse = gatewayClient
          .catalogueItemGet[smithy.Forbidden](
            catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemGetResponse.code shouldBe StatusCode.Forbidden
        catalogueItemGetResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID          = arbitrarySample[UserID]
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userID).zioValue

        val catalogueItemGetResponse = gatewayClient
          .catalogueItemGet[smithy.InternalServerError](
            catalogueItemID,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemGetResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemGetResponse = gatewayClient
          .catalogueItemGet[smithy.InternalServerError](
            catalogueItemID,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemGetResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the catalogue item does not exist" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemGetResponse = gatewayClient
          .catalogueItemGet[smithy.InternalServerError](
            catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemGetResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the catalogue item is archived" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          isArchived = true,
        )
        val catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRowArchived.catalogueItemID,
          isArchived = true,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowArchived)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowArchived)))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemGetResponse = gatewayClient
          .catalogueItemGet[smithy.InternalServerError](
            catalogueItemRowArchived.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemGetResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowArchived)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowArchived)
      }

      "fail with an InternalServerError when the catalogue item belongs to another organization" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
          val organizationIDForeign = arbitrarySample[OrganizationID]

          organizationIDForeign shouldNot equal(organizationUserRow.organizationID)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationIDForeign,
            isArchived = false,
          )
          val catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationIDForeign,
            catalogueItemID = catalogueItemRowForeign.catalogueItemID,
            isArchived = false,
          )

          postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowForeign)).zioValue
          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowForeign)))
            .zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemGetResponse = gatewayClient
            .catalogueItemGet[smithy.InternalServerError](
              catalogueItemRowForeign.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          catalogueItemGetResponse.code shouldBe StatusCode.InternalServerError
          catalogueItemGetResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
            List(catalogueItemRowForeign)
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
            List(catalogueItemVariantRowForeign)
      }
    }

    "GET /catalogue/item/summary" should {
      "successfully return an empty list when the catalogue is empty" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemSummaryGetResponse = gatewayClient
          .catalogueItemSummaryGet[smithy.InternalServerError](
            Nil,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemSummaryGetResponse.code shouldBe StatusCode.Ok
        catalogueItemSummaryGetResponse.body.value shouldBe smithy.CatalogueItemSummaryGetResponse(Nil)

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "successfully return all catalogue item summaries in item and variant order when no IDs are supplied" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val createdAtBaseline           = arbitrarySample[CreatedAt]
          val createdAtOrderOffsetSeconds = 1L
          val catalogueItemRow1           = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("summary-item-1"),
            imageAsset = None,
            isArchived = false,
            createdAt = createdAtBaseline,
          )
          val catalogueItemRow2 = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("summary-item-2"),
            imageAsset = None,
            isArchived = false,
            createdAt = CreatedAt(createdAtBaseline.value.plusSeconds(createdAtOrderOffsetSeconds)),
          )
          val catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow1.catalogueItemID,
            sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("summary-sku-1")))),
            isArchived = false,
            createdAt = createdAtBaseline,
          )
          val catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow1.catalogueItemID,
            sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("summary-sku-2")))),
            isArchived = false,
            createdAt = CreatedAt(createdAtBaseline.value.plusSeconds(createdAtOrderOffsetSeconds)),
          )
          val catalogueItemVariantRow3 = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow2.catalogueItemID,
            sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("summary-sku-3")))),
            isArchived = false,
          )

          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow2, catalogueItemRow1)))
            .zioValue
          postgresClient
            .executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow3, catalogueItemVariantRow2, catalogueItemVariantRow1)
              )
            )
            .zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemSummaryGetResponse = gatewayClient
            .catalogueItemSummaryGet[smithy.InternalServerError](
              Nil,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          catalogueItemSummaryGetResponse.code shouldBe StatusCode.Ok

          val catalogueItemRowsWithVariantRows = List(
            catalogueItemRow1 -> List(catalogueItemVariantRow1, catalogueItemVariantRow2),
            catalogueItemRow2 -> List(catalogueItemVariantRow3),
          )
          val catalogueItemSummariesExpected = catalogueItemRowsWithVariantRows.map {
            case (catalogueItemRow, catalogueItemVariantRows) =>
              smithy.CatalogueItemSummary(
                catalogueItemID = catalogueItemRow.catalogueItemID.value,
                name = catalogueItemRow.name.value,
                status = catalogueItemRow.status.transformInto[smithy.CatalogueItemStatus],
                imageNormalizedUrl = None,
                variants = catalogueItemVariantRows.map(catalogueItemVariantRow =>
                  smithy.CatalogueItemVariantSummary(
                    catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID.value,
                    sku = catalogueItemVariantRow.sku.map(_.value),
                    label = catalogueItemVariantRow.label.map(_.value),
                    price = catalogueItemVariantRow.price.map(catalogueItemVariantPrice =>
                      smithy.CatalogueItemVariantPrice(
                        amount = catalogueItemVariantPrice.value.amount.value,
                        currency = catalogueItemVariantPrice.value.currency.transformInto[smithy.Currency],
                      )
                    ),
                    sellingUnit = smithy.CatalogueItemVariantSaleUnit(
                      quantity = smithy.CatalogueItemVariantQuantity(
                        value = catalogueItemVariantRow.sellingUnit.quantity.value.value,
                        unit = catalogueItemVariantRow.sellingUnit.quantity.unit
                          .transformInto[smithy.CatalogueItemVariantUnit],
                      ),
                      perUnit = catalogueItemVariantRow.sellingUnit.perUnit.map(catalogueItemVariantPerUnit =>
                        smithy.CatalogueItemVariantPerUnit(
                          value = catalogueItemVariantPerUnit.value.value,
                          unit = catalogueItemVariantPerUnit.unit
                            .transformInto[smithy.CatalogueItemVariantUnitPerUnit],
                        )
                      ),
                      unitSize = catalogueItemVariantRow.sellingUnit.unitSize.map(catalogueItemVariantUnitSize =>
                        smithy.CatalogueItemVariantUnitSize(
                          value = catalogueItemVariantUnitSize.value.value,
                          unit = catalogueItemVariantUnitSize.unit
                            .transformInto[smithy.CatalogueItemVariantUnitUnitSize],
                        )
                      ),
                    ),
                    status = catalogueItemVariantRow.status.transformInto[smithy.CatalogueItemVariantStatus],
                  )
                ),
              )
          }

          catalogueItemSummaryGetResponse.body.value shouldBe
            smithy.CatalogueItemSummaryGetResponse(catalogueItemSummariesExpected)

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
            Set(catalogueItemRow1, catalogueItemRow2)
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
            Set(catalogueItemVariantRow1, catalogueItemVariantRow2, catalogueItemVariantRow3)
      }

      "successfully return selected summaries in catalogue order and ignore duplicate and missing IDs" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val createdAtBaseline           = arbitrarySample[CreatedAt]
          val createdAtOrderOffsetSeconds = 1L
          val catalogueItemRow1           = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("selected-item-1"),
            imageAsset = None,
            isArchived = false,
            createdAt = createdAtBaseline,
          )
          val catalogueItemRow2 = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("selected-item-2"),
            imageAsset = None,
            isArchived = false,
            createdAt = CreatedAt(createdAtBaseline.value.plusSeconds(createdAtOrderOffsetSeconds)),
          )
          val catalogueItemRowUnselected = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("selected-item-unselected"),
            imageAsset = None,
            isArchived = false,
          )
          val catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow1.catalogueItemID,
            sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("selected-sku-1")))),
            isArchived = false,
          )
          val catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow2.catalogueItemID,
            sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("selected-sku-2")))),
            isArchived = false,
          )
          val catalogueItemVariantRowUnselected = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRowUnselected.catalogueItemID,
            sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("selected-sku-unselected")))),
            isArchived = false,
          )
          val catalogueItemIDMissing = arbitrarySample[CatalogueItemID]

          Set(
            catalogueItemRow1.catalogueItemID,
            catalogueItemRow2.catalogueItemID,
            catalogueItemRowUnselected.catalogueItemID,
          ) should not contain catalogueItemIDMissing

          postgresClient
            .executeQuery(
              catalogueQueries.insertCatalogueItemRows(
                List(catalogueItemRowUnselected, catalogueItemRow2, catalogueItemRow1)
              )
            )
            .zioValue
          postgresClient
            .executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRowUnselected, catalogueItemVariantRow2, catalogueItemVariantRow1)
              )
            )
            .zioValue

          val catalogueItemIDs = List(
            catalogueItemRow2.catalogueItemID,
            catalogueItemRow1.catalogueItemID,
            catalogueItemRow2.catalogueItemID,
            catalogueItemIDMissing,
          )
          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemSummaryGetResponse = gatewayClient
            .catalogueItemSummaryGet[smithy.InternalServerError](
              catalogueItemIDs,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          catalogueItemSummaryGetResponse.code shouldBe StatusCode.Ok

          val catalogueItemRowsWithVariantRows = List(
            catalogueItemRow1 -> List(catalogueItemVariantRow1),
            catalogueItemRow2 -> List(catalogueItemVariantRow2),
          )
          val catalogueItemSummariesExpected = catalogueItemRowsWithVariantRows.map {
            case (catalogueItemRow, catalogueItemVariantRows) =>
              smithy.CatalogueItemSummary(
                catalogueItemID = catalogueItemRow.catalogueItemID.value,
                name = catalogueItemRow.name.value,
                status = catalogueItemRow.status.transformInto[smithy.CatalogueItemStatus],
                imageNormalizedUrl = None,
                variants = catalogueItemVariantRows.map(catalogueItemVariantRow =>
                  smithy.CatalogueItemVariantSummary(
                    catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID.value,
                    sku = catalogueItemVariantRow.sku.map(_.value),
                    label = catalogueItemVariantRow.label.map(_.value),
                    price = catalogueItemVariantRow.price.map(catalogueItemVariantPrice =>
                      smithy.CatalogueItemVariantPrice(
                        amount = catalogueItemVariantPrice.value.amount.value,
                        currency = catalogueItemVariantPrice.value.currency.transformInto[smithy.Currency],
                      )
                    ),
                    sellingUnit = smithy.CatalogueItemVariantSaleUnit(
                      quantity = smithy.CatalogueItemVariantQuantity(
                        value = catalogueItemVariantRow.sellingUnit.quantity.value.value,
                        unit = catalogueItemVariantRow.sellingUnit.quantity.unit
                          .transformInto[smithy.CatalogueItemVariantUnit],
                      ),
                      perUnit = catalogueItemVariantRow.sellingUnit.perUnit.map(catalogueItemVariantPerUnit =>
                        smithy.CatalogueItemVariantPerUnit(
                          value = catalogueItemVariantPerUnit.value.value,
                          unit = catalogueItemVariantPerUnit.unit
                            .transformInto[smithy.CatalogueItemVariantUnitPerUnit],
                        )
                      ),
                      unitSize = catalogueItemVariantRow.sellingUnit.unitSize.map(catalogueItemVariantUnitSize =>
                        smithy.CatalogueItemVariantUnitSize(
                          value = catalogueItemVariantUnitSize.value.value,
                          unit = catalogueItemVariantUnitSize.unit
                            .transformInto[smithy.CatalogueItemVariantUnitUnitSize],
                        )
                      ),
                    ),
                    status = catalogueItemVariantRow.status.transformInto[smithy.CatalogueItemVariantStatus],
                  )
                ),
              )
          }

          catalogueItemSummaryGetResponse.body.value shouldBe
            smithy.CatalogueItemSummaryGetResponse(catalogueItemSummariesExpected)

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
            Set(catalogueItemRow1, catalogueItemRow2, catalogueItemRowUnselected)
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
            Set(catalogueItemVariantRow1, catalogueItemVariantRow2, catalogueItemVariantRowUnselected)
      }

      "successfully exclude archived and foreign catalogue items and archived variants" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
        val organizationIDForeign = arbitrarySample[OrganizationID]

        organizationIDForeign shouldNot equal(organizationUserRow.organizationID)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          name = CatalogueItemName.assume("filtered-item"),
          imageAsset = None,
          isArchived = false,
        )
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          name = CatalogueItemName.assume("filtered-item-archived"),
          imageAsset = None,
          isArchived = true,
        )
        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationIDForeign,
          name = CatalogueItemName.assume("filtered-item-foreign"),
          imageAsset = None,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("filtered-sku")))),
          isArchived = false,
        )
        val catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("filtered-sku-archived")))),
          isArchived = true,
        )
        val catalogueItemVariantRowForArchivedItem = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRowArchived.catalogueItemID,
          sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("filtered-sku-parent-archived")))),
          isArchived = true,
        )
        val catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("filtered-sku-foreign")))),
          isArchived = false,
        )

        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemRows(
              List(catalogueItemRowForeign, catalogueItemRowArchived, catalogueItemRow)
            )
          )
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(
                catalogueItemVariantRowForeign,
                catalogueItemVariantRowForArchivedItem,
                catalogueItemVariantRowArchived,
                catalogueItemVariantRow,
              )
            )
          )
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemSummaryGetResponse = gatewayClient
          .catalogueItemSummaryGet[smithy.InternalServerError](
            Nil,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemSummaryGetResponse.code shouldBe StatusCode.Ok
        catalogueItemSummaryGetResponse.body.value shouldBe smithy.CatalogueItemSummaryGetResponse(
          List(
            smithy.CatalogueItemSummary(
              catalogueItemID = catalogueItemRow.catalogueItemID.value,
              name = catalogueItemRow.name.value,
              status = catalogueItemRow.status.transformInto[smithy.CatalogueItemStatus],
              imageNormalizedUrl = None,
              variants = List(
                smithy.CatalogueItemVariantSummary(
                  catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID.value,
                  sku = catalogueItemVariantRow.sku.map(_.value),
                  label = catalogueItemVariantRow.label.map(_.value),
                  price = catalogueItemVariantRow.price.map(catalogueItemVariantPrice =>
                    smithy.CatalogueItemVariantPrice(
                      amount = catalogueItemVariantPrice.value.amount.value,
                      currency = catalogueItemVariantPrice.value.currency.transformInto[smithy.Currency],
                    )
                  ),
                  sellingUnit = smithy.CatalogueItemVariantSaleUnit(
                    quantity = smithy.CatalogueItemVariantQuantity(
                      value = catalogueItemVariantRow.sellingUnit.quantity.value.value,
                      unit = catalogueItemVariantRow.sellingUnit.quantity.unit
                        .transformInto[smithy.CatalogueItemVariantUnit],
                    ),
                    perUnit = catalogueItemVariantRow.sellingUnit.perUnit.map(catalogueItemVariantPerUnit =>
                      smithy.CatalogueItemVariantPerUnit(
                        value = catalogueItemVariantPerUnit.value.value,
                        unit = catalogueItemVariantPerUnit.unit.transformInto[smithy.CatalogueItemVariantUnitPerUnit],
                      )
                    ),
                    unitSize = catalogueItemVariantRow.sellingUnit.unitSize.map(catalogueItemVariantUnitSize =>
                      smithy.CatalogueItemVariantUnitSize(
                        value = catalogueItemVariantUnitSize.value.value,
                        unit = catalogueItemVariantUnitSize.unit
                          .transformInto[smithy.CatalogueItemVariantUnitUnitSize],
                      )
                    ),
                  ),
                  status = catalogueItemVariantRow.status.transformInto[smithy.CatalogueItemVariantStatus],
                )
              ),
            )
          )
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemRow, catalogueItemRowArchived, catalogueItemRowForeign)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(
            catalogueItemVariantRow,
            catalogueItemVariantRowArchived,
            catalogueItemVariantRowForArchivedItem,
            catalogueItemVariantRowForeign,
          )
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemSummaryGetResponse = gatewayClient
          .catalogueItemSummaryGet[smithy.BadRequest](
            Nil,
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemSummaryGetResponse.code shouldBe StatusCode.BadRequest
        catalogueItemSummaryGetResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val organizationID = arbitrarySample[OrganizationID]

        val catalogueItemSummaryGetResponse = gatewayClient
          .catalogueItemSummaryGet[smithy.Unauthorized](
            Nil,
            Some(organizationID),
            None,
          )
          .zioValue

        catalogueItemSummaryGetResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemSummaryGetResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val organizationID = arbitrarySample[OrganizationID]

        val catalogueItemSummaryGetResponse = gatewayClient
          .catalogueItemSummaryGet[smithy.Unauthorized](
            Nil,
            Some(organizationID),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        catalogueItemSummaryGetResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemSummaryGetResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemSummaryGetResponse = gatewayClient
          .catalogueItemSummaryGet[smithy.Forbidden](
            Nil,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemSummaryGetResponse.code shouldBe StatusCode.Forbidden
        catalogueItemSummaryGetResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID         = arbitrarySample[UserID]
        val organizationID = arbitrarySample[OrganizationID]
        val accessJwt      = jwtService.generateAccessToken(userID).zioValue

        val catalogueItemSummaryGetResponse = gatewayClient
          .catalogueItemSummaryGet[smithy.InternalServerError](
            Nil,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemSummaryGetResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemSummaryGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID = arbitrarySample[OrganizationID]
        val accessJwt      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemSummaryGetResponse = gatewayClient
          .catalogueItemSummaryGet[smithy.InternalServerError](
            Nil,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemSummaryGetResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemSummaryGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }
    }

    "GET /catalogue/item/uniqueness" should {
      "successfully return both values as unique when neither is reserved" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemName       = arbitrarySample[CatalogueItemName]
        val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]
        val accessJwt               = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemUniquenessGetResponse = gatewayClient
          .catalogueItemUniquenessGet[smithy.InternalServerError](
            Some(catalogueItemName.value),
            Some(catalogueItemVariantSku.value),
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
        catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
          isNameUnique = Some(true),
          isSkuUnique = Some(true),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "successfully return both values as reserved by a non-archived catalogue item and variant" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
          val catalogueItemName       = arbitrarySample[CatalogueItemName]
          val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]
          val catalogueItemRow        = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = catalogueItemName,
            isArchived = false,
          )
          val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = Some(catalogueItemVariantSku),
            isArchived = false,
          )

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
          postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow))).zioValue
          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
            .zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemUniquenessGetResponse = gatewayClient
            .catalogueItemUniquenessGet[smithy.InternalServerError](
              Some(catalogueItemName.value),
              Some(catalogueItemVariantSku.value),
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
          catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
            isNameUnique = Some(false),
            isSkuUnique = Some(false),
          )

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
            List(catalogueItemRow)
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
            List(catalogueItemVariantRow)
      }

      "successfully return only the supplied name uniqueness" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
        val catalogueItemName = arbitrarySample[CatalogueItemName]
        val catalogueItemRow  = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          name = catalogueItemName,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow))).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemUniquenessGetResponse = gatewayClient
          .catalogueItemUniquenessGet[smithy.InternalServerError](
            Some(catalogueItemName.value),
            None,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
        catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
          isNameUnique = Some(false),
          isSkuUnique = None,
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRow)
      }

      "successfully return only the supplied SKU uniqueness" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
        val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]
        val catalogueItemRow        = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = Some(catalogueItemVariantSku),
          isArchived = false,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow))).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemUniquenessGetResponse = gatewayClient
          .catalogueItemUniquenessGet[smithy.InternalServerError](
            None,
            Some(catalogueItemVariantSku.value),
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
        catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
          isNameUnique = None,
          isSkuUnique = Some(false),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRow)
      }

      "successfully return archived catalogue item and variant values as unique" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
        val catalogueItemName        = arbitrarySample[CatalogueItemName]
        val catalogueItemVariantSku  = arbitrarySample[CatalogueItemVariantSku]
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          name = catalogueItemName,
          isArchived = true,
        )
        val catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRowArchived.catalogueItemID,
          sku = Some(catalogueItemVariantSku),
          isArchived = true,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRowArchived)))
          .zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowArchived)))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemUniquenessGetResponse = gatewayClient
          .catalogueItemUniquenessGet[smithy.InternalServerError](
            Some(catalogueItemName.value),
            Some(catalogueItemVariantSku.value),
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
        catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
          isNameUnique = Some(true),
          isSkuUnique = Some(true),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowArchived)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowArchived)
      }

      "successfully return values reserved by another organization as unique" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
        val organizationIDForeign   = arbitrarySample[OrganizationID]
        val catalogueItemName       = arbitrarySample[CatalogueItemName]
        val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]
        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationIDForeign,
          name = catalogueItemName,
          isArchived = false,
        )
        val catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          sku = Some(catalogueItemVariantSku),
          isArchived = false,
        )

        organizationIDForeign shouldNot equal(organizationUserRow.organizationID)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRowForeign)))
          .zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowForeign)))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemUniquenessGetResponse = gatewayClient
          .catalogueItemUniquenessGet[smithy.InternalServerError](
            Some(catalogueItemName.value),
            Some(catalogueItemVariantSku.value),
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
        catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
          isNameUnique = Some(true),
          isSkuUnique = Some(true),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowForeign)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowForeign)
      }

      "successfully report a non-archived variant's case-insensitive SKU as reserved" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
        val catalogueItemVariantSkuStored = CatalogueItemVariantSku.assume("case-sensitive-sku")
        val catalogueItemVariantSkuQuery  = CatalogueItemVariantSku.assume("CASE-SENSITIVE-SKU")
        val catalogueItemRow              = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationUserRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = Some(catalogueItemVariantSkuStored),
          isArchived = false,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow))).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemUniquenessGetResponse = gatewayClient
          .catalogueItemUniquenessGet[smithy.InternalServerError](
            None,
            Some(catalogueItemVariantSkuQuery.value),
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
        catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
          isNameUnique = None,
          isSkuUnique = Some(false),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRow)
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val catalogueItemName = arbitrarySample[CatalogueItemName]
        val accessJwt         = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemUniquenessGetResponse = gatewayClient
          .catalogueItemUniquenessGet[smithy.BadRequest](
            Some(catalogueItemName.value),
            None,
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemUniquenessGetResponse.code shouldBe StatusCode.BadRequest
        catalogueItemUniquenessGetResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a ValidationError when neither a name nor SKU is supplied" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemUniquenessGetResponse = gatewayClient
          .catalogueItemUniquenessGet[smithy.ValidationError](
            None,
            None,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemUniquenessGetResponse.code shouldBe StatusCode.BadRequest
        catalogueItemUniquenessGetResponse.body.left.value shouldBe smithy.ValidationError(
          fields = List("name", "sku")
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a ValidationError when the supplied name and SKU are invalid" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemNameInvalid       = ""
        val catalogueItemVariantSkuInvalid = ""
        val accessJwt                      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemUniquenessGetResponse = gatewayClient
          .catalogueItemUniquenessGet[smithy.ValidationError](
            Some(catalogueItemNameInvalid),
            Some(catalogueItemVariantSkuInvalid),
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemUniquenessGetResponse.code shouldBe StatusCode.BadRequest
        catalogueItemUniquenessGetResponse.body.left.value shouldBe smithy.ValidationError(
          fields = List("name", "sku")
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val catalogueItemName = arbitrarySample[CatalogueItemName]
        val organizationID    = arbitrarySample[OrganizationID]

        val catalogueItemUniquenessGetResponse = gatewayClient
          .catalogueItemUniquenessGet[smithy.Unauthorized](
            Some(catalogueItemName.value),
            None,
            Some(organizationID),
            None,
          )
          .zioValue

        catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemUniquenessGetResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val catalogueItemName = arbitrarySample[CatalogueItemName]
        val organizationID    = arbitrarySample[OrganizationID]

        val catalogueItemUniquenessGetResponse = gatewayClient
          .catalogueItemUniquenessGet[smithy.Unauthorized](
            Some(catalogueItemName.value),
            None,
            Some(organizationID),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Unauthorized
        catalogueItemUniquenessGetResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow]
          .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemName = arbitrarySample[CatalogueItemName]
        val accessJwt         = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemUniquenessGetResponse = gatewayClient
          .catalogueItemUniquenessGet[smithy.Forbidden](
            Some(catalogueItemName.value),
            None,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Forbidden
        catalogueItemUniquenessGetResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID            = arbitrarySample[UserID]
        val catalogueItemName = arbitrarySample[CatalogueItemName]
        val organizationID    = arbitrarySample[OrganizationID]
        val accessJwt         = jwtService.generateAccessToken(userID).zioValue

        val catalogueItemUniquenessGetResponse = gatewayClient
          .catalogueItemUniquenessGet[smithy.InternalServerError](
            Some(catalogueItemName.value),
            None,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemUniquenessGetResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemUniquenessGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val catalogueItemName = arbitrarySample[CatalogueItemName]
        val organizationID    = arbitrarySample[OrganizationID]
        val accessJwt         = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemUniquenessGetResponse = gatewayClient
          .catalogueItemUniquenessGet[smithy.InternalServerError](
            Some(catalogueItemName.value),
            None,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        catalogueItemUniquenessGetResponse.code shouldBe StatusCode.InternalServerError
        catalogueItemUniquenessGetResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }
    }
  }
}
