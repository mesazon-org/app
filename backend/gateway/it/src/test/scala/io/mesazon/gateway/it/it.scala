package io.mesazon.gateway.it

import io.mesazon.domain.gateway.AppName
import zio.ZLayer

val appNameLive = ZLayer.succeed(AppName("gateway-api-it"))
