$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID

structure CustomerEmailRequest {
    @required
    email: String
    @required
    isDefault: Boolean
}

list CustomerEmailRequests {
    member: CustomerEmailRequest
}

structure CustomerPhoneNumberRequest {
    @required
    phoneNumber: PhoneNumberRequest
    @required
    isDefault: Boolean
}

list CustomerPhoneNumberRequests {
    member: CustomerPhoneNumberRequest
}

structure InsertCustomerIndividualPostRequest {
    @required
    fullName: String
    @required
    emails: CustomerEmailRequests
    @required
    phoneNumbers: CustomerPhoneNumberRequests
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
    @required
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
    @required
    emails: CustomerEmailRequests
    taxID: String
    @required
    phoneNumbers: CustomerPhoneNumberRequests
    addressLine1: String
    addressLine2: String
    city: String
    postalCode: String
    country: String
    customerBusinessContacts: InsertCustomerBusinessContacts
}

list InsertCustomerBusinesses {
    member: InsertCustomerBusinessPostRequest
}

structure InsertCustomerBusinessesPostRequest {
    @required
    customerBusinesses: InsertCustomerBusinesses
}

structure InsertCustomersPostRequest {
    @required
    customerBusinesses: InsertCustomerBusinesses
    @required
    customerIndividuals: InsertCustomerIndividuals
}

structure UpdateCustomerIndividualPutRequest {
    @required
    customerID: UUID
    fullName: String
    @required
    emails: CustomerEmailRequests
    @required
    phoneNumbers: CustomerPhoneNumberRequests
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
    @required
    emails: CustomerEmailRequests
    taxID: String
    @required
    phoneNumbers: CustomerPhoneNumberRequests
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
    @required
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
    @required
    customerBusinessContacts: RemoveCustomerBusinessContacts
}

structure GetCustomerIndividualGetResponse {
    @required
    customerID: UUID
    @required
    fullName: String
    @required
    emails: CustomerEmailRequests
    @required
    phoneNumbers: CustomerPhoneNumberRequests
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
    emails: CustomerEmailRequests
    taxID: String
    @required
    phoneNumbers: CustomerPhoneNumberRequests
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
