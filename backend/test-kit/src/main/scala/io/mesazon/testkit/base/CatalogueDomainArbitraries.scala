package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

trait CatalogueDomainArbitraries extends GatewayArbitraries {

  given arbCatalogueItemPrice: Arbitrary[CatalogueItemPrice] = Arbitrary(
    Arbitrary.arbitrary[Price].map(CatalogueItemPrice.apply)
  )

  given arbInsertCatalogueItemPostRequest: Arbitrary[InsertCatalogueItemPostRequest] = Arbitrary(
    Gen.resultOf(InsertCatalogueItemPostRequest.apply)
  )

  given arbInsertCatalogueItemsPostRequest: Arbitrary[InsertCatalogueItemsPostRequest] = Arbitrary(
    Gen
      .choose(0, 50)
      .flatMap(size => Gen.listOfN(size, Arbitrary.arbitrary[InsertCatalogueItemPostRequest]))
      .map(InsertCatalogueItemsPostRequest.apply)
  )

  given arbUpdateCatalogueItemPutRequest: Arbitrary[UpdateCatalogueItemPutRequest] = Arbitrary(
    Gen.resultOf(UpdateCatalogueItemPutRequest.apply)
  )
}
