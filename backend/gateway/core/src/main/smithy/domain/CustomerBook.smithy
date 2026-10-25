$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID
use alloy#nullable

structure CustomerEmailEntry {
    @required
    email: String
    @required
    isDefault: Boolean
}

list CustomerEmailEntries {
    member: CustomerEmailEntry
}

structure CustomerPhoneNumberEntry {
    @required
    phoneNumber: PhoneNumberRequest
    @required
    isDefault: Boolean
}

list CustomerPhoneNumberEntries {
    member: CustomerPhoneNumberEntry
}

structure CustomerAddressEntry {
    @required
    address: AddressRequest
    @required
    addressType: AddressType
}

list CustomerAddressEntries {
    member: CustomerAddressEntry
}

structure CustomerBookIndividualPostRequest {
    @required
    fullName: String
    @default([])
    emails: CustomerEmailEntries
    @default([])
    phoneNumbers: CustomerPhoneNumberEntries
    @default([])
    addresses: CustomerAddressEntries
}

structure CustomerBookIndividualPostResponse {
    @required
    customerID: UUID
    @required
    fullName: String
    @required
    emails: CustomerEmailEntries
    @required
    phoneNumbers: CustomerPhoneNumberEntries
    @required
    addresses: CustomerAddressEntries
}

structure CustomerBookIndividualPutRequest {
    fullName: String
    emails: CustomerEmailEntries
    phoneNumbers: CustomerPhoneNumberEntries
    addresses: CustomerAddressEntries
}

structure CustomerBusinessContactInsert {
    @required
    fullName: String
    role: String
    email: String
    phoneNumber: PhoneNumberRequest
}

list CustomerBusinessContactsInsert {
    member: CustomerBusinessContactInsert
}

structure CustomerBusinessContact {
    @required
    customerBusinessContactID: UUID
    @required
    fullName: String
    role: String
    email: String
    phoneNumber: PhoneNumberRequest
}

list CustomerBusinessContacts {
    member: CustomerBusinessContact
}

structure CustomerBookBusinessPostRequest {
    @required
    businessName: String
    @default([])
    emails: CustomerEmailEntries
    taxID: String
    @default([])
    phoneNumbers: CustomerPhoneNumberEntries
    @default([])
    addresses: CustomerAddressEntries
    @default([])
    customerBusinessContacts: CustomerBusinessContactsInsert
}

structure CustomerBusinessContactUpdate {
    @required
    customerBusinessContactID: UUID
    fullName: String
    @nullable
    role: String
    @nullable
    email: String
    @nullable
    phoneNumber: PhoneNumberRequest
}

list CustomerBusinessContactsUpdate {
    member: CustomerBusinessContactUpdate
}

structure CustomerBusinessContactArchive {
    @required
    customerBusinessContactID: UUID
}

list CustomerBusinessContactsArchive {
    member: CustomerBusinessContactArchive
}

structure CustomerBookBusinessPutRequest {
    businessName: String
    emails: CustomerEmailEntries
    @nullable
    taxID: String
    phoneNumbers: CustomerPhoneNumberEntries
    addresses: CustomerAddressEntries
    @default([])
    customerBusinessContactsInsert: CustomerBusinessContactsInsert
    @default([])
    customerBusinessContactsUpdate: CustomerBusinessContactsUpdate
    @default([])
    customerBusinessContactsArchive: CustomerBusinessContactsArchive
}

structure CustomerBookBusinessPostResponse {
    @required
    customerID: UUID
    @required
    businessName: String
    @required
    emails: CustomerEmailEntries
    taxID: String
    @required
    phoneNumbers: CustomerPhoneNumberEntries
    @required
    addresses: CustomerAddressEntries
    @required
    customerBusinessContacts: CustomerBusinessContacts
}

list CustomerIndividualsInsert {
    member: CustomerBookIndividualPostRequest
}

list CustomerBusinessesInsert {
    member: CustomerBookBusinessPostRequest
}

structure CustomerBookBatchPostRequest {
    @default([])
    customerBusinesses: CustomerBusinessesInsert
    @default([])
    customerIndividuals: CustomerIndividualsInsert
}

structure CustomerBookBatchPostResponse {
    @required
    customers: CustomerSummaries
}

structure CustomerBookIndividualGetResponse {
    @required
    customerID: UUID
    @required
    fullName: String
    @required
    emails: CustomerEmailEntries
    @required
    phoneNumbers: CustomerPhoneNumberEntries
    @required
    addresses: CustomerAddressEntries
}

structure CustomerBookBusinessGetResponse {
    @required
    customerID: UUID
    @required
    businessName: String
    @required
    emails: CustomerEmailEntries
    taxID: String
    @required
    phoneNumbers: CustomerPhoneNumberEntries
    @required
    addresses: CustomerAddressEntries
    @required
    customerBusinessContacts: CustomerBusinessContacts
}

list CustomerIDs {
    member: UUID
}

structure CustomerSummary {
    @required
    customerID: UUID
    @required
    name: String
    @required
    customerType: CustomerType
}

list CustomerSummaries {
    member: CustomerSummary
}

structure CustomerBookSummaryGetResponse {
    @required
    customers: CustomerSummaries
}

structure CustomerBookUniquenessGetResponse {
    @required
    isNameUnique: Boolean
}
