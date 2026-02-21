package io.rikkos.gateway

import com.comcast.ip4s.*
import io.mesazon.waha.config.WahaConfig
import io.rikkos.domain.gateway.AppName
import zio.*
import zio.config.*
import zio.config.magnolia.{deriveConfig, DeriveConfig}
import zio.config.typesafe.TypesafeConfigProvider

import scala.concurrent.duration.Duration as ScalaDuration

package object config {

  private[config] given DeriveConfig[Host] = DeriveConfig[String].mapAttempt(string => Host.fromString(string).get)

  private[config] given DeriveConfig[Port] = DeriveConfig[Int].mapAttempt(int => Port.fromInt(int).get)

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

  val wahaConfigLive = deriveConfigLayer[WahaConfig]("waha")
}
