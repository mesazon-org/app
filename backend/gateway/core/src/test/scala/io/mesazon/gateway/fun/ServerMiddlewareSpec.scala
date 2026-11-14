package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.OrganizationUserRole
import io.mesazon.gateway.middleware.ServerMiddleware
import io.mesazon.gateway.service.{AuthenticationService, AuthorizationService}
import io.mesazon.gateway.smithy as gatewaySmithy
import io.mesazon.testkit.base.{OrganizationManagementDomainArbitraries, ZWordSpecBase}
import io.scalaland.chimney.dsl.*
import org.http4s.*
import org.scalacheck.{Arbitrary, Gen}
import smithy4s.Hints
import smithy4s.http4s.ServerEndpointMiddleware
import zio.*
import zio.interop.catz.*
import zio.test.{testEnvironment, Live, TestClock}

class ServerMiddlewareSpec extends ZWordSpecBase, OrganizationManagementDomainArbitraries {

  "ServerMiddleware" when {
    "prepareWithHints" should {
      "successfully pass the request to the endpoint without reading its body when the bearer token is authorized" in new TestContext {
        val organizationUserRoles = arbitrarySample[List[OrganizationUserRole]]

        val serviceHints  = Hints(smithy.api.HttpBearerAuth(), gatewaySmithy.CompletedOnboardStage())
        val endpointHints = Hints(
          gatewaySmithy.OrganizationUserRolesAllowed(
            organizationUserRoles.transformInto[List[gatewaySmithy.OrganizationUserRole]]
          )
        )

        val requestBodyBytesPulledRef = counterRef.zioValue
        val requestBodyBytes          = arbitrarySample(Gen.listOfN(requestBodyBytesSize, Arbitrary.arbitrary[Byte]))
        val request                   = Request[Task](Method.POST, requestUri)
          .withBodyStream(requestBodyStream(requestBodyBytes, requestBodyBytesPulledRef))

        val response = Response[Task](Status.Ok)

        inSequence(
          (authorizationServiceMock
            .auth(_: Request[Task], _: Boolean, _: Option[List[OrganizationUserRole]]))
            .expects(request, true, Some(organizationUserRoles))
            .returningZIOUnit
            .once(),
          endpointHttpAppMock.expects(request).returningZIO(response).once(),
        )

        val serverMiddleware = buildServerMiddleware

        val responseActual =
          serverMiddleware.prepareWithHints(serviceHints, endpointHints)(endpointHttpApp)(request).zioValue

        responseActual shouldBe response

        requestBodyBytesPulledRef.refValue shouldBe 0
      }

      "successfully pass the request to the endpoint without reading its body when the basic credentials are authenticated" in new TestContext {
        val serviceHints  = Hints(smithy.api.HttpBasicAuth())
        val endpointHints = Hints.empty

        val requestBodyBytesPulledRef = counterRef.zioValue
        val requestBodyBytes          = arbitrarySample(Gen.listOfN(requestBodyBytesSize, Arbitrary.arbitrary[Byte]))
        val request                   = Request[Task](Method.POST, requestUri)
          .withBodyStream(requestBodyStream(requestBodyBytes, requestBodyBytesPulledRef))

        val response = Response[Task](Status.Ok)

        inSequence(
          authenticationServiceMock.auth.expects(request).returningZIOUnit.once(),
          endpointHttpAppMock.expects(request).returningZIO(response).once(),
        )

        val serverMiddleware = buildServerMiddleware

        val responseActual =
          serverMiddleware.prepareWithHints(serviceHints, endpointHints)(endpointHttpApp)(request).zioValue

        responseActual shouldBe response

        requestBodyBytesPulledRef.refValue shouldBe 0
      }

      "fail with an Unauthorized after draining the unread request body when the bearer token is rejected" in new TestContext {
        val organizationUserRoles = arbitrarySample[List[OrganizationUserRole]]

        val serviceHints  = Hints(smithy.api.HttpBearerAuth(), gatewaySmithy.CompletedOnboardStage())
        val endpointHints = Hints(
          gatewaySmithy.OrganizationUserRolesAllowed(
            organizationUserRoles.transformInto[List[gatewaySmithy.OrganizationUserRole]]
          )
        )

        val requestBodyBytesPulledRef = counterRef.zioValue
        val requestBodyBytes          = arbitrarySample(Gen.listOfN(requestBodyBytesSize, Arbitrary.arbitrary[Byte]))
        val request                   = Request[Task](Method.POST, requestUri)
          .withBodyStream(requestBodyStream(requestBodyBytes, requestBodyBytesPulledRef))

        val unauthorized = gatewaySmithy.Unauthorized()

        (authorizationServiceMock
          .auth(_: Request[Task], _: Boolean, _: Option[List[OrganizationUserRole]]))
          .expects(request, true, Some(organizationUserRoles))
          .failingZIO(unauthorized)
          .once()

        val serverMiddleware = buildServerMiddleware

        val error = serverMiddleware.prepareWithHints(serviceHints, endpointHints)(endpointHttpApp)(request).zioError

        error shouldBe unauthorized

        requestBodyBytesPulledRef.refValue shouldBe requestBodyBytes.size
      }

      "fail with an Unauthorized after draining the unread request body when the basic credentials are rejected" in new TestContext {
        val serviceHints  = Hints(smithy.api.HttpBasicAuth())
        val endpointHints = Hints.empty

        val requestBodyBytesPulledRef = counterRef.zioValue
        val requestBodyBytes          = arbitrarySample(Gen.listOfN(requestBodyBytesSize, Arbitrary.arbitrary[Byte]))
        val request                   = Request[Task](Method.POST, requestUri)
          .withBodyStream(requestBodyStream(requestBodyBytes, requestBodyBytesPulledRef))

        val unauthorized = gatewaySmithy.Unauthorized()

        authenticationServiceMock.auth.expects(request).failingZIO(unauthorized).once()

        val serverMiddleware = buildServerMiddleware

        val error = serverMiddleware.prepareWithHints(serviceHints, endpointHints)(endpointHttpApp)(request).zioError

        error shouldBe unauthorized

        requestBodyBytesPulledRef.refValue shouldBe requestBodyBytes.size
      }

      "fail with an Unauthorized once the drain times out when the rejected request body stalls" in new TestContext {
        val serviceHints  = Hints(smithy.api.HttpBearerAuth())
        val endpointHints = Hints.empty

        val requestBodyBytesPulledRef = counterRef.zioValue
        val requestBodyBytes          = arbitrarySample(Gen.listOfN(requestBodyBytesSize, Arbitrary.arbitrary[Byte]))
        val request                   = Request[Task](Method.POST, requestUri)
          .withBodyStream(requestBodyStream(requestBodyBytes, requestBodyBytesPulledRef) ++ fs2.Stream.never[Task])

        val unauthorized = gatewaySmithy.Unauthorized()

        (authorizationServiceMock
          .auth(_: Request[Task], _: Boolean, _: Option[List[OrganizationUserRole]]))
          .expects(request, false, None)
          .failingZIO(unauthorized)
          .once()

        val serverMiddleware = buildServerMiddleware

        val drainTimeoutResult = (for {
          responseFiber <- serverMiddleware
            .prepareWithHints(serviceHints, endpointHints)(endpointHttpApp)(request)
            .either
            .fork

          drainTimeoutSleepsOpt <- Live.live(
            TestClock.sleeps.repeatUntil(_.nonEmpty).timeout(drainTimeoutRegistrationWait)
          )

          responseExitBeforeTimeoutOpt <- responseFiber.poll

          _ <- TestClock.adjust(drainTimeout)

          responseEither <- responseFiber.join
        } yield (
          drainTimeoutSleepsOpt = drainTimeoutSleepsOpt,
          responseExitBeforeTimeoutOpt = responseExitBeforeTimeoutOpt,
          responseEither = responseEither,
        )).provideLayer(testEnvironment).zioValue

        drainTimeoutResult.drainTimeoutSleepsOpt.map(_.size) shouldBe Some(1)
        drainTimeoutResult.responseExitBeforeTimeoutOpt shouldBe None
        drainTimeoutResult.responseEither shouldBe Left(unauthorized)

        requestBodyBytesPulledRef.refValue shouldBe requestBodyBytes.size
      }
    }
  }

  trait TestContext {
    val requestUri                   = Uri.unsafeFromString("localhost")
    val requestBodyBytesSize         = 16 * 1024
    val requestBodyChunkSize         = 1024
    val drainTimeout                 = 5.seconds
    val drainTimeoutRegistrationWait = 1.second

    val authorizationServiceMock  = mock[AuthorizationService[Task]]
    val authenticationServiceMock = mock[AuthenticationService[Task]]
    val endpointHttpAppMock       = mockFunction[Request[Task], Task[Response[Task]]]

    val endpointHttpApp = HttpApp[Task](endpointHttpAppMock)

    def requestBodyStream(requestBodyBytes: List[Byte], requestBodyBytesPulledRef: Ref[Int]): fs2.Stream[Task, Byte] =
      fs2.Stream
        .emits(requestBodyBytes)
        .covary[Task]
        .chunkLimit(requestBodyChunkSize)
        .evalTap(requestBodyChunk => requestBodyBytesPulledRef.update(_ + requestBodyChunk.size))
        .unchunks

    def buildServerMiddleware: ServerEndpointMiddleware.Simple[Task] = ZIO
      .service[ServerEndpointMiddleware.Simple[Task]]
      .provide(
        ServerMiddleware.live,
        ZLayer.succeed(authorizationServiceMock),
        ZLayer.succeed(authenticationServiceMock),
      )
      .zioValue
  }
}
