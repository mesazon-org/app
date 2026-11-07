package io.mesazon.gateway.it

import com.dimafeng.testcontainers.DockerComposeContainer
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

import java.time.Instant
import java.time.temporal.ChronoUnit

@DoNotDiscover
class CatalogueApiSpec(dockerComposeContainer: => DockerComposeContainer)
    extends GatewayAcceptanceTest(dockerComposeContainer),
      CatalogueSmithyArbitraries,
      RepositoryArbitraries {

  override def beforeEach(): Unit = {
    super.beforeEach()

    withContext { context =>
      import context.*

      postgresClient.truncateTable(
        repositoryConfig.schema,
        repositoryConfig.userDetailsTable,
        repositoryConfig.organizationUserTable,
        repositoryConfig.catalogueItemTable,
        repositoryConfig.catalogueItemVariantTable,
      )
    }
  }

  "Catalogue Service API" when {
    "POST /catalogue/item" should {
      "successfully insert a catalogue item with multiple variants" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemVariantSku1    = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("sku1"))))
          catalogueItemVariantSku2    = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("sku2"))))
          catalogueItemVariantInsert1 = arbitrarySample[CatalogueItemVariantInsert]
            .copy(sku = catalogueItemVariantSku1)
          catalogueItemVariantInsert2 = arbitrarySample[CatalogueItemVariantInsert]
            .copy(sku = catalogueItemVariantSku2)
          catalogueItemPostRequest = CatalogueItemPostRequest(
            name = arbitrarySample[CatalogueItemName],
            description = arbitrarySample[Option[CatalogueItemDescription]],
            variants = NonEmptyChunk.fromIterable(
              catalogueItemVariantInsert1,
              List(catalogueItemVariantInsert2),
            ),
          )
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemPostResponse <- gatewayClient.catalogueItemPost[smithy.InternalServerError](
            catalogueItemPostRequest.transformInto[smithy.CatalogueItemPostRequest],
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemPostResponse.code shouldBe StatusCode.Ok

          catalogueItemRowsAll <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)

          _ = catalogueItemRowsAll should have size 1

          catalogueItemRow = catalogueItemRowsAll.head

          _ = catalogueItemPostResponse.body.value shouldBe smithy.CatalogueItemPostResponse(
            catalogueItemID = catalogueItemRow.catalogueItemID.value
          )

          _ = catalogueItemRow shouldBe CatalogueItemRow(
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
          _ = catalogueItemRow.updatedAt.value shouldBe catalogueItemRow.createdAt.value
          _ = catalogueItemRow.createdAt.value.isBefore(instantNow) shouldBe false

          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemVariantRowsAll should have size 2

          catalogueItemVariantRowsExpected = catalogueItemPostRequest.variants.toList.map { catalogueItemVariantInsert =>
            catalogueItemVariantRowsAll.find { catalogueItemVariantRow =>
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

          _ = catalogueItemVariantRowsAll should contain theSameElementsAs catalogueItemVariantRowsExpected
        } yield ()
      }

      "fail with a ValidationError when the catalogue item has no variants" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemPostRequest = smithy.CatalogueItemPostRequest(
            name = arbitrarySample[CatalogueItemName].value,
            description = arbitrarySample[Option[CatalogueItemDescription]].map(_.value),
            variants = Nil,
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemPostResponse <- gatewayClient.catalogueItemPost[smithy.ValidationError](
            catalogueItemPostRequest,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueItemPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("variant"))

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe Nil
          _ = catalogueItemVariantRowsAll shouldBe Nil
        } yield ()
      }

      "fail with a ValidationError containing item and variant fields in declaration order" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemVariantInsert = arbitrarySample[CatalogueItemVariantInsert]
            .transformInto[smithy.CatalogueItemVariantInsert]
            .copy(sku = Some(""))
          catalogueItemPostRequest = smithy.CatalogueItemPostRequest(
            name = "",
            description = arbitrarySample[Option[CatalogueItemDescription]].map(_.value),
            variants = List(catalogueItemVariantInsert),
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemPostResponse <- gatewayClient.catalogueItemPost[smithy.ValidationError](
            catalogueItemPostRequest,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueItemPostResponse.body.left.value shouldBe smithy.ValidationError(fields =
            List("name", "variant")
          )

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe Nil
          _ = catalogueItemVariantRowsAll shouldBe Nil
        } yield ()
      }

      "fail with a Conflict when the catalogue item name already exists in the organization" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemPostRequest = arbitrarySample[CatalogueItemPostRequest]
          catalogueItemRowExisting = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = catalogueItemPostRequest.name,
            isArchived = false,
          )
          catalogueItemVariantRowExisting = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRowExisting.catalogueItemID,
            sku = None,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowExisting))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowExisting))
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemPostResponse <- gatewayClient.catalogueItemPost[smithy.Conflict](
            catalogueItemPostRequest.transformInto[smithy.CatalogueItemPostRequest],
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemPostResponse.code shouldBe StatusCode.Conflict
          _ = catalogueItemPostResponse.body.left.value shouldBe smithy.Conflict(message =
            "A catalogue item with the given name already exists in this organization"
          )

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRowExisting)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRowExisting)
        } yield ()
      }

      "fail with a Conflict when the catalogue item variant SKU already exists in the organization" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemVariantSkuConflicting = arbitrarySample[CatalogueItemVariantSku]
            catalogueItemName                  = CatalogueItemName.assume("item-new")
            catalogueItemNameExisting          = CatalogueItemName.assume("item-existing")
            catalogueItemPostRequest           = CatalogueItemPostRequest(
              name = catalogueItemName,
              description = arbitrarySample[Option[CatalogueItemDescription]],
              variants = NonEmptyChunk(
                arbitrarySample[CatalogueItemVariantInsert].copy(sku = Some(catalogueItemVariantSkuConflicting))
              ),
            )
            catalogueItemRowExisting = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = catalogueItemNameExisting,
              isArchived = false,
            )
            catalogueItemVariantRowExisting = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRowExisting.catalogueItemID,
              sku = Some(catalogueItemVariantSkuConflicting),
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowExisting))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowExisting))
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemPostResponse <- gatewayClient.catalogueItemPost[smithy.Conflict](
              catalogueItemPostRequest.transformInto[smithy.CatalogueItemPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

            _ = catalogueItemPostResponse.code shouldBe StatusCode.Conflict
            _ = catalogueItemPostResponse.body.left.value shouldBe smithy.Conflict(message =
              "A catalogue item variant with the given SKU already exists in this organization"
            )

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRowExisting)
            _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRowExisting)
          } yield ()
      }
    }

    "POST /catalogue/item/batch" should {
      "successfully insert catalogue items and return their IDs in request order" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemVariantSku1 =
            arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("batch-sku1"))))
          catalogueItemVariantSku2 =
            arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("batch-sku2"))))
          catalogueItemVariantSku3 =
            arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("batch-sku3"))))
          catalogueItemVariantInsert1 = arbitrarySample[CatalogueItemVariantInsert]
            .copy(sku = catalogueItemVariantSku1)
          catalogueItemVariantInsert2 = arbitrarySample[CatalogueItemVariantInsert]
            .copy(sku = catalogueItemVariantSku2)
          catalogueItemVariantInsert3 = arbitrarySample[CatalogueItemVariantInsert]
            .copy(sku = catalogueItemVariantSku3)
          catalogueItemPostRequest1 = CatalogueItemPostRequest(
            name = CatalogueItemName.assume("batch-item1"),
            description = arbitrarySample[Option[CatalogueItemDescription]],
            variants = NonEmptyChunk.fromIterable(
              catalogueItemVariantInsert1,
              List(catalogueItemVariantInsert2),
            ),
          )
          catalogueItemPostRequest2 = CatalogueItemPostRequest(
            name = CatalogueItemName.assume("batch-item2"),
            description = arbitrarySample[Option[CatalogueItemDescription]],
            variants = NonEmptyChunk(catalogueItemVariantInsert3),
          )
          catalogueItemBatchPostRequest = CatalogueItemBatchPostRequest(
            NonEmptyChunk.fromIterable(catalogueItemPostRequest1, List(catalogueItemPostRequest2))
          )
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemBatchPostResponse <- gatewayClient.catalogueItemBatchPost[smithy.InternalServerError](
            catalogueItemBatchPostRequest.transformInto[smithy.CatalogueItemBatchPostRequest],
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemBatchPostResponse.code shouldBe StatusCode.Ok

          catalogueItemRowsAll <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)

          _ = catalogueItemRowsAll should have size 2

          catalogueItemRow1 = catalogueItemRowsAll.find(_.name == catalogueItemPostRequest1.name).value
          catalogueItemRow2 = catalogueItemRowsAll.find(_.name == catalogueItemPostRequest2.name).value

          _ = catalogueItemBatchPostResponse.body.value shouldBe smithy.CatalogueItemBatchPostResponse(
            catalogueItemIDs = List(catalogueItemRow1.catalogueItemID.value, catalogueItemRow2.catalogueItemID.value)
          )

          _ = catalogueItemRow1 shouldBe CatalogueItemRow(
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
          _ = catalogueItemRow1.updatedAt.value shouldBe catalogueItemRow1.createdAt.value
          _ = catalogueItemRow1.createdAt.value.isBefore(instantNow) shouldBe false

          _ = catalogueItemRow2 shouldBe CatalogueItemRow(
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
          _ = catalogueItemRow2.createdAt.value shouldBe catalogueItemRow1.createdAt.value
          _ = catalogueItemRow2.updatedAt.value shouldBe catalogueItemRow1.createdAt.value

          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemVariantRowsAll should have size 3

          catalogueItemVariantInserts = List(
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
          catalogueItemVariantRowsExpected = catalogueItemVariantInserts.map {
            case (catalogueItemID, catalogueItemVariantInsert) =>
              catalogueItemVariantRowsAll.find { catalogueItemVariantRow =>
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

          _ = catalogueItemVariantRowsAll should contain theSameElementsAs catalogueItemVariantRowsExpected
        } yield ()
      }

      "fail with a ValidationError when the batch has no catalogue items" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemBatchPostRequest = smithy.CatalogueItemBatchPostRequest(catalogueItems = Nil)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemBatchPostResponse <- gatewayClient.catalogueItemBatchPost[smithy.ValidationError](
            catalogueItemBatchPostRequest,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemBatchPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueItemBatchPostResponse.body.left.value shouldBe smithy.ValidationError(
            fields = List("catalogueItem")
          )

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe Nil
          _ = catalogueItemVariantRowsAll shouldBe Nil
        } yield ()
      }

      "fail with a ValidationError containing accumulated catalogue item fields" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemPostRequestValid1    = arbitrarySample[smithy.CatalogueItemPostRequest]
          catalogueItemPostRequestInvalid1  = arbitrarySample[smithy.CatalogueItemPostRequest].copy(name = "")
          catalogueItemPostRequestValid2    = arbitrarySample[smithy.CatalogueItemPostRequest]
          catalogueItemVariantInsertInvalid = arbitrarySample[smithy.CatalogueItemVariantInsert]
            .copy(sku = Some(""))
          catalogueItemPostRequestInvalid2 = smithy.CatalogueItemPostRequest(
            name = arbitrarySample[CatalogueItemName].value,
            description = arbitrarySample[Option[CatalogueItemDescription]].map(_.value),
            variants = List(catalogueItemVariantInsertInvalid),
          )
          catalogueItemBatchPostRequest = smithy.CatalogueItemBatchPostRequest(
            catalogueItems = List(
              catalogueItemPostRequestValid1,
              catalogueItemPostRequestInvalid1,
              catalogueItemPostRequestValid2,
              catalogueItemPostRequestInvalid2,
            )
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemBatchPostResponse <- gatewayClient.catalogueItemBatchPost[smithy.ValidationError](
            catalogueItemBatchPostRequest,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemBatchPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueItemBatchPostResponse.body.left.value shouldBe smithy.ValidationError(
            fields = List("catalogueItem", "catalogueItem")
          )

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe Nil
          _ = catalogueItemVariantRowsAll shouldBe Nil
        } yield ()
      }

      "fail with a Conflict and roll back the batch when a catalogue item name already exists" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemRowExisting = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("existing-item"),
              isArchived = false,
            )
            catalogueItemVariantRowExisting = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRowExisting.catalogueItemID,
              sku = Some(CatalogueItemVariantSku.assume("existing-sku")),
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowExisting))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowExisting))
            )

            catalogueItemPostRequest1 = CatalogueItemPostRequest(
              name = CatalogueItemName.assume("batch-item1"),
              description = arbitrarySample[Option[CatalogueItemDescription]],
              variants = NonEmptyChunk(
                arbitrarySample[CatalogueItemVariantInsert]
                  .copy(sku = Some(CatalogueItemVariantSku.assume("batch-sku1")))
              ),
            )
            catalogueItemPostRequest2 = CatalogueItemPostRequest(
              name = catalogueItemRowExisting.name,
              description = arbitrarySample[Option[CatalogueItemDescription]],
              variants = NonEmptyChunk(
                arbitrarySample[CatalogueItemVariantInsert]
                  .copy(sku = Some(CatalogueItemVariantSku.assume("batch-sku2")))
              ),
            )
            catalogueItemBatchPostRequest = CatalogueItemBatchPostRequest(
              NonEmptyChunk.fromIterable(catalogueItemPostRequest1, List(catalogueItemPostRequest2))
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemBatchPostResponse <- gatewayClient.catalogueItemBatchPost[smithy.Conflict](
              catalogueItemBatchPostRequest.transformInto[smithy.CatalogueItemBatchPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

            _ = catalogueItemBatchPostResponse.code shouldBe StatusCode.Conflict
            _ = catalogueItemBatchPostResponse.body.left.value shouldBe smithy.Conflict(message =
              "A catalogue item with the given name already exists in this organization"
            )

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRowExisting)
            _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRowExisting)
          } yield ()
      }

      "fail with a Conflict and roll back the batch when a variant SKU is duplicated across items" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemVariantSkuDuplicated = CatalogueItemVariantSku.assume("duplicated-sku")
            catalogueItemPostRequest1         = CatalogueItemPostRequest(
              name = CatalogueItemName.assume("batch-item1"),
              description = arbitrarySample[Option[CatalogueItemDescription]],
              variants = NonEmptyChunk(
                arbitrarySample[CatalogueItemVariantInsert].copy(sku = Some(catalogueItemVariantSkuDuplicated))
              ),
            )
            catalogueItemPostRequest2 = CatalogueItemPostRequest(
              name = CatalogueItemName.assume("batch-item2"),
              description = arbitrarySample[Option[CatalogueItemDescription]],
              variants = NonEmptyChunk(
                arbitrarySample[CatalogueItemVariantInsert].copy(sku = Some(catalogueItemVariantSkuDuplicated))
              ),
            )
            catalogueItemBatchPostRequest = CatalogueItemBatchPostRequest(
              NonEmptyChunk.fromIterable(catalogueItemPostRequest1, List(catalogueItemPostRequest2))
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemBatchPostResponse <- gatewayClient.catalogueItemBatchPost[smithy.Conflict](
              catalogueItemBatchPostRequest.transformInto[smithy.CatalogueItemBatchPostRequest],
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

            _ = catalogueItemBatchPostResponse.code shouldBe StatusCode.Conflict
            _ = catalogueItemBatchPostResponse.body.left.value shouldBe smithy.Conflict(message =
              "A catalogue item variant with the given SKU already exists in this organization"
            )

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe Nil
            _ = catalogueItemVariantRowsAll shouldBe Nil
          } yield ()
      }
    }

    "PUT /catalogue/item/{catalogueItemID}" should {
      "successfully update a catalogue item's name" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          catalogueItemPutRequest = CatalogueItemPutRequest(
            name = Some(arbitrarySample[CatalogueItemName]),
            description = None,
            status = None,
            variantsUpdate = Nil,
            variantsInsert = Nil,
          )
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemPutResponse <- gatewayClient.catalogueItemPut[smithy.InternalServerError](
            catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
            catalogueItemRow.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemPutResponse.code shouldBe StatusCode.NoContent
          _ = catalogueItemPutResponse.body.value shouldBe ()

          catalogueItemRowsAll <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)

          _ = catalogueItemRowsAll should have size 1

          catalogueItemRowUpdated = catalogueItemRowsAll.head

          _ = catalogueItemRowUpdated shouldBe catalogueItemRow.copy(
            name = catalogueItemPutRequest.name.value,
            updatedAt = catalogueItemRowUpdated.updatedAt,
          )
          _ = catalogueItemRowUpdated.name shouldNot equal(catalogueItemRow.name)
          _ = catalogueItemRowUpdated.updatedAt.value.isBefore(instantNow) shouldBe false

          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)
        } yield ()
      }

      "successfully clear a catalogue item's description" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            description = Some(arbitrarySample[CatalogueItemDescription]),
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          catalogueItemPutRequest = CatalogueItemPutRequest(
            name = None,
            description = Some(None),
            status = None,
            variantsUpdate = Nil,
            variantsInsert = Nil,
          )
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemPutResponse <- gatewayClient.catalogueItemPut[smithy.InternalServerError](
            catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
            catalogueItemRow.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemPutResponse.code shouldBe StatusCode.NoContent
          _ = catalogueItemPutResponse.body.value shouldBe ()

          catalogueItemRowsAll <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)

          _ = catalogueItemRowsAll should have size 1

          catalogueItemRowUpdated = catalogueItemRowsAll.head

          _ = catalogueItemRowUpdated shouldBe catalogueItemRow.copy(
            description = None,
            updatedAt = catalogueItemRowUpdated.updatedAt,
          )
          _ = catalogueItemRowUpdated.updatedAt.value.isBefore(instantNow) shouldBe false

          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)
        } yield ()
      }

      "fail with a ValidationError containing item and variant fields in declaration order" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("item-validation"),
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = Some(CatalogueItemVariantSku.assume("sku-validation")),
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          catalogueItemVariantUpdateInvalid = arbitrarySample[smithy.CatalogueItemVariantUpdate].copy(
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
          catalogueItemVariantInsertInvalid = arbitrarySample[smithy.CatalogueItemVariantInsert]
            .copy(sku = Some(""))
          catalogueItemPutRequest = smithy.CatalogueItemPutRequest(
            name = Some(""),
            description = Some(Nullable.Value("")),
            variantsUpdate = List(catalogueItemVariantUpdateInvalid),
            variantsInsert = List(catalogueItemVariantInsertInvalid),
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemPutResponse <- gatewayClient.catalogueItemPut[smithy.ValidationError](
            catalogueItemPutRequest,
            catalogueItemRow.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemPutResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueItemPutResponse.body.left.value shouldBe smithy.ValidationError(
            fields = List("name", "description", "variant", "variant")
          )

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)
        } yield ()
      }

      "fail with a Conflict and roll back the update when the catalogue item name already exists" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("item-name-update-target"),
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              sku = Some(CatalogueItemVariantSku.assume("sku-name-update-target")),
              isArchived = false,
            )
            catalogueItemRowConflicting = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("item-name-conflicting"),
              isArchived = false,
            )
            catalogueItemVariantRowConflicting = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRowConflicting.catalogueItemID,
              sku = Some(CatalogueItemVariantSku.assume("sku-name-conflicting")),
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowConflicting))
            )
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowConflicting)
              )
            )

            catalogueItemVariantUpdate = CatalogueItemVariantUpdate(
              catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID,
              sku = None,
              label = Some(Some(CatalogueItemVariantLabel.assume("label-should-roll-back"))),
              description = None,
              attributes = None,
              price = None,
              sellingUnit = None,
              status = None,
            )
            catalogueItemPutRequest = CatalogueItemPutRequest(
              name = Some(catalogueItemRowConflicting.name),
              description = None,
              status = None,
              variantsUpdate = List(catalogueItemVariantUpdate),
              variantsInsert = Nil,
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemPutResponse <- gatewayClient.catalogueItemPut[smithy.Conflict](
              catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
              catalogueItemRow.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

            _ = catalogueItemPutResponse.code shouldBe StatusCode.Conflict
            _ = catalogueItemPutResponse.body.left.value shouldBe smithy.Conflict(message =
              "A catalogue item with the given name already exists in this organization"
            )

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll.toSet shouldBe Set(catalogueItemRow, catalogueItemRowConflicting)
            _ = catalogueItemVariantRowsAll.toSet shouldBe
              Set(catalogueItemVariantRow, catalogueItemVariantRowConflicting)
          } yield ()
      }

      "fail with a Conflict and roll back the update when the catalogue item variant SKU already exists" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("item-sku-update-target"),
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              sku = Some(CatalogueItemVariantSku.assume("sku-update-target")),
              isArchived = false,
            )
            catalogueItemRowConflicting = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("item-sku-conflicting"),
              isArchived = false,
            )
            catalogueItemVariantRowConflicting = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRowConflicting.catalogueItemID,
              sku = Some(CatalogueItemVariantSku.assume("sku-conflicting")),
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowConflicting))
            )
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowConflicting)
              )
            )

            catalogueItemVariantUpdate = CatalogueItemVariantUpdate(
              catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID,
              sku = Some(catalogueItemVariantRowConflicting.sku),
              label = None,
              description = None,
              attributes = None,
              price = None,
              sellingUnit = None,
              status = None,
            )
            catalogueItemPutRequest = CatalogueItemPutRequest(
              name = Some(CatalogueItemName.assume("item-sku-parent-should-roll-back")),
              description = None,
              status = None,
              variantsUpdate = List(catalogueItemVariantUpdate),
              variantsInsert = Nil,
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemPutResponse <- gatewayClient.catalogueItemPut[smithy.Conflict](
              catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
              catalogueItemRow.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

            _ = catalogueItemPutResponse.code shouldBe StatusCode.Conflict
            _ = catalogueItemPutResponse.body.left.value shouldBe smithy.Conflict(message =
              "A catalogue item variant with the given SKU already exists in this organization"
            )

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll.toSet shouldBe Set(catalogueItemRow, catalogueItemRowConflicting)
            _ = catalogueItemVariantRowsAll.toSet shouldBe
              Set(catalogueItemVariantRow, catalogueItemVariantRowConflicting)
          } yield ()
      }

      "fail with an InternalServerError and roll back the update when a catalogue item variant does not exist" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("item-missing-variant"),
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              sku = Some(CatalogueItemVariantSku.assume("sku-missing-variant-sibling")),
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
            )

            catalogueItemVariantUpdate = arbitrarySample[CatalogueItemVariantUpdate]
            catalogueItemPutRequest    = CatalogueItemPutRequest(
              name = Some(CatalogueItemName.assume("item-name-should-roll-back")),
              description = None,
              status = None,
              variantsUpdate = List(catalogueItemVariantUpdate),
              variantsInsert = Nil,
            )

            _ = catalogueItemVariantUpdate.catalogueItemVariantID shouldNot equal(
              catalogueItemVariantRow.catalogueItemVariantID
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemPutResponse <- gatewayClient.catalogueItemPut[smithy.InternalServerError](
              catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
              catalogueItemRow.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

            _ = catalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
            _ = catalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
            _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)
          } yield ()
      }

      "fail with an InternalServerError and roll back the update when a catalogue item variant is archived" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("item-archived-variant"),
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              sku = Some(CatalogueItemVariantSku.assume("sku-archived-variant-sibling")),
              isArchived = false,
            )
            catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              sku = Some(CatalogueItemVariantSku.assume("sku-archived-variant")),
              isArchived = true,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowArchived)
              )
            )

            catalogueItemVariantUpdate = arbitrarySample[CatalogueItemVariantUpdate].copy(
              catalogueItemVariantID = catalogueItemVariantRowArchived.catalogueItemVariantID
            )
            catalogueItemPutRequest = CatalogueItemPutRequest(
              name = Some(CatalogueItemName.assume("item-name-should-roll-back")),
              description = None,
              status = None,
              variantsUpdate = List(catalogueItemVariantUpdate),
              variantsInsert = Nil,
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemPutResponse <- gatewayClient.catalogueItemPut[smithy.InternalServerError](
              catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
              catalogueItemRow.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

            _ = catalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
            _ = catalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
            _ = catalogueItemVariantRowsAll.toSet shouldBe
              Set(catalogueItemVariantRow, catalogueItemVariantRowArchived)
          } yield ()
      }

      "fail with an InternalServerError and roll back the update when a variant belongs to another catalogue item" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("item-variant-parent-target"),
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              sku = Some(CatalogueItemVariantSku.assume("sku-variant-parent-target")),
              isArchived = false,
            )
            catalogueItemRowOther = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("item-variant-parent-other"),
              isArchived = false,
            )
            catalogueItemVariantRowOther = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRowOther.catalogueItemID,
              sku = Some(CatalogueItemVariantSku.assume("sku-variant-parent-other")),
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowOther))
            )
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowOther)
              )
            )

            catalogueItemVariantUpdate = arbitrarySample[CatalogueItemVariantUpdate].copy(
              catalogueItemVariantID = catalogueItemVariantRowOther.catalogueItemVariantID
            )
            catalogueItemPutRequest = CatalogueItemPutRequest(
              name = Some(CatalogueItemName.assume("item-name-should-roll-back")),
              description = None,
              status = None,
              variantsUpdate = List(catalogueItemVariantUpdate),
              variantsInsert = Nil,
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemPutResponse <- gatewayClient.catalogueItemPut[smithy.InternalServerError](
              catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
              catalogueItemRow.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

            _ = catalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
            _ = catalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll.toSet shouldBe Set(catalogueItemRow, catalogueItemRowOther)
            _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowOther)
          } yield ()
      }

      "fail with an InternalServerError and roll back the update when a variant belongs to another organization" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
            organizationIDForeign = arbitrarySample[OrganizationID]

            _ = organizationIDForeign shouldNot equal(organizationUserRow.organizationID)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("item-variant-organization-target"),
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              sku = Some(CatalogueItemVariantSku.assume("sku-variant-organization-target")),
              isArchived = false,
            )
            catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationIDForeign,
              name = CatalogueItemName.assume("item-variant-organization-other"),
              isArchived = false,
            )
            catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationIDForeign,
              catalogueItemID = catalogueItemRowForeign.catalogueItemID,
              sku = Some(CatalogueItemVariantSku.assume("sku-variant-organization-other")),
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowForeign))
            )
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowForeign)
              )
            )

            catalogueItemVariantUpdate = arbitrarySample[CatalogueItemVariantUpdate].copy(
              catalogueItemVariantID = catalogueItemVariantRowForeign.catalogueItemVariantID
            )
            catalogueItemPutRequest = CatalogueItemPutRequest(
              name = Some(CatalogueItemName.assume("item-name-should-roll-back")),
              description = None,
              status = None,
              variantsUpdate = List(catalogueItemVariantUpdate),
              variantsInsert = Nil,
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemPutResponse <- gatewayClient.catalogueItemPut[smithy.InternalServerError](
              catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
              catalogueItemRow.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

            _ = catalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
            _ = catalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll.toSet shouldBe Set(catalogueItemRow, catalogueItemRowForeign)
            _ = catalogueItemVariantRowsAll.toSet shouldBe
              Set(catalogueItemVariantRow, catalogueItemVariantRowForeign)
          } yield ()
      }

      "fail with an InternalServerError and roll back the update when a catalogue item variant ID is repeated" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("item-repeated-variant"),
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              sku = Some(CatalogueItemVariantSku.assume("sku-repeated-variant")),
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
            )

            catalogueItemVariantUpdate = arbitrarySample[CatalogueItemVariantUpdate]
              .copy(catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID)
            catalogueItemPutRequest = CatalogueItemPutRequest(
              name = Some(CatalogueItemName.assume("item-name-should-roll-back")),
              description = None,
              status = None,
              variantsUpdate = List(catalogueItemVariantUpdate, catalogueItemVariantUpdate),
              variantsInsert = Nil,
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemPutResponse <- gatewayClient.catalogueItemPut[smithy.InternalServerError](
              catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest],
              catalogueItemRow.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

            _ = catalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
            _ = catalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
            _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)
          } yield ()
      }
    }

    "PUT /catalogue/item/{catalogueItemID}/archive" should {
      "successfully archive a catalogue item and its variants" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemArchivePutResponse <- gatewayClient.catalogueItemArchivePut[smithy.InternalServerError](
            catalogueItemRow.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemArchivePutResponse.code shouldBe StatusCode.NoContent
          _ = catalogueItemArchivePutResponse.body.value shouldBe ()

          catalogueItemRowsAll <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)

          _ = catalogueItemRowsAll should have size 1

          catalogueItemRowArchived = catalogueItemRowsAll.head

          _ = catalogueItemRowArchived shouldBe catalogueItemRow.copy(
            isArchived = true,
            updatedAt = catalogueItemRowArchived.updatedAt,
          )
          _ = catalogueItemRowArchived.updatedAt.value.isBefore(instantNow) shouldBe false

          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemVariantRowsAll should have size 1

          catalogueItemVariantRowArchived = catalogueItemVariantRowsAll.head

          _ = catalogueItemVariantRowArchived shouldBe catalogueItemVariantRow.copy(
            isArchived = true,
            updatedAt = catalogueItemVariantRowArchived.updatedAt,
          )
          _ = catalogueItemVariantRowArchived.updatedAt shouldBe catalogueItemRowArchived.updatedAt
        } yield ()
      }
    }

    "PUT /catalogue/item/{catalogueItemID}/variant/{catalogueItemVariantID}/archive" should {
      "successfully archive a catalogue item variant" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          catalogueItemVariantSku =
            arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("sku-archive"))))
          catalogueItemVariantSkuSibling =
            arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("sku-archive-sibling"))))
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = catalogueItemVariantSku,
            isArchived = false,
          )
          catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = catalogueItemVariantSkuSibling,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
            )
          )

          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemVariantArchivePutResponse <- gatewayClient
            .catalogueItemVariantArchivePut[smithy.InternalServerError](
              catalogueItemRow.catalogueItemID,
              catalogueItemVariantRow.catalogueItemVariantID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

          _ = catalogueItemVariantArchivePutResponse.code shouldBe StatusCode.NoContent
          _ = catalogueItemVariantArchivePutResponse.body.value shouldBe ()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll should have size 2

          catalogueItemVariantRowArchived = catalogueItemVariantRowsAll
            .find(_.catalogueItemVariantID == catalogueItemVariantRow.catalogueItemVariantID)
            .value
          catalogueItemVariantRowSiblingUnchanged = catalogueItemVariantRowsAll
            .find(_.catalogueItemVariantID == catalogueItemVariantRowSibling.catalogueItemVariantID)
            .value

          _ = catalogueItemVariantRowArchived shouldBe catalogueItemVariantRow.copy(
            isArchived = true,
            updatedAt = catalogueItemVariantRowArchived.updatedAt,
          )
          _ = catalogueItemVariantRowArchived.updatedAt.value.isBefore(instantNow) shouldBe false

          _ = catalogueItemVariantRowSiblingUnchanged shouldBe catalogueItemVariantRowSibling
        } yield ()
      }

      "fail with a Forbidden when the variant is the last non-archived variant" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemVariantArchivePutResponse <- gatewayClient.catalogueItemVariantArchivePut[smithy.Forbidden](
            catalogueItemRow.catalogueItemID,
            catalogueItemVariantRow.catalogueItemVariantID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemVariantArchivePutResponse.code shouldBe StatusCode.Forbidden
          _ = catalogueItemVariantArchivePutResponse.body.left.value shouldBe smithy.Forbidden()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)
        } yield ()
      }
    }

    "GET /catalogue/item/{catalogueItemID}" should {
      "successfully return a catalogue item with its non-archived variants" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemVariantSku =
            arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("sku-detail"))))
          catalogueItemVariantSkuArchived =
            arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("sku-detail-archived"))))
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = catalogueItemVariantSku,
            isArchived = false,
          )
          catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = catalogueItemVariantSkuArchived,
            isArchived = true,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRow, catalogueItemVariantRowArchived)
            )
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemGetResponse <- gatewayClient.catalogueItemGet[smithy.InternalServerError](
            catalogueItemRow.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemGetResponse.code shouldBe StatusCode.Ok
          _ = catalogueItemGetResponse.body.value shouldBe smithy.CatalogueItemGetResponse(
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

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowArchived)
        } yield ()
      }

      "successfully return a catalogue item with its image URL" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            imageAsset = Some(arbitrarySample[CatalogueItemImageAsset]),
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemGetResponse <- gatewayClient.catalogueItemGet[smithy.InternalServerError](
            catalogueItemRow.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          catalogueItemImageNormalizedUrl = catalogueItemGetResponse.body.value.imageNormalizedUrl.value

          _ = catalogueItemGetResponse.code shouldBe StatusCode.Ok
          _ = catalogueItemGetResponse.body.value shouldBe smithy.CatalogueItemGetResponse(
            catalogueItemID = catalogueItemRow.catalogueItemID.value,
            name = catalogueItemRow.name.value,
            description = catalogueItemRow.description.map(_.value),
            status = catalogueItemRow.status.transformInto[smithy.CatalogueItemStatus],
            imageNormalizedUrl = Some(catalogueItemImageNormalizedUrl),
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

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)
        } yield ()
      }

      "fail with an InternalServerError when the catalogue item does not exist" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemID = arbitrarySample[CatalogueItemID]

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemGetResponse <- gatewayClient.catalogueItemGet[smithy.InternalServerError](
            catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemGetResponse.code shouldBe StatusCode.InternalServerError
          _ = catalogueItemGetResponse.body.left.value shouldBe smithy.InternalServerError()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe Nil
          _ = catalogueItemVariantRowsAll shouldBe Nil
        } yield ()
      }

      "fail with an InternalServerError when the catalogue item is archived" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = true,
          )
          catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRowArchived.catalogueItemID,
            isArchived = true,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowArchived))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowArchived))
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemGetResponse <- gatewayClient.catalogueItemGet[smithy.InternalServerError](
            catalogueItemRowArchived.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemGetResponse.code shouldBe StatusCode.InternalServerError
          _ = catalogueItemGetResponse.body.left.value shouldBe smithy.InternalServerError()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRowArchived)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRowArchived)
        } yield ()
      }

      "fail with an InternalServerError when the catalogue item belongs to another organization" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
            organizationIDForeign = arbitrarySample[OrganizationID]

            _ = organizationIDForeign shouldNot equal(organizationUserRow.organizationID)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationIDForeign,
              isArchived = false,
            )
            catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationIDForeign,
              catalogueItemID = catalogueItemRowForeign.catalogueItemID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowForeign))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowForeign))
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemGetResponse <- gatewayClient.catalogueItemGet[smithy.InternalServerError](
              catalogueItemRowForeign.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

            _ = catalogueItemGetResponse.code shouldBe StatusCode.InternalServerError
            _ = catalogueItemGetResponse.body.left.value shouldBe smithy.InternalServerError()

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRowForeign)
            _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRowForeign)
          } yield ()
      }
    }

    "GET /catalogue/item/summary" should {
      "successfully return an empty list when the catalogue is empty" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemSummaryGetResponse <- gatewayClient.catalogueItemSummaryGet[smithy.InternalServerError](
            Nil,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemSummaryGetResponse.code shouldBe StatusCode.Ok
          _ = catalogueItemSummaryGetResponse.body.value shouldBe smithy.CatalogueItemSummaryGetResponse(Nil)

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe Nil
          _ = catalogueItemVariantRowsAll shouldBe Nil
        } yield ()
      }

      "successfully return all catalogue item summaries in item and variant order when no IDs are supplied" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            createdAtBaseline           = arbitrarySample[CreatedAt]
            createdAtOrderOffsetSeconds = 1L
            catalogueItemRow1           = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("summary-item-1"),
              imageAsset = Some(arbitrarySample[CatalogueItemImageAsset]),
              isArchived = false,
              createdAt = createdAtBaseline,
            )
            catalogueItemRow2 = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("summary-item-2"),
              imageAsset = None,
              isArchived = false,
              createdAt = CreatedAt(createdAtBaseline.value.plusSeconds(createdAtOrderOffsetSeconds)),
            )
            catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow1.catalogueItemID,
              sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("summary-sku-1")))),
              isArchived = false,
              createdAt = createdAtBaseline,
            )
            catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow1.catalogueItemID,
              sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("summary-sku-2")))),
              isArchived = false,
              createdAt = CreatedAt(createdAtBaseline.value.plusSeconds(createdAtOrderOffsetSeconds)),
            )
            catalogueItemVariantRow3 = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow2.catalogueItemID,
              sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("summary-sku-3")))),
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow2, catalogueItemRow1))
            )
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow3, catalogueItemVariantRow2, catalogueItemVariantRow1)
              )
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemSummaryGetResponse <- gatewayClient.catalogueItemSummaryGet[smithy.InternalServerError](
              Nil,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

            catalogueItemImageNormalizedUrl1 = catalogueItemSummaryGetResponse.body.value.catalogueItemSummaries
              .find(_.catalogueItemID == catalogueItemRow1.catalogueItemID.value)
              .flatMap(_.imageNormalizedUrl)
              .value
            catalogueItemRowsWithVariantRows = List(
              (
                catalogueItemRow = catalogueItemRow1,
                catalogueItemVariantRows = List(catalogueItemVariantRow1, catalogueItemVariantRow2),
                imageNormalizedUrl = Some(catalogueItemImageNormalizedUrl1),
              ),
              (
                catalogueItemRow = catalogueItemRow2,
                catalogueItemVariantRows = List(catalogueItemVariantRow3),
                imageNormalizedUrl = None,
              ),
            )
            catalogueItemSummariesExpected = catalogueItemRowsWithVariantRows.map {
              case (catalogueItemRow, catalogueItemVariantRows, imageNormalizedUrl) =>
                smithy.CatalogueItemSummary(
                  catalogueItemID = catalogueItemRow.catalogueItemID.value,
                  name = catalogueItemRow.name.value,
                  status = catalogueItemRow.status.transformInto[smithy.CatalogueItemStatus],
                  imageNormalizedUrl = imageNormalizedUrl,
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

            _ = catalogueItemSummaryGetResponse.code shouldBe StatusCode.Ok
            _ = catalogueItemSummaryGetResponse.body.value shouldBe
              smithy.CatalogueItemSummaryGetResponse(catalogueItemSummariesExpected)

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll.toSet shouldBe Set(catalogueItemRow1, catalogueItemRow2)
            _ = catalogueItemVariantRowsAll.toSet shouldBe
              Set(catalogueItemVariantRow1, catalogueItemVariantRow2, catalogueItemVariantRow3)
          } yield ()
      }

      "successfully return only the selected summaries in catalogue order, ignoring duplicate and missing IDs" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            createdAtBaseline           = arbitrarySample[CreatedAt]
            createdAtOrderOffsetSeconds = 1L
            catalogueItemRow1           = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("selected-item-1"),
              imageAsset = None,
              isArchived = false,
              createdAt = createdAtBaseline,
            )
            catalogueItemRow2 = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("selected-item-2"),
              imageAsset = None,
              isArchived = false,
              createdAt = CreatedAt(createdAtBaseline.value.plusSeconds(createdAtOrderOffsetSeconds)),
            )
            catalogueItemRowUnselected = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = CatalogueItemName.assume("selected-item-unselected"),
              imageAsset = None,
              isArchived = false,
            )
            catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow1.catalogueItemID,
              sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("selected-sku-1")))),
              isArchived = false,
            )
            catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow2.catalogueItemID,
              sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("selected-sku-2")))),
              isArchived = false,
            )
            catalogueItemVariantRowUnselected = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRowUnselected.catalogueItemID,
              sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("selected-sku-unselected")))),
              isArchived = false,
            )
            catalogueItemIDMissing = arbitrarySample[CatalogueItemID]

            _ = Set(
              catalogueItemRow1.catalogueItemID,
              catalogueItemRow2.catalogueItemID,
              catalogueItemRowUnselected.catalogueItemID,
            ) should not contain catalogueItemIDMissing

            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemRows(
                List(catalogueItemRowUnselected, catalogueItemRow2, catalogueItemRow1)
              )
            )
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRowUnselected, catalogueItemVariantRow2, catalogueItemVariantRow1)
              )
            )

            catalogueItemIDs = List(
              catalogueItemRow2.catalogueItemID,
              catalogueItemRow1.catalogueItemID,
              catalogueItemRow2.catalogueItemID,
              catalogueItemIDMissing,
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemSummaryGetResponse <- gatewayClient.catalogueItemSummaryGet[smithy.InternalServerError](
              catalogueItemIDs,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )

            catalogueItemRowsWithVariantRows = List(
              (
                catalogueItemRow = catalogueItemRow1,
                catalogueItemVariantRows = List(catalogueItemVariantRow1),
              ),
              (
                catalogueItemRow = catalogueItemRow2,
                catalogueItemVariantRows = List(catalogueItemVariantRow2),
              ),
            )
            catalogueItemSummariesExpected = catalogueItemRowsWithVariantRows.map {
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

            _ = catalogueItemSummaryGetResponse.code shouldBe StatusCode.Ok
            _ = catalogueItemSummaryGetResponse.body.value shouldBe
              smithy.CatalogueItemSummaryGetResponse(catalogueItemSummariesExpected)

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll.toSet shouldBe Set(
              catalogueItemRow1,
              catalogueItemRow2,
              catalogueItemRowUnselected,
            )
            _ = catalogueItemVariantRowsAll.toSet shouldBe
              Set(catalogueItemVariantRow1, catalogueItemVariantRow2, catalogueItemVariantRowUnselected)
          } yield ()
      }

      "successfully exclude archived and foreign catalogue items and archived variants" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
          organizationIDForeign = arbitrarySample[OrganizationID]

          _ = organizationIDForeign shouldNot equal(organizationUserRow.organizationID)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("filtered-item"),
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = CatalogueItemName.assume("filtered-item-archived"),
            imageAsset = None,
            isArchived = true,
          )
          catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationIDForeign,
            name = CatalogueItemName.assume("filtered-item-foreign"),
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("filtered-sku")))),
            isArchived = false,
          )
          catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("filtered-sku-archived")))),
            isArchived = true,
          )
          catalogueItemVariantRowForArchivedItem = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRowArchived.catalogueItemID,
            sku =
              arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("filtered-sku-parent-archived")))),
            isArchived = true,
          )
          catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationIDForeign,
            catalogueItemID = catalogueItemRowForeign.catalogueItemID,
            sku = arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("filtered-sku-foreign")))),
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemRows(
              List(catalogueItemRowForeign, catalogueItemRowArchived, catalogueItemRow)
            )
          )
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(
                catalogueItemVariantRowForeign,
                catalogueItemVariantRowForArchivedItem,
                catalogueItemVariantRowArchived,
                catalogueItemVariantRow,
              )
            )
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemSummaryGetResponse <- gatewayClient.catalogueItemSummaryGet[smithy.InternalServerError](
            Nil,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemSummaryGetResponse.code shouldBe StatusCode.Ok
          _ = catalogueItemSummaryGetResponse.body.value shouldBe smithy.CatalogueItemSummaryGetResponse(
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

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll.toSet shouldBe Set(
            catalogueItemRow,
            catalogueItemRowArchived,
            catalogueItemRowForeign,
          )
          _ = catalogueItemVariantRowsAll.toSet shouldBe Set(
            catalogueItemVariantRow,
            catalogueItemVariantRowArchived,
            catalogueItemVariantRowForArchivedItem,
            catalogueItemVariantRowForeign,
          )
        } yield ()
      }
    }

    "GET /catalogue/item/uniqueness" should {
      "successfully return both values as unique when neither is reserved" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemName       = arbitrarySample[CatalogueItemName]
          catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemUniquenessGetResponse <- gatewayClient.catalogueItemUniquenessGet[smithy.InternalServerError](
            Some(catalogueItemName.value),
            Some(catalogueItemVariantSku.value),
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
          _ = catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
            isNameUnique = Some(true),
            isSkuUnique = Some(true),
          )

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe Nil
          _ = catalogueItemVariantRowsAll shouldBe Nil
        } yield ()
      }

      "successfully return both values as reserved by a non-archived catalogue item and variant" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
            catalogueItemName       = arbitrarySample[CatalogueItemName]
            catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]
            catalogueItemRow        = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              name = catalogueItemName,
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              sku = Some(catalogueItemVariantSku),
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))
            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow)))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemUniquenessGetResponse <- gatewayClient
              .catalogueItemUniquenessGet[smithy.InternalServerError](
                Some(catalogueItemName.value),
                Some(catalogueItemVariantSku.value),
                Some(organizationUserRow.organizationID),
                Some(accessJwt.accessToken),
              )

            _ = catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
            _ = catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
              isNameUnique = Some(false),
              isSkuUnique = Some(false),
            )

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
            _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)
          } yield ()
      }

      "successfully return only the name result when only a reserved name is supplied" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
          catalogueItemName = arbitrarySample[CatalogueItemName]
          catalogueItemRow  = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = catalogueItemName,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))
          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow)))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemUniquenessGetResponse <- gatewayClient.catalogueItemUniquenessGet[smithy.InternalServerError](
            Some(catalogueItemName.value),
            None,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
          _ = catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
            isNameUnique = Some(false),
            isSkuUnique = None,
          )

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)
        } yield ()
      }

      "successfully return only the SKU result when only a reserved SKU is supplied" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
          catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]
          catalogueItemRow        = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = Some(catalogueItemVariantSku),
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))
          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow)))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemUniquenessGetResponse <- gatewayClient.catalogueItemUniquenessGet[smithy.InternalServerError](
            None,
            Some(catalogueItemVariantSku.value),
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
          _ = catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
            isNameUnique = None,
            isSkuUnique = Some(false),
          )

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)
        } yield ()
      }

      "successfully return archived catalogue item and variant values as unique" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
          catalogueItemName        = arbitrarySample[CatalogueItemName]
          catalogueItemVariantSku  = arbitrarySample[CatalogueItemVariantSku]
          catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = catalogueItemName,
            isArchived = true,
          )
          catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRowArchived.catalogueItemID,
            sku = Some(catalogueItemVariantSku),
            isArchived = true,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))
          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRowArchived)))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowArchived))
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemUniquenessGetResponse <- gatewayClient.catalogueItemUniquenessGet[smithy.InternalServerError](
            Some(catalogueItemName.value),
            Some(catalogueItemVariantSku.value),
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
          _ = catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
            isNameUnique = Some(true),
            isSkuUnique = Some(true),
          )

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRowArchived)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRowArchived)
        } yield ()
      }

      "successfully return values reserved by another organization as unique" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
          organizationIDForeign   = arbitrarySample[OrganizationID]
          catalogueItemName       = arbitrarySample[CatalogueItemName]
          catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]
          catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationIDForeign,
            name = catalogueItemName,
            isArchived = false,
          )
          catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationIDForeign,
            catalogueItemID = catalogueItemRowForeign.catalogueItemID,
            sku = Some(catalogueItemVariantSku),
            isArchived = false,
          )

          _ = organizationIDForeign shouldNot equal(organizationUserRow.organizationID)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))
          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRowForeign)))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowForeign))
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemUniquenessGetResponse <- gatewayClient.catalogueItemUniquenessGet[smithy.InternalServerError](
            Some(catalogueItemName.value),
            Some(catalogueItemVariantSku.value),
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
          _ = catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
            isNameUnique = Some(true),
            isSkuUnique = Some(true),
          )

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRowForeign)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRowForeign)
        } yield ()
      }

      "successfully report a SKU reserved under a different letter case as not unique" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
          catalogueItemVariantSkuStored = CatalogueItemVariantSku.assume("case-sensitive-sku")
          catalogueItemVariantSkuQuery  = CatalogueItemVariantSku.assume("CASE-SENSITIVE-SKU")
          catalogueItemRow              = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            sku = Some(catalogueItemVariantSkuStored),
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))
          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow)))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemUniquenessGetResponse <- gatewayClient.catalogueItemUniquenessGet[smithy.InternalServerError](
            None,
            Some(catalogueItemVariantSkuQuery.value),
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
          _ = catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
            isNameUnique = None,
            isSkuUnique = Some(false),
          )

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)
        } yield ()
      }

      "successfully report a name reserved under a different letter case as unique" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)
          catalogueItemNameStored = CatalogueItemName.assume("case-sensitive-name")
          catalogueItemNameQuery  = CatalogueItemName.assume("CASE-SENSITIVE-NAME")
          catalogueItemRow        = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = catalogueItemNameStored,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))
          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow)))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemUniquenessGetResponse <- gatewayClient.catalogueItemUniquenessGet[smithy.InternalServerError](
            Some(catalogueItemNameQuery.value),
            None,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemUniquenessGetResponse.code shouldBe StatusCode.Ok
          _ = catalogueItemUniquenessGetResponse.body.value shouldBe smithy.CatalogueItemUniquenessGetResponse(
            isNameUnique = Some(true),
            isSkuUnique = None,
          )

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)
        } yield ()
      }

      "fail with a ValidationError when neither a name nor SKU is supplied" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemUniquenessGetResponse <- gatewayClient.catalogueItemUniquenessGet[smithy.ValidationError](
            None,
            None,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemUniquenessGetResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueItemUniquenessGetResponse.body.left.value shouldBe smithy.ValidationError(
            fields = List("name", "sku")
          )

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe Nil
          _ = catalogueItemVariantRowsAll shouldBe Nil
        } yield ()
      }

      "fail with a ValidationError when the supplied name and SKU are invalid" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          organizationUserRole <- Random.shuffle(OrganizationUserRole.userRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow]
            .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          catalogueItemNameInvalid       = ""
          catalogueItemVariantSkuInvalid = ""

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemUniquenessGetResponse <- gatewayClient.catalogueItemUniquenessGet[smithy.ValidationError](
            Some(catalogueItemNameInvalid),
            Some(catalogueItemVariantSkuInvalid),
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )

          _ = catalogueItemUniquenessGetResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueItemUniquenessGetResponse.body.left.value shouldBe smithy.ValidationError(
            fields = List("name", "sku")
          )

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe Nil
          _ = catalogueItemVariantRowsAll shouldBe Nil
        } yield ()
      }
    }

    "middleware" should {
      "fail with a BadRequest when the organization id header is missing on every endpoint" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationID   = arbitrarySample[OrganizationID]
          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationID,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )
          catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
            )
          )

          catalogueItemPostRequest      = arbitrarySample[smithy.CatalogueItemPostRequest]
          catalogueItemBatchPostRequest = arbitrarySample[smithy.CatalogueItemBatchPostRequest]
          catalogueItemPutRequest       = arbitrarySample[smithy.CatalogueItemPutRequest]
          catalogueItemName             = arbitrarySample[CatalogueItemName]

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.catalogueItemPost[smithy.BadRequest](
                catalogueItemPostRequest,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.catalogueItemBatchPost[smithy.BadRequest](
                catalogueItemBatchPostRequest,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.catalogueItemPut[smithy.BadRequest](
                catalogueItemPutRequest,
                catalogueItemRow.catalogueItemID,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.catalogueItemArchivePut[smithy.BadRequest](
                catalogueItemRow.catalogueItemID,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.catalogueItemVariantArchivePut[smithy.BadRequest](
                catalogueItemRow.catalogueItemID,
                catalogueItemVariantRow.catalogueItemVariantID,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.catalogueItemGet[smithy.BadRequest](
                catalogueItemRow.catalogueItemID,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.catalogueItemSummaryGet[smithy.BadRequest](
                Nil,
                None,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.catalogueItemUniquenessGet[smithy.BadRequest](
                Some(catalogueItemName.value),
                None,
                None,
                Some(accessJwt.accessToken),
              ),
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(8)(StatusCode.BadRequest)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(8)(Some(smithy.BadRequest()))

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowSibling)
        } yield ()
      }

      "fail with an Unauthorized when the access token is missing on every endpoint" in withContext { context =>
        import context.*

        for {
          organizationID   = arbitrarySample[OrganizationID]
          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationID,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )
          catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
            )
          )

          catalogueItemPostRequest      = arbitrarySample[smithy.CatalogueItemPostRequest]
          catalogueItemBatchPostRequest = arbitrarySample[smithy.CatalogueItemBatchPostRequest]
          catalogueItemPutRequest       = arbitrarySample[smithy.CatalogueItemPutRequest]
          catalogueItemName             = arbitrarySample[CatalogueItemName]

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.catalogueItemPost[smithy.Unauthorized](
                catalogueItemPostRequest,
                Some(organizationID),
                None,
              ),
              gatewayClient.catalogueItemBatchPost[smithy.Unauthorized](
                catalogueItemBatchPostRequest,
                Some(organizationID),
                None,
              ),
              gatewayClient.catalogueItemPut[smithy.Unauthorized](
                catalogueItemPutRequest,
                catalogueItemRow.catalogueItemID,
                Some(organizationID),
                None,
              ),
              gatewayClient.catalogueItemArchivePut[smithy.Unauthorized](
                catalogueItemRow.catalogueItemID,
                Some(organizationID),
                None,
              ),
              gatewayClient.catalogueItemVariantArchivePut[smithy.Unauthorized](
                catalogueItemRow.catalogueItemID,
                catalogueItemVariantRow.catalogueItemVariantID,
                Some(organizationID),
                None,
              ),
              gatewayClient.catalogueItemGet[smithy.Unauthorized](
                catalogueItemRow.catalogueItemID,
                Some(organizationID),
                None,
              ),
              gatewayClient.catalogueItemSummaryGet[smithy.Unauthorized](
                Nil,
                Some(organizationID),
                None,
              ),
              gatewayClient.catalogueItemUniquenessGet[smithy.Unauthorized](
                Some(catalogueItemName.value),
                None,
                Some(organizationID),
                None,
              ),
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(8)(StatusCode.Unauthorized)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(8)(Some(smithy.Unauthorized()))

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowSibling)
        } yield ()
      }

      "fail with an Unauthorized when the access token is invalid on every endpoint" in withContext { context =>
        import context.*

        for {
          organizationID   = arbitrarySample[OrganizationID]
          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationID,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )
          catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
            )
          )

          catalogueItemPostRequest      = arbitrarySample[smithy.CatalogueItemPostRequest]
          catalogueItemBatchPostRequest = arbitrarySample[smithy.CatalogueItemBatchPostRequest]
          catalogueItemPutRequest       = arbitrarySample[smithy.CatalogueItemPutRequest]
          catalogueItemName             = arbitrarySample[CatalogueItemName]

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.catalogueItemPost[smithy.Unauthorized](
                catalogueItemPostRequest,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.catalogueItemBatchPost[smithy.Unauthorized](
                catalogueItemBatchPostRequest,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.catalogueItemPut[smithy.Unauthorized](
                catalogueItemPutRequest,
                catalogueItemRow.catalogueItemID,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.catalogueItemArchivePut[smithy.Unauthorized](
                catalogueItemRow.catalogueItemID,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.catalogueItemVariantArchivePut[smithy.Unauthorized](
                catalogueItemRow.catalogueItemID,
                catalogueItemVariantRow.catalogueItemVariantID,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.catalogueItemGet[smithy.Unauthorized](
                catalogueItemRow.catalogueItemID,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.catalogueItemSummaryGet[smithy.Unauthorized](
                Nil,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.catalogueItemUniquenessGet[smithy.Unauthorized](
                Some(catalogueItemName.value),
                None,
                Some(organizationID),
                Some(AccessToken("invalidtoken")),
              ),
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(8)(StatusCode.Unauthorized)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(8)(Some(smithy.Unauthorized()))

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowSibling)
        } yield ()
      }

      "fail with a Forbidden when the user is not in a completed onboard stage on every endpoint" in withContext {
        context =>
          import context.*

          for {
            onboardStageInvalid <- Random
              .shuffle(OnboardStage.values.toList diff OnboardStage.completedStages)
              .map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStageInvalid)
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )
            catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
              )
            )

            catalogueItemPostRequest      = arbitrarySample[smithy.CatalogueItemPostRequest]
            catalogueItemBatchPostRequest = arbitrarySample[smithy.CatalogueItemBatchPostRequest]
            catalogueItemPutRequest       = arbitrarySample[smithy.CatalogueItemPutRequest]
            catalogueItemName             = arbitrarySample[CatalogueItemName]

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.catalogueItemPost[smithy.Forbidden](
                  catalogueItemPostRequest,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemBatchPost[smithy.Forbidden](
                  catalogueItemBatchPostRequest,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemPut[smithy.Forbidden](
                  catalogueItemPutRequest,
                  catalogueItemRow.catalogueItemID,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemArchivePut[smithy.Forbidden](
                  catalogueItemRow.catalogueItemID,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemVariantArchivePut[smithy.Forbidden](
                  catalogueItemRow.catalogueItemID,
                  catalogueItemVariantRow.catalogueItemVariantID,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemGet[smithy.Forbidden](
                  catalogueItemRow.catalogueItemID,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemSummaryGet[smithy.Forbidden](
                  Nil,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemUniquenessGet[smithy.Forbidden](
                  Some(catalogueItemName.value),
                  None,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(8)(StatusCode.Forbidden)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(8)(Some(smithy.Forbidden()))

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
            _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowSibling)
          } yield ()
      }

      "fail with a Forbidden when the organization user role is not allowed on every write endpoint" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRoleInvalid <- Random
              .shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles)
              .map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRoleInvalid)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )
            catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
              )
            )

            catalogueItemPostRequest      = arbitrarySample[smithy.CatalogueItemPostRequest]
            catalogueItemBatchPostRequest = arbitrarySample[smithy.CatalogueItemBatchPostRequest]
            catalogueItemPutRequest       = arbitrarySample[smithy.CatalogueItemPutRequest]

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.catalogueItemPost[smithy.Forbidden](
                  catalogueItemPostRequest,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemBatchPost[smithy.Forbidden](
                  catalogueItemBatchPostRequest,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemPut[smithy.Forbidden](
                  catalogueItemPutRequest,
                  catalogueItemRow.catalogueItemID,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemArchivePut[smithy.Forbidden](
                  catalogueItemRow.catalogueItemID,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemVariantArchivePut[smithy.Forbidden](
                  catalogueItemRow.catalogueItemID,
                  catalogueItemVariantRow.catalogueItemVariantID,
                  Some(organizationUserRow.organizationID),
                  Some(accessJwt.accessToken),
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(5)(StatusCode.Forbidden)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(5)(Some(smithy.Forbidden()))

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
            _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowSibling)
          } yield ()
      }

      "fail with an InternalServerError when the user details do not exist on every endpoint" in withContext {
        context =>
          import context.*

          for {
            userID           = arbitrarySample[UserID]
            organizationID   = arbitrarySample[OrganizationID]
            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationID,
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )
            catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
              )
            )

            catalogueItemPostRequest      = arbitrarySample[smithy.CatalogueItemPostRequest]
            catalogueItemBatchPostRequest = arbitrarySample[smithy.CatalogueItemBatchPostRequest]
            catalogueItemPutRequest       = arbitrarySample[smithy.CatalogueItemPutRequest]
            catalogueItemName             = arbitrarySample[CatalogueItemName]

            accessJwt <- jwtService.generateAccessToken(userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.catalogueItemPost[smithy.InternalServerError](
                  catalogueItemPostRequest,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemBatchPost[smithy.InternalServerError](
                  catalogueItemBatchPostRequest,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemPut[smithy.InternalServerError](
                  catalogueItemPutRequest,
                  catalogueItemRow.catalogueItemID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemArchivePut[smithy.InternalServerError](
                  catalogueItemRow.catalogueItemID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemVariantArchivePut[smithy.InternalServerError](
                  catalogueItemRow.catalogueItemID,
                  catalogueItemVariantRow.catalogueItemVariantID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemGet[smithy.InternalServerError](
                  catalogueItemRow.catalogueItemID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemSummaryGet[smithy.InternalServerError](
                  Nil,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemUniquenessGet[smithy.InternalServerError](
                  Some(catalogueItemName.value),
                  None,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(8)(StatusCode.InternalServerError)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(8)(Some(smithy.InternalServerError()))

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
            _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowSibling)
          } yield ()
      }

      "fail with an InternalServerError when the user is not a member of the organization on every endpoint" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            organizationID   = arbitrarySample[OrganizationID]
            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationID,
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )
            catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
              )
            )

            catalogueItemPostRequest      = arbitrarySample[smithy.CatalogueItemPostRequest]
            catalogueItemBatchPostRequest = arbitrarySample[smithy.CatalogueItemBatchPostRequest]
            catalogueItemPutRequest       = arbitrarySample[smithy.CatalogueItemPutRequest]
            catalogueItemName             = arbitrarySample[CatalogueItemName]

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.catalogueItemPost[smithy.InternalServerError](
                  catalogueItemPostRequest,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemBatchPost[smithy.InternalServerError](
                  catalogueItemBatchPostRequest,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemPut[smithy.InternalServerError](
                  catalogueItemPutRequest,
                  catalogueItemRow.catalogueItemID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemArchivePut[smithy.InternalServerError](
                  catalogueItemRow.catalogueItemID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemVariantArchivePut[smithy.InternalServerError](
                  catalogueItemRow.catalogueItemID,
                  catalogueItemVariantRow.catalogueItemVariantID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemGet[smithy.InternalServerError](
                  catalogueItemRow.catalogueItemID,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemSummaryGet[smithy.InternalServerError](
                  Nil,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueItemUniquenessGet[smithy.InternalServerError](
                  Some(catalogueItemName.value),
                  None,
                  Some(organizationID),
                  Some(accessJwt.accessToken),
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(8)(StatusCode.InternalServerError)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(8)(Some(smithy.InternalServerError()))

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
            _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowSibling)
          } yield ()
      }
    }
  }
}
