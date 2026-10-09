package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.CatalogueRepositoryV2
import io.mesazon.gateway.repository.queries.CatalogueQueries
import io.mesazon.gateway.utils.*
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.*
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import org.typelevel.doobie.implicits.*
import zio.*

import java.time.Instant

class CatalogueRepositoryV2Spec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  override def beforeAll(): Unit = {
    super.beforeAll()

    val testContext = new TestContext {}
    import testContext.*

    eventually {
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.catalogueItemTable)
        .zioValue shouldBe true
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.catalogueItemVariantTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    val testContext = new TestContext {}
    import testContext.*

    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.catalogueItemVariantTable).zioValue
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.catalogueItemTable).zioValue
    }
  }

  "CatalogueRepositoryV2" when {
    "catalogueItemVariantTable" should {
      "exist in the repository schema" in new TestContext {
        postgresClient
          .checkIfTableExists(repositoryConfig.schema, repositoryConfig.catalogueItemVariantTable)
          .zioValue shouldBe true
      }

      "store every catalogue item variant status as a native enum label in declaration order" in new TestContext {
        val catalogueItemVariantStatusTypeName = "catalogue_item_variant_status"
        val catalogueItemVariantStatusLabelsExpected =
          CatalogueItemVariantStatus.values.toList.map(_.toString)

        postgresClient
          .executeQuery(
            sql"""SELECT pg_enum.enumlabel
                  FROM pg_enum
                  JOIN pg_type ON pg_type.oid = pg_enum.enumtypid
                  JOIN pg_namespace ON pg_namespace.oid = pg_type.typnamespace
                  WHERE pg_namespace.nspname = ${repositoryConfig.schema}
                    AND pg_type.typname = $catalogueItemVariantStatusTypeName
                  ORDER BY pg_enum.enumsortorder"""
              .query[String]
              .to[List]
          )
          .zioValue shouldBe catalogueItemVariantStatusLabelsExpected
      }

      "keep a SKU unique per organization among non-archived variants that have a SKU" in new TestContext {
        val catalogueItemVariantSkuIndexName               = "uq_catalogue_item_variant_sku"
        val catalogueItemVariantSkuIndexDefinitionExpected =
          s"CREATE UNIQUE INDEX $catalogueItemVariantSkuIndexName " +
            s"ON ${repositoryConfig.schema}.${repositoryConfig.catalogueItemVariantTable} " +
            "USING btree (organization_id, sku) " +
            s"WHERE ((status <> 'Archived'::${repositoryConfig.schema}.catalogue_item_variant_status) " +
            "AND (sku IS NOT NULL))"

        postgresClient
          .executeQuery(
            sql"""SELECT indexdef
                  FROM pg_indexes
                  WHERE schemaname = ${repositoryConfig.schema}
                    AND indexname = $catalogueItemVariantSkuIndexName"""
              .query[String]
              .unique
          )
          .zioValue shouldBe catalogueItemVariantSkuIndexDefinitionExpected
      }

      "index variants by organization and catalogue item" in new TestContext {
        val catalogueItemVariantItemIndexName               = "idx_catalogue_item_variant_item"
        val catalogueItemVariantItemIndexDefinitionExpected =
          s"CREATE INDEX $catalogueItemVariantItemIndexName " +
            s"ON ${repositoryConfig.schema}.${repositoryConfig.catalogueItemVariantTable} " +
            "USING btree (organization_id, catalogue_item_id)"

        postgresClient
          .executeQuery(
            sql"""SELECT indexdef
                  FROM pg_indexes
                  WHERE schemaname = ${repositoryConfig.schema}
                    AND indexname = $catalogueItemVariantItemIndexName"""
              .query[String]
              .unique
          )
          .zioValue shouldBe catalogueItemVariantItemIndexDefinitionExpected
      }

      "reference the owning catalogue item by organization and ID with deletes restricted" in new TestContext {
        val catalogueItemVariantTableQualifiedName              =
          s"${repositoryConfig.schema}.${repositoryConfig.catalogueItemVariantTable}"
        val foreignKeyConstraintType                            = "f"
        val catalogueItemVariantForeignKeyDefinitionsExpected   = List(
          "FOREIGN KEY (organization_id, catalogue_item_id) " +
            s"REFERENCES ${repositoryConfig.schema}.${repositoryConfig.catalogueItemTable}" +
            "(organization_id, catalogue_item_id) ON DELETE RESTRICT"
        )

        postgresClient
          .executeQuery(
            sql"""SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                  WHERE conrelid = $catalogueItemVariantTableQualifiedName::regclass
                    AND contype = $foreignKeyConstraintType::"char""""
              .query[String]
              .to[List]
          )
          .zioValue shouldBe catalogueItemVariantForeignKeyDefinitionsExpected
      }
    }
  }

  trait TestContext {
    val instantNow: Instant = arbitrarySample[CreatedAt].value

    val repositoryConfig: RepositoryConfig = RepositoryConfig(
      schema = "local_schema",
      catalogueItemTable = "catalogue_item",
      catalogueItemVariantTable = "catalogue_item_variant",
    )

    val postgresClientConfig = withContainers(PostgreSQLTestClientConfig.from(_))

    val postgresClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgresClientConfig))
      .zioValue

    val catalogueQueries = ZIO
      .service[CatalogueQueries]
      .provide(CatalogueQueries.live, ZLayer.succeed(repositoryConfig))
      .zioValue

    val timeProviderMock = mock[TimeProvider]
    val idGeneratorMock  = mock[IDGenerator]

    val catalogueRepositoryV2 = ZIO
      .service[CatalogueRepositoryV2]
      .provide(CatalogueRepositoryV2.live)
      .zioValue
  }
}
