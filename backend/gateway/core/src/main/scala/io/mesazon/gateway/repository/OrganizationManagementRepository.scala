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
  def getOrganization(
      organizationID: OrganizationID
  ): IO[ServiceError, Option[OrganizationDetailsRow]]

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

  def updateOrganization(
      organizationID: OrganizationID,
      organizationStage: OrganizationStage,
      nameUpdate: Option[OrganizationName] = None,
      slugUpdate: Option[OrganizationSlug] = None,
      emailUpdate: Option[OrganizationEmail] = None,
      phoneNumberUpdate: Option[OrganizationPhoneNumber] = None,
      addressLine1Update: Option[OrganizationAddressLine1] = None,
      addressLine2Update: Option[OrganizationAddressLine2] = None,
      cityUpdate: Option[OrganizationCity] = None,
      postalCodeUpdate: Option[OrganizationPostalCode] = None,
      countryUpdate: Option[OrganizationCountry] = None,
      logoBucketKeyUpdate: Option[OrganizationLogoBucketKey] = None,
      logoFileNameUpdate: Option[OrganizationLogoFileName] = None,
  ): IO[ServiceError, OrganizationDetailsRow]

  def isOrganizationSlugExists(
      slug: OrganizationSlug
  ): IO[ServiceError, Boolean]
}

object OrganizationManagementRepository {

  private final class OrganizationManagementRepositoryImpl(
      database: DatabaseOps.ServiceOps[Transactor[Task]],
      organizationDetailsQueries: OrganizationDetailsQueries,
      organizationUserQueries: OrganizationUserQueries,
      timeProvider: TimeProvider,
      idGenerator: IDGenerator,
  ) extends OrganizationManagementRepository {

    override def getOrganization(organizationID: OrganizationID): IO[ServiceError, Option[OrganizationDetailsRow]] =
      database
        .transactionOrWiden(
          organizationDetailsQueries.get(organizationID)
        )
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to get organization by ID: [$organizationID]",
            e,
          )
        )

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
        None,
        None,
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

    override def updateOrganization(
        organizationID: OrganizationID,
        organizationStage: OrganizationStage,
        nameUpdate: Option[OrganizationName],
        slugUpdate: Option[OrganizationSlug],
        emailUpdate: Option[OrganizationEmail],
        phoneNumberUpdate: Option[OrganizationPhoneNumber],
        addressLine1Update: Option[OrganizationAddressLine1],
        addressLine2Update: Option[OrganizationAddressLine2],
        cityUpdate: Option[OrganizationCity],
        postalCodeUpdate: Option[OrganizationPostalCode],
        countryUpdate: Option[OrganizationCountry],
        logoBucketKeyUpdate: Option[OrganizationLogoBucketKey],
        logoFileNameUpdate: Option[OrganizationLogoFileName],
    ): IO[ServiceError, OrganizationDetailsRow] = for {
      instantNow                    <- timeProvider.instantNow
      organizationDetailsRowUpdated <- database
        .transactionOrWiden(
          organizationDetailsQueries.update(
            organizationID,
            organizationStage,
            UpdatedAt(instantNow),
            nameUpdate,
            slugUpdate,
            emailUpdate,
            phoneNumberUpdate,
            addressLine1Update,
            addressLine2Update,
            cityUpdate,
            postalCodeUpdate,
            countryUpdate,
            logoBucketKeyUpdate,
            logoFileNameUpdate,
          )
        )
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to update organization with ID: [$organizationID]",
            e,
          )
        )
    } yield organizationDetailsRowUpdated

    override def isOrganizationSlugExists(slug: OrganizationSlug): IO[ServiceError, Boolean] =
      database
        .transactionOrWiden(
          organizationDetailsQueries.slugExist(slug)
        )
        .mapError(e =>
          ServiceError.InternalServerError.RepositoryError(
            s"Failed to check slug uniqueness for slug: [$slug]",
            e,
          )
        )
  }

  val live = ZLayer.derive[OrganizationManagementRepositoryImpl].project[OrganizationManagementRepository](identity)
}
