package io.rikkos.gateway.middleware

import io.rikkos.gateway.auth.AuthorizationService
import org.http4s.HttpApp
import smithy4s.Hints
import smithy4s.http4s.ServerEndpointMiddleware
import zio.*
import zio.interop.catz.*

object ServerMiddleware {

  // TODO: Test this middleware (issue https://github.com/eak-cy/app/issues/25)
  private final class ServerMiddlewareImpl(authorizationService: AuthorizationService[Throwable])
      extends ServerEndpointMiddleware.Simple[Task] {

    override def prepareWithHints(serviceHints: Hints, endpointHints: Hints): HttpApp[Task] => HttpApp[Task] =
      (inputApp: HttpApp[Task]) =>
        serviceHints.get[smithy.api.HttpBearerAuth] match {
          case Some(_) =>
            endpointHints.get[smithy.api.Auth] match {
              case Some(auths) if auths.value.isEmpty => inputApp
              case _ => HttpApp[Task](request => authorizationService.auth(request) *> inputApp(request))
            }
          case None => inputApp
        }
  }

  private def observed(
      middleware: ServerEndpointMiddleware.Simple[Task]
  ): ServerEndpointMiddleware.Simple[Task] = middleware

  val live =
    ZLayer.derive[ServerMiddlewareImpl] >>> ZLayer.fromFunction(observed)
}
