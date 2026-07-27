package io.mesazon.gateway.it.harness

import io.mesazon.domain.gateway.AppName
import zio.ZLayer

val appNameLive = ZLayer.succeed(AppName("gateway-api-it"))
