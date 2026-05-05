package io.mesazon.gateway.it.client

import com.dimafeng.testcontainers.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import fs2.io.net.Network
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.it.client.GatewayClient.GatewayClientConfig
import io.mesazon.gateway.smithy
import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.client4.jsoniter.{asJson, asJsonEitherOrFail}
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

  given JsonValueCodec[smithy.SignUpEmailRequest]              = JsonCodecMaker.make[smithy.SignUpEmailRequest]
  given JsonValueCodec[smithy.SignUpVerifyEmailRequest]        = JsonCodecMaker.make[smithy.SignUpVerifyEmailRequest]
  given JsonValueCodec[smithy.OnboardPasswordRequest]          = JsonCodecMaker.make[smithy.OnboardPasswordRequest]
  given JsonValueCodec[smithy.OnboardDetailsRequest]           = JsonCodecMaker.make[smithy.OnboardDetailsRequest]
  given JsonValueCodec[smithy.OnboardVerifyPhoneNumberRequest] =
    JsonCodecMaker.make[smithy.OnboardVerifyPhoneNumberRequest]

  given JsonValueCodec[smithy.SignUpEmailResponse]              = JsonCodecMaker.make[smithy.SignUpEmailResponse]
  given JsonValueCodec[smithy.SignUpVerifyEmailResponse]        = JsonCodecMaker.make[smithy.SignUpVerifyEmailResponse]
  given JsonValueCodec[smithy.OnboardPasswordResponse]          = JsonCodecMaker.make[smithy.OnboardPasswordResponse]
  given JsonValueCodec[smithy.OnboardDetailsResponse]           = JsonCodecMaker.make[smithy.OnboardDetailsResponse]
  given JsonValueCodec[smithy.OnboardVerifyPhoneNumberResponse] =
    JsonCodecMaker.make[smithy.OnboardVerifyPhoneNumberResponse]
  given JsonValueCodec[smithy.SignInResponse] = JsonCodecMaker.make[smithy.SignInResponse]

  def liveness: Task[StatusCode] = basicRequest
    .get(healthUri.addPath("liveness"))
    .send(sttpBackend)
    .map(_.code)

  def readiness: Task[StatusCode] = basicRequest
    .get(healthUri.addPath("readiness"))
    .send(sttpBackend)
    .map(_.code)

  def signUpEmail[E: JsonValueCodec](
      signUpEmailRequest: smithy.SignUpEmailRequest
  ): Task[Response[Either[E, smithy.SignUpEmailResponse]]] =
    basicRequest
      .post(externalUri.addPath("signup", "email"))
      .body(asJson(signUpEmailRequest))
      .response(asJsonEitherOrFail[E, smithy.SignUpEmailResponse])
      .send(sttpBackend)

  def signUpVerifyEmail[E: JsonValueCodec](
      verifyEmailRequest: smithy.SignUpVerifyEmailRequest
  ): Task[Response[Either[E, smithy.SignUpVerifyEmailResponse]]] =
    basicRequest
      .post(externalUri.addPath("signup", "verify", "email"))
      .body(asJson(verifyEmailRequest))
      .response(asJsonEitherOrFail[E, smithy.SignUpVerifyEmailResponse])
      .send(sttpBackend)

  def onboardPassword[E: JsonValueCodec](
      onboardPasswordRequest: smithy.OnboardPasswordRequest,
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.OnboardPasswordResponse]]] =
    basicRequest
      .post(externalUri.addPath("onboard", "password"))
      .body(asJson(onboardPasswordRequest))
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.OnboardPasswordResponse])
      .send(sttpBackend)

  def onboardDetails[E: JsonValueCodec](
      onboardDetailsRequest: smithy.OnboardDetailsRequest,
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.OnboardDetailsResponse]]] =
    basicRequest
      .post(externalUri.addPath("onboard", "details"))
      .body(asJson(onboardDetailsRequest))
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.OnboardDetailsResponse])
      .send(sttpBackend)

  def onboardVerifyPhoneNumber[E: JsonValueCodec](
      onboardVerifyPhoneNumberRequest: smithy.OnboardVerifyPhoneNumberRequest,
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.OnboardVerifyPhoneNumberResponse]]] =
    basicRequest
      .post(externalUri.addPath("onboard", "verify", "phone-number"))
      .body(asJson(onboardVerifyPhoneNumberRequest))
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.OnboardVerifyPhoneNumberResponse])
      .send(sttpBackend)

  def signIn[E: JsonValueCodec](
      email: Email,
      password: Password,
      addBasicAuth: Boolean = true,
  ): Task[Response[Either[E, smithy.SignInResponse]]] =
    basicRequest
      .post(externalUri.addPath("signin"))
      .pipe(request => if (addBasicAuth) request.auth.basic(email.value, password.value) else request)
      .response(asJsonEitherOrFail[E, smithy.SignInResponse])
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
