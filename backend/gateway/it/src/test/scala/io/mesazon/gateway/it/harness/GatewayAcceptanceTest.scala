package io.mesazon.gateway.it.harness

import com.dimafeng.testcontainers.DockerComposeContainer
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

abstract class GatewayAcceptanceTest(dockerComposeContainer: => DockerComposeContainer) extends ZWordSpecBase {

  protected def withContext[A](f: GatewayItContext => Task[A]): A =
    ZIO
      .serviceWithZIO[GatewayItContext](f)
      .provide(GatewayItContext.live, ZLayer.succeed(dockerComposeContainer))
      .zioValue
}
