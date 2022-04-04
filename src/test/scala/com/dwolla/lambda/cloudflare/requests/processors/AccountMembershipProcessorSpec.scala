package com.dwolla.lambda.cloudflare.requests.processors

import java.util.UUID
import cats.effect._
import com.dwolla.cloudflare.domain.model.accounts._
import com.dwolla.cloudflare._
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import _root_.fs2._
import cats.Applicative
import cats.effect.testing.specs2.CatsEffect
import com.dwolla.cloudflare.domain.model._
import com.dwolla.lambda.cloudflare.Exceptions._
import com.dwolla.circe._
import com.dwolla.lambda.cloudflare.JsonObjectMatchers
import feral.lambda.cloudformation.CloudFormationRequestType._
import feral.lambda.cloudformation._
import org.typelevel.log4cats.noop.NoOpLogger

class AccountMembershipSpec extends Specification with Mockito with JsonObjectMatchers with CatsEffect {
  private implicit def logger[F[_] : Applicative] = NoOpLogger[F]

  trait Setup extends Scope {
    val mockExecutor = mock[StreamingCloudflareApiExecutor[IO]]

    val badRole = AccountRole(
      id = UUID.randomUUID().toString,
      name = "Extra Bonus Fake Role",
      description = "Don't use me!",
      permissions = Map.empty[String, AccountRolePermissions]
    )

    val badRoleId: AccountRole => Boolean = _.id == badRole.id

    def buildProcessor(fakeAccountsClient: AccountsClient[IO] = new FakeAccountsClient,
                       fakeMembersClient: AccountMembersClient[IO] = new FakeAccountMembersClient): AccountMembership[IO] =
      new AccountMembership(fakeAccountsClient, fakeMembersClient)
  }

  "process Create/Update" should {
    "handle a Create action successfully" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id".asInstanceOf[AccountMemberId]
      val emailAddress = "test@test.com"
      val roleNames = List(Json.fromString("Fake Role 1"), Json.fromString("Fake Role 2"))

      val resourceProperties = JsonObject(
        "AccountMember" -> Json.obj(
          "AccountID" -> Json.fromString(accountId),
          "EmailAddress" -> Json.fromString(emailAddress),
          "Roles" -> Json.fromValues(roleNames)
        )
      )

      val accountRoles = Set(
        AccountRole(
          id = "1111",
          name = "Fake Role 1",
          description = "this is the first fake role",
          permissions = Map[String, AccountRolePermissions](
            "analytics" -> AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "2222",
          name = "Fake Role 2",
          description = "second fake role",
          permissions = Map[String, AccountRolePermissions](
            "zone" -> AccountRolePermissions(read = true, edit = false),
            "logs" -> AccountRolePermissions(read = true, edit = false)
          )
        ),
        badRole
      )

      val accountMember = AccountMember(
        id = accountMemberId,
        user = User(
          id = "fake-user-id".asInstanceOf[UserId],
          firstName = None,
          lastName = None,
          emailAddress = emailAddress,
          twoFactorEnabled = false
        ),
        status = "pending",
        roles = accountRoles.toList.filterNot(badRoleId)
      )

      val fakeAccountsClient = new FakeAccountsClient() {
        override def listRoles(accountId: AccountId): Stream[IO, AccountRole] = Stream.emits(accountRoles.toSeq)
      }

      val fakeAccountMembersClient = new FakeAccountMembersClient() {
        override def addMember(accountId: AccountId, emailAddress: String, roleIds: List[String]): Stream[IO, AccountMember] =
          if (accountMember.roles.exists(badRoleId)) Stream.raiseError[IO](AccountContainsUnrequestedRolesException)
          else Stream.emit(accountMember)
      }

      val processor = buildProcessor(fakeAccountsClient, fakeAccountMembersClient)

      private val output: Stream[IO, HandlerResponse[Json]] = processor.process(CreateRequest, None, resourceProperties)
      output.compile.last.attempt.map(_ must beRight.like {
        case Some(handlerResponse) =>
          handlerResponse.physicalId must_== s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId"
          handlerResponse.data must haveKeyValuePair("accountMemberId" -> accountMemberId.asJson)
      })

//      output.compile.last.attempt.map(_ must beRight[HandlerResponse[Json]].like {
//        case handlerResponse =>
//          handlerResponse.physicalId must_== s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId"
//          handlerResponse.data must haveKeyValuePair("accountMemberId" -> accountMemberId.asJson)
//      })
    }

    "throw an exception if roles not found on Create" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2")

      val resourceProperties = JsonObject(
        "AccountMember" -> Json.obj(
          "AccountID" -> Json.fromString(accountId),
          "EmailAddress" -> Json.fromString(emailAddress),
          "Roles" -> Json.arr(roleNames.map(_.asJson): _*)
        )
      )

      val accountRoles = Set(
        AccountRole(
          id = "1111",
          name = "Fake Role 1",
          description = "this is the first fake role",
          permissions = Map[String, AccountRolePermissions](
            "analytics" -> AccountRolePermissions(read = true, edit = false)
          )
        )
      )

      val fakeClient = new FakeAccountsClient() {
        override def listRoles(accountId: AccountId): Stream[IO, AccountRole] = Stream.emits(accountRoles.toSeq)
      }

      val processor = buildProcessor(fakeClient)

      private val output = processor.process(CreateRequest, None, resourceProperties)

      output.compile.toList.attempt.map(_ must_== Left(MissingRoles(roleNames)))
    }

    "process an Update action successfully" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id".asInstanceOf[AccountMemberId]
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = PhysicalResourceId(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val resourceProperties = JsonObject(
        "AccountMember" -> Json.obj(
          "AccountID" -> Json.fromString(accountId),
          "EmailAddress" -> Json.fromString(emailAddress),
          "Roles" -> Json.arr(roleNames.map(_.asJson): _*)
        )
      )

      val accountRoles = Set(
        AccountRole(
          id = "1111",
          name = "Fake Role 1",
          description = "this is the first fake role",
          permissions = Map[String, AccountRolePermissions](
            "analytics" -> AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "2222",
          name = "Fake Role 2",
          description = "second fake role",
          permissions = Map[String, AccountRolePermissions](
            "zone" -> AccountRolePermissions(read = true, edit = false),
            "logs" -> AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "3333",
          name = "Fake Role 3",
          description = "third fake role",
          permissions = Map[String, AccountRolePermissions](
            "crypto" -> AccountRolePermissions(read = true, edit = false)
          )
        ),
        badRole,
      )

      val originalAccountMember = AccountMember(
        id = accountMemberId,
        user = User(
          id = "fake-user-id".asInstanceOf[UserId],
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
          id = "fake-user-id".asInstanceOf[UserId],
          firstName = None,
          lastName = None,
          emailAddress = emailAddress,
          twoFactorEnabled = false
        ),
        status = "accepted",
        roles = accountRoles.toList.filterNot(badRoleId)
      )

      val fakeAccountsClient = new FakeAccountsClient() {
        override def listRoles(accountId: AccountId): Stream[IO, AccountRole] = Stream.emits(accountRoles.toSeq)
      }

      val fakeAccountMembersClient = new FakeAccountMembersClient() {
        override def updateMember(accountId: AccountId, accountMember: AccountMember) =
          if (accountMember.roles.exists(badRoleId)) Stream.raiseError[IO](AccountContainsUnrequestedRolesException)
          else Stream.emit(updatedAccountMember)

        override def getById(accountId: AccountId, accountMemberId: String) = Stream.emit(originalAccountMember)
      }

      val processor = buildProcessor(fakeAccountsClient, fakeAccountMembersClient)

      private val output = processor.process(UpdateRequest, physicalResourceId, resourceProperties)

      output.compile.last.attempt.map(_ must beRight.like {
        case Some(handlerResponse) =>
          handlerResponse.physicalId must_== physicalResourceId.get
          handlerResponse.data must haveKeyValuePair("accountMemberId" -> accountMemberId.asJson)
      })

      val responseData = Json.obj(
        "accountMember" -> updatedAccountMember.asJson,
        "created" -> None.asJson,
        "updated" -> Some(updatedAccountMember).asJson,
        "oldAccountMember" -> originalAccountMember.asJson
      )

      val request: AccountMembershipRequest =
        resourceProperties("AccountMember").toRight(MissingResourcePropertiesKey("AccountMember")).flatMap(_.as[AccountMembershipRequest](AccountMembership.accountMembershipRequestDecoder)).getOrElse(null)
    }

    "throw an exception if accounts don't match on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = PhysicalResourceId(s"https://api.cloudflare.com/client/v4/accounts/fake-account-id2/members/$accountMemberId")

      val resourceProperties = JsonObject(
        "AccountMember" -> Json.obj(
          "AccountID" -> Json.fromString(accountId),
          "EmailAddress" -> Json.fromString(emailAddress),
          "Roles" -> Json.arr(roleNames.map(_.asJson): _*)
        )
      )

      val processor = buildProcessor()

      private val output = processor.process(UpdateRequest, physicalResourceId, resourceProperties)

      output.compile.last.attempt.map(_ must_== Left(AccountIdMismatch(accountId, physicalResourceId)))
    }

    "throw an exception if existing account member not found on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = PhysicalResourceId(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val resourceProperties = JsonObject(
        "AccountMember" -> Json.obj(
          "AccountID" -> Json.fromString(accountId),
          "EmailAddress" -> Json.fromString(emailAddress),
          "Roles" -> Json.arr(roleNames.map(_.asJson): _*)
        )
      )

      val accountRoles = Set(
        AccountRole(
          id = "1111",
          name = "Fake Role 1",
          description = "this is the first fake role",
          permissions = Map[String, AccountRolePermissions](
            "analytics" -> AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "2222",
          name = "Fake Role 2",
          description = "second fake role",
          permissions = Map[String, AccountRolePermissions](
            "zone" -> AccountRolePermissions(read = true, edit = false),
            "logs" -> AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "3333",
          name = "Fake Role 3",
          description = "third fake role",
          permissions = Map[String, AccountRolePermissions](
            "crypto" -> AccountRolePermissions(read = true, edit = false)
          )
        )
      )

      val fakeAccountsClient = new FakeAccountsClient() {
        override def listRoles(accountId: AccountId) = Stream.emits(accountRoles.toSeq)
      }

      val fakeAccountMembersClient = new FakeAccountMembersClient() {
        override def getById(accountId: AccountId, accountMemberId: String) = Stream.empty
      }

      val processor = buildProcessor(fakeAccountsClient, fakeAccountMembersClient)

      private val output = processor.process(UpdateRequest, (physicalResourceId), resourceProperties)

      output.compile.last.attempt.map(_ must_== Left(AccountMemberNotFound((physicalResourceId))))
    }

    "throw an exception if email address changed on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = PhysicalResourceId(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val resourceProperties = JsonObject(
        "AccountMember" -> Json.obj(
          "AccountID" -> Json.fromString(accountId),
          "EmailAddress" -> Json.fromString(emailAddress),
          "Roles" -> Json.arr(roleNames.map(_.asJson): _*)
        )
      )

      val accountRoles = Set(
        AccountRole(
          id = "1111",
          name = "Fake Role 1",
          description = "this is the first fake role",
          permissions = Map[String, AccountRolePermissions](
            "analytics" -> AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "2222",
          name = "Fake Role 2",
          description = "second fake role",
          permissions = Map[String, AccountRolePermissions](
            "zone" -> AccountRolePermissions(read = true, edit = false),
            "logs" -> AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "3333",
          name = "Fake Role 3",
          description = "third fake role",
          permissions = Map[String, AccountRolePermissions](
            "crypto" -> AccountRolePermissions(read = true, edit = false)
          )
        )
      )

      val originalAccountMember = AccountMember(
        id = accountMemberId.asInstanceOf[AccountMemberId],
        user = User(
          id = "fake-user-id".asInstanceOf[UserId],
          firstName = null,
          lastName = null,
          emailAddress = "not_the_same@test.com",
          twoFactorEnabled = false
        ),
        status = "accepted",
        roles = accountRoles.take(2).toList
      )

      val fakeAccountsClient = new FakeAccountsClient() {
        override def listRoles(accountId: AccountId) = Stream.emits(accountRoles.toSeq)
      }

      val fakeAccountMembersClient = new FakeAccountMembersClient() {
        override def getById(accountId: AccountId, accountMemberId: String) = Stream.emit(originalAccountMember)
      }

      val processor = buildProcessor(fakeAccountsClient, fakeAccountMembersClient)

      private val output = processor.process(UpdateRequest, (physicalResourceId), resourceProperties)

      output.compile.last.attempt.map(_ must_== Left(RefusingToChangeEmailAddress))
    }

    "throw an exception if roles not found on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = PhysicalResourceId(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val resourceProperties = JsonObject(
        "AccountMember" -> Json.obj(
          "AccountID" -> Json.fromString(accountId),
          "EmailAddress" -> Json.fromString(emailAddress),
          "Roles" -> Json.arr(roleNames.map(_.asJson): _*),
        )
      )

      val accountRoles = Set(
        AccountRole(
          id = "1111",
          name = "Fake Role 1",
          description = "this is the first fake role",
          permissions = Map[String, AccountRolePermissions](
            "analytics" -> AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "2222",
          name = "Fake Role 2",
          description = "second fake role",
          permissions = Map[String, AccountRolePermissions](
            "zone" -> AccountRolePermissions(read = true, edit = false),
            "logs" -> AccountRolePermissions(read = true, edit = false)
          )
        ),
        AccountRole(
          id = "3333",
          name = "Fake Role 3",
          description = "third fake role",
          permissions = Map[String, AccountRolePermissions](
            "crypto" -> AccountRolePermissions(read = true, edit = false)
          )
        )
      )

      val originalAccountMember = AccountMember(
        id = accountMemberId.asInstanceOf[AccountMemberId],
        user = User(
          id = "fake-user-id".asInstanceOf[UserId],
          firstName = null,
          lastName = null,
          emailAddress = emailAddress,
          twoFactorEnabled = false
        ),
        status = "accepted",
        roles = accountRoles.take(2).toList
      )

      val fakeAccountsClient = new FakeAccountsClient() {
        override def listRoles(accountId: AccountId) = Stream.emits(accountRoles.toSeq).take(2)
      }

      val fakeAccountMembersClient = new FakeAccountMembersClient() {
        override def getById(accountId: AccountId, accountMemberId: String) = Stream.emit(originalAccountMember)
      }

      val processor = buildProcessor(fakeAccountsClient, fakeAccountMembersClient)

      private val output = processor.process(UpdateRequest, physicalResourceId, resourceProperties)

      output.compile.last.attempt.map(_ must_== Left(MissingRoles(roleNames)))
    }

    "throw an exception if invalid physical resource id format on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = PhysicalResourceId("not-a-cloudflare-uri")

      val resourceProperties = JsonObject(
        "AccountMember" -> Json.obj(
          "AccountID" -> Json.fromString(accountId),
          "EmailAddress" -> Json.fromString(emailAddress),
          "Roles" -> Json.arr(roleNames.map(_.asJson): _*),
        )
      )

      val processor = buildProcessor()

      private val output = processor.process(UpdateRequest, (physicalResourceId), resourceProperties)

      output.compile.last.attempt.map(_ must_== Left(InvalidCloudflareUri(physicalResourceId)))
    }
  }

  "process Delete" should {

    "process a Delete action successfully" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = PhysicalResourceId(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val resourceProperties = JsonObject(
        "AccountMember" -> Json.obj(
          "AccountID" -> Json.fromString(accountId),
          "EmailAddress" -> Json.fromString(emailAddress),
          "Roles" -> Json.arr(roleNames.map(_.asJson): _*),
        )
      )

      private val client = new FakeAccountMembersClient() {
        override def getById(accountId: AccountId, accountMemberId: String) = Stream.empty

        override def removeMember(accountId: AccountId, accountMemberId: String) = Stream.emit(accountMemberId.asInstanceOf[AccountMemberId])
      }

      val processor = buildProcessor(fakeMembersClient = client)

      private val output = processor.process(DeleteRequest, physicalResourceId, resourceProperties)

      output.compile.last.attempt.map(_ must beRight.like {
        case Some(handlerResponse) =>
          handlerResponse.physicalId must_== physicalResourceId.get
          handlerResponse.data must haveKeyValuePair("accountMemberId" -> accountMemberId.asJson)
      })
    }

    "process a Delete action successfully even if existing account member not found" in new Setup {
      val accountId = "fake-account-id1".asInstanceOf[AccountId]
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = PhysicalResourceId(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val resourceProperties = JsonObject(
        "AccountMember" -> Json.obj(
          "AccountID" -> Json.fromString(accountId),
          "EmailAddress" -> Json.fromString(emailAddress),
          "Roles" -> Json.arr(roleNames.map(_.asJson): _*),
        )
      )

      private val client = new FakeAccountMembersClient() {
        override def getById(accountId: AccountId, accountMemberId: String) = Stream.empty

        override def removeMember(accountId: AccountId, accountMemberId: String) = Stream.raiseError[IO](AccountMemberDoesNotExistException(accountId, accountMemberId))
      }

      val processor = buildProcessor(fakeMembersClient = client)

      private val output = processor.process(DeleteRequest, physicalResourceId, resourceProperties)

      output.compile.last.attempt.map(_ must beRight.like {
        case Some(handlerResponse) =>
          handlerResponse.physicalId must_== physicalResourceId.get
          handlerResponse.data must not(haveKeyValuePair("accountMemberId" -> accountMemberId.asJson))
      })
    }

    "throw an exception if invalid physical resource id format on Delete" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = PhysicalResourceId(s"https://api.cloudflare.com/client/v4/$accountId/$accountMemberId")

      val resourceProperties = JsonObject(
        "AccountMember" -> Json.obj(
          "AccountID" -> Json.fromString(accountId),
          "EmailAddress" -> Json.fromString(emailAddress),
          "Roles" -> Json.arr(roleNames.map(_.asJson): _*),
        )
      )

      private val processor = buildProcessor()

      private val output = processor.process(DeleteRequest, (physicalResourceId), resourceProperties)

      output.compile.last.attempt.map(_ must_== Left(InvalidCloudflareUri(physicalResourceId)))
    }

  }

  "Process" should {
    "throw an exception if action not supported" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List("Fake Role 1", "Fake Role 2", "Fake Role 3")
      val physicalResourceId = PhysicalResourceId(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val action = "BUILD"
      val resourceProperties = JsonObject(
        "AccountMember" -> Json.obj(
          "AccountID" -> Json.fromString(accountId),
          "EmailAddress" -> Json.fromString(emailAddress),
          "Roles" -> Json.arr(roleNames.map(_.asJson): _*),
        )
      )

      private val client = new FakeAccountMembersClient() {
        override def getById(accountId: AccountId, accountMemberId: String) = Stream.empty
      }
      val processor = buildProcessor(fakeMembersClient = client)

      private val output = processor.process(OtherRequestType(action), physicalResourceId, resourceProperties)

      output.compile.last.attempt.map(_ must_== Left(UnsupportedRequestType(OtherRequestType(action))))
    }

  }

}

class FakeAccountsClient extends AccountsClient[IO] {
  override def list(): Stream[IO, Account] = Stream.raiseError[IO](new NotImplementedError())
  override def getById(accountId: String): Stream[IO, Account] = Stream.raiseError[IO](new NotImplementedError())
  override def getByName(name: String): Stream[IO, Account] = Stream.raiseError[IO](new NotImplementedError())
  override def listRoles(accountId: AccountId): Stream[IO, AccountRole] = Stream.raiseError[IO](new NotImplementedError())
}

class FakeAccountMembersClient extends AccountMembersClient[IO] {
  override def getById(accountId: AccountId, memberId: String): Stream[IO, AccountMember] = Stream.raiseError[IO](new NotImplementedError())
  override def addMember(accountId: AccountId, emailAddress: String, roleIds: List[String]): Stream[IO, AccountMember] = Stream.raiseError[IO](new NotImplementedError())
  override def updateMember(accountId: AccountId, accountMember: AccountMember): Stream[IO, AccountMember] = Stream.raiseError[IO](new NotImplementedError())
  override def removeMember(accountId: AccountId, accountMemberId: String): Stream[IO, AccountMemberId] = Stream.raiseError[IO](new NotImplementedError())
}

object AccountContainsUnrequestedRolesException extends RuntimeException("exception intentionally thrown by test", null, true, false)
