package io.mesazon.gateway.it

import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

/** Mixed into every gateway acceptance spec. The parent [[GatewayAcceptanceSpec]] boots the one shared stack and injects
  * the context (via [[setContext]]) before the nested specs run, so a spec only calls `withContext` and gets a clean DB
  * and external state before each of its tests. Specs are `@DoNotDiscover` — they run only through the parent suite.
  */
trait GatewayAcceptanceTest extends ZWordSpecBase {

  @volatile private var injectedContext: GatewayItContext = scala.compiletime.uninitialized

  private[it] def setContext(context: GatewayItContext): Unit = injectedContext = context

  protected def withContext[A](f: GatewayItContext => A): A = f(injectedContext)

  override def beforeEach(): Unit = withContext { context =>
    import context.*

    super.beforeEach()

    // Full per-test isolation across the shared stack: truncate every table and reset each external service's state.
    ZIO
      .foreach(repositoryConfig.allTableNames)(tableName =>
        postgresClient.truncateTable(repositoryConfig.schema, tableName)
      )
      .zioValue

    mailHogClient.clearInbox().zioValue
    s3TestClient.emptyAllBuckets().zioValue
  }
}
