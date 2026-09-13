package io.mesazon.domain.gateway

// Contact points (each carries an isDefault flag)

case class CustomerEmailEntryRequest(
    email: CustomerEmail,
    isDefault: Boolean,
)

case class CustomerPhoneNumberEntryRequest(
    phoneNumber: CustomerPhoneNumber,
    isDefault: Boolean,
)

// Individuals

case class InsertCustomerIndividualPostRequest(
    fullName: CustomerFullName,
    emails: List[CustomerEmailEntryRequest],
    phoneNumbers: List[CustomerPhoneNumberEntryRequest],
    addressLine1: Option[CustomerAddressLine1],
    addressLine2: Option[CustomerAddressLine2],
    city: Option[CustomerCity],
    postalCode: Option[CustomerPostalCode],
    country: Option[CustomerCountry],
)

case class InsertCustomerIndividualsPostRequest(
    customerIndividuals: List[InsertCustomerIndividualPostRequest]
)

case class UpdateCustomerIndividualPutRequest(
    customerID: CustomerID,
    fullName: Option[CustomerFullName],
    emails: List[CustomerEmailEntryRequest],
    phoneNumbers: List[CustomerPhoneNumberEntryRequest],
    addressLine1: Option[CustomerAddressLine1],
    addressLine2: Option[CustomerAddressLine2],
    city: Option[CustomerCity],
    postalCode: Option[CustomerPostalCode],
    country: Option[CustomerCountry],
)

// Business contacts

case class InsertCustomerBusinessContact(
    fullName: CustomerFullName,
    role: Option[CustomerBusinessContactRole],
    email: Option[CustomerEmail],
    phoneNumber: Option[CustomerPhoneNumber],
)

case class AddCustomerBusinessContact(
    fullName: CustomerFullName,
    role: Option[CustomerBusinessContactRole],
    email: Option[CustomerEmail],
    phoneNumber: Option[CustomerPhoneNumber],
)

case class AddCustomerBusinessContactsPutRequest(
    customerID: CustomerID,
    customerBusinessContacts: List[AddCustomerBusinessContact],
)

// Businesses

case class InsertCustomerBusinessPostRequest(
    businessName: CustomerBusinessName,
    emails: List[CustomerEmailEntryRequest],
    taxID: Option[CustomerTaxID],
    phoneNumbers: List[CustomerPhoneNumberEntryRequest],
    addressLine1: Option[CustomerAddressLine1],
    addressLine2: Option[CustomerAddressLine2],
    city: Option[CustomerCity],
    postalCode: Option[CustomerPostalCode],
    country: Option[CustomerCountry],
    customerBusinessContacts: List[InsertCustomerBusinessContact],
)

case class InsertCustomerBusinessesPostRequest(
    customerBusinesses: List[InsertCustomerBusinessPostRequest]
)

case class UpdateCustomerBusinessPutRequest(
    customerID: CustomerID,
    businessName: Option[CustomerBusinessName],
    emails: List[CustomerEmailEntryRequest],
    taxID: Option[CustomerTaxID],
    phoneNumbers: List[CustomerPhoneNumberEntryRequest],
    addressLine1: Option[CustomerAddressLine1],
    addressLine2: Option[CustomerAddressLine2],
    city: Option[CustomerCity],
    postalCode: Option[CustomerPostalCode],
    country: Option[CustomerCountry],
)

// Combined

case class InsertCustomersPostRequest(
    customerBusinesses: List[InsertCustomerBusinessPostRequest],
    customerIndividuals: List[InsertCustomerIndividualPostRequest],
)

// Photo extraction

case class ExtractCustomerEmailEntry(
    email: CustomerEmail,
    isDefault: Boolean,
)

case class ExtractCustomerPhoneNumber(
    phoneNationalNumber: PhoneNationalNumber,
    phoneCountryCode: PhoneCountryCode,
)

case class ExtractCustomerPhoneNumberEntry(
    phoneNumber: ExtractCustomerPhoneNumber,
    isDefault: Boolean,
)

case class ExtractCustomerIndividual(
    fullName: CustomerFullName,
    emails: List[ExtractCustomerEmailEntry],
    phoneNumbers: List[ExtractCustomerPhoneNumberEntry],
    addressLine1: Option[CustomerAddressLine1],
    addressLine2: Option[CustomerAddressLine2],
    city: Option[CustomerCity],
    postalCode: Option[CustomerPostalCode],
    country: Option[CustomerCountry],
)

case class ExtractCustomerBusinessContact(
    fullName: CustomerFullName,
    role: Option[CustomerBusinessContactRole],
    email: Option[CustomerEmail],
    phoneNumber: Option[ExtractCustomerPhoneNumber],
)

case class ExtractCustomerBusiness(
    businessName: CustomerBusinessName,
    emails: List[ExtractCustomerEmailEntry],
    taxID: Option[CustomerTaxID],
    phoneNumbers: List[ExtractCustomerPhoneNumberEntry],
    addressLine1: Option[CustomerAddressLine1],
    addressLine2: Option[CustomerAddressLine2],
    city: Option[CustomerCity],
    postalCode: Option[CustomerPostalCode],
    country: Option[CustomerCountry],
    customerBusinessContacts: List[ExtractCustomerBusinessContact],
)

case class ExtractCustomerIndividualData(
    candidate: ExtractCustomerIndividual,
    isDuplicate: Boolean,
    extractionNotes: Option[String],
)

case class ExtractCustomerBusinessData(
    candidate: ExtractCustomerBusiness,
    isDuplicate: Boolean,
    extractionNotes: Option[String],
)

case class ExtractCustomersResponse(
    entriesIdentified: Long,
    entriesProcessed: Long,
    customerIndividualCandidates: List[ExtractCustomerIndividualData],
    customerBusinessCandidates: List[ExtractCustomerBusinessData],
    unidentifiedEntriesSummary: Option[String],
)
