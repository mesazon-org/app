package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.domain.to
import io.mesazon.gateway.clients.S3ClientOrganizationMedia
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.service.JwtService.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.state.AuthState
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

class UserSignInServiceSpec extends ZWordSpecBase, SmithyArbitraries, RepositoryArbitraries, TokenArbitraries {

  private val repositoryError =
    ServiceError.InternalServerError.RepositoryError("repository failure", new RuntimeException("boom"))

  "UserSignInService" when {
    "signInEmailPost" should {
      "successfully sign in a user and return their organizations" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID)

        val organizationAndUserDetailsRowOwnerWithLogo = arbitrarySample[OrganizationAndUserDetailsRow]
          .copy(
            userRole = OrganizationUserRole.Owner,
            logoImageNormalizedS3BucketKey = Some(arbitrarySample[ImageNormalizedS3BucketKey]),
          )
        val organizationAndUserDetailsRowAdminWithoutLogo = arbitrarySample[OrganizationAndUserDetailsRow]
          .copy(
            userRole = OrganizationUserRole.Admin,
            logoImageNormalizedS3BucketKey = None,
          )

        organizationAndUserDetailsRowOwnerWithLogo.organizationID shouldNot equal(
          organizationAndUserDetailsRowAdminWithoutLogo.organizationID
        )

        val organizationLogoUrl = arbitrarySample[S3MediaUrl]

        val userTokenRow = arbitrarySample[UserTokenRow]
          .copy(
            userID = authedUser.userID,
            tokenType = TokenType.RefreshToken,
          )

        val refreshJwt = arbitrarySample[RefreshJwt]
          .copy(
            tokenID = userTokenRow.tokenID,
            expiresAt = userTokenRow.expiresAt,
          )

        val accessJwt = arbitrarySample[AccessJwt]

        inSequence(
          (() => authStateMock.get)
            .expects()
            .returningZIO(authedUser)
            .once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          organizationManagementRepositoryMock.getUserOrganizations
            .expects(authedUser.userID)
            .returningZIO(
              List(organizationAndUserDetailsRowOwnerWithLogo, organizationAndUserDetailsRowAdminWithoutLogo)
            )
            .once(),
          s3ClientOrganizationMediaMock.genMediaUrl
            .expects(organizationAndUserDetailsRowOwnerWithLogo.logoImageNormalizedS3BucketKey.value.to[S3BucketKey])
            .returningZIO(organizationLogoUrl)
            .once(),
          userTokenRepositoryMock.deleteAllUserTokens
            .expects(authedUser.userID)
            .returnsZIOUnit
            .once(),
          jwtServiceMock.generateAccessToken
            .expects(authedUser.userID)
            .returningZIO(accessJwt)
            .once(),
          jwtServiceMock.generateRefreshToken
            .expects(authedUser.userID)
            .returningZIO(refreshJwt)
            .once(),
          userTokenRepositoryMock.upsertUserToken
            .expects(userTokenRow.tokenID, userTokenRow.userID, userTokenRow.tokenType, userTokenRow.expiresAt, None)
            .returnsZIOUnit
            .once(),
        )

        val userSignInService = buildUserSignInServiceLive

        val signInPostResponse = userSignInService.signInPost().zioValue

        signInPostResponse shouldBe smithy.SignInPostResponse(
          accessTokenExpiresInSeconds = accessJwt.expiresIn.toSeconds,
          onboardStage = onboardStageFromDomainToSmithy(userDetailsRow.onboardStage),
          refreshToken = refreshJwt.refreshToken.value,
          accessToken = accessJwt.accessToken.value,
          organizations = List(
            smithy.SignInOrganization(
              organizationID = organizationAndUserDetailsRowOwnerWithLogo.organizationID.value,
              name = organizationAndUserDetailsRowOwnerWithLogo.name.value,
              slug = organizationAndUserDetailsRowOwnerWithLogo.slug.value,
              role = smithy.OrganizationUserRole.Owner,
              logoUrl = Some(organizationLogoUrl.value),
            ),
            smithy.SignInOrganization(
              organizationID = organizationAndUserDetailsRowAdminWithoutLogo.organizationID.value,
              name = organizationAndUserDetailsRowAdminWithoutLogo.name.value,
              slug = organizationAndUserDetailsRowAdminWithoutLogo.slug.value,
              role = smithy.OrganizationUserRole.Admin,
              logoUrl = None,
            ),
          ),
        )
      }

      "successfully sign in a user with no organizations" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID)

        val userTokenRow = arbitrarySample[UserTokenRow]
          .copy(
            userID = authedUser.userID,
            tokenType = TokenType.RefreshToken,
          )

        val refreshJwt = arbitrarySample[RefreshJwt]
          .copy(
            tokenID = userTokenRow.tokenID,
            expiresAt = userTokenRow.expiresAt,
          )

        val accessJwt = arbitrarySample[AccessJwt]

        inSequence(
          (() => authStateMock.get)
            .expects()
            .returningZIO(authedUser)
            .once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          organizationManagementRepositoryMock.getUserOrganizations
            .expects(authedUser.userID)
            .returningZIO(Nil)
            .once(),
          userTokenRepositoryMock.deleteAllUserTokens
            .expects(authedUser.userID)
            .returnsZIOUnit
            .once(),
          jwtServiceMock.generateAccessToken
            .expects(authedUser.userID)
            .returningZIO(accessJwt)
            .once(),
          jwtServiceMock.generateRefreshToken
            .expects(authedUser.userID)
            .returningZIO(refreshJwt)
            .once(),
          userTokenRepositoryMock.upsertUserToken
            .expects(userTokenRow.tokenID, userTokenRow.userID, userTokenRow.tokenType, userTokenRow.expiresAt, None)
            .returnsZIOUnit
            .once(),
        )

        val userSignInService = buildUserSignInServiceLive

        val signInPostResponse = userSignInService.signInPost().zioValue

        signInPostResponse shouldBe smithy.SignInPostResponse(
          accessTokenExpiresInSeconds = accessJwt.expiresIn.toSeconds,
          onboardStage = onboardStageFromDomainToSmithy(userDetailsRow.onboardStage),
          refreshToken = refreshJwt.refreshToken.value,
          accessToken = accessJwt.accessToken.value,
          organizations = Nil,
        )
      }

      "fail with UnexpectedError when user details not found" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        inSequence(
          (() => authStateMock.get)
            .expects()
            .returningZIO(authedUser)
            .once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(None)
            .once(),
        )

        val userSignInService = buildUserSignInServiceLive

        val serviceError = userSignInService.signInPost().zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError] shouldBe ServiceError.InternalServerError
          .UnexpectedError(
            s"User details not found for userID: [${authedUser.userID}]"
          )
      }

      "fail with a RepositoryError and leave existing tokens untouched when the user's organizations cannot be loaded" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID)

        inSequence(
          (() => authStateMock.get)
            .expects()
            .returningZIO(authedUser)
            .once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          organizationManagementRepositoryMock.getUserOrganizations
            .expects(authedUser.userID)
            .returns(ZIO.fail(repositoryError))
            .once(),
        )

        val userSignInService = buildUserSignInServiceLive

        userSignInService.signInPost().zioError shouldBe repositoryError
      }

      "fail with an UnexpectedError and leave existing tokens untouched when a logo link cannot be signed" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID)

        val organizationAndUserDetailsRow = arbitrarySample[OrganizationAndUserDetailsRow]
          .copy(logoImageNormalizedS3BucketKey = Some(arbitrarySample[ImageNormalizedS3BucketKey]))

        val unexpectedError = ServiceError.InternalServerError.UnexpectedError("Failed to presignGetObject request")

        inSequence(
          (() => authStateMock.get)
            .expects()
            .returningZIO(authedUser)
            .once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          organizationManagementRepositoryMock.getUserOrganizations
            .expects(authedUser.userID)
            .returningZIO(
              List(organizationAndUserDetailsRow)
            )
            .once(),
          s3ClientOrganizationMediaMock.genMediaUrl
            .expects(organizationAndUserDetailsRow.logoImageNormalizedS3BucketKey.value.to[S3BucketKey])
            .returns(ZIO.fail(unexpectedError))
            .once(),
        )

        val userSignInService = buildUserSignInServiceLive

        userSignInService.signInPost().zioError shouldBe unexpectedError
      }
    }
  }

  trait TestContext {

    val authStateMock                        = mock[AuthState]
    val jwtServiceMock                       = mock[JwtService]
    val userDetailsRepositoryMock            = mock[UserDetailsRepository]
    val userTokenRepositoryMock              = mock[UserTokenRepository]
    val organizationManagementRepositoryMock = mock[OrganizationManagementRepository]
    val s3ClientOrganizationMediaMock        = mock[S3ClientOrganizationMedia]

    def buildUserSignInServiceLive: smithy.UserSignInService[ServiceTask] =
      ZIO
        .service[smithy.UserSignInService[ServiceTask]]
        .provide(
          UserSignInService.local,
          ZLayer.succeed(authStateMock),
          ZLayer.succeed(userDetailsRepositoryMock),
          ZLayer.succeed(userTokenRepositoryMock),
          ZLayer.succeed(jwtServiceMock),
          ZLayer.succeed(organizationManagementRepositoryMock),
          ZLayer.succeed(s3ClientOrganizationMediaMock),
        )
        .zioValue
  }
}
