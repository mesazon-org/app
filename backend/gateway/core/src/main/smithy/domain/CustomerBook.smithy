$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID

structure CustomerEmailEntryRequest {
    @required
    email: String
    @required
    isDefault: Boolean
}

list CustomerEmailEntryRequests {
    member: CustomerEmailEntryRequest
}

structure CustomerPhoneNumberEntryRequest {
    @required
    phoneNumber: PhoneNumberRequest
    @required
    isDefault: Boolean
}

list CustomerPhoneNumberEntryRequests {
    member: CustomerPhoneNumberEntryRequest
}

structure InsertCustomerIndividualPostRequest {
    @required
    fullName: String
    @default([])
    emails: CustomerEmailEntryRequests
    @default([])
    phoneNumbers: CustomerPhoneNumberEntryRequests
    addressLine1: String
    addressLine2: String
    city: String
    postalCode: String
    country: String
}

list InsertCustomerIndividuals {
    member: InsertCustomerIndividualPostRequest
}

structure InsertCustomerIndividualsPostRequest {
    @default([])
    customerIndividuals: InsertCustomerIndividuals
}

structure InsertCustomerBusinessContact {
    @required
    fullName: String
    role: String
    email: String
    phoneNumber: PhoneNumberRequest
}

list InsertCustomerBusinessContacts {
    member: InsertCustomerBusinessContact
}

structure InsertCustomerBusinessPostRequest {
    @required
    businessName: String
    @default([])
    emails: CustomerEmailEntryRequests
    taxID: String
    @default([])
    phoneNumbers: CustomerPhoneNumberEntryRequests
    addressLine1: String
    addressLine2: String
    city: String
    postalCode: String
    country: String
    @default([])
    customerBusinessContacts: InsertCustomerBusinessContacts
}

list InsertCustomerBusinesses {
    member: InsertCustomerBusinessPostRequest
}

structure InsertCustomerBusinessesPostRequest {
    @default([])
    customerBusinesses: InsertCustomerBusinesses
}

structure InsertCustomersPostRequest {
    @default([])
    customerBusinesses: InsertCustomerBusinesses
    @default([])
    customerIndividuals: InsertCustomerIndividuals
}

structure UpdateCustomerIndividualPutRequest {
    @required
    customerID: UUID
    fullName: String
    @default([])
    emails: CustomerEmailEntryRequests
    @default([])
    phoneNumbers: CustomerPhoneNumberEntryRequests
    addressLine1: String
    addressLine2: String
    city: String
    postalCode: String
    country: String
}

structure UpdateCustomerBusinessPutRequest {
    @required
    customerID: UUID
    businessName: String
    @default([])
    emails: CustomerEmailEntryRequests
    taxID: String
    @default([])
    phoneNumbers: CustomerPhoneNumberEntryRequests
    addressLine1: String
    addressLine2: String
    city: String
    postalCode: String
    country: String
}

structure AddCustomerBusinessContact {
    @required
    fullName: String
    role: String
    email: String
    phoneNumber: PhoneNumberRequest
}

list AddCustomerBusinessContacts {
    member: AddCustomerBusinessContact
}

structure AddCustomerBusinessContactsPutRequest {
    @required
    customerID: UUID
    @default([])
    customerBusinessContacts: AddCustomerBusinessContacts
}

structure RemoveCustomerBusinessContact {
    @required
    customerBusinessContactID: UUID
}

list RemoveCustomerBusinessContacts {
    member: RemoveCustomerBusinessContact
}

structure RemoveCustomerBusinessContactsPutRequest {
    @required
    customerID: UUID
    @default([])
    customerBusinessContacts: RemoveCustomerBusinessContacts
}

structure GetCustomerIndividualGetResponse {
    @required
    customerID: UUID
    @required
    fullName: String
    @required
    emails: CustomerEmailEntryRequests
    @required
    phoneNumbers: CustomerPhoneNumberEntryRequests
    addressLine1: String
    addressLine2: String
    city: String
    postalCode: String
    country: String
}

structure GetCustomerBusinessGetResponse {
    @required
    customerID: UUID
    @required
    businessName: String
    @required
    emails: CustomerEmailEntryRequests
    taxID: String
    @required
    phoneNumbers: CustomerPhoneNumberEntryRequests
    addressLine1: String
    addressLine2: String
    city: String
    postalCode: String
    country: String
}

structure GetCustomer {
    @required
    customerID: UUID
    @required
    displayName: String
    @required
    customerType: CustomerType
}

list GetCustomers {
    member: GetCustomer
}

structure GetCustomersGetResponse {
    @required
    customers: GetCustomers
}
