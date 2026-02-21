package io.rikkos.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.rikkos.gateway.repository.PingRepository
import io.rikkos.gateway.utils.SmithyArbitraries
import io.rikkos.test.postgresql.PostgreSQLTestClient
import io.rikkos.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.rikkos.testkit.base.*
import zio.{Clock as _, *}

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
