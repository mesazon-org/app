package io.mesazon.gateway.it

import com.dimafeng.testcontainers.DockerComposeContainer
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.given
import io.mesazon.gateway.it.harness.GatewayAcceptanceTest
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.scalatest.DoNotDiscover
import sttp.model.*
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

@DoNotDiscover
class UserSignInApiSpec(dockerComposeContainer: => DockerComposeContainer)
    extends GatewayAcceptanceTest(dockerComposeContainer),
      SmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  override def beforeEach(): Unit = {
    super.beforeEach()

    withContext { context =>
      import context.*

      postgresClient.truncateTable(
        repositoryConfig.schema,
        repositoryConfig.userDetailsTable,
        repositoryConfig.userCredentialsTable,
        repositoryConfig.userActionAttemptTable,
        repositoryConfig.userTokenTable,
        repositoryConfig.organizationDetailsTable,
        repositoryConfig.organizationUserTable,
      )
    }
  }

  "User Sign In API" when {
    "POST /signin" should {
      "successfully sign in user with valid credentials" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.signInAllowedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(
              onboardStage = onboardStage
            )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          password = arbitrarySample[Password]

          passwordHash <- passwordService.hashPassword(password)

          userCredentialsRow = arbitrarySample[UserCredentialsRow]
            .copy(
              userID = userDetailsRow.userID,
              passwordHash = passwordHash,
            )

          _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))

          signInPostResponse <- gatewayClient.signInPost[smithy.InternalServerError](userDetailsRow.email, password)

          _ = signInPostResponse.code shouldBe StatusCode.Ok
          _ = signInPostResponse.body.value.onboardStage shouldBe onboardStage.transformInto[smithy.OnboardStage]
          _ = signInPostResponse.body.value.organizations shouldBe empty

          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userActionAttemptRowsAll shouldBe empty
          _ = userTokenRowsAll should have size 1
          _ = userTokenRowsAll.head.tokenType shouldBe TokenType.RefreshToken
        } yield ()
      }

      "successfully sign in user with valid credentials should delete all users refresh tokens and create a new one" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signInAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            password = arbitrarySample[Password]

            passwordHash <- passwordService.hashPassword(password)

            userCredentialsRow = arbitrarySample[UserCredentialsRow]
              .copy(
                userID = userDetailsRow.userID,
                passwordHash = passwordHash,
              )

            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))

            // Insert an existing refresh token for the user
            existingRefreshTokenRow = arbitrarySample[UserTokenRow]
              .copy(
                userID = userDetailsRow.userID,
                tokenType = TokenType.RefreshToken,
              )

            _ <- postgresClient.executeQuery(userTokenQueries.insertUserToken(existingRefreshTokenRow))

            // Now attempt to sign in, should delete the existing refresh token and create a new one in its place
            signInPostResponse <- gatewayClient.signInPost[smithy.InternalServerError](userDetailsRow.email, password)

            _ = signInPostResponse.code shouldBe StatusCode.Ok
            _ = signInPostResponse.body.value.onboardStage shouldBe onboardStage.transformInto[smithy.OnboardStage]

            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )
            userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            _ = userActionAttemptRowsAll shouldBe empty
            _ = userTokenRowsAll should have size 1
            _ = all(userTokenRowsAll.map(_.tokenType)) shouldBe TokenType.RefreshToken
            _ = userTokenRowsAll.head.tokenID should not be existingRefreshTokenRow.tokenID
          } yield ()
      }

      "successfully sign in user with valid credentials on the last allowed attempt after one fewer failed retries than the max" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signInAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            password = arbitrarySample[Password]

            passwordHash <- passwordService.hashPassword(password)

            userCredentialsRow = arbitrarySample[UserCredentialsRow]
              .copy(
                userID = userDetailsRow.userID,
                passwordHash = passwordHash,
              )

            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))

            wrongPassword = Password("WrongPassword123!")
            maxAttempts   = 10 // application.conf sign-in-attempts-max

            signInPostResponsesWrongPassword <- ZIO.collectAll(
              List.fill(maxAttempts - 1)(
                gatewayClient.signInPost[smithy.Unauthorized](userDetailsRow.email, wrongPassword)
              )
            )

            _ = signInPostResponsesWrongPassword.map(_.code).toSet shouldBe Set(StatusCode.Unauthorized)

            signInPostResponse <- gatewayClient.signInPost[smithy.InternalServerError](userDetailsRow.email, password)

            _ = signInPostResponse.code shouldBe StatusCode.Ok
            _ = signInPostResponse.body.value.onboardStage shouldBe onboardStage.transformInto[smithy.OnboardStage]

            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )
            userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            _ = userActionAttemptRowsAll shouldBe empty
            _ = userTokenRowsAll should have size 1
            _ = userTokenRowsAll.head.tokenType shouldBe TokenType.RefreshToken
          } yield ()
      }

      "successfully sign in user with valid credentials once the too-many-attempts block duration has passed" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signInAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            password = arbitrarySample[Password]

            passwordHash <- passwordService.hashPassword(password)

            userCredentialsRow = arbitrarySample[UserCredentialsRow]
              .copy(
                userID = userDetailsRow.userID,
                passwordHash = passwordHash,
              )

            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))

            instantNow    = Instant.now.truncatedTo(ChronoUnit.MILLIS)
            maxAttempts   = 10         // application.conf sign-in-attempts-max
            blockDuration = 15.minutes // application.conf sign-in-attempts-block-duration
            // The extra minute absorbs clock drift between the test host and the gateway container
            lastAttemptAt        = instantNow.minus(blockDuration.plusMinutes(1))
            userActionAttemptRow = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.SignIn,
                attempts = Attempts.assume(maxAttempts + 1), // the next attempt is the first past the limit
                createdAt = CreatedAt(instantNow),
                updatedAt = UpdatedAt(lastAttemptAt),
              )

            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRow)
            )

            signInPostResponse <- gatewayClient.signInPost[smithy.InternalServerError](userDetailsRow.email, password)

            _ = signInPostResponse.code shouldBe StatusCode.Ok
            _ = signInPostResponse.body.value.onboardStage shouldBe onboardStage.transformInto[smithy.OnboardStage]

            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )
            userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            _ = userActionAttemptRowsAll shouldBe empty
            _ = userTokenRowsAll should have size 1
            _ = userTokenRowsAll.head.tokenType shouldBe TokenType.RefreshToken
          } yield ()
      }

      "successfully sign in and delete the user's outstanding password-reset token, keeping only the new refresh token" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signInAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            password = arbitrarySample[Password]

            passwordHash <- passwordService.hashPassword(password)

            userCredentialsRow = arbitrarySample[UserCredentialsRow]
              .copy(
                userID = userDetailsRow.userID,
                passwordHash = passwordHash,
              )
            userTokenRowRefreshToken = arbitrarySample[UserTokenRow]
              .copy(
                userID = userDetailsRow.userID,
                tokenType = TokenType.RefreshToken,
              )
            userTokenRowResetPasswordToken = arbitrarySample[UserTokenRow]
              .copy(
                userID = userDetailsRow.userID,
                tokenType = TokenType.ResetPasswordToken,
              )

            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))
            _ <- postgresClient.executeQuery(userTokenQueries.insertUserToken(userTokenRowRefreshToken))
            _ <- postgresClient.executeQuery(userTokenQueries.insertUserToken(userTokenRowResetPasswordToken))

            signInPostResponse <- gatewayClient.signInPost[smithy.InternalServerError](userDetailsRow.email, password)

            _ = signInPostResponse.code shouldBe StatusCode.Ok
            _ = signInPostResponse.body.value.onboardStage shouldBe onboardStage.transformInto[smithy.OnboardStage]

            authedUserRefresh <- jwtService.verifyRefreshToken(
              RefreshToken.assume(signInPostResponse.body.value.refreshToken)
            )

            _ = authedUserRefresh.tokenID shouldNot equal(userTokenRowRefreshToken.tokenID)
            _ = authedUserRefresh.tokenID shouldNot equal(userTokenRowResetPasswordToken.tokenID)

            userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            _ = userTokenRowsAll shouldBe List(
              UserTokenRow(
                tokenID = authedUserRefresh.tokenID,
                userID = userDetailsRow.userID,
                tokenType = TokenType.RefreshToken,
                createdAt = userTokenRowsAll.head.createdAt, // ignore timestamp value
                expiresAt = userTokenRowsAll.head.expiresAt, // ignore timestamp value
              )
            )
          } yield ()
      }

      "successfully sign in and return the user's organizations newest membership first, with a logo link only for the organization that has a logo" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signInAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            password = arbitrarySample[Password]

            passwordHash <- passwordService.hashPassword(password)

            userCredentialsRow = arbitrarySample[UserCredentialsRow]
              .copy(
                userID = userDetailsRow.userID,
                passwordHash = passwordHash,
              )

            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))

            instantNow                     = Instant.now.truncatedTo(ChronoUnit.MILLIS)
            organizationLogoImageAsset     = arbitrarySample[OrganizationLogoImageAsset]
            organizationDetailsRowWithLogo = arbitrarySample[OrganizationDetailsRow]
              .copy(
                slug = OrganizationSlug.assume("organization1"),
                logoImageAsset = Some(organizationLogoImageAsset),
              )
            organizationDetailsRowWithoutLogo = arbitrarySample[OrganizationDetailsRow]
              .copy(
                slug = OrganizationSlug.assume("organization2"),
                logoImageAsset = None,
              )
            organizationUserRowWithLogo = arbitrarySample[OrganizationUserRow]
              .copy(
                organizationID = organizationDetailsRowWithLogo.organizationID,
                userID = userDetailsRow.userID,
                createdAt = CreatedAt(instantNow),
                updatedAt = UpdatedAt(instantNow),
              )
            organizationUserRowWithoutLogo = arbitrarySample[OrganizationUserRow]
              .copy(
                organizationID = organizationDetailsRowWithoutLogo.organizationID,
                userID = userDetailsRow.userID,
                createdAt = CreatedAt(instantNow.minusSeconds(10)),
                updatedAt = UpdatedAt(instantNow.minusSeconds(10)),
              )

            _ = organizationDetailsRowWithLogo.organizationID shouldNot equal(
              organizationDetailsRowWithoutLogo.organizationID
            )

            _ <- postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRowWithLogo))
            _ <- postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRowWithoutLogo))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRowWithoutLogo))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRowWithLogo))

            signInPostResponse <- gatewayClient.signInPost[smithy.InternalServerError](userDetailsRow.email, password)

            _ = signInPostResponse.code shouldBe StatusCode.Ok
            _ = signInPostResponse.body.value.onboardStage shouldBe onboardStage.transformInto[smithy.OnboardStage]
            _ = signInPostResponse.body.value.organizations shouldBe List(
              smithy.SignInOrganization(
                organizationID = organizationDetailsRowWithLogo.organizationID.value,
                name = organizationDetailsRowWithLogo.name.value,
                slug = organizationDetailsRowWithLogo.slug.value,
                userRole = organizationUserRowWithLogo.userRole.transformInto[smithy.OrganizationUserRole],
                logoUrl = signInPostResponse.body.value.organizations.head.logoUrl, // asserted below
              ),
              smithy.SignInOrganization(
                organizationID = organizationDetailsRowWithoutLogo.organizationID.value,
                name = organizationDetailsRowWithoutLogo.name.value,
                slug = organizationDetailsRowWithoutLogo.slug.value,
                userRole = organizationUserRowWithoutLogo.userRole.transformInto[smithy.OrganizationUserRole],
                logoUrl = None,
              ),
            )
            _ = signInPostResponse.body.value.organizations.head.logoUrl.value should include(
              organizationLogoImageAsset.value.imageNormalizedS3BucketKey.value
            )

            userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            _ = userTokenRowsAll should have size 1
            _ = userTokenRowsAll.head.tokenType shouldBe TokenType.RefreshToken
          } yield ()
      }

      "fail with BadRequest ValidationError when email is invalid" in withContext { context =>
        import context.*

        for {
          invalidEmail = Email.assume("invalid-email-format")
          password     = arbitrarySample[Password]

          signInPostResponse <- gatewayClient.signInPost[smithy.ValidationError](invalidEmail, password)

          _ = signInPostResponse.code shouldBe StatusCode.BadRequest
          _ = signInPostResponse.body.left.value shouldBe smithy.ValidationError(
            fields = List("email")
          )

          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userActionAttemptRowsAll shouldBe empty
          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }

      "fail with Unauthorized when basic credentials is missing" in withContext { context =>
        import context.*

        for {
          email    = arbitrarySample[Email]
          password = arbitrarySample[Password]

          signInPostResponse <- gatewayClient.signInPost[smithy.Unauthorized](email, password, addBasicAuth = false)

          _ = signInPostResponse.code shouldBe StatusCode.Unauthorized
          _ = signInPostResponse.body.left.value shouldBe smithy.Unauthorized()

          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userActionAttemptRowsAll shouldBe empty
          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }

      "fail with Unauthorized when the password is wrong" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.signInAllowedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(
              onboardStage = onboardStage
            )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          password = arbitrarySample[Password]

          passwordHash <- passwordService.hashPassword(password)

          userCredentialsRow = arbitrarySample[UserCredentialsRow]
            .copy(
              userID = userDetailsRow.userID,
              passwordHash = passwordHash,
            )

          _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))

          wrongPassword = Password("WrongPassword123!")

          signInPostResponse <- gatewayClient.signInPost[smithy.Unauthorized](userDetailsRow.email, wrongPassword)

          _ = signInPostResponse.code shouldBe StatusCode.Unauthorized
          _ = signInPostResponse.body.left.value shouldBe smithy.Unauthorized()

          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userActionAttemptRowsAll should have size 1
          _ = userActionAttemptRowsAll.head shouldBe UserActionAttemptRow(
            actionAttemptID = userActionAttemptRowsAll.head.actionAttemptID, // ignore ID value
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.SignIn,
            attempts = Attempts.assume(2),                       // stored one ahead: the next attempt's number
            createdAt = userActionAttemptRowsAll.head.createdAt, // ignore timestamp value
            updatedAt = userActionAttemptRowsAll.head.updatedAt, // ignore timestamp value
          )
          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }

      "fail with Unauthorized when credentials are correct but max invalid attempts has been reached" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signInAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            password = arbitrarySample[Password]

            passwordHash <- passwordService.hashPassword(password)

            userCredentialsRow = arbitrarySample[UserCredentialsRow]
              .copy(
                userID = userDetailsRow.userID,
                passwordHash = passwordHash,
              )

            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))

            wrongPassword = Password("WrongPassword123!")
            maxAttempts   = 10 // application.conf sign-in-attempts-max

            signInPostResponses <- ZIO.collectAll(
              List.fill(maxAttempts)(
                gatewayClient.signInPost[smithy.Unauthorized](userDetailsRow.email, wrongPassword)
              )
            )

            _ = signInPostResponses.last.code shouldBe StatusCode.Unauthorized
            _ = signInPostResponses.last.body.left.value shouldBe smithy.Unauthorized()

            signInPostResponseAfterFailedAttempts <- gatewayClient.signInPost[smithy.Unauthorized](
              userDetailsRow.email,
              password,
            )

            _ = signInPostResponseAfterFailedAttempts.code shouldBe StatusCode.Unauthorized
            _ = signInPostResponseAfterFailedAttempts.body.left.value shouldBe smithy.Unauthorized()

            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )
            userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            _ = userActionAttemptRowsAll should have size 1
            _ = userActionAttemptRowsAll.head shouldBe UserActionAttemptRow(
              actionAttemptID = userActionAttemptRowsAll.head.actionAttemptID, // ignore ID value
              userID = userDetailsRow.userID,
              actionAttemptType = ActionAttemptType.SignIn,
              attempts = Attempts.assume(maxAttempts + 2),         // maxAttempts + 1 attempts made, stored one ahead
              createdAt = userActionAttemptRowsAll.head.createdAt, // ignore timestamp value
              updatedAt = userActionAttemptRowsAll.head.updatedAt, // ignore timestamp value
            )
            _ = userTokenRowsAll shouldBe empty
          } yield ()
      }

      "fail with an Unauthorized when the email has no account, the same as a wrong password, without counting an attempt" in withContext {
        context =>
          import context.*

          for {
            email    = arbitrarySample[Email]
            password = arbitrarySample[Password]

            signInPostResponse <- gatewayClient.signInPost[smithy.Unauthorized](email, password)

            _ = signInPostResponse.code shouldBe StatusCode.Unauthorized
            _ = signInPostResponse.body.left.value shouldBe smithy.Unauthorized()

            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )
            userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            _ = userActionAttemptRowsAll shouldBe empty
            _ = userTokenRowsAll shouldBe empty
          } yield ()
      }

      "fail with an Unauthorized when the authorization header is not Basic" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.signInAllowedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          password = arbitrarySample[Password]

          passwordHash <- passwordService.hashPassword(password)

          userCredentialsRow = arbitrarySample[UserCredentialsRow]
            .copy(
              userID = userDetailsRow.userID,
              passwordHash = passwordHash,
            )

          _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          signInPostResponse <- gatewayClient.signInPostBearerAuth[smithy.Unauthorized](accessJwt.accessToken)

          _ = signInPostResponse.code shouldBe StatusCode.Unauthorized
          _ = signInPostResponse.body.left.value shouldBe smithy.Unauthorized()

          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userActionAttemptRowsAll shouldBe empty
          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }

      "fail with Forbidden when user is not in an allowed onboard stage" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random
            .shuffle(OnboardStage.values.toList.diff(OnboardStage.signInAllowedStages))
            .map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          password = arbitrarySample[Password]

          passwordHash <- passwordService.hashPassword(password)

          userCredentialsRow = arbitrarySample[UserCredentialsRow]
            .copy(
              userID = userDetailsRow.userID,
              passwordHash = passwordHash,
            )

          _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))

          signInPostResponse <- gatewayClient.signInPost[smithy.Forbidden](userDetailsRow.email, password)

          _ = signInPostResponse.code shouldBe StatusCode.Forbidden
          _ = signInPostResponse.body.left.value shouldBe smithy.Forbidden()

          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userActionAttemptRowsAll shouldBe empty
          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }

      "fail with an InternalServerError when the user has no stored credentials, still counting the attempt" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signInAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            password = arbitrarySample[Password]

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            signInPostResponse <- gatewayClient.signInPost[smithy.InternalServerError](userDetailsRow.email, password)

            _ = signInPostResponse.code shouldBe StatusCode.InternalServerError
            _ = signInPostResponse.body.left.value shouldBe smithy.InternalServerError()

            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )
            userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            _ = userActionAttemptRowsAll should have size 1
            _ = userActionAttemptRowsAll.head shouldBe UserActionAttemptRow(
              actionAttemptID = userActionAttemptRowsAll.head.actionAttemptID, // ignore ID value
              userID = userDetailsRow.userID,
              actionAttemptType = ActionAttemptType.SignIn,
              attempts = Attempts.assume(2),                       // stored one ahead: the next attempt's number
              createdAt = userActionAttemptRowsAll.head.createdAt, // ignore timestamp value
              updatedAt = userActionAttemptRowsAll.head.updatedAt, // ignore timestamp value
            )
            _ = userTokenRowsAll shouldBe empty
          } yield ()
      }

      "fail with an InternalServerError when a membership's organization is missing, keeping the existing refresh token" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signInAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            password = arbitrarySample[Password]

            passwordHash <- passwordService.hashPassword(password)

            userCredentialsRow = arbitrarySample[UserCredentialsRow]
              .copy(
                userID = userDetailsRow.userID,
                passwordHash = passwordHash,
              )
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID)
            userTokenRowRefreshToken = arbitrarySample[UserTokenRow]
              .copy(
                userID = userDetailsRow.userID,
                tokenType = TokenType.RefreshToken,
              )

            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))
            _ <- postgresClient.executeQuery(userTokenQueries.insertUserToken(userTokenRowRefreshToken))

            signInPostResponse <- gatewayClient.signInPost[smithy.InternalServerError](userDetailsRow.email, password)

            _ = signInPostResponse.code shouldBe StatusCode.InternalServerError
            _ = signInPostResponse.body.left.value shouldBe smithy.InternalServerError()

            organizationDetailsRowsAll <- postgresClient.executeQuery(
              organizationDetailsQueries.getAllOrganizationDetailsTesting
            )
            userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            _ = organizationDetailsRowsAll shouldBe empty
            _ = userTokenRowsAll shouldBe List(userTokenRowRefreshToken)
          } yield ()
      }
    }
  }
}
