package io.mesazon.gateway.it

import com.dimafeng.testcontainers.DockerComposeContainer
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.given
import io.mesazon.gateway.it.harness.GatewayAcceptanceTest
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.{RepositoryArbitraries, SmithyArbitraries, UserOnboardSmithyArbitraries}
import io.mesazon.testkit.base.IronRefinedTypeTransformer
import org.scalacheck.Gen
import org.scalatest.DoNotDiscover
import sttp.model.*
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

@DoNotDiscover
class UserOnboardApiSpec(dockerComposeContainer: => DockerComposeContainer)
    extends GatewayAcceptanceTest(dockerComposeContainer),
      SmithyArbitraries,
      UserOnboardSmithyArbitraries,
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
        )
        _ <- mailHogClient.clearInbox()
      } yield ()
    }
  }

  "User Onboard API" when {
    "POST /onboard/password" should {
      "successfully store the hashed password, move the user to PasswordProvided and send a welcome email" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.onboardPasswordStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage, phoneNumber = None, fullName = None)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]
              .copy(password = "ValidPassword123!")

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            onboardPasswordPostResponse <- gatewayClient.onboardPasswordPost[smithy.InternalServerError](
              onboardPasswordPostRequest,
              Some(accessJwt.accessToken),
            )

            _ = onboardPasswordPostResponse.code shouldBe StatusCode.Ok
            _ = onboardPasswordPostResponse.body.value shouldBe smithy.OnboardPasswordPostResponse(
              smithy.OnboardStage.PasswordProvided
            )

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 1

            userDetailsRowsAll     <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head shouldBe userDetailsRow.copy(
              onboardStage = OnboardStage.PasswordProvided,
              updatedAt = userDetailsRowsAll.head.updatedAt,
            )
            _ = userCredentialsRowsAll should have size 1
            _ = userCredentialsRowsAll.head shouldBe UserCredentialsRow(
              userID = userDetailsRow.userID,
              passwordHash = userCredentialsRowsAll.head.passwordHash,
              createdAt = userCredentialsRowsAll.head.createdAt,
              updatedAt = userCredentialsRowsAll.head.updatedAt,
            )

            isPasswordVerified <- passwordService.verifyPassword(
              Password.assume(onboardPasswordPostRequest.password),
              userCredentialsRowsAll.head.passwordHash,
            )

            _ = isPasswordVerified shouldBe true
          } yield ()
      }

      "fail with BadRequest ValidationError when request is invalid" in withContext { context =>
        import context.*

        for {
          userID = arbitrarySample[UserID]

          accessJwt <- jwtService.generateAccessToken(userID)

          onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]
            .copy(password = "short!")

          onboardPasswordPostResponse <- gatewayClient.onboardPasswordPost[smithy.ValidationError](
            onboardPasswordPostRequest,
            Some(accessJwt.accessToken),
          )

          _ = onboardPasswordPostResponse.code shouldBe StatusCode.BadRequest
          _ = onboardPasswordPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("password"))

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userCredentialsRowsAll <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)

          _ = userCredentialsRowsAll shouldBe empty
        } yield ()
      }
    }

    "POST /onboard/details" should {
      "successfully save the details, issue and text a new OTP and reset the verify attempts counter" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.onboardDetailsStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage, phoneNumber = None, fullName = None)
            // Reset because a genuinely new OTP is issued
            userActionAttemptRowPhoneVerificationVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.PhoneVerificationVerifyOTP,
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowPhoneVerificationVerifyOTP)
            )

            onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]
            phoneNumberExpected       = PhoneNumber(
              phoneRegion = Option
                .when(onboardDetailsPostRequest.phoneNumber.phoneCountryCode == "+44")(PhoneRegion.assume("GB"))
                .getOrElse(PhoneRegion.assume("CY")),
              phoneCountryCode = PhoneCountryCode.assume(onboardDetailsPostRequest.phoneNumber.phoneCountryCode),
              phoneNationalNumber =
                PhoneNationalNumber.assume(onboardDetailsPostRequest.phoneNumber.phoneNationalNumber),
              phoneNumberE164 = PhoneNumberE164.assume(
                onboardDetailsPostRequest.phoneNumber.phoneCountryCode + onboardDetailsPostRequest.phoneNumber.phoneNationalNumber
              ),
            )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            onboardDetailsPostResponse <- gatewayClient.onboardDetailsPost[smithy.InternalServerError](
              onboardDetailsPostRequest,
              Some(accessJwt.accessToken),
            )

            userOtpRowsAll <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

            _ = userOtpRowsAll should have size 1
            _ = userOtpRowsAll.head shouldBe UserOtpRow(
              otpID = userOtpRowsAll.head.otpID,
              userID = userDetailsRow.userID,
              otp = userOtpRowsAll.head.otp,
              otpType = OtpType.PhoneVerification,
              createdAt = userOtpRowsAll.head.createdAt,
              updatedAt = userOtpRowsAll.head.updatedAt,
              expiresAt = userOtpRowsAll.head.expiresAt,
            )

            _ = onboardDetailsPostResponse.code shouldBe StatusCode.Ok
            _ = onboardDetailsPostResponse.body.value shouldBe smithy.OnboardDetailsPostResponse(
              onboardStage = smithy.OnboardStage.PhoneVerification,
              otpID = userOtpRowsAll.head.otpID.value,
              otpExpiresInSeconds = 45, // application.conf otp-phone-verification-expires-at-offset
            )

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head shouldBe userDetailsRow.copy(
              fullName = Some(FullName.assume(onboardDetailsPostRequest.fullName)),
              phoneNumber = Some(phoneNumberExpected),
              onboardStage = OnboardStage.PhoneVerification,
              updatedAt = userDetailsRowsAll.head.updatedAt,
            )
            _ = userActionAttemptRowsAll shouldBe empty
          } yield ()
      }

      "successfully save the details and issue a new OTP when the phone number given is already the caller's own" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.onboardDetailsStages).map(_.head)
            phoneNumberCaller    = arbitrarySample[PhoneNumber]
            userDetailsRowCaller = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage,
                fullName = Some(arbitrarySample[FullName]),
                phoneNumber = Some(phoneNumberCaller),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRowCaller))

            onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]
              .copy(
                phoneNumber = smithy.PhoneNumberRequest(
                  phoneNumberCaller.phoneNationalNumber.value,
                  phoneNumberCaller.phoneCountryCode.value,
                )
              )

            accessJwt <- jwtService.generateAccessToken(userDetailsRowCaller.userID)

            onboardDetailsPostResponse <- gatewayClient.onboardDetailsPost[smithy.InternalServerError](
              onboardDetailsPostRequest,
              Some(accessJwt.accessToken),
            )

            userOtpRowsAll <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

            _ = userOtpRowsAll should have size 1
            _ = userOtpRowsAll.head shouldBe UserOtpRow(
              otpID = userOtpRowsAll.head.otpID,
              userID = userDetailsRowCaller.userID,
              otp = userOtpRowsAll.head.otp,
              otpType = OtpType.PhoneVerification,
              createdAt = userOtpRowsAll.head.createdAt,
              updatedAt = userOtpRowsAll.head.updatedAt,
              expiresAt = userOtpRowsAll.head.expiresAt,
            )

            _ = onboardDetailsPostResponse.code shouldBe StatusCode.Ok
            _ = onboardDetailsPostResponse.body.value shouldBe smithy.OnboardDetailsPostResponse(
              onboardStage = smithy.OnboardStage.PhoneVerification,
              otpID = userOtpRowsAll.head.otpID.value,
              otpExpiresInSeconds = 45, // application.conf otp-phone-verification-expires-at-offset
            )

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head shouldBe userDetailsRowCaller.copy(
              fullName = Some(FullName.assume(onboardDetailsPostRequest.fullName)),
              onboardStage = OnboardStage.PhoneVerification,
              updatedAt = userDetailsRowsAll.head.updatedAt,
            )
          } yield ()
      }

      "successfully re-submit details while the OTP is still fresh, saving a changed phone number, reusing the OTP and not resetting the verify attempts counter" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.onboardDetailsStages).map(_.head)
            phoneNumberStored = arbitrarySample[PhoneNumber]
            // Draw until distinct from the stored one
            phoneNumberChanged = Iterator
              .continually(arbitrarySample[PhoneNumber])
              .dropWhile(_ == phoneNumberStored)
              .next()
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage,
                phoneNumber = Some(phoneNumberStored),
                fullName = Some(arbitrarySample[FullName]),
              )
            // Still fresh: more than 15 seconds left (application.conf), so the OTP is reused
            otpExpiresInSeconds = 100L
            userOtpRow          = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.PhoneVerification,
                expiresAt = ExpiresAt(instantNow.plusSeconds(otpExpiresInSeconds)),
              )
            // Kept because the OTP is reused, not newly issued
            userActionAttemptRowPhoneVerificationVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.PhoneVerificationVerifyOTP,
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowPhoneVerificationVerifyOTP)
            )

            onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]
              .copy(
                phoneNumber = smithy.PhoneNumberRequest(
                  phoneNumberChanged.phoneNationalNumber.value,
                  phoneNumberChanged.phoneCountryCode.value,
                )
              )

            _ = phoneNumberChanged shouldNot equal(phoneNumberStored)

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            onboardDetailsPostResponse <- gatewayClient.onboardDetailsPost[smithy.InternalServerError](
              onboardDetailsPostRequest,
              Some(accessJwt.accessToken),
            )

            _ = onboardDetailsPostResponse.code shouldBe StatusCode.Ok
            _ = onboardDetailsPostResponse.body.value.onboardStage shouldBe smithy.OnboardStage.PhoneVerification
            _ = onboardDetailsPostResponse.body.value.otpID shouldBe userOtpRow.otpID.value
            _ = onboardDetailsPostResponse.body.value.otpExpiresInSeconds shouldBe (otpExpiresInSeconds +- 1)

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head.phoneNumber should not be Some(phoneNumberStored)
            _ = userDetailsRowsAll.head shouldBe userDetailsRow.copy(
              fullName = Some(FullName.assume(onboardDetailsPostRequest.fullName)),
              phoneNumber = Some(phoneNumberChanged),
              onboardStage = OnboardStage.PhoneVerification,
              updatedAt = userDetailsRowsAll.head.updatedAt,
            )
            _ = userOtpRowsAll shouldBe List(userOtpRow)
            _ = userActionAttemptRowsAll shouldBe List(userActionAttemptRowPhoneVerificationVerifyOTP)
          } yield ()
      }

      "successfully replace an OTP that is about to expire with a new one and reset the verify attempts counter" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.onboardDetailsStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage,
                phoneNumber = Some(arbitrarySample[PhoneNumber]),
                fullName = Some(arbitrarySample[FullName]),
              )
            // About to expire: 15 seconds or less left (application.conf) but not yet expired, so the OTP is replaced
            userOtpRowStale = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.PhoneVerification,
                expiresAt = ExpiresAt(instantNow.plusSeconds(10)),
              )
            // Reset because a genuinely new OTP is issued
            userActionAttemptRowPhoneVerificationVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.PhoneVerificationVerifyOTP,
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRowStale))
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowPhoneVerificationVerifyOTP)
            )

            onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]
              .copy(
                phoneNumber = smithy.PhoneNumberRequest(
                  userDetailsRow.phoneNumber.value.phoneNationalNumber.value,
                  userDetailsRow.phoneNumber.value.phoneCountryCode.value,
                )
              )

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            onboardDetailsPostResponse <- gatewayClient.onboardDetailsPost[smithy.InternalServerError](
              onboardDetailsPostRequest,
              Some(accessJwt.accessToken),
            )

            userOtpRowsAll <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

            _ = userOtpRowsAll should have size 1
            _ = userOtpRowsAll.head.otpID should not be userOtpRowStale.otpID
            _ = userOtpRowsAll.head.expiresAt should not be userOtpRowStale.expiresAt
            _ = userOtpRowsAll.head shouldBe UserOtpRow(
              otpID = userOtpRowsAll.head.otpID,
              userID = userDetailsRow.userID,
              otp = userOtpRowsAll.head.otp,
              otpType = OtpType.PhoneVerification,
              createdAt = userOtpRowsAll.head.createdAt,
              updatedAt = userOtpRowsAll.head.updatedAt,
              expiresAt = userOtpRowsAll.head.expiresAt,
            )

            _ = onboardDetailsPostResponse.code shouldBe StatusCode.Ok
            _ = onboardDetailsPostResponse.body.value shouldBe smithy.OnboardDetailsPostResponse(
              onboardStage = smithy.OnboardStage.PhoneVerification,
              otpID = userOtpRowsAll.head.otpID.value,
              otpExpiresInSeconds = 45, // application.conf otp-phone-verification-expires-at-offset
            )

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head shouldBe userDetailsRow.copy(
              fullName = Some(FullName.assume(onboardDetailsPostRequest.fullName)),
              onboardStage = OnboardStage.PhoneVerification,
              updatedAt = userDetailsRowsAll.head.updatedAt,
            )
            _ = userActionAttemptRowsAll shouldBe empty
          } yield ()
      }

      "successfully save the details and reuse the still-fresh OTP when the phone number given is already the caller's own" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.onboardDetailsStages).map(_.head)
            phoneNumberCaller    = arbitrarySample[PhoneNumber]
            userDetailsRowCaller = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage,
                fullName = Some(arbitrarySample[FullName]),
                phoneNumber = Some(phoneNumberCaller),
              )
            // Still fresh: more than 15 seconds left (application.conf), so the OTP is reused
            otpExpiresInSeconds = 100L
            userOtpRowCaller    = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRowCaller.userID,
                otpType = OtpType.PhoneVerification,
                expiresAt = ExpiresAt(instantNow.plusSeconds(otpExpiresInSeconds)),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRowCaller))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRowCaller))

            onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]
              .copy(
                phoneNumber = smithy.PhoneNumberRequest(
                  phoneNumberCaller.phoneNationalNumber.value,
                  phoneNumberCaller.phoneCountryCode.value,
                )
              )

            accessJwt <- jwtService.generateAccessToken(userDetailsRowCaller.userID)

            onboardDetailsPostResponse <- gatewayClient.onboardDetailsPost[smithy.InternalServerError](
              onboardDetailsPostRequest,
              Some(accessJwt.accessToken),
            )

            _ = onboardDetailsPostResponse.code shouldBe StatusCode.Ok
            _ = onboardDetailsPostResponse.body.value.onboardStage shouldBe smithy.OnboardStage.PhoneVerification
            _ = onboardDetailsPostResponse.body.value.otpID shouldBe userOtpRowCaller.otpID.value
            _ = onboardDetailsPostResponse.body.value.otpExpiresInSeconds shouldBe (otpExpiresInSeconds +- 1)

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userOtpRowsAll     <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userDetailsRowsAll <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)

            _ = userOtpRowsAll shouldBe List(userOtpRowCaller)
            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head shouldBe userDetailsRowCaller.copy(
              fullName = Some(FullName.assume(onboardDetailsPostRequest.fullName)),
              onboardStage = OnboardStage.PhoneVerification,
              updatedAt = userDetailsRowsAll.head.updatedAt,
            )
          } yield ()
      }

      "fail with BadRequest ValidationError when request is invalid" in withContext { context =>
        import context.*

        for {
          userID = arbitrarySample[UserID]

          accessJwt <- jwtService.generateAccessToken(userID)

          onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]
            .copy(phoneNumber =
              smithy.PhoneNumberRequest(phoneNationalNumber = "invalid-phone", phoneCountryCode = "XX")
            )

          onboardDetailsPostResponse <- gatewayClient.onboardDetailsPost[smithy.ValidationError](
            onboardDetailsPostRequest,
            Some(accessJwt.accessToken),
          )

          _ = onboardDetailsPostResponse.code shouldBe StatusCode.BadRequest
          _ = onboardDetailsPostResponse.body.left.value shouldBe smithy.ValidationError(
            fields = List("phoneNationalNumber")
          )

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll     <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

          _ = userDetailsRowsAll shouldBe empty
          _ = userOtpRowsAll shouldBe empty
        } yield ()
      }

      "fail with Conflict when the phone number given already belongs to a different account that has not verified its phone" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.onboardDetailsStages).map(_.head)
            userDetailsRowCaller = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage, fullName = None, phoneNumber = None)
            onboardStageOtherNotVerified <- Random
              .shuffle(OnboardStage.values.toList diff List(OnboardStage.PhoneVerified))
              .map(_.head)
            phoneNumberOther    = arbitrarySample[PhoneNumber]
            userDetailsRowOther = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStageOtherNotVerified, phoneNumber = Some(phoneNumberOther))

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRowCaller))
            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRowOther))

            onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]
              .copy(
                phoneNumber = smithy.PhoneNumberRequest(
                  phoneNumberOther.phoneNationalNumber.value,
                  phoneNumberOther.phoneCountryCode.value,
                )
              )

            accessJwt <- jwtService.generateAccessToken(userDetailsRowCaller.userID)

            onboardDetailsPostResponse <- gatewayClient.onboardDetailsPost[smithy.Conflict](
              onboardDetailsPostRequest,
              Some(accessJwt.accessToken),
            )

            _ = onboardDetailsPostResponse.code shouldBe StatusCode.Conflict
            _ = onboardDetailsPostResponse.body.left.value shouldBe smithy.Conflict(message =
              "The phone number given already belongs to a different account"
            )

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll should contain theSameElementsAs List(userDetailsRowCaller, userDetailsRowOther)
            _ = userOtpRowsAll shouldBe empty
            _ = userActionAttemptRowsAll shouldBe empty
          } yield ()
      }

      "fail with Conflict when the phone number given already belongs to a different account, even while the caller's own OTP is still fresh" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.onboardDetailsStages).map(_.head)
            phoneNumberCaller = arbitrarySample[PhoneNumber]
            // Draw until distinct from the caller's own
            phoneNumberOther = Iterator
              .continually(arbitrarySample[PhoneNumber])
              .dropWhile(_ == phoneNumberCaller)
              .next()
            userDetailsRowCaller = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage,
                fullName = Some(arbitrarySample[FullName]),
                phoneNumber = Some(phoneNumberCaller),
              )
            userDetailsRowOther = arbitrarySample[UserDetailsRow].copy(phoneNumber = Some(phoneNumberOther))
            // Still fresh: more than 15 seconds left (application.conf), so the OTP is reused
            userOtpRowCaller = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRowCaller.userID,
                otpType = OtpType.PhoneVerification,
                expiresAt = ExpiresAt(instantNow.plusSeconds(100)),
              )

            _ = phoneNumberOther shouldNot equal(phoneNumberCaller)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRowCaller))
            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRowOther))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRowCaller))

            onboardDetailsPostRequest = arbitrarySample[smithy.OnboardDetailsPostRequest]
              .copy(
                phoneNumber = smithy.PhoneNumberRequest(
                  phoneNumberOther.phoneNationalNumber.value,
                  phoneNumberOther.phoneCountryCode.value,
                )
              )

            accessJwt <- jwtService.generateAccessToken(userDetailsRowCaller.userID)

            onboardDetailsPostResponse <- gatewayClient.onboardDetailsPost[smithy.Conflict](
              onboardDetailsPostRequest,
              Some(accessJwt.accessToken),
            )

            _ = onboardDetailsPostResponse.code shouldBe StatusCode.Conflict
            _ = onboardDetailsPostResponse.body.left.value shouldBe smithy.Conflict(message =
              "The phone number given already belongs to a different account"
            )

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll should contain theSameElementsAs List(userDetailsRowCaller, userDetailsRowOther)
            _ = userOtpRowsAll shouldBe List(userOtpRowCaller)
            _ = userActionAttemptRowsAll shouldBe empty
          } yield ()
      }
    }

    "POST /onboard/verify/phone-number" should {
      "successfully verify the phone number, move the user to PhoneVerified and delete the OTP" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage,
                fullName = Some(arbitrarySample[FullName]),
                phoneNumber = Some(arbitrarySample[PhoneNumber]),
              )
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.PhoneVerification,
                expiresAt = ExpiresAt(instantNow.plusSeconds(10)),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

            onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
              .copy(otpID = userOtpRow.otpID.value, otp = userOtpRow.otp.value)

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            onboardVerifyPhoneNumberPostResponse <- gatewayClient
              .onboardVerifyPhoneNumberPost[smithy.InternalServerError](
                onboardVerifyPhoneNumberPostRequest,
                Some(accessJwt.accessToken),
              )

            _ = onboardVerifyPhoneNumberPostResponse.code shouldBe StatusCode.Ok
            _ = onboardVerifyPhoneNumberPostResponse.body.value shouldBe smithy.OnboardVerifyPhoneNumberPostResponse(
              smithy.OnboardStage.PhoneVerified
            )

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head shouldBe userDetailsRow.copy(
              onboardStage = OnboardStage.PhoneVerified,
              updatedAt = userDetailsRowsAll.head.updatedAt,
            )
            _ = userOtpRowsAll shouldBe empty
            _ = userActionAttemptRowsAll shouldBe empty
          } yield ()
      }

      "successfully verify the phone number on the last allowed attempt and delete the OTP and the wrong-attempt counter" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage,
                fullName = Some(arbitrarySample[FullName]),
                phoneNumber = Some(arbitrarySample[PhoneNumber]),
              )
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.PhoneVerification,
                expiresAt = ExpiresAt(instantNow.plusSeconds(10)),
              )
            otpVerifyAttemptsMaxRetries                    = 5 // application.conf otp-verify-attempts-max-retries
            userActionAttemptRowPhoneVerificationVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.PhoneVerificationVerifyOTP,
                attempts = Attempts.assume(otpVerifyAttemptsMaxRetries), // stored one ahead: this call is attempt max
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowPhoneVerificationVerifyOTP)
            )

            onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
              .copy(otpID = userOtpRow.otpID.value, otp = userOtpRow.otp.value)

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            onboardVerifyPhoneNumberPostResponse <- gatewayClient
              .onboardVerifyPhoneNumberPost[smithy.InternalServerError](
                onboardVerifyPhoneNumberPostRequest,
                Some(accessJwt.accessToken),
              )

            _ = onboardVerifyPhoneNumberPostResponse.code shouldBe StatusCode.Ok
            _ = onboardVerifyPhoneNumberPostResponse.body.value shouldBe smithy.OnboardVerifyPhoneNumberPostResponse(
              smithy.OnboardStage.PhoneVerified
            )

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll should have size 1
            _ = userDetailsRowsAll.head shouldBe userDetailsRow.copy(
              onboardStage = OnboardStage.PhoneVerified,
              updatedAt = userDetailsRowsAll.head.updatedAt,
            )
            _ = userOtpRowsAll shouldBe empty
            _ = userActionAttemptRowsAll shouldBe empty
          } yield ()
      }

      "fail with BadRequest when the OTP is wrong, counting the attempt and keeping the OTP usable" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage,
                fullName = Some(arbitrarySample[FullName]),
                phoneNumber = Some(arbitrarySample[PhoneNumber]),
              )
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.PhoneVerification,
                expiresAt = ExpiresAt(instantNow.plusSeconds(10)),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

            onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
              .copy(otpID = userOtpRow.otpID.value, otp = "132ABC") // wrong otp

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            onboardVerifyPhoneNumberPostResponse <- gatewayClient.onboardVerifyPhoneNumberPost[smithy.BadRequest](
              onboardVerifyPhoneNumberPostRequest,
              Some(accessJwt.accessToken),
            )

            _ = onboardVerifyPhoneNumberPostResponse.code shouldBe StatusCode.BadRequest
            _ = onboardVerifyPhoneNumberPostResponse.body.left.value shouldBe smithy.BadRequest()

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll shouldBe List(userDetailsRow)
            _ = userOtpRowsAll shouldBe List(userOtpRow)
            _ = userActionAttemptRowsAll should have size 1
            _ = userActionAttemptRowsAll.head shouldBe UserActionAttemptRow(
              actionAttemptID = userActionAttemptRowsAll.head.actionAttemptID,
              userID = userDetailsRow.userID,
              actionAttemptType = ActionAttemptType.PhoneVerificationVerifyOTP,
              attempts = Attempts.assume(2), // stored one ahead: the next attempt's number
              createdAt = userActionAttemptRowsAll.head.createdAt,
              updatedAt = userActionAttemptRowsAll.head.updatedAt,
            )
          } yield ()
      }

      "fail with BadRequest ValidationError when the OTP is invalid" in withContext { context =>
        import context.*

        for {
          userID = arbitrarySample[UserID]

          accessJwt <- jwtService.generateAccessToken(userID)

          onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
            .copy(otp = "invalidotp")

          onboardVerifyPhoneNumberPostResponse <- gatewayClient.onboardVerifyPhoneNumberPost[smithy.ValidationError](
            onboardVerifyPhoneNumberPostRequest,
            Some(accessJwt.accessToken),
          )

          _ = onboardVerifyPhoneNumberPostResponse.code shouldBe StatusCode.BadRequest
          _ = onboardVerifyPhoneNumberPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("otp"))

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )

          _ = userActionAttemptRowsAll shouldBe empty
        } yield ()
      }

      "fail with UnauthorizedOtp and delete the OTP when it is expired, counting the attempt" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage,
                fullName = Some(arbitrarySample[FullName]),
                phoneNumber = Some(arbitrarySample[PhoneNumber]),
              )
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.PhoneVerification,
                expiresAt = ExpiresAt(instantNow.minusSeconds(10)),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

            onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
              .copy(otpID = userOtpRow.otpID.value, otp = userOtpRow.otp.value)

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            onboardVerifyPhoneNumberPostResponse <- gatewayClient
              .onboardVerifyPhoneNumberPost[smithy.UnauthorizedOtp](
                onboardVerifyPhoneNumberPostRequest,
                Some(accessJwt.accessToken),
              )

            _ = onboardVerifyPhoneNumberPostResponse.code shouldBe StatusCode.Unauthorized
            _ = onboardVerifyPhoneNumberPostResponse.body.left.value shouldBe smithy.UnauthorizedOtp()

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll shouldBe List(userDetailsRow)
            _ = userOtpRowsAll shouldBe empty
            _ = userActionAttemptRowsAll should have size 1
            _ = userActionAttemptRowsAll.head shouldBe UserActionAttemptRow(
              actionAttemptID = userActionAttemptRowsAll.head.actionAttemptID,
              userID = userDetailsRow.userID,
              actionAttemptType = ActionAttemptType.PhoneVerificationVerifyOTP,
              attempts = Attempts.assume(2), // stored one ahead: the next attempt's number
              createdAt = userActionAttemptRowsAll.head.createdAt,
              updatedAt = userActionAttemptRowsAll.head.updatedAt,
            )
          } yield ()
      }

      "fail with UnauthorizedOtp when the OTP ID is not recognized, deleting nothing" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(
              onboardStage = onboardStage,
              fullName = Some(arbitrarySample[FullName]),
              phoneNumber = Some(arbitrarySample[PhoneNumber]),
            )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]

          onboardVerifyPhoneNumberPostResponse <- gatewayClient.onboardVerifyPhoneNumberPost[smithy.UnauthorizedOtp](
            onboardVerifyPhoneNumberPostRequest,
            Some(accessJwt.accessToken),
          )

          _ = onboardVerifyPhoneNumberPostResponse.code shouldBe StatusCode.Unauthorized
          _ = onboardVerifyPhoneNumberPostResponse.body.left.value shouldBe smithy.UnauthorizedOtp()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )

          _ = userDetailsRowsAll shouldBe List(userDetailsRow)
          _ = userOtpRowsAll shouldBe empty
          _ = userActionAttemptRowsAll shouldBe empty
        } yield ()
      }

      "fail with UnauthorizedOtp when the OTP ID belongs to another user, deleting nothing" in withContext { context =>
        import context.*

        for {
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          onboardStage <- Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(
              onboardStage = onboardStage,
              fullName = Some(arbitrarySample[FullName]),
              phoneNumber = Some(arbitrarySample[PhoneNumber]),
            )
          userIDOther     = arbitrarySample[UserID]
          userOtpRowOther = arbitrarySample[UserOtpRow]
            .copy(
              userID = userIDOther,
              otpType = OtpType.PhoneVerification,
              expiresAt = ExpiresAt(instantNow.plusSeconds(10)),
            )

          _ = userIDOther shouldNot equal(userDetailsRow.userID)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRowOther))

          onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
            .copy(otpID = userOtpRowOther.otpID.value, otp = userOtpRowOther.otp.value)

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          onboardVerifyPhoneNumberPostResponse <- gatewayClient.onboardVerifyPhoneNumberPost[smithy.UnauthorizedOtp](
            onboardVerifyPhoneNumberPostRequest,
            Some(accessJwt.accessToken),
          )

          _ = onboardVerifyPhoneNumberPostResponse.code shouldBe StatusCode.Unauthorized
          _ = onboardVerifyPhoneNumberPostResponse.body.left.value shouldBe smithy.UnauthorizedOtp()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )

          _ = userDetailsRowsAll shouldBe List(userDetailsRow)
          _ = userOtpRowsAll shouldBe List(userOtpRowOther)
          _ = userActionAttemptRowsAll shouldBe empty
        } yield ()
      }

      "fail with UnauthorizedOtp when the OTP ID belongs to a non-phone-verification OTP of the user, deleting nothing" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage,
                fullName = Some(arbitrarySample[FullName]),
                phoneNumber = Some(arbitrarySample[PhoneNumber]),
              )
            otpTypeOther        = arbitrarySample(Gen.oneOf(OtpType.values.toList diff List(OtpType.PhoneVerification)))
            userOtpRowOtherType = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = otpTypeOther,
                expiresAt = ExpiresAt(instantNow.plusSeconds(10)),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRowOtherType))

            onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
              .copy(otpID = userOtpRowOtherType.otpID.value, otp = userOtpRowOtherType.otp.value)

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            onboardVerifyPhoneNumberPostResponse <- gatewayClient
              .onboardVerifyPhoneNumberPost[smithy.UnauthorizedOtp](
                onboardVerifyPhoneNumberPostRequest,
                Some(accessJwt.accessToken),
              )

            _ = onboardVerifyPhoneNumberPostResponse.code shouldBe StatusCode.Unauthorized
            _ = onboardVerifyPhoneNumberPostResponse.body.left.value shouldBe smithy.UnauthorizedOtp()

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll shouldBe List(userDetailsRow)
            _ = userOtpRowsAll shouldBe List(userOtpRowOtherType)
            _ = userActionAttemptRowsAll shouldBe empty
          } yield ()
      }

      "fail with UnauthorizedOtp and delete the OTP when the verify attempts are over the limit, even with the correct OTP" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage,
                fullName = Some(arbitrarySample[FullName]),
                phoneNumber = Some(arbitrarySample[PhoneNumber]),
              )
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.PhoneVerification,
                expiresAt = ExpiresAt(instantNow.plusSeconds(100)),
              )
            otpVerifyAttemptsMaxRetries                    = 5 // application.conf otp-verify-attempts-max-retries
            userActionAttemptRowPhoneVerificationVerifyOTP = arbitrarySample[UserActionAttemptRow]
              .copy(
                userID = userDetailsRow.userID,
                actionAttemptType = ActionAttemptType.PhoneVerificationVerifyOTP,
                attempts =
                  Attempts.assume(otpVerifyAttemptsMaxRetries + 1), // stored one ahead: this call is attempt max + 1
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))
            _ <- postgresClient.executeQuery(
              userActionAttemptQueries.insertUserActionAttemptTesting(userActionAttemptRowPhoneVerificationVerifyOTP)
            )

            // The correct OTP must still be rejected without being checked
            onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
              .copy(otpID = userOtpRow.otpID.value, otp = userOtpRow.otp.value)

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            onboardVerifyPhoneNumberPostResponse <- gatewayClient
              .onboardVerifyPhoneNumberPost[smithy.UnauthorizedOtp](
                onboardVerifyPhoneNumberPostRequest,
                Some(accessJwt.accessToken),
              )

            _ = onboardVerifyPhoneNumberPostResponse.code shouldBe StatusCode.Unauthorized
            _ = onboardVerifyPhoneNumberPostResponse.body.left.value shouldBe smithy.UnauthorizedOtp()

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll shouldBe List(userDetailsRow)
            _ = userOtpRowsAll shouldBe empty
            _ = userActionAttemptRowsAll should have size 1
            _ = userActionAttemptRowsAll.head shouldBe userActionAttemptRowPhoneVerificationVerifyOTP.copy(
              attempts = Attempts.assume(otpVerifyAttemptsMaxRetries + 2),
              updatedAt = userActionAttemptRowsAll.head.updatedAt,
            )
          } yield ()
      }
    }

    "GET /onboard/verify/phone-number" should {
      "successfully return the waiting OTP's ID and remaining seconds without changing it" in withContext { context =>
        import context.*

        for {
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          onboardStage <- Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(
              onboardStage = onboardStage,
              fullName = Some(arbitrarySample[FullName]),
              phoneNumber = Some(arbitrarySample[PhoneNumber]),
            )
          otpExpiresInSeconds = 100L
          userOtpRow          = arbitrarySample[UserOtpRow]
            .copy(
              userID = userDetailsRow.userID,
              otpType = OtpType.PhoneVerification,
              expiresAt = ExpiresAt(instantNow.plusSeconds(otpExpiresInSeconds)),
            )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          onboardVerifyPhoneNumberGetResponse <- gatewayClient.onboardVerifyPhoneNumberGet[smithy.InternalServerError](
            Some(accessJwt.accessToken)
          )

          _ = onboardVerifyPhoneNumberGetResponse.code shouldBe StatusCode.Ok
          _ = onboardVerifyPhoneNumberGetResponse.body.value.otpID shouldBe userOtpRow.otpID.value
          _ = onboardVerifyPhoneNumberGetResponse.body.value.otpExpiresInSeconds shouldBe (otpExpiresInSeconds +- 1)

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll     <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

          _ = userDetailsRowsAll shouldBe List(userDetailsRow)
          _ = userOtpRowsAll shouldBe List(userOtpRow)
        } yield ()
      }

      "fail with UnauthorizedOtp and delete the OTP when it is expired" in withContext { context =>
        import context.*

        for {
          instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

          onboardStage <- Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(
              onboardStage = onboardStage,
              fullName = Some(arbitrarySample[FullName]),
              phoneNumber = Some(arbitrarySample[PhoneNumber]),
            )
          userOtpRow = arbitrarySample[UserOtpRow]
            .copy(
              userID = userDetailsRow.userID,
              otpType = OtpType.PhoneVerification,
              expiresAt = ExpiresAt(instantNow),
            )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          onboardVerifyPhoneNumberGetResponse <- gatewayClient.onboardVerifyPhoneNumberGet[smithy.UnauthorizedOtp](
            Some(accessJwt.accessToken)
          )

          _ = onboardVerifyPhoneNumberGetResponse.code shouldBe StatusCode.Unauthorized
          _ = onboardVerifyPhoneNumberGetResponse.body.left.value shouldBe smithy.UnauthorizedOtp()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll     <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

          _ = userDetailsRowsAll shouldBe List(userDetailsRow)
          _ = userOtpRowsAll shouldBe empty
        } yield ()
      }

      "fail with UnauthorizedOtp and delete the OTP when it is about to expire but not yet expired" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStage <- Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStage,
                fullName = Some(arbitrarySample[FullName]),
                phoneNumber = Some(arbitrarySample[PhoneNumber]),
              )
            // About to expire: 15 seconds or less left (application.conf) but not yet expired
            userOtpRow = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRow.userID,
                otpType = OtpType.PhoneVerification,
                expiresAt = ExpiresAt(instantNow.plusSeconds(10)),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            onboardVerifyPhoneNumberGetResponse <- gatewayClient
              .onboardVerifyPhoneNumberGet[smithy.UnauthorizedOtp](Some(accessJwt.accessToken))

            _ = onboardVerifyPhoneNumberGetResponse.code shouldBe StatusCode.Unauthorized
            _ = onboardVerifyPhoneNumberGetResponse.body.left.value shouldBe smithy.UnauthorizedOtp()

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userOtpRowsAll     <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

            _ = userDetailsRowsAll shouldBe List(userDetailsRow)
            _ = userOtpRowsAll shouldBe empty
          } yield ()
      }

      "fail with UnauthorizedOtp when no OTP is waiting for the user" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.onboardVerifyPhoneNumberStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(
              onboardStage = onboardStage,
              fullName = Some(arbitrarySample[FullName]),
              phoneNumber = Some(arbitrarySample[PhoneNumber]),
            )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          onboardVerifyPhoneNumberGetResponse <- gatewayClient.onboardVerifyPhoneNumberGet[smithy.UnauthorizedOtp](
            Some(accessJwt.accessToken)
          )

          _ = onboardVerifyPhoneNumberGetResponse.code shouldBe StatusCode.Unauthorized
          _ = onboardVerifyPhoneNumberGetResponse.body.left.value shouldBe smithy.UnauthorizedOtp()

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userOtpRowsAll     <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)

          _ = userDetailsRowsAll shouldBe List(userDetailsRow)
          _ = userOtpRowsAll shouldBe empty
        } yield ()
      }
    }

    "middleware" should {
      "fail with an Unauthorized when the access token is missing on every endpoint" in withContext { context =>
        import context.*

        for {
          onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]
            .copy(password = "ValidPassword123!")
          onboardDetailsPostRequest           = arbitrarySample[smithy.OnboardDetailsPostRequest]
          onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.onboardPasswordPost[smithy.Unauthorized](onboardPasswordPostRequest, None),
              gatewayClient.onboardDetailsPost[smithy.Unauthorized](onboardDetailsPostRequest, None),
              gatewayClient.onboardVerifyPhoneNumberPost[smithy.Unauthorized](
                onboardVerifyPhoneNumberPostRequest,
                None,
              ),
              gatewayClient.onboardVerifyPhoneNumberGet[smithy.Unauthorized](None),
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(4)(StatusCode.Unauthorized)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(4)(Some(smithy.Unauthorized()))

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userCredentialsRowsAll   <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )

          _ = userDetailsRowsAll shouldBe empty
          _ = userCredentialsRowsAll shouldBe empty
          _ = userOtpRowsAll shouldBe empty
          _ = userActionAttemptRowsAll shouldBe empty
        } yield ()
      }

      "fail with an Unauthorized when the access token is invalid on every endpoint" in withContext { context =>
        import context.*

        for {
          onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]
            .copy(password = "ValidPassword123!")
          onboardDetailsPostRequest           = arbitrarySample[smithy.OnboardDetailsPostRequest]
          onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
          accessTokenInvalid                  = AccessToken("invalidtoken")

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.onboardPasswordPost[smithy.Unauthorized](
                onboardPasswordPostRequest,
                Some(accessTokenInvalid),
              ),
              gatewayClient.onboardDetailsPost[smithy.Unauthorized](
                onboardDetailsPostRequest,
                Some(accessTokenInvalid),
              ),
              gatewayClient.onboardVerifyPhoneNumberPost[smithy.Unauthorized](
                onboardVerifyPhoneNumberPostRequest,
                Some(accessTokenInvalid),
              ),
              gatewayClient.onboardVerifyPhoneNumberGet[smithy.Unauthorized](Some(accessTokenInvalid)),
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(4)(StatusCode.Unauthorized)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(4)(Some(smithy.Unauthorized()))

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0

          userDetailsRowsAll       <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
          userCredentialsRowsAll   <- postgresClient.executeQuery(userCredentialsQueries.getAllUserCredentialsTesting)
          userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
          userActionAttemptRowsAll <- postgresClient.executeQuery(
            userActionAttemptQueries.getAllUserActionAttemptsTesting
          )

          _ = userDetailsRowsAll shouldBe empty
          _ = userCredentialsRowsAll shouldBe empty
          _ = userOtpRowsAll shouldBe empty
          _ = userActionAttemptRowsAll shouldBe empty
        } yield ()
      }

      "fail with a Forbidden when the user is not in an onboard stage the endpoint allows on every endpoint" in withContext {
        context =>
          import context.*

          for {
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            onboardStageInvalidOnboardPassword <- Random
              .shuffle(OnboardStage.values.toList diff OnboardStage.onboardPasswordStages)
              .map(_.head)
            onboardStageInvalidOnboardDetails <- Random
              .shuffle(OnboardStage.values.toList diff OnboardStage.onboardDetailsStages)
              .map(_.head)
            onboardStageInvalidOnboardVerifyPhoneNumberPost <- Random
              .shuffle(OnboardStage.values.toList diff OnboardStage.onboardVerifyPhoneNumberStages)
              .map(_.head)
            onboardStageInvalidOnboardVerifyPhoneNumberGet <- Random
              .shuffle(OnboardStage.values.toList diff OnboardStage.onboardVerifyPhoneNumberStages)
              .map(_.head)
            userDetailsRowOnboardPassword = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStageInvalidOnboardPassword, phoneNumber = None, fullName = None)
            userDetailsRowOnboardDetails = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStageInvalidOnboardDetails, phoneNumber = None, fullName = None)
            userDetailsRowOnboardVerifyPhoneNumberPost = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStageInvalidOnboardVerifyPhoneNumberPost,
                fullName = Some(arbitrarySample[FullName]),
                phoneNumber = Some(arbitrarySample[PhoneNumber]),
              )
            userDetailsRowOnboardVerifyPhoneNumberGet = arbitrarySample[UserDetailsRow]
              .copy(
                onboardStage = onboardStageInvalidOnboardVerifyPhoneNumberGet,
                fullName = Some(arbitrarySample[FullName]),
                phoneNumber = Some(arbitrarySample[PhoneNumber]),
              )
            userOtpRowOnboardVerifyPhoneNumberPost = arbitrarySample[UserOtpRow]
              .copy(
                userID = userDetailsRowOnboardVerifyPhoneNumberPost.userID,
                otpType = OtpType.PhoneVerification,
                expiresAt = ExpiresAt(instantNow.plusSeconds(10)),
              )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRowOnboardPassword))
            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRowOnboardDetails))
            _ <- postgresClient.executeQuery(
              userDetailsQueries.insertUserDetails(userDetailsRowOnboardVerifyPhoneNumberPost)
            )
            _ <- postgresClient.executeQuery(
              userDetailsQueries.insertUserDetails(userDetailsRowOnboardVerifyPhoneNumberGet)
            )
            _ <- postgresClient.executeQuery(userOtpQueries.insertUserOtp(userOtpRowOnboardVerifyPhoneNumberPost))

            onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]
              .copy(password = "ValidPassword123!")
            onboardDetailsPostRequest           = arbitrarySample[smithy.OnboardDetailsPostRequest]
            onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]
              .copy(
                otpID = userOtpRowOnboardVerifyPhoneNumberPost.otpID.value,
                otp = userOtpRowOnboardVerifyPhoneNumberPost.otp.value,
              )

            accessJwtOnboardPassword <- jwtService.generateAccessToken(userDetailsRowOnboardPassword.userID)
            accessJwtOnboardDetails  <- jwtService.generateAccessToken(userDetailsRowOnboardDetails.userID)
            accessJwtOnboardVerifyPhoneNumberPost <- jwtService.generateAccessToken(
              userDetailsRowOnboardVerifyPhoneNumberPost.userID
            )
            accessJwtOnboardVerifyPhoneNumberGet <- jwtService.generateAccessToken(
              userDetailsRowOnboardVerifyPhoneNumberGet.userID
            )

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.onboardPasswordPost[smithy.Forbidden](
                  onboardPasswordPostRequest,
                  Some(accessJwtOnboardPassword.accessToken),
                ),
                gatewayClient.onboardDetailsPost[smithy.Forbidden](
                  onboardDetailsPostRequest,
                  Some(accessJwtOnboardDetails.accessToken),
                ),
                gatewayClient.onboardVerifyPhoneNumberPost[smithy.Forbidden](
                  onboardVerifyPhoneNumberPostRequest,
                  Some(accessJwtOnboardVerifyPhoneNumberPost.accessToken),
                ),
                gatewayClient.onboardVerifyPhoneNumberGet[smithy.Forbidden](
                  Some(accessJwtOnboardVerifyPhoneNumberGet.accessToken)
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(4)(StatusCode.Forbidden)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(4)(Some(smithy.Forbidden()))

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll     <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userCredentialsRowsAll <- postgresClient.executeQuery(
              userCredentialsQueries.getAllUserCredentialsTesting
            )
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll.toSet shouldBe Set(
              userDetailsRowOnboardPassword,
              userDetailsRowOnboardDetails,
              userDetailsRowOnboardVerifyPhoneNumberPost,
              userDetailsRowOnboardVerifyPhoneNumberGet,
            )
            _ = userCredentialsRowsAll shouldBe empty
            _ = userOtpRowsAll shouldBe List(userOtpRowOnboardVerifyPhoneNumberPost)
            _ = userActionAttemptRowsAll shouldBe empty
          } yield ()
      }

      "fail with an InternalServerError when the user details do not exist on every endpoint" in withContext {
        context =>
          import context.*

          for {
            userID = arbitrarySample[UserID]

            onboardPasswordPostRequest = arbitrarySample[smithy.OnboardPasswordPostRequest]
              .copy(password = "ValidPassword123!")
            onboardDetailsPostRequest           = arbitrarySample[smithy.OnboardDetailsPostRequest]
            onboardVerifyPhoneNumberPostRequest = arbitrarySample[smithy.OnboardVerifyPhoneNumberPostRequest]

            accessJwt <- jwtService.generateAccessToken(userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.onboardPasswordPost[smithy.InternalServerError](
                  onboardPasswordPostRequest,
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.onboardDetailsPost[smithy.InternalServerError](
                  onboardDetailsPostRequest,
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.onboardVerifyPhoneNumberPost[smithy.InternalServerError](
                  onboardVerifyPhoneNumberPostRequest,
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.onboardVerifyPhoneNumberGet[smithy.InternalServerError](Some(accessJwt.accessToken)),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(4)(StatusCode.InternalServerError)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(4)(Some(smithy.InternalServerError()))

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0

            userDetailsRowsAll     <- postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting)
            userCredentialsRowsAll <- postgresClient.executeQuery(
              userCredentialsQueries.getAllUserCredentialsTesting
            )
            userOtpRowsAll           <- postgresClient.executeQuery(userOtpQueries.getAllUserOtpsTesting)
            userActionAttemptRowsAll <- postgresClient.executeQuery(
              userActionAttemptQueries.getAllUserActionAttemptsTesting
            )

            _ = userDetailsRowsAll shouldBe empty
            _ = userCredentialsRowsAll shouldBe empty
            _ = userOtpRowsAll shouldBe empty
            _ = userActionAttemptRowsAll shouldBe empty
          } yield ()
      }
    }
  }
}
