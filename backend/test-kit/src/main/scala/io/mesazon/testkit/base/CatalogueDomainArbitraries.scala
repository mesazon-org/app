package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

trait CatalogueDomainArbitraries extends GatewayArbitraries {

  given arbCatalogueItemPrice: Arbitrary[CatalogueItemPrice] = Arbitrary(
    Arbitrary.arbitrary[Price].map(CatalogueItemPrice.apply)
  )

  given arbCatalogueItemVariantPrice: Arbitrary[CatalogueItemVariantPrice] = Arbitrary(
    Arbitrary.arbitrary[Price].map(CatalogueItemVariantPrice.apply)
  )

  given arbCatalogueItemVariantStatus: Arbitrary[CatalogueItemVariantStatus] =
    Arbitrary(Gen.oneOf(CatalogueItemVariantStatus.values.toIndexedSeq))

  given arbCatalogueItemVariantUnit: Arbitrary[CatalogueItemVariantUnit] =
    Arbitrary(Gen.oneOf(CatalogueItemVariantUnit.values.toIndexedSeq))

  given arbCatalogueItemVariantUnitPerUnit: Arbitrary[CatalogueItemVariantUnit.PerUnit] =
    Arbitrary(Gen.oneOf(CatalogueItemVariantUnit.PerUnit.values.toIndexedSeq))

  given arbCatalogueItemVariantUnitUnitSize: Arbitrary[CatalogueItemVariantUnit.UnitSize] =
    Arbitrary(Gen.oneOf(CatalogueItemVariantUnit.UnitSize.values.toIndexedSeq))

  given arbCatalogueItemVariantQuantity: Arbitrary[CatalogueItemVariantQuantity] =
    Arbitrary(Gen.resultOf(CatalogueItemVariantQuantity.apply))

  given arbCatalogueItemVariantPerUnit: Arbitrary[CatalogueItemVariantPerUnit] =
    Arbitrary(Gen.resultOf(CatalogueItemVariantPerUnit.apply))

  given arbCatalogueItemVariantUnitSize: Arbitrary[CatalogueItemVariantUnitSize] =
    Arbitrary(Gen.resultOf(CatalogueItemVariantUnitSize.apply))

  given arbCatalogueItemVariantSaleUnit: Arbitrary[CatalogueItemVariantSaleUnit] =
    Arbitrary(Gen.resultOf(CatalogueItemVariantSaleUnit.apply))

  given arbCatalogueItemVariantAttributes
      : Arbitrary[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]] = Arbitrary(
    Gen
      .choose(0, 10)
      .flatMap(size =>
        Gen.mapOfN(
          size,
          Gen.zip(
            Arbitrary.arbitrary[CatalogueItemVariantAttributeName],
            Arbitrary.arbitrary[CatalogueItemVariantAttributeValue],
          ),
        )
      )
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
