package io.mesazon.gateway.fun

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import io.circe.{parser, Json}
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.AIClientDataExtraction
import io.mesazon.gateway.config.CustomerBookFileServiceConfig
import io.mesazon.gateway.json.tapir.{
  customerBookFileAiExtractPostResponseCodec,
  customerBusinessCandidateSchema,
  customerIndividualCandidateSchema,
}
import io.mesazon.gateway.mock.Mocks
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
        val extractFromImageProcessingMessagesRef = Ref.make(List.empty[Option[String]]).zioValue

        fileScannerMock.scan
          .expects(
            customerBookFileExtractByteStream,
            customerBookFileExtractFileName.value,
            SupportedMediaType.extractData,
            customerBookFileServiceConfig.extractCustomersFileBytesMax,
          )
          .returns(ZIO.succeed(fileScannerScanOutput))
          .once()

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(customerBookFileAIExtract)),
          extractFromImageProcessingMessagesRefOpt = Some(extractFromImageProcessingMessagesRef),
          noteCompactionCallsRefOpt = Some(noteCompactionCallsRef),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
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
                CustomerEmailEntry(customerEmailDefault, isDefault = true),
                CustomerEmailEntry(customerEmailSecondary, isDefault = false),
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
                CustomerEmailEntry(customerBusinessEmailDefault, isDefault = true),
                CustomerEmailEntry(customerBusinessEmailSecondary, isDefault = false),
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
        extractFromImageCallsRef.refValue shouldBe List(
          (
            fileScannerScanOutput.fileScannedPath,
            SupportedMediaType.JPEG,
            AIInstructions.extractCustomersFromImageInstructions,
          )
        )
        extractFromImageProcessingMessagesRef.refValue shouldBe List(None)
        extractFromCsvCallsRef.refValue shouldBe Nil
        noteCompactionCallsRef.refValue shouldBe Nil
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
        val customerBookFileAIExtract             = CustomerBookFileAIExtract(0L, 0L, Nil, Nil, Nil, Nil, Nil)
        val extractFromImageProcessingMessagesRef = Ref.make(List.empty[Option[String]]).zioValue

        fileScannerMock.scan
          .expects(
            customerBookFileExtractByteStream,
            customerBookFileExtractFileName.value,
            SupportedMediaType.extractData,
            customerBookFileServiceConfig.extractCustomersFileBytesMax,
          )
          .returns(ZIO.succeed(fileScannerScanOutput))
          .once()

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(customerBookFileAIExtract)),
          extractFromImageProcessingMessagesRefOpt = Some(extractFromImageProcessingMessagesRef),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
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
        extractFromImageCallsRef.refValue shouldBe List(
          (
            fileScannerScanOutput.fileScannedPath,
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
          )
        )
        extractFromImageProcessingMessagesRef.refValue shouldBe List(Some(customerBookFileProcessingMessage.value))
        extractFromCsvCallsRef.refValue shouldBe Nil
      }

      "extract candidates from a CSV file with processing guidance" in new TestContext {
        val customerBookFileProcessingMessage   = CustomerBookFileProcessingMessage("Treat column B as the email")
        val organizationID                      = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName     = CustomerBookFileExtractFileName("customers.csv")
        val customerBookFileExtractByteStream   = ZStream.empty
        val extractFromCsvProcessingMessagesRef = Ref.make(List.empty[Option[String]]).zioValue
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
            1L,
            1L,
            List(customerIndividualAIExtract),
            Nil,
            Nil,
            Nil,
            List("Column B holds two emails for some rows"),
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
        )

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromCsvOptResult = Some(ZIO.succeed(NonEmptyChunk.single(customerBookFileAIExtract))),
          extractFromCsvProcessingMessagesRefOpt = Some(extractFromCsvProcessingMessagesRef),
          noteCompactionCallsRefOpt = Some(noteCompactionCallsRef),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
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
                CustomerEmailEntry(customerEmailDefault, isDefault = true),
                CustomerEmailEntry(customerEmailSecondary, isDefault = false),
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
        extractFromCsvCallsRef.refValue shouldBe List(
          (csvRecords, AIInstructions.extractCustomersFromFileInstructions)
        )
        extractFromCsvProcessingMessagesRef.refValue shouldBe List(Some(customerBookFileProcessingMessage.value))
        extractFromImageCallsRef.refValue shouldBe Nil
        noteCompactionCallsRef.refValue shouldBe Nil
      }

      "extract candidates from an XLS file through Excel conversion" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.xls")
        val customerBookFileExtractByteStream            = ZStream.empty
        val extractFromCsvProcessingMessagesRef          = Ref.make(List.empty[Option[String]]).zioValue
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.xls")),
          supportedMediaType = SupportedMediaType.XLS,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val csvRecords: List[CSVRecord] =
          CSVParser.parse("Full Name\nJane Doe", CSVFormat.DEFAULT).getRecords.asScala.toList
        val customerBookFileAIExtract = CustomerBookFileAIExtract(0L, 0L, Nil, Nil, Nil, Nil, Nil)

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
        )

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromCsvOptResult = Some(ZIO.succeed(NonEmptyChunk.single(customerBookFileAIExtract))),
          extractFromCsvProcessingMessagesRefOpt = Some(extractFromCsvProcessingMessagesRef),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = Nil,
        )
        extractFromCsvCallsRef.refValue shouldBe List(
          (csvRecords, AIInstructions.extractCustomersFromFileInstructions)
        )
        extractFromCsvProcessingMessagesRef.refValue shouldBe List(None)
        extractFromImageCallsRef.refValue shouldBe Nil
      }

      "extract candidates from an XLSX file through Excel conversion" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.xlsx")
        val customerBookFileExtractByteStream            = ZStream.empty
        val extractFromCsvProcessingMessagesRef          = Ref.make(List.empty[Option[String]]).zioValue
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.xlsx")),
          supportedMediaType = SupportedMediaType.XLSX,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val csvRecords: List[CSVRecord] =
          CSVParser.parse("Full Name\nJane Doe", CSVFormat.DEFAULT).getRecords.asScala.toList
        val customerBookFileAIExtract = CustomerBookFileAIExtract(0L, 0L, Nil, Nil, Nil, Nil, Nil)

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
        )

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromCsvOptResult = Some(ZIO.succeed(NonEmptyChunk.single(customerBookFileAIExtract))),
          extractFromCsvProcessingMessagesRefOpt = Some(extractFromCsvProcessingMessagesRef),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = Nil,
        )
        extractFromCsvCallsRef.refValue shouldBe List(
          (csvRecords, AIInstructions.extractCustomersFromFileInstructions)
        )
        extractFromCsvProcessingMessagesRef.refValue shouldBe List(None)
        extractFromImageCallsRef.refValue shouldBe Nil
      }

      "scan the upload with the configured customer extraction byte limit" in new TestContext {
        val extractCustomersFileBytesMaxApplicationDefault = 20L * 1024 * 1024
        val organizationID                                 = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName                = CustomerBookFileExtractFileName("customers.webp")
        val customerBookFileExtractByteStream              = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput   = (
          fileScannedPath = FileScannedPath(Path.of("customers.webp")),
          supportedMediaType = SupportedMediaType.WEBP,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val customerBookFileAIExtract = CustomerBookFileAIExtract(0L, 0L, Nil, Nil, Nil, Nil, Nil)

        customerBookFileServiceConfig.extractCustomersFileBytesMax shouldNot equal(
          extractCustomersFileBytesMaxApplicationDefault
        )

        fileScannerMock.scan
          .expects(
            customerBookFileExtractByteStream,
            customerBookFileExtractFileName.value,
            SupportedMediaType.extractData,
            customerBookFileServiceConfig.extractCustomersFileBytesMax,
          )
          .returns(ZIO.succeed(fileScannerScanOutput))
          .once()

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(customerBookFileAIExtract)),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
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
          if (customerFullNameBatch1.value.length < 255) customerFullNameBatch1.value + "b"
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
          List("Column D looks like an internal reference in every group", "Some cells are merged")
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
        )

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromCsvOptResult =
            Some(ZIO.succeed(NonEmptyChunk(customerBookFileAIExtractBatch1, customerBookFileAIExtractBatch2))),
          noteCompactionCallsRefOpt = Some(noteCompactionCallsRef),
          noteCompactionOptResult = Some(ZIO.succeed(noteCompactionOutput)),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            CustomerIndividualCandidate(
              fullName = Some(customerFullNameBatch1),
              emails = List(
                CustomerEmailEntry(customerEmailBatch1Default, isDefault = true),
                CustomerEmailEntry(customerEmailBatch1Secondary, isDefault = false),
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
                CustomerEmailEntry(customerEmailBatch2Default, isDefault = true),
                CustomerEmailEntry(customerEmailBatch2Secondary, isDefault = false),
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
                CustomerEmailEntry(customerBusinessEmailDefault, isDefault = true),
                CustomerEmailEntry(customerBusinessEmailSecondary, isDefault = false),
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
        extractFromCsvCallsRef.refValue shouldBe List(
          (csvRecords, AIInstructions.extractCustomersFromFileInstructions)
        )
        noteCompactionCallsRef.refValue shouldBe List(
          (customerBookFileAIExtractNotesMerged, AIInstructions.compactCustomerBookFileNotesInstructions)
        )
        extractFromImageCallsRef.refValue shouldBe Nil
      }

      "keep the raw top-level notes when note compaction fails with an UnexpectedError" in new TestContext {
        val customerBookFileAIExtractNotesGroup  = List("Some cells are merged")
        val customerBookFileAIExtractNotesMerged = List("Some cells are merged", "Some cells are merged")
        val serviceErrorNoteCompaction           =
          ServiceError.InternalServerError.UnexpectedError("Note compaction failed")

        aiExtractFromSpreadsheetWithTwoGroups(
          customerBookFileAIExtractNotesGroup,
          ZIO.fail(serviceErrorNoteCompaction),
        ) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = customerBookFileAIExtractNotesMerged,
        )
        noteCompactionCallsRef.refValue shouldBe List(
          (customerBookFileAIExtractNotesMerged, AIInstructions.compactCustomerBookFileNotesInstructions)
        )
      }

      "keep the raw top-level notes when note compaction dies with a defect" in new TestContext {
        val customerBookFileAIExtractNotesGroup  = List("Some cells are merged")
        val customerBookFileAIExtractNotesMerged = List("Some cells are merged", "Some cells are merged")
        val runtimeExceptionNoteCompaction       = new RuntimeException("Note compaction defect")

        aiExtractFromSpreadsheetWithTwoGroups(
          customerBookFileAIExtractNotesGroup,
          ZIO.die(runtimeExceptionNoteCompaction),
        ) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = customerBookFileAIExtractNotesMerged,
        )
        noteCompactionCallsRef.refValue shouldBe List(
          (customerBookFileAIExtractNotesMerged, AIInstructions.compactCustomerBookFileNotesInstructions)
        )
      }

      "keep the raw top-level notes when note compaction returns no notes" in new TestContext {
        val customerBookFileAIExtractNotesGroup  = List("Some cells are merged")
        val customerBookFileAIExtractNotesMerged = List("Some cells are merged", "Some cells are merged")
        val noteCompactionOutput                 = NoteCompactionOutput(Nil)

        aiExtractFromSpreadsheetWithTwoGroups(
          customerBookFileAIExtractNotesGroup,
          ZIO.succeed(noteCompactionOutput),
        ) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = customerBookFileAIExtractNotesMerged,
        )
        noteCompactionCallsRef.refValue shouldBe List(
          (customerBookFileAIExtractNotesMerged, AIInstructions.compactCustomerBookFileNotesInstructions)
        )
      }

      "keep the raw top-level notes when note compaction returns more notes than it was sent" in new TestContext {
        val customerBookFileAIExtractNotesGroup  = List("Some cells are merged")
        val customerBookFileAIExtractNotesMerged = List("Some cells are merged", "Some cells are merged")
        val noteCompactionOutput                 = NoteCompactionOutput(
          List("Some cells are merged", "Column D looks like an internal reference", "Row 7 is blank")
        )

        noteCompactionOutput.notes.size should be > customerBookFileAIExtractNotesMerged.size

        aiExtractFromSpreadsheetWithTwoGroups(
          customerBookFileAIExtractNotesGroup,
          ZIO.succeed(noteCompactionOutput),
        ) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = Nil,
          businessCandidates = Nil,
          infoNotes = List("Processed 0 of 0 entries"),
          extractionNotes = customerBookFileAIExtractNotesMerged,
        )
        noteCompactionCallsRef.refValue shouldBe List(
          (customerBookFileAIExtractNotesMerged, AIInstructions.compactCustomerBookFileNotesInstructions)
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
          CustomerBookFileAIExtract(4L, 1L, List(customerIndividualAIExtract), Nil, Nil, Nil, Nil)

        fileScannerMock.scan
          .expects(
            customerBookFileExtractByteStream,
            customerBookFileExtractFileName.value,
            SupportedMediaType.extractData,
            customerBookFileServiceConfig.extractCustomersFileBytesMax,
          )
          .returns(ZIO.succeed(fileScannerScanOutput))
          .once()

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(customerBookFileAIExtract)),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
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
          CustomerBookFileAIExtract(3L, 0L, Nil, Nil, List(2L, 5L), List(7L), Nil)

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
        )

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromCsvOptResult = Some(ZIO.succeed(NonEmptyChunk.single(customerBookFileAIExtract))),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
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
        )

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromCsvOptResult = Some(ZIO.succeed(NonEmptyChunk.single(customerBookFileAIExtract))),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List.fill(346)(
            CustomerIndividualCandidate(
              fullName = None,
              emails = List(CustomerEmailEntry(customerEmail, isDefault = true)),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/fullName")),
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
        )

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromCsvOptResult = Some(ZIO.succeed(NonEmptyChunk.single(customerBookFileAIExtract))),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List.fill(440)(
            CustomerIndividualCandidate(
              fullName = None,
              emails = List(CustomerEmailEntry(customerEmail, isDefault = true)),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/fullName")),
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
        )

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromCsvOptResult =
            Some(ZIO.succeed(NonEmptyChunk(customerBookFileAIExtractBatch1, customerBookFileAIExtractBatch2))),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List.fill(4)(
            CustomerIndividualCandidate(
              fullName = None,
              emails = List(CustomerEmailEntry(customerEmail, isDefault = true)),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/fullName")),
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
        val customerBookFileAIExtract = CustomerBookFileAIExtract(0L, 0L, Nil, Nil, Nil, Nil, extractionNotes)

        fileScannerMock.scan
          .expects(
            customerBookFileExtractByteStream,
            customerBookFileExtractFileName.value,
            SupportedMediaType.extractData,
            customerBookFileServiceConfig.extractCustomersFileBytesMax,
          )
          .returns(ZIO.succeed(fileScannerScanOutput))
          .once()

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(customerBookFileAIExtract)),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
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
          duplicateFields = List(FieldInfo("/fullName")),
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
          duplicateFields = List(FieldInfo("/businessName")),
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
          duplicateFields = List(FieldInfo("/fullName")),
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
          duplicateFields = List(FieldInfo("/businessName")),
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
        )

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromCsvOptResult =
            Some(ZIO.succeed(NonEmptyChunk(customerBookFileAIExtractBatch, customerBookFileAIExtractBatch))),
          noteCompactionCallsRefOpt = Some(noteCompactionCallsRef),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioValue

        customerBookFileAiExtractPostResponse shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(customerIndividualCandidateDuplicate, customerIndividualCandidateDuplicate),
          businessCandidates = List(customerBusinessCandidateDuplicate, customerBusinessCandidateDuplicate),
          infoNotes = List("Processed 4 of 4 entries"),
          extractionNotes = Nil,
        )
        noteCompactionCallsRef.refValue shouldBe Nil
      }

      "not mark names that differ only in letter case as duplicates" in new TestContext {
        val customerFullNameLowerCase =
          CustomerFullName.assume((arbitrarySample[CustomerFullName].value.take(254) + "a").toLowerCase(Locale.ROOT))
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
              emails = List(CustomerEmailEntry(customerEmail1, isDefault = true)),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/fullName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = Nil,
            ),
            CustomerIndividualCandidate(
              fullName = None,
              emails = List(CustomerEmailEntry(customerEmail2, isDefault = true)),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/fullName")),
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
              missingFields = List(FieldInfo("/businessName")),
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
              missingFields = List(FieldInfo("/businessName")),
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
              emails = List(CustomerEmailEntry(customerEmail, isDefault = true)),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/fullName")),
              duplicateFields = Nil,
              needsAttention = true,
              extractionNotes = List("Only an email is readable"),
            ),
            CustomerIndividualCandidate(
              fullName = None,
              emails = Nil,
              phoneNumbers = List(
                CustomerPhoneNumberEntryCandidate(
                  CustomerPhoneNumberCandidate(phoneNationalNumber, Some(phoneCountryCode)),
                  isDefault = true,
                )
              ),
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/fullName")),
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
                    Address(
                      addressLine1 = None,
                      addressLine2 = None,
                      city = Some(customerCity),
                      postalCode = None,
                      country = None,
                    ),
                    AddressType.ShippingAndBilling,
                  )
                )
              ),
              inferredFields = Nil,
              missingFields = List(FieldInfo("/fullName")),
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
              missingFields = List(FieldInfo("/businessName")),
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
              missingFields = List(FieldInfo("/businessName")),
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
                  phoneNumber = Some(CustomerPhoneNumberCandidate(phoneNationalNumber, Some(phoneCountryCode))),
                ),
              ),
              inferredFields = Nil,
              missingFields = List(
                FieldInfo("/customerBusinessContacts/0/fullName"),
                FieldInfo("/customerBusinessContacts/1/fullName"),
                FieldInfo("/customerBusinessContacts/2/fullName"),
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
                  CustomerPhoneNumberCandidate(phoneNationalNumber, None),
                  isDefault = true,
                )
              ),
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/phoneNumbers/0/phoneNumber/phoneCountryCode")),
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
                  phoneNumber = Some(CustomerPhoneNumberCandidate(phoneNationalNumber, None)),
                )
              ),
              inferredFields = Nil,
              missingFields = List(FieldInfo("/customerBusinessContacts/0/phoneNumber/phoneCountryCode")),
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
                    Address(
                      addressLine1 = Some(customerAddressLine1),
                      addressLine2 = None,
                      city = None,
                      postalCode = None,
                      country = None,
                    ),
                    AddressType.ShippingAndBilling,
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
                  CustomerAddressAIExtract(None, None, Some(customerCity), None, None, Nil),
                  customerFullNameWithAddressType.addressType,
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
                  AddressEntry(Address(None, None, Some(customerCity), None, None), AddressType.ShippingAndBilling)
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
                  CustomerAddressAIExtract(None, None, Some(customerCityFirst), None, None, Nil),
                  customerFullNameWithAddressTypePair.addressTypeFirst,
                ),
                CustomerAddressEntryAIExtract(
                  CustomerAddressAIExtract(None, None, Some(customerCitySecond), None, None, Nil),
                  customerFullNameWithAddressTypePair.addressTypeSecond,
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
                    Address(None, None, Some(customerCityFirst), None, None),
                    customerFullNameWithAddressTypePair.addressTypeFirst,
                  )
                ),
                CustomerAddressEntry(
                  AddressEntry(
                    Address(None, None, Some(customerCitySecond), None, None),
                    customerFullNameWithAddressTypePair.addressTypeSecond,
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
                  CustomerAddressAIExtract(None, None, Some(customerCityFirst), None, None, Nil),
                  customerFullNameWithAddressTypePair.addressTypeFirst,
                ),
                CustomerAddressEntryAIExtract(
                  CustomerAddressAIExtract(None, None, Some(customerCitySecond), None, None, Nil),
                  customerFullNameWithAddressTypePair.addressTypeSecond,
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
                  AddressEntry(Address(None, None, Some(customerCityFirst), None, None), AddressType.Shipping)
                ),
                CustomerAddressEntry(
                  AddressEntry(Address(None, None, Some(customerCitySecond), None, None), AddressType.Billing)
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
              CustomerAddressAIExtract(None, None, Some(customerCity1), None, None, Nil),
              AddressType.Billing,
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(None, None, Some(customerCity2), None, None, Nil),
              AddressType.Billing,
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(
                addressLine1 = Some(customerAddressLine1),
                addressLine2 = None,
                city = Some(customerCity3),
                postalCode = None,
                country = None,
                extractionNotes = List("The third address is faint"),
              ),
              arbitrarySample[AddressType],
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
              addresses = List(
                CustomerAddressEntry(
                  AddressEntry(Address(None, None, Some(customerCity1), None, None), AddressType.Shipping)
                ),
                CustomerAddressEntry(
                  AddressEntry(Address(None, None, Some(customerCity2), None, None), AddressType.Billing)
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
              CustomerAddressAIExtract(None, None, Some(customerCity1), None, None, Nil),
              AddressType.Shipping,
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(None, None, Some(customerCity2), None, None, Nil),
              AddressType.Billing,
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(Some(customerAddressLine13), None, Some(customerCity3), None, None, Nil),
              arbitrarySample[AddressType],
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(Some(customerAddressLine14), None, Some(customerCity4), None, None, Nil),
              arbitrarySample[AddressType],
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
              addresses = List(
                CustomerAddressEntry(
                  AddressEntry(Address(None, None, Some(customerCity1), None, None), AddressType.Shipping)
                ),
                CustomerAddressEntry(
                  AddressEntry(Address(None, None, Some(customerCity2), None, None), AddressType.Billing)
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
              CustomerAddressAIExtract(None, None, Some(customerCity1), None, None, Nil),
              AddressType.Shipping,
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(None, None, Some(customerCity2), None, None, Nil),
              AddressType.Billing,
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(
                Some(customerAddressLine1),
                Some(customerAddressLine2),
                Some(customerCity),
                Some(customerPostalCode),
                Some(customerCountry),
                Nil,
              ),
              arbitrarySample[AddressType],
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(
                None,
                Some(customerAddressLine2),
                Some(customerCity),
                Some(customerPostalCode),
                Some(customerCountry),
                Nil,
              ),
              arbitrarySample[AddressType],
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(
                None,
                None,
                Some(customerCity),
                Some(customerPostalCode),
                Some(customerCountry),
                Nil,
              ),
              arbitrarySample[AddressType],
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(None, None, None, Some(customerPostalCode), Some(customerCountry), Nil),
              arbitrarySample[AddressType],
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(None, None, None, None, Some(customerCountry), Nil),
              arbitrarySample[AddressType],
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
              addresses = List(
                CustomerAddressEntry(
                  AddressEntry(Address(None, None, Some(customerCity1), None, None), AddressType.Shipping)
                ),
                CustomerAddressEntry(
                  AddressEntry(Address(None, None, Some(customerCity2), None, None), AddressType.Billing)
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
              CustomerAddressAIExtract(
                addressLine1 = None,
                addressLine2 = None,
                city = None,
                postalCode = Some(customerPostalCode),
                country = None,
                extractionNotes = List("Only the postal code is readable"),
              ),
              arbitrarySample[AddressType],
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
                  CustomerPhoneNumberCandidate(phoneNationalNumber, Some(phoneCountryCode)),
                  isDefault = true,
                )
              ),
              addresses = List(
                CustomerAddressEntry(
                  AddressEntry(
                    Address(None, None, None, Some(customerPostalCode), None),
                    AddressType.ShippingAndBilling,
                  )
                )
              ),
              inferredFields = List(
                FieldInfo("/fullName"),
                FieldInfo("/phoneNumbers/0/phoneNumber/phoneCountryCode"),
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
                FieldInfo("/businessName"),
                FieldInfo("/customerBusinessContacts/0/fullName"),
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
                CustomerEmailEntry(customerEmail1, isDefault = true),
                CustomerEmailEntry(customerEmail2, isDefault = false),
                CustomerEmailEntry(customerEmail3, isDefault = false),
              ),
              phoneNumbers = List(
                CustomerPhoneNumberEntryCandidate(
                  CustomerPhoneNumberCandidate(phoneNationalNumber1, None),
                  isDefault = true,
                ),
                CustomerPhoneNumberEntryCandidate(
                  CustomerPhoneNumberCandidate(phoneNationalNumber2, Some(phoneCountryCode2)),
                  isDefault = false,
                ),
                CustomerPhoneNumberEntryCandidate(
                  CustomerPhoneNumberCandidate(phoneNationalNumber3, Some(phoneCountryCode3)),
                  isDefault = false,
                ),
              ),
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/phoneNumbers/0/phoneNumber/phoneCountryCode")),
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
              CustomerAddressAIExtract(None, None, Some(customerCity1), None, None, List("First address note")),
              AddressType.Billing,
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(None, None, None, None, None, List("Empty address note")),
              arbitrarySample[AddressType],
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(None, None, Some(customerCity3), None, None, List("Third address note")),
              AddressType.Shipping,
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(None, None, Some(customerCity4), None, None, List("Fourth address note")),
              arbitrarySample[AddressType],
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
              addresses = List(
                CustomerAddressEntry(
                  AddressEntry(Address(None, None, Some(customerCity1), None, None), AddressType.Billing)
                ),
                CustomerAddressEntry(
                  AddressEntry(Address(None, None, Some(customerCity3), None, None), AddressType.Shipping)
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
              CustomerAddressAIExtract(None, None, Some(customerCity1), None, None, List("First address note")),
              AddressType.Billing,
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(None, None, Some(customerCity2), None, None, List("Second address note")),
              AddressType.ShippingAndBilling,
            ),
            CustomerAddressEntryAIExtract(
              CustomerAddressAIExtract(None, None, Some(customerCity3), None, None, List("Third address note")),
              arbitrarySample[AddressType],
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
              addresses = List(
                CustomerAddressEntry(
                  AddressEntry(Address(None, None, Some(customerCity1), None, None), AddressType.Shipping)
                ),
                CustomerAddressEntry(
                  AddressEntry(Address(None, None, Some(customerCity2), None, None), AddressType.Billing)
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
              phoneNationalNumber1,
              Some(phoneCountryCode1),
              phoneCountryCodeInferred = false,
            ),
            CustomerPhoneNumberAIExtract(
              phoneNationalNumber2,
              Some(phoneCountryCode2),
              phoneCountryCodeInferred = false,
            ),
            CustomerPhoneNumberAIExtract(phoneNationalNumber3, None, phoneCountryCodeInferred = false),
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
              phoneNationalNumber1,
              Some(phoneCountryCode1),
              phoneCountryCodeInferred = false,
            ),
            CustomerPhoneNumberAIExtract(phoneNationalNumber2, None, phoneCountryCodeInferred = false),
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
                  CustomerPhoneNumberCandidate(phoneNationalNumber1, Some(phoneCountryCode1)),
                  isDefault = true,
                ),
                CustomerPhoneNumberEntryCandidate(
                  CustomerPhoneNumberCandidate(phoneNationalNumber2, Some(phoneCountryCode2)),
                  isDefault = false,
                ),
                CustomerPhoneNumberEntryCandidate(
                  CustomerPhoneNumberCandidate(phoneNationalNumber3, None),
                  isDefault = false,
                ),
              ),
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/phoneNumbers/2/phoneNumber/phoneCountryCode")),
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
                  CustomerPhoneNumberCandidate(phoneNationalNumber1, Some(phoneCountryCode1)),
                  isDefault = true,
                ),
                CustomerPhoneNumberEntryCandidate(
                  CustomerPhoneNumberCandidate(phoneNationalNumber2, None),
                  isDefault = false,
                ),
              ),
              addresses = Nil,
              customerBusinessContacts = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/phoneNumbers/1/phoneNumber/phoneCountryCode")),
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
                  phoneNationalNumber,
                  Some(phoneCountryCode),
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
                  phoneNumber = Some(CustomerPhoneNumberCandidate(phoneNationalNumber, Some(phoneCountryCode))),
                ),
              ),
              inferredFields = List(FieldInfo("/customerBusinessContacts/1/phoneNumber/phoneCountryCode")),
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
        val phoneNationalNumber4                     = arbitrarySample[PhoneNationalNumber]
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
          phoneNumber =
            Some(CustomerPhoneNumberAIExtract(phoneNationalNumber1, None, phoneCountryCodeInferred = false)),
          fullNameInferred = false,
          extractionNotes = Nil,
        )
        val customerBusinessContactAIExtractPhoneCountryCodeInferred = CustomerBusinessContactAIExtract(
          fullName = Some(customerBusinessContactFullName2),
          role = None,
          email = None,
          phoneNumber = Some(
            CustomerPhoneNumberAIExtract(phoneNationalNumber2, Some(phoneCountryCode2), phoneCountryCodeInferred = true)
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
          phoneNumber =
            Some(CustomerPhoneNumberAIExtract(phoneNationalNumber4, None, phoneCountryCodeInferred = false)),
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
                  phoneNumber = Some(CustomerPhoneNumberCandidate(phoneNationalNumber1, None)),
                ),
                CustomerBusinessContactCandidate(
                  fullName = Some(customerBusinessContactFullName2),
                  role = None,
                  email = None,
                  phoneNumber = Some(CustomerPhoneNumberCandidate(phoneNationalNumber2, Some(phoneCountryCode2))),
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
                  phoneNumber = Some(CustomerPhoneNumberCandidate(phoneNationalNumber4, None)),
                ),
              ),
              inferredFields = List(
                FieldInfo("/customerBusinessContacts/2/phoneNumber/phoneCountryCode"),
                FieldInfo("/customerBusinessContacts/3/fullName"),
              ),
              missingFields = List(
                FieldInfo("/customerBusinessContacts/0/fullName"),
                FieldInfo("/customerBusinessContacts/1/phoneNumber/phoneCountryCode"),
                FieldInfo("/customerBusinessContacts/4/fullName"),
                FieldInfo("/customerBusinessContacts/4/phoneNumber/phoneCountryCode"),
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
          if (customerFullName.value.length < 255) customerFullName.value + "b"
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
          duplicateFields = List(FieldInfo("/fullName")),
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
        )

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromCsvOptResult =
            Some(ZIO.succeed(NonEmptyChunk(customerBookFileAIExtractBatch1, customerBookFileAIExtractBatch2))),
        )

        val customerBookFileAiExtractPostResponse = buildCustomerBookFileService(aiClientDataExtraction)
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
              emails = List(CustomerEmailEntry(customerEmail, isDefault = true)),
              phoneNumbers = Nil,
              addresses = Nil,
              inferredFields = Nil,
              missingFields = List(FieldInfo("/fullName")),
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
              missingFields = List(FieldInfo("/businessName")),
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
          phoneNumbers =
            List(CustomerPhoneNumberAIExtract(phoneNationalNumber1, None, phoneCountryCodeInferred = false)),
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
                phoneNumber =
                  Some(CustomerPhoneNumberAIExtract(phoneNationalNumber2, None, phoneCountryCodeInferred = false)),
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
          emails = List(CustomerEmailEntry(customerEmail, isDefault = true)),
          phoneNumbers = Nil,
          addresses = Nil,
          inferredFields = Nil,
          missingFields = List(FieldInfo("/fullName")),
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
          missingFields = List(FieldInfo("/customerBusinessContacts/0/fullName")),
          duplicateFields = Nil,
          needsAttention = true,
          extractionNotes = Nil,
        )

        val customerIndividualCandidateDuplicate = customerIndividualCandidateNameless.copy(
          fullName = Some(customerFullNameDuplicate),
          emails = Nil,
          missingFields = Nil,
          duplicateFields = List(FieldInfo("/fullName")),
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
                  CustomerPhoneNumberCandidate(phoneNationalNumber1, None),
                  isDefault = true,
                )
              ),
              missingFields = List(FieldInfo("/phoneNumbers/0/phoneNumber/phoneCountryCode")),
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
                  phoneNumber = Some(CustomerPhoneNumberCandidate(phoneNationalNumber2, None)),
                )
              ),
              missingFields = List(FieldInfo("/customerBusinessContacts/0/phoneNumber/phoneCountryCode")),
            ),
          ),
          infoNotes = List("Processed 6 of 6 entries"),
          extractionNotes = Nil,
        )
      }

      "not mark a candidate whose only findings are inferred fields, AI notes or a dropped all-empty address" in new TestContext {
        val customerFullNameInferred                    = arbitrarySample[CustomerFullName]
        val customerFullNamePhoneNumber                 = arbitrarySample[CustomerFullName]
        val customerFullNameExtractionNotes             = arbitrarySample[CustomerFullName]
        val customerFullNameAddress                     = arbitrarySample[CustomerFullName]
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
            CustomerPhoneNumberAIExtract(phoneNationalNumber, Some(phoneCountryCode), phoneCountryCodeInferred = true)
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
              CustomerAddressAIExtract(None, None, None, None, None, Nil),
              arbitrarySample[AddressType],
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
          inferredFields = List(FieldInfo("/fullName")),
          missingFields = Nil,
          duplicateFields = Nil,
          needsAttention = false,
          extractionNotes = Nil,
        )

        aiExtractFromImage(customerBookFileAIExtract) shouldBe CustomerBookFileAiExtractPostResponse(
          individualCandidates = List(
            customerIndividualCandidateFullNameInferred,
            customerIndividualCandidateFullNameInferred.copy(
              fullName = Some(customerFullNamePhoneNumber),
              phoneNumbers = List(
                CustomerPhoneNumberEntryCandidate(
                  CustomerPhoneNumberCandidate(phoneNationalNumber, Some(phoneCountryCode)),
                  isDefault = true,
                )
              ),
              inferredFields = List(FieldInfo("/phoneNumbers/0/phoneNumber/phoneCountryCode")),
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
                  phoneNationalNumber,
                  Some(phoneCountryCode),
                  phoneCountryCodeInferred = true,
                ),
                CustomerPhoneNumberAIExtract(phoneNationalNumber, None, phoneCountryCodeInferred = false),
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
                  phoneNumber =
                    Some(CustomerPhoneNumberAIExtract(phoneNationalNumber, None, phoneCountryCodeInferred = false)),
                  fullNameInferred = true,
                  extractionNotes = Nil,
                ),
                CustomerBusinessContactAIExtract(
                  fullName = Some(customerBusinessContactFullName2),
                  role = None,
                  email = None,
                  phoneNumber = Some(
                    CustomerPhoneNumberAIExtract(
                      phoneNationalNumber,
                      Some(phoneCountryCode),
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
        val serviceErrorScan = ServiceError.InternalServerError.UnexpectedError("Failed to scan the upload")

        fileScannerMock.scan
          .expects(
            customerBookFileExtractByteStream,
            customerBookFileExtractFileName.value,
            SupportedMediaType.extractData,
            customerBookFileServiceConfig.extractCustomersFileBytesMax,
          )
          .returns(ZIO.fail(serviceErrorScan))
          .once()

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock[CustomerBookFileAIExtract](
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
        )

        val serviceError = buildCustomerBookFileService(aiClientDataExtraction)
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioError

        serviceError shouldBe serviceErrorScan
        extractFromImageCallsRef.refValue shouldBe Nil
        extractFromCsvCallsRef.refValue shouldBe Nil
      }

      "fail with an UnexpectedError when photo AI extraction fails" in new TestContext {
        val organizationID                               = arbitrarySample[OrganizationID]
        val customerBookFileExtractFileName              = CustomerBookFileExtractFileName("customers.jpeg")
        val customerBookFileExtractByteStream            = ZStream.empty
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = FileScannedPath(Path.of("customers.jpeg")),
          supportedMediaType = SupportedMediaType.JPEG,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val serviceErrorAI = ServiceError.InternalServerError.UnexpectedError("AI extraction failed")

        fileScannerMock.scan
          .expects(
            customerBookFileExtractByteStream,
            customerBookFileExtractFileName.value,
            SupportedMediaType.extractData,
            customerBookFileServiceConfig.extractCustomersFileBytesMax,
          )
          .returns(ZIO.succeed(fileScannerScanOutput))
          .once()

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock[CustomerBookFileAIExtract](
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromImageOptResult = Some(ZIO.fail(serviceErrorAI)),
        )

        val serviceError = buildCustomerBookFileService(aiClientDataExtraction)
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioError

        serviceError shouldBe serviceErrorAI
        extractFromImageCallsRef.refValue shouldBe List(
          (
            fileScannerScanOutput.fileScannedPath,
            SupportedMediaType.JPEG,
            AIInstructions.extractCustomersFromImageInstructions,
          )
        )
        extractFromCsvCallsRef.refValue shouldBe Nil
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
        val serviceErrorAI = ServiceError.InternalServerError.UnexpectedError("AI extraction failed")

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
        )

        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock[CustomerBookFileAIExtract](
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromCsvOptResult = Some(ZIO.fail(serviceErrorAI)),
        )

        val serviceError = buildCustomerBookFileService(aiClientDataExtraction)
          .aiExtract(organizationID, customerBookFileExtractFileName, None, customerBookFileExtractByteStream)
          .zioError

        serviceError shouldBe serviceErrorAI
        extractFromCsvCallsRef.refValue shouldBe List(
          (csvRecords, AIInstructions.extractCustomersFromFileInstructions)
        )
        extractFromImageCallsRef.refValue shouldBe Nil
      }
    }
  }

  trait TestContext {
    val customerBookFileServiceConfig = CustomerBookFileServiceConfig(
      extractCustomersFileBytesMax = 15L * 1024 * 1024
    )

    val fileScannerMock     = mock[FileScanner]
    val spreadsheetToolMock = mock[SpreadsheetTool]

    val extractFromImageCallsRef = Ref.make(List.empty[(FileScannedPath, SupportedMediaType, String)]).zioValue
    val extractFromCsvCallsRef   = Ref.make(List.empty[(List[CSVRecord], String)]).zioValue
    val noteCompactionCallsRef   = Ref.make(List.empty[(List[String], String)]).zioValue

    def buildCustomerBookFileService(
        aiClientDataExtraction: AIClientDataExtraction
    ): CustomerBookFileService[ServiceTask] = ZIO
      .service[CustomerBookFileService[ServiceTask]]
      .provide(
        CustomerBookFileService.local,
        ZLayer.succeed(customerBookFileServiceConfig),
        ZLayer.succeed(fileScannerMock),
        ZLayer.succeed(spreadsheetToolMock),
        ZLayer.succeed(aiClientDataExtraction),
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

      fileScannerMock.scan
        .expects(
          customerBookFileExtractByteStream,
          customerBookFileExtractFileName.value,
          SupportedMediaType.extractData,
          customerBookFileServiceConfig.extractCustomersFileBytesMax,
        )
        .returns(ZIO.succeed(fileScannerScanOutput))
        .once()

      buildCustomerBookFileService(
        new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(customerBookFileAIExtract)),
        )
      )
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
      )

      buildCustomerBookFileService(
        new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromCsvOptResult =
            Some(ZIO.succeed(NonEmptyChunk(customerBookFileAIExtractGroup, customerBookFileAIExtractGroup))),
          noteCompactionCallsRefOpt = Some(noteCompactionCallsRef),
          noteCompactionOptResult = Some(noteCompactionResult),
        )
      )
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
