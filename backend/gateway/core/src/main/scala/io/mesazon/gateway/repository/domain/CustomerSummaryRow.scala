package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class CustomerSummaryRow(
    customerID: CustomerID,
    displayName: CustomerDisplayName,
    customerType: CustomerType,
)
