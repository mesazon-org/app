package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.gateway.repository.PingRepository
import io.mesazon.gateway.utils.SmithyArbitraries
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.testkit.base.{DockerComposeBase, ZWordSpecBase}
import zio.*

import PostgreSQLTestClient.PostgreSQLTestClientConfig

class PingRepositorySpec extends ZWordSpecBase, SmithyArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  def withContext[A](f: PostgreSQLTestClient => A): A = withContainers { container =>
    val config               = PostgreSQLTestClientConfig.from(container)
    val postgreSQLTestClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(config))
      .zioValue

    f(postgreSQLTestClient)
  }

  "PingRepository" when {
    "ping" should {
      "receive successful response" in withContext { (client: PostgreSQLTestClient) =>
        val pingRepository = ZIO
          .service[PingRepository]
          .provide(
            PingRepository.live,
            ZLayer.succeed(client.database),
          )
          .zioValue

        eventually(
          assert(pingRepository.ping().zioEither.isRight)
        )
      }
    }
  }
}
