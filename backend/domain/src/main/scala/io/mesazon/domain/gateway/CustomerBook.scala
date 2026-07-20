package io.mesazon.domain.gateway

import io.github.iltotore.iron.*
import io.mesazon.domain.*

// Newtypes

object CustomerID extends RefinedTypeUUID
type CustomerID = CustomerID.T

object CustomerBusinessContactID extends RefinedTypeUUID
type CustomerBusinessContactID = CustomerBusinessContactID.T

object CustomerFullName extends RefinedType[String, NonEmptyTrimmed]
type CustomerFullName = CustomerFullName.T

object CustomerBusinessName extends RefinedType[String, NonEmptyTrimmed]
type CustomerBusinessName = CustomerBusinessName.T

object CustomerTaxID extends RefinedType[String, NonEmptyTrimmed]
type CustomerTaxID = CustomerTaxID.T

object CustomerBusinessContactRole extends RefinedType[String, NonEmptyTrimmed]
type CustomerBusinessContactRole = CustomerBusinessContactRole.T

object CustomerEmail extends RefinedType[String, EmailPredicate]
type CustomerEmail = CustomerEmail.T

object CustomerPhoneNumber extends RefinedType[PhoneNumber, Pure]
type CustomerPhoneNumber = CustomerPhoneNumber.T

object CustomerAddressLine1 extends RefinedType[String, NonEmptyTrimmed]
type CustomerAddressLine1 = CustomerAddressLine1.T

object CustomerAddressLine2 extends RefinedType[String, NonEmptyTrimmed]
type CustomerAddressLine2 = CustomerAddressLine2.T

object CustomerCity extends RefinedType[String, NonEmptyTrimmed]
type CustomerCity = CustomerCity.T

object CustomerPostalCode extends RefinedType[String, NonEmptyTrimmed]
type CustomerPostalCode = CustomerPostalCode.T

object CustomerCountry extends RefinedType[String, NonEmptyTrimmed]
type CustomerCountry = CustomerCountry.T

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

case class RemoveCustomerBusinessContactsPutRequest(
    customerID: CustomerID,
    customerBusinessContactIDs: List[CustomerBusinessContactID],
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
