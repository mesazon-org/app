package io.mesazon.gateway.repository

import com.zaxxer.hikari.HikariDataSource
import doobie.LogHandler
import io.github.gaelrenoux.tranzactio.doobie.{Database, DbContext}
import io.mesazon.gateway.config.DatabaseConfig
import zio.*

object PostgresTransactor {

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
      config     <- ZIO.service[DatabaseConfig]
      datasource <- ZIO.acquireRelease(ZIO.attempt {
        val hikariDataSource = new HikariDataSource()
        hikariDataSource.setDriverClassName(config.driver)
        hikariDataSource.setJdbcUrl(config.url)
        hikariDataSource.setUsername(config.username)
        hikariDataSource.setPassword(config.password)
        hikariDataSource.setMaximumPoolSize(config.threadPoolSize)
        hikariDataSource
      })(ds => ZIO.attemptBlocking(ds.close()).orDie <* ZIO.logWarning("HikariDataSource closed"))
    } yield datasource
  }

  private given DbContext = DbContext(logHandler)

  val live = datasourceLive >>> Database.fromDatasource
}
