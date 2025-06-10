package io.rikkos.test.postgresql

import com.dimafeng.testcontainers.{DockerComposeContainer, ExposedService}
import doobie.LogHandler
import doobie.hikari.HikariTransactor
import doobie.util.transactor.Transactor
import io.rikkos.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import zio.*
import zio.interop.catz.*

final case class PostgreSQLTestClient(config: PostgreSQLTestClientConfig, transactor: Transactor[Task])

object PostgreSQLTestClient {
  private lazy val defaultDatabase = "local_db"
  private lazy val defaultUser     = "postgres"
  private lazy val defaultPassword = "postgres"

  lazy val ServiceName     = "postgres"
  lazy val ServicePort     = 5432
  lazy val ExposedServices = Set(ExposedService(ServiceName, ServicePort))

  final case class PostgreSQLTestClientConfig(
      host: String = "localhost",
      port: Int = 5432,
      database: String = defaultDatabase,
      user: String = defaultUser,
      password: String = defaultPassword,
  ) {

    /** @param containers
      *   Option[DockerComposeContainer] * If provided resolves host and port with testcontainers
      * @param serviceName
      *   String container name
      * @param servicePort
      *   Int container port
      * @return
      *   PostgreSQLTestClientConfig
      */
    def adjust(
        containers: Option[DockerComposeContainer],
        serviceName: String = ServiceName,
        servicePort: Int = ServicePort,
    ): PostgreSQLTestClientConfig = containers match {
      case Some(containers) =>
        PostgreSQLTestClientConfig.from(
          containers,
          serviceName,
          servicePort,
        )
      case None => this
    }
  }

  object PostgreSQLTestClientConfig {

    /** @param containers
      *   DockerComposeContainer * Resolves host and port with testcontainers
      * @param serviceName
      *   String container name
      * @param servicePort
      *   Int container port
      * @return
      *   PostgreSQLTestClientConfig
      */
    def from(
        containers: DockerComposeContainer,
        serviceName: String = ServiceName,
        servicePort: Int = ServicePort,
        database: String = defaultDatabase,
        user: String = defaultUser,
        password: String = defaultPassword,
    ): PostgreSQLTestClientConfig = {
      val host = containers.getServiceHost(serviceName, servicePort)
      val port = containers.getServicePort(serviceName, servicePort)

      PostgreSQLTestClientConfig(host, port, database, user, password)
    }
  }

  private val logHandler: LogHandler[Task] = {
    case success: doobie.util.log.Success =>
      ZIO.logDebug(s"Database log handler: $success").unit
    case execFailure: doobie.util.log.ExecFailure =>
      ZIO.fiberId.flatMap(fid =>
        ZIO
          .logErrorCause(
            s"Database log handler: $execFailure",
            Cause.die(execFailure.failure, StackTrace.fromJava(fid, execFailure.failure.getStackTrace)),
          )
      )
    case processingFailure: doobie.util.log.ProcessingFailure =>
      ZIO.fiberId.flatMap(fid =>
        ZIO
          .logErrorCause(
            s"Database log handler: $processingFailure",
            Cause.die(processingFailure.failure, StackTrace.fromJava(fid, processingFailure.failure.getStackTrace)),
          )
      )
  }

  /** @param config
    *   PostgreSQLTestClientConfig * config for Transactor[Task]
    * @return
    *   Transactor[Task]
    */
  val live: RLayer[PostgreSQLTestClientConfig, PostgreSQLTestClient] = ZLayer.scoped {
    for {
      config <- ZIO.service[PostgreSQLTestClientConfig]
      transactor <- ZIO.executorWith { executor =>
        HikariTransactor
          .newHikariTransactor[Task](
            driverClassName = "org.postgresql.Driver",
            url = s"jdbc:postgresql://${config.host}:${config.port}/${config.database}",
            user = config.user,
            pass = config.password,
            connectEC = executor.asExecutionContext,
            logHandler = Some(logHandler),
          )
          .toScopedZIO
      }
    } yield PostgreSQLTestClient(config, transactor)
  }
}
