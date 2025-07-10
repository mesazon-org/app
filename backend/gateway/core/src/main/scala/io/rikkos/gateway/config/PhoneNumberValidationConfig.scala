package io.rikkos.gateway.config

import com.google.i18n.phonenumbers.PhoneNumberUtil
import zio.*
import zio.Config.Error

import scala.jdk.CollectionConverters.SetHasAsScala

final case class PhoneNumberValidationConfig(
    supportedRegions: Set[String]
)

object PhoneNumberValidationConfig {

  private[gateway] val supportedRegionsConfig
      : ZLayer[PhoneNumberUtil & PhoneNumberValidationConfig, Error.Unsupported, PhoneNumberValidationConfig] =
    ZLayer(for {
      config          <- ZIO.service[PhoneNumberValidationConfig]
      phoneNumberUtil <- ZIO.service[PhoneNumberUtil]
      unsupportedRegions = config.supportedRegions.diff(phoneNumberUtil.getSupportedRegions.asScala.toSet)
      _ <-
        if (unsupportedRegions.isEmpty) ZIO.unit
        else
          ZIO.fail(
            Config.Error.Unsupported(
              path = Chunk("validation", "supported-regions"),
              s"Config pass unsupported regions ${unsupportedRegions.mkString("[", ", ", "]")}",
            )
          )
    } yield config)

  val live = deriveConfigLayer[PhoneNumberValidationConfig]("validation") >>> supportedRegionsConfig
}
