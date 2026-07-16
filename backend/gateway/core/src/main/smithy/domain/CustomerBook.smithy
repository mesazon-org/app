$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID

structure InsertCustomerIndividualPostRequest {
    @required
    fullName: String
    email: String
    phoneNumber: PhoneNumberRequest
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
    email: String
    taxID: String
    phoneNumber: PhoneNumberRequest
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
    customerBusinesses: InsertCustomerBusinesses
}


structure InsertCustomersPostRequest {
    customerBusinesses: InsertCustomerBusinesses
    customerIndividuals: InsertCustomerIndividuals
}

structure UpdateCustomerIndividualPutRequest {
    @required
    customerID: UUID
    fullName: String
    email: String
    phoneNumber: PhoneNumberRequest
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
    email: String
    taxID: String
    phoneNumber: PhoneNumberRequest
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
    customerID: UUID
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
    customerID: UUID
    customerBusinessContacts: RemoveCustomerBusinessContacts
}

structure GetCustomerIndividualGetResponse {
    @required
    customerID: UUID
    @required
    fullName: String
    email: String
    phoneNumber: PhoneNumberRequest
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
    email: String
    taxID: String
    phoneNumber: PhoneNumberRequest
    addressLine1: String
    addressLine2: String
    city: String
    postalCode: String
    country: String
}

structure GetCustomers {
    @required
    customerID: UUID
    @required
    displayName: String
    customerType: CustomerType
}

structure GetCustomersGetResponse {
    @required
    customers: GetCustomers
}
