package io.mesazon.gateway.validation.service

import cats.data.{NonEmptyChain, ValidatedNec}
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.PriceValidator
import io.scalaland.chimney.dsl.*
import smithy4s.Nullable
import zio.*

final class CatalogueV2RequestValidator(
    priceValidator: PriceValidator
) {
  private def validateAttributes(
      attributesRaw: Map[String, String]
  ): ValidatedNec[
    InvalidFieldError,
    Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue],
  ] =
    attributesRaw.toList.traverse { case (nameRaw, valueRaw) =>
      (
        validateRequiredField("attributeName", nameRaw, CatalogueItemVariantAttributeName.either),
        validateRequiredField("attributeValue", valueRaw, CatalogueItemVariantAttributeValue.either),
      ).mapN((_, _))
    }
      .map(_.toMap)

  private def validatePrice(
      priceSmithy: smithy.v2.CatalogueItemVariantPrice
  ): UIO[ValidatedNec[InvalidFieldError, CatalogueItemVariantPrice]] =
    priceValidator
      .validate((amountRaw = priceSmithy.amount, currency = priceSmithy.currency.transformInto[Currency]))
      .map(
        _.leftMap(errors =>
          NonEmptyChain.one(
            InvalidFieldError(
              "price",
              s"Failed with invalid fields ${errors.toNonEmptyList.toList.mkString("[", ", ", "]")}",
              List(priceSmithy.amount.toString, priceSmithy.currency.stringValue),
            )
          )
        ).map(CatalogueItemVariantPrice.apply)
      )

  private def validateOptionalPrice(
      priceSmithy: Option[smithy.v2.CatalogueItemVariantPrice]
  ): UIO[ValidatedNec[InvalidFieldError, Option[CatalogueItemVariantPrice]]] =
    priceSmithy match {
      case None        => ZIO.succeed(none[CatalogueItemVariantPrice].validNec)
      case Some(price) => validatePrice(price).map(_.map(_.some))
    }

  private def validateNullablePrice(
      priceSmithy: Option[Nullable[smithy.v2.CatalogueItemVariantPrice]]
  ): UIO[ValidatedNec[InvalidFieldError, Option[Option[CatalogueItemVariantPrice]]]] =
    priceSmithy match {
      case None                        => ZIO.succeed(none[Option[CatalogueItemVariantPrice]].validNec)
      case Some(Nullable.Null)         => ZIO.succeed(none[CatalogueItemVariantPrice].some.validNec)
      case Some(Nullable.Value(price)) => validatePrice(price).map(_.map(_.some.some))
    }

  private def validateSaleUnit(
      saleUnitSmithy: smithy.v2.CatalogueItemVariantSaleUnit
  ): ValidatedNec[InvalidFieldError, CatalogueItemVariantSaleUnit] =
    (
      validateRequiredField(
        "quantity",
        saleUnitSmithy.quantity.value,
        CatalogueItemVariantQuantityValue.either,
      ).map(value =>
        CatalogueItemVariantQuantity(
          value,
          saleUnitSmithy.quantity.unit.transformInto[CatalogueItemVariantUnit],
        )
      ),
      saleUnitSmithy.perUnit.traverse(perUnitSmithy =>
        validateRequiredField(
          "perUnit",
          perUnitSmithy.value,
          CatalogueItemVariantPerUnitValue.either,
        ).map(value =>
          CatalogueItemVariantPerUnit(
            value,
            perUnitSmithy.unit.transformInto[CatalogueItemVariantUnit.PerUnit],
          )
        )
      ),
      saleUnitSmithy.unitSize.traverse(unitSizeSmithy =>
        validateRequiredField(
          "unitSize",
          unitSizeSmithy.value,
          CatalogueItemVariantUnitSizeValue.either,
        ).map(value =>
          CatalogueItemVariantUnitSize(
            value,
            unitSizeSmithy.unit.transformInto[CatalogueItemVariantUnit.UnitSize],
          )
        )
      ),
    ).mapN(CatalogueItemVariantSaleUnit.apply)

  private def validateVariantInsert(
      variantSmithy: smithy.v2.CatalogueItemVariantInsert
  ): UIO[ValidatedNec[InvalidFieldError, CatalogueItemVariantInsert]] =
    validateOptionalPrice(variantSmithy.price).map(price =>
      (
        validateOptionalField("sku", variantSmithy.sku, CatalogueItemVariantSku.either),
        validateOptionalField("label", variantSmithy.label, CatalogueItemVariantLabel.either),
        validateOptionalField("description", variantSmithy.description, CatalogueItemVariantDescription.either),
        validateAttributes(variantSmithy.attributes),
        price,
        validateSaleUnit(variantSmithy.sellingUnit),
      ).mapN(CatalogueItemVariantInsert.apply)
    )

  private def validateVariantInserts(
      variantsSmithy: List[smithy.v2.CatalogueItemVariantInsert]
  ): UIO[ValidatedNec[InvalidFieldError, List[CatalogueItemVariantInsert]]] =
    validateAllNested("variant", variantsSmithy)(validateVariantInsert)

  private def validateItemPost(
      requestSmithy: smithy.v2.CatalogueItemPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, CatalogueItemPostRequest]] =
    validateNonEmptyNested(
      "variant",
      requestSmithy.variants,
    )(validateVariantInsert).map(variants =>
      (
        validateRequiredField("name", requestSmithy.name, CatalogueItemName.either),
        validateOptionalField("description", requestSmithy.description, CatalogueItemDescription.either),
        variants,
      ).mapN(CatalogueItemPostRequest.apply)
    )

  private def validateVariantStatus(
      statusSmithy: Option[smithy.v2.CatalogueItemVariantStatus]
  ): ValidatedNec[InvalidFieldError, Option[CatalogueItemVariantStatus]] =
    statusSmithy
      .map(_.transformInto[CatalogueItemVariantStatus])
      .traverse { status =>
        if (CatalogueItemVariantStatus.disabled.contains(status))
          InvalidFieldError(
            "status",
            "Disabled status is not allowed for catalogue item variant updates",
            List(status.toString),
          ).invalidNec
        else status.validNec
      }

  private def validateVariantUpdate(
      variantSmithy: smithy.v2.CatalogueItemVariantUpdate
  ): UIO[ValidatedNec[InvalidFieldError, CatalogueItemVariantUpdate]] =
    validateNullablePrice(variantSmithy.price).map(price =>
      (
        CatalogueItemVariantID(variantSmithy.catalogueItemVariantID).validNec,
        validateNullableField("sku", variantSmithy.sku, CatalogueItemVariantSku.either),
        validateNullableField("label", variantSmithy.label, CatalogueItemVariantLabel.either),
        validateNullableField("description", variantSmithy.description, CatalogueItemVariantDescription.either),
        variantSmithy.attributes.traverse(validateAttributes),
        price,
        variantSmithy.sellingUnit.traverse(validateSaleUnit),
        validateVariantStatus(variantSmithy.status),
      ).mapN(CatalogueItemVariantUpdate.apply)
    )

  def validatedCatalogueItemPostRequest(
      requestSmithy: smithy.v2.CatalogueItemPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, CatalogueItemPostRequest] =
    toValidatedRequestIO(validateItemPost(requestSmithy))

  def validatedCatalogueItemBatchPostRequest(
      requestSmithy: smithy.v2.CatalogueItemBatchPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, CatalogueItemBatchPostRequest] =
    toValidatedRequestIO(
      validateNonEmptyNested("catalogueItem", requestSmithy.catalogueItems)(validateItemPost)
        .map(_.map(CatalogueItemBatchPostRequest.apply))
    )

  def validatedCatalogueItemPutRequest(
      requestSmithy: smithy.v2.CatalogueItemPutRequest
  ): IO[ServiceError.BadRequestError.ValidationError, CatalogueItemPutRequest] =
    toValidatedRequestIO(
      for {
        variantsUpdate <- validateAllNested("variant", requestSmithy.variantsUpdate)(validateVariantUpdate)
        variantsInsert <- validateVariantInserts(requestSmithy.variantsInsert)
      } yield (
        validateOptionalField("name", requestSmithy.name, CatalogueItemName.either),
        validateNullableField("description", requestSmithy.description, CatalogueItemDescription.either),
        variantsUpdate,
        variantsInsert,
      ).mapN(CatalogueItemPutRequest.apply)
    )

  def validatedCatalogueItemUniquenessGetRequest(
      inputSmithy: smithy.v2.CatalogueItemUniquenessGetInput
  ): IO[ServiceError.BadRequestError.ValidationError, CatalogueItemUniquenessGetRequest] =
    toValidatedRequestIO(
      ZIO.succeed(
        (
          validateOptionalField("name", inputSmithy.name, CatalogueItemName.either),
          validateOptionalField("sku", inputSmithy.sku, CatalogueItemVariantSku.either),
        ).mapN(CatalogueItemUniquenessGetRequest.apply).andThen { request =>
          if (request.name.nonEmpty || request.sku.nonEmpty) request.validNec
          else
            NonEmptyChain
              .of(
                InvalidFieldError("name", "At least one of name or sku must be provided", Seq.empty),
                InvalidFieldError("sku", "At least one of name or sku must be provided", Seq.empty),
              )
              .invalid
        }
      )
    )
}

object CatalogueV2RequestValidator {

  val live = ZLayer.derive[CatalogueV2RequestValidator]
}
