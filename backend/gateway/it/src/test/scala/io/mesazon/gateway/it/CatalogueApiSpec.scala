package io.mesazon.gateway.it

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
  }
}
