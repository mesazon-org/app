package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.transform.given
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait CatalogueSmithyArbitraries extends CatalogueDomainArbitraries, IronRefinedTypeTransformer {

  given arbCatalogueItemVariantInsertSmithy: Arbitrary[smithy.CatalogueItemVariantInsert] = Arbitrary(
    Arbitrary.arbitrary[CatalogueItemVariantInsert].map(_.transformInto[smithy.CatalogueItemVariantInsert])
  )

  given arbCatalogueItemVariantUpdateSmithy: Arbitrary[smithy.CatalogueItemVariantUpdate] = Arbitrary(
    Arbitrary.arbitrary[CatalogueItemVariantUpdate].map(_.transformInto[smithy.CatalogueItemVariantUpdate])
  )

  given arbCatalogueItemPostRequestSmithy: Arbitrary[smithy.CatalogueItemPostRequest] = Arbitrary(
    Arbitrary.arbitrary[CatalogueItemPostRequest].map(_.transformInto[smithy.CatalogueItemPostRequest])
  )

  given arbCatalogueItemBatchPostRequestSmithy: Arbitrary[smithy.CatalogueItemBatchPostRequest] = Arbitrary(
    Arbitrary.arbitrary[CatalogueItemBatchPostRequest].map(_.transformInto[smithy.CatalogueItemBatchPostRequest])
  )

  given arbCatalogueItemPutRequestSmithy: Arbitrary[smithy.CatalogueItemPutRequest] = Arbitrary(
    Arbitrary.arbitrary[CatalogueItemPutRequest].map(_.transformInto[smithy.CatalogueItemPutRequest])
  )

  given arbCatalogueItemUniquenessGetInputSmithy: Arbitrary[smithy.CatalogueItemUniquenessGetInput] = Arbitrary(
    for {
      organizationID <- Arbitrary.arbitrary[OrganizationID]
      request        <- Arbitrary.arbitrary[CatalogueItemUniquenessGetRequest]
    } yield smithy.CatalogueItemUniquenessGetInput(
      organizationID = organizationID.value,
      name = request.name.map(_.value),
      sku = request.sku.map(_.value),
    )
  )
}
