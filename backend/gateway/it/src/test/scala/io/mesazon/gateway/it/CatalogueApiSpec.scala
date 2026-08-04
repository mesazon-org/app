package io.mesazon.gateway.it

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.it.client.GatewayClient.given
import io.mesazon.gateway.it.harness.GatewayAcceptanceTest
import io.mesazon.gateway.repository.domain.{CatalogueItemRow, OrganizationUserRow, UserDetailsRow}
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.{CatalogueSmithyArbitraries, RepositoryArbitraries}
import io.scalaland.chimney.dsl.*
import org.scalatest.DoNotDiscover
import sttp.model.*
import zio.*

@DoNotDiscover
class CatalogueApiSpec extends GatewayAcceptanceTest, CatalogueSmithyArbitraries, RepositoryArbitraries {

  "Catalogue Service API" when {
    "POST /insert/catalogue-item" should {
      "successfully insert an active catalogue item" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val insertCatalogueItemPostRequest = arbitrarySample[InsertCatalogueItemPostRequest]
        val accessJwt                      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCatalogueItemPostResponse = gatewayClient
          .insertCatalogueItemPost[smithy.InternalServerError](
            insertCatalogueItemPostRequest.transformInto[smithy.InsertCatalogueItemPostRequest],
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemPostResponse.code shouldBe StatusCode.NoContent

        val catalogueItemRowsAll =
          postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue
        val catalogueItemRowInserted = catalogueItemRowsAll.head

        catalogueItemRowsAll shouldBe List(
          CatalogueItemRow(
            organizationID = organizationUserRow.organizationID,
            catalogueItemID = catalogueItemRowInserted.catalogueItemID,
            name = insertCatalogueItemPostRequest.name,
            unit = insertCatalogueItemPostRequest.unit,
            price = insertCatalogueItemPostRequest.price,
            imageAsset = None,
            status = CatalogueItemStatus.Active,
            createdAt = catalogueItemRowInserted.createdAt,
            updatedAt = catalogueItemRowInserted.updatedAt,
          )
        )
        catalogueItemRowInserted.createdAt.value shouldBe catalogueItemRowInserted.updatedAt.value
      }

      "fail with a ValidationError and leave the catalogue empty when the name is invalid" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val insertCatalogueItemPostRequestSmithy =
          arbitrarySample[smithy.InsertCatalogueItemPostRequest].copy(name = "")
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCatalogueItemPostResponse = gatewayClient
          .insertCatalogueItemPost[smithy.ValidationError](
            insertCatalogueItemPostRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemPostResponse.code shouldBe StatusCode.BadRequest
        insertCatalogueItemPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("name"))

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val insertCatalogueItemPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemPostRequest]
        val accessJwt                            = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCatalogueItemPostResponse = gatewayClient
          .insertCatalogueItemPost[smithy.BadRequest](
            insertCatalogueItemPostRequestSmithy,
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemPostResponse.code shouldBe StatusCode.BadRequest
        insertCatalogueItemPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val insertCatalogueItemPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemPostRequest]
        val organizationID                       = arbitrarySample[OrganizationID]

        val insertCatalogueItemPostResponse = gatewayClient
          .insertCatalogueItemPost[smithy.Unauthorized](
            insertCatalogueItemPostRequestSmithy,
            Some(organizationID),
            None,
          )
          .zioValue

        insertCatalogueItemPostResponse.code shouldBe StatusCode.Unauthorized
        insertCatalogueItemPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val insertCatalogueItemPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemPostRequest]
        val organizationID                       = arbitrarySample[OrganizationID]

        val insertCatalogueItemPostResponse = gatewayClient
          .insertCatalogueItemPost[smithy.Unauthorized](
            insertCatalogueItemPostRequestSmithy,
            Some(organizationID),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        insertCatalogueItemPostResponse.code shouldBe StatusCode.Unauthorized
        insertCatalogueItemPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val insertCatalogueItemPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemPostRequest]
        val accessJwt                            = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCatalogueItemPostResponse = gatewayClient
          .insertCatalogueItemPost[smithy.Forbidden](
            insertCatalogueItemPostRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemPostResponse.code shouldBe StatusCode.Forbidden
        insertCatalogueItemPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleInvalid =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleInvalid,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val insertCatalogueItemPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemPostRequest]
        val accessJwt                            = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCatalogueItemPostResponse = gatewayClient
          .insertCatalogueItemPost[smithy.Forbidden](
            insertCatalogueItemPostRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemPostResponse.code shouldBe StatusCode.Forbidden
        insertCatalogueItemPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Conflict when the catalogue item name already exists in the organization" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          status = CatalogueItemStatus.Active,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient.executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue

        val insertCatalogueItemPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemPostRequest].copy(
          name = catalogueItemRow.name.value
        )
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCatalogueItemPostResponse = gatewayClient
          .insertCatalogueItemPost[smithy.Conflict](
            insertCatalogueItemPostRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemPostResponse.code shouldBe StatusCode.Conflict
        insertCatalogueItemPostResponse.body.left.value shouldBe smithy.Conflict(message =
          "A catalogue item with the given name already exists in this organization"
        )

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID                               = arbitrarySample[UserID]
        val insertCatalogueItemPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemPostRequest]
        val organizationID                       = arbitrarySample[OrganizationID]
        val accessJwt                            = jwtService.generateAccessToken(userID).zioValue

        val insertCatalogueItemPostResponse = gatewayClient
          .insertCatalogueItemPost[smithy.InternalServerError](
            insertCatalogueItemPostRequestSmithy,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemPostResponse.code shouldBe StatusCode.InternalServerError
        insertCatalogueItemPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val insertCatalogueItemPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemPostRequest]
        val organizationID                       = arbitrarySample[OrganizationID]
        val accessJwt                            = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCatalogueItemPostResponse = gatewayClient
          .insertCatalogueItemPost[smithy.InternalServerError](
            insertCatalogueItemPostRequestSmithy,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemPostResponse.code shouldBe StatusCode.InternalServerError
        insertCatalogueItemPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }
    }

    "POST /insert/catalogue-items" should {
      "successfully insert every catalogue item atomically" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemName      = arbitrarySample[CatalogueItemName]
        val catalogueItemNameOther = CatalogueItemName.assume(s"${catalogueItemName.value.take(253)}-N")

        catalogueItemNameOther shouldNot equal(catalogueItemName)

        val insertCatalogueItemsPostRequestSmithy = smithy.InsertCatalogueItemsPostRequest(
          List(
            arbitrarySample[smithy.InsertCatalogueItemPostRequest].copy(name = catalogueItemName.value),
            arbitrarySample[smithy.InsertCatalogueItemPostRequest].copy(name = catalogueItemNameOther.value),
          )
        )
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCatalogueItemsPostResponse = gatewayClient
          .insertCatalogueItemsPost[smithy.InternalServerError](
            insertCatalogueItemsPostRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemsPostResponse.code shouldBe StatusCode.NoContent

        val catalogueItemRowsAll =
          postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue

        catalogueItemRowsAll should have size insertCatalogueItemsPostRequestSmithy.catalogueItems.size
        catalogueItemRowsAll.map(catalogueItemRow =>
          (
            catalogueItemRow.name.value,
            catalogueItemRow.unit.value,
            catalogueItemRow.price.map(price =>
              smithy.CatalogueItemPriceRequest(price.value.amount.value, price.value.currency.value)
            ),
          )
        ) should contain theSameElementsAs insertCatalogueItemsPostRequestSmithy.catalogueItems.map(catalogueItem =>
          (catalogueItem.name, catalogueItem.unit, catalogueItem.price)
        )
        catalogueItemRowsAll.map(_.organizationID) should contain only organizationUserRow.organizationID
        catalogueItemRowsAll.map(_.imageAsset) should contain only None
        catalogueItemRowsAll.map(_.status) should contain only CatalogueItemStatus.Active
        catalogueItemRowsAll.foreach(catalogueItemRow =>
          catalogueItemRow.createdAt.value shouldBe catalogueItemRow.updatedAt.value
        )
      }

      "successfully insert an empty batch with no side effect" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val insertCatalogueItemsPostRequestSmithy = smithy.InsertCatalogueItemsPostRequest(Nil)
        val accessJwt                             = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCatalogueItemsPostResponse = gatewayClient
          .insertCatalogueItemsPost[smithy.InternalServerError](
            insertCatalogueItemsPostRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemsPostResponse.code shouldBe StatusCode.NoContent

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a ValidationError and insert no catalogue items when one batch item is invalid" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val insertCatalogueItemsPostRequestSmithy = smithy.InsertCatalogueItemsPostRequest(
            List(
              arbitrarySample[smithy.InsertCatalogueItemPostRequest],
              arbitrarySample[smithy.InsertCatalogueItemPostRequest].copy(unit = ""),
            )
          )
          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val insertCatalogueItemsPostResponse = gatewayClient
            .insertCatalogueItemsPost[smithy.ValidationError](
              insertCatalogueItemsPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          insertCatalogueItemsPostResponse.code shouldBe StatusCode.BadRequest
          insertCatalogueItemsPostResponse.body.left.value shouldBe
            smithy.ValidationError(fields = List("catalogueItem"))

          postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val insertCatalogueItemsPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemsPostRequest]
        val accessJwt                             = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCatalogueItemsPostResponse = gatewayClient
          .insertCatalogueItemsPost[smithy.BadRequest](
            insertCatalogueItemsPostRequestSmithy,
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemsPostResponse.code shouldBe StatusCode.BadRequest
        insertCatalogueItemsPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val insertCatalogueItemsPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemsPostRequest]
        val organizationID                        = arbitrarySample[OrganizationID]

        val insertCatalogueItemsPostResponse = gatewayClient
          .insertCatalogueItemsPost[smithy.Unauthorized](
            insertCatalogueItemsPostRequestSmithy,
            Some(organizationID),
            None,
          )
          .zioValue

        insertCatalogueItemsPostResponse.code shouldBe StatusCode.Unauthorized
        insertCatalogueItemsPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val insertCatalogueItemsPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemsPostRequest]
        val organizationID                        = arbitrarySample[OrganizationID]

        val insertCatalogueItemsPostResponse = gatewayClient
          .insertCatalogueItemsPost[smithy.Unauthorized](
            insertCatalogueItemsPostRequestSmithy,
            Some(organizationID),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        insertCatalogueItemsPostResponse.code shouldBe StatusCode.Unauthorized
        insertCatalogueItemsPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val insertCatalogueItemsPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemsPostRequest]
        val accessJwt                             = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCatalogueItemsPostResponse = gatewayClient
          .insertCatalogueItemsPost[smithy.Forbidden](
            insertCatalogueItemsPostRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemsPostResponse.code shouldBe StatusCode.Forbidden
        insertCatalogueItemsPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleInvalid =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleInvalid,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val insertCatalogueItemsPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemsPostRequest]
        val accessJwt                             = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCatalogueItemsPostResponse = gatewayClient
          .insertCatalogueItemsPost[smithy.Forbidden](
            insertCatalogueItemsPostRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemsPostResponse.code shouldBe StatusCode.Forbidden
        insertCatalogueItemsPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Conflict when a catalogue item in the batch has a name that already exists, rolling back the whole batch" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )
          val catalogueItemRowExisting = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            status = CatalogueItemStatus.Active,
          )
          val catalogueItemNameNew =
            CatalogueItemName.assume(s"${catalogueItemRowExisting.name.value.take(253)}-N")

          catalogueItemNameNew shouldNot equal(catalogueItemRowExisting.name)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
          postgresClient
            .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRowExisting))
            .zioValue

          val insertCatalogueItemsPostRequestSmithy = smithy.InsertCatalogueItemsPostRequest(
            List(
              arbitrarySample[smithy.InsertCatalogueItemPostRequest].copy(name = catalogueItemNameNew.value),
              arbitrarySample[smithy.InsertCatalogueItemPostRequest].copy(name = catalogueItemRowExisting.name.value),
            )
          )
          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val insertCatalogueItemsPostResponse = gatewayClient
            .insertCatalogueItemsPost[smithy.Conflict](
              insertCatalogueItemsPostRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          insertCatalogueItemsPostResponse.code shouldBe StatusCode.Conflict
          insertCatalogueItemsPostResponse.body.left.value shouldBe smithy.Conflict(message =
            "A catalogue item with the given name already exists in this organization"
          )

          postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
            List(catalogueItemRowExisting)
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID                                = arbitrarySample[UserID]
        val insertCatalogueItemsPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemsPostRequest]
        val organizationID                        = arbitrarySample[OrganizationID]
        val accessJwt                             = jwtService.generateAccessToken(userID).zioValue

        val insertCatalogueItemsPostResponse = gatewayClient
          .insertCatalogueItemsPost[smithy.InternalServerError](
            insertCatalogueItemsPostRequestSmithy,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemsPostResponse.code shouldBe StatusCode.InternalServerError
        insertCatalogueItemsPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val insertCatalogueItemsPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemsPostRequest]
        val organizationID                        = arbitrarySample[OrganizationID]
        val accessJwt                             = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val insertCatalogueItemsPostResponse = gatewayClient
          .insertCatalogueItemsPost[smithy.InternalServerError](
            insertCatalogueItemsPostRequestSmithy,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        insertCatalogueItemsPostResponse.code shouldBe StatusCode.InternalServerError
        insertCatalogueItemsPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

    }

    "PUT /update/catalogue-item" should {
      "successfully update an active catalogue item while retaining absent fields" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          status = CatalogueItemStatus.Active,
        )
        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient.executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue

        val catalogueItemNameUpdate             = arbitrarySample[CatalogueItemName]
        val catalogueItemUnitUpdate             = arbitrarySample[CatalogueItemUnit]
        val updateCatalogueItemPutRequestSmithy = smithy.UpdateCatalogueItemPutRequest(
          catalogueItemID = catalogueItemRow.catalogueItemID.value,
          name = Some(catalogueItemNameUpdate.value),
          unit = Some(catalogueItemUnitUpdate.value),
          price = None,
        )
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val updateCatalogueItemPutResponse = gatewayClient
          .updateCatalogueItemPut[smithy.InternalServerError](
            updateCatalogueItemPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        updateCatalogueItemPutResponse.code shouldBe StatusCode.NoContent

        val catalogueItemRowUpdated = postgresClient
          .executeQuery(
            catalogueItemQueries.getCatalogueItemRow(
              catalogueItemRow.organizationID,
              catalogueItemRow.catalogueItemID,
            )
          )
          .zioValue
          .value

        catalogueItemRowUpdated shouldBe CatalogueItemRow(
          organizationID = catalogueItemRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          name = catalogueItemNameUpdate,
          unit = catalogueItemUnitUpdate,
          price = catalogueItemRow.price,
          imageAsset = catalogueItemRow.imageAsset,
          status = catalogueItemRow.status,
          createdAt = catalogueItemRow.createdAt,
          updatedAt = catalogueItemRowUpdated.updatedAt,
        )
      }

      "silently succeed with no side effect when no active catalogue item matches the id" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemIDMissing = arbitrarySample[CatalogueItemID]

        catalogueItemIDMissing shouldNot equal(catalogueItemRow.catalogueItemID)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient.executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue

        val updateCatalogueItemPutRequestSmithy =
          arbitrarySample[smithy.UpdateCatalogueItemPutRequest].copy(
            catalogueItemID = catalogueItemIDMissing.value
          )
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val updateCatalogueItemPutResponse = gatewayClient
          .updateCatalogueItemPut[smithy.InternalServerError](
            updateCatalogueItemPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        updateCatalogueItemPutResponse.code shouldBe StatusCode.NoContent

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
      }

      "silently succeed with no side effect when the catalogue item belongs to another organization" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )
          val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
            status = CatalogueItemStatus.Active
          )

          catalogueItemRowForeign.organizationID shouldNot equal(organizationUserRow.organizationID)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
          postgresClient
            .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRowForeign))
            .zioValue

          val updateCatalogueItemPutRequestSmithy =
            arbitrarySample[smithy.UpdateCatalogueItemPutRequest].copy(
              catalogueItemID = catalogueItemRowForeign.catalogueItemID.value
            )
          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val updateCatalogueItemPutResponse = gatewayClient
            .updateCatalogueItemPut[smithy.InternalServerError](
              updateCatalogueItemPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          updateCatalogueItemPutResponse.code shouldBe StatusCode.NoContent

          postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
            List(catalogueItemRowForeign)
      }

      "silently succeed with no side effect when the catalogue item is already archived" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          status = CatalogueItemStatus.Archived,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRowArchived))
          .zioValue

        val updateCatalogueItemPutRequestSmithy =
          arbitrarySample[smithy.UpdateCatalogueItemPutRequest].copy(
            catalogueItemID = catalogueItemRowArchived.catalogueItemID.value
          )
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val updateCatalogueItemPutResponse = gatewayClient
          .updateCatalogueItemPut[smithy.InternalServerError](
            updateCatalogueItemPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        updateCatalogueItemPutResponse.code shouldBe StatusCode.NoContent

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowArchived)
      }

      "fail with a ValidationError and leave the catalogue item unchanged when the unit is invalid" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )
          val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            status = CatalogueItemStatus.Active,
          )

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
          postgresClient.executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue

          val updateCatalogueItemPutRequestSmithy =
            smithy.UpdateCatalogueItemPutRequest(catalogueItemRow.catalogueItemID.value, None, Some(""), None)
          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val updateCatalogueItemPutResponse = gatewayClient
            .updateCatalogueItemPut[smithy.ValidationError](
              updateCatalogueItemPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          updateCatalogueItemPutResponse.code shouldBe StatusCode.BadRequest
          updateCatalogueItemPutResponse.body.left.value shouldBe smithy.ValidationError(fields = List("unit"))

          postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
            List(catalogueItemRow)
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val updateCatalogueItemPutRequestSmithy = arbitrarySample[smithy.UpdateCatalogueItemPutRequest]
        val accessJwt                           = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val updateCatalogueItemPutResponse = gatewayClient
          .updateCatalogueItemPut[smithy.BadRequest](
            updateCatalogueItemPutRequestSmithy,
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        updateCatalogueItemPutResponse.code shouldBe StatusCode.BadRequest
        updateCatalogueItemPutResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val updateCatalogueItemPutRequestSmithy = arbitrarySample[smithy.UpdateCatalogueItemPutRequest]
        val organizationID                      = arbitrarySample[OrganizationID]

        val updateCatalogueItemPutResponse = gatewayClient
          .updateCatalogueItemPut[smithy.Unauthorized](
            updateCatalogueItemPutRequestSmithy,
            Some(organizationID),
            None,
          )
          .zioValue

        updateCatalogueItemPutResponse.code shouldBe StatusCode.Unauthorized
        updateCatalogueItemPutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val updateCatalogueItemPutRequestSmithy = arbitrarySample[smithy.UpdateCatalogueItemPutRequest]
        val organizationID                      = arbitrarySample[OrganizationID]

        val updateCatalogueItemPutResponse = gatewayClient
          .updateCatalogueItemPut[smithy.Unauthorized](
            updateCatalogueItemPutRequestSmithy,
            Some(organizationID),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        updateCatalogueItemPutResponse.code shouldBe StatusCode.Unauthorized
        updateCatalogueItemPutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val updateCatalogueItemPutRequestSmithy = arbitrarySample[smithy.UpdateCatalogueItemPutRequest]
        val accessJwt                           = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val updateCatalogueItemPutResponse = gatewayClient
          .updateCatalogueItemPut[smithy.Forbidden](
            updateCatalogueItemPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        updateCatalogueItemPutResponse.code shouldBe StatusCode.Forbidden
        updateCatalogueItemPutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleInvalid =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleInvalid,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val updateCatalogueItemPutRequestSmithy = arbitrarySample[smithy.UpdateCatalogueItemPutRequest]
        val accessJwt                           = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val updateCatalogueItemPutResponse = gatewayClient
          .updateCatalogueItemPut[smithy.Forbidden](
            updateCatalogueItemPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        updateCatalogueItemPutResponse.code shouldBe StatusCode.Forbidden
        updateCatalogueItemPutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Conflict when the updated name already belongs to an active catalogue item" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )
          val catalogueItemNameExisting = arbitrarySample[CatalogueItemName]
          val catalogueItemName         =
            CatalogueItemName.assume(s"${catalogueItemNameExisting.value.take(253)}-N")
          val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = catalogueItemName,
            status = CatalogueItemStatus.Active,
          )
          val catalogueItemRowExisting = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            name = catalogueItemNameExisting,
            status = CatalogueItemStatus.Active,
          )

          catalogueItemRow.name shouldNot equal(catalogueItemRowExisting.name)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
          postgresClient
            .executeQuery(
              catalogueItemQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowExisting))
            )
            .zioValue

          val updateCatalogueItemPutRequestSmithy = smithy.UpdateCatalogueItemPutRequest(
            catalogueItemID = catalogueItemRow.catalogueItemID.value,
            name = Some(catalogueItemRowExisting.name.value),
            unit = None,
            price = None,
          )
          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val updateCatalogueItemPutResponse = gatewayClient
            .updateCatalogueItemPut[smithy.Conflict](
              updateCatalogueItemPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          updateCatalogueItemPutResponse.code shouldBe StatusCode.Conflict
          updateCatalogueItemPutResponse.body.left.value shouldBe smithy.Conflict(message =
            "A catalogue item with the given name already exists in this organization"
          )

          postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue should
            contain theSameElementsAs List(catalogueItemRow, catalogueItemRowExisting)
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID                              = arbitrarySample[UserID]
        val updateCatalogueItemPutRequestSmithy = arbitrarySample[smithy.UpdateCatalogueItemPutRequest]
        val organizationID                      = arbitrarySample[OrganizationID]
        val accessJwt                           = jwtService.generateAccessToken(userID).zioValue

        val updateCatalogueItemPutResponse = gatewayClient
          .updateCatalogueItemPut[smithy.InternalServerError](
            updateCatalogueItemPutRequestSmithy,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        updateCatalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
        updateCatalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val updateCatalogueItemPutRequestSmithy = arbitrarySample[smithy.UpdateCatalogueItemPutRequest]
        val organizationID                      = arbitrarySample[OrganizationID]
        val accessJwt                           = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val updateCatalogueItemPutResponse = gatewayClient
          .updateCatalogueItemPut[smithy.InternalServerError](
            updateCatalogueItemPutRequestSmithy,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        updateCatalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
        updateCatalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

    }

    "PUT /archive/catalogue-item" should {
      "successfully archive an active catalogue item" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          status = CatalogueItemStatus.Active,
        )
        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient.executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue

        val archiveCatalogueItemPutRequestSmithy =
          smithy.ArchiveCatalogueItemPutRequest(catalogueItemRow.catalogueItemID.value)
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val archiveCatalogueItemPutResponse = gatewayClient
          .archiveCatalogueItemPut[smithy.InternalServerError](
            archiveCatalogueItemPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        archiveCatalogueItemPutResponse.code shouldBe StatusCode.NoContent

        val catalogueItemRowArchived = postgresClient
          .executeQuery(
            catalogueItemQueries.getCatalogueItemRow(
              catalogueItemRow.organizationID,
              catalogueItemRow.catalogueItemID,
            )
          )
          .zioValue
          .value

        catalogueItemRowArchived shouldBe catalogueItemRow.copy(
          status = CatalogueItemStatus.Archived,
          updatedAt = catalogueItemRowArchived.updatedAt,
        )
      }

      "silently succeed with no side effect when no catalogue item matches the id" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemIDMissing = arbitrarySample[CatalogueItemID]

        catalogueItemIDMissing shouldNot equal(catalogueItemRow.catalogueItemID)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient.executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue

        val archiveCatalogueItemPutRequestSmithy =
          smithy.ArchiveCatalogueItemPutRequest(catalogueItemIDMissing.value)
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val archiveCatalogueItemPutResponse = gatewayClient
          .archiveCatalogueItemPut[smithy.InternalServerError](
            archiveCatalogueItemPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        archiveCatalogueItemPutResponse.code shouldBe StatusCode.NoContent

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
      }

      "silently succeed with no side effect when the catalogue item belongs to another organization" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )
          val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
            status = CatalogueItemStatus.Active
          )

          catalogueItemRowForeign.organizationID shouldNot equal(organizationUserRow.organizationID)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
          postgresClient
            .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRowForeign))
            .zioValue

          val archiveCatalogueItemPutRequestSmithy =
            smithy.ArchiveCatalogueItemPutRequest(catalogueItemRowForeign.catalogueItemID.value)
          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val archiveCatalogueItemPutResponse = gatewayClient
            .archiveCatalogueItemPut[smithy.InternalServerError](
              archiveCatalogueItemPutRequestSmithy,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          archiveCatalogueItemPutResponse.code shouldBe StatusCode.NoContent

          postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
            List(catalogueItemRowForeign)
      }

      "silently succeed with no side effect when the catalogue item is already archived" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          status = CatalogueItemStatus.Archived,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRowArchived))
          .zioValue

        val archiveCatalogueItemPutRequestSmithy =
          smithy.ArchiveCatalogueItemPutRequest(catalogueItemRowArchived.catalogueItemID.value)
        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val archiveCatalogueItemPutResponse = gatewayClient
          .archiveCatalogueItemPut[smithy.InternalServerError](
            archiveCatalogueItemPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        archiveCatalogueItemPutResponse.code shouldBe StatusCode.NoContent

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowArchived)
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val archiveCatalogueItemPutRequestSmithy = arbitrarySample[smithy.ArchiveCatalogueItemPutRequest]
        val accessJwt                            = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val archiveCatalogueItemPutResponse = gatewayClient
          .archiveCatalogueItemPut[smithy.BadRequest](
            archiveCatalogueItemPutRequestSmithy,
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        archiveCatalogueItemPutResponse.code shouldBe StatusCode.BadRequest
        archiveCatalogueItemPutResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val archiveCatalogueItemPutRequestSmithy = arbitrarySample[smithy.ArchiveCatalogueItemPutRequest]
        val organizationID                       = arbitrarySample[OrganizationID]

        val archiveCatalogueItemPutResponse = gatewayClient
          .archiveCatalogueItemPut[smithy.Unauthorized](
            archiveCatalogueItemPutRequestSmithy,
            Some(organizationID),
            None,
          )
          .zioValue

        archiveCatalogueItemPutResponse.code shouldBe StatusCode.Unauthorized
        archiveCatalogueItemPutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val archiveCatalogueItemPutRequestSmithy = arbitrarySample[smithy.ArchiveCatalogueItemPutRequest]
        val organizationID                       = arbitrarySample[OrganizationID]

        val archiveCatalogueItemPutResponse = gatewayClient
          .archiveCatalogueItemPut[smithy.Unauthorized](
            archiveCatalogueItemPutRequestSmithy,
            Some(organizationID),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        archiveCatalogueItemPutResponse.code shouldBe StatusCode.Unauthorized
        archiveCatalogueItemPutResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val archiveCatalogueItemPutRequestSmithy = arbitrarySample[smithy.ArchiveCatalogueItemPutRequest]
        val accessJwt                            = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val archiveCatalogueItemPutResponse = gatewayClient
          .archiveCatalogueItemPut[smithy.Forbidden](
            archiveCatalogueItemPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        archiveCatalogueItemPutResponse.code shouldBe StatusCode.Forbidden
        archiveCatalogueItemPutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a Forbidden when the organization user role is not allowed" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleInvalid =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleInvalid,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val archiveCatalogueItemPutRequestSmithy = arbitrarySample[smithy.ArchiveCatalogueItemPutRequest]
        val accessJwt                            = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val archiveCatalogueItemPutResponse = gatewayClient
          .archiveCatalogueItemPut[smithy.Forbidden](
            archiveCatalogueItemPutRequestSmithy,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        archiveCatalogueItemPutResponse.code shouldBe StatusCode.Forbidden
        archiveCatalogueItemPutResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID                               = arbitrarySample[UserID]
        val archiveCatalogueItemPutRequestSmithy = arbitrarySample[smithy.ArchiveCatalogueItemPutRequest]
        val organizationID                       = arbitrarySample[OrganizationID]
        val accessJwt                            = jwtService.generateAccessToken(userID).zioValue

        val archiveCatalogueItemPutResponse = gatewayClient
          .archiveCatalogueItemPut[smithy.InternalServerError](
            archiveCatalogueItemPutRequestSmithy,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        archiveCatalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
        archiveCatalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val archiveCatalogueItemPutRequestSmithy = arbitrarySample[smithy.ArchiveCatalogueItemPutRequest]
        val organizationID                       = arbitrarySample[OrganizationID]
        val accessJwt                            = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val archiveCatalogueItemPutResponse = gatewayClient
          .archiveCatalogueItemPut[smithy.InternalServerError](
            archiveCatalogueItemPutRequestSmithy,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        archiveCatalogueItemPutResponse.code shouldBe StatusCode.InternalServerError
        archiveCatalogueItemPutResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }
    }

    "GET /get/catalogue-item/{catalogueItemID}" should {
      "successfully return an active catalogue item including its persisted image fields" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          status = CatalogueItemStatus.Active,
          imageAsset = Some(arbitrarySample[CatalogueItemImageAsset]),
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient.executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val getCatalogueItemGetResponse = gatewayClient
          .getCatalogueItemGet[smithy.InternalServerError](
            catalogueItemRow.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        getCatalogueItemGetResponse.code shouldBe StatusCode.Ok

        val getCatalogueItemGetResponseBody = getCatalogueItemGetResponse.body.value

        getCatalogueItemGetResponseBody.catalogueItemID shouldBe catalogueItemRow.catalogueItemID.value
        getCatalogueItemGetResponseBody.name shouldBe catalogueItemRow.name.value
        getCatalogueItemGetResponseBody.unit shouldBe catalogueItemRow.unit.value
        getCatalogueItemGetResponseBody.price shouldBe catalogueItemRow.price.map(price =>
          smithy.CatalogueItemPriceRequest(price.value.amount.value, price.value.currency.value)
        )
        // Presigned URLs are generated on the fly (signature + expiry query params) rather than the raw
        // bucket key, so only presence is asserted here; `S3ClientOrganizationMediaSpec` proves the URLs
        // themselves actually serve the uploaded bytes.
        getCatalogueItemGetResponseBody.imageNormalizedUrl shouldBe defined
      }

      "successfully return an archived catalogue item by id" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          status = CatalogueItemStatus.Archived,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRowArchived))
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val getCatalogueItemGetResponse = gatewayClient
          .getCatalogueItemGet[smithy.InternalServerError](
            catalogueItemRowArchived.catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        getCatalogueItemGetResponse.code shouldBe StatusCode.Ok
        getCatalogueItemGetResponse.body.value.catalogueItemID shouldBe catalogueItemRowArchived.catalogueItemID.value
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val getCatalogueItemGetResponse = gatewayClient
          .getCatalogueItemGet[smithy.BadRequest](
            catalogueItemID,
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        getCatalogueItemGetResponse.code shouldBe StatusCode.BadRequest
        getCatalogueItemGetResponse.body.left.value shouldBe smithy.BadRequest()
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val organizationID  = arbitrarySample[OrganizationID]

        val getCatalogueItemGetResponse = gatewayClient
          .getCatalogueItemGet[smithy.Unauthorized](
            catalogueItemID,
            Some(organizationID),
            None,
          )
          .zioValue

        getCatalogueItemGetResponse.code shouldBe StatusCode.Unauthorized
        getCatalogueItemGetResponse.body.left.value shouldBe smithy.Unauthorized()
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val organizationID  = arbitrarySample[OrganizationID]

        val getCatalogueItemGetResponse = gatewayClient
          .getCatalogueItemGet[smithy.Unauthorized](
            catalogueItemID,
            Some(organizationID),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        getCatalogueItemGetResponse.code shouldBe StatusCode.Unauthorized
        getCatalogueItemGetResponse.body.left.value shouldBe smithy.Unauthorized()
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val getCatalogueItemGetResponse = gatewayClient
          .getCatalogueItemGet[smithy.Forbidden](
            catalogueItemID,
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        getCatalogueItemGetResponse.code shouldBe StatusCode.Forbidden
        getCatalogueItemGetResponse.body.left.value shouldBe smithy.Forbidden()
      }

      "fail with an InternalServerError when the catalogue item does not exist in the organization" in withContext {
        context =>
          import context.*

          val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
          val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRole,
          )
          val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow]

          catalogueItemRowForeign.organizationID shouldNot equal(organizationUserRow.organizationID)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
          postgresClient
            .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRowForeign))
            .zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val getCatalogueItemGetResponse = gatewayClient
            .getCatalogueItemGet[smithy.InternalServerError](
              catalogueItemRowForeign.catalogueItemID,
              Some(organizationUserRow.organizationID),
              Some(accessJwt.accessToken),
            )
            .zioValue

          getCatalogueItemGetResponse.code shouldBe StatusCode.InternalServerError
          getCatalogueItemGetResponse.body.left.value shouldBe smithy.InternalServerError()
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID          = arbitrarySample[UserID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val organizationID  = arbitrarySample[OrganizationID]
        val accessJwt       = jwtService.generateAccessToken(userID).zioValue

        val getCatalogueItemGetResponse = gatewayClient
          .getCatalogueItemGet[smithy.InternalServerError](
            catalogueItemID,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        getCatalogueItemGetResponse.code shouldBe StatusCode.InternalServerError
        getCatalogueItemGetResponse.body.left.value shouldBe smithy.InternalServerError()
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val organizationID  = arbitrarySample[OrganizationID]
        val accessJwt       = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val getCatalogueItemGetResponse = gatewayClient
          .getCatalogueItemGet[smithy.InternalServerError](
            catalogueItemID,
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        getCatalogueItemGetResponse.code shouldBe StatusCode.InternalServerError
        getCatalogueItemGetResponse.body.left.value shouldBe smithy.InternalServerError()
      }

    }

    "GET /get/catalogue-items" should {
      "successfully return only active catalogue items for the organization" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )
        val catalogueItemRowActive = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          status = CatalogueItemStatus.Active,
          imageAsset = Some(arbitrarySample[CatalogueItemImageAsset]),
        )
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          status = CatalogueItemStatus.Archived,
        )
        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
          status = CatalogueItemStatus.Active
        )

        catalogueItemRowForeign.organizationID shouldNot equal(organizationUserRow.organizationID)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueItemQueries.insertCatalogueItemRows(
              List(catalogueItemRowActive, catalogueItemRowArchived, catalogueItemRowForeign)
            )
          )
          .zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val getCatalogueItemsGetResponse = gatewayClient
          .getCatalogueItemsGet[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        getCatalogueItemsGetResponse.code shouldBe StatusCode.Ok

        val getCatalogueItemsGetResponseBody = getCatalogueItemsGetResponse.body.value

        getCatalogueItemsGetResponseBody.catalogueItems should have size 1

        val getCatalogueItem = getCatalogueItemsGetResponseBody.catalogueItems.head

        getCatalogueItem.catalogueItemID shouldBe catalogueItemRowActive.catalogueItemID.value
        getCatalogueItem.name shouldBe catalogueItemRowActive.name.value
        getCatalogueItem.status shouldBe smithy.CatalogueItemStatus.ACTIVE
        getCatalogueItem.imageNormalizedUrl shouldBe defined
      }

      "successfully return an empty catalogue when the organization has no active items" in withContext { context =>
        import context.*

        val onboardStage         = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val getCatalogueItemsGetResponse = gatewayClient
          .getCatalogueItemsGet[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        getCatalogueItemsGetResponse.code shouldBe StatusCode.Ok
        getCatalogueItemsGetResponse.body.value shouldBe smithy.GetCatalogueItemsGetResponse(Nil)
      }

      "fail with a BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val getCatalogueItemsGetResponse = gatewayClient
          .getCatalogueItemsGet[smithy.BadRequest](
            None,
            Some(accessJwt.accessToken),
          )
          .zioValue

        getCatalogueItemsGetResponse.code shouldBe StatusCode.BadRequest
        getCatalogueItemsGetResponse.body.left.value shouldBe smithy.BadRequest()
      }

      "fail with an Unauthorized when the access token is missing" in withContext { context =>
        import context.*

        val organizationID = arbitrarySample[OrganizationID]

        val getCatalogueItemsGetResponse = gatewayClient
          .getCatalogueItemsGet[smithy.Unauthorized](
            Some(organizationID),
            None,
          )
          .zioValue

        getCatalogueItemsGetResponse.code shouldBe StatusCode.Unauthorized
        getCatalogueItemsGetResponse.body.left.value shouldBe smithy.Unauthorized()
      }

      "fail with an Unauthorized when the access token is invalid" in withContext { context =>
        import context.*

        val organizationID = arbitrarySample[OrganizationID]

        val getCatalogueItemsGetResponse = gatewayClient
          .getCatalogueItemsGet[smithy.Unauthorized](
            Some(organizationID),
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        getCatalogueItemsGetResponse.code shouldBe StatusCode.Unauthorized
        getCatalogueItemsGetResponse.body.left.value shouldBe smithy.Unauthorized()
      }

      "fail with a Forbidden when the user is not in a completed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow       = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStageInvalid)
        val organizationUserRole = Random.shuffle(OrganizationUserRole.userRoles).zioValue.head
        val organizationUserRow  = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRole,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val getCatalogueItemsGetResponse = gatewayClient
          .getCatalogueItemsGet[smithy.Forbidden](
            Some(organizationUserRow.organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        getCatalogueItemsGetResponse.code shouldBe StatusCode.Forbidden
        getCatalogueItemsGetResponse.body.left.value shouldBe smithy.Forbidden()
      }

      "fail with an InternalServerError when the user details do not exist" in withContext { context =>
        import context.*

        val userID         = arbitrarySample[UserID]
        val organizationID = arbitrarySample[OrganizationID]
        val accessJwt      = jwtService.generateAccessToken(userID).zioValue

        val getCatalogueItemsGetResponse = gatewayClient
          .getCatalogueItemsGet[smithy.InternalServerError](
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        getCatalogueItemsGetResponse.code shouldBe StatusCode.InternalServerError
        getCatalogueItemsGetResponse.body.left.value shouldBe smithy.InternalServerError()
      }

      "fail with an InternalServerError when the user is not a member of the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationID = arbitrarySample[OrganizationID]
        val accessJwt      = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val getCatalogueItemsGetResponse = gatewayClient
          .getCatalogueItemsGet[smithy.InternalServerError](
            Some(organizationID),
            Some(accessJwt.accessToken),
          )
          .zioValue

        getCatalogueItemsGetResponse.code shouldBe StatusCode.InternalServerError
        getCatalogueItemsGetResponse.body.left.value shouldBe smithy.InternalServerError()
      }
    }
  }
}
