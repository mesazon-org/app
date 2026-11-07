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

// AI
case class CustomerPhoneNumberAIExtract(
    phoneNationalNumber: PhoneNationalNumber,
    phoneCountryCode: Option[PhoneCountryCode],
    phoneCountryCodeInferred: Boolean,
)

case class CustomerAddressAIExtract(
    addressLine1: Option[AddressLine1],
    addressLine2: Option[AddressLine2],
    city: Option[City],
    postalCode: Option[PostalCode],
    country: Option[Country],
    extractionNotes: List[String],
)

case class CustomerAddressEntryAIExtract(
    address: CustomerAddressAIExtract,
    addressType: AddressType,
)

case class CustomerBusinessContactAIExtract(
    fullName: Option[CustomerFullName],
    role: Option[CustomerBusinessContactRole],
    email: Option[CustomerEmail],
    phoneNumber: Option[CustomerPhoneNumberAIExtract],
    fullNameInferred: Boolean,
    extractionNotes: List[String],
)

case class CustomerIndividualAIExtract(
    fullName: Option[CustomerFullName],
    emails: List[CustomerEmail],
    phoneNumbers: List[CustomerPhoneNumberAIExtract],
    addresses: List[CustomerAddressEntryAIExtract],
    fullNameInferred: Boolean,
    extractionNotes: List[String],
)

case class CustomerBusinessAIExtract(
    businessName: Option[CustomerBusinessName],
    emails: List[CustomerEmail],
    taxID: Option[CustomerTaxID],
    phoneNumbers: List[CustomerPhoneNumberAIExtract],
    addresses: List[CustomerAddressEntryAIExtract],
    customerBusinessContacts: List[CustomerBusinessContactAIExtract],
    businessNameInferred: Boolean,
    extractionNotes: List[String],
)

case class CustomerBookFileAIExtract(
    entriesIdentified: Long,
    entriesProcessed: Long,
    individualCandidates: List[CustomerIndividualAIExtract],
    businessCandidates: List[CustomerBusinessAIExtract],
    emptyEntryRowIndexes: List[Long],
    unidentifiedEntryRowIndexes: List[Long],
    extractionNotes: List[String],
)

// Customer Book File

case class CustomerPhoneNumberCandidate(
    phoneNationalNumber: PhoneNationalNumber,
    phoneCountryCode: Option[PhoneCountryCode],
)

case class CustomerPhoneNumberEntryCandidate(
    phoneNumber: CustomerPhoneNumberCandidate,
    isDefault: Boolean,
)

case class CustomerBusinessContactCandidate(
    fullName: Option[CustomerFullName],
    role: Option[CustomerBusinessContactRole],
    email: Option[CustomerEmail],
    phoneNumber: Option[CustomerPhoneNumberCandidate],
)

case class CustomerIndividualCandidate(
    fullName: Option[CustomerFullName],
    emails: List[CustomerEmailEntry],
    phoneNumbers: List[CustomerPhoneNumberEntryCandidate],
    addresses: List[CustomerAddressEntry],
    inferredFields: List[FieldInfo],
    missingFields: List[FieldInfo],
    duplicateFields: List[FieldInfo],
    needsAttention: Boolean,
    extractionNotes: List[String],
)

case class CustomerBusinessCandidate(
    businessName: Option[CustomerBusinessName],
    emails: List[CustomerEmailEntry],
    taxID: Option[CustomerTaxID],
    phoneNumbers: List[CustomerPhoneNumberEntryCandidate],
    addresses: List[CustomerAddressEntry],
    customerBusinessContacts: List[CustomerBusinessContactCandidate],
    inferredFields: List[FieldInfo],
    missingFields: List[FieldInfo],
    duplicateFields: List[FieldInfo],
    needsAttention: Boolean,
    extractionNotes: List[String],
)

case class CustomerBookFileAiExtractPostResponse(
    individualCandidates: List[CustomerIndividualCandidate],
    businessCandidates: List[CustomerBusinessCandidate],
    infoNotes: List[String],
    extractionNotes: List[String],
)
