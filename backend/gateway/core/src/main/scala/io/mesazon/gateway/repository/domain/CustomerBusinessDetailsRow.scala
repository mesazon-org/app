package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.CustomerBookRepository.{CustomerEmailEntryInput, CustomerPhoneNumberEntryInput}

case class CustomerBusinessDetailsRow(
    organizationID: OrganizationID,
    customerID: CustomerID,
    businessName: CustomerBusinessName,
    taxID: Option[CustomerTaxID],
    emails: List[CustomerEmailEntryInput],
    phoneNumbers: List[CustomerPhoneNumberEntryInput],
    addresses: List[CustomerAddressEntry],
    status: CustomerStatus,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
