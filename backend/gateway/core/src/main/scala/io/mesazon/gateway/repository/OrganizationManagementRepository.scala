package io.mesazon.gateway.repository

import doobie.Transactor
import io.github.gaelrenoux.tranzactio.DatabaseOps
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.domain.{OrganizationDetailsRow, OrganizationUserRow}
import io.mesazon.gateway.repository.queries.*
import io.mesazon.generator.IDGenerator
import zio.*

trait OrganizationManagementRepository {
  def createOrganization(
      userID: UserID,
      name: OrganizationName,
      slug: OrganizationSlug,
      email: OrganizationEmail,
      phoneNumber: OrganizationPhoneNumber,
      organizationStage: OrganizationStage,
      addressLine1: OrganizationAddressLine1,
      addressLine2: Option[OrganizationAddressLine2],
      city: OrganizationCity,
      postalCode: OrganizationPostalCode,
      country: OrganizationCountry,
  ): IO[ServiceError, OrganizationDetailsRow]
}

object OrganizationManagementRepository {

  private final class OrganizationManagementRepositoryImpl(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      organizationDetailsQueries: OrganizationDetailsQueries,
      organizationUserQueries: OrganizationUserQueries,
      timeProvider: TimeProvider,
      idGenerator: IDGenerator,
  ) extends OrganizationManagementRepository {

    override def createOrganization(
        userID: UserID,
        name: OrganizationName,
        slug: OrganizationSlug,
        email: OrganizationEmail,
        phoneNumber: OrganizationPhoneNumber,
        organizationStage: OrganizationStage,
        addressLine1: OrganizationAddressLine1,
        addressLine2: Option[OrganizationAddressLine2],
        city: OrganizationCity,
        postalCode: OrganizationPostalCode,
        country: OrganizationCountry,
    ): IO[ServiceError, OrganizationDetailsRow] = for {
      instantNow     <- timeProvider.instantNow
      organizationID <- idGenerator.generateID
        .map(OrganizationID.either)
        .flatMap(
          ZIO
            .fromEither(_)
            .mapError(e =>
              ServiceError.InternalServerError.UnexpectedError(s"Failed to construct organizationID: [$e]")
            )
        )
      organizationDetailsRow = OrganizationDetailsRow(
        organizationID,
        name,
        slug,
        email,
        phoneNumber,
        organizationStage,
        addressLine1,
        addressLine2,
        city,
        postalCode,
        country,
        CreatedAt(instantNow),
        UpdatedAt(instantNow),
      )
      _ <- database
        .transactionOrWiden(
          for {
            _ <- organizationDetailsQueries.insert(organizationDetailsRow)
            organizationUserRow = OrganizationUserRow(
              organizationID,
              userID,
              UserRole.Owner,
              CreatedAt(instantNow),
              UpdatedAt(instantNow),
            )
            _ <- organizationUserQueries.insert(organizationUserRow)
          } yield ()
        )
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to create organization with ID: [$organizationID]",
            e,
          )
        )
    } yield organizationDetailsRow
  }

  val live = ZLayer.derive[OrganizationManagementRepositoryImpl].project[OrganizationManagementRepository](identity)
}
