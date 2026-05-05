package io.mesazon.test.postgresql

import com.dimafeng.testcontainers.*
import doobie.*
import doobie.implicits.*
import io.github.gaelrenoux.tranzactio.doobie.{tzio, Database, DbContext, TranzactIO}
import io.github.gaelrenoux.tranzactio.{DatabaseOps, DbException}
import org.postgresql.ds.PGSimpleDataSource
import zio.*

import PostgreSQLTestClient.PostgreSQLTestClientConfig

case class PostgreSQLTestClient(
    config: PostgreSQLTestClientConfig,
    database: DatabaseOps.ServiceOps[Transactor[Task]],
) {

  private def checkIfTableExistsQuery(schema: String, table: String): TranzactIO[Boolean] =
    tzio {
      sql"""
      SELECT EXISTS (
        SELECT 1 FROM information_schema.tables 
          WHERE table_schema = $schema AND table_name = $table
      )
     """.query[Boolean].unique
    }

  private def truncateTableQuery(schema: String, table: String): TranzactIO[Unit] = {
    val queryFragment = sql"""TRUNCATE TABLE """ ++ Fragment.const(s"$schema.$table CASCADE")
    tzio {
      queryFragment.update.run.map(_ => ())
    }
  }

  /** Check if the PostgreSQL database is ready for connections.
    */
  def checkIfTableExists(schema: String, table: String): Task[Boolean] =
    database.transactionOrDie(checkIfTableExistsQuery(schema, table))

  def truncateTable(schema: String, table: String): Task[Unit] =
    database.transactionOrDie(truncateTableQuery(schema, table))

  def executeQuery[A](query: ConnectionIO[A]): IO[DbException, A] = database.transactionOrDie(tzio(query))

  def executeQuery[A](query: TranzactIO[A]): IO[DbException, A] = database.transactionOrDie(query)
}

object PostgreSQLTestClient {
  private lazy val defaultDatabase = "local_db"
  private lazy val defaultUsername = "local_test_user"
  private lazy val defaultPassword = "local_test_password"

  lazy val ServiceName     = "postgres"
  lazy val ServicePort     = 5432
  lazy val ExposedServices = Set(ExposedService(ServiceName, ServicePort))

  case class PostgreSQLTestClientConfig(
      host: String = "localhost",
      port: Int = ServicePort,
      database: String = defaultDatabase,
      username: String = defaultUsername,
      password: String = defaultPassword,
  ) {
    val driver: String = "org.postgresql.Driver"
    val url: String    = s"jdbc:postgresql://$host:$port/$database"

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
        user: String = defaultUsername,
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

  private val datasourceLive = ZLayer.scoped {
    for {
      config     <- ZIO.service[PostgreSQLTestClientConfig]
      datasource <- ZIO.attempt {
        val pgDataSource = new PGSimpleDataSource()
        pgDataSource.setUrl(config.url)
        pgDataSource.setUser(config.username)
        pgDataSource.setPassword(config.password)
        pgDataSource
      }
    } yield datasource
  }

  private given DbContext = DbContext(logHandler)

  val live = datasourceLive >>> Database.fromDatasource >>> ZLayer.fromFunction(PostgreSQLTestClient.apply)
}
