package io.mesazon.gateway.it.client

import com.dimafeng.testcontainers.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import fs2.io.net.Network
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.it.client.GatewayClient.GatewayClientConfig
import io.mesazon.gateway.smithy
import io.mesazon.gateway.smithy.PhoneNumberRequest
import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.client4.jsoniter.*
import sttp.model.*
import zio.*
import zio.interop.catz.*

import scala.util.chaining.scalaUtilChainingOps

case class GatewayClient(config: GatewayClientConfig, sttpBackend: Backend[Task]) {
  import config.*

  given JsonValueCodec[smithy.OnboardStage] = new JsonValueCodec[smithy.OnboardStage] {
    override def decodeValue(in: JsonReader, default: smithy.OnboardStage): smithy.OnboardStage =
      in.readString(null) match {
        case "EMAIL_VERIFICATION" => smithy.OnboardStage.EMAIL_VERIFICATION
        case "EMAIL_VERIFIED"     => smithy.OnboardStage.EMAIL_VERIFIED
        case "PASSWORD_PROVIDED"  => smithy.OnboardStage.PASSWORD_PROVIDED
        case "PHONE_VERIFICATION" => smithy.OnboardStage.PHONE_VERIFICATION
        case "PHONE_VERIFIED"     => smithy.OnboardStage.PHONE_VERIFIED
        case str                  => throw new IllegalArgumentException(s"Unknown OnboardStage: $str")
      }

    override def encodeValue(x: smithy.OnboardStage, out: JsonWriter): Unit =
      x match {
        case smithy.OnboardStage.EMAIL_VERIFICATION => out.writeVal("EMAIL_VERIFICATION")
        case smithy.OnboardStage.EMAIL_VERIFIED     => out.writeVal("EMAIL_VERIFIED")
        case smithy.OnboardStage.PASSWORD_PROVIDED  => out.writeVal("PASSWORD_PROVIDED")
        case smithy.OnboardStage.PHONE_VERIFICATION => out.writeVal("PHONE_VERIFICATION")
        case smithy.OnboardStage.PHONE_VERIFIED     => out.writeVal("PHONE_VERIFIED")
      }

    override def nullValue: smithy.OnboardStage = null
  }

  given JsonValueCodec[smithy.SignUpEmailPostRequest]        = JsonCodecMaker.make[smithy.SignUpEmailPostRequest]
  given JsonValueCodec[smithy.SignUpVerifyEmailPostRequest]  = JsonCodecMaker.make[smithy.SignUpVerifyEmailPostRequest]
  given JsonValueCodec[smithy.OnboardPasswordPostRequest]    = JsonCodecMaker.make[smithy.OnboardPasswordPostRequest]
  given JsonValueCodec[smithy.OnboardDetailsPostRequest]     = JsonCodecMaker.make[smithy.OnboardDetailsPostRequest]
  given JsonValueCodec[smithy.ForgotPasswordPostRequest]     = JsonCodecMaker.make[smithy.ForgotPasswordPostRequest]
  given JsonValueCodec[smithy.TokenRefreshPostRequest]       = JsonCodecMaker.make[smithy.TokenRefreshPostRequest]
  given JsonValueCodec[smithy.CreateOrganizationPostRequest] = JsonCodecMaker.make[smithy.CreateOrganizationPostRequest]
  given JsonValueCodec[smithy.ForgotPasswordResetPostRequest] =
    JsonCodecMaker.make[smithy.ForgotPasswordResetPostRequest]
  given JsonValueCodec[smithy.ForgotPasswordVerifyOTPPostRequest] =
    JsonCodecMaker.make[smithy.ForgotPasswordVerifyOTPPostRequest]
  given JsonValueCodec[smithy.OnboardVerifyPhoneNumberPostRequest] =
    JsonCodecMaker.make[smithy.OnboardVerifyPhoneNumberPostRequest]

  given JsonValueCodec[smithy.SignUpEmailPostResponse]        = JsonCodecMaker.make[smithy.SignUpEmailPostResponse]
  given JsonValueCodec[smithy.CreateOrganizationPostResponse] =
    JsonCodecMaker.make[smithy.CreateOrganizationPostResponse]
  given JsonValueCodec[smithy.TokenRefreshPostResponse]      = JsonCodecMaker.make[smithy.TokenRefreshPostResponse]
  given JsonValueCodec[smithy.ForgotPasswordPostResponse]    = JsonCodecMaker.make[smithy.ForgotPasswordPostResponse]
  given JsonValueCodec[smithy.SignUpVerifyEmailPostResponse] = JsonCodecMaker.make[smithy.SignUpVerifyEmailPostResponse]
  given JsonValueCodec[smithy.OnboardPasswordPostResponse]   = JsonCodecMaker.make[smithy.OnboardPasswordPostResponse]
  given JsonValueCodec[smithy.OnboardDetailsPostResponse]    = JsonCodecMaker.make[smithy.OnboardDetailsPostResponse]
  given JsonValueCodec[smithy.ForgotPasswordVerifyOTPPostResponse] =
    JsonCodecMaker.make[smithy.ForgotPasswordVerifyOTPPostResponse]
  given JsonValueCodec[smithy.OnboardVerifyPhoneNumberPostResponse] =
    JsonCodecMaker.make[smithy.OnboardVerifyPhoneNumberPostResponse]
  given JsonValueCodec[smithy.SignInPostResponse]                  = JsonCodecMaker.make[smithy.SignInPostResponse]
  given JsonValueCodec[smithy.OnboardVerifyPhoneNumberGetResponse] =
    JsonCodecMaker.make[smithy.OnboardVerifyPhoneNumberGetResponse]

  private def asJsonErrorUnit[E: JsonValueCodec]: ResponseAs[Either[E, Unit]] =
    asEither(asJsonAlways[E].map(_.fold(e => throw e, identity)), ignore)

  def liveness: Task[StatusCode] = basicRequest
    .get(healthUri.addPath("liveness"))
    .send(sttpBackend)
    .map(_.code)

  def readiness: Task[StatusCode] = basicRequest
    .get(healthUri.addPath("readiness"))
    .send(sttpBackend)
    .map(_.code)

  def signUpEmailPost[E: JsonValueCodec](
      signUpEmailPostRequest: smithy.SignUpEmailPostRequest
  ): Task[Response[Either[E, smithy.SignUpEmailPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("signup", "email"))
      .body(asJson(signUpEmailPostRequest))
      .response(asJsonEitherOrFail[E, smithy.SignUpEmailPostResponse])
      .send(sttpBackend)

  def signUpVerifyEmailPost[E: JsonValueCodec](
      verifyEmailRequest: smithy.SignUpVerifyEmailPostRequest
  ): Task[Response[Either[E, smithy.SignUpVerifyEmailPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("signup", "verify", "email"))
      .body(asJson(verifyEmailRequest))
      .response(asJsonEitherOrFail[E, smithy.SignUpVerifyEmailPostResponse])
      .send(sttpBackend)

  def onboardPasswordPost[E: JsonValueCodec](
      onboardPasswordPostRequest: smithy.OnboardPasswordPostRequest,
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.OnboardPasswordPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("onboard", "password"))
      .body(asJson(onboardPasswordPostRequest))
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.OnboardPasswordPostResponse])
      .send(sttpBackend)

  def onboardDetailsPost[E: JsonValueCodec](
      onboardDetailsPostRequest: smithy.OnboardDetailsPostRequest,
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.OnboardDetailsPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("onboard", "details"))
      .body(asJson(onboardDetailsPostRequest))
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.OnboardDetailsPostResponse])
      .send(sttpBackend)

  def onboardVerifyPhoneNumberPost[E: JsonValueCodec](
      onboardVerifyPhoneNumberPostRequest: smithy.OnboardVerifyPhoneNumberPostRequest,
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.OnboardVerifyPhoneNumberPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("onboard", "verify", "phone-number"))
      .body(asJson(onboardVerifyPhoneNumberPostRequest))
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.OnboardVerifyPhoneNumberPostResponse])
      .send(sttpBackend)

  def onboardVerifyPhoneNumberGet[E: JsonValueCodec](
      accessTokenOpt: Option[AccessToken]
  ): Task[Response[Either[E, smithy.OnboardVerifyPhoneNumberGetResponse]]] =
    basicRequest
      .get(externalUri.addPath("onboard", "verify", "phone-number"))
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.OnboardVerifyPhoneNumberGetResponse])
      .send(sttpBackend)

  def signInPost[E: JsonValueCodec](
      email: Email,
      password: Password,
      addBasicAuth: Boolean = true,
  ): Task[Response[Either[E, smithy.SignInPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("signin"))
      .pipe(request => if (addBasicAuth) request.auth.basic(email.value, password.value) else request)
      .response(asJsonEitherOrFail[E, smithy.SignInPostResponse])
      .send(sttpBackend)

  def forgotPasswordPost[E: JsonValueCodec](
      email: Email
  ): Task[Response[Either[E, smithy.ForgotPasswordPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("forgot", "password"))
      .body(asJson(smithy.ForgotPasswordPostRequest(email.value)))
      .response(asJsonEitherOrFail[E, smithy.ForgotPasswordPostResponse])
      .send(sttpBackend)

  def forgotPasswordVerifyOTPPost[E: JsonValueCodec](
      otpID: OtpID,
      otp: Otp,
  ): Task[Response[Either[E, smithy.ForgotPasswordVerifyOTPPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("forgot", "password", "verify-otp"))
      .body(asJson(smithy.ForgotPasswordVerifyOTPPostRequest(otpID.value, otp.value)))
      .response(asJsonEitherOrFail[E, smithy.ForgotPasswordVerifyOTPPostResponse])
      .send(sttpBackend)

  def forgotPasswordResetPost[E: JsonValueCodec](
      resetPasswordToken: ResetPasswordToken,
      password: Password,
  ): Task[Response[Either[E, Unit]]] =
    basicRequest
      .post(externalUri.addPath("forgot", "password", "reset"))
      .body(asJson(smithy.ForgotPasswordResetPostRequest(resetPasswordToken.value, password.value)))
      .response(asJsonErrorUnit[E])
      .send(sttpBackend)

  def tokenRefreshPost[E: JsonValueCodec](
      refreshToken: RefreshToken
  ): Task[Response[Either[E, smithy.TokenRefreshPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("token", "refresh"))
      .body(asJson(smithy.TokenRefreshPostRequest(refreshToken.value)))
      .response(asJsonEitherOrFail[E, smithy.TokenRefreshPostResponse])
      .send(sttpBackend)

  def createOrganizationPost[E: JsonValueCodec](
      name: OrganizationName,
      slug: OrganizationSlug,
      email: OrganizationEmail,
      phoneNumber: OrganizationPhoneNumber,
      addressLine1: OrganizationAddressLine1,
      addressLine2: Option[OrganizationAddressLine2],
      city: OrganizationCity,
      postalCode: OrganizationPostalCode,
      country: OrganizationCountry,
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.CreateOrganizationPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("create", "organization"))
      .body(
        asJson(
          smithy.CreateOrganizationPostRequest(
            name = name.value,
            slug = slug.value,
            email = email.value,
            phoneNumber = smithy.PhoneNumberRequest(
              phoneNumber.value.phoneNationalNumber.value,
              phoneNumber.value.phoneCountryCode.value,
            ),
            addressLine1 = addressLine1.value,
            addressLine2 = addressLine2.map(_.value),
            city = city.value,
            postalCode = postalCode.value,
            country = country.value,
          )
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.CreateOrganizationPostResponse])
      .send(sttpBackend)

  def uploadOrganizationLogoPost[E: JsonValueCodec](
      organizationID: OrganizationID,
      organizationLogoOriginalFileNameOpt: Option[OrganizationLogoOriginalFileName],
      logoBytes: Chunk[Byte],
  ): Task[Response[Either[E, Unit]]] =
    basicRequest
      .post(externalUri.addPath("upload", "organization", "logo", organizationID.value.toString))
      .pipe(request =>
        organizationLogoOriginalFileNameOpt.fold(request)(organizationLogoOriginalFileName =>
          request.header("X-File-Name", organizationLogoOriginalFileName.value)
        )
      )
      .body(logoBytes.toArray)
      .contentType(MediaType.ApplicationOctetStream)
      .response(asJsonErrorUnit[E])
      .send(sttpBackend)
}

object GatewayClient {

  given JsonValueCodec[smithy.ValidationError]     = JsonCodecMaker.make[smithy.ValidationError]
  given JsonValueCodec[smithy.BadRequest]          = JsonCodecMaker.make[smithy.BadRequest]
  given JsonValueCodec[smithy.Unauthorized]        = JsonCodecMaker.make[smithy.Unauthorized]
  given JsonValueCodec[smithy.InternalServerError] = JsonCodecMaker.make[smithy.InternalServerError]

  // Remove stupid warning that can't execute bin/sh in distroless images
  lazy val ServiceName     = "gateway"
  lazy val ExternalPort    = 8080
  lazy val InternalPort    = 8081
  lazy val HealthPort      = 8082
  lazy val ExposedServices = Set(
    ExposedService(ServiceName, ExternalPort),
    ExposedService(ServiceName, InternalPort),
    ExposedService(ServiceName, HealthPort),
  )

  given Network[Task] = Network.forAsync[Task]

  case class GatewayClientConfig(externalUri: Uri, internalUri: Uri, healthUri: Uri, token: String) {

    /** @param containers
      *   Option[DockerComposeContainer] * If provided resolves host and port with testcontainers
      * @param serviceName
      *   String container name
      * @param externalPort
      *   Int container service external port
      * @param internalPort
      *   Int container service internal port
      * @return
      *   GatewayApiClientConfig
      */
    def adjust(
        containers: Option[DockerComposeContainer],
        serviceName: String = ServiceName,
        externalPort: Int = ExternalPort,
        internalPort: Int = InternalPort,
    ): GatewayClientConfig = containers match {
      case Some(containers) =>
        GatewayClientConfig.from(
          containers,
          serviceName,
          externalPort,
          internalPort,
        )
      case None => this
    }
  }

  object GatewayClientConfig {

    /** @param containers
      *   DockerComposeContainer * Resolves host and port with testcontainers
      * @param serviceName
      *   String container name
      * @param externalPort
      *   Int container service external port
      * @param internalPort
      *   Int container service internal port
      * @param healthPort
      *   Int container health port
      * @return
      *   GatewayApiClientConfig
      */
    def from(
        containers: DockerComposeContainer,
        serviceName: String = ServiceName,
        externalPort: Int = ExternalPort,
        internalPort: Int = InternalPort,
        healthPort: Int = HealthPort,
        token: String = "valid-token",
    ): GatewayClientConfig = {
      val adjustedHost         = containers.getServiceHost(serviceName, externalPort)
      val adjustedExternalPort = containers.getServicePort(serviceName, externalPort)
      val adjustedInternalPort = containers.getServicePort(serviceName, internalPort)
      val adjustedHealthPort   = containers.getServicePort(serviceName, healthPort)

      GatewayClientConfig(
        Uri.unsafeApply(adjustedHost, adjustedExternalPort),
        Uri.unsafeApply(adjustedHost, adjustedInternalPort),
        Uri.unsafeApply(adjustedHost, adjustedHealthPort),
        token,
      )
    }
  }

  val live = HttpClientZioBackend.layer() >>> ZLayer.fromFunction(GatewayClient.apply)
}
