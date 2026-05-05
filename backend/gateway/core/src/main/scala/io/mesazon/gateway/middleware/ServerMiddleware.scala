package io.mesazon.gateway.middleware

import io.mesazon.gateway.service.{AuthenticationService, AuthorizationService}
import io.mesazon.gateway.smithy as gatewaySmithy
import org.http4s.HttpApp
import smithy4s.Hints
import smithy4s.http4s.ServerEndpointMiddleware
import zio.*
import zio.interop.catz.*

object ServerMiddleware {

  // TODO: Test this middleware (issue https://github.com/eak-cy/app/issues/25)
  private final class ServerMiddlewareImpl(
      authorizationService: AuthorizationService[Task],
      authenticationService: AuthenticationService[Task],
  ) extends ServerEndpointMiddleware.Simple[Task] {

    override def prepareWithHints(serviceHints: Hints, endpointHints: Hints): HttpApp[Task] => HttpApp[Task] =
      (inputApp: HttpApp[Task]) =>
        (serviceHints.get[smithy.api.HttpBasicAuth], serviceHints.get[smithy.api.HttpBearerAuth]) match {
          case (Some(_), None) =>
            endpointHints.get[smithy.api.Auth] match {
              case Some(auths) if auths.value.isEmpty => inputApp
              case _                                  =>
                HttpApp[Task](request => authenticationService.auth(request) *> inputApp(request))
            }
          case (None, Some(_)) =>
            endpointHints.get[smithy.api.Auth] match {
              case Some(auths) if auths.value.isEmpty => inputApp
              case _                                  =>
                HttpApp[Task](request => authorizationService.auth(request) *> inputApp(request))
            }
          case (None, None)       => inputApp
          case (Some(_), Some(_)) =>
            HttpApp[Task](_ =>
              ZIO.logError("Multiple authentication types not supported") *>
                ZIO.fail(gatewaySmithy.InternalServerError())
            )
        }
  }

  private def observed(
      middleware: ServerEndpointMiddleware.Simple[Task]
  ): ServerEndpointMiddleware.Simple[Task] = middleware

  val live =
    ZLayer.derive[ServerMiddlewareImpl] >>> ZLayer.fromFunction(observed)
}
