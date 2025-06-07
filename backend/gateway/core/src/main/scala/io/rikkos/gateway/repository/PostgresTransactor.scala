package io.rikkos.gateway.repository

import doobie.*
import doobie.hikari.HikariTransactor
import doobie.util.log.*
import io.rikkos.gateway.config.DatabaseConfig
import zio.*
import zio.interop.catz.*

object PostgresTransactor {
  private val logHandler: LogHandler[Task] = (logEvent: LogEvent) =>
    logEvent match {
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

  val live: RLayer[DatabaseConfig, Transactor[Task]] = ZLayer.scoped {
    for {
      config <- ZIO.service[DatabaseConfig]
      transactor <- ZIO.executorWith { executor =>
        HikariTransactor
          .newHikariTransactor[Task](
            driverClassName = config.driver,
            url = s"jdbc:postgresql://${config.host}:${config.port}/${config.name}",
            user = config.user,
            pass = config.password,
            connectEC = executor.asExecutionContext,
            logHandler = Some(logHandler),
          )
          .toScopedZIO
      }
    } yield transactor
  }
}
