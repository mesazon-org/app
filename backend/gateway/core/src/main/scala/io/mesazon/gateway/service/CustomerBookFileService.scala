package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.HttpErrorHandler
import io.mesazon.gateway.clients.AIClientDataExtraction
import io.mesazon.gateway.config.CustomerBookFileServiceConfig
import io.mesazon.gateway.json.ai.given
import io.mesazon.gateway.tapir.TapirTask
import io.mesazon.gateway.utils.*
import io.scalaland.chimney.dsl.*
import zio.*
import zio.stream.ZStream

trait CustomerBookFileService[F[_]] {
  def aiExtract(
      organizationID: OrganizationID,
      customerBookFileExtractFileName: CustomerBookFileExtractFileName,
      customerBookFileProcessingMessageOpt: Option[CustomerBookFileProcessingMessage],
      customerBookFileExtractByteStream: ZStream[Any, Throwable, Byte],
  ): F[CustomerBookFileAiExtractPostResponse]
}

object CustomerBookFileService {

  private final class CustomerBookFileServiceImpl(
      customerBookFileServiceConfig: CustomerBookFileServiceConfig,
      fileScanner: FileScanner,
      spreadsheetTool: SpreadsheetTool,
      aiClientDataExtraction: AIClientDataExtraction,
  ) extends CustomerBookFileService[ServiceTask] {

    private type CustomerPhoneNumberEntriesComputed = (
        customerPhoneNumberEntryCandidates: List[CustomerPhoneNumberEntryCandidate],
        missingFields: List[FieldInfo],
        inferredFields: List[FieldInfo],
    )

    private type CustomerAddressEntriesComputed = (
        addressEntries: List[CustomerAddressEntry],
        extractionNotes: List[String],
    )

    private type CustomerNameFieldsComputed = (
        missingFields: List[FieldInfo],
        inferredFields: List[FieldInfo],
        duplicateFields: List[FieldInfo],
    )

    private type CustomerBusinessContactsFieldsComputed =
      (
          customerBusinessContacts: List[CustomerBusinessContactCandidate],
          missingFields: List[FieldInfo],
          inferredFields: List[FieldInfo],
          extractionNotes: List[String],
      )

    private inline val SingleOccurrenceCount = 1

    private inline val NoteCompactionGroupCountMin = 2

    private inline val AddressEntryKeptCountMax = 2

    private inline val FullNameProperty = "fullName"

    private inline val BusinessNameProperty = "businessName"

    private inline val PhoneNumbersProperty = "phoneNumbers"

    private inline val PhoneNumberProperty = "phoneNumber"

    private inline val PhoneCountryCodeProperty = "phoneCountryCode"

    private inline val CustomerBusinessContactsProperty = "customerBusinessContacts"

    private def mergeCustomerBookFileAIExtracts(
        customerBookFileAIExtracts: NonEmptyChunk[CustomerBookFileAIExtract]
    ): CustomerBookFileAIExtract = {
      val customerBookFileAIExtractsList = customerBookFileAIExtracts.toList

      CustomerBookFileAIExtract(
        entriesIdentified = customerBookFileAIExtracts.map(_.entriesIdentified).sum,
        entriesProcessed = customerBookFileAIExtracts.map(_.entriesProcessed).sum,
        individualCandidates = customerBookFileAIExtractsList.flatMap(_.individualCandidates),
        businessCandidates = customerBookFileAIExtractsList.flatMap(_.businessCandidates),
        emptyEntryRowIndexes = customerBookFileAIExtractsList.flatMap(_.emptyEntryRowIndexes),
        unidentifiedEntryRowIndexes = customerBookFileAIExtractsList.flatMap(_.unidentifiedEntryRowIndexes),
        extractionNotes = customerBookFileAIExtractsList.flatMap(_.extractionNotes),
      )
    }

    private def computeExtractionNotesCompactedOpt(
        extractionNotesMerged: List[String],
        noteCompactionOutput: NoteCompactionOutput,
    ): Option[List[String]] =
      Option.when(
        noteCompactionOutput.notes.nonEmpty && noteCompactionOutput.notes.size <= extractionNotesMerged.size
      )(noteCompactionOutput.notes)

    private def computeInfoNotes(customerBookFileAIExtract: CustomerBookFileAIExtract): List[String] = {
      val candidatesCreatedCount =
        (customerBookFileAIExtract.individualCandidates.size + customerBookFileAIExtract.businessCandidates.size).toLong

      List(
        Some(
          s"Processed ${customerBookFileAIExtract.entriesProcessed} of ${customerBookFileAIExtract.entriesIdentified} entries"
        ),
        Option
          .when(candidatesCreatedCount != customerBookFileAIExtract.entriesProcessed)(
            s"Candidates created $candidatesCreatedCount"
          ),
        Option
          .when(customerBookFileAIExtract.emptyEntryRowIndexes.nonEmpty)(
            s"Empty rows (${customerBookFileAIExtract.emptyEntryRowIndexes.size}) at [${customerBookFileAIExtract.emptyEntryRowIndexes.mkString(", ")}]"
          ),
        Option
          .when(customerBookFileAIExtract.unidentifiedEntryRowIndexes.nonEmpty)(
            s"Unidentified rows (${customerBookFileAIExtract.unidentifiedEntryRowIndexes.size}) at [${customerBookFileAIExtract.unidentifiedEntryRowIndexes.mkString(", ")}]"
          ),
      ).flatten
    }

    private def computeFieldInfo(pathSegments: (String | Int)*): FieldInfo =
      FieldInfo(
        pathSegments.map {
          case pathSegmentProperty: String => s"/${pathSegmentProperty.replace("~", "~0").replace("/", "~1")}"
          case pathSegmentIndex: Int       => s"/$pathSegmentIndex"
        }.mkString
      )

    private def computeCustomerEmailEntries(emails: List[CustomerEmail]): List[CustomerEmailEntry] =
      emails match {
        case Nil          => Nil
        case head :: tail =>
          CustomerEmailEntry(head, isDefault = true) :: tail.map(email => CustomerEmailEntry(email, isDefault = false))
      }

    private def computeCustomerPhoneNumberEntries(
        customerPhoneNumberAIExtracts: List[CustomerPhoneNumberAIExtract]
    ): CustomerPhoneNumberEntriesComputed = {
      val (missingCountryCode, nonMissingCountryCode) =
        customerPhoneNumberAIExtracts.zipWithIndex.partition { case (customerPhoneNumberAIExtract, _) =>
          customerPhoneNumberAIExtract.phoneCountryCode.isEmpty
        }
      val inferredCountryCode = nonMissingCountryCode.filter { case (phoneNumberAIExtract, _) =>
        phoneNumberAIExtract.phoneCountryCodeInferred
      }
      (
        customerPhoneNumberEntryCandidates = customerPhoneNumberAIExtracts match {
          case Nil          => Nil
          case head :: tail =>
            CustomerPhoneNumberEntryCandidate(head.transformInto[CustomerPhoneNumberCandidate], isDefault = true) ::
              tail.map(customerPhoneNumberAIExtract =>
                CustomerPhoneNumberEntryCandidate(
                  customerPhoneNumberAIExtract.transformInto[CustomerPhoneNumberCandidate],
                  isDefault = false,
                )
              )
        },
        missingFields = missingCountryCode.map { case (_, index) =>
          computeFieldInfo(PhoneNumbersProperty, index, PhoneNumberProperty, PhoneCountryCodeProperty)
        },
        inferredFields = inferredCountryCode.map { case (_, index) =>
          computeFieldInfo(PhoneNumbersProperty, index, PhoneNumberProperty, PhoneCountryCodeProperty)
        },
      )
    }

    private def computeAddressLeftOutNote(address: Address): String = {
      val addressNotedOpt = List(
        address.addressLine1.map(_.value),
        address.addressLine2.map(_.value),
        address.city.map(_.value),
        address.postalCode.map(_.value),
        address.country.map(_.value),
      ).flatten.headOption

      s"Address left out: ${addressNotedOpt.mkString}"
    }

    private def computeAddressEntries(
        addressEntryAIExtracts: List[CustomerAddressEntryAIExtract]
    ): CustomerAddressEntriesComputed = {
      val (addressEntriesKept, addressEntriesLeftOut) =
        addressEntryAIExtracts
          .map(_.transformInto[AddressEntry])
          .filterNot(_.address.isEmpty)
          .splitAt(AddressEntryKeptCountMax)

      val addressEntriesTyped = addressEntriesKept match {
        case addressEntry :: Nil =>
          List(addressEntry.copy(addressType = AddressType.ShippingAndBilling))
        case addressEntryFirst :: addressEntrySecond :: Nil
            if Set(addressEntryFirst.addressType, addressEntrySecond.addressType) ==
              Set(AddressType.Shipping, AddressType.Billing) =>
          addressEntriesKept
        case addressEntryFirst :: addressEntrySecond :: Nil =>
          List(
            addressEntryFirst.copy(addressType = AddressType.Shipping),
            addressEntrySecond.copy(addressType = AddressType.Billing),
          )
        case _ => addressEntriesKept
      }

      val extractionNotesLeftOut =
        addressEntriesLeftOut.map(addressEntry => computeAddressLeftOutNote(addressEntry.address))

      (
        addressEntries = addressEntriesTyped.map(CustomerAddressEntry(_)),
        extractionNotes = addressEntryAIExtracts.flatMap(_.address.extractionNotes) ++ extractionNotesLeftOut,
      )
    }

    private def computeCustomerIndividualFullNameCounts(
        customerIndividualAIExtracts: List[CustomerIndividualAIExtract]
    ): Map[CustomerFullName, Int] =
      customerIndividualAIExtracts.flatMap(_.fullName).groupMapReduce(identity)(_ => SingleOccurrenceCount)(_ + _)

    private def computeCustomerBusinessNameCounts(
        customerBusinessAIExtracts: List[CustomerBusinessAIExtract]
    ): Map[CustomerBusinessName, Int] =
      customerBusinessAIExtracts.flatMap(_.businessName).groupMapReduce(identity)(_ => SingleOccurrenceCount)(_ + _)

    private def computeCustomerIndividualFields(
        customerIndividualAIExtract: CustomerIndividualAIExtract,
        customerIndividualFullNameCounts: Map[CustomerFullName, Int],
    ): CustomerNameFieldsComputed = (
      missingFields =
        Option.when(customerIndividualAIExtract.fullName.isEmpty)(computeFieldInfo(FullNameProperty)).toList,
      inferredFields =
        Option.when(customerIndividualAIExtract.fullNameInferred)(computeFieldInfo(FullNameProperty)).toList,
      duplicateFields = Option
        .when(
          customerIndividualAIExtract.fullName
            .exists(fullName => customerIndividualFullNameCounts.get(fullName).exists(_ > SingleOccurrenceCount))
        )(computeFieldInfo(FullNameProperty))
        .toList,
    )

    private def computeCustomerBusinessFields(
        customerBusinessAIExtract: CustomerBusinessAIExtract,
        customerBusinessNameCounts: Map[CustomerBusinessName, Int],
    ): CustomerNameFieldsComputed =
      (
        missingFields =
          Option.when(customerBusinessAIExtract.businessName.isEmpty)(computeFieldInfo(BusinessNameProperty)).toList,
        inferredFields =
          Option.when(customerBusinessAIExtract.businessNameInferred)(computeFieldInfo(BusinessNameProperty)).toList,
        duplicateFields = Option
          .when(
            customerBusinessAIExtract.businessName
              .exists(businessName => customerBusinessNameCounts.get(businessName).exists(_ > SingleOccurrenceCount))
          )(computeFieldInfo(BusinessNameProperty))
          .toList,
      )

    private def computeCustomerBusinessContacts(
        customerBusinessContactAIExtracts: List[CustomerBusinessContactAIExtract]
    ): CustomerBusinessContactsFieldsComputed = {
      val computedFields = customerBusinessContactAIExtracts.zipWithIndex.map {
        case (customerBusinessContactAIExtract, index) =>
          val customerPhoneNumberEntriesComputed =
            computeCustomerPhoneNumberEntries(customerBusinessContactAIExtract.phoneNumber.toList)

          val missingFields = Option
            .when(customerBusinessContactAIExtract.fullName.isEmpty)(
              computeFieldInfo(CustomerBusinessContactsProperty, index, FullNameProperty)
            )
            .toList ++ Option
            .when(customerPhoneNumberEntriesComputed.missingFields.nonEmpty)(
              computeFieldInfo(CustomerBusinessContactsProperty, index, PhoneNumberProperty, PhoneCountryCodeProperty)
            )
            .toList

          val inferredFields = Option
            .when(customerBusinessContactAIExtract.fullNameInferred)(
              computeFieldInfo(CustomerBusinessContactsProperty, index, FullNameProperty)
            )
            .toList ++ Option
            .when(customerPhoneNumberEntriesComputed.inferredFields.nonEmpty)(
              computeFieldInfo(CustomerBusinessContactsProperty, index, PhoneNumberProperty, PhoneCountryCodeProperty)
            )
            .toList

          (missingFields = missingFields, inferredFields = inferredFields)
      }

      (
        customerBusinessContacts =
          customerBusinessContactAIExtracts.transformInto[List[CustomerBusinessContactCandidate]],
        missingFields = computedFields.flatMap(_.missingFields),
        inferredFields = computedFields.flatMap(_.inferredFields),
        extractionNotes = customerBusinessContactAIExtracts.flatMap(_.extractionNotes),
      )
    }

    override def aiExtract(
        organizationID: OrganizationID,
        customerBookFileExtractFileName: CustomerBookFileExtractFileName,
        customerBookFileProcessingMessageOpt: Option[CustomerBookFileProcessingMessage],
        customerBookFileExtractByteStream: ZStream[Any, Throwable, Byte],
    ): ServiceTask[CustomerBookFileAiExtractPostResponse] = ZIO.scoped(
      for {
        customerBookFileScanOutput <- fileScanner.scan(
          customerBookFileExtractByteStream,
          customerBookFileExtractFileName.value,
          SupportedMediaType.extractData,
          customerBookFileServiceConfig.extractCustomersFileBytesMax,
        )
        customerBookFileAIExtract <- customerBookFileScanOutput.supportedMediaType match {
          case supportedMediaType if SupportedMediaType.images.contains(supportedMediaType) =>
            aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
              customerBookFileScanOutput.fileScannedPath,
              supportedMediaType,
              AIInstructions.extractCustomersFromImageInstructions,
              customerBookFileProcessingMessageOpt.map(_.value),
            )
          case supportedMediaType if SupportedMediaType.spreadsheets.contains(supportedMediaType) =>
            val csvRecordStream =
              if (SupportedMediaType.excel.contains(supportedMediaType))
                spreadsheetTool.convertExcelToCsv(customerBookFileScanOutput.fileScannedPath)
              else spreadsheetTool.convertToCsv(customerBookFileScanOutput.fileScannedPath)

            aiClientDataExtraction
              .extractFromCsv[CustomerBookFileAIExtract](
                csvRecordStream,
                AIInstructions.extractCustomersFromFileInstructions,
                customerBookFileProcessingMessageOpt.map(_.value),
              )
              .flatMap { customerBookFileAIExtracts =>
                val customerBookFileAIExtractMerged = mergeCustomerBookFileAIExtracts(customerBookFileAIExtracts)
                val extractionNotesMerged           = customerBookFileAIExtractMerged.extractionNotes

                if (customerBookFileAIExtracts.size < NoteCompactionGroupCountMin || extractionNotesMerged.isEmpty)
                  ZIO.succeed(customerBookFileAIExtractMerged)
                else
                  aiClientDataExtraction
                    .noteCompaction(extractionNotesMerged, AIInstructions.compactCustomerBookFileNotesInstructions)
                    .flatMap(noteCompactionOutput =>
                      computeExtractionNotesCompactedOpt(extractionNotesMerged, noteCompactionOutput) match {
                        case Some(extractionNotesCompacted) => ZIO.succeed(extractionNotesCompacted)
                        case None                           =>
                          ZIO
                            .logWarning(
                              s"Note compaction returned [${noteCompactionOutput.notes.size}] notes for [${extractionNotesMerged.size}] sent, keeping the raw notes"
                            )
                            .as(extractionNotesMerged)
                      }
                    )
                    .catchAllCause(cause =>
                      ZIO
                        .logWarningCause("Note compaction failed, keeping the raw notes", cause)
                        .as(extractionNotesMerged)
                    )
                    .map(extractionNotes => customerBookFileAIExtractMerged.copy(extractionNotes = extractionNotes))
              }
          case supportedMediaTypeUnexpected =>
            ZIO.fail(
              ServiceError.InternalServerError.UnexpectedError(
                s"Unexpected supported media type: [$supportedMediaTypeUnexpected]"
              )
            )
        }
        customerIndividualFullNameCounts =
          computeCustomerIndividualFullNameCounts(customerBookFileAIExtract.individualCandidates)
        customerBusinessNameCounts = computeCustomerBusinessNameCounts(customerBookFileAIExtract.businessCandidates)
        individualCandidates       = customerBookFileAIExtract.individualCandidates.map { customerIndividualAIExtract =>
          val customerIndividualFieldsComputed =
            computeCustomerIndividualFields(customerIndividualAIExtract, customerIndividualFullNameCounts)
          val customerPhoneNumberEntriesComputed =
            computeCustomerPhoneNumberEntries(customerIndividualAIExtract.phoneNumbers)
          val customerAddressEntriesComputed = computeAddressEntries(customerIndividualAIExtract.addresses)

          val missingFields =
            customerIndividualFieldsComputed.missingFields ++ customerPhoneNumberEntriesComputed.missingFields
          val duplicateFields = customerIndividualFieldsComputed.duplicateFields
          val inferredFields  =
            customerIndividualFieldsComputed.inferredFields ++ customerPhoneNumberEntriesComputed.inferredFields

          val extractionNotes =
            customerIndividualAIExtract.extractionNotes ++ customerAddressEntriesComputed.extractionNotes

          customerIndividualAIExtract
            .into[CustomerIndividualCandidate]
            .withFieldConst(_.emails, computeCustomerEmailEntries(customerIndividualAIExtract.emails))
            .withFieldConst(_.phoneNumbers, customerPhoneNumberEntriesComputed.customerPhoneNumberEntryCandidates)
            .withFieldConst(_.addresses, customerAddressEntriesComputed.addressEntries)
            .withFieldConst(_.missingFields, missingFields)
            .withFieldConst(_.inferredFields, inferredFields)
            .withFieldConst(_.duplicateFields, duplicateFields)
            .withFieldConst(_.needsAttention, duplicateFields.nonEmpty || missingFields.nonEmpty)
            .withFieldConst(_.extractionNotes, extractionNotes)
            .transform
        }
        businessCandidates = customerBookFileAIExtract.businessCandidates.map { customerBusinessAIExtract =>
          val customerBusinessFieldsComputed =
            computeCustomerBusinessFields(customerBusinessAIExtract, customerBusinessNameCounts)
          val customerPhoneNumberEntriesComputed =
            computeCustomerPhoneNumberEntries(customerBusinessAIExtract.phoneNumbers)
          val customerAddressEntriesComputed   = computeAddressEntries(customerBusinessAIExtract.addresses)
          val customerBusinessContactsComputed =
            computeCustomerBusinessContacts(customerBusinessAIExtract.customerBusinessContacts)

          val missingFields =
            customerBusinessFieldsComputed.missingFields ++ customerPhoneNumberEntriesComputed.missingFields ++ customerBusinessContactsComputed.missingFields
          val duplicateFields = customerBusinessFieldsComputed.duplicateFields
          val inferredFields  =
            customerBusinessFieldsComputed.inferredFields ++ customerPhoneNumberEntriesComputed.inferredFields ++ customerBusinessContactsComputed.inferredFields

          val extractionNotes =
            customerBusinessAIExtract.extractionNotes ++ customerAddressEntriesComputed.extractionNotes ++ customerBusinessContactsComputed.extractionNotes

          customerBusinessAIExtract
            .into[CustomerBusinessCandidate]
            .withFieldConst(_.emails, computeCustomerEmailEntries(customerBusinessAIExtract.emails))
            .withFieldConst(_.phoneNumbers, customerPhoneNumberEntriesComputed.customerPhoneNumberEntryCandidates)
            .withFieldConst(_.addresses, customerAddressEntriesComputed.addressEntries)
            .withFieldConst(_.customerBusinessContacts, customerBusinessContactsComputed.customerBusinessContacts)
            .withFieldConst(_.missingFields, missingFields)
            .withFieldConst(_.inferredFields, inferredFields)
            .withFieldConst(_.duplicateFields, duplicateFields)
            .withFieldConst(_.needsAttention, duplicateFields.nonEmpty || missingFields.nonEmpty)
            .withFieldConst(_.extractionNotes, extractionNotes)
            .transform
        }
        infoNotesComputed = computeInfoNotes(customerBookFileAIExtract)
      } yield CustomerBookFileAiExtractPostResponse(
        individualCandidates = individualCandidates,
        businessCandidates = businessCandidates,
        infoNotes = infoNotesComputed,
        extractionNotes = customerBookFileAIExtract.extractionNotes,
      )
    )
  }

  def observed(service: CustomerBookFileService[ServiceTask]): CustomerBookFileService[TapirTask] =
    new CustomerBookFileService[TapirTask] {
      override def aiExtract(
          organizationID: OrganizationID,
          customerBookFileExtractFileName: CustomerBookFileExtractFileName,
          customerBookFileProcessingMessageOpt: Option[CustomerBookFileProcessingMessage],
          customerBookFileExtractByteStream: ZStream[Any, Throwable, Byte],
      ): TapirTask[CustomerBookFileAiExtractPostResponse] =
        HttpErrorHandler.errorResponseHandlerTapir(
          service.aiExtract(
            organizationID,
            customerBookFileExtractFileName,
            customerBookFileProcessingMessageOpt,
            customerBookFileExtractByteStream,
          )
        )
    }

  val local = ZLayer
    .derive[CustomerBookFileServiceImpl]
    .project[CustomerBookFileService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
