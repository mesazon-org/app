package io.mesazon.waha.it

import com.dimafeng.testcontainers.ExposedService
import io.mesazon.waha.WahaClient
import io.mesazon.waha.config.WahaConfig
import io.mesazon.waha.domain.input.*
import io.mesazon.waha.domain.output.*
import io.mesazon.waha.domain.{UserAccountID, *}
import io.mesazon.waha.it.WahaClientSpec.Context
import io.mesazon.waha.it.client.WiremockClient
import io.mesazon.waha.it.client.WiremockClient.WiremockClientConfig
import io.mesazon.waha.it.utils.WahaArbitraries
import io.rikkos.testkit.base.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.model.StatusCode
import zio.*

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration as ScalaDuration

class WahaClientSpec extends ZWordSpecBase with DockerComposeBase with WahaArbitraries with IronRefinedTypeTransformer {

  private val sessionID        = SessionID.assume("session-test")
  private val groupID          = GroupID.assume("0000@g.us")
  private val groupInviteUrl   = GroupInviteUrl.assume("http://chat.com/invite")
  val nonRegisteredParticipant = UserAccountID.assume("6666@c.us")
  val participants             = List(
    UserAccountID.assume("1000@c.us"),
    UserAccountID.assume("1001@c.us"),
    UserAccountID.assume("1002@c.us"),
    nonRegisteredParticipant,
  )

  override def exposedServices: Set[ExposedService] = WiremockClient.ExposedServices

  private def buildWahaConfigLayer(config: WiremockClientConfig): ULayer[WahaConfig] = ZLayer.succeed(
    WahaConfig(
      config.scheme,
      config.host,
      config.port,
      apiKey = "dummy-key",
      wordsPerMinute = 10000,
      humanDelayMin = ScalaDuration.Zero,
      humanDelayMax = ScalaDuration.apply(1, TimeUnit.MILLISECONDS),
    )
  )

  def withContext[A](f: Context => A): A = withContainers { container =>
    val context = for {
      gatewayApiClientConfig = WiremockClientConfig.from(container)
      wiremockClient <- ZIO
        .service[WiremockClient]
        .provide(WiremockClient.live, ZLayer.succeed(gatewayApiClientConfig), HttpClientZioBackend.layer())
    } yield Context(wiremockClient)

    f(context.zioValue)
  }

  override def beforeAll(): Unit = withContext { case Context(wiremockClient) =>
    super.beforeAll()

    // Ensure the GatewayApiClient is initialized before running tests
    eventually {
      val healthCheckStatusResponse = wiremockClient.healthCheck.zioValue

      healthCheckStatusResponse.code shouldBe StatusCode.Ok
      healthCheckStatusResponse.body.status shouldBe "healthy"
    }
  }

  override def afterEach(): Unit = withContext { case Context(wiremockClient) =>
    super.beforeEach()

    eventually {
      wiremockClient.reset.zioValue.code shouldBe StatusCode.Ok
    }
  }

  "WahaClient" when {
    "Chatting API" should {
      "chattingSendMessage send message" in withContext { case Context(wiremockClient) =>
        val wahaClient = ZIO
          .service[WahaClient]
          .provide(
            WahaClient.live,
            buildWahaConfigLayer(wiremockClient.config),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val input = arbitrarySample[ChattingMessageInput.Text]
          .copy(sessionID = sessionID)

        wahaClient.chattingSendMessage(input).zioValue

        val requestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        requestMappings.size shouldBe 3

        requestMappings(0).mapping.method shouldBe "POST"
        requestMappings(0).mapping.url shouldBe "/api/startTyping"
        requestMappings(0).count shouldBe 1

        requestMappings(1).mapping.method shouldBe "POST"
        requestMappings(1).mapping.url shouldBe "/api/stopTyping"
        requestMappings(1).count shouldBe 1

        requestMappings(2).mapping.method shouldBe "POST"
        requestMappings(2).mapping.url shouldBe "/api/sendText"
        requestMappings(2).count shouldBe 1
      }
    }

    "Groups API" should {
      "groupsCreate be able to create groups" in withContext { case Context(wiremockClient) =>
        val wahaClient = ZIO
          .service[WahaClient]
          .provide(
            WahaClient.live,
            buildWahaConfigLayer(wiremockClient.config),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val groupDescription         = arbitrarySample[GroupDescription]
        val groupName                = arbitrarySample[GroupName]
        val fileType                 = arbitrarySample[FileType]
        val nonRegisteredParticipant = UserAccountID.assume("6666@c.us")
        val participants             = List(
          UserAccountID.assume("1000@c.us"),
          UserAccountID.assume("1001@c.us"),
          UserAccountID.assume("1002@c.us"),
          nonRegisteredParticipant,
        )
        val input = GroupsCreateInput(
          sessionID = sessionID,
          name = groupName,
          description = Some(groupDescription),
          picture = Some(fileType),
          participants = participants,
        )

        val groupsCreateOutput = wahaClient.groupsCreate(input).zioValue

        val requestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        val expectedGroupsCreateOutput = GroupsCreateOutput(
          groupID = groupID,
          inviteUrl = groupInviteUrl,
          nonRegisteredUserAccountIDs = List(nonRegisteredParticipant),
        )

        requestMappings.size shouldBe 11

        participants.zipWithIndex.foreach { case (_, index) =>
          requestMappings(index).mapping.method shouldBe "GET"
          requestMappings(
            index
          ).mapping.url should startWith("/api/contacts/check-exists")
          requestMappings(index).count shouldBe 1
        }

        requestMappings(4).mapping.method shouldBe "POST"
        requestMappings(4).mapping.url shouldBe s"/api/$sessionID/groups"
        requestMappings(4).count shouldBe 1

        requestMappings(5).mapping.method shouldBe "PUT"
        requestMappings(5).mapping.url shouldBe s"/api/$sessionID/groups/$groupID/description"
        requestMappings(5).count shouldBe 1

        requestMappings(6).mapping.method shouldBe "PUT"
        requestMappings(6).mapping.url shouldBe s"/api/$sessionID/groups/$groupID/picture"
        requestMappings(6).count shouldBe 1

        requestMappings(7).mapping.method shouldBe "GET"
        requestMappings(7).mapping.url shouldBe s"/api/$sessionID/groups/$groupID/invite-code"
        requestMappings(7).count shouldBe 1

        requestMappings(8).mapping.method shouldBe "POST"
        requestMappings(8).mapping.url shouldBe "/api/startTyping"
        requestMappings(8).count shouldBe 1

        requestMappings(9).mapping.method shouldBe "POST"
        requestMappings(9).mapping.url shouldBe "/api/stopTyping"
        requestMappings(9).count shouldBe 1

        requestMappings(10).mapping.method shouldBe "POST"
        requestMappings(10).mapping.url shouldBe "/api/sendText"
        requestMappings(10).count shouldBe 1

        groupsCreateOutput shouldBe expectedGroupsCreateOutput
      }

      "groupsUpdate be able to update group" in withContext { case Context(wiremockClient) =>
        val wahaClient = ZIO
          .service[WahaClient]
          .provide(
            WahaClient.live,
            buildWahaConfigLayer(wiremockClient.config),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val groupName        = arbitrarySample[GroupName]
        val groupDescription = arbitrarySample[GroupDescription]
        val fileType         = arbitrarySample[FileType]
        val input            = GroupsUpdateInput(
          sessionID = sessionID,
          groupID = groupID,
          name = Some(groupName),
          description = Some(groupDescription),
          picture = Some(fileType),
          addParticipants = participants,
          removeParticipants = participants,
          promoteParticipants = participants,
          demoteParticipants = participants,
        )

        val groupsUpdateOutput = wahaClient.groupsUpdate(input).zioValue

        val expectedGroupsUpdateOutput = GroupsUpdateOutput(
          nonRegisteredUserAccountIDs = List(nonRegisteredParticipant)
        )

        val requestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        requestMappings.size shouldBe 27

        requestMappings(0).mapping.method shouldBe "PUT"
        requestMappings(0).mapping.url shouldBe s"/api/$sessionID/groups/$groupID/subject"
        requestMappings(0).count shouldBe 1

        requestMappings(1).mapping.method shouldBe "PUT"
        requestMappings(1).mapping.url shouldBe s"/api/$sessionID/groups/$groupID/description"
        requestMappings(1).count shouldBe 1

        requestMappings(2).mapping.method shouldBe "PUT"
        requestMappings(2).mapping.url shouldBe s"/api/$sessionID/groups/$groupID/picture"
        requestMappings(2).count shouldBe 1

        participants.zipWithIndex.foreach { case (_, index) =>
          val mappingIndex = (1 + index) + 2
          requestMappings(mappingIndex).mapping.method shouldBe "GET"
          requestMappings(
            mappingIndex
          ).mapping.url should startWith("/api/contacts/check-exists")
          requestMappings(mappingIndex).count shouldBe 4 // Used in add, remove, promote, demote
        }

        requestMappings(7).mapping.method shouldBe "POST"
        requestMappings(7).mapping.url shouldBe s"/api/$sessionID/groups/$groupID/participants/add"
        requestMappings(7).count shouldBe 1

        requestMappings(8).mapping.method shouldBe "GET"
        requestMappings(8).mapping.url shouldBe s"/api/$sessionID/groups/$groupID/invite-code"
        requestMappings(8).count shouldBe 1

        requestMappings(9).mapping.method shouldBe "POST"
        requestMappings(9).mapping.url shouldBe "/api/startTyping"
        requestMappings(9).count shouldBe 1

        requestMappings(10).mapping.method shouldBe "POST"
        requestMappings(10).mapping.url shouldBe "/api/stopTyping"
        requestMappings(10).count shouldBe 1

        requestMappings(11).mapping.method shouldBe "POST"
        requestMappings(11).mapping.url shouldBe "/api/sendText"
        requestMappings(11).count shouldBe 1

        participants.zipWithIndex.foreach { case (_, index) =>
          val mappingIndex = (1 + index) + 11
          requestMappings(mappingIndex).mapping.method shouldBe "GET"
          requestMappings(
            mappingIndex
          ).mapping.url should startWith("/api/contacts/check-exists")
          requestMappings(mappingIndex).count shouldBe 4
        }

        requestMappings(16).mapping.method shouldBe "POST"
        requestMappings(16).mapping.url shouldBe s"/api/$sessionID/groups/$groupID/participants/remove"
        requestMappings(16).count shouldBe 1

        participants.zipWithIndex.foreach { case (_, index) =>
          val mappingIndex = (1 + index) + 16
          requestMappings(mappingIndex).mapping.method shouldBe "GET"
          requestMappings(
            mappingIndex
          ).mapping.url should startWith("/api/contacts/check-exists")
          requestMappings(mappingIndex).count shouldBe 4 // Used in add, remove, promote, demote
        }

        requestMappings(21).mapping.method shouldBe "POST"
        requestMappings(21).mapping.url shouldBe s"/api/$sessionID/groups/$groupID/admin/promote"
        requestMappings(21).count shouldBe 1

        participants.zipWithIndex.foreach { case (_, index) =>
          val mappingIndex = (1 + index) + 21
          requestMappings(mappingIndex).mapping.method shouldBe "GET"
          requestMappings(
            mappingIndex
          ).mapping.url should startWith("/api/contacts/check-exists")
          requestMappings(mappingIndex).count shouldBe 4 // Used in add, remove, promote, demote
        }

        requestMappings(26).mapping.method shouldBe "POST"
        requestMappings(26).mapping.url shouldBe s"/api/$sessionID/groups/$groupID/admin/demote"
        requestMappings(26).count shouldBe 1

        groupsUpdateOutput shouldBe expectedGroupsUpdateOutput
      }

      "groupsLeave be able to leave group" in withContext { case Context(wiremockClient) =>
        val wahaClient = ZIO
          .service[WahaClient]
          .provide(
            WahaClient.live,
            buildWahaConfigLayer(wiremockClient.config),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val input = GroupsLeaveInput(
          sessionID = sessionID,
          groupID = groupID,
        )

        wahaClient.groupsLeave(input).zioValue

        val requestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        requestMappings.size shouldBe 1

        requestMappings(0).mapping.method shouldBe "POST"
        requestMappings(0).mapping.url shouldBe s"/api/$sessionID/groups/$groupID/leave"
        requestMappings(0).count shouldBe 1
      }

      "groupsInviteCode should return the invite url" in withContext { case Context(wiremockClient) =>
        val wahaClient = ZIO
          .service[WahaClient]
          .provide(
            WahaClient.live,
            buildWahaConfigLayer(wiremockClient.config),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val input = GroupsInviteCodeInput(
          sessionID = sessionID,
          groupID = groupID,
        )

        val groupsInviteCodeOutput = wahaClient.groupsInviteCode(input).zioValue

        val requestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        requestMappings.size shouldBe 1

        requestMappings(0).mapping.method shouldBe "GET"
        requestMappings(0).mapping.url shouldBe s"/api/$sessionID/groups/$groupID/invite-code"
        requestMappings(0).count shouldBe 1

        groupsInviteCodeOutput.inviteUrl shouldBe groupInviteUrl
      }

      "groupsGetInfo should return group info" in withContext { case Context(wiremockClient) =>
        val wahaClient = ZIO
          .service[WahaClient]
          .provide(
            WahaClient.live,
            buildWahaConfigLayer(wiremockClient.config),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val input = GroupsGetInfoInput(
          sessionID = sessionID,
          groupID = groupID,
        )

        val expectedGroupName          = GroupName.assume("Waha Group")
        val expectedOwnerUserID        = UserID.assume("1000@lid")
        val expectedOwnerUserAccountID = UserAccountID.assume("1000@c.us")
        val expectedOwnerPhoneNumber   = WhatsAppPhoneNumber.assume("1000@s.whatsapp.net")
        val expectedGroupDescription   = GroupDescription.assume("Waha Description")
        val expectedGroupPictureUrl    = GroupPictureUrl.assume("http://chat.com/image.jpg")
        val expectedParticipants       = List(
          GroupParticipant(
            userID = UserID.assume("1000@lid"),
            userAccountID = UserAccountID.assume("1000@c.us"),
            role = GroupParticipantRole.SuperAdmin,
          ),
          GroupParticipant(
            userID = UserID.assume("1001@lid"),
            userAccountID = UserAccountID.assume("1001@c.us"),
            role = GroupParticipantRole.Admin,
          ),
          GroupParticipant(
            userID = UserID.assume("1002@lid"),
            userAccountID = UserAccountID.assume("1002@c.us"),
            role = GroupParticipantRole.Participant,
          ),
          GroupParticipant(
            userID = UserID.assume("1003@lid"),
            userAccountID = UserAccountID.assume("1003@c.us"),
            role = GroupParticipantRole.Left,
          ),
          GroupParticipant(
            userID = UserID.assume("1004@lid"),
            userAccountID = UserAccountID.assume("1004@c.us"),
            role = GroupParticipantRole.Unknown("member"),
          ),
        )

        val expectedGroupsGetInfoOutput = GroupsGetInfoOutput(
          groupID,
          expectedGroupName,
          expectedOwnerUserID,
          expectedOwnerUserAccountID,
          expectedOwnerPhoneNumber,
          Some(expectedGroupDescription),
          Some(expectedGroupPictureUrl),
          expectedParticipants,
        )

        val groupsGetInfoOutput = wahaClient.groupsGetInfo(input).zioValue
        val requestMappings     =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        requestMappings.size shouldBe 3

        groupsGetInfoOutput shouldBe expectedGroupsGetInfoOutput
      }
    }
  }
}
object WahaClientSpec {
  final case class Context(
      wiremockClient: WiremockClient
  )
}
