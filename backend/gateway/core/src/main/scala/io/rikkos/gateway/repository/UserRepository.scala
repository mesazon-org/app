package io.rikkos.gateway.repository

import io.rikkos.domain.*
import zio.*

trait UserRepository {
  def insertUserDetails(userID: UserID, email: Email, onboardUserDetails: OnboardUserDetails): UIO[Unit]
  def updateUserDetails(userID: UserID, updateUserDetails: UpdateUserDetails): UIO[Unit]
}

object UserRepository {

  final private class UserRepositoryMemory(userDetailsRef: Ref[Map[UserID, OnboardUserDetails]])
      extends UserRepository {
    override def insertUserDetails(userID: UserID, email: Email, onboardUserDetails: OnboardUserDetails): UIO[Unit] =
      for {
        _ <- userDetailsRef.update(_.updated(userID, onboardUserDetails))
      } yield ()

    override def updateUserDetails(userID: UserID, updateUserDetails: UpdateUserDetails): UIO[Unit] =
      for {
        map: Map[UserID, OnboardUserDetails] <- userDetailsRef.get
        updateUser: Option[OnboardUserDetails] = map.get(userID)
        _ = updateUser.map(user =>
          OnboardUserDetails(
            firstName = updateUserDetails.firstName.getOrElse(user.firstName),
            lastName = updateUserDetails.lastName.getOrElse(user.lastName),
            countryCode = updateUserDetails.countryCode.getOrElse(user.countryCode),
            phoneNumber = updateUserDetails.phoneNumber.getOrElse(user.phoneNumber),
            addressLine1 = updateUserDetails.addressLine1.getOrElse(user.addressLine1),
            addressLine2 = updateUserDetails.addressLine2.orElse(user.addressLine2),
            city = updateUserDetails.city.getOrElse(user.city),
            postalCode = updateUserDetails.postalCode.getOrElse(user.postalCode),
            company = updateUserDetails.company.getOrElse(user.company),
          )
        ) //todo needs fix
      } yield ()
  }

  val live: ULayer[UserRepository] = ZLayer {
    Ref.make(Map.empty[UserID, OnboardUserDetails]).map { userDetailsRef =>
      new UserRepositoryMemory(userDetailsRef)
    }
  }
}
