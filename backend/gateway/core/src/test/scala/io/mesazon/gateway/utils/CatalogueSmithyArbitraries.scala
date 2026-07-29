package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.*
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait CatalogueSmithyArbitraries extends CatalogueDomainArbitraries, IronRefinedTypeTransformer {

  given catalogueItemPriceTransformer: Transformer[CatalogueItemPrice, smithy.CatalogueItemPriceRequest] = price =>
    smithy.CatalogueItemPriceRequest(price.value.amount.value, price.value.currency.value)

  given arbInsertCatalogueItemPostRequestSmithy: Arbitrary[smithy.InsertCatalogueItemPostRequest] = Arbitrary(
    Arbitrary.arbitrary[InsertCatalogueItemPostRequest].map(_.transformInto[smithy.InsertCatalogueItemPostRequest])
  )

  given arbInsertCatalogueItemsPostRequestSmithy: Arbitrary[smithy.InsertCatalogueItemsPostRequest] = Arbitrary(
    Arbitrary.arbitrary[InsertCatalogueItemsPostRequest].map(_.transformInto[smithy.InsertCatalogueItemsPostRequest])
  )

  given arbUpdateCatalogueItemPutRequestSmithy: Arbitrary[smithy.UpdateCatalogueItemPutRequest] = Arbitrary(
    Arbitrary.arbitrary[UpdateCatalogueItemPutRequest].map(_.transformInto[smithy.UpdateCatalogueItemPutRequest])
  )
}
