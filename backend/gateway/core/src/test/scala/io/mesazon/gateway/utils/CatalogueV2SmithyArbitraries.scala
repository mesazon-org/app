package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.*
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl.*
import org.scalacheck.*
import smithy4s.Nullable

trait CatalogueV2SmithyArbitraries extends CatalogueDomainArbitraries, IronRefinedTypeTransformer {

  given catalogueItemVariantPriceTransformer
      : Transformer[CatalogueItemVariantPrice, smithy.v2.CatalogueItemVariantPrice] =
    price =>
      smithy.v2.CatalogueItemVariantPrice(
        amount = price.value.amount.value,
        currency = Currency.valueOf(price.value.currency.value).transformInto[smithy.v2.Currency],
      )

  given optionNullableTransformer[Source, Target](using
      transformer: Transformer[Source, Target]
  ): Transformer[Option[Source], Nullable[Target]] =
    value => Nullable.fromOption(value.map(transformer.transform))

  given catalogueItemPostRequestTransformer: Transformer[CatalogueItemPostRequest, smithy.v2.CatalogueItemPostRequest] =
    catalogueItemPostRequest =>
      catalogueItemPostRequest
        .into[smithy.v2.CatalogueItemPostRequest]
        .withFieldComputed(_.variants, _.variants.toList.transformInto[List[smithy.v2.CatalogueItemVariantInsert]])
        .transform

  given arbCatalogueItemVariantInsertSmithy: Arbitrary[smithy.v2.CatalogueItemVariantInsert] = Arbitrary(
    Arbitrary.arbitrary[CatalogueItemVariantInsert].map(_.transformInto[smithy.v2.CatalogueItemVariantInsert])
  )

  given arbCatalogueItemVariantUpdateSmithy: Arbitrary[smithy.v2.CatalogueItemVariantUpdate] = Arbitrary(
    Arbitrary.arbitrary[CatalogueItemVariantUpdate].map(_.transformInto[smithy.v2.CatalogueItemVariantUpdate])
  )

  given arbCatalogueItemPostRequestSmithy: Arbitrary[smithy.v2.CatalogueItemPostRequest] = Arbitrary(
    Arbitrary.arbitrary[CatalogueItemPostRequest].map(_.transformInto[smithy.v2.CatalogueItemPostRequest])
  )

  given catalogueItemBatchPostRequestTransformer
      : Transformer[CatalogueItemBatchPostRequest, smithy.v2.CatalogueItemBatchPostRequest] =
    catalogueItemBatchPostRequest =>
      catalogueItemBatchPostRequest
        .into[smithy.v2.CatalogueItemBatchPostRequest]
        .withFieldComputed(
          _.catalogueItems,
          _.catalogueItems.toList.transformInto[List[smithy.v2.CatalogueItemPostRequest]],
        )
        .transform

  given arbCatalogueItemBatchPostRequestSmithy: Arbitrary[smithy.v2.CatalogueItemBatchPostRequest] = Arbitrary(
    Arbitrary.arbitrary[CatalogueItemBatchPostRequest].map(_.transformInto[smithy.v2.CatalogueItemBatchPostRequest])
  )

  given arbCatalogueItemPutRequestSmithy: Arbitrary[smithy.v2.CatalogueItemPutRequest] = Arbitrary(
    Arbitrary.arbitrary[CatalogueItemPutRequest].map(_.transformInto[smithy.v2.CatalogueItemPutRequest])
  )

  given arbCatalogueItemUniquenessGetInputSmithy: Arbitrary[smithy.v2.CatalogueItemUniquenessGetInput] = Arbitrary(
    for {
      organizationID <- Arbitrary.arbitrary[OrganizationID]
      request        <- Arbitrary.arbitrary[CatalogueItemUniquenessGetRequest]
    } yield smithy.v2.CatalogueItemUniquenessGetInput(
      organizationID = organizationID.value,
      name = request.name.map(_.value),
      sku = request.sku.map(_.value),
    )
  )
}
