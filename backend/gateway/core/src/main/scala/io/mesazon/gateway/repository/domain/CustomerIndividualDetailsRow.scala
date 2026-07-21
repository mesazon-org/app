package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.CustomerBookRepository.{CustomerEmailEntryInput, CustomerPhoneNumberEntryInput}

case class CustomerIndividualDetailsRow(
    organizationID: OrganizationID,
    customerID: CustomerID,
    fullName: CustomerFullName,
    emails: List[CustomerEmailEntryInput],
    phoneNumbers: List[CustomerPhoneNumberEntryInput],
    addressLine1: Option[CustomerAddressLine1],
    addressLine2: Option[CustomerAddressLine2],
    city: Option[CustomerCity],
    postalCode: Option[CustomerPostalCode],
    country: Option[CustomerCountry],
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
