//package io.rikkos.gateway.it
//
//import io.rikkos.domain.*
//import io.rikkos.gateway.repository.UserRepository
//import io.rikkos.gateway.utils.GatewayArbitraries
//import io.rikkos.testkit.base.ZWordSpecBase
//import zio.ZIO
//
//class UserRepositorySpec extends ZWordSpecBase, GatewayArbitraries {
//
//  "UserRepository" should {
//    "insert user details" in {
//      val input = arbitrarySample[OnboardUserDetails]
//      val repository = ZIO.service[UserRepository]
//        .provide(UserRepository.live, )
//
//      repository
//      for {
//        userDetails <- atbit[OnboardUserDetails]
//        _           <- userRepository.insertUserDetails(userDetails)
//      } yield succeed
//    }
//
//}
