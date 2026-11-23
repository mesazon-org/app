package io.mesazon.gateway.golden

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString, JsonValueCodec}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import diffson.circe.*
import diffson.diff
import diffson.jsonpatch.lcsdiff.remembering.*
import diffson.lcs.{Lcs, Patience}
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.AIClientDataExtraction
import io.mesazon.gateway.config.AIClientDataExtractionConfig
import io.mesazon.gateway.json.ai.given
import io.mesazon.gateway.json.tapir.addressTypeCodec
import io.mesazon.gateway.service.AIInstructions
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.{SttpTestBackend, ZWordSpecBase}
import zio.*

import java.nio.file.*

class CustomerBookFileAiExtractGoldenSpec extends ZWordSpecBase {

  private given lcsJson: Lcs[Json] = new Patience[Json]

  private final case class Context(
      aiClientDataExtraction: AIClientDataExtraction,
      spreadsheetTool: SpreadsheetTool,
  )

  private val apiKey =
    sys.env.getOrElse(
      "AI_CLIENT_DATA_EXTRACTION_API_KEY",
      "",
    )

  private val assetDirectory =
    Paths.get("src/test/resources/assets-test/CustomerBookFileAiExtractGoldenSpec")

  private val goldenDirectory =
    Paths.get("src/test/resources/assets-golden/CustomerBookFileAiExtractGoldenSpec")

  private val customerBookFileAIExtractCodecFieldComplete: JsonValueCodec[CustomerBookFileAIExtract] =
    JsonCodecMaker.make[CustomerBookFileAIExtract](
      CodecMakerConfig.withTransientNone(false).withTransientEmpty(false).withTransientDefault(false)
    )

  private def loadCustomerBookFileAIExtractGolden(goldenFileName: String): CustomerBookFileAIExtract = {
    val golden = goldenDirectory.resolve(goldenFileName)

    if (!Files.exists(golden)) fail(s"Golden file is missing: ${golden.toAbsolutePath}")
    else readFromString[CustomerBookFileAIExtract](Files.readString(golden))
  }

  private def withContext[A](f: Context => Task[A]): A =
    ZIO
      .serviceWithZIO[Context](f)
      .provide(
        ZLayer.fromFunction(Context.apply),
        AIClientDataExtraction.live,
        SpreadsheetTool.live,
        ZLayer.succeed(AppName("gateway-api")),
        AIClientDataExtractionConfig.live.map(environment =>
          environment.update[AIClientDataExtractionConfig](
            _.copy(
              scheme = "https",
              host = "api.openai.com",
              port = 443,
              apiKey = apiKey,
              requestTimeout = Duration.fromSeconds(600),
            )
          )
        ),
        SttpTestBackend.live,
      )
      .zioValue

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

  private def customerBookFileAIExtractJson(customerBookFileAIExtract: CustomerBookFileAIExtract): Json =
    parse(writeToString(customerBookFileAIExtract)(using customerBookFileAIExtractCodecFieldComplete))
      .fold(parsingFailure => fail(s"Failed to parse the encoded answer: [${parsingFailure.message}]"), identity)

  private def compareWithGolden(
      customerBookFileAIExtract: CustomerBookFileAIExtract,
      customerBookFileAIExtractGolden: CustomerBookFileAIExtract,
  ): Unit = {
    val jsonPatch = diff(
      customerBookFileAIExtractJson(customerBookFileAIExtractGolden),
      customerBookFileAIExtractJson(customerBookFileAIExtract),
    )

    if (jsonPatch.ops.nonEmpty) fail(jsonPatch.asJson.spaces2)
  }

  "AIClientDataExtraction" when {
    "extractFromImage" should {
      "match the golden answer for the contact-list picture image-1.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-1.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-1.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-2.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-2.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-2.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-3.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-3.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-3.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-4.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-4.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-4.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-5.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-5.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-5.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-6.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-6.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-6.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-7.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-7.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-7.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-8.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-8.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-8.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-9.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-9.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-9.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-10.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-10.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-10.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-1.png sent with its processing message" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileProcessingMessage = "omit phone numbers, keep everything else"

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-1-omit-phone-numbers.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-1.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            Some(customerBookFileProcessingMessage),
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with no customers for the unrelated picture image-11.jpeg" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-11.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-11.jpeg")),
            SupportedMediaType.JPEG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with no customers for the unrelated picture image-12.webp" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-12.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-12.webp")),
            SupportedMediaType.WEBP,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with no customers for the unrelated picture image-13.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-13.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-13.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with no customers for the unrelated picture image-14.jpeg" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("image-14.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-14.jpeg")),
            SupportedMediaType.JPEG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }
    }

    "extractFromPdf" should {
      "match the golden answer for the typed PDF contact list pdf-1.pdf" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("pdf-1.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromPdf[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("pdf-1.pdf")),
            "pdf-1.pdf",
            AIInstructions.extractCustomersFromPdfInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the scanned handwritten PDF contact list pdf-2.pdf" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("pdf-2.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromPdf[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("pdf-2.pdf")),
            "pdf-2.pdf",
            AIInstructions.extractCustomersFromPdfInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with no customers for the unrelated PDF pdf-3.pdf" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("pdf-3.json")

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromPdf[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("pdf-3.pdf")),
            "pdf-3.pdf",
            AIInstructions.extractCustomersFromPdfInstructions,
            None,
          )

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }
    }

    "extractFromCsv" should {
      "match the golden answer for the spreadsheet spreadsheet-1.csv" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("spreadsheet-1.json")

          customerBookFileAIExtracts <- ZIO.scoped(
            aiClientDataExtraction.extractFromCsv[CustomerBookFileAIExtract](
              spreadsheetTool.convertToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-1.csv"))),
              AIInstructions.extractCustomersFromFileInstructions,
              None,
            )
          )

          customerBookFileAIExtract = customerBookFileAIExtracts.head

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the spreadsheet spreadsheet-2.xls" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("spreadsheet-2.json")

          customerBookFileAIExtracts <- ZIO.scoped(
            aiClientDataExtraction.extractFromCsv[CustomerBookFileAIExtract](
              spreadsheetTool.convertExcelToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-2.xls"))),
              AIInstructions.extractCustomersFromFileInstructions,
              None,
            )
          )

          customerBookFileAIExtract = customerBookFileAIExtracts.head

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the spreadsheet spreadsheet-3.xlsx" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("spreadsheet-3.json")

          customerBookFileAIExtracts <- ZIO.scoped(
            aiClientDataExtraction.extractFromCsv[CustomerBookFileAIExtract](
              spreadsheetTool.convertExcelToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-3.xlsx"))),
              AIInstructions.extractCustomersFromFileInstructions,
              None,
            )
          )

          customerBookFileAIExtract = customerBookFileAIExtracts.head

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with shortened notes for spreadsheet-4.csv read in several groups" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("spreadsheet-4.json")

          customerBookFileAIExtracts <- ZIO.scoped(
            aiClientDataExtraction.extractFromCsv[CustomerBookFileAIExtract](
              spreadsheetTool.convertToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-4.csv"))),
              AIInstructions.extractCustomersFromFileInstructions,
              None,
            )
          )

          customerBookFileAIExtractMerged = mergeCustomerBookFileAIExtracts(customerBookFileAIExtracts)

          noteCompactionOutput <- aiClientDataExtraction.noteCompaction(
            customerBookFileAIExtractMerged.extractionNotes,
            AIInstructions.compactCustomerBookFileNotesInstructions,
          )

          customerBookFileAIExtract = customerBookFileAIExtractMerged.copy(extractionNotes = noteCompactionOutput.notes)

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with no customers for the unrelated spreadsheet spreadsheet-5.csv" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden("spreadsheet-5.json")

          customerBookFileAIExtracts <- ZIO.scoped(
            aiClientDataExtraction.extractFromCsv[CustomerBookFileAIExtract](
              spreadsheetTool.convertToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-5.csv"))),
              AIInstructions.extractCustomersFromFileInstructions,
              None,
            )
          )

          customerBookFileAIExtract = customerBookFileAIExtracts.head

          _ = compareWithGolden(customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }
    }
  }
}
