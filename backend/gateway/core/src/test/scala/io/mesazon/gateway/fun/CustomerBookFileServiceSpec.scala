package io.mesazon.gateway.fun

import com.github.plokhotnyuk.jsoniter_scala.core.{writeToString, JsonValueCodec}
import io.circe.{parser, Json}
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.AIClientDataExtraction
import io.mesazon.gateway.config.CustomerBookFileServiceConfig
import io.mesazon.gateway.json.tapir.{
  customerBookFileAiExtractPostResponseCodec,
  customerBusinessCandidateSchema,
  customerIndividualCandidateSchema,
}
import io.mesazon.gateway.json.{ai, OpenAIJsonSchema}
import io.mesazon.gateway.service.{AIInstructions, CustomerBookFileService, ServiceTask}
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.{CustomerBookDomainArbitraries, ZWordSpecBase}
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVRecord}
import sttp.tapir.{Schema, SchemaType}
import zio.*
import zio.stream.ZStream

import java.nio.file.Path
import java.util.Locale
import scala.jdk.CollectionConverters.*

class CustomerBookFileServiceSpec extends ZWordSpecBase, CustomerBookDomainArbitraries {

  private inline val CustomerFullNameLengthMax = 255

  "CustomerBookFileService" when {
    "aiExtract" should {
      "extract candidates from an image without processing guidance" in new TestContext {
        val customerBookFileProcessingMessageOpt: Option[CustomerBookFileProcessingMessage] = None
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.jpeg")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.jpeg")),
          supportedMediaType = SupportedMediaType.JPEG,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val customerFullName                = arbitrarySample[CustomerFullName]
        val customerEmailDefault            = arbitrarySample[CustomerEmail]
        val customerEmailSecondary          = arbitrarySample[CustomerEmail]
        val customerBusinessName            = arbitrarySample[CustomerBusinessName]
        val customerBusinessEmailDefault    = arbitrarySample[CustomerEmail]
        val customerBusinessEmailSecondary  = arbitrarySample[CustomerEmail]
        val customerTaxID                   = arbitrarySample[CustomerTaxID]
        val customerBusinessContactFullName = arbitrarySample[CustomerFullName]
        val customerBusinessContactRole     = arbitrarySample[CustomerBusinessContactRole]
        val customerBusinessContactEmail    = arbitrarySample[CustomerEmail]
        val customerIndividualAIExtract     = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = List(customerEmailDefault, customerEmailSecondary),
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = List("Handwriting next to the name is faint"),
        )
        val customerBusinessAIExtract = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = List(customerBusinessEmailDefault, customerBusinessEmailSecondary),
          taxID = Some(customerTaxID),
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = List(
            CustomerBusinessContactAIExtract(
              fullName = Some(customerBusinessContactFullName),
              role = Some(customerBusinessContactRole),
              email = Some(customerBusinessContactEmail),
              phoneNumber = None,
              fullNameInferred = false,
              extractionNotes = Nil,
            )
          ),
          businessNameInferred = false,
          extractionNotes = List("Tax ID is printed in small type"),
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          individualCandidates = List(customerIndividualAIExtract),
          businessCandidates = List(customerBusinessAIExtract),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = List("The image is slightly cropped"),
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          (aiClientDataExtractionMock
            .extractFromImage[CustomerBookFileAIExtract](
              _: FileScannedPath,
              _: SupportedMediaType,
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              fileScannerScanOutput.fileScannedPath,
              SupportedMediaType.JPEG,
              AIInstructions.extractCustomersFromImageInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .returns(ZIO.succeed(customerBookFileAIExtract))
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(
            organizationID,
            customerBookFileExtractFileName,
            customerBookFileProcessingMessageOpt,
            customerBookFileExtractByteStream,
          )
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullName),
              emails = List(
                CustomerEmailEntry(email = customerEmailDefault, isDefault = true),
                CustomerEmailEntry(email = customerEmailSecondary, isDefault = false),
              ),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List("Handwriting next to the name is faint"),
            )
          ),
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = Some(customerBusinessName),
              emails = List(
                CustomerEmailEntry(email = customerBusinessEmailDefault, isDefault = true),
                CustomerEmailEntry(email = customerBusinessEmailSecondary, isDefault = false),
              ),
              taxID = Some(customerTaxID),
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = List(
                CustomerBusinessContactCandidate(
                  fullName = Some(customerBusinessContactFullName),
                  role = Some(customerBusinessContactRole),
                  email = Some(customerBusinessContactEmail),
                  phoneNumber = None,
                )
              ),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List("Tax ID is printed in small type"),
            )
          ),
          infoNotes = List("Processed 2 of 2 entries"),
          extractionNotes = List("The image is slightly cropped"),
        )
      }

      "forward image processing guidance to the image extraction" in new TestContext {
        val customerBookFileProcessingMessage            = CustomerBookFileProcessingMessage("Omit phone numbers")
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.png")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.png")),
          supportedMediaType = SupportedMediaType.PNG,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 0L,
          entriesProcessed = 0L,
          individualCandidates = Nil,
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          (aiClientDataExtractionMock
            .extractFromImage[CustomerBookFileAIExtract](
              _: FileScannedPath,
              _: SupportedMediaType,
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              fileScannerScanOutput.fileScannedPath,
              SupportedMediaType.PNG,
              AIInstructions.extractCustomersFromImageInstructions,
              Some(customerBookFileProcessingMessage.value),
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .returns(ZIO.succeed(customerBookFileAIExtract))
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(
            organizationID,
            customerBookFileExtractFileName,
            Some(customerBookFileProcessingMessage),
            customerBookFileExtractByteStream,
          )
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = Nil,
        )
      }

      "extract candidates from a CSV file with processing guidance" in new TestContext {
        val customerBookFileProcessingMessage = CustomerBookFileProcessingMessage("Treat column B as the email")
        val organizationID                    = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName   = CustomerBookFileExtractFileName("customers.csv")
        val customerBookFileExtractByteStream = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.csv")),
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val csvRecords: List[CSVRecord] =
          CSVParser.parse("Full Name,Email\nJane Doe,jane@example.com", CSVFormat.DEFAULT).getRecords.asScala.toList
        val customerFullName            = arbitrarySample[CustomerFullName]
        val customerEmailDefault        = arbitrarySample[CustomerEmail]
        val customerEmailSecondary      = arbitrarySample[CustomerEmail]
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = List(customerEmailDefault, customerEmailSecondary),
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract =
          CustomerBookFileAIExtract(
            entriesIdentified = 1L,
            entriesProcessed = 1L,
            individualCandidates = List(customerIndividualAIExtract),
            businessCandidates = Nil,
            emptyEntryRowIndexes = Nil,
            unidentifiedEntryRowIndexes = Nil,
            extractionNotes = List("Column B holds two emails for some rows"),
          )

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          spreadsheetToolMock.convertToCsv
            .expects(fileScannerScanOutput.fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CustomerBookFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCustomersFromFileInstructions,
              Some(customerBookFileProcessingMessage.value),
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .onCall { (csvRecordStream, _, _, _, _) =>
              csvRecordStream.runCollect.map { csvRecordsCollected =>
                csvRecordsCollected.toList shouldBe csvRecords
                NonEmptyChunk.single(customerBookFileAIExtract)
              }
            }
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(
            organizationID,
            customerBookFileExtractFileName,
            Some(customerBookFileProcessingMessage),
            customerBookFileExtractByteStream,
          )
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullName),
              emails = List(
                CustomerEmailEntry(email = customerEmailDefault, isDefault = true),
                CustomerEmailEntry(email = customerEmailSecondary, isDefault = false),
              ),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = List("Column B holds two emails for some rows"),
        )
      }

      "extract candidates from an XLS file through Excel conversion" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.xls")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.xls")),
          supportedMediaType = SupportedMediaType.XLS,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val csvRecords: List[CSVRecord] =
          CSVParser.parse("Full Name\nJane Doe", CSVFormat.DEFAULT).getRecords.asScala.toList
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 0L,
          entriesProcessed = 0L,
          individualCandidates = Nil,
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          spreadsheetToolMock.convertExcelToCsv
            .expects(fileScannerScanOutput.fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CustomerBookFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCustomersFromFileInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .onCall { (csvRecordStream, _, _, _, _) =>
              csvRecordStream.runCollect.map { csvRecordsCollected =>
                csvRecordsCollected.toList shouldBe csvRecords
                NonEmptyChunk.single(customerBookFileAIExtract)
              }
            }
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = Nil,
        )
      }

      "extract candidates from an XLSX file through Excel conversion" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.xlsx")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.xlsx")),
          supportedMediaType = SupportedMediaType.XLSX,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val csvRecords: List[CSVRecord] =
          CSVParser.parse("Full Name\nJane Doe", CSVFormat.DEFAULT).getRecords.asScala.toList
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 0L,
          entriesProcessed = 0L,
          individualCandidates = Nil,
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          spreadsheetToolMock.convertExcelToCsv
            .expects(fileScannerScanOutput.fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CustomerBookFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCustomersFromFileInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .onCall { (csvRecordStream, _, _, _, _) =>
              csvRecordStream.runCollect.map { csvRecordsCollected =>
                csvRecordsCollected.toList shouldBe csvRecords
                NonEmptyChunk.single(customerBookFileAIExtract)
              }
            }
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = Nil,
        )
      }

      "send a WEBP upload to the image extraction" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.webp")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.webp")),
          supportedMediaType = SupportedMediaType.WEBP,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 0L,
          entriesProcessed = 0L,
          individualCandidates = Nil,
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          (aiClientDataExtractionMock
            .extractFromImage[CustomerBookFileAIExtract](
              _: FileScannedPath,
              _: SupportedMediaType,
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              fileScannerScanOutput.fileScannedPath,
              SupportedMediaType.WEBP,
              AIInstructions.extractCustomersFromImageInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .returns(ZIO.succeed(customerBookFileAIExtract))
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = Nil,
        )
      }

      "merge spreadsheet batches in group order and replace the repeated top-level notes with the compacted notes" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.csv")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.csv")),
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val csvRecords: List[CSVRecord] =
          CSVParser.parse("Full Name\nJane Doe\nJohn Roe", CSVFormat.DEFAULT).getRecords.asScala.toList
        val customerFullNameBatch1 = arbitrarySample[CustomerFullName]
        val customerFullNameBatch2 = CustomerFullName.assume(
          if (customerFullNameBatch1.value.length < CustomerFullNameLengthMax) customerFullNameBatch1.value + "b"
          else customerFullNameBatch1.value.dropRight(1)
        )
        val customerEmailBatch1Default        = arbitrarySample[CustomerEmail]
        val customerEmailBatch1Secondary      = arbitrarySample[CustomerEmail]
        val customerEmailBatch2Default        = arbitrarySample[CustomerEmail]
        val customerEmailBatch2Secondary      = arbitrarySample[CustomerEmail]
        val customerBusinessName              = arbitrarySample[CustomerBusinessName]
        val customerBusinessEmailDefault      = arbitrarySample[CustomerEmail]
        val customerBusinessEmailSecondary    = arbitrarySample[CustomerEmail]
        val customerIndividualAIExtractBatch1 = CustomerIndividualAIExtract(
          fullName = Some(customerFullNameBatch1),
          emails = List(customerEmailBatch1Default, customerEmailBatch1Secondary),
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerIndividualAIExtractBatch2 = customerIndividualAIExtractBatch1.copy(
          fullName = Some(customerFullNameBatch2),
          emails = List(customerEmailBatch2Default, customerEmailBatch2Secondary),
        )
        val customerBusinessAIExtract = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = List(customerBusinessEmailDefault, customerBusinessEmailSecondary),
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = Nil,
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtractBatch1 = CustomerBookFileAIExtract(
          entriesIdentified = 4L,
          entriesProcessed = 2L,
          individualCandidates = List(customerIndividualAIExtractBatch1),
          businessCandidates = List(customerBusinessAIExtract),
          emptyEntryRowIndexes = List(3L),
          unidentifiedEntryRowIndexes = List(5L),
          extractionNotes = List("Column D looks like an internal reference", "Some cells are merged"),
        )
        val customerBookFileAIExtractBatch2 = CustomerBookFileAIExtract(
          entriesIdentified = 3L,
          entriesProcessed = 1L,
          individualCandidates = List(customerIndividualAIExtractBatch2),
          businessCandidates = Nil,
          emptyEntryRowIndexes = List(52L),
          unidentifiedEntryRowIndexes = List(54L),
          extractionNotes = List("Column D looks like an internal reference"),
        )
        val customerBookFileAIExtractNotesMerged = List(
          "Column D looks like an internal reference",
          "Some cells are merged",
          "Column D looks like an internal reference",
        )
        val noteCompactionOutput = NoteCompactionOutput(
          notes = List("Column D looks like an internal reference in every group", "Some cells are merged")
        )

        customerFullNameBatch2.value.toLowerCase(Locale.ROOT) shouldNot equal(
          customerFullNameBatch1.value.toLowerCase(Locale.ROOT)
        )
        noteCompactionOutput.notes shouldNot equal(customerBookFileAIExtractNotesMerged)

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          spreadsheetToolMock.convertToCsv
            .expects(fileScannerScanOutput.fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CustomerBookFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCustomersFromFileInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .onCall { (csvRecordStream, _, _, _, _) =>
              csvRecordStream.runCollect.map { csvRecordsCollected =>
                csvRecordsCollected.toList shouldBe csvRecords
                NonEmptyChunk(customerBookFileAIExtractBatch1, customerBookFileAIExtractBatch2)
              }
            }
            .once(),
          aiClientDataExtractionMock.noteCompaction
            .expects(customerBookFileAIExtractNotesMerged, AIInstructions.compactCustomerBookFileNotesInstructions)
            .returns(ZIO.succeed(noteCompactionOutput))
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullNameBatch1),
              emails = List(
                CustomerEmailEntry(email = customerEmailBatch1Default, isDefault = true),
                CustomerEmailEntry(email = customerEmailBatch1Secondary, isDefault = false),
              ),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            ),
            CustomerIndividualCandidate(
              fullName = Some(customerFullNameBatch2),
              emails = List(
                CustomerEmailEntry(email = customerEmailBatch2Default, isDefault = true),
                CustomerEmailEntry(email = customerEmailBatch2Secondary, isDefault = false),
              ),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            ),
          ),
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = Some(customerBusinessName),
              emails = List(
                CustomerEmailEntry(email = customerBusinessEmailDefault, isDefault = true),
                CustomerEmailEntry(email = customerBusinessEmailSecondary, isDefault = false),
              ),
              taxID = None,
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = Nil,
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            )
          ),
          infoNotes = List(
            "Processed 3 of 7 entries",
            "Empty rows (2) at [3, 52]",
            "Unidentified rows (2) at [5, 54]",
          ),
          extractionNotes = noteCompactionOutput.notes,
        )
      }

      "replace the top-level notes with the compacted notes when compaction returns as many notes as it was sent" in new TestContext {
        val customerBookFileAIExtractNotesGroup  = List("Some cells are merged")
        val customerBookFileAIExtractNotesMerged = List("Some cells are merged", "Some cells are merged")
        val noteCompactionOutput                 = NoteCompactionOutput(
          notes = List("Some cells are merged in every group", "Column D looks like an internal reference")
        )

        noteCompactionOutput.notes.size shouldBe customerBookFileAIExtractNotesMerged.size
        noteCompactionOutput.notes shouldNot equal(customerBookFileAIExtractNotesMerged)

        aiExtractFromSpreadsheetWithTwoGroups(
          customerBookFileAIExtractNotesGroup,
          customerBookFileAIExtractNotesMerged,
          ZIO.succeed(noteCompactionOutput),
        ) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = noteCompactionOutput.notes,
        )
      }

      "keep the raw top-level notes when note compaction fails with an UnexpectedError" in new TestContext {
        val customerBookFileAIExtractNotesGroup  = List("Some cells are merged")
        val customerBookFileAIExtractNotesMerged = List("Some cells are merged", "Some cells are merged")
        val serviceErrorNoteCompaction           =
          ServiceError.InternalServerError.UnexpectedError(error = "Note compaction failed")

        aiExtractFromSpreadsheetWithTwoGroups(
          customerBookFileAIExtractNotesGroup,
          customerBookFileAIExtractNotesMerged,
          ZIO.fail(serviceErrorNoteCompaction),
        ) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = customerBookFileAIExtractNotesMerged,
        )
      }

      "keep the raw top-level notes when note compaction dies with a defect" in new TestContext {
        val customerBookFileAIExtractNotesGroup  = List("Some cells are merged")
        val customerBookFileAIExtractNotesMerged = List("Some cells are merged", "Some cells are merged")
        val runtimeExceptionNoteCompaction       = new RuntimeException("Note compaction defect")

        aiExtractFromSpreadsheetWithTwoGroups(
          customerBookFileAIExtractNotesGroup,
          customerBookFileAIExtractNotesMerged,
          ZIO.die(runtimeExceptionNoteCompaction),
        ) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = customerBookFileAIExtractNotesMerged,
        )
      }

      "keep the raw top-level notes when note compaction returns no notes" in new TestContext {
        val customerBookFileAIExtractNotesGroup  = List("Some cells are merged")
        val customerBookFileAIExtractNotesMerged = List("Some cells are merged", "Some cells are merged")
        val noteCompactionOutput                 = NoteCompactionOutput(notes = Nil)

        aiExtractFromSpreadsheetWithTwoGroups(
          customerBookFileAIExtractNotesGroup,
          customerBookFileAIExtractNotesMerged,
          ZIO.succeed(noteCompactionOutput),
        ) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = customerBookFileAIExtractNotesMerged,
        )
      }

      "keep the raw top-level notes when note compaction returns more notes than it was sent" in new TestContext {
        val customerBookFileAIExtractNotesGroup  = List("Some cells are merged")
        val customerBookFileAIExtractNotesMerged = List("Some cells are merged", "Some cells are merged")
        val noteCompactionOutput                 = NoteCompactionOutput(
          notes = List("Some cells are merged", "Column D looks like an internal reference", "Row 7 is blank")
        )

        noteCompactionOutput.notes.size should be > customerBookFileAIExtractNotesMerged.size

        aiExtractFromSpreadsheetWithTwoGroups(
          customerBookFileAIExtractNotesGroup,
          customerBookFileAIExtractNotesMerged,
          ZIO.succeed(noteCompactionOutput),
        ) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = customerBookFileAIExtractNotesMerged,
        )
      }

      "summarize the AI's processed entries against its total, without a candidates note when every processed entry made one candidate, or row notes" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.jpg")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.jpg")),
          supportedMediaType = SupportedMediaType.JPEG,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val customerFullName            = arbitrarySample[CustomerFullName]
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract =
          CustomerBookFileAIExtract(
            entriesIdentified = 4L,
            entriesProcessed = 1L,
            individualCandidates = List(customerIndividualAIExtract),
            businessCandidates = Nil,
            emptyEntryRowIndexes = Nil,
            unidentifiedEntryRowIndexes = Nil,
            extractionNotes = Nil,
          )

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          (aiClientDataExtractionMock
            .extractFromImage[CustomerBookFileAIExtract](
              _: FileScannedPath,
              _: SupportedMediaType,
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              fileScannerScanOutput.fileScannedPath,
              SupportedMediaType.JPEG,
              AIInstructions.extractCustomersFromImageInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .returns(ZIO.succeed(customerBookFileAIExtract))
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullName),
              emails = Nil,
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 1 of 4 entries"),
          extractionNotes = Nil,
        )
      }

      "summarize several empty rows before a single unidentified row" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.csv")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.csv")),
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val csvRecords: List[CSVRecord] =
          CSVParser.parse("Full Name\n\n?\n", CSVFormat.DEFAULT).getRecords.asScala.toList
        val customerBookFileAIExtract =
          CustomerBookFileAIExtract(
            entriesIdentified = 3L,
            entriesProcessed = 0L,
            individualCandidates = Nil,
            businessCandidates = Nil,
            emptyEntryRowIndexes = List(2L, 5L),
            unidentifiedEntryRowIndexes = List(7L),
            extractionNotes = Nil,
          )

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          spreadsheetToolMock.convertToCsv
            .expects(fileScannerScanOutput.fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CustomerBookFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCustomersFromFileInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .returns(ZIO.succeed(NonEmptyChunk.single(customerBookFileAIExtract)))
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List(
            "Processed 0 of 3 entries",
            "Empty rows (2) at [2, 5]",
            "Unidentified rows (1) at [7]",
          ),
          extractionNotes = Nil,
        )
      }

      "summarize a spreadsheet's candidates against the AI's total that includes a single empty row" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.csv")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.csv")),
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val csvRecords: List[CSVRecord] =
          CSVParser.parse("Email\njane@example.com", CSVFormat.DEFAULT).getRecords.asScala.toList
        val customerEmail                       = arbitrarySample[CustomerEmail]
        val customerIndividualAIExtractNameless = CustomerIndividualAIExtract(
          fullName = None,
          emails = List(customerEmail),
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 347L,
          entriesProcessed = 346L,
          individualCandidates = List.fill(346)(customerIndividualAIExtractNameless),
          businessCandidates = Nil,
          emptyEntryRowIndexes = List(6L),
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          spreadsheetToolMock.convertToCsv
            .expects(fileScannerScanOutput.fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CustomerBookFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCustomersFromFileInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .returns(ZIO.succeed(NonEmptyChunk.single(customerBookFileAIExtract)))
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List.fill(346)(
            CustomerIndividualCandidate(
              fullName = None,
              emails = List(CustomerEmailEntry(email = customerEmail, isDefault = true)),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/fullName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 346 of 347 entries", "Empty rows (1) at [6]"),
          extractionNotes = Nil,
        )
      }

      "report the candidates created when split rows make more candidates than the AI's processed entries" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.csv")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.csv")),
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val csvRecords: List[CSVRecord] =
          CSVParser.parse("Email\njane@example.com", CSVFormat.DEFAULT).getRecords.asScala.toList
        val customerEmail                       = arbitrarySample[CustomerEmail]
        val customerIndividualAIExtractNameless = CustomerIndividualAIExtract(
          fullName = None,
          emails = List(customerEmail),
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 347L,
          entriesProcessed = 346L,
          individualCandidates = List.fill(440)(customerIndividualAIExtractNameless),
          businessCandidates = Nil,
          emptyEntryRowIndexes = List(6L),
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        customerBookFileAIExtract.individualCandidates.size.toLong shouldNot equal(
          customerBookFileAIExtract.entriesProcessed
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          spreadsheetToolMock.convertToCsv
            .expects(fileScannerScanOutput.fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CustomerBookFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCustomersFromFileInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .returns(ZIO.succeed(NonEmptyChunk.single(customerBookFileAIExtract)))
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List.fill(440)(
            CustomerIndividualCandidate(
              fullName = None,
              emails = List(CustomerEmailEntry(email = customerEmail, isDefault = true)),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/fullName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 346 of 347 entries", "Candidates created 440", "Empty rows (1) at [6]"),
          extractionNotes = Nil,
        )
      }

      "report the candidates created when merged entries make fewer candidates than the AI's processed entries" in new TestContext {
        val customerFullName            = arbitrarySample[CustomerFullName]
        val customerBusinessName        = arbitrarySample[CustomerBusinessName]
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessAIExtract = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = Nil,
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 3L,
          entriesProcessed = 3L,
          individualCandidates = List(customerIndividualAIExtract),
          businessCandidates = List(customerBusinessAIExtract),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = List("Two rows describe the same customer"),
        )

        (customerBookFileAIExtract.individualCandidates.size + customerBookFileAIExtract.businessCandidates.size).toLong should be <
          customerBookFileAIExtract.entriesProcessed

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullName),
              emails = Nil,
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = Some(customerBusinessName),
              emails = Nil,
              taxID = None,
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = Nil,
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            )
          ),
          infoNotes = List("Processed 3 of 3 entries", "Candidates created 2"),
          extractionNotes = List("Two rows describe the same customer"),
        )
      }

      "sum the processed and identified entries of every spreadsheet group and report the candidates created" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.csv")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.csv")),
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val csvRecords: List[CSVRecord] =
          CSVParser.parse("Email\njane@example.com", CSVFormat.DEFAULT).getRecords.asScala.toList
        val customerEmail                       = arbitrarySample[CustomerEmail]
        val customerIndividualAIExtractNameless = CustomerIndividualAIExtract(
          fullName = None,
          emails = List(customerEmail),
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtractBatch1 = CustomerBookFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 1L,
          individualCandidates = List.fill(2)(customerIndividualAIExtractNameless),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = List(3L),
          extractionNotes = Nil,
        )
        val customerBookFileAIExtractBatch2 = CustomerBookFileAIExtract(
          entriesIdentified = 3L,
          entriesProcessed = 2L,
          individualCandidates = List.fill(2)(customerIndividualAIExtractNameless),
          businessCandidates = Nil,
          emptyEntryRowIndexes = List(52L),
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        customerBookFileAIExtractBatch1.entriesProcessed shouldNot equal(
          customerBookFileAIExtractBatch2.entriesProcessed
        )
        customerBookFileAIExtractBatch1.entriesIdentified shouldNot equal(
          customerBookFileAIExtractBatch2.entriesIdentified
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          spreadsheetToolMock.convertToCsv
            .expects(fileScannerScanOutput.fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CustomerBookFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCustomersFromFileInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .returns(ZIO.succeed(NonEmptyChunk(customerBookFileAIExtractBatch1, customerBookFileAIExtractBatch2)))
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List.fill(4)(
            CustomerIndividualCandidate(
              fullName = None,
              emails = List(CustomerEmailEntry(email = customerEmail, isDefault = true)),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/fullName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = Nil,
          infoNotes = List(
            "Processed 3 of 5 entries",
            "Candidates created 4",
            "Empty rows (1) at [52]",
            "Unidentified rows (1) at [3]",
          ),
          extractionNotes = Nil,
        )
      }

      "report an image's unidentified entry positions with the AI's total" in new TestContext {
        val customerBusinessName      = arbitrarySample[CustomerBusinessName]
        val customerBusinessAIExtract = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = Nil,
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 3L,
          entriesProcessed = 1L,
          individualCandidates = Nil,
          businessCandidates = List(customerBusinessAIExtract),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = List(2L, 4L),
          extractionNotes = List("Two entries were too blurred to read"),
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = Some(customerBusinessName),
              emails = Nil,
              taxID = None,
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = Nil,
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            )
          ),
          infoNotes = List("Processed 1 of 3 entries", "Unidentified rows (2) at [2, 4]"),
          extractionNotes = List("Two entries were too blurred to read"),
        )
      }

      "return an empty preview with the general note when nothing is recognized" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.jpeg")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.jpeg")),
          supportedMediaType = SupportedMediaType.JPEG,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val extractionNotes           = List("AI could not detect relevant customer data in this image.")
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 0L,
          entriesProcessed = 0L,
          individualCandidates = Nil,
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = extractionNotes,
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          (aiClientDataExtractionMock
            .extractFromImage[CustomerBookFileAIExtract](
              _: FileScannedPath,
              _: SupportedMediaType,
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              fileScannerScanOutput.fileScannedPath,
              SupportedMediaType.JPEG,
              AIInstructions.extractCustomersFromImageInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .returns(ZIO.succeed(customerBookFileAIExtract))
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = extractionNotes,
        )
      }

      "mark same-kind candidates with exactly equal names in an image as duplicates" in new TestContext {
        val customerFullName            = arbitrarySample[CustomerFullName]
        val customerBusinessName        = arbitrarySample[CustomerBusinessName]
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = List("The same name appears twice in the image"),
        )
        val customerBusinessAIExtract = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = Nil,
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 4L,
          entriesProcessed = 4L,
          individualCandidates = List(customerIndividualAIExtract, customerIndividualAIExtract),
          businessCandidates = List(customerBusinessAIExtract, customerBusinessAIExtract),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )
        val customerIndividualCandidateDuplicate = CustomerIndividualCandidate(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = Nil,
          inferredFields = Nil,
          missingFields = Nil,
          duplicateFields = List(FieldInfo(path = "/fullName")),
          needsAttention = true,
          extractionNotes = List("The same name appears twice in the image"),
        )
        val customerBusinessCandidateDuplicate = CustomerBusinessCandidate(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = Nil,
          inferredFields = Nil,
          missingFields = Nil,
          duplicateFields = List(FieldInfo(path = "/businessName")),
          needsAttention = true,
          extractionNotes = Nil,
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(customerIndividualCandidateDuplicate, customerIndividualCandidateDuplicate),
          businessCandidates = List(customerBusinessCandidateDuplicate, customerBusinessCandidateDuplicate),
          infoNotes = List("Processed 4 of 4 entries"),
          extractionNotes = Nil,
        )
      }

      "mark same-kind candidates with exactly equal names in different spreadsheet groups as duplicates" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.csv")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.csv")),
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val csvRecords: List[CSVRecord] =
          CSVParser.parse("Full Name\nJane Doe\nJane Doe", CSVFormat.DEFAULT).getRecords.asScala.toList
        val customerFullName            = arbitrarySample[CustomerFullName]
        val customerBusinessName        = arbitrarySample[CustomerBusinessName]
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessAIExtract = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = Nil,
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtractBatch = CustomerBookFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          individualCandidates = List(customerIndividualAIExtract),
          businessCandidates = List(customerBusinessAIExtract),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )
        val customerIndividualCandidateDuplicate = CustomerIndividualCandidate(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = Nil,
          inferredFields = Nil,
          missingFields = Nil,
          duplicateFields = List(FieldInfo(path = "/fullName")),
          needsAttention = true,
          extractionNotes = Nil,
        )
        val customerBusinessCandidateDuplicate = CustomerBusinessCandidate(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = Nil,
          inferredFields = Nil,
          missingFields = Nil,
          duplicateFields = List(FieldInfo(path = "/businessName")),
          needsAttention = true,
          extractionNotes = Nil,
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          spreadsheetToolMock.convertToCsv
            .expects(fileScannerScanOutput.fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CustomerBookFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCustomersFromFileInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .returns(ZIO.succeed(NonEmptyChunk(customerBookFileAIExtractBatch, customerBookFileAIExtractBatch)))
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(customerIndividualCandidateDuplicate, customerIndividualCandidateDuplicate),
          businessCandidates = List(customerBusinessCandidateDuplicate, customerBusinessCandidateDuplicate),
          infoNotes = List("Processed 4 of 4 entries"),
          extractionNotes = Nil,
        )
      }

      "not mark names that differ only in letter case as duplicates" in new TestContext {
        val customerFullNameLowerCase =
          CustomerFullName.assume(
            (arbitrarySample[CustomerFullName].value.take(CustomerFullNameLengthMax - 1) + "a").toLowerCase(Locale.ROOT)
          )
        val customerFullNameUpperCase =
          CustomerFullName.assume(customerFullNameLowerCase.value.toUpperCase(Locale.ROOT))
        val customerIndividualAIExtractLowerCase = CustomerIndividualAIExtract(
          fullName = Some(customerFullNameLowerCase),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerIndividualAIExtractUpperCase =
          customerIndividualAIExtractLowerCase.copy(fullName = Some(customerFullNameUpperCase))
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          individualCandidates = List(customerIndividualAIExtractLowerCase, customerIndividualAIExtractUpperCase),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        customerFullNameUpperCase shouldNot equal(customerFullNameLowerCase)
        customerFullNameUpperCase.value.toLowerCase(Locale.ROOT) shouldBe customerFullNameLowerCase.value

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullNameLowerCase),
              emails = Nil,
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            ),
            CustomerIndividualCandidate(
              fullName = Some(customerFullNameUpperCase),
              emails = Nil,
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            ),
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 2 of 2 entries"),
          extractionNotes = Nil,
        )
      }

      "not mark a person and a business with the same name as duplicates" in new TestContext {
        val customerFullName            = arbitrarySample[CustomerFullName]
        val customerBusinessName        = CustomerBusinessName.assume(customerFullName.value)
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessAIExtract = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = Nil,
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          individualCandidates = List(customerIndividualAIExtract),
          businessCandidates = List(customerBusinessAIExtract),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullName),
              emails = Nil,
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = Some(customerBusinessName),
              emails = Nil,
              taxID = None,
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = Nil,
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            )
          ),
          infoNotes = List("Processed 2 of 2 entries"),
          extractionNotes = Nil,
        )
      }

      "not mark nameless candidates as duplicates of each other" in new TestContext {
        val customerEmail1               = arbitrarySample[CustomerEmail]
        val customerEmail2               = arbitrarySample[CustomerEmail]
        val customerTaxID1               = arbitrarySample[CustomerTaxID]
        val customerTaxID2               = arbitrarySample[CustomerTaxID]
        val customerIndividualAIExtract1 = CustomerIndividualAIExtract(
          fullName = None,
          emails = List(customerEmail1),
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerIndividualAIExtract2 = customerIndividualAIExtract1.copy(emails = List(customerEmail2))
        val customerBusinessAIExtract1   = CustomerBusinessAIExtract(
          businessName = None,
          emails = Nil,
          taxID = Some(customerTaxID1),
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = Nil,
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessAIExtract2 = customerBusinessAIExtract1.copy(taxID = Some(customerTaxID2))
        val customerBookFileAIExtract  = CustomerBookFileAIExtract(
          entriesIdentified = 4L,
          entriesProcessed = 4L,
          individualCandidates = List(customerIndividualAIExtract1, customerIndividualAIExtract2),
          businessCandidates = List(customerBusinessAIExtract1, customerBusinessAIExtract2),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = None,
              emails = List(CustomerEmailEntry(email = customerEmail1, isDefault = true)),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/fullName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            ),
            CustomerIndividualCandidate(
              fullName = None,
              emails = List(CustomerEmailEntry(email = customerEmail2, isDefault = true)),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/fullName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            ),
          ),
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = None,
              emails = Nil,
              taxID = Some(customerTaxID1),
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/businessName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            ),
            CustomerBusinessCandidate(
              businessName = None,
              emails = Nil,
              taxID = Some(customerTaxID2),
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/businessName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            ),
          ),
          infoNotes = List("Processed 4 of 4 entries"),
          extractionNotes = Nil,
        )
      }

      "report a missing full name for nameless individuals, keeping them as the AI returned them" in new TestContext {
        val customerEmail                    = arbitrarySample[CustomerEmail]
        val customerCity                     = arbitrarySample[City]
        val phoneNationalNumber              = arbitrarySample[PhoneNationalNumber]
        val phoneCountryCode                 = arbitrarySample[PhoneCountryCode]
        val customerIndividualAIExtractEmail = CustomerIndividualAIExtract(
          fullName = None,
          emails = List(customerEmail),
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = List("Only an email is readable"),
        )
        val customerIndividualAIExtractPhoneNumber = customerIndividualAIExtractEmail.copy(
          emails = Nil,
          phoneNumbers = List(
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber,
              phoneCountryCode = Some(phoneCountryCode),
              phoneCountryCodeInferred = false,
            )
          ),
          extractionNotes = Nil,
        )
        val customerIndividualAIExtractAddress = customerIndividualAIExtractEmail.copy(
          emails = Nil,
          addresses = List(
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(customerCity),
                postalCode = None,
                country = None,
                extractionNotes = Nil,
              ),
              addressType = arbitrarySample[AddressType],
            )
          ),
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 3L,
          entriesProcessed = 3L,
          individualCandidates = List(
            customerIndividualAIExtractEmail,
            customerIndividualAIExtractPhoneNumber,
            customerIndividualAIExtractAddress,
          ),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = None,
              emails = List(CustomerEmailEntry(email = customerEmail, isDefault = true)),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/fullName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = List("Only an email is readable"),
            ),
            CustomerIndividualCandidate(
              fullName = None,
              emails = Nil,
              phoneNumbers = List(
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber = CustomerPhoneNumberCandidate(
                    phoneNationalNumber = phoneNationalNumber,
                    phoneCountryCode = Some(phoneCountryCode),
                  ),
                  isDefault = true,
                )
              ),
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/fullName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            ),
            CustomerIndividualCandidate(
              fullName = None,
              emails = Nil,
              phoneNumbers = Nil,
              addresses = List(
                CustomerAddressEntry(
                  AddressEntry(
                    address = Address(
                      addressLine1 = None,
                      addressLine2 = None,
                      city = Some(customerCity),
                      postalCode = None,
                      country = None,
                    ),
                    addressType = AddressType.ShippingAndBilling,
                  )
                )
              ),
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/fullName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            ),
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 3 of 3 entries"),
          extractionNotes = Nil,
        )
      }

      "report a missing business name for nameless businesses, keeping them as the AI returned them" in new TestContext {
        val customerTaxID                   = arbitrarySample[CustomerTaxID]
        val customerBusinessContactFullName = arbitrarySample[CustomerFullName]
        val customerBusinessAIExtractTaxID  = CustomerBusinessAIExtract(
          businessName = None,
          emails = Nil,
          taxID = Some(customerTaxID),
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = Nil,
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessAIExtractContact = customerBusinessAIExtractTaxID.copy(
          taxID = None,
          customerBusinessContacts = List(
            CustomerBusinessContactAIExtract(
              fullName = Some(customerBusinessContactFullName),
              role = None,
              email = None,
              phoneNumber = None,
              fullNameInferred = false,
              extractionNotes = Nil,
            )
          ),
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          individualCandidates = Nil,
          businessCandidates = List(customerBusinessAIExtractTaxID, customerBusinessAIExtractContact),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = None,
              emails = Nil,
              taxID = Some(customerTaxID),
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/businessName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            ),
            CustomerBusinessCandidate(
              businessName = None,
              emails = Nil,
              taxID = None,
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = List(
                CustomerBusinessContactCandidate(
                  fullName = Some(customerBusinessContactFullName),
                  role = None,
                  email = None,
                  phoneNumber = None,
                )
              ),
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/businessName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            ),
          ),
          infoNotes = List("Processed 2 of 2 entries"),
          extractionNotes = Nil,
        )
      }

      "report each nameless contact's missing full name by its position in the contact list and mark the business" in new TestContext {
        val customerBusinessName                 = arbitrarySample[CustomerBusinessName]
        val customerBusinessContactRole          = arbitrarySample[CustomerBusinessContactRole]
        val customerBusinessContactEmail         = arbitrarySample[CustomerEmail]
        val phoneNationalNumber                  = arbitrarySample[PhoneNationalNumber]
        val phoneCountryCode                     = arbitrarySample[PhoneCountryCode]
        val customerBusinessContactAIExtractRole = CustomerBusinessContactAIExtract(
          fullName = None,
          role = Some(customerBusinessContactRole),
          email = None,
          phoneNumber = None,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessContactAIExtractEmail =
          customerBusinessContactAIExtractRole.copy(role = None, email = Some(customerBusinessContactEmail))
        val customerBusinessContactAIExtractPhoneNumber = customerBusinessContactAIExtractRole.copy(
          role = None,
          phoneNumber = Some(
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber,
              phoneCountryCode = Some(phoneCountryCode),
              phoneCountryCodeInferred = false,
            )
          ),
        )
        val customerBusinessAIExtract = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = List(
            customerBusinessContactAIExtractRole,
            customerBusinessContactAIExtractEmail,
            customerBusinessContactAIExtractPhoneNumber,
          ),
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          individualCandidates = Nil,
          businessCandidates = List(customerBusinessAIExtract),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = Some(customerBusinessName),
              emails = Nil,
              taxID = None,
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = List(
                CustomerBusinessContactCandidate(
                  fullName = None,
                  role = Some(customerBusinessContactRole),
                  email = None,
                  phoneNumber = None,
                ),
                CustomerBusinessContactCandidate(
                  fullName = None,
                  role = None,
                  email = Some(customerBusinessContactEmail),
                  phoneNumber = None,
                ),
                CustomerBusinessContactCandidate(
                  fullName = None,
                  role = None,
                  email = None,
                  phoneNumber = Some(
                    CustomerPhoneNumberCandidate(
                      phoneNationalNumber = phoneNationalNumber,
                      phoneCountryCode = Some(phoneCountryCode),
                    )
                  ),
                ),
              ),
              inferredFields = Nil,
              missingFields = List(
                FieldInfo(path = "/customerBusinessContacts/0/fullName"),
                FieldInfo(path = "/customerBusinessContacts/1/fullName"),
                FieldInfo(path = "/customerBusinessContacts/2/fullName"),
              ),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "keep a customer phone without a country code, report the country code missing at its position and mark the candidate" in new TestContext {
        val customerFullName            = arbitrarySample[CustomerFullName]
        val phoneNationalNumber         = arbitrarySample[PhoneNationalNumber]
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = List(
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber,
              phoneCountryCode = None,
              phoneCountryCodeInferred = false,
            )
          ),
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = List("The country code is cut off"),
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          individualCandidates = List(customerIndividualAIExtract),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullName),
              emails = Nil,
              phoneNumbers = List(
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber =
                    CustomerPhoneNumberCandidate(phoneNationalNumber = phoneNationalNumber, phoneCountryCode = None),
                  isDefault = true,
                )
              ),
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/phoneNumbers/0/phoneNumber/phoneCountryCode")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = List("The country code is cut off"),
            )
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "keep a contact phone without a country code, report the country code missing at the contact's position and mark the business" in new TestContext {
        val customerBusinessName            = arbitrarySample[CustomerBusinessName]
        val customerBusinessContactFullName = arbitrarySample[CustomerFullName]
        val phoneNationalNumber             = arbitrarySample[PhoneNationalNumber]
        val customerBusinessAIExtract       = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = List(
            CustomerBusinessContactAIExtract(
              fullName = Some(customerBusinessContactFullName),
              role = None,
              email = None,
              phoneNumber = Some(
                CustomerPhoneNumberAIExtract(
                  phoneNationalNumber = phoneNationalNumber,
                  phoneCountryCode = None,
                  phoneCountryCodeInferred = false,
                )
              ),
              fullNameInferred = false,
              extractionNotes = Nil,
            )
          ),
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          individualCandidates = Nil,
          businessCandidates = List(customerBusinessAIExtract),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = Some(customerBusinessName),
              emails = Nil,
              taxID = None,
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = List(
                CustomerBusinessContactCandidate(
                  fullName = Some(customerBusinessContactFullName),
                  role = None,
                  email = None,
                  phoneNumber = Some(
                    CustomerPhoneNumberCandidate(phoneNationalNumber = phoneNationalNumber, phoneCountryCode = None)
                  ),
                )
              ),
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/customerBusinessContacts/0/phoneNumber/phoneCountryCode")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "drop an all-empty address without marking the candidate, keeping its AI notes" in new TestContext {
        val customerFullName            = arbitrarySample[CustomerFullName]
        val customerAddressLine1        = arbitrarySample[AddressLine1]
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = List(
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = None,
                postalCode = None,
                country = None,
                extractionNotes = List("The second address is smudged"),
              ),
              addressType = arbitrarySample[AddressType],
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = Some(customerAddressLine1),
                addressLine2 = None,
                city = None,
                postalCode = None,
                country = None,
                extractionNotes = List("Only the street is readable"),
              ),
              addressType = arbitrarySample[AddressType],
            ),
          ),
          fullNameInferred = false,
          extractionNotes = List("Two addresses are shown"),
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          individualCandidates = List(customerIndividualAIExtract),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullName),
              emails = Nil,
              phoneNumbers = Nil,
              addresses = List(
                CustomerAddressEntry(
                  AddressEntry(
                    address = Address(
                      addressLine1 = Some(customerAddressLine1),
                      addressLine2 = None,
                      city = None,
                      postalCode = None,
                      country = None,
                    ),
                    addressType = AddressType.ShippingAndBilling,
                  )
                )
              ),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List(
                "Two addresses are shown",
                "The second address is smudged",
                "Only the street is readable",
              ),
            )
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "type a single address as shipping and billing whatever type the AI gave it" in new TestContext {
        val customerCity                      = arbitrarySample[City]
        val customerFullNamesWithAddressTypes = AddressType.values.toList.map(addressType =>
          (customerFullName = arbitrarySample[CustomerFullName], addressType = addressType)
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 3L,
          entriesProcessed = 3L,
          individualCandidates = customerFullNamesWithAddressTypes.map(customerFullNameWithAddressType =>
            CustomerIndividualAIExtract(
              fullName = Some(customerFullNameWithAddressType.customerFullName),
              emails = Nil,
              phoneNumbers = Nil,
              addresses = List(
                CustomerAddressEntryAIExtract(
                  address = CustomerAddressAIExtract(
                    addressLine1 = None,
                    addressLine2 = None,
                    city = Some(customerCity),
                    postalCode = None,
                    country = None,
                    extractionNotes = Nil,
                  ),
                  addressType = customerFullNameWithAddressType.addressType,
                )
              ),
              fullNameInferred = false,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        customerFullNamesWithAddressTypes.map(_.customerFullName).distinct should have size 3

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = customerFullNamesWithAddressTypes.map(customerFullNameWithAddressType =>
            CustomerIndividualCandidate(
              fullName = Some(customerFullNameWithAddressType.customerFullName),
              emails = Nil,
              phoneNumbers = Nil,
              addresses = List(
                CustomerAddressEntry(
                  AddressEntry(
                    address = Address(
                      addressLine1 = None,
                      addressLine2 = None,
                      city = Some(customerCity),
                      postalCode = None,
                      country = None,
                    ),
                    addressType = AddressType.ShippingAndBilling,
                  )
                )
              ),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 3 of 3 entries"),
          extractionNotes = Nil,
        )
      }

      "keep two addresses typed as one shipping and one billing, in either order, as the AI typed them" in new TestContext {
        val customerCityFirst                     = arbitrarySample[City]
        val customerCitySecond                    = arbitrarySample[City]
        val customerFullNamesWithAddressTypePairs = List(
          (
            customerFullName = arbitrarySample[CustomerFullName],
            addressTypeFirst = AddressType.Shipping,
            addressTypeSecond = AddressType.Billing,
          ),
          (
            customerFullName = arbitrarySample[CustomerFullName],
            addressTypeFirst = AddressType.Billing,
            addressTypeSecond = AddressType.Shipping,
          ),
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          individualCandidates = customerFullNamesWithAddressTypePairs.map(customerFullNameWithAddressTypePair =>
            CustomerIndividualAIExtract(
              fullName = Some(customerFullNameWithAddressTypePair.customerFullName),
              emails = Nil,
              phoneNumbers = Nil,
              addresses = List(
                CustomerAddressEntryAIExtract(
                  address = CustomerAddressAIExtract(
                    addressLine1 = None,
                    addressLine2 = None,
                    city = Some(customerCityFirst),
                    postalCode = None,
                    country = None,
                    extractionNotes = Nil,
                  ),
                  addressType = customerFullNameWithAddressTypePair.addressTypeFirst,
                ),
                CustomerAddressEntryAIExtract(
                  address = CustomerAddressAIExtract(
                    addressLine1 = None,
                    addressLine2 = None,
                    city = Some(customerCitySecond),
                    postalCode = None,
                    country = None,
                    extractionNotes = Nil,
                  ),
                  addressType = customerFullNameWithAddressTypePair.addressTypeSecond,
                ),
              ),
              fullNameInferred = false,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        customerCitySecond shouldNot equal(customerCityFirst)
        customerFullNamesWithAddressTypePairs.map(_.customerFullName).distinct should have size 2

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = customerFullNamesWithAddressTypePairs.map(customerFullNameWithAddressTypePair =>
            CustomerIndividualCandidate(
              fullName = Some(customerFullNameWithAddressTypePair.customerFullName),
              emails = Nil,
              phoneNumbers = Nil,
              addresses = List(
                CustomerAddressEntry(
                  AddressEntry(
                    address = Address(
                      addressLine1 = None,
                      addressLine2 = None,
                      city = Some(customerCityFirst),
                      postalCode = None,
                      country = None,
                    ),
                    addressType = customerFullNameWithAddressTypePair.addressTypeFirst,
                  )
                ),
                CustomerAddressEntry(
                  AddressEntry(
                    address = Address(
                      addressLine1 = None,
                      addressLine2 = None,
                      city = Some(customerCitySecond),
                      postalCode = None,
                      country = None,
                    ),
                    addressType = customerFullNameWithAddressTypePair.addressTypeSecond,
                  )
                ),
              ),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 2 of 2 entries"),
          extractionNotes = Nil,
        )
      }

      "type two addresses as shipping then billing when the AI gave them the same type or either one shipping and billing" in new TestContext {
        val customerCityFirst                     = arbitrarySample[City]
        val customerCitySecond                    = arbitrarySample[City]
        val customerFullNamesWithAddressTypePairs = List(
          (addressTypeFirst = AddressType.Shipping, addressTypeSecond = AddressType.Shipping),
          (addressTypeFirst = AddressType.Billing, addressTypeSecond = AddressType.Billing),
          (addressTypeFirst = AddressType.ShippingAndBilling, addressTypeSecond = AddressType.ShippingAndBilling),
          (addressTypeFirst = AddressType.ShippingAndBilling, addressTypeSecond = AddressType.Shipping),
          (addressTypeFirst = AddressType.ShippingAndBilling, addressTypeSecond = AddressType.Billing),
          (addressTypeFirst = AddressType.Shipping, addressTypeSecond = AddressType.ShippingAndBilling),
          (addressTypeFirst = AddressType.Billing, addressTypeSecond = AddressType.ShippingAndBilling),
        ).map(addressTypePair =>
          (
            customerFullName = arbitrarySample[CustomerFullName],
            addressTypeFirst = addressTypePair.addressTypeFirst,
            addressTypeSecond = addressTypePair.addressTypeSecond,
          )
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 7L,
          entriesProcessed = 7L,
          individualCandidates = customerFullNamesWithAddressTypePairs.map(customerFullNameWithAddressTypePair =>
            CustomerIndividualAIExtract(
              fullName = Some(customerFullNameWithAddressTypePair.customerFullName),
              emails = Nil,
              phoneNumbers = Nil,
              addresses = List(
                CustomerAddressEntryAIExtract(
                  address = CustomerAddressAIExtract(
                    addressLine1 = None,
                    addressLine2 = None,
                    city = Some(customerCityFirst),
                    postalCode = None,
                    country = None,
                    extractionNotes = Nil,
                  ),
                  addressType = customerFullNameWithAddressTypePair.addressTypeFirst,
                ),
                CustomerAddressEntryAIExtract(
                  address = CustomerAddressAIExtract(
                    addressLine1 = None,
                    addressLine2 = None,
                    city = Some(customerCitySecond),
                    postalCode = None,
                    country = None,
                    extractionNotes = Nil,
                  ),
                  addressType = customerFullNameWithAddressTypePair.addressTypeSecond,
                ),
              ),
              fullNameInferred = false,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        customerCitySecond shouldNot equal(customerCityFirst)
        customerFullNamesWithAddressTypePairs.map(_.customerFullName).distinct should have size 7
        customerFullNamesWithAddressTypePairs.foreach(customerFullNameWithAddressTypePair =>
          List(
            customerFullNameWithAddressTypePair.addressTypeFirst,
            customerFullNameWithAddressTypePair.addressTypeSecond,
          ) shouldNot equal(List(AddressType.Shipping, AddressType.Billing))
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = customerFullNamesWithAddressTypePairs.map(customerFullNameWithAddressTypePair =>
            CustomerIndividualCandidate(
              fullName = Some(customerFullNameWithAddressTypePair.customerFullName),
              emails = Nil,
              phoneNumbers = Nil,
              addresses = List(
                CustomerAddressEntry(
                  AddressEntry(
                    address = Address(
                      addressLine1 = None,
                      addressLine2 = None,
                      city = Some(customerCityFirst),
                      postalCode = None,
                      country = None,
                    ),
                    addressType = AddressType.Shipping,
                  )
                ),
                CustomerAddressEntry(
                  AddressEntry(
                    address = Address(
                      addressLine1 = None,
                      addressLine2 = None,
                      city = Some(customerCitySecond),
                      postalCode = None,
                      country = None,
                    ),
                    addressType = AddressType.Billing,
                  )
                ),
              ),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 7 of 7 entries"),
          extractionNotes = Nil,
        )
      }

      "keep only the first two of three addresses, typed by the rule, and note the third as left out by its line 1 alone, keeping its AI notes before the left-out note" in new TestContext {
        val customerFullName            = arbitrarySample[CustomerFullName]
        val customerCity1               = arbitrarySample[City]
        val customerCity2               = arbitrarySample[City]
        val customerAddressLine1        = arbitrarySample[AddressLine1]
        val customerCity3               = arbitrarySample[City]
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = List(
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(customerCity1),
                postalCode = None,
                country = None,
                extractionNotes = Nil,
              ),
              addressType = AddressType.Billing,
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(customerCity2),
                postalCode = None,
                country = None,
                extractionNotes = Nil,
              ),
              addressType = AddressType.Billing,
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = Some(customerAddressLine1),
                addressLine2 = None,
                city = Some(customerCity3),
                postalCode = None,
                country = None,
                extractionNotes = List("The third address is faint"),
              ),
              addressType = arbitrarySample[AddressType],
            ),
          ),
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          individualCandidates = List(customerIndividualAIExtract),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        customerCity2 shouldNot equal(customerCity1)
        customerAddressLine1.value shouldNot equal(customerCity3.value)

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullName),
              emails = Nil,
              phoneNumbers = Nil,
              addresses =
                List(
                  CustomerAddressEntry(
                    AddressEntry(
                      address = Address(
                        addressLine1 = None,
                        addressLine2 = None,
                        city = Some(customerCity1),
                        postalCode = None,
                        country = None,
                      ),
                      addressType = AddressType.Shipping,
                    )
                  ),
                  CustomerAddressEntry(
                    AddressEntry(
                      address = Address(
                        addressLine1 = None,
                        addressLine2 = None,
                        city = Some(customerCity2),
                        postalCode = None,
                        country = None,
                      ),
                      addressType = AddressType.Billing,
                    )
                  ),
                ),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List(
                "The third address is faint",
                s"Address left out: ${customerAddressLine1.value}",
              ),
            )
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "write one left-out note per address beyond the first two, in the AI's order" in new TestContext {
        val customerFullName            = arbitrarySample[CustomerFullName]
        val customerCity1               = arbitrarySample[City]
        val customerCity2               = arbitrarySample[City]
        val customerAddressLine13       = arbitrarySample[AddressLine1]
        val customerCity3               = arbitrarySample[City]
        val customerAddressLine14       = arbitrarySample[AddressLine1]
        val customerCity4               = arbitrarySample[City]
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = List(
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(customerCity1),
                postalCode = None,
                country = None,
                extractionNotes = Nil,
              ),
              addressType = AddressType.Shipping,
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(customerCity2),
                postalCode = None,
                country = None,
                extractionNotes = Nil,
              ),
              addressType = AddressType.Billing,
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = Some(customerAddressLine13),
                addressLine2 = None,
                city = Some(customerCity3),
                postalCode = None,
                country = None,
                extractionNotes = Nil,
              ),
              addressType = arbitrarySample[AddressType],
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = Some(customerAddressLine14),
                addressLine2 = None,
                city = Some(customerCity4),
                postalCode = None,
                country = None,
                extractionNotes = Nil,
              ),
              addressType = arbitrarySample[AddressType],
            ),
          ),
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          individualCandidates = List(customerIndividualAIExtract),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        customerAddressLine14 shouldNot equal(customerAddressLine13)

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullName),
              emails = Nil,
              phoneNumbers = Nil,
              addresses =
                List(
                  CustomerAddressEntry(
                    AddressEntry(
                      address = Address(
                        addressLine1 = None,
                        addressLine2 = None,
                        city = Some(customerCity1),
                        postalCode = None,
                        country = None,
                      ),
                      addressType = AddressType.Shipping,
                    )
                  ),
                  CustomerAddressEntry(
                    AddressEntry(
                      address = Address(
                        addressLine1 = None,
                        addressLine2 = None,
                        city = Some(customerCity2),
                        postalCode = None,
                        country = None,
                      ),
                      addressType = AddressType.Billing,
                    )
                  ),
                ),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List(
                s"Address left out: ${customerAddressLine13.value}",
                s"Address left out: ${customerAddressLine14.value}",
              ),
            )
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "name a left-out address by its first non-empty field, in the order line 1, line 2, city, postal code, country" in new TestContext {
        val customerFullName            = arbitrarySample[CustomerFullName]
        val customerCity1               = arbitrarySample[City]
        val customerCity2               = arbitrarySample[City]
        val customerAddressLine1        = arbitrarySample[AddressLine1]
        val customerAddressLine2        = arbitrarySample[AddressLine2]
        val customerCity                = arbitrarySample[City]
        val customerPostalCode          = arbitrarySample[PostalCode]
        val customerCountry             = arbitrarySample[Country]
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = List(
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(customerCity1),
                postalCode = None,
                country = None,
                extractionNotes = Nil,
              ),
              addressType = AddressType.Shipping,
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(customerCity2),
                postalCode = None,
                country = None,
                extractionNotes = Nil,
              ),
              addressType = AddressType.Billing,
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = Some(customerAddressLine1),
                addressLine2 = Some(customerAddressLine2),
                city = Some(customerCity),
                postalCode = Some(customerPostalCode),
                country = Some(customerCountry),
                extractionNotes = Nil,
              ),
              addressType = arbitrarySample[AddressType],
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = Some(customerAddressLine2),
                city = Some(customerCity),
                postalCode = Some(customerPostalCode),
                country = Some(customerCountry),
                extractionNotes = Nil,
              ),
              addressType = arbitrarySample[AddressType],
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(customerCity),
                postalCode = Some(customerPostalCode),
                country = Some(customerCountry),
                extractionNotes = Nil,
              ),
              addressType = arbitrarySample[AddressType],
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = None,
                postalCode = Some(customerPostalCode),
                country = Some(customerCountry),
                extractionNotes = Nil,
              ),
              addressType = arbitrarySample[AddressType],
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = None,
                postalCode = None,
                country = Some(customerCountry),
                extractionNotes = Nil,
              ),
              addressType = arbitrarySample[AddressType],
            ),
          ),
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          individualCandidates = List(customerIndividualAIExtract),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        List(
          customerAddressLine1.value,
          customerAddressLine2.value,
          customerCity.value,
          customerPostalCode.value,
          customerCountry.value,
        ).distinct should have size 5

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullName),
              emails = Nil,
              phoneNumbers = Nil,
              addresses =
                List(
                  CustomerAddressEntry(
                    AddressEntry(
                      address = Address(
                        addressLine1 = None,
                        addressLine2 = None,
                        city = Some(customerCity1),
                        postalCode = None,
                        country = None,
                      ),
                      addressType = AddressType.Shipping,
                    )
                  ),
                  CustomerAddressEntry(
                    AddressEntry(
                      address = Address(
                        addressLine1 = None,
                        addressLine2 = None,
                        city = Some(customerCity2),
                        postalCode = None,
                        country = None,
                      ),
                      addressType = AddressType.Billing,
                    )
                  ),
                ),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List(
                s"Address left out: ${customerAddressLine1.value}",
                s"Address left out: ${customerAddressLine2.value}",
                s"Address left out: ${customerCity.value}",
                s"Address left out: ${customerPostalCode.value}",
                s"Address left out: ${customerCountry.value}",
              ),
            )
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "report inferred names and inferred country codes without marking candidates, keeping partial addresses and passing their AI notes through" in new TestContext {
        val customerFullName                = arbitrarySample[CustomerFullName]
        val customerBusinessName            = arbitrarySample[CustomerBusinessName]
        val customerBusinessContactFullName = arbitrarySample[CustomerFullName]
        val customerPostalCode              = arbitrarySample[PostalCode]
        val phoneNationalNumber             = arbitrarySample[PhoneNationalNumber]
        val phoneCountryCode                = arbitrarySample[PhoneCountryCode]
        val customerIndividualAIExtract     = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = List(
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber,
              phoneCountryCode = Some(phoneCountryCode),
              phoneCountryCodeInferred = true,
            )
          ),
          addresses = List(
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = None,
                postalCode = Some(customerPostalCode),
                country = None,
                extractionNotes = List("Only the postal code is readable"),
              ),
              addressType = arbitrarySample[AddressType],
            )
          ),
          fullNameInferred = true,
          extractionNotes = List("Name inferred from the email address", "Country code inferred from the address"),
        )
        val customerBusinessAIExtract = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = List(
            CustomerBusinessContactAIExtract(
              fullName = Some(customerBusinessContactFullName),
              role = None,
              email = None,
              phoneNumber = None,
              fullNameInferred = true,
              extractionNotes = List("Name inferred from the email address"),
            )
          ),
          businessNameInferred = true,
          extractionNotes = List("Business name inferred from the email domain"),
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          individualCandidates = List(customerIndividualAIExtract),
          businessCandidates = List(customerBusinessAIExtract),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullName),
              emails = Nil,
              phoneNumbers = List(
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber = CustomerPhoneNumberCandidate(
                    phoneNationalNumber = phoneNationalNumber,
                    phoneCountryCode = Some(phoneCountryCode),
                  ),
                  isDefault = true,
                )
              ),
              addresses = List(
                CustomerAddressEntry(
                  AddressEntry(
                    address = Address(
                      addressLine1 = None,
                      addressLine2 = None,
                      city = None,
                      postalCode = Some(customerPostalCode),
                      country = None,
                    ),
                    addressType = AddressType.ShippingAndBilling,
                  )
                )
              ),
              inferredFields = List(
                FieldInfo(path = "/fullName"),
                FieldInfo(path = "/phoneNumbers/0/phoneNumber/phoneCountryCode"),
              ),
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List(
                "Name inferred from the email address",
                "Country code inferred from the address",
                "Only the postal code is readable",
              ),
            )
          ),
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = Some(customerBusinessName),
              emails = Nil,
              taxID = None,
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = List(
                CustomerBusinessContactCandidate(
                  fullName = Some(customerBusinessContactFullName),
                  role = None,
                  email = None,
                  phoneNumber = None,
                )
              ),
              inferredFields = List(
                FieldInfo(path = "/businessName"),
                FieldInfo(path = "/customerBusinessContacts/0/fullName"),
              ),
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List(
                "Business name inferred from the email domain",
                "Name inferred from the email address",
              ),
            )
          ),
          infoNotes = List("Processed 2 of 2 entries"),
          extractionNotes = Nil,
        )
      }

      "make the first email and the first phone the defaults, even when that phone has no country code" in new TestContext {
        val customerFullName            = arbitrarySample[CustomerFullName]
        val customerEmail1              = arbitrarySample[CustomerEmail]
        val customerEmail2              = arbitrarySample[CustomerEmail]
        val customerEmail3              = arbitrarySample[CustomerEmail]
        val phoneNationalNumber1        = arbitrarySample[PhoneNationalNumber]
        val phoneNationalNumber2        = PhoneNationalNumber.assume(phoneNationalNumber1.value + "1")
        val phoneNationalNumber3        = PhoneNationalNumber.assume(phoneNationalNumber1.value + "2")
        val phoneCountryCode2           = arbitrarySample[PhoneCountryCode]
        val phoneCountryCode3           = arbitrarySample[PhoneCountryCode]
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = List(customerEmail1, customerEmail2, customerEmail3),
          phoneNumbers = List(
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber1,
              phoneCountryCode = None,
              phoneCountryCodeInferred = false,
            ),
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber2,
              phoneCountryCode = Some(phoneCountryCode2),
              phoneCountryCodeInferred = false,
            ),
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber3,
              phoneCountryCode = Some(phoneCountryCode3),
              phoneCountryCodeInferred = false,
            ),
          ),
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          individualCandidates = List(customerIndividualAIExtract),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        phoneNationalNumber2 shouldNot equal(phoneNationalNumber1)
        phoneNationalNumber3 shouldNot equal(phoneNationalNumber1)
        phoneNationalNumber3 shouldNot equal(phoneNationalNumber2)

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullName),
              emails = List(
                CustomerEmailEntry(email = customerEmail1, isDefault = true),
                CustomerEmailEntry(email = customerEmail2, isDefault = false),
                CustomerEmailEntry(email = customerEmail3, isDefault = false),
              ),
              phoneNumbers = List(
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber =
                    CustomerPhoneNumberCandidate(phoneNationalNumber = phoneNationalNumber1, phoneCountryCode = None),
                  isDefault = true,
                ),
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber = CustomerPhoneNumberCandidate(
                    phoneNationalNumber = phoneNationalNumber2,
                    phoneCountryCode = Some(phoneCountryCode2),
                  ),
                  isDefault = false,
                ),
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber = CustomerPhoneNumberCandidate(
                    phoneNationalNumber = phoneNationalNumber3,
                    phoneCountryCode = Some(phoneCountryCode3),
                  ),
                  isDefault = false,
                ),
              ),
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/phoneNumbers/0/phoneNumber/phoneCountryCode")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "order an individual's notes as its AI notes, then every address's AI notes, including a dropped empty and a left-out address's, then each left-out address note" in new TestContext {
        val customerFullName            = arbitrarySample[CustomerFullName]
        val customerCity1               = arbitrarySample[City]
        val customerCity3               = arbitrarySample[City]
        val customerCity4               = arbitrarySample[City]
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = List(
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(customerCity1),
                postalCode = None,
                country = None,
                extractionNotes = List("First address note"),
              ),
              addressType = AddressType.Billing,
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = None,
                postalCode = None,
                country = None,
                extractionNotes = List("Empty address note"),
              ),
              addressType = arbitrarySample[AddressType],
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(customerCity3),
                postalCode = None,
                country = None,
                extractionNotes = List("Third address note"),
              ),
              addressType = AddressType.Shipping,
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(customerCity4),
                postalCode = None,
                country = None,
                extractionNotes = List("Fourth address note"),
              ),
              addressType = arbitrarySample[AddressType],
            ),
          ),
          fullNameInferred = false,
          extractionNotes = List("Candidate note"),
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          individualCandidates = List(customerIndividualAIExtract),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        customerCity4 shouldNot equal(customerCity3)

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullName),
              emails = Nil,
              phoneNumbers = Nil,
              addresses =
                List(
                  CustomerAddressEntry(
                    AddressEntry(
                      address = Address(
                        addressLine1 = None,
                        addressLine2 = None,
                        city = Some(customerCity1),
                        postalCode = None,
                        country = None,
                      ),
                      addressType = AddressType.Billing,
                    )
                  ),
                  CustomerAddressEntry(
                    AddressEntry(
                      address = Address(
                        addressLine1 = None,
                        addressLine2 = None,
                        city = Some(customerCity3),
                        postalCode = None,
                        country = None,
                      ),
                      addressType = AddressType.Shipping,
                    )
                  ),
                ),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List(
                "Candidate note",
                "First address note",
                "Empty address note",
                "Third address note",
                "Fourth address note",
                s"Address left out: ${customerCity4.value}",
              ),
            )
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "order a business's notes as its AI notes, then every address's AI notes, including a left-out address's, then each left-out address note, then each contact's AI notes, unprefixed" in new TestContext {
        val customerBusinessName             = arbitrarySample[CustomerBusinessName]
        val customerCity1                    = arbitrarySample[City]
        val customerCity2                    = arbitrarySample[City]
        val customerCity3                    = arbitrarySample[City]
        val customerBusinessContactFullName1 = arbitrarySample[CustomerFullName]
        val customerBusinessContactFullName2 = arbitrarySample[CustomerFullName]
        val customerBusinessAIExtract        = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = List(
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(customerCity1),
                postalCode = None,
                country = None,
                extractionNotes = List("First address note"),
              ),
              addressType = AddressType.Billing,
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(customerCity2),
                postalCode = None,
                country = None,
                extractionNotes = List("Second address note"),
              ),
              addressType = AddressType.ShippingAndBilling,
            ),
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = Some(customerCity3),
                postalCode = None,
                country = None,
                extractionNotes = List("Third address note"),
              ),
              addressType = arbitrarySample[AddressType],
            ),
          ),
          customerBusinessContacts = List(
            CustomerBusinessContactAIExtract(
              fullName = Some(customerBusinessContactFullName1),
              role = None,
              email = None,
              phoneNumber = None,
              fullNameInferred = false,
              extractionNotes = List("First contact note"),
            ),
            CustomerBusinessContactAIExtract(
              fullName = Some(customerBusinessContactFullName2),
              role = None,
              email = None,
              phoneNumber = None,
              fullNameInferred = false,
              extractionNotes = List("Second contact note"),
            ),
          ),
          businessNameInferred = false,
          extractionNotes = List("Business note"),
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          individualCandidates = Nil,
          businessCandidates = List(customerBusinessAIExtract),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        customerCity2 shouldNot equal(customerCity1)
        customerCity3 shouldNot equal(customerCity2)

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = Some(customerBusinessName),
              emails = Nil,
              taxID = None,
              phoneNumbers = Nil,
              addresses =
                List(
                  CustomerAddressEntry(
                    AddressEntry(
                      address = Address(
                        addressLine1 = None,
                        addressLine2 = None,
                        city = Some(customerCity1),
                        postalCode = None,
                        country = None,
                      ),
                      addressType = AddressType.Shipping,
                    )
                  ),
                  CustomerAddressEntry(
                    AddressEntry(
                      address = Address(
                        addressLine1 = None,
                        addressLine2 = None,
                        city = Some(customerCity2),
                        postalCode = None,
                        country = None,
                      ),
                      addressType = AddressType.Billing,
                    )
                  ),
                ),
              customerBusinessContacts = List(
                CustomerBusinessContactCandidate(
                  fullName = Some(customerBusinessContactFullName1),
                  role = None,
                  email = None,
                  phoneNumber = None,
                ),
                CustomerBusinessContactCandidate(
                  fullName = Some(customerBusinessContactFullName2),
                  role = None,
                  email = None,
                  phoneNumber = None,
                ),
              ),
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List(
                "Business note",
                "First address note",
                "Second address note",
                "Third address note",
                s"Address left out: ${customerCity3.value}",
                "First contact note",
                "Second contact note",
              ),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "report a later phone's missing country code at that phone's position, keeping phone order and the first phone as the default" in new TestContext {
        val customerFullName            = arbitrarySample[CustomerFullName]
        val customerBusinessName        = arbitrarySample[CustomerBusinessName]
        val phoneNationalNumber1        = arbitrarySample[PhoneNationalNumber]
        val phoneNationalNumber2        = PhoneNationalNumber.assume(phoneNationalNumber1.value + "1")
        val phoneNationalNumber3        = PhoneNationalNumber.assume(phoneNationalNumber1.value + "2")
        val phoneCountryCode1           = arbitrarySample[PhoneCountryCode]
        val phoneCountryCode2           = arbitrarySample[PhoneCountryCode]
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = List(
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber1,
              phoneCountryCode = Some(phoneCountryCode1),
              phoneCountryCodeInferred = false,
            ),
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber2,
              phoneCountryCode = Some(phoneCountryCode2),
              phoneCountryCodeInferred = false,
            ),
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber3,
              phoneCountryCode = None,
              phoneCountryCodeInferred = false,
            ),
          ),
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessAIExtract = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = List(
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber1,
              phoneCountryCode = Some(phoneCountryCode1),
              phoneCountryCodeInferred = false,
            ),
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber2,
              phoneCountryCode = None,
              phoneCountryCodeInferred = false,
            ),
          ),
          addresses = Nil,
          customerBusinessContacts = Nil,
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          individualCandidates = List(customerIndividualAIExtract),
          businessCandidates = List(customerBusinessAIExtract),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        phoneNationalNumber2 shouldNot equal(phoneNationalNumber1)
        phoneNationalNumber3 shouldNot equal(phoneNationalNumber1)
        phoneNationalNumber3 shouldNot equal(phoneNationalNumber2)

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullName),
              emails = Nil,
              phoneNumbers = List(
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber = CustomerPhoneNumberCandidate(
                    phoneNationalNumber = phoneNationalNumber1,
                    phoneCountryCode = Some(phoneCountryCode1),
                  ),
                  isDefault = true,
                ),
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber = CustomerPhoneNumberCandidate(
                    phoneNationalNumber = phoneNationalNumber2,
                    phoneCountryCode = Some(phoneCountryCode2),
                  ),
                  isDefault = false,
                ),
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber =
                    CustomerPhoneNumberCandidate(phoneNationalNumber = phoneNationalNumber3, phoneCountryCode = None),
                  isDefault = false,
                ),
              ),
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/phoneNumbers/2/phoneNumber/phoneCountryCode")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = Some(customerBusinessName),
              emails = Nil,
              taxID = None,
              phoneNumbers = List(
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber = CustomerPhoneNumberCandidate(
                    phoneNationalNumber = phoneNationalNumber1,
                    phoneCountryCode = Some(phoneCountryCode1),
                  ),
                  isDefault = true,
                ),
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber =
                    CustomerPhoneNumberCandidate(phoneNationalNumber = phoneNationalNumber2, phoneCountryCode = None),
                  isDefault = false,
                ),
              ),
              addresses = Nil,
              customerBusinessContacts = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/phoneNumbers/1/phoneNumber/phoneCountryCode")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          infoNotes = List("Processed 2 of 2 entries"),
          extractionNotes = Nil,
        )
      }

      "report a contact's inferred country code at the contact's position without marking the business" in new TestContext {
        val customerBusinessName             = arbitrarySample[CustomerBusinessName]
        val customerBusinessContactFullName1 = arbitrarySample[CustomerFullName]
        val customerBusinessContactFullName2 = arbitrarySample[CustomerFullName]
        val phoneNationalNumber              = arbitrarySample[PhoneNationalNumber]
        val phoneCountryCode                 = arbitrarySample[PhoneCountryCode]
        val customerBusinessAIExtract        = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = List(
            CustomerBusinessContactAIExtract(
              fullName = Some(customerBusinessContactFullName1),
              role = None,
              email = None,
              phoneNumber = None,
              fullNameInferred = false,
              extractionNotes = Nil,
            ),
            CustomerBusinessContactAIExtract(
              fullName = Some(customerBusinessContactFullName2),
              role = None,
              email = None,
              phoneNumber = Some(
                CustomerPhoneNumberAIExtract(
                  phoneNationalNumber = phoneNationalNumber,
                  phoneCountryCode = Some(phoneCountryCode),
                  phoneCountryCodeInferred = true,
                )
              ),
              fullNameInferred = false,
              extractionNotes = List("Country code inferred from the address"),
            ),
          ),
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          individualCandidates = Nil,
          businessCandidates = List(customerBusinessAIExtract),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = Some(customerBusinessName),
              emails = Nil,
              taxID = None,
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts =
                List(
                  CustomerBusinessContactCandidate(
                    fullName = Some(customerBusinessContactFullName1),
                    role = None,
                    email = None,
                    phoneNumber = None,
                  ),
                  CustomerBusinessContactCandidate(
                    fullName = Some(customerBusinessContactFullName2),
                    role = None,
                    email = None,
                    phoneNumber = Some(
                      CustomerPhoneNumberCandidate(
                        phoneNationalNumber = phoneNationalNumber,
                        phoneCountryCode = Some(phoneCountryCode),
                      )
                    ),
                  ),
                ),
              inferredFields = List(FieldInfo(path = "/customerBusinessContacts/1/phoneNumber/phoneCountryCode")),
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List("Country code inferred from the address"),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "report a business's own inferred country code at the phone's position without marking the business" in new TestContext {
        val customerBusinessName      = arbitrarySample[CustomerBusinessName]
        val phoneNationalNumber1      = arbitrarySample[PhoneNationalNumber]
        val phoneNationalNumber2      = PhoneNationalNumber.assume(phoneNationalNumber1.value + "1")
        val phoneCountryCode1         = arbitrarySample[PhoneCountryCode]
        val phoneCountryCode2         = arbitrarySample[PhoneCountryCode]
        val customerBusinessAIExtract = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = List(
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber1,
              phoneCountryCode = Some(phoneCountryCode1),
              phoneCountryCodeInferred = false,
            ),
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber2,
              phoneCountryCode = Some(phoneCountryCode2),
              phoneCountryCodeInferred = true,
            ),
          ),
          addresses = Nil,
          customerBusinessContacts = Nil,
          businessNameInferred = false,
          extractionNotes = List("Country code inferred from the address"),
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          individualCandidates = Nil,
          businessCandidates = List(customerBusinessAIExtract),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        phoneNationalNumber2 shouldNot equal(phoneNationalNumber1)

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = Some(customerBusinessName),
              emails = Nil,
              taxID = None,
              phoneNumbers =
                List(
                  CustomerPhoneNumberEntryCandidate(
                    phoneNumber = CustomerPhoneNumberCandidate(
                      phoneNationalNumber = phoneNationalNumber1,
                      phoneCountryCode = Some(phoneCountryCode1),
                    ),
                    isDefault = true,
                  ),
                  CustomerPhoneNumberEntryCandidate(
                    phoneNumber = CustomerPhoneNumberCandidate(
                      phoneNationalNumber = phoneNationalNumber2,
                      phoneCountryCode = Some(phoneCountryCode2),
                    ),
                    isDefault = false,
                  ),
                ),
              addresses = Nil,
              customerBusinessContacts = Nil,
              inferredFields = List(FieldInfo(path = "/phoneNumbers/1/phoneNumber/phoneCountryCode")),
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = List("Country code inferred from the address"),
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "report each contact's missing and inferred fields at the contact's position in AI order, without de-duplicating them" in new TestContext {
        val customerBusinessName                     = arbitrarySample[CustomerBusinessName]
        val customerBusinessContactFullName1         = arbitrarySample[CustomerFullName]
        val customerBusinessContactFullName2         = arbitrarySample[CustomerFullName]
        val customerBusinessContactFullName3         = arbitrarySample[CustomerFullName]
        val customerBusinessContactRole              = arbitrarySample[CustomerBusinessContactRole]
        val phoneNationalNumber1                     = arbitrarySample[PhoneNationalNumber]
        val phoneNationalNumber2                     = arbitrarySample[PhoneNationalNumber]
        val phoneNationalNumber3                     = arbitrarySample[PhoneNationalNumber]
        val phoneCountryCode2                        = arbitrarySample[PhoneCountryCode]
        val customerBusinessContactAIExtractNameless = CustomerBusinessContactAIExtract(
          fullName = None,
          role = Some(customerBusinessContactRole),
          email = None,
          phoneNumber = None,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessContactAIExtractPhoneNumberWithoutCountryCode = CustomerBusinessContactAIExtract(
          fullName = Some(customerBusinessContactFullName1),
          role = None,
          email = None,
          phoneNumber = Some(
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber1,
              phoneCountryCode = None,
              phoneCountryCodeInferred = false,
            )
          ),
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessContactAIExtractPhoneCountryCodeInferred = CustomerBusinessContactAIExtract(
          fullName = Some(customerBusinessContactFullName2),
          role = None,
          email = None,
          phoneNumber = Some(
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber2,
              phoneCountryCode = Some(phoneCountryCode2),
              phoneCountryCodeInferred = true,
            )
          ),
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessContactAIExtractFullNameInferred = CustomerBusinessContactAIExtract(
          fullName = Some(customerBusinessContactFullName3),
          role = None,
          email = None,
          phoneNumber = None,
          fullNameInferred = true,
          extractionNotes = Nil,
        )
        val customerBusinessContactAIExtractNamelessPhoneNumberWithoutCountryCode = CustomerBusinessContactAIExtract(
          fullName = None,
          role = None,
          email = None,
          phoneNumber = Some(
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber3,
              phoneCountryCode = None,
              phoneCountryCodeInferred = false,
            )
          ),
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessAIExtract = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = List(
            customerBusinessContactAIExtractNameless,
            customerBusinessContactAIExtractPhoneNumberWithoutCountryCode,
            customerBusinessContactAIExtractPhoneCountryCodeInferred,
            customerBusinessContactAIExtractFullNameInferred,
            customerBusinessContactAIExtractNamelessPhoneNumberWithoutCountryCode,
          ),
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          individualCandidates = Nil,
          businessCandidates = List(customerBusinessAIExtract),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = Some(customerBusinessName),
              emails = Nil,
              taxID = None,
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = List(
                CustomerBusinessContactCandidate(
                  fullName = None,
                  role = Some(customerBusinessContactRole),
                  email = None,
                  phoneNumber = None,
                ),
                CustomerBusinessContactCandidate(
                  fullName = Some(customerBusinessContactFullName1),
                  role = None,
                  email = None,
                  phoneNumber = Some(
                    CustomerPhoneNumberCandidate(phoneNationalNumber = phoneNationalNumber1, phoneCountryCode = None)
                  ),
                ),
                CustomerBusinessContactCandidate(
                  fullName = Some(customerBusinessContactFullName2),
                  role = None,
                  email = None,
                  phoneNumber = Some(
                    CustomerPhoneNumberCandidate(
                      phoneNationalNumber = phoneNationalNumber2,
                      phoneCountryCode = Some(phoneCountryCode2),
                    )
                  ),
                ),
                CustomerBusinessContactCandidate(
                  fullName = Some(customerBusinessContactFullName3),
                  role = None,
                  email = None,
                  phoneNumber = None,
                ),
                CustomerBusinessContactCandidate(
                  fullName = None,
                  role = None,
                  email = None,
                  phoneNumber = Some(
                    CustomerPhoneNumberCandidate(phoneNationalNumber = phoneNationalNumber3, phoneCountryCode = None)
                  ),
                ),
              ),
              inferredFields = List(
                FieldInfo(path = "/customerBusinessContacts/2/phoneNumber/phoneCountryCode"),
                FieldInfo(path = "/customerBusinessContacts/3/fullName"),
              ),
              missingFields = List(
                FieldInfo(path = "/customerBusinessContacts/0/fullName"),
                FieldInfo(path = "/customerBusinessContacts/1/phoneNumber/phoneCountryCode"),
                FieldInfo(path = "/customerBusinessContacts/4/fullName"),
                FieldInfo(path = "/customerBusinessContacts/4/phoneNumber/phoneCountryCode"),
              ),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = Nil,
        )
      }

      "report names that are duplicates across spreadsheet groups alongside missing names" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.csv")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.csv")),
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val csvRecords: List[CSVRecord] =
          CSVParser
            .parse("Full Name\nJane Doe\nJohn Roe\nJane Doe\n\n\nAcme", CSVFormat.DEFAULT)
            .getRecords
            .asScala
            .toList
        val customerFullName       = arbitrarySample[CustomerFullName]
        val customerFullNameUnique = CustomerFullName.assume(
          if (customerFullName.value.length < CustomerFullNameLengthMax) customerFullName.value + "b"
          else customerFullName.value.dropRight(1)
        )
        val customerBusinessName        = arbitrarySample[CustomerBusinessName]
        val customerEmail               = arbitrarySample[CustomerEmail]
        val customerTaxID               = arbitrarySample[CustomerTaxID]
        val customerIndividualAIExtract = CustomerIndividualAIExtract(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerIndividualAIExtractUnique =
          customerIndividualAIExtract.copy(fullName = Some(customerFullNameUnique))
        val customerIndividualAIExtractNameless =
          customerIndividualAIExtract.copy(fullName = None, emails = List(customerEmail))
        val customerBusinessAIExtractNameless = CustomerBusinessAIExtract(
          businessName = None,
          emails = Nil,
          taxID = Some(customerTaxID),
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = Nil,
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessAIExtractUnique =
          customerBusinessAIExtractNameless.copy(businessName = Some(customerBusinessName), taxID = None)
        val customerBookFileAIExtractBatch1 = CustomerBookFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          individualCandidates = List(customerIndividualAIExtract, customerIndividualAIExtractUnique),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtractBatch2 = CustomerBookFileAIExtract(
          entriesIdentified = 4L,
          entriesProcessed = 4L,
          individualCandidates = List(customerIndividualAIExtract, customerIndividualAIExtractNameless),
          businessCandidates = List(customerBusinessAIExtractNameless, customerBusinessAIExtractUnique),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )
        val customerIndividualCandidateDuplicate = CustomerIndividualCandidate(
          fullName = Some(customerFullName),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = Nil,
          inferredFields = Nil,
          missingFields = Nil,
          duplicateFields = List(FieldInfo(path = "/fullName")),
          needsAttention = true,
          extractionNotes = Nil,
        )

        customerFullNameUnique shouldNot equal(customerFullName)

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          spreadsheetToolMock.convertToCsv
            .expects(fileScannerScanOutput.fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CustomerBookFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCustomersFromFileInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .returns(ZIO.succeed(NonEmptyChunk(customerBookFileAIExtractBatch1, customerBookFileAIExtractBatch2)))
            .once(),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            customerIndividualCandidateDuplicate,
            customerIndividualCandidateDuplicate.copy(
              fullName = Some(customerFullNameUnique),
              duplicateFields = Nil,
              needsAttention = false,
            ),
            customerIndividualCandidateDuplicate,
            CustomerIndividualCandidate(
              fullName = None,
              emails = List(CustomerEmailEntry(email = customerEmail, isDefault = true)),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/fullName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            ),
          ),
          businessCandidates = List(
            CustomerBusinessCandidate(
              businessName = None,
              emails = Nil,
              taxID = Some(customerTaxID),
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo(path = "/businessName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            ),
            CustomerBusinessCandidate(
              businessName = Some(customerBusinessName),
              emails = Nil,
              taxID = None,
              phoneNumbers = Nil,
              addresses = Nil,
              customerBusinessContacts = Nil,
              inferredFields = Nil,
              missingFields = Nil,
              duplicateFields = Nil,
              needsAttention = false,
              extractionNotes = Nil,
            ),
          ),
          infoNotes = List("Processed 6 of 6 entries"),
          extractionNotes = Nil,
        )
      }

      "mark a candidate as needing attention for each single missing or duplicate field" in new TestContext {
        val customerEmail                       = arbitrarySample[CustomerEmail]
        val customerFullNameDuplicate           = arbitrarySample[CustomerFullName]
        val customerFullNamePhoneNumber         = arbitrarySample[CustomerFullName]
        val customerBusinessName1               = arbitrarySample[CustomerBusinessName]
        val customerBusinessName2               = arbitrarySample[CustomerBusinessName]
        val customerBusinessContactFullName     = arbitrarySample[CustomerFullName]
        val customerBusinessContactRole         = arbitrarySample[CustomerBusinessContactRole]
        val phoneNationalNumber1                = arbitrarySample[PhoneNationalNumber]
        val phoneNationalNumber2                = arbitrarySample[PhoneNationalNumber]
        val customerIndividualAIExtractNameless = CustomerIndividualAIExtract(
          fullName = None,
          emails = List(customerEmail),
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerIndividualAIExtractDuplicate = customerIndividualAIExtractNameless.copy(
          fullName = Some(customerFullNameDuplicate),
          emails = Nil,
        )
        val customerIndividualAIExtractPhoneNumberWithoutCountryCode = customerIndividualAIExtractNameless.copy(
          fullName = Some(customerFullNamePhoneNumber),
          emails = Nil,
          phoneNumbers = List(
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber1,
              phoneCountryCode = None,
              phoneCountryCodeInferred = false,
            )
          ),
        )
        val customerBusinessAIExtractNamelessContact = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessName1),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = List(
            CustomerBusinessContactAIExtract(
              fullName = None,
              role = Some(customerBusinessContactRole),
              email = None,
              phoneNumber = None,
              fullNameInferred = false,
              extractionNotes = Nil,
            )
          ),
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessAIExtractContactPhoneNumberWithoutCountryCode =
          customerBusinessAIExtractNamelessContact.copy(
            businessName = Some(customerBusinessName2),
            customerBusinessContacts = List(
              CustomerBusinessContactAIExtract(
                fullName = Some(customerBusinessContactFullName),
                role = None,
                email = None,
                phoneNumber = Some(
                  CustomerPhoneNumberAIExtract(
                    phoneNationalNumber = phoneNationalNumber2,
                    phoneCountryCode = None,
                    phoneCountryCodeInferred = false,
                  )
                ),
                fullNameInferred = false,
                extractionNotes = Nil,
              )
            ),
          )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 6L,
          entriesProcessed = 6L,
          individualCandidates = List(
            customerIndividualAIExtractNameless,
            customerIndividualAIExtractDuplicate,
            customerIndividualAIExtractDuplicate,
            customerIndividualAIExtractPhoneNumberWithoutCountryCode,
          ),
          businessCandidates = List(
            customerBusinessAIExtractNamelessContact,
            customerBusinessAIExtractContactPhoneNumberWithoutCountryCode,
          ),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )
        val customerIndividualCandidateNameless = CustomerIndividualCandidate(
          fullName = None,
          emails = List(CustomerEmailEntry(email = customerEmail, isDefault = true)),
          phoneNumbers = Nil,
          addresses = Nil,
          inferredFields = Nil,
          missingFields = List(FieldInfo(path = "/fullName")),
          duplicateFields = Nil,
          needsAttention = true,
          extractionNotes = Nil,
        )
        val customerBusinessCandidateNamelessContact = CustomerBusinessCandidate(
          businessName = Some(customerBusinessName1),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = List(
            CustomerBusinessContactCandidate(
              fullName = None,
              role = Some(customerBusinessContactRole),
              email = None,
              phoneNumber = None,
            )
          ),
          inferredFields = Nil,
          missingFields = List(FieldInfo(path = "/customerBusinessContacts/0/fullName")),
          duplicateFields = Nil,
          needsAttention = true,
          extractionNotes = Nil,
        )

        val customerIndividualCandidateDuplicate = customerIndividualCandidateNameless.copy(
          fullName = Some(customerFullNameDuplicate),
          emails = Nil,
          missingFields = Nil,
          duplicateFields = List(FieldInfo(path = "/fullName")),
        )

        customerFullNamePhoneNumber shouldNot equal(customerFullNameDuplicate)
        customerBusinessName2 shouldNot equal(customerBusinessName1)

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            customerIndividualCandidateNameless,
            customerIndividualCandidateDuplicate,
            customerIndividualCandidateDuplicate,
            customerIndividualCandidateNameless.copy(
              fullName = Some(customerFullNamePhoneNumber),
              emails = Nil,
              phoneNumbers = List(
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber =
                    CustomerPhoneNumberCandidate(phoneNationalNumber = phoneNationalNumber1, phoneCountryCode = None),
                  isDefault = true,
                )
              ),
              missingFields = List(FieldInfo(path = "/phoneNumbers/0/phoneNumber/phoneCountryCode")),
            ),
          ),
          businessCandidates = List(
            customerBusinessCandidateNamelessContact,
            customerBusinessCandidateNamelessContact.copy(
              businessName = Some(customerBusinessName2),
              customerBusinessContacts = List(
                CustomerBusinessContactCandidate(
                  fullName = Some(customerBusinessContactFullName),
                  role = None,
                  email = None,
                  phoneNumber = Some(
                    CustomerPhoneNumberCandidate(phoneNationalNumber = phoneNationalNumber2, phoneCountryCode = None)
                  ),
                )
              ),
              missingFields = List(FieldInfo(path = "/customerBusinessContacts/0/phoneNumber/phoneCountryCode")),
            ),
          ),
          infoNotes = List("Processed 6 of 6 entries"),
          extractionNotes = Nil,
        )
      }

      "not mark a candidate whose only findings are inferred fields, AI notes or a dropped all-empty address" in new TestContext {
        val customerFullName         = arbitrarySample[CustomerFullName]
        val customerFullNameInferred =
          CustomerFullName.assume(customerFullName.value.take(CustomerFullNameLengthMax - 1) + "a")
        val customerFullNamePhoneNumber =
          CustomerFullName.assume(customerFullName.value.take(CustomerFullNameLengthMax - 1) + "b")
        val customerFullNameExtractionNotes =
          CustomerFullName.assume(customerFullName.value.take(CustomerFullNameLengthMax - 1) + "c")
        val customerFullNameAddress =
          CustomerFullName.assume(customerFullName.value.take(CustomerFullNameLengthMax - 1) + "d")
        val phoneNationalNumber                         = arbitrarySample[PhoneNationalNumber]
        val phoneCountryCode                            = arbitrarySample[PhoneCountryCode]
        val customerIndividualAIExtractFullNameInferred = CustomerIndividualAIExtract(
          fullName = Some(customerFullNameInferred),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = true,
          extractionNotes = Nil,
        )
        val customerIndividualAIExtractPhoneCountryCodeInferred = customerIndividualAIExtractFullNameInferred.copy(
          fullName = Some(customerFullNamePhoneNumber),
          phoneNumbers = List(
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber = phoneNationalNumber,
              phoneCountryCode = Some(phoneCountryCode),
              phoneCountryCodeInferred = true,
            )
          ),
          fullNameInferred = false,
        )
        val customerIndividualAIExtractExtractionNotes = customerIndividualAIExtractFullNameInferred.copy(
          fullName = Some(customerFullNameExtractionNotes),
          fullNameInferred = false,
          extractionNotes = List("The email address is unreadable"),
        )
        val customerIndividualAIExtractEmptyAddress = customerIndividualAIExtractFullNameInferred.copy(
          fullName = Some(customerFullNameAddress),
          addresses = List(
            CustomerAddressEntryAIExtract(
              address = CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = None,
                postalCode = None,
                country = None,
                extractionNotes = Nil,
              ),
              addressType = arbitrarySample[AddressType],
            )
          ),
          fullNameInferred = false,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 4L,
          entriesProcessed = 4L,
          individualCandidates = List(
            customerIndividualAIExtractFullNameInferred,
            customerIndividualAIExtractPhoneCountryCodeInferred,
            customerIndividualAIExtractExtractionNotes,
            customerIndividualAIExtractEmptyAddress,
          ),
          businessCandidates = Nil,
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )
        val customerIndividualCandidateFullNameInferred = CustomerIndividualCandidate(
          fullName = Some(customerFullNameInferred),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = Nil,
          inferredFields = List(FieldInfo(path = "/fullName")),
          missingFields = Nil,
          duplicateFields = Nil,
          needsAttention = false,
          extractionNotes = Nil,
        )

        customerFullNamePhoneNumber shouldNot equal(customerFullNameInferred)
        customerFullNameExtractionNotes shouldNot equal(customerFullNameInferred)
        customerFullNameExtractionNotes shouldNot equal(customerFullNamePhoneNumber)
        customerFullNameAddress shouldNot equal(customerFullNameInferred)
        customerFullNameAddress shouldNot equal(customerFullNamePhoneNumber)
        customerFullNameAddress shouldNot equal(customerFullNameExtractionNotes)

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            customerIndividualCandidateFullNameInferred,
            customerIndividualCandidateFullNameInferred.copy(
              fullName = Some(customerFullNamePhoneNumber),
              phoneNumbers = List(
                CustomerPhoneNumberEntryCandidate(
                  phoneNumber = CustomerPhoneNumberCandidate(
                    phoneNationalNumber = phoneNationalNumber,
                    phoneCountryCode = Some(phoneCountryCode),
                  ),
                  isDefault = true,
                )
              ),
              inferredFields = List(FieldInfo(path = "/phoneNumbers/0/phoneNumber/phoneCountryCode")),
            ),
            customerIndividualCandidateFullNameInferred.copy(
              fullName = Some(customerFullNameExtractionNotes),
              inferredFields = Nil,
              extractionNotes = List("The email address is unreadable"),
            ),
            customerIndividualCandidateFullNameInferred.copy(
              fullName = Some(customerFullNameAddress),
              inferredFields = Nil,
            ),
          ),
          businessCandidates = Nil,
          infoNotes = List("Processed 4 of 4 entries"),
          extractionNotes = Nil,
        )
      }

      "report only field paths that resolve against the candidate JSON, to an existing value or, for a missing value, to its existing parent object" in new TestContext {
        val customerFullNameDuplicate            = arbitrarySample[CustomerFullName]
        val customerEmail                        = arbitrarySample[CustomerEmail]
        val customerTaxID                        = arbitrarySample[CustomerTaxID]
        val customerBusinessNameDuplicate        = arbitrarySample[CustomerBusinessName]
        val customerBusinessContactRole          = arbitrarySample[CustomerBusinessContactRole]
        val customerBusinessContactFullName1     = arbitrarySample[CustomerFullName]
        val customerBusinessContactFullName2     = arbitrarySample[CustomerFullName]
        val phoneNationalNumber                  = arbitrarySample[PhoneNationalNumber]
        val phoneCountryCode                     = arbitrarySample[PhoneCountryCode]
        val customerIndividualAIExtractDuplicate = CustomerIndividualAIExtract(
          fullName = Some(customerFullNameDuplicate),
          emails = Nil,
          phoneNumbers = Nil,
          addresses = Nil,
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessAIExtractDuplicate = CustomerBusinessAIExtract(
          businessName = Some(customerBusinessNameDuplicate),
          emails = Nil,
          taxID = None,
          phoneNumbers = Nil,
          addresses = Nil,
          customerBusinessContacts = Nil,
          businessNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(
          entriesIdentified = 6L,
          entriesProcessed = 6L,
          individualCandidates = List(
            customerIndividualAIExtractDuplicate.copy(
              phoneNumbers = List(
                CustomerPhoneNumberAIExtract(
                  phoneNationalNumber = phoneNationalNumber,
                  phoneCountryCode = Some(phoneCountryCode),
                  phoneCountryCodeInferred = true,
                ),
                CustomerPhoneNumberAIExtract(
                  phoneNationalNumber = phoneNationalNumber,
                  phoneCountryCode = None,
                  phoneCountryCodeInferred = false,
                ),
              ),
              fullNameInferred = true,
            ),
            customerIndividualAIExtractDuplicate,
            customerIndividualAIExtractDuplicate.copy(fullName = None, emails = List(customerEmail)),
          ),
          businessCandidates = List(
            customerBusinessAIExtractDuplicate.copy(
              businessName = None,
              taxID = Some(customerTaxID),
              customerBusinessContacts = List(
                CustomerBusinessContactAIExtract(
                  fullName = None,
                  role = Some(customerBusinessContactRole),
                  email = None,
                  phoneNumber = None,
                  fullNameInferred = false,
                  extractionNotes = Nil,
                ),
                CustomerBusinessContactAIExtract(
                  fullName = Some(customerBusinessContactFullName1),
                  role = None,
                  email = None,
                  phoneNumber = Some(
                    CustomerPhoneNumberAIExtract(
                      phoneNationalNumber = phoneNationalNumber,
                      phoneCountryCode = None,
                      phoneCountryCodeInferred = false,
                    )
                  ),
                  fullNameInferred = true,
                  extractionNotes = Nil,
                ),
                CustomerBusinessContactAIExtract(
                  fullName = Some(customerBusinessContactFullName2),
                  role = None,
                  email = None,
                  phoneNumber = Some(
                    CustomerPhoneNumberAIExtract(
                      phoneNationalNumber = phoneNationalNumber,
                      phoneCountryCode = Some(phoneCountryCode),
                      phoneCountryCodeInferred = true,
                    )
                  ),
                  fullNameInferred = false,
                  extractionNotes = Nil,
                ),
              ),
            ),
            customerBusinessAIExtractDuplicate.copy(businessNameInferred = true),
            customerBusinessAIExtractDuplicate,
          ),
          emptyEntryRowIndexes = Nil,
          unidentifiedEntryRowIndexes = Nil,
          extractionNotes = Nil,
        )

        def jsonPointerSegments(path: String): List[String] =
          path.split("/", -1).toList.drop(1).map(segment => segment.replace("~1", "/").replace("~0", "~"))

        def resolveJsonPointer(json: Json, segments: List[String]): Option[Json] =
          segments.foldLeft(Option(json))((jsonOpt, segment) =>
            jsonOpt.flatMap(jsonParent =>
              jsonParent.asObject
                .flatMap(jsonObject => jsonObject(segment))
                .orElse(jsonParent.asArray.flatMap(jsons => segment.toIntOption.flatMap(jsons.lift)))
            )
          )

        def schemaHasJsonPointer(schema: Schema[?], segments: List[String]): Boolean =
          (schema.schemaType, segments) match {
            case (_, Nil)                               => true
            case (SchemaType.SOption(schemaElement), _) => schemaHasJsonPointer(schemaElement, segments)
            case (SchemaType.SArray(schemaElement), segment :: segmentsRest) =>
              segment.toIntOption.isDefined && schemaHasJsonPointer(schemaElement, segmentsRest)
            case (SchemaType.SProduct(schemaFields), segment :: segmentsRest) =>
              schemaFields
                .find(schemaField => schemaField.name.encodedName == segment)
                .exists(schemaField => schemaHasJsonPointer(schemaField.schema, segmentsRest))
            case _ => false
          }

        def candidateJsons(responseJson: Json, candidatesProperty: String): List[Json] =
          responseJson.asObject.flatMap(jsonObject => jsonObject(candidatesProperty)).flatMap(_.asArray).toList.flatten

        def fieldInfoPaths(candidateJson: Json, fieldInfosProperty: String): List[String] =
          candidateJson.asObject
            .flatMap(jsonObject => jsonObject(fieldInfosProperty))
            .flatMap(_.asArray)
            .toList
            .flatten
            .flatMap(fieldInfoJson => fieldInfoJson.asObject.flatMap(jsonObject => jsonObject("path")))
            .flatMap(_.asString)

        val customerBookFileAiExtractPostResponseJson =
          parser
            .parse(writeToString(aiExtractFromImage(customerBookFileAIExtract)))
            .getOrElse(fail("The response did not serialize to JSON"))
        val candidateJsonsWithSchema: List[(candidateJson: Json, candidateSchema: Schema[?])] =
          candidateJsons(customerBookFileAiExtractPostResponseJson, "individualCandidates").map(candidateJson =>
            (candidateJson = candidateJson, candidateSchema = customerIndividualCandidateSchema)
          ) ++ candidateJsons(customerBookFileAiExtractPostResponseJson, "businessCandidates").map(candidateJson =>
            (candidateJson = candidateJson, candidateSchema = customerBusinessCandidateSchema)
          )
        val fieldInfoPathsReported = candidateJsonsWithSchema.flatMap(candidateJsonWithSchema =>
          List("inferredFields", "missingFields", "duplicateFields").flatMap(fieldInfosProperty =>
            fieldInfoPaths(candidateJsonWithSchema.candidateJson, fieldInfosProperty).map(path =>
              (
                candidateJson = candidateJsonWithSchema.candidateJson,
                candidateSchema = candidateJsonWithSchema.candidateSchema,
                fieldInfosProperty = fieldInfosProperty,
                path = path,
              )
            )
          )
        )

        fieldInfoPathsReported.map(fieldInfoPathReported =>
          (fieldInfosProperty = fieldInfoPathReported.fieldInfosProperty, path = fieldInfoPathReported.path)
        ) shouldBe List(
          (fieldInfosProperty = "inferredFields", path = "/fullName"),
          (fieldInfosProperty = "inferredFields", path = "/phoneNumbers/0/phoneNumber/phoneCountryCode"),
          (fieldInfosProperty = "missingFields", path = "/phoneNumbers/1/phoneNumber/phoneCountryCode"),
          (fieldInfosProperty = "duplicateFields", path = "/fullName"),
          (fieldInfosProperty = "duplicateFields", path = "/fullName"),
          (fieldInfosProperty = "missingFields", path = "/fullName"),
          (fieldInfosProperty = "inferredFields", path = "/customerBusinessContacts/1/fullName"),
          (fieldInfosProperty = "inferredFields", path = "/customerBusinessContacts/2/phoneNumber/phoneCountryCode"),
          (fieldInfosProperty = "missingFields", path = "/businessName"),
          (fieldInfosProperty = "missingFields", path = "/customerBusinessContacts/0/fullName"),
          (fieldInfosProperty = "missingFields", path = "/customerBusinessContacts/1/phoneNumber/phoneCountryCode"),
          (fieldInfosProperty = "inferredFields", path = "/businessName"),
          (fieldInfosProperty = "duplicateFields", path = "/businessName"),
          (fieldInfosProperty = "duplicateFields", path = "/businessName"),
        )
        fieldInfoPathsReported.foreach(fieldInfoPathReported =>
          withClue(s"${fieldInfoPathReported.fieldInfosProperty} ${fieldInfoPathReported.path}: ") {
            val segments = jsonPointerSegments(fieldInfoPathReported.path)

            fieldInfoPathReported.path should startWith("/")
            schemaHasJsonPointer(fieldInfoPathReported.candidateSchema, segments) shouldBe true
            fieldInfoPathReported.fieldInfosProperty match {
              case "missingFields" =>
                resolveJsonPointer(fieldInfoPathReported.candidateJson, segments.init).flatMap(
                  _.asObject
                ) shouldBe defined
                resolveJsonPointer(fieldInfoPathReported.candidateJson, segments).filterNot(_.isNull) shouldBe None
              case _ =>
                resolveJsonPointer(fieldInfoPathReported.candidateJson, segments).filterNot(_.isNull) shouldBe defined
            }
          }
        )
      }

      "fail with an UnexpectedError when extraction file scanning fails" in new TestContext {
        val organizationID                    = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName   = CustomerBookFileExtractFileName("customers.jpeg")
        val customerBookFileExtractByteStream = ZStream.empty
        val serviceErrorScan = ServiceError.InternalServerError.UnexpectedError(error = "Failed to scan the upload")

        fileScannerMock.scan
          .expects(
            customerBookFileExtractByteStream,
            customerBookFileExtractFileName.value,
            SupportedMediaType.extractData,
            customerBookFileServiceConfig.extractCustomersFileBytesMax,
          )
          .returns(ZIO.fail(serviceErrorScan))
          .once()

        val serviceError = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioError

        serviceError shouldBe serviceErrorScan
      }

      "fail with an UnexpectedError when image AI extraction fails" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.jpeg")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.jpeg")),
          supportedMediaType = SupportedMediaType.JPEG,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val serviceErrorAI = ServiceError.InternalServerError.UnexpectedError(error = "AI extraction failed")

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          (aiClientDataExtractionMock
            .extractFromImage[CustomerBookFileAIExtract](
              _: FileScannedPath,
              _: SupportedMediaType,
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              fileScannerScanOutput.fileScannedPath,
              SupportedMediaType.JPEG,
              AIInstructions.extractCustomersFromImageInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .returns(ZIO.fail(serviceErrorAI))
            .once(),
        )

        val serviceError = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioError

        serviceError shouldBe serviceErrorAI
      }

      "fail with an UnexpectedError when spreadsheet conversion fails" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.csv")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.csv")),
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val serviceErrorConversion =
          ServiceError.InternalServerError.UnexpectedError(error = "Failed spreadsheet conversion")

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          spreadsheetToolMock.convertToCsv
            .expects(fileScannerScanOutput.fileScannedPath)
            .returns(ZStream.fail(serviceErrorConversion))
            .once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CustomerBookFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCustomersFromFileInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .onCall { (csvRecordStream, _, _, _, _) =>
              csvRecordStream.runCollect *>
                ZIO.die(new IllegalStateException("The CSV record stream should fail with the conversion error"))
            }
            .once(),
        )

        val serviceError = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioError

        serviceError shouldBe serviceErrorConversion
      }

      "fail with an UnexpectedError when spreadsheet AI extraction fails" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.csv")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.csv")),
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val csvRecords: List[CSVRecord] =
          CSVParser.parse("Full Name\nJane Doe", CSVFormat.DEFAULT).getRecords.asScala.toList
        val serviceErrorAI = ServiceError.InternalServerError.UnexpectedError(error = "AI extraction failed")

        inSequence(
          fileScannerMock.scan
            .expects(
              customerBookFileExtractByteStream,
              customerBookFileExtractFileName.value,
              SupportedMediaType.extractData,
              customerBookFileServiceConfig.extractCustomersFileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          spreadsheetToolMock.convertToCsv
            .expects(fileScannerScanOutput.fileScannedPath)
            .returns(ZStream.fromIterable(csvRecords))
            .once(),
          (aiClientDataExtractionMock
            .extractFromCsv[CustomerBookFileAIExtract](
              _: ZStream[Scope, ServiceError, CSVRecord],
              _: String,
              _: Option[String],
            )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
            .expects(
              *,
              AIInstructions.extractCustomersFromFileInstructions,
              None,
              ai.customerBookFileAIExtractOpenAIJsonSchema,
              ai.customerBookFileAIExtractCodec,
            )
            .onCall { (csvRecordStream, _, _, _, _) =>
              csvRecordStream.runCollect.flatMap { csvRecordsCollected =>
                csvRecordsCollected.toList shouldBe csvRecords
                ZIO.fail(serviceErrorAI)
              }
            }
            .once(),
        )

        val serviceError = buildCustomerBookFileService
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioError

        serviceError shouldBe serviceErrorAI
      }
    }
  }

  trait TestContext {
    val customerBookFileServiceConfig = CustomerBookFileServiceConfig(
      extractCustomersFileBytesMax = 20971520L
    )

    val fileScannerMock            = mock[FileScanner]
    val spreadsheetToolMock        = mock[SpreadsheetTool]
    val aiClientDataExtractionMock = mock[AIClientDataExtraction]

    def buildCustomerBookFileService: CustomerBookFileService[ServiceTask] = ZIO
      .service[CustomerBookFileService[ServiceTask]]
      .provide(
        CustomerBookFileService.local,
        ZLayer.succeed(customerBookFileServiceConfig),
        ZLayer.succeed(fileScannerMock),
        ZLayer.succeed(spreadsheetToolMock),
        ZLayer.succeed(aiClientDataExtractionMock),
      )
      .zioValue

    def aiExtractFromImage(
        customerBookFileAIExtract: CustomerBookFileAIExtract
    ): CustomerBookFileAiExtractPostResponse = {
      val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.jpeg")
      val customerBookFileExtractByteStream            = ZStream.empty
      val fileScannerScanOutput: FileScannerScanOutput = (
        fileScannedPath = FileScannedPath(Path.of("customers.jpeg")),
        supportedMediaType = SupportedMediaType.JPEG,
        fileBytesSize = FileBytesSize.assume(1L),
      )

      inSequence(
        fileScannerMock.scan
          .expects(
            customerBookFileExtractByteStream,
            customerBookFileExtractFileName.value,
            SupportedMediaType.extractData,
            customerBookFileServiceConfig.extractCustomersFileBytesMax,
          )
          .returns(ZIO.succeed(fileScannerScanOutput))
          .once(),
        (aiClientDataExtractionMock
          .extractFromImage[CustomerBookFileAIExtract](
            _: FileScannedPath,
            _: SupportedMediaType,
            _: String,
            _: Option[String],
          )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
          .expects(
            fileScannerScanOutput.fileScannedPath,
            SupportedMediaType.JPEG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
            ai.customerBookFileAIExtractOpenAIJsonSchema,
            ai.customerBookFileAIExtractCodec,
          )
          .returns(ZIO.succeed(customerBookFileAIExtract))
          .once(),
      )

      buildCustomerBookFileService
        .aiExtract(
          arbitrarySample[OrganizationID],
          customerBookFileExtractFileName,
          None,
          customerBookFileExtractByteStream,
        )
        .zioValue
    }

    def aiExtractFromSpreadsheetWithTwoGroups(
        customerBookFileAIExtractNotesGroup: List[String],
        customerBookFileAIExtractNotesMerged: List[String],
        noteCompactionResult: IO[ServiceError, NoteCompactionOutput],
    ): CustomerBookFileAiExtractPostResponse = {
      val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.csv")
      val customerBookFileExtractByteStream            = ZStream.empty
      val fileScannerScanOutput: FileScannerScanOutput = (
        fileScannedPath = FileScannedPath(Path.of("customers.csv")),
        supportedMediaType = SupportedMediaType.CSV,
        fileBytesSize = FileBytesSize.assume(1L),
      )
      val csvRecords: List[CSVRecord] =
        CSVParser.parse("Full Name\nJane Doe", CSVFormat.DEFAULT).getRecords.asScala.toList
      val customerBookFileAIExtractGroup = CustomerBookFileAIExtract(
        entriesIdentified = 0L,
        entriesProcessed = 0L,
        individualCandidates = Nil,
        businessCandidates = Nil,
        emptyEntryRowIndexes = Nil,
        unidentifiedEntryRowIndexes = Nil,
        extractionNotes = customerBookFileAIExtractNotesGroup,
      )

      inSequence(
        fileScannerMock.scan
          .expects(
            customerBookFileExtractByteStream,
            customerBookFileExtractFileName.value,
            SupportedMediaType.extractData,
            customerBookFileServiceConfig.extractCustomersFileBytesMax,
          )
          .returns(ZIO.succeed(fileScannerScanOutput))
          .once(),
        spreadsheetToolMock.convertToCsv
          .expects(fileScannerScanOutput.fileScannedPath)
          .returns(ZStream.fromIterable(csvRecords))
          .once(),
        (aiClientDataExtractionMock
          .extractFromCsv[CustomerBookFileAIExtract](
            _: ZStream[Scope, ServiceError, CSVRecord],
            _: String,
            _: Option[String],
          )(using _: OpenAIJsonSchema[CustomerBookFileAIExtract], _: JsonValueCodec[CustomerBookFileAIExtract]))
          .expects(
            *,
            AIInstructions.extractCustomersFromFileInstructions,
            None,
            ai.customerBookFileAIExtractOpenAIJsonSchema,
            ai.customerBookFileAIExtractCodec,
          )
          .returns(ZIO.succeed(NonEmptyChunk(customerBookFileAIExtractGroup, customerBookFileAIExtractGroup)))
          .once(),
        aiClientDataExtractionMock.noteCompaction
          .expects(customerBookFileAIExtractNotesMerged, AIInstructions.compactCustomerBookFileNotesInstructions)
          .returns(noteCompactionResult)
          .once(),
      )

      buildCustomerBookFileService
        .aiExtract(
          arbitrarySample[OrganizationID],
          customerBookFileExtractFileName,
          None,
          customerBookFileExtractByteStream,
        )
        .zioValue
    }
  }
}
