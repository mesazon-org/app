package io.mesazon.domain.gateway

import io.github.iltotore.iron.*
import io.mesazon.domain.*

// -- Newtypes ---------------------------------------------------------------

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

// -- Individuals ------------------------------------------------------------

case class InsertCustomerIndividual(
    fullName: CustomerFullName,
    email: Option[CustomerEmail],
    phoneNumber: Option[CustomerPhoneNumber],
    addressLine1: Option[CustomerAddressLine1],
    addressLine2: Option[CustomerAddressLine2],
    city: Option[CustomerCity],
    postalCode: Option[CustomerPostalCode],
    country: Option[CustomerCountry],
)

case class InsertCustomerIndividuals(
    customerIndividuals: List[InsertCustomerIndividual]
)

case class UpdateCustomerIndividual(
    customerID: CustomerID,
    fullName: Option[CustomerFullName],
    email: Option[CustomerEmail],
    phoneNumber: Option[CustomerPhoneNumber],
    addressLine1: Option[CustomerAddressLine1],
    addressLine2: Option[CustomerAddressLine2],
    city: Option[CustomerCity],
    postalCode: Option[CustomerPostalCode],
    country: Option[CustomerCountry],
)

// -- Business contacts ------------------------------------------------------

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

case class AddCustomerBusinessContacts(
    customerID: CustomerID,
    customerBusinessContacts: List[AddCustomerBusinessContact],
)

case class RemoveCustomerBusinessContacts(
    customerID: CustomerID,
    customerBusinessContactIDs: List[CustomerBusinessContactID],
)

// -- Businesses -------------------------------------------------------------

case class InsertCustomerBusiness(
    businessName: CustomerBusinessName,
    email: Option[CustomerEmail],
    taxID: Option[CustomerTaxID],
    phoneNumber: Option[CustomerPhoneNumber],
    addressLine1: Option[CustomerAddressLine1],
    addressLine2: Option[CustomerAddressLine2],
    city: Option[CustomerCity],
    postalCode: Option[CustomerPostalCode],
    country: Option[CustomerCountry],
    customerBusinessContacts: List[InsertCustomerBusinessContact],
)

case class InsertCustomerBusinesses(
    customerBusinesses: List[InsertCustomerBusiness]
)

case class UpdateCustomerBusiness(
    customerID: CustomerID,
    businessName: Option[CustomerBusinessName],
    email: Option[CustomerEmail],
    taxID: Option[CustomerTaxID],
    phoneNumber: Option[CustomerPhoneNumber],
    addressLine1: Option[CustomerAddressLine1],
    addressLine2: Option[CustomerAddressLine2],
    city: Option[CustomerCity],
    postalCode: Option[CustomerPostalCode],
    country: Option[CustomerCountry],
)

// -- Combined ---------------------------------------------------------------

case class InsertCustomers(
    customerBusinesses: List[InsertCustomerBusiness],
    customerIndividuals: List[InsertCustomerIndividual],
)
