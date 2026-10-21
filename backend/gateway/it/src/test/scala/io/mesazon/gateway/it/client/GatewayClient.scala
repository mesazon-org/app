package io.mesazon.gateway.it.client

import com.dimafeng.testcontainers.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import fs2.io.net.Network
import io.github.iltotore.iron.chimney.given
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.it.client.GatewayClient.GatewayClientConfig
import io.mesazon.gateway.json.tapir.given
import io.mesazon.gateway.smithy
import io.scalaland.chimney.dsl.*
import smithy4s.Nullable
import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.client4.jsoniter.*
import sttp.model.*
import zio.*
import zio.interop.catz.*

import scala.util.chaining.scalaUtilChainingOps

case class GatewayClient(config: GatewayClientConfig, sttpBackend: Backend[Task]) {
  import config.*

  private inline val OrganizationIDHeader  = "X-Organization-ID"
  private inline val FileNameHeader        = "X-File-Name"
  private inline val CatalogueItemIDHeader = "X-Catalogue-Item-ID"

  given JsonValueCodec[smithy.OnboardStage] = new JsonValueCodec[smithy.OnboardStage] {
    override def decodeValue(in: JsonReader, default: smithy.OnboardStage): smithy.OnboardStage = {
      val onboardStageRaw = in.readString(null)
      smithy.OnboardStage
        .fromString(onboardStageRaw)
        .getOrElse(in.decodeError(s"Unknown OnboardStage: [$onboardStageRaw]"))
    }

    override def encodeValue(onboardStage: smithy.OnboardStage, out: JsonWriter): Unit =
      out.writeVal(onboardStage.stringValue)

    override def nullValue: smithy.OnboardStage = null
  }

  given JsonValueCodec[smithy.AddressType] = new JsonValueCodec[smithy.AddressType] {
    override def decodeValue(in: JsonReader, default: smithy.AddressType): smithy.AddressType = {
      val addressTypeRaw = in.readString(null)
      smithy.AddressType
        .fromString(addressTypeRaw)
        .getOrElse(in.decodeError(s"Unknown AddressType: [$addressTypeRaw]"))
    }

    override def encodeValue(addressType: smithy.AddressType, out: JsonWriter): Unit =
      out.writeVal(addressType.stringValue)

    override def nullValue: smithy.AddressType = null
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

  given JsonValueCodec[smithy.CustomerType] = new JsonValueCodec[smithy.CustomerType] {
    override def decodeValue(in: JsonReader, default: smithy.CustomerType): smithy.CustomerType = {
      val customerTypeRaw = in.readString(null)
      smithy.CustomerType
        .fromString(customerTypeRaw)
        .getOrElse(in.decodeError(s"Unknown CustomerType: [$customerTypeRaw]"))
    }

    override def encodeValue(customerType: smithy.CustomerType, out: JsonWriter): Unit =
      out.writeVal(customerType.stringValue)

    override def nullValue: smithy.CustomerType = null
  }

  given JsonValueCodec[smithy.Currency] = new JsonValueCodec[smithy.Currency] {
    override def decodeValue(in: JsonReader, default: smithy.Currency): smithy.Currency = {
      val currencyRaw = in.readString(null)
      smithy.Currency
        .fromString(currencyRaw)
        .getOrElse(in.decodeError(s"Unknown Currency: [$currencyRaw]"))
    }

    override def encodeValue(currency: smithy.Currency, out: JsonWriter): Unit =
      out.writeVal(currency.stringValue)

    override def nullValue: smithy.Currency = null
  }

  given JsonValueCodec[smithy.CatalogueItemVariantUnit] = new JsonValueCodec[smithy.CatalogueItemVariantUnit] {
    override def decodeValue(
        in: JsonReader,
        default: smithy.CatalogueItemVariantUnit,
    ): smithy.CatalogueItemVariantUnit = {
      val catalogueItemVariantUnitRaw = in.readString(null)
      smithy.CatalogueItemVariantUnit
        .fromString(catalogueItemVariantUnitRaw)
        .getOrElse(in.decodeError(s"Unknown CatalogueItemVariantUnit: [$catalogueItemVariantUnitRaw]"))
    }

    override def encodeValue(catalogueItemVariantUnit: smithy.CatalogueItemVariantUnit, out: JsonWriter): Unit =
      out.writeVal(catalogueItemVariantUnit.stringValue)

    override def nullValue: smithy.CatalogueItemVariantUnit = null
  }

  given JsonValueCodec[smithy.CatalogueItemVariantUnitPerUnit] =
    new JsonValueCodec[smithy.CatalogueItemVariantUnitPerUnit] {
      override def decodeValue(
          in: JsonReader,
          default: smithy.CatalogueItemVariantUnitPerUnit,
      ): smithy.CatalogueItemVariantUnitPerUnit = {
        val catalogueItemVariantUnitPerUnitRaw = in.readString(null)
        smithy.CatalogueItemVariantUnitPerUnit
          .fromString(catalogueItemVariantUnitPerUnitRaw)
          .getOrElse(
            in.decodeError(s"Unknown CatalogueItemVariantUnitPerUnit: [$catalogueItemVariantUnitPerUnitRaw]")
          )
      }

      override def encodeValue(
          catalogueItemVariantUnitPerUnit: smithy.CatalogueItemVariantUnitPerUnit,
          out: JsonWriter,
      ): Unit =
        out.writeVal(catalogueItemVariantUnitPerUnit.stringValue)

      override def nullValue: smithy.CatalogueItemVariantUnitPerUnit = null
    }

  given JsonValueCodec[smithy.CatalogueItemVariantUnitUnitSize] =
    new JsonValueCodec[smithy.CatalogueItemVariantUnitUnitSize] {
      override def decodeValue(
          in: JsonReader,
          default: smithy.CatalogueItemVariantUnitUnitSize,
      ): smithy.CatalogueItemVariantUnitUnitSize = {
        val catalogueItemVariantUnitUnitSizeRaw = in.readString(null)
        smithy.CatalogueItemVariantUnitUnitSize
          .fromString(catalogueItemVariantUnitUnitSizeRaw)
          .getOrElse(
            in.decodeError(s"Unknown CatalogueItemVariantUnitUnitSize: [$catalogueItemVariantUnitUnitSizeRaw]")
          )
      }

      override def encodeValue(
          catalogueItemVariantUnitUnitSize: smithy.CatalogueItemVariantUnitUnitSize,
          out: JsonWriter,
      ): Unit =
        out.writeVal(catalogueItemVariantUnitUnitSize.stringValue)

      override def nullValue: smithy.CatalogueItemVariantUnitUnitSize = null
    }

  given JsonValueCodec[smithy.CatalogueItemStatus] = new JsonValueCodec[smithy.CatalogueItemStatus] {
    override def decodeValue(in: JsonReader, default: smithy.CatalogueItemStatus): smithy.CatalogueItemStatus = {
      val catalogueItemStatusRaw = in.readString(null)
      smithy.CatalogueItemStatus
        .fromString(catalogueItemStatusRaw)
        .getOrElse(in.decodeError(s"Unknown CatalogueItemStatus: [$catalogueItemStatusRaw]"))
    }

    override def encodeValue(catalogueItemStatus: smithy.CatalogueItemStatus, out: JsonWriter): Unit =
      out.writeVal(catalogueItemStatus.stringValue)

    override def nullValue: smithy.CatalogueItemStatus = null
  }

  given JsonValueCodec[smithy.CatalogueItemVariantStatus] = new JsonValueCodec[smithy.CatalogueItemVariantStatus] {
    override def decodeValue(
        in: JsonReader,
        default: smithy.CatalogueItemVariantStatus,
    ): smithy.CatalogueItemVariantStatus = {
      val catalogueItemVariantStatusRaw = in.readString(null)
      smithy.CatalogueItemVariantStatus
        .fromString(catalogueItemVariantStatusRaw)
        .getOrElse(in.decodeError(s"Unknown CatalogueItemVariantStatus: [$catalogueItemVariantStatusRaw]"))
    }

    override def encodeValue(
        catalogueItemVariantStatus: smithy.CatalogueItemVariantStatus,
        out: JsonWriter,
    ): Unit =
      out.writeVal(catalogueItemVariantStatus.stringValue)

    override def nullValue: smithy.CatalogueItemVariantStatus = null
  }

  inline given nullableJsonValueCodec[A]: JsonValueCodec[Nullable[A]] = {
    val optionJsonValueCodec =
      JsonCodecMaker.make[Option[A]](CodecMakerConfig.withTransientEmpty(false))

    nullableJsonValueCodec(optionJsonValueCodec)
  }

  private def nullableJsonValueCodec[A](
      optionJsonValueCodec: JsonValueCodec[Option[A]]
  ): JsonValueCodec[Nullable[A]] =
    new JsonValueCodec[Nullable[A]] {
      override def decodeValue(in: JsonReader, default: Nullable[A]): Nullable[A] =
        Nullable.fromOption(optionJsonValueCodec.decodeValue(in, None))

      override def encodeValue(nullable: Nullable[A], out: JsonWriter): Unit =
        optionJsonValueCodec.encodeValue(nullable.toOption, out)

      override def nullValue: Nullable[A] = Nullable.Null
    }

  given JsonValueCodec[smithy.InsertCustomerIndividualPostRequest] =
    JsonCodecMaker.make[smithy.InsertCustomerIndividualPostRequest](CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[smithy.InsertCustomerIndividualsPostRequest] =
    JsonCodecMaker.make[smithy.InsertCustomerIndividualsPostRequest](CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[smithy.InsertCustomerBusinessPostRequest] =
    JsonCodecMaker.make[smithy.InsertCustomerBusinessPostRequest](CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[smithy.InsertCustomerBusinessesPostRequest] =
    JsonCodecMaker.make[smithy.InsertCustomerBusinessesPostRequest](CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[smithy.InsertCustomersPostRequest] =
    JsonCodecMaker.make[smithy.InsertCustomersPostRequest](CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[smithy.InsertCustomerIndividualPostResponse] =
    JsonCodecMaker.make[smithy.InsertCustomerIndividualPostResponse]
  given JsonValueCodec[smithy.InsertCustomerIndividualsPostResponse] =
    JsonCodecMaker.make[smithy.InsertCustomerIndividualsPostResponse]
  given JsonValueCodec[smithy.InsertCustomerBusinessPostResponse] =
    JsonCodecMaker.make[smithy.InsertCustomerBusinessPostResponse]
  given JsonValueCodec[smithy.InsertCustomerBusinessesPostResponse] =
    JsonCodecMaker.make[smithy.InsertCustomerBusinessesPostResponse]
  given JsonValueCodec[smithy.InsertCustomersPostResponse] =
    JsonCodecMaker.make[smithy.InsertCustomersPostResponse]
  given JsonValueCodec[smithy.UpdateCustomerIndividualPutRequest] =
    JsonCodecMaker.make[smithy.UpdateCustomerIndividualPutRequest](CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[smithy.UpdateCustomerBusinessPutRequest] =
    JsonCodecMaker.make[smithy.UpdateCustomerBusinessPutRequest](CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[smithy.AddCustomerBusinessContactsPutRequest] =
    JsonCodecMaker.make[smithy.AddCustomerBusinessContactsPutRequest](CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[smithy.RemoveCustomerBusinessContactsPutRequest] =
    JsonCodecMaker.make[smithy.RemoveCustomerBusinessContactsPutRequest](CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[smithy.ArchiveCustomerPutRequest] =
    JsonCodecMaker.make[smithy.ArchiveCustomerPutRequest]
  given JsonValueCodec[smithy.CatalogueItemPostRequest] =
    JsonCodecMaker.make[smithy.CatalogueItemPostRequest](CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[smithy.CatalogueItemBatchPostRequest] =
    JsonCodecMaker.make[smithy.CatalogueItemBatchPostRequest](CodecMakerConfig.withTransientEmpty(false))
  given JsonValueCodec[smithy.CatalogueItemPutRequest] =
    JsonCodecMaker.make[smithy.CatalogueItemPutRequest](CodecMakerConfig.withTransientEmpty(false))

  given JsonValueCodec[smithy.GetCustomerIndividualGetResponse] =
    JsonCodecMaker.make[smithy.GetCustomerIndividualGetResponse]
  given JsonValueCodec[smithy.GetCustomerBusinessGetResponse] =
    JsonCodecMaker.make[smithy.GetCustomerBusinessGetResponse]
  given JsonValueCodec[smithy.GetCustomersGetResponse]        = JsonCodecMaker.make[smithy.GetCustomersGetResponse]
  given JsonValueCodec[smithy.CatalogueItemPostResponse]      = JsonCodecMaker.make[smithy.CatalogueItemPostResponse]
  given JsonValueCodec[smithy.CatalogueItemBatchPostResponse] =
    JsonCodecMaker.make[smithy.CatalogueItemBatchPostResponse]
  given JsonValueCodec[smithy.CatalogueItemGetResponse]        = JsonCodecMaker.make[smithy.CatalogueItemGetResponse]
  given JsonValueCodec[smithy.CatalogueItemSummaryGetResponse] =
    JsonCodecMaker.make[smithy.CatalogueItemSummaryGetResponse]
  given JsonValueCodec[smithy.CatalogueItemUniquenessGetResponse] =
    JsonCodecMaker.make[smithy.CatalogueItemUniquenessGetResponse]
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
      tagline: Option[OrganizationTagline],
      emails: List[OrganizationEmailEntryRequest],
      phoneNumbers: List[OrganizationPhoneNumberEntryRequest],
      addresses: List[OrganizationAddressEntry],
      companyRegistrationNumberOpt: Option[OrganizationCompanyRegistrationNumber],
      taxIDOpt: Option[OrganizationTaxID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.CreateOrganizationPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("create", "organization"))
      .body(
        asJson(
          smithy.CreateOrganizationPostRequest(
            name = name.value,
            slug = slug.value,
            tagline = tagline.map(_.value),
            emails = emails.map(entry => smithy.OrganizationEmailEntryRequest(entry.email.value, entry.isDefault)),
            phoneNumbers = phoneNumbers.map(entry =>
              smithy.OrganizationPhoneNumberEntryRequest(
                smithy.PhoneNumberRequest(
                  entry.phoneNumber.value.phoneNationalNumber.value,
                  entry.phoneNumber.value.phoneCountryCode.value,
                ),
                entry.isDefault,
              )
            ),
            addresses = addresses.transformInto[List[smithy.AddressEntryRequest]],
            companyRegistrationNumber = companyRegistrationNumberOpt.map(_.value),
            taxID = taxIDOpt.map(_.value),
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
      organizationIDOpt: Option[OrganizationID],
      organizationLogoImageOriginalFileNameOpt: Option[ImageOriginalFileName],
      logoBytes: Chunk[Byte],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, Unit]]] =
    basicRequest
      .post(externalUri.addPath("upload", "organization", "logo"))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        organizationLogoImageOriginalFileNameOpt.fold(request)(organizationLogoImageOriginalFileName =>
          request.header(FileNameHeader, organizationLogoImageOriginalFileName.value)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .body(logoBytes.toArray)
      .contentType(MediaType.ApplicationOctetStream)
      .response(asJsonErrorUnit[E])
      .send(sttpBackend)

  def uploadCatalogueItemImagePost[E: JsonValueCodec](
      organizationIDOpt: Option[OrganizationID],
      catalogueItemIDOpt: Option[CatalogueItemID],
      catalogueItemImageOriginalFileNameOpt: Option[ImageOriginalFileName],
      imageBytes: Chunk[Byte],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, Unit]]] =
    basicRequest
      .post(externalUri.addPath("upload", "catalogue-item", "image"))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        catalogueItemIDOpt.fold(request)(catalogueItemID =>
          request.header(CatalogueItemIDHeader, catalogueItemID.value.toString)
        )
      )
      .pipe(request =>
        catalogueItemImageOriginalFileNameOpt.fold(request)(catalogueItemImageOriginalFileName =>
          request.header(FileNameHeader, catalogueItemImageOriginalFileName.value)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .body(imageBytes.toArray)
      .contentType(MediaType.ApplicationOctetStream)
      .response(asJsonErrorUnit[E])
      .send(sttpBackend)

  def extractCustomersPost[E: JsonValueCodec](
      organizationIDOpt: Option[OrganizationID],
      extractCustomersFileNameOpt: Option[ExtractCustomersFileName],
      extractCustomersFileBytes: Chunk[Byte],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, ExtractCustomersPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("extract", "customers"))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        extractCustomersFileNameOpt.fold(request)(extractCustomersFileName =>
          request.header(FileNameHeader, extractCustomersFileName.value)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .body(extractCustomersFileBytes.toArray)
      .contentType(MediaType.ApplicationOctetStream)
      .response(asJsonEitherOrFail[E, ExtractCustomersPostResponse])
      .send(sttpBackend)

  def insertCustomerIndividualPost[E: JsonValueCodec](
      insertCustomerIndividualPostRequest: smithy.InsertCustomerIndividualPostRequest,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.InsertCustomerIndividualPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("insert", "customer-individual"))
      .body(asJson(insertCustomerIndividualPostRequest))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.InsertCustomerIndividualPostResponse])
      .send(sttpBackend)

  def catalogueItemPost[E: JsonValueCodec](
      catalogueItemPostRequest: smithy.CatalogueItemPostRequest,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.CatalogueItemPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("catalogue", "item"))
      .body(asJson(catalogueItemPostRequest))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.CatalogueItemPostResponse])
      .send(sttpBackend)

  def catalogueItemBatchPost[E: JsonValueCodec](
      catalogueItemBatchPostRequest: smithy.CatalogueItemBatchPostRequest,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.CatalogueItemBatchPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("catalogue", "item", "batch"))
      .body(asJson(catalogueItemBatchPostRequest))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.CatalogueItemBatchPostResponse])
      .send(sttpBackend)

  def catalogueItemPut[E: JsonValueCodec](
      catalogueItemPutRequest: smithy.CatalogueItemPutRequest,
      catalogueItemID: CatalogueItemID,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, Unit]]] =
    basicRequest
      .put(externalUri.addPath("catalogue", "item", catalogueItemID.value.toString))
      .body(asJson(catalogueItemPutRequest))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonErrorUnit[E])
      .send(sttpBackend)

  def catalogueItemArchivePut[E: JsonValueCodec](
      catalogueItemID: CatalogueItemID,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, Unit]]] =
    basicRequest
      .put(externalUri.addPath("catalogue", "item", catalogueItemID.value.toString, "archive"))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonErrorUnit[E])
      .send(sttpBackend)

  def catalogueItemVariantArchivePut[E: JsonValueCodec](
      catalogueItemID: CatalogueItemID,
      catalogueItemVariantID: CatalogueItemVariantID,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, Unit]]] =
    basicRequest
      .put(
        externalUri.addPath(
          "catalogue",
          "item",
          catalogueItemID.value.toString,
          "variant",
          catalogueItemVariantID.value.toString,
          "archive",
        )
      )
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonErrorUnit[E])
      .send(sttpBackend)

  def catalogueItemGet[E: JsonValueCodec](
      catalogueItemID: CatalogueItemID,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.CatalogueItemGetResponse]]] =
    basicRequest
      .get(externalUri.addPath("catalogue", "item", catalogueItemID.value.toString))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.CatalogueItemGetResponse])
      .send(sttpBackend)

  def catalogueItemSummaryGet[E: JsonValueCodec](
      catalogueItemIDs: List[CatalogueItemID],
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.CatalogueItemSummaryGetResponse]]] = {
    val catalogueItemSummaryUri = catalogueItemIDs.foldLeft(externalUri.addPath("catalogue", "item", "summary")) {
      (uri, catalogueItemID) => uri.addParam("catalogueItemIDs", catalogueItemID.value.toString)
    }

    basicRequest
      .get(catalogueItemSummaryUri)
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.CatalogueItemSummaryGetResponse])
      .send(sttpBackend)
  }

  def catalogueItemUniquenessGet[E: JsonValueCodec](
      nameOpt: Option[String],
      skuOpt: Option[String],
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.CatalogueItemUniquenessGetResponse]]] = {
    val catalogueItemUniquenessUriWithName = nameOpt.fold(
      externalUri.addPath("catalogue", "item", "uniqueness")
    )(name => externalUri.addPath("catalogue", "item", "uniqueness").addParam("name", name))
    val catalogueItemUniquenessUri =
      skuOpt.fold(catalogueItemUniquenessUriWithName)(sku => catalogueItemUniquenessUriWithName.addParam("sku", sku))

    basicRequest
      .get(catalogueItemUniquenessUri)
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.CatalogueItemUniquenessGetResponse])
      .send(sttpBackend)
  }

  def insertCustomerIndividualsPost[E: JsonValueCodec](
      insertCustomerIndividualsPostRequest: smithy.InsertCustomerIndividualsPostRequest,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.InsertCustomerIndividualsPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("insert", "customer-individuals"))
      .body(asJson(insertCustomerIndividualsPostRequest))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.InsertCustomerIndividualsPostResponse])
      .send(sttpBackend)

  def insertCustomerBusinessPost[E: JsonValueCodec](
      insertCustomerBusinessPostRequest: smithy.InsertCustomerBusinessPostRequest,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.InsertCustomerBusinessPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("insert", "customer-business"))
      .body(asJson(insertCustomerBusinessPostRequest))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.InsertCustomerBusinessPostResponse])
      .send(sttpBackend)

  def insertCustomerBusinessesPost[E: JsonValueCodec](
      insertCustomerBusinessesPostRequest: smithy.InsertCustomerBusinessesPostRequest,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.InsertCustomerBusinessesPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("insert", "customer-businesses"))
      .body(asJson(insertCustomerBusinessesPostRequest))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.InsertCustomerBusinessesPostResponse])
      .send(sttpBackend)

  def insertCustomersPost[E: JsonValueCodec](
      insertCustomersPostRequest: smithy.InsertCustomersPostRequest,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.InsertCustomersPostResponse]]] =
    basicRequest
      .post(externalUri.addPath("insert", "customers"))
      .body(asJson(insertCustomersPostRequest))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.InsertCustomersPostResponse])
      .send(sttpBackend)

  def updateCustomerIndividualPut[E: JsonValueCodec](
      updateCustomerIndividualPutRequest: smithy.UpdateCustomerIndividualPutRequest,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, Unit]]] =
    basicRequest
      .put(externalUri.addPath("update", "customer-individual"))
      .body(asJson(updateCustomerIndividualPutRequest))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonErrorUnit[E])
      .send(sttpBackend)

  def updateCustomerBusinessPut[E: JsonValueCodec](
      updateCustomerBusinessPutRequest: smithy.UpdateCustomerBusinessPutRequest,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, Unit]]] =
    basicRequest
      .put(externalUri.addPath("update", "customer-business"))
      .body(asJson(updateCustomerBusinessPutRequest))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonErrorUnit[E])
      .send(sttpBackend)

  def addCustomerBusinessContactsPut[E: JsonValueCodec](
      addCustomerBusinessContactsPutRequest: smithy.AddCustomerBusinessContactsPutRequest,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, Unit]]] =
    basicRequest
      .put(externalUri.addPath("add", "customer-business-contacts"))
      .body(asJson(addCustomerBusinessContactsPutRequest))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonErrorUnit[E])
      .send(sttpBackend)

  def removeCustomerBusinessContactsPut[E: JsonValueCodec](
      removeCustomerBusinessContactsPutRequest: smithy.RemoveCustomerBusinessContactsPutRequest,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, Unit]]] =
    basicRequest
      .put(externalUri.addPath("remove", "customer-business-contacts"))
      .body(asJson(removeCustomerBusinessContactsPutRequest))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonErrorUnit[E])
      .send(sttpBackend)

  def archiveCustomerPut[E: JsonValueCodec](
      archiveCustomerPutRequest: smithy.ArchiveCustomerPutRequest,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, Unit]]] =
    basicRequest
      .put(externalUri.addPath("archive", "customer"))
      .body(asJson(archiveCustomerPutRequest))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonErrorUnit[E])
      .send(sttpBackend)

  def getCustomerIndividualGet[E: JsonValueCodec](
      customerID: CustomerID,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.GetCustomerIndividualGetResponse]]] =
    basicRequest
      .get(externalUri.addPath("get", "customer-individual", customerID.value.toString))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.GetCustomerIndividualGetResponse])
      .send(sttpBackend)

  def getCustomerBusinessGet[E: JsonValueCodec](
      customerID: CustomerID,
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.GetCustomerBusinessGetResponse]]] =
    basicRequest
      .get(externalUri.addPath("get", "customer-business", customerID.value.toString))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.GetCustomerBusinessGetResponse])
      .send(sttpBackend)

  def getCustomersGet[E: JsonValueCodec](
      organizationIDOpt: Option[OrganizationID],
      accessTokenOpt: Option[AccessToken],
  ): Task[Response[Either[E, smithy.GetCustomersGetResponse]]] =
    basicRequest
      .get(externalUri.addPath("get", "customers"))
      .pipe(request =>
        organizationIDOpt.fold(request)(organizationID =>
          request.header(OrganizationIDHeader, organizationID.value.toString)
        )
      )
      .pipe(request =>
        accessTokenOpt.fold(request)(accessToken =>
          request.header(HeaderNames.Authorization, s"Bearer ${accessToken.value}")
        )
      )
      .response(asJsonEitherOrFail[E, smithy.GetCustomersGetResponse])
      .send(sttpBackend)

}

object GatewayClient {

  given JsonValueCodec[smithy.ValidationError]     = JsonCodecMaker.make[smithy.ValidationError]
  given JsonValueCodec[smithy.BadRequest]          = JsonCodecMaker.make[smithy.BadRequest]
  given JsonValueCodec[smithy.Unauthorized]        = JsonCodecMaker.make[smithy.Unauthorized]
  given JsonValueCodec[smithy.UnauthorizedOtp]     = JsonCodecMaker.make[smithy.UnauthorizedOtp]
  given JsonValueCodec[smithy.Forbidden]           = JsonCodecMaker.make[smithy.Forbidden]
  given JsonValueCodec[smithy.Conflict]            = JsonCodecMaker.make[smithy.Conflict]
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
