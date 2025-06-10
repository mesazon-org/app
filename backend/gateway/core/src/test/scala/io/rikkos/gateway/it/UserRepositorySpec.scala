package io.rikkos.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.rikkos.domain.*
import io.rikkos.gateway.repository.UserRepository
import io.rikkos.gateway.utils.GatewayArbitraries
import io.rikkos.test.postgresql.PostgreSQLTestClient
import io.rikkos.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.rikkos.testkit.base.*
import zio.{ZIO, ZLayer}

class UserRepositorySpec extends ZWordSpecBase, GatewayArbitraries, DockerComposeBase {

  override def dockerComposeFile: String            = "/src/test/resources/compose.yaml"
  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  def withContext[A](f: PostgreSQLTestClient => A): A = withContainers { container =>
    val config = PostgreSQLTestClientConfig.from(container)
    val postgreSQLTestClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(config))
      .zioValue

    f(postgreSQLTestClient)
  }

  "UserRepository" should {
    "insert user details" in withContext { client: PostgreSQLTestClient =>
      val input = arbitrarySample[OnboardUserDetails]
      val repository = ZIO
        .service[UserRepository]
        .provide(UserRepository.live)

      for {
        userDetails <- atbit[OnboardUserDetails]
        _           <- userRepository.insertUserDetails(userDetails)
      } yield succeed
    }
  }
}
