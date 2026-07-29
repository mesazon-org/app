package io.mesazon.gateway.validation.service

import cats.data.{NonEmptyChain, ValidatedNec}
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.PriceDomainValidator
import zio.*

final class CatalogueRequestValidator(
    priceDomainValidator: PriceDomainValidator
) {

  def validatedInsertCatalogueItemPostRequest(
      insertCatalogueItemPostRequestSmithy: smithy.InsertCatalogueItemPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, InsertCatalogueItemPostRequest] =
    toValidatedRequestIO(validateInsertCatalogueItem(insertCatalogueItemPostRequestSmithy))

  def validatedInsertCatalogueItemsPostRequest(
      insertCatalogueItemsPostRequestSmithy: smithy.InsertCatalogueItemsPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, InsertCatalogueItemsPostRequest] =
    toValidatedRequestIO(
      validateAllNested("catalogueItem", insertCatalogueItemsPostRequestSmithy.catalogueItems)(
        validateInsertCatalogueItem
      )
        .map(_.map(InsertCatalogueItemsPostRequest.apply))
    )

  def validatedUpdateCatalogueItemPutRequest(
      updateCatalogueItemPutRequestSmithy: smithy.UpdateCatalogueItemPutRequest
  ): IO[ServiceError.BadRequestError.ValidationError, UpdateCatalogueItemPutRequest] =
    toValidatedRequestIO(validateOptionalCatalogueItemPrice(updateCatalogueItemPutRequestSmithy.price).map {
      catalogueItemPriceValidatedOpt =>
        (
          CatalogueItemID(updateCatalogueItemPutRequestSmithy.catalogueItemID).validNec,
          validateOptionalField("name", updateCatalogueItemPutRequestSmithy.name, CatalogueItemName.either),
          validateOptionalField("unit", updateCatalogueItemPutRequestSmithy.unit, CatalogueItemUnit.either),
          catalogueItemPriceValidatedOpt,
        ).mapN(UpdateCatalogueItemPutRequest.apply)
    })

  private def validateInsertCatalogueItem(
      insertCatalogueItemPostRequestSmithy: smithy.InsertCatalogueItemPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, InsertCatalogueItemPostRequest]] =
    validateOptionalCatalogueItemPrice(insertCatalogueItemPostRequestSmithy.price).map { catalogueItemPriceValidatedOpt =>
      (
        validateRequiredField("name", insertCatalogueItemPostRequestSmithy.name, CatalogueItemName.either),
        validateRequiredField("unit", insertCatalogueItemPostRequestSmithy.unit, CatalogueItemUnit.either),
        catalogueItemPriceValidatedOpt,
      ).mapN(InsertCatalogueItemPostRequest.apply)
    }

  private def validateOptionalCatalogueItemPrice(
      catalogueItemPriceRequestSmithyOpt: Option[smithy.CatalogueItemPriceRequest]
  ): UIO[ValidatedNec[InvalidFieldError, Option[CatalogueItemPrice]]] =
    catalogueItemPriceRequestSmithyOpt match {
      case None                                  => ZIO.succeed(none[CatalogueItemPrice].validNec)
      case Some(catalogueItemPriceRequestSmithy) =>
        priceDomainValidator
          .validate(
            (
              amountRaw = catalogueItemPriceRequestSmithy.amount,
              currencyRaw = catalogueItemPriceRequestSmithy.currency,
            )
          )
          .map(
            _.leftMap(invalidFields =>
              NonEmptyChain.one(
                InvalidFieldError(
                  "price",
                  s"Failed with invalid fields ${invalidFields.toNonEmptyList.toList.mkString("[", ", ", "]")}",
                  List(catalogueItemPriceRequestSmithy.amount.toString, catalogueItemPriceRequestSmithy.currency),
                )
              )
            ).map(price => CatalogueItemPrice(price).some)
          )
    }
}

object CatalogueRequestValidator {

  val live = ZLayer.derive[CatalogueRequestValidator]
}
