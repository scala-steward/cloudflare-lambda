package com.dwolla.lambda.cloudflare.requests.processors

import com.dwolla.cloudflare.domain.model.accounts.{AccountMember, AccountRole, AccountRolePermissions, User}
import com.dwolla.cloudflare.{AccountMemberDoesNotExistException, AccountsClient, FutureCloudflareApiExecutor}
import com.dwolla.lambda.cloudflare.Exceptions.{MissingRoles, UnsupportedAction}
import com.dwolla.lambda.cloudformation.HandlerResponse
import org.json4s.JsonAST.{JArray, JObject, JString}
import org.json4s.{DefaultFormats, Formats, JValue}
import org.slf4j.Logger
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

import scala.concurrent.Future

class AccountMembershipSpec extends Specification with Mockito {
  implicit val ee: ExecutionEnv = ExecutionEnv.fromGlobalExecutionContext
  implicit val formats: Formats = DefaultFormats ++ org.json4s.ext.JodaTimeSerializers.all

  trait Setup extends Scope {
    implicit val mockCloudflareApiExecutor = mock[FutureCloudflareApiExecutor]
    val mockLogger = mock[Logger]
    val mockAccountsClient = mock[AccountsClient[Future]]

    val processor = new AccountMembership() {
      override protected lazy val logger: Logger = mockLogger

      override protected def cloudflareApiClient(executor: FutureCloudflareApiExecutor) = mockAccountsClient
    }
  }

  "process" should {
    "handle a Create action successfully" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List(JString("Fake Role 1"), JString("Fake Role 2"))

      val action = "CREATE"
      val resourceProperties: Map[String, JValue] = Map(
        "AccountMember" → JObject(
          "AccountID" → JString(accountId),
          "EmailAddress" → JString(emailAddress),
          "Roles" → JArray(roleNames)
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
          firstName = null,
          lastName = null,
          emailAddress = emailAddress,
          twoFactorEnabled = false
        ),
        status = "pending",
        roles = accountRoles.toList
      )

      mockAccountsClient.getRolesForAccount(accountId) returns Future.apply(accountRoles)
      mockAccountsClient.addMemberToAccount(accountId, emailAddress, List("1111", "2222")) returns Future.apply(accountMember)

      val output = processor.process(action, None, resourceProperties)

      output must beLike[HandlerResponse] {
        case handlerResponse ⇒
          handlerResponse.physicalId must_== s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId"
          handlerResponse.data must havePair("accountMember" → accountMember)
          handlerResponse.data must havePair("created" → Some(accountMember))
          handlerResponse.data must havePair("updated" → None)
          handlerResponse.data must havePair("oldAccountMember" → None)
      }.await
    }

    "throw an exception if roles not found on Create" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List(JString("Fake Role 1"), JString("Fake Role 2"))

      val action = "CREATE"
      val resourceProperties: Map[String, JValue] = Map(
        "AccountMember" → JObject(
          "AccountID" → JString(accountId),
          "EmailAddress" → JString(emailAddress),
          "Roles" → JArray(roleNames)
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

      mockAccountsClient.getRolesForAccount(accountId) returns Future.apply(accountRoles)

      val output = processor.process(action, None, resourceProperties)

      output must throwA(MissingRoles(roleNames.map(_.extract[String]))).await
    }

    "process an Update action successfully" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List(JString("Fake Role 1"), JString("Fake Role 2"), JString("Fake Role 3"))
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val action = "UPDATE"
      val resourceProperties: Map[String, JValue] = Map(
        "AccountMember" → JObject(
          "AccountID" → JString(accountId),
          "EmailAddress" → JString(emailAddress),
          "Roles" → JArray(roleNames)
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

      val updatedAccountMember = AccountMember(
        id = accountMemberId,
        user = User(
          id = "fake-user-id",
          firstName = null,
          lastName = null,
          emailAddress = emailAddress,
          twoFactorEnabled = false
        ),
        status = "accepted",
        roles = accountRoles.toList
      )

      mockAccountsClient.getAccountMember(accountId, accountMemberId) returns Future.apply(Some(originalAccountMember))
      mockAccountsClient.getRolesForAccount(accountId) returns Future.apply(accountRoles)
      mockAccountsClient.updateAccountMember(accountId, updatedAccountMember) returns Future.apply(updatedAccountMember)

      val output = processor.process(action, physicalResourceId, resourceProperties)

      output must beLike[HandlerResponse] {
        case handlerResponse ⇒
          handlerResponse.physicalId must_== physicalResourceId.get
          handlerResponse.data must havePair("accountMember" → updatedAccountMember)
          handlerResponse.data must havePair("created" → None)
          handlerResponse.data must havePair("updated" → Some(updatedAccountMember))
          handlerResponse.data must havePair("oldAccountMember" → Some(originalAccountMember))
      }.await
    }

    "throw an exception if accounts don't match on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List(JString("Fake Role 1"), JString("Fake Role 2"), JString("Fake Role 3"))
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/accounts/fake-account-id2/members/$accountMemberId")

      val action = "UPDATE"
      val resourceProperties: Map[String, JValue] = Map(
        "AccountMember" → JObject(
          "AccountID" → JString(accountId),
          "EmailAddress" → JString(emailAddress),
          "Roles" → JArray(roleNames)
        )
      )

      val output = processor.process(action, physicalResourceId, resourceProperties)

      output must throwA(new RuntimeException("Mismatched accounts")).await
    }

    "throw an exception if existing account member not found on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List(JString("Fake Role 1"), JString("Fake Role 2"), JString("Fake Role 3"))
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val action = "UPDATE"
      val resourceProperties: Map[String, JValue] = Map(
        "AccountMember" → JObject(
          "AccountID" → JString(accountId),
          "EmailAddress" → JString(emailAddress),
          "Roles" → JArray(roleNames)
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

      mockAccountsClient.getAccountMember(accountId, accountMemberId) returns Future.apply(None)
      mockAccountsClient.getRolesForAccount(accountId) returns Future.apply(accountRoles)

      val output = processor.process(action, physicalResourceId, resourceProperties)

      output must throwA(new RuntimeException("Account member not found")).await
    }

    "throw an exception if email address changed on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List(JString("Fake Role 1"), JString("Fake Role 2"), JString("Fake Role 3"))
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val action = "UPDATE"
      val resourceProperties: Map[String, JValue] = Map(
        "AccountMember" → JObject(
          "AccountID" → JString(accountId),
          "EmailAddress" → JString(emailAddress),
          "Roles" → JArray(roleNames)
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

      mockAccountsClient.getAccountMember(accountId, accountMemberId) returns Future.apply(Some(originalAccountMember))
      mockAccountsClient.getRolesForAccount(accountId) returns Future.apply(accountRoles)

      val output = processor.process(action, physicalResourceId, resourceProperties)

      output must throwA(new RuntimeException("Unable to update email address for account member")).await
    }

    "throw an exception if roles not found on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List(JString("Fake Role 1"), JString("Fake Role 2"), JString("Fake Role 3"))
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val action = "UPDATE"
      val resourceProperties: Map[String, JValue] = Map(
        "AccountMember" → JObject(
          "AccountID" → JString(accountId),
          "EmailAddress" → JString(emailAddress),
          "Roles" → JArray(roleNames)
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

      mockAccountsClient.getAccountMember(accountId, accountMemberId) returns Future.apply(Some(originalAccountMember))
      mockAccountsClient.getRolesForAccount(accountId) returns Future.apply(accountRoles.take(2))

      val output = processor.process(action, physicalResourceId, resourceProperties)

      output must throwA(MissingRoles(roleNames.map(_.extract[String]))).await
    }

    "throw an exception if invalid physical resource id format on Update" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List(JString("Fake Role 1"), JString("Fake Role 2"), JString("Fake Role 3"))
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/$accountId/$accountMemberId")

      val action = "UPDATE"
      val resourceProperties: Map[String, JValue] = Map(
        "AccountMember" → JObject(
          "AccountID" → JString(accountId),
          "EmailAddress" → JString(emailAddress),
          "Roles" → JArray(roleNames)
        )
      )

      val output = processor.process(action, physicalResourceId, resourceProperties)

      output must throwA(new RuntimeException("Passed string does not match URL pattern for Cloudflare account member record")).await
      there was one (mockLogger).error(s"The physical resource id ${physicalResourceId.get} does not match the URL pattern for a Cloudflare account")
    }

    "process a Delete action successfully" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List(JString("Fake Role 1"), JString("Fake Role 2"), JString("Fake Role 3"))
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val action = "DELETE"
      val resourceProperties: Map[String, JValue] = Map(
        "AccountMember" → JObject(
          "AccountID" → JString(accountId),
          "EmailAddress" → JString(emailAddress),
          "Roles" → JArray(roleNames)
        )
      )

      mockAccountsClient.removeAccountMember(accountId, accountMemberId) returns Future.apply(accountMemberId)

      val output = processor.process(action, physicalResourceId, resourceProperties)

      output must beLike[HandlerResponse] {
        case handlerResponse ⇒
          handlerResponse.physicalId must_== physicalResourceId.get
          handlerResponse.data must havePair("deletedAccountMemberId" → accountMemberId)
      }.await
    }

    "process a Delete action successfully even if existing account member not found" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List(JString("Fake Role 1"), JString("Fake Role 2"), JString("Fake Role 3"))
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/accounts/$accountId/members/$accountMemberId")

      val action = "DELETE"
      val resourceProperties: Map[String, JValue] = Map(
        "AccountMember" → JObject(
          "AccountID" → JString(accountId),
          "EmailAddress" → JString(emailAddress),
          "Roles" → JArray(roleNames)
        )
      )

      val ex = AccountMemberDoesNotExistException(accountId, accountMemberId)
      mockAccountsClient.removeAccountMember(accountId, accountMemberId) returns Future.failed(ex)

      val output = processor.process(action, physicalResourceId, resourceProperties)

      output must beLike[HandlerResponse] {
        case handlerResponse ⇒
          handlerResponse.physicalId must_== physicalResourceId.get
          handlerResponse.data must not(havePair("deletedAccountMemberId" → accountMemberId))
      }.await

      there was one (mockLogger).error("The record could not be deleted because it did not exist; nonetheless, responding with Success!", ex)
    }

    "throw an exception if invalid physical resource id format on Delete" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List(JString("Fake Role 1"), JString("Fake Role 2"), JString("Fake Role 3"))
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/$accountId/$accountMemberId")

      val action = "DELETE"
      val resourceProperties: Map[String, JValue] = Map(
        "AccountMember" → JObject(
          "AccountID" → JString(accountId),
          "EmailAddress" → JString(emailAddress),
          "Roles" → JArray(roleNames)
        )
      )

      val output = processor.process(action, physicalResourceId, resourceProperties)

      output must throwA(new RuntimeException("Passed string does not match URL pattern for Cloudflare account member record")).await
      there was one (mockLogger).error(s"The physical resource id ${physicalResourceId.get} does not match the URL pattern for a Cloudflare account")
    }

    "throw an exception if action not supported" in new Setup {
      val accountId = "fake-account-id1"
      val accountMemberId = "fake-account-member-id"
      val emailAddress = "test@test.com"
      val roleNames = List(JString("Fake Role 1"), JString("Fake Role 2"), JString("Fake Role 3"))
      val physicalResourceId = Some(s"https://api.cloudflare.com/client/v4/$accountId/$accountMemberId")

      val action = "BUILD"
      val resourceProperties: Map[String, JValue] = Map(
        "AccountMember" → JObject(
          "AccountID" → JString(accountId),
          "EmailAddress" → JString(emailAddress),
          "Roles" → JArray(roleNames)
        )
      )

      val output = processor.process(action, physicalResourceId, resourceProperties)

      output must throwA(UnsupportedAction(action)).await
      there was one (mockLogger).error(s"The action $action is not supported by the processor")
    }
  }
}
