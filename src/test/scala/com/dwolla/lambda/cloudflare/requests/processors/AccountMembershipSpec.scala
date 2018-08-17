package com.dwolla.lambda.cloudflare.requests.processors

import cats.effect.IO
import com.dwolla.cloudflare.domain.model.accounts._
import com.dwolla.cloudflare._
import com.dwolla.lambda.cloudformation.HandlerResponse
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import org.slf4j.Logger
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import _root_.fs2._
import com.dwolla.lambda.cloudflare.Exceptions._
import org.specs2.concurrent.ExecutionEnv

class AccountMembershipSpec(implicit ee: ExecutionEnv) extends Specification with Mockito {
  trait Setup extends Scope {
    val mockExecutor = mock[StreamingCloudflareApiExecutor[IO]]
    val mockLogger: Logger = mock[Logger]

    def buildProcessor(fakeClient: AccountsClient[IO], log: Logger): AccountMembership =
      new AccountMembership(mockExecutor) {
        override protected lazy val logger: Logger = log
        override protected lazy val accountsClient = fakeClient
      }
  }

  "process Create/Update" should {
    "handle a Create action successfully" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List(Json.fromString("Fake Role 1"), Json.fromString("Fake Role 2"))

      val action = "CREATE"
      val resourceProperties: Map[String, Json] = Map(
        "AccountMember" → Json.obj(
          "AccountID" → Json.fromString(accountId),
          "EmailAddress" → Json.fromString(emailAddress),
          "Roles" → Json.fromValues(roleNames)
        )
      )

      val accountRoles = Set(
        AccountRole(
          id = "1111",
          name = "Fake Role 1",
          description = "this is the first fake role",
          permissions = Map[String, AccountRolePermissions](
            "analytics" → AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "2222",
          name = "Fake Role 2",
          description = "second fake role",
          permissions = Map[String, AccountRolePermissions](
            "zone" → AccountRolePermissions(read = true, edit = false),
            "logs" → AccountRolePermissions(read = true, edit = false)
          )
        )
      )

      val accountMember = AccountMember(
        id = accountMemberId,
        user = User(
          id = "fake-user-id",
          firstName = None,
          lastName = None,
          emailAddress = emailAddress,
          twoFactorEnabled = false
        ),
        status = "pending",
        roles = accountRoles.toList
      )

      val fakeAccountsClient = new FakeAccountsClient() {
        override def listRoles(accountId: String): Stream[IO, AccountRole] = Stream.emits(accountRoles.toSeq)

        override def addMember(accountId: String, emailAddress: String, roleIds: List[String]): Stream[IO, AccountMember] = Stream.emit(accountMember)
      }

      val processor = buildProcessor(fakeAccountsClient, mockLogger)

      private val output: Stream[IO, HandlerResponse] = processor.process(action, None, resourceProperties)

      output.compile.last.unsafeToFuture() must beSome[HandlerResponse].like {
        case handlerResponse ⇒
          handlerResponse.physicalId must_== s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId"
          handlerResponse.data must havePair("accountMemberId" → accountMemberId.asJson)
      }.await
    }

    "throw an exception if roles not found on Create" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2")

      val action = "CREATE"
      val resourceProperties: Map[String, Json] = Map(
        "AccountMember" → Json.obj(
          "AccountID" → Json.fromString(accountId),
          "EmailAddress" → Json.fromString(emailAddress),
          "Roles" → Json.arr(roleNames.map(_.asJson): _*)
        )
      )

      val accountRoles = Set(
        AccountRole(
          id = "1111",
          name = "Fake Role 1",
          description = "this is the first fake role",
          permissions = Map[String, AccountRolePermissions](
            "analytics" → AccountRolePermissions(read = true, edit = false)
          )
        )
      )

      val fakeAccountsClient = new FakeAccountsClient() {
        override def listRoles(accountId: String): Stream[IO, AccountRole] = Stream.emits(accountRoles.toSeq)
      }

      val processor = buildProcessor(fakeAccountsClient, mockLogger)

      private val output = processor.process(action, None, resourceProperties)

      output.compile.toList.unsafeToFuture() must throwA(MissingRoles(roleNames)).await
    }

    "process an Update action successfully" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val action = "UPDATE"
      val resourceProperties: Map[String, Json] = Map(
        "AccountMember" → Json.obj(
          "AccountID" → Json.fromString(accountId),
          "EmailAddress" → Json.fromString(emailAddress),
          "Roles" → Json.arr(roleNames.map(_.asJson): _*)
        )
      )

      val accountRoles = Set(
        AccountRole(
          id = "1111",
          name = "Fake Role 1",
          description = "this is the first fake role",
          permissions = Map[String, AccountRolePermissions](
            "analytics" → AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "2222",
          name = "Fake Role 2",
          description = "second fake role",
          permissions = Map[String, AccountRolePermissions](
            "zone" → AccountRolePermissions(read = true, edit = false),
            "logs" → AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "3333",
          name = "Fake Role 3",
          description = "third fake role",
          permissions = Map[String, AccountRolePermissions](
            "crypto" → AccountRolePermissions(read = true, edit = false)
          )
        )
      )

      val originalAccountMember = AccountMember(
        id = accountMemberId,
        user = User(
          id = "fake-user-id",
          firstName = None,
          lastName = None,
          emailAddress = emailAddress,
          twoFactorEnabled = false
        ),
        status = "accepted",
        roles = accountRoles.take(2).toList
      )

      val updatedAccountMember = AccountMember(
        id = accountMemberId,
        user = User(
          id = "fake-user-id",
          firstName = None,
          lastName = None,
          emailAddress = emailAddress,
          twoFactorEnabled = false
        ),
        status = "accepted",
        roles = accountRoles.toList
      )

      val fakeAccountsClient = new FakeAccountsClient() {
        override def listRoles(accountId: String): Stream[IO, AccountRole] = Stream.emits(accountRoles.toSeq)

        override def updateMember(accountId: String, accountMember: AccountMember) = Stream.emit(updatedAccountMember)

        override def getMember(accountId: String, accountMemberId: String) = Stream.emit(originalAccountMember)
      }

      val processor = buildProcessor(fakeAccountsClient, mockLogger)

      private val output = processor.process(action, physicalResourceId, resourceProperties)

      output.compile.last.unsafeToFuture() must beSome[HandlerResponse].like {
        case handlerResponse ⇒
          handlerResponse.physicalId must_== physicalResourceId.get
          handlerResponse.data must havePair("accountMemberId" → accountMemberId.asJson)
      }.await

      val responseData = Json.obj(
        "accountMember" → updatedAccountMember.asJson,
        "created" → None.asJson,
        "updated" → Some(updatedAccountMember).asJson,
        "oldAccountMember" → originalAccountMember.asJson
      )

      there was one(mockLogger).info(resourceProperties("AccountMember").as[AccountMembershipRequest](AccountMembership.accountMembershipRequestDecoder).right.get.toString)
      there was one(mockLogger).info(s"Cloudflare AccountMembership response data: ${responseData.noSpaces}")
    }

    "throw an exception if accounts don't match on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = s"https://api.cloudflare.com/client/v4/accounts/fake-account-id2/members/$accountMemberId"

      val action = "UPDATE"
      val resourceProperties: Map[String, Json] = Map(
        "AccountMember" → Json.obj(
          "AccountID" → Json.fromString(accountId),
          "EmailAddress" → Json.fromString(emailAddress),
          "Roles" → Json.arr(roleNames.map(_.asJson): _*)
        )
      )

      val fakeAccountsClient = new FakeAccountsClient() {}

      val processor = buildProcessor(fakeAccountsClient, mockLogger)

      private val output = processor.process(action, Some(physicalResourceId), resourceProperties)

      output.compile.last.unsafeToFuture() must throwA(AccountIdMismatch(accountId, physicalResourceId)).await
    }

    "throw an exception if existing account member not found on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId"

      val action = "UPDATE"
      val resourceProperties: Map[String, Json] = Map(
        "AccountMember" → Json.obj(
          "AccountID" → Json.fromString(accountId),
          "EmailAddress" → Json.fromString(emailAddress),
          "Roles" → Json.arr(roleNames.map(_.asJson): _*)
        )
      )

      val accountRoles = Set(
        AccountRole(
          id = "1111",
          name = "Fake Role 1",
          description = "this is the first fake role",
          permissions = Map[String, AccountRolePermissions](
            "analytics" → AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "2222",
          name = "Fake Role 2",
          description = "second fake role",
          permissions = Map[String, AccountRolePermissions](
            "zone" → AccountRolePermissions(read = true, edit = false),
            "logs" → AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "3333",
          name = "Fake Role 3",
          description = "third fake role",
          permissions = Map[String, AccountRolePermissions](
            "crypto" → AccountRolePermissions(read = true, edit = false)
          )
        )
      )

      val fakeAccountsClient = new FakeAccountsClient() {
        override def listRoles(accountId: String) = Stream.emits(accountRoles.toSeq)

        override def getMember(accountId: String, accountMemberId: String) = Stream.empty
      }

      val processor = buildProcessor(fakeAccountsClient, mockLogger)

      private val output = processor.process(action, Some(physicalResourceId), resourceProperties)

      output.compile.last.unsafeToFuture() must throwA(AccountMemberNotFound(Some(physicalResourceId))).await
    }

    "throw an exception if email address changed on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId"

      val action = "UPDATE"
      val resourceProperties: Map[String, Json] = Map(
        "AccountMember" → Json.obj(
          "AccountID" → Json.fromString(accountId),
          "EmailAddress" → Json.fromString(emailAddress),
          "Roles" → Json.arr(roleNames.map(_.asJson): _*)
        )
      )

      val accountRoles = Set(
        AccountRole(
          id = "1111",
          name = "Fake Role 1",
          description = "this is the first fake role",
          permissions = Map[String, AccountRolePermissions](
            "analytics" → AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "2222",
          name = "Fake Role 2",
          description = "second fake role",
          permissions = Map[String, AccountRolePermissions](
            "zone" → AccountRolePermissions(read = true, edit = false),
            "logs" → AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "3333",
          name = "Fake Role 3",
          description = "third fake role",
          permissions = Map[String, AccountRolePermissions](
            "crypto" → AccountRolePermissions(read = true, edit = false)
          )
        )
      )

      val originalAccountMember = AccountMember(
        id = accountMemberId,
        user = User(
          id = "fake-user-id",
          firstName = null,
          lastName = null,
          emailAddress = "not_the_same@test.com",
          twoFactorEnabled = false
        ),
        status = "accepted",
        roles = accountRoles.take(2).toList
      )

      val fakeAccountsClient = new FakeAccountsClient() {
        override def listRoles(accountId: String) = Stream.emits(accountRoles.toSeq)

        override def getMember(accountId: String, accountMemberId: String) = Stream.emit(originalAccountMember)
      }

      val processor = buildProcessor(fakeAccountsClient, mockLogger)

      private val output = processor.process(action, Some(physicalResourceId), resourceProperties)

      output.compile.last.unsafeToFuture() must throwA(RefusingToChangeEmailAddress).await
    }

    "throw an exception if roles not found on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val action = "UPDATE"
      val resourceProperties: Map[String, Json] = Map(
        "AccountMember" → Json.obj(
          "AccountID" → Json.fromString(accountId),
          "EmailAddress" → Json.fromString(emailAddress),
          "Roles" → Json.arr(roleNames.map(_.asJson): _*),
        )
      )

      val accountRoles = Set(
        AccountRole(
          id = "1111",
          name = "Fake Role 1",
          description = "this is the first fake role",
          permissions = Map[String, AccountRolePermissions](
            "analytics" → AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "2222",
          name = "Fake Role 2",
          description = "second fake role",
          permissions = Map[String, AccountRolePermissions](
            "zone" → AccountRolePermissions(read = true, edit = false),
            "logs" → AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "3333",
          name = "Fake Role 3",
          description = "third fake role",
          permissions = Map[String, AccountRolePermissions](
            "crypto" → AccountRolePermissions(read = true, edit = false)
          )
        )
      )

      val originalAccountMember = AccountMember(
        id = accountMemberId,
        user = User(
          id = "fake-user-id",
          firstName = null,
          lastName = null,
          emailAddress = emailAddress,
          twoFactorEnabled = false
        ),
        status = "accepted",
        roles = accountRoles.take(2).toList
      )

      val fakeAccountsClient = new FakeAccountsClient() {
        override def listRoles(accountId: String) = Stream.emits(accountRoles.toSeq).take(2)

        override def getMember(accountId: String, accountMemberId: String) = Stream.emit(originalAccountMember)
      }

      val processor = buildProcessor(fakeAccountsClient, mockLogger)

      private val output = processor.process(action, physicalResourceId, resourceProperties)

      output.compile.last.unsafeToFuture() must throwA(MissingRoles(roleNames)).await
    }

    "throw an exception if invalid physical resource id format on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = "not-a-cloudflare-uri"

      val action = "UPDATE"
      val resourceProperties: Map[String, Json] = Map(
        "AccountMember" → Json.obj(
          "AccountID" → Json.fromString(accountId),
          "EmailAddress" → Json.fromString(emailAddress),
          "Roles" → Json.arr(roleNames.map(_.asJson): _*),
        )
      )

      val fakeAccountsClient = new FakeAccountsClient() {}
      val processor = buildProcessor(fakeAccountsClient, mockLogger)

      private val output = processor.process(action, Some(physicalResourceId), resourceProperties)

      output.compile.last.unsafeToFuture() must throwA(InvalidCloudflareAccountUri(physicalResourceId)).await
    }
  }

  "process Delete" should {

    "process a Delete action successfully" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val action = "DELETE"
      val resourceProperties: Map[String, Json] = Map(
        "AccountMember" → Json.obj(
          "AccountID" → Json.fromString(accountId),
          "EmailAddress" → Json.fromString(emailAddress),
          "Roles" → Json.arr(roleNames.map(_.asJson): _*),
        )
      )

      private val client = new FakeAccountsClient() {
        override def getMember(accountId: String, accountMemberId: String) = Stream.empty

        override def removeMember(accountId: String, accountMemberId: String) = Stream.emit(accountMemberId)
      }

      val processor = buildProcessor(client, mockLogger)

      private val output = processor.process(action, physicalResourceId, resourceProperties)

      output.compile.last.unsafeToFuture() must beSome[HandlerResponse].like {
        case handlerResponse ⇒
          handlerResponse.physicalId must_== physicalResourceId.get
          handlerResponse.data must havePair("accountMemberId" → accountMemberId.asJson)
      }.await
    }

    "process a Delete action successfully even if existing account member not found" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val action = "DELETE"
      val resourceProperties: Map[String, Json] = Map(
        "AccountMember" → Json.obj(
          "AccountID" → Json.fromString(accountId),
          "EmailAddress" → Json.fromString(emailAddress),
          "Roles" → Json.arr(roleNames.map(_.asJson): _*),
        )
      )

      private val client = new FakeAccountsClient() {
        override def getMember(accountId: String, accountMemberId: String) = Stream.empty

        override def removeMember(accountId: String, accountMemberId: String) = Stream.raiseError(AccountMemberDoesNotExistException(accountId, accountMemberId))
      }

      val processor = buildProcessor(client, mockLogger)

      private val output = processor.process(action, physicalResourceId, resourceProperties)

      output.compile.last.unsafeToFuture() must beSome[HandlerResponse].like {
        case handlerResponse ⇒
          handlerResponse.physicalId must_== physicalResourceId.get
          handlerResponse.data must not(havePair("accountMemberId" → accountMemberId.asJson))
      }.await

      there was one (mockLogger).error("The record could not be deleted because it did not exist; nonetheless, responding with Success!", AccountMemberDoesNotExistException(accountId, accountMemberId))
    }

    "throw an exception if invalid physical resource id format on Delete" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = s"https://api.cloudflare.com/client/v4/$accountId/$accountMemberId"

      val action = "DELETE"
      val resourceProperties: Map[String, Json] = Map(
        "AccountMember" → Json.obj(
          "AccountID" → Json.fromString(accountId),
          "EmailAddress" → Json.fromString(emailAddress),
          "Roles" → Json.arr(roleNames.map(_.asJson): _*),
        )
      )

      private val client = new FakeAccountsClient() {}

      val processor = buildProcessor(client, mockLogger)

      private val output = processor.process(action, Some(physicalResourceId), resourceProperties)

      output.compile.last.unsafeToFuture() must throwA(InvalidCloudflareAccountUri(physicalResourceId)).await
    }

  }

  "Process" should {
    "throw an exception if action not supported" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val action = "BUILD"
      val resourceProperties: Map[String, Json] = Map(
        "AccountMember" → Json.obj(
          "AccountID" → Json.fromString(accountId),
          "EmailAddress" → Json.fromString(emailAddress),
          "Roles" → Json.arr(roleNames.map(_.asJson): _*),
        )
      )

      private val client = new FakeAccountsClient() {
        override def getMember(accountId: String, accountMemberId: String) = Stream.empty
      }
      val processor = buildProcessor(client, mockLogger)

      private val output = processor.process(action, physicalResourceId, resourceProperties)

      output.compile.last.unsafeToFuture() must throwA(UnsupportedAction(action)).await
    }

  }

}

class FakeAccountsClient extends AccountsClient[IO] {
  override def list(): Stream[IO, Account] = Stream.raiseError(new NotImplementedError())
  override def getById(accountId: String): Stream[IO, Account] = Stream.raiseError(new NotImplementedError())
  override def getByName(name: String): Stream[IO, Account] = Stream.raiseError(new NotImplementedError())
  override def listRoles(accountId: String): Stream[IO, AccountRole] = Stream.raiseError(new NotImplementedError())
  override def getMember(accountId: String, accountMemberId: String): Stream[IO, AccountMember] = Stream.raiseError(new NotImplementedError())
  override def addMember(accountId: String, emailAddress: String, roleIds: List[String]): Stream[IO, AccountMember] = Stream.raiseError(new NotImplementedError())
  override def updateMember(accountId: String, accountMember: AccountMember): Stream[IO, AccountMember] = Stream.raiseError(new NotImplementedError())
  override def removeMember(accountId: String, accountMemberId: String): Stream[IO, String] = Stream.raiseError(new NotImplementedError())
}
