package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*
import zio.NonEmptyChunk

trait CatalogueDomainArbitraries extends GatewayArbitraries {

  given arbCatalogueItemStatus: Arbitrary[CatalogueItemStatus] =
    Arbitrary(Gen.oneOf(CatalogueItemStatus.values.toIndexedSeq))

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

  given arbCatalogueItemVariantInsert: Arbitrary[CatalogueItemVariantInsert] =
    Arbitrary(Gen.resultOf(CatalogueItemVariantInsert.apply))

  given arbCatalogueItemVariantUpdate: Arbitrary[CatalogueItemVariantUpdate] =
    Arbitrary(Gen.resultOf(CatalogueItemVariantUpdate.apply))

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

  given arbCatalogueItemPostRequest: Arbitrary[CatalogueItemPostRequest] = Arbitrary(
    for {
      catalogueItemName               <- Arbitrary.arbitrary[CatalogueItemName]
      catalogueItemDescriptionOpt     <- Arbitrary.arbitrary[Option[CatalogueItemDescription]]
      catalogueItemVariantInsertHead  <- Arbitrary.arbitrary[CatalogueItemVariantInsert]
      catalogueItemVariantInsertsTail <- Gen
        .choose(0, 9)
        .flatMap(size => Gen.listOfN(size, Arbitrary.arbitrary[CatalogueItemVariantInsert]))
      catalogueItemVariantInserts = NonEmptyChunk.fromIterable(
        catalogueItemVariantInsertHead,
        catalogueItemVariantInsertsTail,
      )
    } yield CatalogueItemPostRequest(catalogueItemName, catalogueItemDescriptionOpt, catalogueItemVariantInserts)
  )

  given arbCatalogueItemBatchPostRequest: Arbitrary[CatalogueItemBatchPostRequest] = Arbitrary(
    for {
      catalogueItemPostRequestHead <- Arbitrary.arbitrary[CatalogueItemPostRequest]
      catalogueItemPostRequestTail <- Gen
        .choose(0, 9)
        .flatMap(size => Gen.listOfN(size, Arbitrary.arbitrary[CatalogueItemPostRequest]))
      catalogueItemPostRequests = NonEmptyChunk.fromIterable(
        catalogueItemPostRequestHead,
        catalogueItemPostRequestTail,
      )
    } yield CatalogueItemBatchPostRequest(catalogueItemPostRequests)
  )

  given arbCatalogueItemPutRequest: Arbitrary[CatalogueItemPutRequest] = Arbitrary(
    for {
      name           <- Arbitrary.arbitrary[Option[CatalogueItemName]]
      description    <- Arbitrary.arbitrary[Option[Option[CatalogueItemDescription]]]
      status         <- Arbitrary.arbitrary[Option[CatalogueItemStatus]]
      variantsUpdate <- Gen
        .choose(0, 10)
        .flatMap(size => Gen.listOfN(size, Arbitrary.arbitrary[CatalogueItemVariantUpdate]))
      variantsInsert <- Gen
        .choose(0, 10)
        .flatMap(size => Gen.listOfN(size, Arbitrary.arbitrary[CatalogueItemVariantInsert]))
    } yield CatalogueItemPutRequest(name, description, status, variantsUpdate, variantsInsert)
  )

  given arbCatalogueItemUniquenessGetRequest: Arbitrary[CatalogueItemUniquenessGetRequest] = Arbitrary(
    for {
      name    <- Arbitrary.arbitrary[CatalogueItemName]
      sku     <- Arbitrary.arbitrary[CatalogueItemVariantSku]
      request <- Gen.oneOf(
        CatalogueItemUniquenessGetRequest(Some(name), None),
        CatalogueItemUniquenessGetRequest(None, Some(sku)),
        CatalogueItemUniquenessGetRequest(Some(name), Some(sku)),
      )
    } yield request
  )
}
