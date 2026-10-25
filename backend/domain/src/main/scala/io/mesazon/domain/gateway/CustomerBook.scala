package io.mesazon.domain.gateway

// Contact points (each carries an isDefault flag)

case class CustomerEmailEntry(
    email: CustomerEmail,
    isDefault: Boolean,
)

case class CustomerPhoneNumberEntry(
    phoneNumber: CustomerPhoneNumber,
    isDefault: Boolean,
)

// Individuals

case class CustomerBookIndividualPostRequest(
    fullName: CustomerFullName,
    emails: List[CustomerEmailEntry],
    phoneNumbers: List[CustomerPhoneNumberEntry],
    addresses: List[CustomerAddressEntry],
)

case class CustomerBookIndividualPutRequest(
    fullName: Option[CustomerFullName],
    emails: Option[List[CustomerEmailEntry]],
    phoneNumbers: Option[List[CustomerPhoneNumberEntry]],
    addresses: Option[List[CustomerAddressEntry]],
)

// Business contacts

case class CustomerBusinessContactInsert(
    fullName: CustomerFullName,
    role: Option[CustomerBusinessContactRole],
    email: Option[CustomerEmail],
    phoneNumber: Option[CustomerPhoneNumber],
)

case class CustomerBusinessContactUpdate(
    customerBusinessContactID: CustomerBusinessContactID,
    fullName: Option[CustomerFullName],
    role: Option[Option[CustomerBusinessContactRole]],
    email: Option[Option[CustomerEmail]],
    phoneNumber: Option[Option[CustomerPhoneNumber]],
)

case class CustomerBusinessContactArchive(
    customerBusinessContactID: CustomerBusinessContactID
)

// Businesses

case class CustomerBookBusinessPostRequest(
    businessName: CustomerBusinessName,
    emails: List[CustomerEmailEntry],
    taxID: Option[CustomerTaxID],
    phoneNumbers: List[CustomerPhoneNumberEntry],
    addresses: List[CustomerAddressEntry],
    customerBusinessContacts: List[CustomerBusinessContactInsert],
)

case class CustomerBookBusinessPutRequest(
    businessName: Option[CustomerBusinessName],
    emails: Option[List[CustomerEmailEntry]],
    taxID: Option[Option[CustomerTaxID]],
    phoneNumbers: Option[List[CustomerPhoneNumberEntry]],
    addresses: Option[List[CustomerAddressEntry]],
    customerBusinessContactsInsert: List[CustomerBusinessContactInsert],
    customerBusinessContactsUpdate: List[CustomerBusinessContactUpdate],
    customerBusinessContactsArchive: List[CustomerBusinessContactArchive],
)

// Combined

case class CustomerBookBatchPostRequest(
    customerBusinesses: List[CustomerBookBusinessPostRequest],
    customerIndividuals: List[CustomerBookIndividualPostRequest],
)

case class CustomerBookUniquenessGetRequest(
    name: CustomerName,
    customerType: CustomerType,
    customerID: Option[CustomerID],
)

// File extraction

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
    addresses: List[CustomerAddressEntry],
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
    addresses: List[CustomerAddressEntry],
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

case class ExtractCustomersPostResponse(
    entriesIdentified: Long,
    entriesProcessed: Long,
    customerIndividualCandidates: List[ExtractCustomerIndividualData],
    customerBusinessCandidates: List[ExtractCustomerBusinessData],
    emptyEntryRows: List[Long],
    unidentifiedEntryRows: List[Long],
    unidentifiedEntriesNotes: Option[String],
)
