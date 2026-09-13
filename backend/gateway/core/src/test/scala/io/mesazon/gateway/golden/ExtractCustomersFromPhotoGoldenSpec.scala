package io.mesazon.gateway.golden

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.AIClient
import io.mesazon.gateway.config.AIClientConfig
import io.mesazon.gateway.json.ai.given
import io.mesazon.gateway.json.tapir.extractCustomersResponseCodec
import io.mesazon.gateway.service.FileService
import io.mesazon.gateway.utils.FileByteStreamScanned
import io.mesazon.testkit.base.ZWordSpecBase
import sttp.client4.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.stream.ZStream

/** Manual-only check against the real OpenAI API: sends each sample photo in
  * `assets/contact-book-test-photo-1.png`..`-10.png` through the real `AIClient` and asserts the complete captured
  * golden response for that photo across different languages, column namings, and source types.
  *
  * Never calls out for real in CI: `apiKey` ships empty, so every case is canceled rather than hitting the real API
  * with a blank key. To run for real, fill in a real key below and invoke this spec directly:
  * {{{
  * sbt "gateway-core/testOnly io.mesazon.gateway.golden.ExtractCustomersFromPhotoGoldenSpec"
  * }}}
  */
class ExtractCustomersFromPhotoGoldenSpec extends ZWordSpecBase {

  private val apiKey =
    ""

  private def buildAIClient: AIClient = ZIO
    .service[AIClient]
    .provide(
      AIClient.live,
      ZLayer.succeed(
        AIClientConfig(
          scheme = "https",
          host = "api.openai.com",
          port = 443,
          apiKey = apiKey,
          requestTimeout = Duration.fromSeconds(60),
          sendMaxRetries = 2,
          sendRetryDelay = Duration.fromSeconds(1),
        )
      ),
      HttpClientZioBackend.layer(),
    )
    .zioValue

  private val extractCustomersResponseExpectedByPhotoNumber: Map[Int, ExtractCustomersResponse] = Map(
    1 -> ExtractCustomersResponse(
      entriesIdentified = 5L,
      entriesProcessed = 4L,
      customerIndividualCandidates = List(
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Daniel Mercer"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("dan.mercer@gmail.com"), isDefault = true)),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("7700900341"), PhoneCountryCode.assume("+44")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("18 Pine Rd")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Bristol")),
            postalCode = Some(CustomerPostalCode.assume("BS1 4DJ")),
            country = Some(CustomerCountry.assume("UK")),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Sofia Lang"),
            emails = List.empty,
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("7718334921"), PhoneCountryCode.assume("+44")),
                isDefault = true,
              )
            ),
            addressLine1 = None,
            addressLine2 = None,
            city = None,
            postalCode = None,
            country = None,
          ),
          isDuplicate = false,
          extractionNotes = Some("No email or address is listed."),
        ),
      ),
      customerBusinessCandidates = List(
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("Evia Flowers Ltd"),
            emails =
              List(ExtractCustomerEmailEntry(CustomerEmail.assume("orders@eviaflowers.co.uk"), isDefault = true)),
            taxID = Some(CustomerTaxID.assume("GB381552122")),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("2079462013"), PhoneCountryCode.assume("+44")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("7 Market Lane")),
            addressLine2 = Some(CustomerAddressLine2.assume("Unit 2")),
            city = Some(CustomerCity.assume("London")),
            postalCode = Some(CustomerPostalCode.assume("E8 3RT")),
            country = Some(CustomerCountry.assume("UK")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Maria Cole"),
                role = Some(CustomerBusinessContactRole.assume("Buyer")),
                email = None,
                phoneNumber = None,
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("Aegean Repairs"),
            emails = List.empty,
            taxID = None,
            phoneNumbers = List.empty,
            addressLine1 = Some(CustomerAddressLine1.assume("22 Dock Street")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Southampton")),
            postalCode = Some(CustomerPostalCode.assume("SO14 3JQ")),
            country = Some(CustomerCountry.assume("UK")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("A. Reed"),
                role = None,
                email = None,
                phoneNumber = None,
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = Some("The phone number is smudged and no email is listed."),
        ),
      ),
      unidentifiedEntriesSummary =
        Some("The crossed-out name and smudged phone number in entry 5 at the bottom could not be read."),
    ),
    2 -> ExtractCustomersResponse(
      entriesIdentified = 5L,
      entriesProcessed = 5L,
      customerIndividualCandidates = List(
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Γιώργος Παπαδόπουλος"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("giorgos.pap@gmail.com"), isDefault = true)),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("99124567"), PhoneCountryCode.assume("+357")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("Λεωφ. Αθηνάς 14")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Αθήνα")),
            postalCode = Some(CustomerPostalCode.assume("10551")),
            country = Some(CustomerCountry.assume("Ελλάδα")),
          ),
          isDuplicate = false,
          extractionNotes = Some("The phone country code is inferred from its eight-digit format."),
        ),
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Μαρία Κωνσταντίνου"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("maria.kon@gmail.com"), isDefault = true)),
            phoneNumbers = List.empty,
            addressLine1 = None,
            addressLine2 = None,
            city = None,
            postalCode = None,
            country = None,
          ),
          isDuplicate = false,
          extractionNotes = Some("No phone number or address is shown."),
        ),
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Νίκος Ανδρέου"),
            emails = List.empty,
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("96334221"), PhoneCountryCode.assume("+357")),
                isDefault = true,
              )
            ),
            addressLine1 = None,
            addressLine2 = None,
            city = Some(CustomerCity.assume("Θεσσαλονίκη")),
            postalCode = None,
            country = Some(CustomerCountry.assume("Ελλάδα")),
          ),
          isDuplicate = false,
          extractionNotes =
            Some("No email or street address is shown, and the phone country is inferred from its format."),
        ),
      ),
      customerBusinessCandidates = List(
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("Αφοί Νικολάου ΟΕ"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("info@nikolaoutools.gr"), isDefault = true)),
            taxID = Some(CustomerTaxID.assume("094582761")),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("2106654321"), PhoneCountryCode.assume("+30")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("Βιομηχανική Περιοχή, Οδός 3")),
            addressLine2 = Some(CustomerAddressLine2.assume("Μονάδα Β")),
            city = Some(CustomerCity.assume("Πάτρα")),
            postalCode = Some(CustomerPostalCode.assume("26332")),
            country = Some(CustomerCountry.assume("Ελλάδα")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Ελένη Νικολάου"),
                role = None,
                email = None,
                phoneNumber = None,
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = Some("No role or direct contact details are shown for Ελένη Νικολάου."),
        ),
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("Κυπριακή Ψύξη Ltd"),
            emails = List.empty,
            taxID = None,
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("22761478"), PhoneCountryCode.assume("+357")),
                isDefault = true,
              )
            ),
            addressLine1 = None,
            addressLine2 = None,
            city = Some(CustomerCity.assume("Λευκωσία")),
            postalCode = None,
            country = Some(CustomerCountry.assume("Κύπρος")),
            customerBusinessContacts = List.empty,
          ),
          isDuplicate = false,
          extractionNotes = Some("No email, tax ID, street address, or named contact is shown."),
        ),
      ),
      unidentifiedEntriesSummary = None,
    ),
    3 -> ExtractCustomersResponse(
      entriesIdentified = 5L,
      entriesProcessed = 4L,
      customerIndividualCandidates = List(
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Ирина Смирнова"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("irina.smirnova@mail.ru"), isDefault = true)),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("9214431872"), PhoneCountryCode.assume("+7")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("Невский пр., 23")),
            addressLine2 = Some(CustomerAddressLine2.assume("кв. 4")),
            city = Some(CustomerCity.assume("Санкт-Петербург")),
            postalCode = Some(CustomerPostalCode.assume("191186")),
            country = Some(CustomerCountry.assume("Россия")),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Марина Волкова"),
            emails = List.empty,
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("9206673321"), PhoneCountryCode.assume("+7")),
                isDefault = true,
              )
            ),
            addressLine1 = None,
            addressLine2 = None,
            city = None,
            postalCode = None,
            country = None,
          ),
          isDuplicate = false,
          extractionNotes = Some("Email and address are not shown."),
        ),
      ),
      customerBusinessCandidates = List(
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("ООО Балтик Сервис"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("office@baltserv.ru"), isDefault = true)),
            taxID = Some(CustomerTaxID.assume("7812459031")),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("8125554219"), PhoneCountryCode.assume("+7")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("ул. Моисеенко, 11")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Санкт-Петербург")),
            postalCode = None,
            country = Some(CustomerCountry.assume("Россия")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Олег Титов"),
                role = Some(CustomerBusinessContactRole.assume("закупки")),
                email = None,
                phoneNumber = None,
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = Some("The postal code and the contact's direct details are not shown."),
        ),
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("ИП Соколова"),
            emails = List.empty,
            taxID = None,
            phoneNumbers = List.empty,
            addressLine1 = None,
            addressLine2 = None,
            city = None,
            postalCode = None,
            country = None,
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Светлана"),
                role = Some(CustomerBusinessContactRole.assume("контакт")),
                email = None,
                phoneNumber = None,
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = Some("The phone number is obscured, and no email or address is shown."),
        ),
      ),
      unidentifiedEntriesSummary =
        Some("Entry 5 at the bottom has a crossed-out, unreadable name and an incomplete phone number."),
    ),
    4 -> ExtractCustomersResponse(
      entriesIdentified = 1L,
      entriesProcessed = 1L,
      customerIndividualCandidates = List.empty,
      customerBusinessCandidates = List(
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("Northbridge Catering Supplies"),
            emails =
              List(ExtractCustomerEmailEntry(CustomerEmail.assume("emma@northbridgecatering.com"), isDefault = true)),
            taxID = Some(CustomerTaxID.assume("GB492701814")),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("1134962081"), PhoneCountryCode.assume("+44")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("15 Riverside Park")),
            addressLine2 = Some(CustomerAddressLine2.assume("Unit B")),
            city = Some(CustomerCity.assume("Leeds")),
            postalCode = Some(CustomerPostalCode.assume("LS10 1AB")),
            country = Some(CustomerCountry.assume("United Kingdom")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Emma Walsh"),
                role = Some(CustomerBusinessContactRole.assume("Purchasing Manager")),
                email = Some(CustomerEmail.assume("emma@northbridgecatering.com")),
                phoneNumber = Some(
                  ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("1134962081"), PhoneCountryCode.assume("+44"))
                ),
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = None,
        )
      ),
      unidentifiedEntriesSummary = None,
    ),
    5 -> ExtractCustomersResponse(
      entriesIdentified = 1L,
      entriesProcessed = 1L,
      customerIndividualCandidates = List.empty,
      customerBusinessCandidates = List(
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("ООО Балтик Сервис"),
            emails =
              List(ExtractCustomerEmailEntry(CustomerEmail.assume("i.smirnova@baltservice.ru"), isDefault = true)),
            taxID = Some(CustomerTaxID.assume("7812465098")),
            phoneNumbers = List.empty,
            addressLine1 = Some(CustomerAddressLine1.assume("Лиговский пр., 52, офис 14")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Санкт-Петербург")),
            postalCode = Some(CustomerPostalCode.assume("191040")),
            country = Some(CustomerCountry.assume("Россия")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Ирина Смирнова"),
                role = Some(CustomerBusinessContactRole.assume("менеджер по закупкам")),
                email = Some(CustomerEmail.assume("i.smirnova@baltservice.ru")),
                phoneNumber = None,
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = Some("The last digit of the phone number is obscured, so the phone number was omitted."),
        )
      ),
      unidentifiedEntriesSummary = None,
    ),
    6 -> ExtractCustomersResponse(
      entriesIdentified = 5L,
      entriesProcessed = 5L,
      customerIndividualCandidates = List(
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Δημήτρης Λάμπρου"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("d.lamprou@mail.gr"), isDefault = true)),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("6973112305"), PhoneCountryCode.assume("+30")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("Αγ. Νικολάου 12")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Πάτρα")),
            postalCode = Some(CustomerPostalCode.assume("26221")),
            country = Some(CustomerCountry.assume("Ελλάδα")),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Εύα Στυλιανού"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("eva.stylianou@gmail.com"), isDefault = true)),
            phoneNumbers = List.empty,
            addressLine1 = Some(CustomerAddressLine1.assume("Κύπρου 5")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Αθήνα")),
            postalCode = Some(CustomerPostalCode.assume("11527")),
            country = Some(CustomerCountry.assume("Ελλάδα")),
          ),
          isDuplicate = false,
          extractionNotes = Some("No phone number is shown."),
        ),
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Νίκος Παπαδόπουλος"),
            emails = List.empty,
            phoneNumbers = List.empty,
            addressLine1 = None,
            addressLine2 = None,
            city = None,
            postalCode = None,
            country = None,
          ),
          isDuplicate = false,
          extractionNotes = Some("The email address is incomplete, and no phone number or address is shown."),
        ),
      ),
      customerBusinessCandidates = List(
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("Κρητικά Τρόφιμα ΕΠΕ"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("anna@kritika-trofima.gr"), isDefault = true)),
            taxID = None,
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("2810334455"), PhoneCountryCode.assume("+30")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("ΒΙ.ΠΕ. Ηρακλείου, Οδός Γ'")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Ηράκλειο")),
            postalCode = Some(CustomerPostalCode.assume("71601")),
            country = Some(CustomerCountry.assume("Ελλάδα")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Άννα Πετράκη"),
                role = None,
                email = Some(CustomerEmail.assume("anna@kritika-trofima.gr")),
                phoneNumber = Some(
                  ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("2810334455"), PhoneCountryCode.assume("+30"))
                ),
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("Τεχνική Δομή ΑΕ"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("k.maris@texniki-domi.gr"), isDefault = true)),
            taxID = None,
            phoneNumbers = List.empty,
            addressLine1 = Some(CustomerAddressLine1.assume("Λεωφ. Μεσογείων 278")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Χαλάνδρι")),
            postalCode = Some(CustomerPostalCode.assume("15232")),
            country = Some(CustomerCountry.assume("Ελλάδα")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Κώστας Μαρής"),
                role = None,
                email = Some(CustomerEmail.assume("k.maris@texniki-domi.gr")),
                phoneNumber = None,
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = Some("The phone number is smudged and cannot be read reliably."),
        ),
      ),
      unidentifiedEntriesSummary = None,
    ),
    7 -> ExtractCustomersResponse(
      entriesIdentified = 5L,
      entriesProcessed = 4L,
      customerIndividualCandidates = List(
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Алексей Воронов"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("voronov.a@mail.ru"), isDefault = true)),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("9213341256"), PhoneCountryCode.assume("+7")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("ул. Лесная, д. 10, кв. 25")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Санкт-Петербург")),
            postalCode = None,
            country = Some(CustomerCountry.assume("Россия")),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Ольга Романова"),
            emails = List.empty,
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("9052213344"), PhoneCountryCode.assume("+7")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("ул. Чистопольская, д. 8")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Казань")),
            postalCode = None,
            country = Some(CustomerCountry.assume("Россия")),
          ),
          isDuplicate = false,
          extractionNotes = Some("Электронная почта не указана."),
        ),
      ),
      customerBusinessCandidates = List(
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("ООО СеверТрейд"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("mk@severtrade.ru"), isDefault = true)),
            taxID = Some(CustomerTaxID.assume("5190087654")),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("8124487711"), PhoneCountryCode.assume("+7")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("пр. Ленина, д. 45")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Мурманск")),
            postalCode = None,
            country = Some(CustomerCountry.assume("Россия")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Марина Кузнецова"),
                role = None,
                email = Some(CustomerEmail.assume("mk@severtrade.ru")),
                phoneNumber = Some(
                  ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("8124487711"), PhoneCountryCode.assume("+7"))
                ),
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("ИП Михайлов"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("ip-mihailov@bk.ru"), isDefault = true)),
            taxID = Some(CustomerTaxID.assume("540712345670")),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("9637770018"), PhoneCountryCode.assume("+7")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("ул. Кирова, д. 121")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Новосибирск")),
            postalCode = None,
            country = Some(CustomerCountry.assume("Россия")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Сергей Михайлов"),
                role = None,
                email = Some(CustomerEmail.assume("ip-mihailov@bk.ru")),
                phoneNumber = Some(
                  ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("9637770018"), PhoneCountryCode.assume("+7"))
                ),
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
      ),
      unidentifiedEntriesSummary = Some("В нижней строке таблицы имя замазано, а телефон указан лишь частично."),
    ),
    8 -> ExtractCustomersResponse(
      entriesIdentified = 5L,
      entriesProcessed = 4L,
      customerIndividualCandidates = List(
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Olivia Anne Carter"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("olivia.carter@mail.com"), isDefault = true)),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("4165550198"), PhoneCountryCode.assume("+1")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("123 Maple Ave")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Toronto")),
            postalCode = Some(CustomerPostalCode.assume("M4B 1C3")),
            country = Some(CustomerCountry.assume("Canada")),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Leo Martin"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("leo.martin@email.com"), isDefault = true)),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("2079460321"), PhoneCountryCode.assume("+44")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("Flat 4, 22 Kingsway")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("London")),
            postalCode = Some(CustomerPostalCode.assume("WC2B 6LE")),
            country = Some(CustomerCountry.assume("UK")),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
      ),
      customerBusinessCandidates = List(
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("Green Harbor Logistics Ltd"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("info@greenharbor.ca"), isDefault = true)),
            taxID = None,
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("6045550144"), PhoneCountryCode.assume("+1")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("200-1188 West Pender St")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Vancouver")),
            postalCode = Some(CustomerPostalCode.assume("V6E 4A2")),
            country = Some(CustomerCountry.assume("Canada")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Ben Walsh"),
                role = None,
                email = None,
                phoneNumber = None,
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = Some("No role, email, or phone number is shown for the business contact."),
        ),
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("Orion Print House"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("hello@orionprint.co.uk"), isDefault = true)),
            taxID = None,
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("1615550177"), PhoneCountryCode.assume("+44")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("Unit 3, 56 Barton Rd")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Manchester")),
            postalCode = Some(CustomerPostalCode.assume("M3 7JL")),
            country = Some(CustomerCountry.assume("UK")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Sara Ng"),
                role = None,
                email = None,
                phoneNumber = None,
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = Some("No role, email, or phone number is shown for the business contact."),
        ),
      ),
      unidentifiedEntriesSummary = Some(
        "The last populated row has contact details and an Austin address, but no person or business name is visible."
      ),
    ),
    9 -> ExtractCustomersResponse(
      entriesIdentified = 5L,
      entriesProcessed = 5L,
      customerIndividualCandidates = List(
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Ελένη Παναγιώτου"),
            emails =
              List(ExtractCustomerEmailEntry(CustomerEmail.assume("eleni.panagiotou@gmail.com"), isDefault = true)),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("6945123456"), PhoneCountryCode.assume("+30")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("Λεωφ. Κηφισίας 124")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Αθήνα")),
            postalCode = Some(CustomerPostalCode.assume("11526")),
            country = Some(CustomerCountry.assume("Greece")),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Σπύρος Θεοδώρου"),
            emails =
              List(ExtractCustomerEmailEntry(CustomerEmail.assume("spyros.theodorou@hotmail.gr"), isDefault = true)),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("6932456789"), PhoneCountryCode.assume("+30")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("Αγίου Δημητρίου 8")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Πάτρα")),
            postalCode = Some(CustomerPostalCode.assume("26223")),
            country = Some(CustomerCountry.assume("Greece")),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Μαρία Κωνσταντίνου"),
            emails = List.empty,
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("6971889920"), PhoneCountryCode.assume("+30")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("Πλατεία Ναυαρίνου 5")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Καλαμάτα")),
            postalCode = Some(CustomerPostalCode.assume("24100")),
            country = Some(CustomerCountry.assume("Greece")),
          ),
          isDuplicate = false,
          extractionNotes = Some("The email domain is obscured by glare and could not be read."),
        ),
      ),
      customerBusinessCandidates = List(
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("Αιγαίο Εξοπλισμοί ΕΠΕ"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("info@aigaoequip.gr"), isDefault = true)),
            taxID = Some(CustomerTaxID.assume("998877663")),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("2109876543"), PhoneCountryCode.assume("+30")),
                isDefault = true,
              )
            ),
            addressLine1 = Some(CustomerAddressLine1.assume("Βιομηχανική Περιοχή Ηρακλείου")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Ηράκλειο")),
            postalCode = Some(CustomerPostalCode.assume("71601")),
            country = Some(CustomerCountry.assume("Greece")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Μάριος Ιωάννου"),
                role = None,
                email = None,
                phoneNumber = None,
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("Κρήτη Τεχνική ΑΕ"),
            emails =
              List(ExtractCustomerEmailEntry(CustomerEmail.assume("contact@kriti-texniki.gr"), isDefault = true)),
            taxID = Some(CustomerTaxID.assume("777665544")),
            phoneNumbers = List.empty,
            addressLine1 = Some(CustomerAddressLine1.assume("3ο χλμ. Ε.Ο. Χανίων – Ρεθύμνου")),
            addressLine2 = None,
            city = Some(CustomerCity.assume("Χανιά")),
            postalCode = Some(CustomerPostalCode.assume("73100")),
            country = Some(CustomerCountry.assume("Greece")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Γιώργος Κτιστάκης"),
                role = None,
                email = None,
                phoneNumber = None,
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = Some("No phone number is shown."),
        ),
      ),
      unidentifiedEntriesSummary = None,
    ),
    10 -> ExtractCustomersResponse(
      entriesIdentified = 6L,
      entriesProcessed = 6L,
      customerIndividualCandidates = List(
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Наталья Орлова"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("n.orlova@mail.ru"), isDefault = true)),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("9161234567"), PhoneCountryCode.assume("+7")),
                isDefault = true,
              )
            ),
            addressLine1 = None,
            addressLine2 = None,
            city = None,
            postalCode = None,
            country = Some(CustomerCountry.assume("Россия")),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Сергей Павлов"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("s.pavlov@inbox.ru"), isDefault = true)),
            phoneNumbers = List.empty,
            addressLine1 = None,
            addressLine2 = None,
            city = None,
            postalCode = None,
            country = Some(CustomerCountry.assume("Россия")),
          ),
          isDuplicate = false,
          extractionNotes = Some("Телефон не указан."),
        ),
        ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Екатерина Смирнова"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("e.smirnova@list.ru"), isDefault = true)),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("9053342144"), PhoneCountryCode.assume("+7")),
                isDefault = true,
              )
            ),
            addressLine1 = None,
            addressLine2 = None,
            city = None,
            postalCode = None,
            country = Some(CustomerCountry.assume("Россия")),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
      ),
      customerBusinessCandidates = List(
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("ООО Ладога Маркет"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("info@ladoga-market.ru"), isDefault = true)),
            taxID = None,
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("8126002211"), PhoneCountryCode.assume("+7")),
                isDefault = true,
              )
            ),
            addressLine1 = None,
            addressLine2 = None,
            city = None,
            postalCode = None,
            country = Some(CustomerCountry.assume("Россия")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Игорь Беляев"),
                role = Some(CustomerBusinessContactRole.assume("директор")),
                email = None,
                phoneNumber = Some(
                  ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("8126002211"), PhoneCountryCode.assume("+7"))
                ),
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = None,
        ),
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("ИП Захарова"),
            emails = List.empty,
            taxID = None,
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("9037789012"), PhoneCountryCode.assume("+7")),
                isDefault = true,
              )
            ),
            addressLine1 = None,
            addressLine2 = None,
            city = None,
            postalCode = None,
            country = Some(CustomerCountry.assume("Россия")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Анна Захарова"),
                role = Some(CustomerBusinessContactRole.assume("владелец")),
                email = None,
                phoneNumber = Some(
                  ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("9037789012"), PhoneCountryCode.assume("+7"))
                ),
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = Some("Email не указан."),
        ),
        ExtractCustomerBusinessData(
          candidate = ExtractCustomerBusiness(
            businessName = CustomerBusinessName.assume("ТехноСервис"),
            emails = List(ExtractCustomerEmailEntry(CustomerEmail.assume("a.kuznetsov@techno.ru"), isDefault = true)),
            taxID = None,
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("4957781032"), PhoneCountryCode.assume("+7")),
                isDefault = true,
              )
            ),
            addressLine1 = None,
            addressLine2 = None,
            city = None,
            postalCode = None,
            country = Some(CustomerCountry.assume("Россия")),
            customerBusinessContacts = List(
              ExtractCustomerBusinessContact(
                fullName = CustomerFullName.assume("Алексей Кузнецов"),
                role = Some(CustomerBusinessContactRole.assume("менеджер")),
                email = Some(CustomerEmail.assume("a.kuznetsov@techno.ru")),
                phoneNumber = Some(
                  ExtractCustomerPhoneNumber(PhoneNationalNumber.assume("4957781032"), PhoneCountryCode.assume("+7"))
                ),
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = Some("Последние цифры телефона частично закрыты бликом."),
        ),
      ),
      unidentifiedEntriesSummary = None,
    ),
  )

  "AIClient" when {
    "extractFromImage" should {
      (1 to 10).foreach { photoNumber =>
        s"extract customers from contact-book-test-photo-$photoNumber.png" in {
          assume(
            apiKey.nonEmpty,
            "Fill in a real OpenAI API key in ExtractCustomersFromPhotoGoldenSpec.apiKey to run this manually",
          )

          val aiClient = buildAIClient

          val customerBookPhotoByteStreamScanned =
            FileByteStreamScanned(ZStream.fromResource(s"assets/contact-book-test-photo-$photoNumber.png"))

          val extractCustomersResponse = aiClient
            .extractFromImage[ExtractCustomersResponse](
              customerBookPhotoByteStreamScanned,
              SupportedMediaType.PNG,
              FileService.extractCustomersFromPhotoInstructions,
            )
            .zioValue

          info(s"contact-book-test-photo-$photoNumber.png => $extractCustomersResponse")

          extractCustomersResponse shouldBe extractCustomersResponseExpectedByPhotoNumber(photoNumber)
        }
      }
    }
  }
}
