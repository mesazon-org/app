package io.mesazon.gateway.config

import com.google.i18n.phonenumbers.PhoneNumberUtil
import zio.*
import zio.Config.Error

import scala.jdk.CollectionConverters.SetHasAsScala

case class PhoneNumberValidatorConfig(
    supportedPhoneRegions: Set[String]
)

object PhoneNumberValidatorConfig {

  private[gateway] val supportedRegionsConfig
      : ZLayer[PhoneNumberUtil & PhoneNumberValidatorConfig, Error.Unsupported, PhoneNumberValidatorConfig] =
    ZLayer(for {
      config          <- ZIO.service[PhoneNumberValidatorConfig]
      phoneNumberUtil <- ZIO.service[PhoneNumberUtil]
      unsupportedRegions = config.supportedPhoneRegions.diff(phoneNumberUtil.getSupportedRegions.asScala.toSet)
      _ <-
        if (unsupportedRegions.isEmpty) ZIO.unit
        else
          ZIO.fail(
            Config.Error.Unsupported(
              path = Chunk("validation", "supported-regions"),
              s"Config pass unsupported regions ${unsupportedRegions.mkString("[", ", ", "]")}",
            )
          )
    } yield config.copy(supportedPhoneRegions = config.supportedPhoneRegions.map(_.trim.toUpperCase)))

  val live = deriveConfigLayer[PhoneNumberValidatorConfig]("validation") >>> supportedRegionsConfig
}
