package io.mesazon.gateway.tapir

import io.mesazon.gateway.service.{AuthorizationService, ServiceTask}
import zio.*

object TapirRoutes {

  val routes = for {
    _ <- ZIO.service[AuthorizationService[ServiceTask]]
  } yield ()

}
