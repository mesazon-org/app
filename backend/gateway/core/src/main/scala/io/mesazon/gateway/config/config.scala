package io.mesazon.gateway.config

import com.comcast.ip4s.*
import io.mesazon.domain.gateway.AppName
import io.mesazon.waha.config.WahaClientConfig
import sttp.model.Uri
import zio.*
import zio.config.*
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.config.typesafe.TypesafeConfigProvider

import scala.concurrent.duration.Duration as ScalaDuration

private[config] given DeriveConfig[Host] = DeriveConfig[String].mapAttempt(string => Host.fromString(string).get)

private[config] given DeriveConfig[Port] = DeriveConfig[Int].mapAttempt(int => Port.fromInt(int).get)

private[config] given DeriveConfig[Uri] = DeriveConfig[String].mapAttempt(str => Uri.unsafeApply(str))

private[config] given DeriveConfig[ScalaDuration] = DeriveConfig[Duration].mapAttempt(_.asScala)

private[config] def deriveConfigLayer[A: {Tag, DeriveConfig}](
    path: String
): ZLayer[AppName, Config.Error, A] =
  ZLayer {
    for {
      appName <- ZIO.service[AppName]
      derivedConfig  = deriveConfig[A]
      modifiedConfig = derivedConfig.nested(appName.value, path).mapKey(toKebabCase)
      config <- TypesafeConfigProvider.fromResourcePath().load(modifiedConfig)
    } yield config
  }

val wahaConfigLive = deriveConfigLayer[WahaClientConfig]("waha-client")
