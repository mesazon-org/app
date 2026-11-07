package io.mesazon.gateway.it

import com.dimafeng.testcontainers.DockerComposeContainer
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.given
import io.mesazon.gateway.it.harness.GatewayAcceptanceTest
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.{RepositoryArbitraries, SmithyArbitraries, UserSignUpSmithyArbitraries}
import io.mesazon.testkit.base.IronRefinedTypeTransformer
import org.scalatest.DoNotDiscover
import sttp.model.*
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

@DoNotDiscover
class UserSignUpApiSpec(dockerComposeContainer: => DockerComposeContainer)
    extends GatewayAcceptanceTest(dockerComposeContainer),
      SmithyArbitraries,
      UserSignUpSmithyArbitraries,
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
          repositoryConfig.userOtpTable,
          repositoryConfig.userActionAttemptTable,
          repositoryConfig.userTokenTable,
        )
        _ <- mailHogClient.clearInbox()
      } yield ()
    }
  }

  "User Signup API" when {
    "POST /signup/email" should {
      "successfully sign up a new user with valid email" in withContext { context =>
        import context.*

        for {
          signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]

          signUpEmailPostResponse <- gatewayClient.signUpEmailPost[smithy.InternalServerError](signUpEmailPostRequest)

          _ = signUpEmailPostResponse.code shouldBe StatusCode.Ok
          _ = signUpEmailPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 1

          userDetailsRowsAll <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll     <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

          _ = userDetailsRowsAll should have size 1
          _ = userDetailsRowsAll should contain theSameElementsAs List(
            UserDetailsRow(
              userID = userDetailsRowsAll.head.userID,
              email = Email.assume(signUpEmailPostRequest.email),
              fullName = None,
              phoneNumber = None,
              onboardStage = OnboardStage.EmailVerification,
              createdAt = userDetailsRowsAll.head.createdAt,
              updatedAt = userDetailsRowsAll.head.updatedAt,
            )
          )
          _ = userOtpRowsAll should have size 1
          _ = userOtpRowsAll should contain theSameElementsAs List(
            UserOtpRow(
              otpID = OtpID.assume(signUpEmailPostResponse.body.value.otpID),
              userID = userDetailsRowsAll.head.userID,
              otp = userOtpRowsAll.head.otp,
              otpType = OtpType.EmailVerification,
              createdAt = userOtpRowsAll.head.createdAt,
              updatedAt = userOtpRowsAll.head.updatedAt,
              expiresAt = userOtpRowsAll.head.expiresAt,
            )
          )
        } yield ()
      }

      "successfully re-sign up an existing user whose OTP is not about to expire, reusing the OTP code without sending an email or resetting the verify attempts counter" in withContext {
        context =>
          import context.*

          for {
            signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]

            signUpEmailPostResponse1 <- gatewayClient.signUpEmailPost[smithy.InternalServerError](
              signUpEmailPostRequest
            )

            _ = signUpEmailPostResponse1.code shouldBe StatusCode.Ok
            _ = signUpEmailPostResponse1.body.value.otpExpiresInSeconds shouldBe 45 // application.conf

            mailHogResponse1 <- mailHogClient.readInbox()

            _ = mailHogResponse1.total shouldBe 1

            userOtpRowsAll1 <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

            _ = userOtpRowsAll1 should have size 1

            // Must survive the resend below, because the OTP is reused rather than genuinely new
            userActionAttemptRowEmailVerificationVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userOtpRowsAll1.head.userID,
                actionAttemptType = ActionAttemptType.EmailVerificationVerifyOTP,
              )

            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowEmailVerificationVerifyOTP)
            )

            signUpEmailPostResponse2 <- gatewayClient.signUpEmailPost[smithy.InternalServerError](
              signUpEmailPostRequest
            )

            _ = signUpEmailPostResponse2.code shouldBe StatusCode.Ok
            _ = signUpEmailPostResponse2.body.value.otpExpiresInSeconds shouldBe 45 // application.conf
            // Reuse always issues a fresh id via upsertUserOtp, even though the code itself is unchanged.
            _ = signUpEmailPostResponse2.body.value.otpID should not be signUpEmailPostResponse1.body.value.otpID

            mailHogResponse2 <- mailHogClient.readInbox()

            _ = mailHogResponse2.total shouldBe 1 // only the first sign up's email, the reused OTP is not resent

            userDetailsRowsAll <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll2    <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll should contain theSameElementsAs List(
              UserDetailsRow(
                userID = userDetailsRowsAll.head.userID,
                email = Email.assume(signUpEmailPostRequest.email),
                fullName = None,
                phoneNumber = None,
                onboardStage = OnboardStage.EmailVerification,
                createdAt = userDetailsRowsAll.head.createdAt,
                updatedAt = userDetailsRowsAll.head.updatedAt,
              )
            )
            _ = userOtpRowsAll2 should have size 1
            _ = assert(userOtpRowsAll1.head.expiresAt.value.isBefore(userOtpRowsAll2.head.expiresAt.value))
            // Reusing an OTP that is not about to expire and is under its resend-attempts limit keeps the code
            // unchanged, but upsertUserOtp always issues a fresh id and createdAt, even on reuse.
            _ = userOtpRowsAll2.head.otpID should not be userOtpRowsAll1.head.otpID
            _ = userOtpRowsAll2.head.createdAt should not be userOtpRowsAll1.head.createdAt
            _ = userOtpRowsAll2 should contain theSameElementsAs List(
              UserOtpRow(
                otpID = OtpID.assume(signUpEmailPostResponse2.body.value.otpID),
                userID = userDetailsRowsAll.head.userID,
                otp = userOtpRowsAll1.head.otp,
                otpType = OtpType.EmailVerification,
                createdAt = userOtpRowsAll2.head.createdAt,
                updatedAt = userOtpRowsAll2.head.updatedAt,
                expiresAt = userOtpRowsAll2.head.expiresAt,
              )
            )

            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            // The pre-seeded verify-attempts counter survives untouched, plus a fresh resend-attempts counter
            // (EmailVerificationOtpLifetime) is created for this, its first-ever resend.
            _ = userActionAttemptRowsAll should have size 2
            _ = userActionAttemptRowsAll should contain(userActionAttemptRowEmailVerificationVerifyOTP)

            userActionAttemptRowEmailVerificationOtpLifetime = userActionAttemptRowsAll
              .filterNot(_ == userActionAttemptRowEmailVerificationVerifyOTP)
              .head

            _ = userActionAttemptRowEmailVerificationOtpLifetime shouldBe UserActionAttemptRow(
              actionAttemptID = userActionAttemptRowEmailVerificationOtpLifetime.actionAttemptID,
              userID = userDetailsRowsAll.head.userID,
              actionAttemptType = ActionAttemptType.EmailVerificationOtpLifetime,
              attempts = Attempts.assume(2), // stored one ahead: the next attempt's number
              createdAt = userActionAttemptRowEmailVerificationOtpLifetime.createdAt,
              updatedAt = userActionAttemptRowEmailVerificationOtpLifetime.updatedAt,
            )
          } yield ()
      }

      "successfully re-sign up an existing user whose OTP is about to expire with a new OTP and email, resetting the verify attempts counter" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signUpEmailStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)
            // Expires in 5s, inside the 15s resend cooldown before expiry, forcing the genuinely-new-OTP branch
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.EmailVerification,
                expiresAt = ExpiresAt(instantNow.plusSeconds(5)),
              )

            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

            userActionAttemptRowEmailVerificationVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.EmailVerificationVerifyOTP,
              )

            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowEmailVerificationVerifyOTP)
            )

            signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
              .copy(email = userDetailsRow.email.value)

            signUpEmailPostResponse <- gatewayClient.signUpEmailPost[smithy.InternalServerError](
              signUpEmailPostRequest
            )

            _ = signUpEmailPostResponse.code shouldBe StatusCode.Ok
            _ = signUpEmailPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 1

            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userOtpRowsAll should have size 1
            _ = userOtpRowsAll.head.otp should not be userOtpRow.otp
            _ = userOtpRowsAll.head.otpID.value shouldBe signUpEmailPostResponse.body.value.otpID
            _ = userActionAttemptRowsAll should have size 0
          } yield ()
      }

      "successfully re-sign up a user reusing the OTP when resend attempts are at the limit but not exceeding it" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signUpEmailStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)
            // Expires in 40s, outside the 15s resend cooldown before expiry, so only the resend-attempts count
            // decides this case.
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.EmailVerification,
                expiresAt = ExpiresAt(instantNow.plusSeconds(40)),
              )

            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

            // getAndIncreaseUserActionAttempt returns the pre-existing row as-is (see UserActionAttemptRepositorySpec),
            // so seeding exactly at the limit (5, application.conf otp-email-verification-resend-attempts-max-retries)
            // proves the boundary is inclusive: still a reuse, not yet genuinely new.
            userActionAttemptRowEmailVerificationOtpLifetime = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.EmailVerificationOtpLifetime,
                attempts = Attempts.assume(5),
              )

            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowEmailVerificationOtpLifetime)
            )

            signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
              .copy(email = userDetailsRow.email.value)

            signUpEmailPostResponse <- gatewayClient.signUpEmailPost[smithy.InternalServerError](
              signUpEmailPostRequest
            )

            _ = signUpEmailPostResponse.code shouldBe StatusCode.Ok
            _ = signUpEmailPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0 // the OTP is reused, so nothing is sent

            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userOtpRowsAll should have size 1
            _ = userOtpRowsAll.head.otpID should not be userOtpRow.otpID
            _ = userOtpRowsAll.head.otp shouldBe userOtpRow.otp
            _ = userOtpRowsAll.head.otpID.value shouldBe signUpEmailPostResponse.body.value.otpID
            _ = userActionAttemptRowsAll should have size 1
            _ = userActionAttemptRowsAll.head shouldBe userActionAttemptRowEmailVerificationOtpLifetime.copy(
              attempts = Attempts.assume(6), // increased by 1 via getAndIncreaseUserActionAttempt's own upsert
              updatedAt = userActionAttemptRowsAll.head.updatedAt,
            )
          } yield ()
      }

      "successfully re-sign up an existing user with a new OTP and email, resetting both attempt counters, when the resend-attempts limit is exceeded even though the OTP is not about to expire" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signUpEmailStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)
            // Expires in 40s, outside the 15s resend cooldown before expiry, so the expiry check alone would have
            // reused this OTP. But its resend-attempts counter is already past the limit, so it must now be
            // treated exactly as an expired OTP. createdAt is pinned safely in the past so the freshly-issued
            // row's real createdAt can never collide with it at millisecond resolution.
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.EmailVerification,
                createdAt = CreatedAt(instantNow.minusSeconds(1)),
                expiresAt = ExpiresAt(instantNow.plusSeconds(40)),
              )

            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

            userActionAttemptRowEmailVerificationVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.EmailVerificationVerifyOTP,
              )

            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowEmailVerificationVerifyOTP)
            )

            // getAndIncreaseUserActionAttempt returns the pre-existing row as-is (see UserActionAttemptRepositorySpec),
            // so seeding one above the limit (6, application.conf otp-email-verification-resend-attempts-max-retries
            // = 5) is what makes this call the one that treats the OTP as genuinely new.
            userActionAttemptRowEmailVerificationOtpLifetime = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.EmailVerificationOtpLifetime,
                attempts = Attempts.assume(6),
              )

            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowEmailVerificationOtpLifetime)
            )

            signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
              .copy(email = userDetailsRow.email.value)

            signUpEmailPostResponse <- gatewayClient.signUpEmailPost[smithy.InternalServerError](
              signUpEmailPostRequest
            )

            _ = signUpEmailPostResponse.code shouldBe StatusCode.Ok
            _ = signUpEmailPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 1

            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userOtpRowsAll should have size 1
            _ = userOtpRowsAll.head.otpID should not be userOtpRow.otpID
            _ = userOtpRowsAll.head.otp should not be userOtpRow.otp
            _ = userOtpRowsAll.head.createdAt should not be userOtpRow.createdAt
            _ = userOtpRowsAll.head.otpID.value shouldBe signUpEmailPostResponse.body.value.otpID
            _ = userActionAttemptRowsAll should have size 0
          } yield ()
      }

      "successfully re-sign up an EmailVerified user and reset them to the email verification stage" in withContext {
        context =>
          import context.*

          for {
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = OnboardStage.EmailVerified)
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)
            // Expires in 40s, outside the 15s resend cooldown before expiry, so the OTP code is reused
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.EmailVerification,
                expiresAt = ExpiresAt(instantNow.plusSeconds(40)),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

            signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
              .copy(email = userDetailsRow.email.value)

            signUpEmailPostResponse <- gatewayClient.signUpEmailPost[smithy.InternalServerError](
              signUpEmailPostRequest
            )

            _ = signUpEmailPostResponse.code shouldBe StatusCode.Ok
            _ = signUpEmailPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0 // the OTP is reused, so nothing is sent

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll shouldBe List(
              userDetailsRow.copy(
                onboardStage = OnboardStage.EmailVerification,
                updatedAt = userDetailsRowsAll.head.updatedAt,
              )
            )
            _ = userOtpRowsAll should have size 1
            _ = userOtpRowsAll.head.otpID should not be userOtpRow.otpID
            _ = userOtpRowsAll.head.otpID.value shouldBe signUpEmailPostResponse.body.value.otpID
            _ = userOtpRowsAll.head.otp shouldBe userOtpRow.otp
            _ = userActionAttemptRowsAll shouldBe List(
              UserActionAttemptRow(
                actionAttemptID = userActionAttemptRowsAll.head.actionAttemptID,
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.EmailVerificationOtpLifetime,
                attempts = Attempts.assume(2), // stored one ahead: the next attempt's number
                createdAt = userActionAttemptRowsAll.head.createdAt,
                updatedAt = userActionAttemptRowsAll.head.updatedAt,
              )
            )
          } yield ()
      }

      "successfully re-sign up an existing user without an OTP with a new OTP and email" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.signUpEmailStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
            .copy(email = userDetailsRow.email.value)

          signUpEmailPostResponse <- gatewayClient.signUpEmailPost[smithy.InternalServerError](signUpEmailPostRequest)

          _ = signUpEmailPostResponse.code shouldBe StatusCode.Ok
          _ = signUpEmailPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 1

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )

          _ = userDetailsRowsAll shouldBe List(
            userDetailsRow.copy(
              onboardStage = OnboardStage.EmailVerification,
              updatedAt = userDetailsRowsAll.head.updatedAt,
            )
          )
          _ = userOtpRowsAll shouldBe List(
            UserOtpRow(
              otpID = OtpID.assume(signUpEmailPostResponse.body.value.otpID),
              userID = userDetailsRow.userID,
              otp = userOtpRowsAll.head.otp,
              otpType = OtpType.EmailVerification,
              createdAt = userOtpRowsAll.head.createdAt,
              updatedAt = userOtpRowsAll.head.updatedAt,
              expiresAt = userOtpRowsAll.head.expiresAt,
            )
          )
          _ = userActionAttemptRowsAll shouldBe empty
        } yield ()
      }

      "successfully re-sign up an existing user when the email differs only in case and surrounding whitespace" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signUpEmailStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
              .copy(email = s"  ${userDetailsRow.email.value.toUpperCase}  ")

            signUpEmailPostResponse <- gatewayClient.signUpEmailPost[smithy.InternalServerError](
              signUpEmailPostRequest
            )

            _ = signUpEmailPostResponse.code shouldBe StatusCode.Ok
            _ = signUpEmailPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 1

            userDetailsRowsAll <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll     <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head.userID shouldBe userDetailsRow.userID
            _ = userDetailsRowsAll.head.email shouldBe userDetailsRow.email
            _ = userOtpRowsAll should have size 1
            _ = userOtpRowsAll.head.userID shouldBe userDetailsRow.userID
            _ = userOtpRowsAll.head.otpID.value shouldBe signUpEmailPostResponse.body.value.otpID
          } yield ()
      }

      "successfully return a fake OTP ID without sending an email or writing anything when the user is past the sign-up stages" in withContext {
        context =>
          import context.*

          for {
            onboardStageInvalid <- Random
              .shuffle(OnboardStage.values.toList diff OnboardStage.signUpEmailStages)
              .map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStageInvalid)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
              .copy(email = userDetailsRow.email.value)

            signUpEmailPostResponse <- gatewayClient.signUpEmailPost[smithy.InternalServerError](
              signUpEmailPostRequest
            )

            _ = signUpEmailPostResponse.code shouldBe StatusCode.Ok
            _ = signUpEmailPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll should contain theSameElementsAs List(userDetailsRow)
            _ = userOtpRowsAll should have size 0
            _ = userActionAttemptRowsAll shouldBe empty
          } yield ()
      }

      "fail with BadRequest ValidationError when the email is invalid" in withContext { context =>
        import context.*

        for {
          signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]
            .copy(email = "invalidemail")

          signUpEmailPostResponse <- gatewayClient.signUpEmailPost[smithy.ValidationError](signUpEmailPostRequest)

          _ = signUpEmailPostResponse.code shouldBe StatusCode.BadRequest
          _ = signUpEmailPostResponse.body.left.value shouldBe smithy.ValidationError(
            fields = List("email")
          )

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll     <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

          _ = userDetailsRowsAll shouldBe empty
          _ = userOtpRowsAll shouldBe empty
        } yield ()
      }
    }

    "POST /signup/verify/email" should {
      "successfully verify the email with the correct OTP, mark the user as email verified, delete the OTP and store a new refresh token" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signUpVerifyEmailStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.EmailVerification,
                expiresAt = ExpiresAt.assume(instantNow.plusSeconds(10)),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

            signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
              .copy(
                otpID = userOtpRow.otpID.value,
                otp = userOtpRow.otp.value,
              )

            signUpVerifyEmailPostResponse <- gatewayClient.signUpVerifyEmailPost[smithy.InternalServerError](
              signUpVerifyEmailPostRequest
            )

            _ = signUpVerifyEmailPostResponse.code shouldBe StatusCode.Ok
            _ = signUpVerifyEmailPostResponse.body.value.accessTokenExpiresInSeconds shouldBe 15.minutes.toSeconds
            _ = signUpVerifyEmailPostResponse.body.value.onboardStage shouldBe smithy.OnboardStage.EmailVerified

            authedUserRefresh <- jwtService.verifyRefreshToken(
              RefreshToken.assume(signUpVerifyEmailPostResponse.body.value.refreshToken)
            )

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll     <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userTokenRowsAll   <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            _ = userDetailsRowsAll shouldBe List(
              userDetailsRow.copy(
                onboardStage = OnboardStage.EmailVerified,
                updatedAt = userDetailsRowsAll.head.updatedAt,
              )
            )
            _ = userOtpRowsAll shouldBe empty
            _ = userTokenRowsAll shouldBe List(
              UserTokenRow(
                tokenID = authedUserRefresh.tokenID,
                userID = userDetailsRow.userID,
                tokenType = TokenType.RefreshToken,
                createdAt = userTokenRowsAll.head.createdAt,
                expiresAt = userTokenRowsAll.head.expiresAt,
              )
            )
          } yield ()
      }

      "successfully verify the email and replace the user's existing tokens with a single new refresh token" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signUpVerifyEmailStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.EmailVerification,
                expiresAt = ExpiresAt.assume(instantNow.plusSeconds(10)),
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

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))
            _ <- postgresClient.executeQuery(userTokenQueries.insertUserToken(userTokenRowRefreshToken))
            _ <- postgresClient.executeQuery(userTokenQueries.insertUserToken(userTokenRowResetPasswordToken))

            signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
              .copy(
                otpID = userOtpRow.otpID.value,
                otp = userOtpRow.otp.value,
              )

            signUpVerifyEmailPostResponse <- gatewayClient.signUpVerifyEmailPost[smithy.InternalServerError](
              signUpVerifyEmailPostRequest
            )

            _ = signUpVerifyEmailPostResponse.code shouldBe StatusCode.Ok

            authedUserRefresh <- jwtService.verifyRefreshToken(
              RefreshToken.assume(signUpVerifyEmailPostResponse.body.value.refreshToken)
            )

            _ = authedUserRefresh.tokenID shouldNot equal(userTokenRowRefreshToken.tokenID)
            _ = authedUserRefresh.tokenID shouldNot equal(userTokenRowResetPasswordToken.tokenID)

            userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            _ = userTokenRowsAll shouldBe List(
              UserTokenRow(
                tokenID = authedUserRefresh.tokenID,
                userID = userDetailsRow.userID,
                tokenType = TokenType.RefreshToken,
                createdAt = userTokenRowsAll.head.createdAt,
                expiresAt = userTokenRowsAll.head.expiresAt,
              )
            )
          } yield ()
      }

      "successfully verify email with valid OTP on the last allowed attempt and delete the wrong-attempt counter" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signUpVerifyEmailStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.EmailVerification,
                expiresAt = ExpiresAt.assume(instantNow.plusSeconds(10)),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

            otpVerifyAttemptsMaxRetries                    = 5 // application.conf otp-verify-attempts-max-retries
            userActionAttemptRowEmailVerificationVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.EmailVerificationVerifyOTP,
                attempts = Attempts.assume(otpVerifyAttemptsMaxRetries),
              )

            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowEmailVerificationVerifyOTP)
            )

            signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
              .copy(
                otpID = userOtpRow.otpID.value,
                otp = userOtpRow.otp.value,
              )

            signUpVerifyEmailPostResponse <- gatewayClient.signUpVerifyEmailPost[smithy.InternalServerError](
              signUpVerifyEmailPostRequest
            )

            _ = signUpVerifyEmailPostResponse.code shouldBe StatusCode.Ok

            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userActionAttemptRowsAll should have size 0
          } yield ()
      }

      "fail with BadRequest ValidationError when the OTP is invalid" in withContext { context =>
        import context.*

        for {
          signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
            .copy(otp = "invalidotp")

          signUpVerifyEmailPostResponse <- gatewayClient.signUpVerifyEmailPost[smithy.ValidationError](
            signUpVerifyEmailPostRequest
          )

          _ = signUpVerifyEmailPostResponse.code shouldBe StatusCode.BadRequest
          _ = signUpVerifyEmailPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("otp"))

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userActionAttemptRowsAll shouldBe empty
          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }

      "fail with BadRequest when the OTP is wrong, keeping the OTP usable" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.signUpVerifyEmailStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)
          userOtpRow = arbitrarySample[UserOtpRow]
            .copy(
              userID = userDetailsRow.userID,
              otpType = OtpType.EmailVerification,
              expiresAt = ExpiresAt.assume(instantNow.plusSeconds(10)),
            )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

          signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
            .copy(
              otpID = userOtpRow.otpID.value,
              otp = "123ABC", // wrong otp
            )

          signUpVerifyEmailPostResponse <- gatewayClient.signUpVerifyEmailPost[smithy.BadRequest](
            signUpVerifyEmailPostRequest
          )

          _ = signUpVerifyEmailPostResponse.code shouldBe StatusCode.BadRequest
          _ = signUpVerifyEmailPostResponse.body.left.value shouldBe smithy.BadRequest()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userDetailsRowsAll should have size 1
          _ = userDetailsRowsAll should contain theSameElementsAs List(userDetailsRow)
          _ = userOtpRowsAll should have size 1
          _ = userOtpRowsAll should contain theSameElementsAs List(userOtpRow)
          _ = userActionAttemptRowsAll should have size 1
          _ = userActionAttemptRowsAll.head shouldBe UserActionAttemptRow(
            actionAttemptID = userActionAttemptRowsAll.head.actionAttemptID,
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.EmailVerificationVerifyOTP,
            attempts = Attempts.assume(2), // stored one ahead: the next attempt's number
            createdAt = userActionAttemptRowsAll.head.createdAt,
            updatedAt = userActionAttemptRowsAll.head.updatedAt,
          )
          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }

      "fail with BadRequest and delete the OTP when it is expired" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.signUpVerifyEmailStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)
          userOtpRow = arbitrarySample[UserOtpRow]
            .copy(
              userID = userDetailsRow.userID,
              otpType = OtpType.EmailVerification,
              expiresAt = ExpiresAt.assume(instantNow.minusSeconds(10)),
            )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

          signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
            .copy(
              otpID = userOtpRow.otpID.value,
              otp = userOtpRow.otp.value,
            )

          signUpVerifyEmailPostResponse <- gatewayClient.signUpVerifyEmailPost[smithy.BadRequest](
            signUpVerifyEmailPostRequest
          )

          _ = signUpVerifyEmailPostResponse.code shouldBe StatusCode.BadRequest
          _ = signUpVerifyEmailPostResponse.body.left.value shouldBe smithy.BadRequest()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userDetailsRowsAll should contain theSameElementsAs List(userDetailsRow)
          _ = userOtpRowsAll should have size 0
          _ = userActionAttemptRowsAll should have size 1
          _ = userActionAttemptRowsAll.head shouldBe UserActionAttemptRow(
            actionAttemptID = userActionAttemptRowsAll.head.actionAttemptID,
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.EmailVerificationVerifyOTP,
            attempts = Attempts.assume(2), // stored one ahead: the next attempt's number
            createdAt = userActionAttemptRowsAll.head.createdAt,
            updatedAt = userActionAttemptRowsAll.head.updatedAt,
          )
          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }

      "fail with BadRequest and delete the OTP when the verify-attempts limit is exceeded, even with the correct OTP" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.signUpVerifyEmailStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.EmailVerification,
                expiresAt = ExpiresAt.assume(instantNow.plusSeconds(100)),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

            // getAndIncreaseUserActionAttempt returns the pre-existing row as-is (see UserActionAttemptRepositorySpec),
            // so seeding one above the limit (6, application.conf otp-verify-attempts-max-retries = 5) is what makes
            // this call the one that exceeds it
            userActionAttemptRowEmailVerificationVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.EmailVerificationVerifyOTP,
                attempts = Attempts.assume(6),
              )

            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowEmailVerificationVerifyOTP)
            )

            // The correct OTP is submitted after the limit is exceeded and must still be rejected without being checked
            signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
              .copy(
                otpID = userOtpRow.otpID.value,
                otp = userOtpRow.otp.value,
              )

            signUpVerifyEmailPostResponse <- gatewayClient.signUpVerifyEmailPost[smithy.BadRequest](
              signUpVerifyEmailPostRequest
            )

            _ = signUpVerifyEmailPostResponse.code shouldBe StatusCode.BadRequest
            _ = signUpVerifyEmailPostResponse.body.left.value shouldBe smithy.BadRequest()

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )
            userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head shouldBe userDetailsRow
            _ = userOtpRowsAll should have size 0
            _ = userActionAttemptRowsAll should have size 1
            _ = userActionAttemptRowsAll.head shouldBe userActionAttemptRowEmailVerificationVerifyOTP.copy(
              attempts = Attempts.assume(7), // attempts should be increased by 1
              updatedAt = userActionAttemptRowsAll.head.updatedAt,
            )
            _ = userTokenRowsAll shouldBe empty
          } yield ()
      }

      "fail with BadRequest when the OTP ID is not recognized" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.signUpVerifyEmailStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]

          signUpVerifyEmailPostResponse <- gatewayClient.signUpVerifyEmailPost[smithy.BadRequest](
            signUpVerifyEmailPostRequest
          )

          _ = signUpVerifyEmailPostResponse.code shouldBe StatusCode.BadRequest
          _ = signUpVerifyEmailPostResponse.body.left.value shouldBe smithy.BadRequest()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userDetailsRowsAll should contain theSameElementsAs List(userDetailsRow)
          _ = userOtpRowsAll should have size 0
          _ = userActionAttemptRowsAll shouldBe empty
          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }

      "fail with BadRequest when the OTP ID belongs to a non-email-verification OTP" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.signUpVerifyEmailStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)
          instantNow               = Instant.now.truncatedTo(ChronoUnit.MILLIS)
          userOtpRowForgotPassword = arbitrarySample[UserOtpRow]
            .copy(
              userID = userDetailsRow.userID,
              otpType = OtpType.ForgotPassword,
              expiresAt = ExpiresAt.assume(instantNow.plusSeconds(10)),
            )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRowForgotPassword))

          signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
            .copy(
              otpID = userOtpRowForgotPassword.otpID.value,
              otp = userOtpRowForgotPassword.otp.value,
            )

          signUpVerifyEmailPostResponse <- gatewayClient.signUpVerifyEmailPost[smithy.BadRequest](
            signUpVerifyEmailPostRequest
          )

          _ = signUpVerifyEmailPostResponse.code shouldBe StatusCode.BadRequest
          _ = signUpVerifyEmailPostResponse.body.left.value shouldBe smithy.BadRequest()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userDetailsRowsAll should contain theSameElementsAs List(userDetailsRow)
          _ = userOtpRowsAll should contain theSameElementsAs List(userOtpRowForgotPassword)
          _ = userActionAttemptRowsAll shouldBe empty
          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }

      "fail with Forbidden when user is not in an allowed onboard stage" in withContext { context =>
        import context.*

        for {
          onboardStageInvalid <- Random
            .shuffle(OnboardStage.values.toList diff OnboardStage.signUpVerifyEmailStages)
            .map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStageInvalid)
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)
          userOtpRow = arbitrarySample[UserOtpRow]
            .copy(
              userID = userDetailsRow.userID,
              otpType = OtpType.EmailVerification,
              expiresAt = ExpiresAt.assume(instantNow.plusSeconds(10)),
            )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

          signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
            .copy(
              otpID = userOtpRow.otpID.value,
              otp = userOtpRow.otp.value,
            )

          signUpVerifyEmailPostResponse <- gatewayClient.signUpVerifyEmailPost[smithy.Forbidden](
            signUpVerifyEmailPostRequest
          )

          _ = signUpVerifyEmailPostResponse.code shouldBe StatusCode.Forbidden
          _ = signUpVerifyEmailPostResponse.body.left.value shouldBe smithy.Forbidden()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userDetailsRowsAll should contain theSameElementsAs List(userDetailsRow)
          _ = userOtpRowsAll should contain theSameElementsAs List(userOtpRow)
          _ = userActionAttemptRowsAll shouldBe empty
          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }

      "fail with InternalServerError when the OTP's user details are missing" in withContext { context =>
        import context.*

        for {
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)
          // No user_details row is inserted for this OTP's userID
          userOtpRow = arbitrarySample[UserOtpRow]
            .copy(
              otpType = OtpType.EmailVerification,
              expiresAt = ExpiresAt.assume(instantNow.plusSeconds(10)),
            )

          _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

          signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest]
            .copy(
              otpID = userOtpRow.otpID.value,
              otp = userOtpRow.otp.value,
            )

          signUpVerifyEmailPostResponse <- gatewayClient.signUpVerifyEmailPost[smithy.InternalServerError](
            signUpVerifyEmailPostRequest
          )

          _ = signUpVerifyEmailPostResponse.code shouldBe StatusCode.InternalServerError
          _ = signUpVerifyEmailPostResponse.body.left.value shouldBe smithy.InternalServerError()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )
          userTokenRowsAll <- postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting)

          _ = userDetailsRowsAll shouldBe empty
          _ = userOtpRowsAll should contain theSameElementsAs List(userOtpRow)
          _ = userActionAttemptRowsAll shouldBe empty
          _ = userTokenRowsAll shouldBe empty
        } yield ()
      }
    }
  }
}
