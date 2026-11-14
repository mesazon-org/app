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
import org.scalatest.DoNotDiscover
import sttp.model.*
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

@DoNotDiscover
class UserForgotPasswordApiSpec(dockerComposeContainer: => DockerComposeContainer)
    extends GatewayAcceptanceTest(dockerComposeContainer),
      SmithyArbitraries,
      UserForgotPasswordSmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  override def beforeEach(): Unit = {
    super.beforeEach()

    withContext { context =>
      import context.*

      for {
        _ <- postgresClient.truncateTable(
          repositoryConfig.schema,
          repositoryConfig.userDetailsTable,
          repositoryConfig.userCredentialsTable,
          repositoryConfig.userOtpTable,
          repositoryConfig.userActionAttemptTable,
          repositoryConfig.userTokenTable,
        )
        _ <- mailHogClient.clearInbox()
      } yield ()
    }
  }

  "User Forgot Password API" when {
    "POST /forgot/password" should {
      "successfully create and email a new OTP, clearing the verify attempts counter but keeping the request counter, when the user has no OTP" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.forgotPasswordAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            // Cleared because a new OTP is created
            userActionAttemptRowForgotPasswordVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
              )
            // Kept because only a correct verify clears the request counter
            userActionAttemptRowForgotPassword = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.ForgotPassword,
              )
            userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPasswordVerifyOTP)
            )
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPassword)
            )
            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))

            forgotPasswordPostResponse <- gatewayClient.forgotPasswordPost[smithy.InternalServerError](
              userDetailsRow.email
            )

            userOtpRowsAll <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

            _ = userOtpRowsAll should have size 1
            _ = userOtpRowsAll shouldBe List(
              UserOtpRow(
                otpID = OtpID.assume(forgotPasswordPostResponse.body.value.otpID),
                userID = userDetailsRow.userID,
                otp = userOtpRowsAll.head.otp,
                otpType = OtpType.ForgotPassword,
                createdAt = userOtpRowsAll.head.createdAt,
                updatedAt = userOtpRowsAll.head.updatedAt,
                expiresAt = userOtpRowsAll.head.expiresAt,
              )
            )

            _ = forgotPasswordPostResponse.code shouldBe StatusCode.Ok
            _ = forgotPasswordPostResponse.body.value.otpID shouldBe userOtpRowsAll.head.otpID.value
            _ = forgotPasswordPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf default value

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 1

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )
            userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
            userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head shouldBe userDetailsRow
            _ = userActionAttemptRowsAll shouldBe List(userActionAttemptRowForgotPassword)
            _ = userTokenRowsAll shouldBe empty
            _ = userCredentialsRowsAll should have size 1
            _ = userCredentialsRowsAll.head shouldBe userCredentialsRow
          } yield ()
      }

      "successfully replace a stale OTP with a new one, email it and clear the verify attempts counter" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.forgotPasswordAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            // Cleared because a new OTP is created
            userActionAttemptRowForgotPasswordVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
              )
            userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)
            // Not yet expired, but inside the resend cooldown (application.conf otp-resend-cooldown 15 seconds)
            userOtpRowStale = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.ForgotPassword,
                expiresAt = ExpiresAt(instantNow.plusSeconds(10)),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPasswordVerifyOTP)
            )
            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRowStale))

            forgotPasswordPostResponse <- gatewayClient.forgotPasswordPost[smithy.InternalServerError](
              userDetailsRow.email
            )

            _ = forgotPasswordPostResponse.code shouldBe StatusCode.Ok
            _ = forgotPasswordPostResponse.body.value.otpID should not be userOtpRowStale.otpID.value
            _ = forgotPasswordPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf default value

            userOtpRowsAll <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

            _ = userOtpRowsAll should have size 1
            _ = userOtpRowsAll.head.expiresAt should not be userOtpRowStale.expiresAt
            _ = userOtpRowsAll shouldBe List(
              UserOtpRow(
                otpID = OtpID.assume(forgotPasswordPostResponse.body.value.otpID),
                userID = userDetailsRow.userID,
                otp = userOtpRowsAll.head.otp,
                otpType = OtpType.ForgotPassword,
                createdAt = userOtpRowsAll.head.createdAt,
                updatedAt = userOtpRowsAll.head.updatedAt,
                expiresAt = userOtpRowsAll.head.expiresAt,
              )
            )

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 1

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )
            userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
            userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

            _ = userDetailsRowsAll shouldBe List(userDetailsRow)
            _ = userActionAttemptRowsAll shouldBe empty
            _ = userTokenRowsAll shouldBe empty
            _ = userCredentialsRowsAll shouldBe List(userCredentialsRow)
          } yield ()
      }

      "successfully reuse the existing OTP, reset its expiry and count the request without sending an email" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.forgotPasswordAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            // Should not delete this action attempt for non expired otp
            userActionAttemptRowForgotPasswordVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
              )
            userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)
            userOtpRow         = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.ForgotPassword,
                expiresAt = ExpiresAt(instantNow.plusSeconds(100)),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPasswordVerifyOTP)
            )
            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

            forgotPasswordPostResponse <- gatewayClient.forgotPasswordPost[smithy.InternalServerError](
              userDetailsRow.email
            )

            instantNowAfterForgotPasswordPost = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            userOtpRowsAll <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

            _ = userOtpRowsAll should have size 1
            _ = userOtpRowsAll.head.expiresAt should not be userOtpRow.expiresAt
            _ = userOtpRowsAll.head.updatedAt should not be userOtpRow.updatedAt
            _ = userOtpRowsAll.head shouldBe userOtpRow.copy(
              expiresAt = userOtpRowsAll.head.expiresAt,
              updatedAt = userOtpRowsAll.head.updatedAt,
            )

            _ = forgotPasswordPostResponse.code shouldBe StatusCode.Ok
            _ = forgotPasswordPostResponse.body.value.otpID shouldBe userOtpRowsAll.head.otpID.value
            _ = forgotPasswordPostResponse.body.value.otpExpiresInSeconds shouldBe
              (userOtpRowsAll.head.expiresAt.value.getEpochSecond - instantNowAfterForgotPasswordPost.getEpochSecond) +- 1

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head shouldBe userDetailsRow
            _ = userActionAttemptRowsAll should have size 2

            userActionAttemptRowForgotPasswordVerifyOTPResult = userActionAttemptRowsAll
              .filter(_.actionAttemptType == ActionAttemptType.ForgotPasswordVerifyOTP)
              .head
            userActionAttemptRowForgotPasswordResult = userActionAttemptRowsAll
              .filter(_.actionAttemptType == ActionAttemptType.ForgotPassword)
              .head

            _ = userActionAttemptRowForgotPasswordVerifyOTPResult shouldBe userActionAttemptRowForgotPasswordVerifyOTP

            _ = userActionAttemptRowForgotPasswordResult shouldBe UserActionAttemptRow(
              actionAttemptID = userActionAttemptRowForgotPasswordResult.actionAttemptID,
              userID = userDetailsRow.userID,
              actionAttemptType = ActionAttemptType.ForgotPassword,
              attempts = Attempts.assume(2), // stored one ahead: the next request's number
              createdAt = userActionAttemptRowForgotPasswordResult.createdAt,
              updatedAt = userActionAttemptRowForgotPasswordResult.updatedAt,
            )

            userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
            userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

            _ = userTokenRowsAll shouldBe empty
            _ = userCredentialsRowsAll should have size 1
            _ = userCredentialsRowsAll.head shouldBe userCredentialsRow
          } yield ()
      }

      "successfully reset the expiry of the existing OTP without sending an email on the last allowed repeat request" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.forgotPasswordAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)
            userOtpRow         = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.ForgotPassword,
                expiresAt = ExpiresAt(instantNow.plusSeconds(100)),
              )
            otpResetAttemptsMaxRetries = 3 // application.conf otp-reset-attempts-max-retries
            userActionAttemptRow       = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.ForgotPassword,
                attempts = Attempts.assume(otpResetAttemptsMaxRetries),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRow)
            )

            forgotPasswordPostResponse <- gatewayClient.forgotPasswordPost[smithy.InternalServerError](
              userDetailsRow.email
            )

            instantNowAfterForgotPasswordPost = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            userOtpRowsAll <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

            _ = userOtpRowsAll should have size 1
            _ = userOtpRowsAll.head.expiresAt should not be userOtpRow.expiresAt
            _ = userOtpRowsAll.head.updatedAt should not be userOtpRow.updatedAt
            _ = userOtpRowsAll.head shouldBe userOtpRow.copy(
              expiresAt = userOtpRowsAll.head.expiresAt,
              updatedAt = userOtpRowsAll.head.updatedAt,
            )

            _ = forgotPasswordPostResponse.code shouldBe StatusCode.Ok
            _ = forgotPasswordPostResponse.body.value.otpID shouldBe userOtpRowsAll.head.otpID.value
            _ = forgotPasswordPostResponse.body.value.otpExpiresInSeconds shouldBe
              (userOtpRowsAll.head.expiresAt.value.getEpochSecond - instantNowAfterForgotPasswordPost.getEpochSecond) +- 1

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userActionAttemptRowsAll should have size 1
            _ = userActionAttemptRowsAll.head shouldBe userActionAttemptRow.copy(
              attempts = Attempts.assume(otpResetAttemptsMaxRetries + 1),
              updatedAt = userActionAttemptRowsAll.head.updatedAt,
            )
          } yield ()
      }

      "successfully return the existing OTP without changing its expiry or sending an email when the requests are over the limit" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.forgotPasswordAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            // Should not delete this action attempt
            userActionAttemptRowForgotPasswordVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
              )
            userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)
            userOtpRow         = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.ForgotPassword,
                expiresAt = ExpiresAt(instantNow.plusSeconds(100)),
              )
            otpResetAttemptsMaxRetries = 3 // application.conf otp-reset-attempts-max-retries
            userActionAttemptRow       = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.ForgotPassword,
                attempts = Attempts.assume(otpResetAttemptsMaxRetries + 1),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPasswordVerifyOTP)
            )
            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRow)
            )

            forgotPasswordPostResponse <- gatewayClient.forgotPasswordPost[smithy.InternalServerError](
              userDetailsRow.email
            )

            userOtpRowsAll <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

            _ = userOtpRowsAll should have size 1
            _ = userOtpRowsAll.head shouldBe userOtpRow

            _ = forgotPasswordPostResponse.code shouldBe StatusCode.Ok
            _ = forgotPasswordPostResponse.body.value.otpID shouldBe userOtpRow.otpID.value
            _ = forgotPasswordPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf default value

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head shouldBe userDetailsRow
            _ = userActionAttemptRowsAll should have size 2

            userActionAttemptRowForgotPasswordVerifyOTPResult = userActionAttemptRowsAll
              .filter(_.actionAttemptType == ActionAttemptType.ForgotPasswordVerifyOTP)
              .head
            userActionAttemptRowForgotPasswordResult = userActionAttemptRowsAll
              .filter(_.actionAttemptType == ActionAttemptType.ForgotPassword)
              .head

            _ = userActionAttemptRowForgotPasswordVerifyOTPResult shouldBe userActionAttemptRowForgotPasswordVerifyOTP

            _ = userActionAttemptRowForgotPasswordResult shouldBe userActionAttemptRow.copy(
              attempts = Attempts.assume(otpResetAttemptsMaxRetries + 2),
              updatedAt = userActionAttemptRowForgotPasswordResult.updatedAt,
            )

            userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
            userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

            _ = userTokenRowsAll shouldBe empty
            _ = userCredentialsRowsAll should have size 1
            _ = userCredentialsRowsAll.head shouldBe userCredentialsRow
          } yield ()
      }

      "successfully return a made-up OTP ID without storing or sending anything when no user has the email" in withContext {
        context =>
          import context.*

          for {
            email = arbitrarySample[Email]

            forgotPasswordPostResponse <- gatewayClient.forgotPasswordPost[smithy.InternalServerError](email)

            _ = forgotPasswordPostResponse.code shouldBe StatusCode.Ok
            _ = forgotPasswordPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf default value

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )
            userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
            userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

            _ = userDetailsRowsAll should have size 0
            _ = userOtpRowsAll shouldBe empty
            _ = userActionAttemptRowsAll should have size 0
            _ = userTokenRowsAll shouldBe empty
            _ = userCredentialsRowsAll shouldBe empty
          } yield ()
      }

      "fail with BadRequest ValidationError when the email is invalid" in withContext { context =>
        import context.*

        for {
          forgotPasswordPostResponse <- gatewayClient.forgotPasswordPost[smithy.ValidationError](
            Email.assume("invalid-email")
          )

          _ = forgotPasswordPostResponse.code shouldBe StatusCode.BadRequest
          _ = forgotPasswordPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("email"))

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
          userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

          _ = userDetailsRowsAll should have size 0
          _ = userOtpRowsAll shouldBe empty
          _ = userActionAttemptRowsAll should have size 0
          _ = userTokenRowsAll should have size 0
          _ = userCredentialsRowsAll should have size 0
        } yield ()
      }
    }

    "POST /forgot/password/verify-otp" should {
      "successfully verify the OTP on the last allowed attempt, clear both attempt counters, delete the user's existing tokens and issue a reset password token" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.forgotPasswordAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            // Cleared once the OTP is verified
            userActionAttemptRowForgotPassword = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.ForgotPassword,
              )
            otpVerifyAttemptsMaxRetries                 = 5 // application.conf otp-verify-attempts-max-retries
            userActionAttemptRowForgotPasswordVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
                attempts = Attempts.assume(otpVerifyAttemptsMaxRetries),
              )
            userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)
            userOtpRow         = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.ForgotPassword,
                expiresAt = ExpiresAt(instantNow.plusSeconds(100)),
              )
            // Should be deleted once the OTP is verified successfully
            userTokenRowExisting = arbitrarySample[UserTokenRow]
              .copy(
                userID = userDetailsRow.userID,
                tokenType = TokenType.RefreshToken,
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPassword)
            )
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPasswordVerifyOTP)
            )
            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))
            _ <- postgresClient.executeQuery(userTokenQueries.insertUserToken(userTokenRowExisting))

            forgotPasswordVerifyOTPPostResponse <- gatewayClient
              .forgotPasswordVerifyOTPPost[smithy.InternalServerError](
                userOtpRow.otpID,
                userOtpRow.otp,
              )

            _ = forgotPasswordVerifyOTPPostResponse.code shouldBe StatusCode.Ok
            _ = forgotPasswordVerifyOTPPostResponse.body.value.resetPasswordToken should not be empty
            _ = forgotPasswordVerifyOTPPostResponse.body.value.resetPasswordTokenExpiresInSeconds shouldBe
              (2.minutes.toSeconds +- 1) // application.conf default value

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head shouldBe userDetailsRow
            _ = userOtpRowsAll should have size 0
            _ = userActionAttemptRowsAll should have size 0

            userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            authedUserResetPassword <- jwtService.verifyResetPasswordToken(
              ResetPasswordToken.assume(forgotPasswordVerifyOTPPostResponse.body.value.resetPasswordToken)
            )

            _ = userTokenRowsAll should have size 1
            _ = userTokenRowsAll.head.userID shouldBe userDetailsRow.userID
            _ = userTokenRowsAll.head.tokenType shouldBe TokenType.ResetPasswordToken
            _ = userTokenRowsAll.head.tokenID shouldBe authedUserResetPassword.tokenID
            _ = userTokenRowsAll.head.tokenID should not be userTokenRowExisting.tokenID

            userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

            _ = userCredentialsRowsAll should have size 1
            _ = userCredentialsRowsAll.head shouldBe userCredentialsRow
          } yield ()
      }

      "fail with BadRequest when the OTP is wrong, counting the attempt and keeping the OTP" in withContext { context =>
        import context.*

        for {
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          onboardStage <- Random.shuffle(OnboardStage.forgotPasswordAllowedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          // Should not delete this action attempt when OTP is wrong
          userActionAttemptRowForgotPassword = arbitrarySample[UserActionAttemptRow]
            .copy(
              userID = userDetailsRow.userID,
              actionAttemptType = ActionAttemptType.ForgotPassword,
            )
          userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)
          userOtpRow         = arbitrarySample[UserOtpRow]
            .copy(
              userID = userDetailsRow.userID,
              otpType = OtpType.ForgotPassword,
              expiresAt = ExpiresAt(instantNow.plusSeconds(100)),
            )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(
            userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPassword)
          )
          _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))
          _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

          forgotPasswordVerifyOTPPostResponse <- gatewayClient
            .forgotPasswordVerifyOTPPost[smithy.BadRequest](
              userOtpRow.otpID,
              Otp.assume("111AAA"), // wrong otp
            )

          _ = forgotPasswordVerifyOTPPostResponse.code shouldBe StatusCode.BadRequest
          _ = forgotPasswordVerifyOTPPostResponse.body.left.value shouldBe smithy.BadRequest()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )

          _ = userDetailsRowsAll should have size 1
          _ = userDetailsRowsAll.head shouldBe userDetailsRow
          _ = userOtpRowsAll should have size 1
          _ = userOtpRowsAll.head shouldBe userOtpRow
          _ = userActionAttemptRowsAll should have size 2

          userActionAttemptRowForgotPasswordResult = userActionAttemptRowsAll
            .filter(_.actionAttemptType == ActionAttemptType.ForgotPassword)
            .head
          userActionAttemptRowForgotPasswordVerifyOTPResult = userActionAttemptRowsAll
            .filter(_.actionAttemptType == ActionAttemptType.ForgotPasswordVerifyOTP)
            .head

          _ = userActionAttemptRowForgotPasswordResult shouldBe userActionAttemptRowForgotPassword

          _ = userActionAttemptRowForgotPasswordVerifyOTPResult shouldBe UserActionAttemptRow(
            actionAttemptID = userActionAttemptRowForgotPasswordVerifyOTPResult.actionAttemptID,
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
            attempts = Attempts.assume(2), // stored one ahead: the next attempt's number
            createdAt = userActionAttemptRowForgotPasswordVerifyOTPResult.createdAt,
            updatedAt = userActionAttemptRowForgotPasswordVerifyOTPResult.updatedAt,
          )

          userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
          userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

          _ = userTokenRowsAll should have size 0
          _ = userCredentialsRowsAll should have size 1
          _ = userCredentialsRowsAll.head shouldBe userCredentialsRow
        } yield ()
      }

      "fail with BadRequest when the OTP is expired, deleting the OTP" in withContext { context =>
        import context.*

        for {
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          onboardStage <- Random.shuffle(OnboardStage.forgotPasswordAllowedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          // Should not delete this action attempt when OTP is expired
          userActionAttemptRowForgotPassword = arbitrarySample[UserActionAttemptRow]
            .copy(
              userID = userDetailsRow.userID,
              actionAttemptType = ActionAttemptType.ForgotPassword,
            )
          userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)
          userOtpRow         = arbitrarySample[UserOtpRow]
            .copy(
              userID = userDetailsRow.userID,
              otpType = OtpType.ForgotPassword,
              expiresAt = ExpiresAt(instantNow.minusSeconds(100)),
            )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(
            userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPassword)
          )
          _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))
          _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

          forgotPasswordVerifyOTPPostResponse <- gatewayClient
            .forgotPasswordVerifyOTPPost[smithy.BadRequest](
              userOtpRow.otpID,
              userOtpRow.otp,
            )

          _ = forgotPasswordVerifyOTPPostResponse.code shouldBe StatusCode.BadRequest
          _ = forgotPasswordVerifyOTPPostResponse.body.left.value shouldBe smithy.BadRequest()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )

          _ = userDetailsRowsAll should have size 1
          _ = userDetailsRowsAll.head shouldBe userDetailsRow
          _ = userOtpRowsAll should have size 0
          _ = userActionAttemptRowsAll should have size 2

          userActionAttemptRowForgotPasswordResult = userActionAttemptRowsAll
            .filter(_.actionAttemptType == ActionAttemptType.ForgotPassword)
            .head
          userActionAttemptRowForgotPasswordVerifyOTPResult = userActionAttemptRowsAll
            .filter(_.actionAttemptType == ActionAttemptType.ForgotPasswordVerifyOTP)
            .head

          _ = userActionAttemptRowForgotPasswordResult shouldBe userActionAttemptRowForgotPassword

          _ = userActionAttemptRowForgotPasswordVerifyOTPResult shouldBe UserActionAttemptRow(
            actionAttemptID = userActionAttemptRowForgotPasswordVerifyOTPResult.actionAttemptID,
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
            attempts = Attempts.assume(2), // stored one ahead: the next attempt's number
            createdAt = userActionAttemptRowForgotPasswordVerifyOTPResult.createdAt,
            updatedAt = userActionAttemptRowForgotPasswordVerifyOTPResult.updatedAt,
          )

          userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
          userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

          _ = userTokenRowsAll should have size 0
          _ = userCredentialsRowsAll should have size 1
          _ = userCredentialsRowsAll.head shouldBe userCredentialsRow
        } yield ()
      }

      "fail with BadRequest and delete the OTP when the verify attempts are over the limit, even with the correct OTP" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.forgotPasswordAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            userActionAttemptRowForgotPassword = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.ForgotPassword,
              )
            userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)
            userOtpRow         = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.ForgotPassword,
                expiresAt = ExpiresAt(instantNow.plusSeconds(100)),
              )
            otpVerifyAttemptsMaxRetries                 = 5 // application.conf otp-verify-attempts-max-retries
            userActionAttemptRowForgotPasswordVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
                attempts = Attempts.assume(otpVerifyAttemptsMaxRetries + 1),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPassword)
            )
            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowForgotPasswordVerifyOTP)
            )

            forgotPasswordVerifyOTPPostResponse <- gatewayClient
              .forgotPasswordVerifyOTPPost[smithy.BadRequest](
                userOtpRow.otpID,
                userOtpRow.otp,
              )

            _ = forgotPasswordVerifyOTPPostResponse.code shouldBe StatusCode.BadRequest
            _ = forgotPasswordVerifyOTPPostResponse.body.left.value shouldBe smithy.BadRequest()

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head shouldBe userDetailsRow

            // OTP is deleted when the verify attempt limit is exceeded
            userOtpRowsAll <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

            _ = userOtpRowsAll should have size 0

            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userActionAttemptRowsAll should have size 2

            userActionAttemptRowForgotPasswordResult = userActionAttemptRowsAll
              .filter(_.actionAttemptType == ActionAttemptType.ForgotPassword)
              .head
            userActionAttemptRowForgotPasswordVerifyOTPResult = userActionAttemptRowsAll
              .filter(_.actionAttemptType == ActionAttemptType.ForgotPasswordVerifyOTP)
              .head

            _ = userActionAttemptRowForgotPasswordResult shouldBe userActionAttemptRowForgotPassword

            _ = userActionAttemptRowForgotPasswordVerifyOTPResult shouldBe userActionAttemptRowForgotPasswordVerifyOTP
              .copy(
                attempts = Attempts.assume(otpVerifyAttemptsMaxRetries + 2),
                updatedAt = userActionAttemptRowForgotPasswordVerifyOTPResult.updatedAt,
              )

            userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
            userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

            _ = userTokenRowsAll should have size 0
            _ = userCredentialsRowsAll should have size 1
            _ = userCredentialsRowsAll.head shouldBe userCredentialsRow
          } yield ()
      }

      "fail with BadRequest when the OTP ID is not recognized" in withContext { context =>
        import context.*

        for {
          otpID = arbitrarySample[OtpID]

          forgotPasswordVerifyOTPPostResponse <- gatewayClient
            .forgotPasswordVerifyOTPPost[smithy.BadRequest](
              otpID = otpID,
              otp = Otp.assume("111AAA"),
            )

          _ = forgotPasswordVerifyOTPPostResponse.code shouldBe StatusCode.BadRequest
          _ = forgotPasswordVerifyOTPPostResponse.body.left.value shouldBe smithy.BadRequest()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
          userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

          _ = userDetailsRowsAll should have size 0
          _ = userOtpRowsAll should have size 0
          _ = userActionAttemptRowsAll should have size 0
          _ = userTokenRowsAll should have size 0
          _ = userCredentialsRowsAll should have size 0
        } yield ()
      }

      "fail with BadRequest ValidationError when the OTP is invalid" in withContext { context =>
        import context.*

        for {
          otpID = arbitrarySample[OtpID]

          forgotPasswordVerifyOTPPostResponse <- gatewayClient
            .forgotPasswordVerifyOTPPost[smithy.ValidationError](
              otpID = otpID,
              otp = Otp.assume("invalid-otp"),
            )

          _ = forgotPasswordVerifyOTPPostResponse.code shouldBe StatusCode.BadRequest
          _ = forgotPasswordVerifyOTPPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("otp"))

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
          userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

          _ = userDetailsRowsAll should have size 0
          _ = userOtpRowsAll should have size 0
          _ = userActionAttemptRowsAll should have size 0
          _ = userTokenRowsAll should have size 0
          _ = userCredentialsRowsAll should have size 0
        } yield ()
      }
    }

    "POST /forgot/password/reset" should {
      "successfully reset the password, delete the reset password token and send a confirmation email" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.forgotPasswordAllowedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))

            resetPasswordJwt <- jwtService.generateResetPasswordToken(userDetailsRow.userID)

            userTokenRow = arbitrarySample[UserTokenRow]
              .copy(
                tokenID = resetPasswordJwt.tokenID,
                userID = userDetailsRow.userID,
                tokenType = TokenType.ResetPasswordToken,
                expiresAt = resetPasswordJwt.expiresAt,
              )

            _ <- postgresClient.executeQuery(userTokenQueries.insertUserToken(userTokenRow))

            passwordNew = arbitrarySample[Password]

            forgotPasswordResetPostResponse <- gatewayClient
              .forgotPasswordResetPost[smithy.InternalServerError](
                resetPasswordJwt.resetPasswordToken,
                passwordNew,
              )

            _ = forgotPasswordResetPostResponse.code shouldBe StatusCode.NoContent

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 1

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )
            userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
            userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head shouldBe userDetailsRow
            _ = userOtpRowsAll should have size 0
            _ = userActionAttemptRowsAll should have size 0
            _ = userTokenRowsAll should have size 0
            _ = userCredentialsRowsAll should have size 1
            _ = userCredentialsRowsAll.head.passwordHash should not be userCredentialsRow.passwordHash
            _ = userCredentialsRowsAll.head shouldBe userCredentialsRow.copy(
              passwordHash = userCredentialsRowsAll.head.passwordHash,
              updatedAt = userCredentialsRowsAll.head.updatedAt,
            )

            isPasswordNewVerified <- passwordService.verifyPassword(
              passwordNew,
              userCredentialsRowsAll.head.passwordHash,
            )

            _ = isPasswordNewVerified shouldBe true
          } yield ()
      }

      "fail with BadRequest ValidationError when the password is invalid, keeping the reset password token usable" in withContext {
        context =>
          import context.*

          for {
            userID = arbitrarySample[UserID]

            resetPasswordJwt <- jwtService.generateResetPasswordToken(userID)

            userTokenRow = arbitrarySample[UserTokenRow]
              .copy(
                tokenID = resetPasswordJwt.tokenID,
                userID = userID,
                tokenType = TokenType.ResetPasswordToken,
                expiresAt = resetPasswordJwt.expiresAt,
              )

            _ <- postgresClient.executeQuery(userTokenQueries.insertUserToken(userTokenRow))

            forgotPasswordResetPostResponse <- gatewayClient
              .forgotPasswordResetPost[smithy.ValidationError](
                resetPasswordToken = resetPasswordJwt.resetPasswordToken,
                password = Password.assume("short"),
              )

            _ = forgotPasswordResetPostResponse.code shouldBe StatusCode.BadRequest
            _ = forgotPasswordResetPostResponse.body.left.value shouldBe smithy.ValidationError(fields =
              List("password")
            )

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )
            userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
            userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

            _ = userDetailsRowsAll should have size 0
            _ = userOtpRowsAll should have size 0
            _ = userActionAttemptRowsAll should have size 0
            _ = userTokenRowsAll shouldBe List(userTokenRow)
            _ = userCredentialsRowsAll should have size 0
          } yield ()
      }

      "fail with Unauthorized when the reset password token is invalid" in withContext { context =>
        import context.*

        for {
          passwordNew = arbitrarySample[Password]

          forgotPasswordResetPostResponse <- gatewayClient
            .forgotPasswordResetPost[smithy.Unauthorized](
              resetPasswordToken = ResetPasswordToken.assume("invalid-reset-password-token"),
              password = passwordNew,
            )

          _ = forgotPasswordResetPostResponse.code shouldBe StatusCode.Unauthorized
          _ = forgotPasswordResetPostResponse.body.left.value shouldBe smithy.Unauthorized()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
          userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

          _ = userDetailsRowsAll shouldBe empty
          _ = userOtpRowsAll shouldBe empty
          _ = userActionAttemptRowsAll shouldBe empty
          _ = userTokenRowsAll shouldBe empty
          _ = userCredentialsRowsAll shouldBe empty
        } yield ()
      }

      "fail with Unauthorized when a refresh token is sent as the reset password token" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.forgotPasswordAllowedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))

          refreshJwt <- jwtService.generateRefreshToken(userDetailsRow.userID)

          // Stored as a reset password token so that only the JWT audience check can reject the request
          userTokenRow = arbitrarySample[UserTokenRow]
            .copy(
              tokenID = refreshJwt.tokenID,
              userID = userDetailsRow.userID,
              tokenType = TokenType.ResetPasswordToken,
            )

          _ <- postgresClient.executeQuery(userTokenQueries.insertUserToken(userTokenRow))

          passwordNew = arbitrarySample[Password]

          forgotPasswordResetPostResponse <- gatewayClient
            .forgotPasswordResetPost[smithy.Unauthorized](
              resetPasswordToken = ResetPasswordToken.assume(refreshJwt.refreshToken.value),
              password = passwordNew,
            )

          _ = forgotPasswordResetPostResponse.code shouldBe StatusCode.Unauthorized
          _ = forgotPasswordResetPostResponse.body.left.value shouldBe smithy.Unauthorized()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
          userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

          _ = userDetailsRowsAll shouldBe List(userDetailsRow)
          _ = userOtpRowsAll shouldBe empty
          _ = userActionAttemptRowsAll shouldBe empty
          _ = userTokenRowsAll shouldBe List(userTokenRow)
          _ = userCredentialsRowsAll shouldBe List(userCredentialsRow)
        } yield ()
      }
      "fail with InternalServerError when the reset password token is valid but not stored" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.forgotPasswordAllowedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          userCredentialsRow = arbitrarySample[UserCredentialsRow].copy(userID = userDetailsRow.userID)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(userCredentialsQueries.insertUserCredentials(userCredentialsRow))

          resetPasswordJwt <- jwtService.generateResetPasswordToken(userDetailsRow.userID)

          passwordNew = arbitrarySample[Password]

          forgotPasswordResetPostResponse <- gatewayClient
            .forgotPasswordResetPost[smithy.InternalServerError](
              resetPasswordToken = resetPasswordJwt.resetPasswordToken,
              password = passwordNew,
            )

          _ = forgotPasswordResetPostResponse.code shouldBe StatusCode.InternalServerError
          _ = forgotPasswordResetPostResponse.body.left.value shouldBe smithy.InternalServerError()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
          userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

          _ = userDetailsRowsAll should have size 1
          _ = userDetailsRowsAll.head shouldBe userDetailsRow
          _ = userOtpRowsAll should have size 0
          _ = userActionAttemptRowsAll should have size 0
          _ = userTokenRowsAll should have size 0
          _ = userCredentialsRowsAll should have size 1
          _ = userCredentialsRowsAll.head shouldBe userCredentialsRow
        } yield ()
      }
    }

    "middleware" should {
      "fail with a Forbidden when the user is not in an onboard stage the endpoint allows on every endpoint" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStageInvalidForgotPassword <- Random
              .shuffle(OnboardStage.values.toList.diff(OnboardStage.forgotPasswordAllowedStages))
              .map(_.head)
            onboardStageInvalidForgotPasswordVerifyOTP <- Random
              .shuffle(OnboardStage.values.toList.diff(OnboardStage.forgotPasswordAllowedStages))
              .map(_.head)
            onboardStageInvalidForgotPasswordReset <- Random
              .shuffle(OnboardStage.values.toList.diff(OnboardStage.forgotPasswordAllowedStages))
              .map(_.head)
            userDetailsRowForgotPassword = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStageInvalidForgotPassword, phoneNumber = None)
            userDetailsRowForgotPasswordVerifyOTP = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStageInvalidForgotPasswordVerifyOTP, phoneNumber = None)
            userDetailsRowForgotPasswordReset = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStageInvalidForgotPasswordReset, phoneNumber = None)
            userOtpRowForgotPasswordVerifyOTP = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRowForgotPasswordVerifyOTP.userID,
                otpType = OtpType.ForgotPassword,
                expiresAt = ExpiresAt(instantNow.plusSeconds(100)),
              )
            userCredentialsRowForgotPasswordReset = arbitrarySample[UserCredentialsRow]
              .copy(userID = userDetailsRowForgotPasswordReset.userID)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRowForgotPassword))
            _ <- postgresClient.executeQuery(
              userDetailsQueries.insertUserDetails(userDetailsRowForgotPasswordVerifyOTP)
            )
            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRowForgotPasswordReset))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRowForgotPasswordVerifyOTP))
            _ <- postgresClient.executeQuery(
              userCredentialsQueries.insertUserCredentials(userCredentialsRowForgotPasswordReset)
            )

            resetPasswordJwt <- jwtService.generateResetPasswordToken(userDetailsRowForgotPasswordReset.userID)

            passwordNew = arbitrarySample[Password]

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.forgotPasswordPost[smithy.Forbidden](userDetailsRowForgotPassword.email),
                gatewayClient.forgotPasswordVerifyOTPPost[smithy.Forbidden](
                  userOtpRowForgotPasswordVerifyOTP.otpID,
                  userOtpRowForgotPasswordVerifyOTP.otp,
                ),
                gatewayClient.forgotPasswordResetPost[smithy.Forbidden](
                  resetPasswordToken = resetPasswordJwt.resetPasswordToken,
                  password = passwordNew,
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(3)(StatusCode.Forbidden)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(3)(Some(smithy.Forbidden()))

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )
            userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
            userCredentialsRowsAll <- postgresClient.executeQuery(
              userCredentialsQueries.getAllUserCredentialsTesting
            )

            _ = userDetailsRowsAll.toSet shouldBe Set(
              userDetailsRowForgotPassword,
              userDetailsRowForgotPasswordVerifyOTP,
              userDetailsRowForgotPasswordReset,
            )
            _ = userOtpRowsAll shouldBe List(userOtpRowForgotPasswordVerifyOTP)
            _ = userActionAttemptRowsAll shouldBe empty
            _ = userTokenRowsAll shouldBe empty
            _ = userCredentialsRowsAll shouldBe List(userCredentialsRowForgotPasswordReset)
          } yield ()
      }

      "fail with an InternalServerError when the user details do not exist on every endpoint that loads the user by ID" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            userID     = arbitrarySample[UserID]
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userID,
                otpType = OtpType.ForgotPassword,
                expiresAt = ExpiresAt(instantNow.plusSeconds(100)),
              )

            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

            resetPasswordJwt <- jwtService.generateResetPasswordToken(userID)

            passwordNew = arbitrarySample[Password]

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.forgotPasswordVerifyOTPPost[smithy.InternalServerError](
                  userOtpRow.otpID,
                  userOtpRow.otp,
                ),
                gatewayClient.forgotPasswordResetPost[smithy.InternalServerError](
                  resetPasswordToken = resetPasswordJwt.resetPasswordToken,
                  password = passwordNew,
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(2)(StatusCode.InternalServerError)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(2)(Some(smithy.InternalServerError()))

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )
            userTokenRowsAll       <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)
            userCredentialsRowsAll <- postgresClient.executeQuery(
              userCredentialsQueries.getAllUserCredentialsTesting
            )

            _ = userDetailsRowsAll shouldBe empty
            _ = userOtpRowsAll shouldBe List(userOtpRow)
            _ = userActionAttemptRowsAll shouldBe empty
            _ = userTokenRowsAll shouldBe empty
            _ = userCredentialsRowsAll shouldBe empty
          } yield ()
      }
    }
  }
}
