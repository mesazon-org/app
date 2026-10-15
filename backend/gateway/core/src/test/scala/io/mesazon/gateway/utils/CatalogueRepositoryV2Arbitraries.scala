package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.CatalogueRepositoryV2.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.testkit.base.*
import org.scalacheck.*
import zio.NonEmptyChunk

trait CatalogueRepositoryV2Arbitraries extends CatalogueDomainArbitraries {

  given arbCatalogueItemStatus: Arbitrary[CatalogueItemStatus] =
    Arbitrary(Gen.oneOf(CatalogueItemStatus.values.toIndexedSeq))

  given arbCatalogueItemRowV2: Arbitrary[CatalogueItemRowV2] = Arbitrary(Gen.resultOf(CatalogueItemRowV2.apply))

  given arbCatalogueItemVariantRow: Arbitrary[CatalogueItemVariantRow] =
    Arbitrary(Gen.resultOf(CatalogueItemVariantRow.apply))

  given arbCatalogueItemVariantRows: Arbitrary[NonEmptyChunk[CatalogueItemVariantRow]] = Arbitrary(
    for {
      catalogueItemVariantRowHead  <- Arbitrary.arbitrary[CatalogueItemVariantRow]
      catalogueItemVariantRowsTail <- Gen
        .choose(0, 9)
        .flatMap(size => Gen.listOfN(size, Arbitrary.arbitrary[CatalogueItemVariantRow]))
    } yield NonEmptyChunk.fromIterable(catalogueItemVariantRowHead, catalogueItemVariantRowsTail)
  )

  given arbCatalogueItemWithVariantsRow: Arbitrary[CatalogueItemWithVariantsRow] =
    Arbitrary(Gen.resultOf(CatalogueItemWithVariantsRow.apply))

  given arbCatalogueItemVariantSummaryRow: Arbitrary[CatalogueItemVariantSummaryRow] =
    Arbitrary(Gen.resultOf(CatalogueItemVariantSummaryRow.apply))

  given arbCatalogueItemVariantSummaryRows: Arbitrary[NonEmptyChunk[CatalogueItemVariantSummaryRow]] = Arbitrary(
    for {
      catalogueItemVariantSummaryRowHead  <- Arbitrary.arbitrary[CatalogueItemVariantSummaryRow]
      catalogueItemVariantSummaryRowsTail <- Gen
        .choose(0, 9)
        .flatMap(size => Gen.listOfN(size, Arbitrary.arbitrary[CatalogueItemVariantSummaryRow]))
    } yield NonEmptyChunk.fromIterable(catalogueItemVariantSummaryRowHead, catalogueItemVariantSummaryRowsTail)
  )

  given arbCatalogueItemSummaryRowV2: Arbitrary[CatalogueItemSummaryRowV2] =
    Arbitrary(Gen.resultOf(CatalogueItemSummaryRowV2.apply))

  given arbInsertCatalogueItemVariantInput: Arbitrary[InsertCatalogueItemVariantInput] = Arbitrary(
    for {
      insertCatalogueItemVariantInput <- Gen.resultOf(InsertCatalogueItemVariantInput.apply)
      enabledStatus                   <- Gen.oneOf(CatalogueItemVariantStatus.enabled)
    } yield insertCatalogueItemVariantInput.copy(status = enabledStatus)
  )

  given arbInsertCatalogueItemVariantInputs: Arbitrary[NonEmptyChunk[InsertCatalogueItemVariantInput]] = Arbitrary(
    for {
      insertCatalogueItemVariantInputHead  <- Arbitrary.arbitrary[InsertCatalogueItemVariantInput]
      insertCatalogueItemVariantInputsTail <- Gen
        .choose(0, 9)
        .flatMap(size => Gen.listOfN(size, Arbitrary.arbitrary[InsertCatalogueItemVariantInput]))
    } yield NonEmptyChunk.fromIterable(insertCatalogueItemVariantInputHead, insertCatalogueItemVariantInputsTail)
  )

  given arbInsertCatalogueItemInput: Arbitrary[InsertCatalogueItemInput] =
    Arbitrary(Gen.resultOf(InsertCatalogueItemInput.apply))

  given arbInsertCatalogueItemInputs: Arbitrary[List[InsertCatalogueItemInput]] = Arbitrary(
    Gen
      .choose(0, 10)
      .flatMap(size => Gen.listOfN(size, Arbitrary.arbitrary[InsertCatalogueItemInput]))
  )

  given arbUpdateCatalogueItemVariantInput: Arbitrary[UpdateCatalogueItemVariantInput] = Arbitrary(
    for {
      updateCatalogueItemVariantInput <- Gen.resultOf(UpdateCatalogueItemVariantInput.apply)
      enabledStatusOpt                <- Gen.option(Gen.oneOf(CatalogueItemVariantStatus.enabled))
    } yield updateCatalogueItemVariantInput.copy(status = enabledStatusOpt)
  )
}
