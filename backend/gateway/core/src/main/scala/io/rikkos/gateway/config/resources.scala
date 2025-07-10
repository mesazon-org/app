package io.rikkos.gateway.config

import com.comcast.ip4s.*
import io.rikkos.domain.AppName
import zio.*
import zio.config.*
import zio.config.magnolia.{deriveConfig, DeriveConfig}
import zio.config.typesafe.TypesafeConfigProvider

given DeriveConfig[Host] = DeriveConfig[String].mapAttempt(string => Host.fromString(string).get)

given DeriveConfig[Port] = DeriveConfig[Int].mapAttempt(int => Port.fromInt(int).get)

def deriveConfigLayer[A: {Tag, DeriveConfig}](
    path: String
): ZLayer[AppName, Config.Error, A] =
  ZLayer {
    for {
      appName <- ZIO.service[AppName]
      derivedConfig  = deriveConfig[A]
      modifiedConfig = derivedConfig.nested(appName, path).mapKey(toKebabCase)
      config <- TypesafeConfigProvider.fromResourcePath().load(modifiedConfig)
    } yield config
  }
