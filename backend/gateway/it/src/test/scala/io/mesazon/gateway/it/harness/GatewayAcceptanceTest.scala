package io.mesazon.gateway.it.harness

import io.mesazon.testkit.base.ZWordSpecBase
import zio.ZIO

trait GatewayAcceptanceTest extends ZWordSpecBase {

  @volatile private var context: GatewayItContext = scala.compiletime.uninitialized

  private[it] def setContext(context: GatewayItContext): Unit = this.context = context

  protected def withContext[A](f: GatewayItContext => A): A = f(context)

  override def beforeEach(): Unit = {
    super.beforeEach()
    val resetState = ZIO.foreachDiscard(context.repositoryConfig.allTableNames)(tableName =>
      context.postgresClient.truncateTable(context.repositoryConfig.schema, tableName)
    ) *> context.mailHogClient.clearInbox() *> context.s3TestClient.emptyAllBuckets()
    resetState.zioValue
  }
}
