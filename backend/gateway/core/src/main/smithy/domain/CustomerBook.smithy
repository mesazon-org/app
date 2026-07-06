$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID

structure GetCustomer {
    @required
    customerID: UUID
    @required
    fullName: String
    phoneNumber: PhoneNumberRequest
}

structure InsertCustomer {
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

structure UpdateCustomer {
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

list GetCustomers {
    member: GetCustomer
}

list InsertCustomers {
    member: InsertCustomer
}

list UpdateCustomers {
    member: UpdateCustomer
}

list CustomerIDs {
    member: UUID
}

structure GetCustomerGetResponse {
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

structure GetCustomersGetResponse {
    @required
    customers: GetCustomers
}

structure InsertCustomersPostRequest {
    @required
    customers: InsertCustomers
}

structure UpdateCustomersPutRequest {
    @required
    customers: UpdateCustomers
}

structure DeleteCustomersPostRequest {
    @required
    customerIDs: CustomerIDs
}
