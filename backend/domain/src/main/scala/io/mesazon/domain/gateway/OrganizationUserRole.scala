package io.mesazon.domain.gateway

enum OrganizationUserRole {
  case Owner
  case Admin
  case User
}

object OrganizationUserRole {

  val ownerRoles: List[OrganizationUserRole] = List(OrganizationUserRole.Owner)
  val adminRoles: List[OrganizationUserRole] = List(OrganizationUserRole.Owner, OrganizationUserRole.Admin)
  val userRoles: List[OrganizationUserRole]  =
    List(OrganizationUserRole.Owner, OrganizationUserRole.Admin, OrganizationUserRole.User)
}
