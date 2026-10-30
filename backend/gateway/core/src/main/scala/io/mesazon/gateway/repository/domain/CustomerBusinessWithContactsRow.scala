package io.mesazon.gateway.repository.domain

case class CustomerBusinessWithContactsRow(
    customerBusinessDetailsRow: CustomerBusinessDetailsRow,
    customerBusinessContactRows: List[CustomerBusinessContactRow],
)
