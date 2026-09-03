package io.mesazon.gateway.it

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
class UserSignUpApiSpec
    extends GatewayAcceptanceTest,
      SmithyArbitraries,
      UserSignUpSmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  "User Signup API" when {
    "POST /signup/email" should {
      "successfully sign up a new user with valid email" in withContext { context =>
        import context.*

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]

        val signUpEmailPostResponse =
          gatewayClient.signUpEmailPost[smithy.InternalServerError](signUpEmailPostRequest).zioValue

        signUpEmailPostResponse.code shouldBe StatusCode.Ok
        signUpEmailPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf

        mailHogClient.readInbox().zioValue.total shouldBe 1

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1

        userDetailsRowsAll should contain theSameElementsAs List(
          UserDetailsRow(
            userID = userDetailsRowsAll.head.userID,
            email = userDetailsRowsAll.head.email,
            fullName = None,
            phoneNumber = None,
            onboardStage = OnboardStage.EmailVerification,
            createdAt = userDetailsRowsAll.head.createdAt,
            updatedAt = userDetailsRowsAll.head.updatedAt,
          )
        )

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 1
        userOtpRowsAll should contain theSameElementsAs List(
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
      }

      "successfully re-sign up a user already seen user with stages before completion" in withContext { context =>
        import context.*

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest]

        val signUpEmailPostResponse1 =
          gatewayClient.signUpEmailPost[smithy.InternalServerError](signUpEmailPostRequest).zioValue

        signUpEmailPostResponse1.code shouldBe StatusCode.Ok
        signUpEmailPostResponse1.body.value.otpExpiresInSeconds shouldBe 45 // application.conf

        mailHogClient.readInbox().zioValue.total shouldBe 1

        val userOtpRowsAll1 = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        // Should not reset this action attempt when the OTP is reused within its resend cooldown
        val userActionAttemptRowEmailVerificationVerifyOTP = arbitrarySample[UserActionAttemptRow].copy(
          userID = userOtpRowsAll1.head.userID,
          actionAttemptType = ActionAttemptType.EmailVerificationVerifyOTP,
        )

        postgresClient
          .executeQuery(
            userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowEmailVerificationVerifyOTP)
          )
          .zioValue

        val signUpEmailPostResponse2 =
          gatewayClient.signUpEmailPost[smithy.InternalServerError](signUpEmailPostRequest).zioValue

        signUpEmailPostResponse2.code shouldBe StatusCode.Ok
        signUpEmailPostResponse2.body.value.otpExpiresInSeconds shouldBe 45 // application.conf

        mailHogClient.readInbox().zioValue.total shouldBe 1

        signUpEmailPostResponse2.body.value.otpID should not be signUpEmailPostResponse1.body.value.otpID

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll should contain theSameElementsAs List(
          UserDetailsRow(
            userID = userDetailsRowsAll.head.userID,
            email = userDetailsRowsAll.head.email,
            fullName = None,
            phoneNumber = None,
            onboardStage = OnboardStage.EmailVerification,
            createdAt = userDetailsRowsAll.head.createdAt,
            updatedAt = userDetailsRowsAll.head.updatedAt,
          )
        )

        val userOtpRowsAll2 = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll1 should have size 1
        userOtpRowsAll2 should have size 1

        assert(userOtpRowsAll1.head.expiresAt.value.isBefore(userOtpRowsAll2.head.expiresAt.value))

        userOtpRowsAll2 should contain theSameElementsAs List(
          UserOtpRow(
            otpID = OtpID.assume(signUpEmailPostResponse2.body.value.otpID),
            userID = userDetailsRowsAll.head.userID,
            otp = userOtpRowsAll2.head.otp,
            otpType = OtpType.EmailVerification,
            createdAt = userOtpRowsAll2.head.createdAt,
            updatedAt = userOtpRowsAll2.head.updatedAt,
            expiresAt = userOtpRowsAll2.head.expiresAt,
          )
        )

        val userActionAttemptRowsAll =
          postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

        userActionAttemptRowsAll should have size 1
        userActionAttemptRowsAll.head shouldBe userActionAttemptRowEmailVerificationVerifyOTP
      }

      "successfully re-sign up a user and reset the verify attempts counter when a genuinely new OTP is issued" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.signUpEmailStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          // Already inside the resend cooldown window (45s expiry, 15s cooldown), forcing the genuinely-new-OTP branch
          val userOtpRow = arbitrarySample[UserOtpRow].copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt(Instant.now.truncatedTo(ChronoUnit.MILLIS).plusSeconds(5)),
          )

          postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

          val userActionAttemptRowEmailVerificationVerifyOTP = arbitrarySample[UserActionAttemptRow].copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.EmailVerificationVerifyOTP,
          )

          postgresClient
            .executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowEmailVerificationVerifyOTP)
            )
            .zioValue

          val signUpEmailPostRequest =
            arbitrarySample[smithy.SignUpEmailPostRequest].copy(email = userDetailsRow.email.value)

          val signUpEmailPostResponse =
            gatewayClient.signUpEmailPost[smithy.InternalServerError](signUpEmailPostRequest).zioValue

          signUpEmailPostResponse.code shouldBe StatusCode.Ok
          signUpEmailPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf

          mailHogClient.readInbox().zioValue.total shouldBe 1

          val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

          userOtpRowsAll should have size 1
          userOtpRowsAll.head.otp should not be userOtpRow.otp
          userOtpRowsAll.head.otpID.value shouldBe signUpEmailPostResponse.body.value.otpID

          val userActionAttemptRowsAll =
            postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

          userActionAttemptRowsAll should have size 0
      }

      "successfully not re sign up a user with not sign up email stages" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.signUpEmailStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStageInvalid
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val signUpEmailPostRequest =
          arbitrarySample[smithy.SignUpEmailPostRequest].copy(email = userDetailsRow.email.value)

        val signUpEmailPostResponse =
          gatewayClient.signUpEmailPost[smithy.InternalServerError](signUpEmailPostRequest).zioValue

        signUpEmailPostResponse.code shouldBe StatusCode.Ok
        signUpEmailPostResponse.body.value.otpExpiresInSeconds shouldBe 45 // application.conf

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll should contain theSameElementsAs List(userDetailsRow)

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 0
      }

      "fail with BadRequest ValidationError when request is invalid" in withContext { context =>
        import context.*

        val signUpEmailPostRequest = arbitrarySample[smithy.SignUpEmailPostRequest].copy(email = "invalidemail")

        val signUpEmailPostResponse =
          gatewayClient.signUpEmailPost[smithy.ValidationError](signUpEmailPostRequest).zioValue

        signUpEmailPostResponse.code shouldBe StatusCode.BadRequest
        signUpEmailPostResponse.body.left.value shouldBe smithy.ValidationError(
          fields = List("email")
        )

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }
    }

    "POST /signup/verify/email" should {
      "successfully verify email with valid OTP and return user token" in withContext { context =>
        import context.*

        val onboardStage = Random.shuffle(OnboardStage.signUpVerifyEmailStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStage
        )

        val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID,
          otpType = OtpType.EmailVerification,
          expiresAt = ExpiresAt.assume(instantNow.plusSeconds(10)),
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest].copy(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val signUpVerifyEmailPostResponse =
          gatewayClient.signUpVerifyEmailPost[smithy.InternalServerError](signUpVerifyEmailPostRequest).zioValue

        signUpVerifyEmailPostResponse.code shouldBe StatusCode.Ok
        signUpVerifyEmailPostResponse.body.value.accessTokenExpiresInSeconds shouldBe 15.minutes.toSeconds

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userTokenRowsAll = postgresClient.executeQuery(userTokenQueries.getAllUserTokensTesting).zioValue

        userTokenRowsAll should have size 1
        userTokenRowsAll.head.userID shouldBe userDetailsRow.userID
        userTokenRowsAll.head.tokenType shouldBe TokenType.RefreshToken
      }

      "fail with BadRequest ValidationError when request is invalid" in withContext { context =>
        import context.*

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest].copy(
          otp = "invalidotp"
        )

        val signUpVerifyEmailPostResponse =
          gatewayClient.signUpVerifyEmailPost[smithy.ValidationError](signUpVerifyEmailPostRequest).zioValue

        signUpVerifyEmailPostResponse.code shouldBe StatusCode.BadRequest
        signUpVerifyEmailPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("otp"))

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with BadRequest when OTP is wrong" in withContext { context =>
        import context.*

        val onboardStage = Random.shuffle(OnboardStage.signUpVerifyEmailStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStage
        )
        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID,
          otpType = OtpType.EmailVerification,
          expiresAt = ExpiresAt.assume(Instant.now.plusSeconds(10).truncatedTo(ChronoUnit.MILLIS)),
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest].copy(
          otpID = userOtpRow.otpID.value,
          otp = "123ABC", // wrong otp
        )

        val signUpVerifyEmailPostResponse =
          gatewayClient.signUpVerifyEmailPost[smithy.BadRequest](signUpVerifyEmailPostRequest).zioValue

        signUpVerifyEmailPostResponse.code shouldBe StatusCode.BadRequest
        signUpVerifyEmailPostResponse.body.left.value shouldBe smithy.BadRequest()

        mailHogClient.readInbox().zioValue.total shouldBe 0

        val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll should contain theSameElementsAs List(userDetailsRow)

        val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

        userOtpRowsAll should have size 1
        userOtpRowsAll should contain theSameElementsAs List(userOtpRow)
      }

      "fail with Unauthorized when OTP is expired" in withContext { context =>
        import context.*

        val onboardStage = Random.shuffle(OnboardStage.signUpVerifyEmailStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStage
        )

        val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID,
          otpType = OtpType.EmailVerification,
          expiresAt = ExpiresAt.assume(instantNow.minusSeconds(10)),
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest].copy(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val signUpVerifyEmailPostResponse =
          gatewayClient.signUpVerifyEmailPost[smithy.Unauthorized](signUpVerifyEmailPostRequest).zioValue

        signUpVerifyEmailPostResponse.code shouldBe StatusCode.Unauthorized
        signUpVerifyEmailPostResponse.body.left.value shouldBe smithy.Unauthorized()

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with Forbidden when user is not in an allowed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.signUpVerifyEmailStages).zioValue.head

        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
          onboardStage = onboardStageInvalid
        )

        val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

        val userOtpRow = arbitrarySample[UserOtpRow].copy(
          userID = userDetailsRow.userID,
          otpType = OtpType.EmailVerification,
          expiresAt = ExpiresAt.assume(instantNow.plusSeconds(10)),
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

        val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest].copy(
          otpID = userOtpRow.otpID.value,
          otp = userOtpRow.otp.value,
        )

        val signUpVerifyEmailPostResponse =
          gatewayClient.signUpVerifyEmailPost[smithy.Forbidden](signUpVerifyEmailPostRequest).zioValue

        signUpVerifyEmailPostResponse.code shouldBe StatusCode.Forbidden
        signUpVerifyEmailPostResponse.body.left.value shouldBe smithy.Forbidden()

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with Unauthorized when verify attempts has reached the limit, even with the actually-correct OTP" in withContext {
        context =>
          import context.*

          val onboardStage = Random.shuffle(OnboardStage.signUpVerifyEmailStages).zioValue.head

          val userDetailsRow = arbitrarySample[UserDetailsRow].copy(
            onboardStage = onboardStage
          )

          val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          val userOtpRow = arbitrarySample[UserOtpRow].copy(
            userID = userDetailsRow.userID,
            otpType = OtpType.EmailVerification,
            expiresAt = ExpiresAt.assume(instantNow.plusSeconds(100)),
          )

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow)).zioValue

          // getAndIncreaseUserActionAttempt returns the pre-existing row as-is (see UserActionAttemptRepositorySpec),
          // so seeding one above the limit (6, application.conf otp-verify-attempts-max-retries = 5) is what makes
          // this call the one that exceeds it
          val userActionAttemptRowEmailVerificationVerifyOTP = arbitrarySample[UserActionAttemptRow].copy(
            userID = userDetailsRow.userID,
            actionAttemptType = ActionAttemptType.EmailVerificationVerifyOTP,
            attempts = Attempts.assume(6),
          )

          postgresClient
            .executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowEmailVerificationVerifyOTP)
            )
            .zioValue

          // The actually-correct OTP is submitted as the 6th call and must still be rejected without being checked
          val signUpVerifyEmailPostRequest = arbitrarySample[smithy.SignUpVerifyEmailPostRequest].copy(
            otpID = userOtpRow.otpID.value,
            otp = userOtpRow.otp.value,
          )

          val signUpVerifyEmailPostResponse =
            gatewayClient.signUpVerifyEmailPost[smithy.Unauthorized](signUpVerifyEmailPostRequest).zioValue

          signUpVerifyEmailPostResponse.code shouldBe StatusCode.Unauthorized
          signUpVerifyEmailPostResponse.body.left.value shouldBe smithy.Unauthorized()

          mailHogClient.readInbox().zioValue.total shouldBe 0

          val userDetailsRowsAll = postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

          userDetailsRowsAll should have size 1
          userDetailsRowsAll.head shouldBe userDetailsRow

          val userOtpRowsAll = postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting).zioValue

          userOtpRowsAll should have size 0

          val userActionAttemptRowsAll =
            postgresClient.executeQuery(userActionAttemptQueries.getAllUserActionAttemptsTesting).zioValue

          userActionAttemptRowsAll should have size 1
          userActionAttemptRowsAll.head shouldBe userActionAttemptRowEmailVerificationVerifyOTP.copy(
            attempts = Attempts.assume(7), // attempts should be increased by 1
            updatedAt = userActionAttemptRowsAll.head.updatedAt,
          )
      }
    }
  }
}
