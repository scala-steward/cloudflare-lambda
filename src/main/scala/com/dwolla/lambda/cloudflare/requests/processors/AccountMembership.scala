package com.dwolla.lambda.cloudflare.requests.processors

import cats.implicits._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model.{Create, CreateOrUpdate, Update}
import com.dwolla.cloudflare.domain.model.accounts.{AccountMember, AccountRole}
import com.dwolla.lambda.cloudflare.Exceptions.{MissingRoles, UnsupportedAction}
import com.dwolla.lambda.cloudflare.util.JValue._
import com.dwolla.lambda.cloudformation.HandlerResponse
import org.json4s.{DefaultFormats, Formats, JValue}
import org.json4s.native.Serialization
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

class AccountMembership(implicit ec: ExecutionContext) extends ResourceRequestProcessor {
  protected val accountMemberUriRegex: Regex = "https://api.cloudflare.com/client/v4/accounts/([^/]+)/members/([^/]+)".r("accountId", "accountMemberId")

  protected implicit val formats: Formats = DefaultFormats ++ org.json4s.ext.JodaTimeSerializers.all

  protected lazy val logger: Logger = LoggerFactory.getLogger("LambdaLogger")

  protected def cloudflareApiClient(executor: FutureCloudflareApiExecutor) = new AccountsClient(executor)

  def process(action: String, physicalResourceId: Option[String], properties: Map[String, JValue])
             (implicit executor: FutureCloudflareApiExecutor): Future[HandlerResponse] = {
    implicit val accountsClient: AccountsClient[Future] = cloudflareApiClient(executor)

    val request = parseRequestFrom(properties)

    action match {
      case "CREATE" ⇒ handleCreate(request)
      case "UPDATE" ⇒ handleUpdate(request, physicalResourceId.get)
      case "DELETE" ⇒ handleDelete(physicalResourceId.get)
      case _ ⇒
        logger.error(s"The action $action is not supported by the processor")
        Future.failed(UnsupportedAction(action))
    }
  }

  private def handleCreate(request: AccountMembershipRequest)
                  (implicit accountsClient: AccountsClient[Future]): Future[HandlerResponse] = {
    for {
      accountRoles ← accountsClient.getRolesForAccount(request.accountId)
      foundRoles = findRequestedRoles(request.roles, accountRoles)
      create ← accountsClient.addMemberToAccount(request.accountId, request.emailAddress, foundRoles.map(_.id)).map(Create(_))
    } yield {
      buildCreateOrUpdateResponse(request.accountId, create, None)
    }
  }

  private def handleUpdate(request: AccountMembershipRequest, physicalResourceId: String)
                  (implicit accountsClient: AccountsClient[Future]): Future[HandlerResponse] = {
    physicalResourceId match {
      case accountMemberUriRegex(accountId, accountMemberId) ⇒
        if (accountId != request.accountId) return Future.failed(new RuntimeException("Mismatched accounts"))

        for {
          existing ← accountsClient.getAccountMember(accountId, accountMemberId)
          accountRoles ← accountsClient.getRolesForAccount(accountId)
          foundRoles = findRequestedRoles(request.roles, accountRoles)
          update ← existing.fold(throw new RuntimeException("Account member not found")) { found ⇒
            if (found.user.emailAddress != request.emailAddress) throw new RuntimeException("Unable to update email address for account member")

            accountsClient.updateAccountMember(request.accountId, found.copy(roles=foundRoles)).map(Update(_))
          }
        } yield {
          buildCreateOrUpdateResponse(request.accountId, update, existing)
        }
      case _ ⇒
        logger.error(s"The physical resource id $physicalResourceId does not match the URL pattern for a Cloudflare account")
        Future.failed(new RuntimeException("Passed string does not match URL pattern for Cloudflare account member record"))
    }
  }

  private def handleDelete(physicalResourceId: String)
                  (implicit accountsClient: AccountsClient[Future]): Future[HandlerResponse] = {
    physicalResourceId match {
      case accountMemberUriRegex(accountId, accountMemberId) ⇒
        accountsClient.removeAccountMember(accountId, accountMemberId) map { deleted ⇒
          HandlerResponse(physicalResourceId, Map("accountMemberId" → deleted))
        }
      case _ ⇒
        logger.error(s"The physical resource id $physicalResourceId does not match the URL pattern for a Cloudflare account")
        Future.failed(new RuntimeException("Passed string does not match URL pattern for Cloudflare account member record"))
    }
  }
  .recover {
    case ex: AccountMemberDoesNotExistException ⇒
      logger.error("The record could not be deleted because it did not exist; nonetheless, responding with Success!", ex)
      HandlerResponse(physicalResourceId, Map.empty[String, AnyRef])
  }

  private def parseRequestFrom(props: Map[String, JValue]): AccountMembershipRequest = {
    val m = props("AccountMember").extract[Map[String, JValue]]
    AccountMembershipRequest(
      m("AccountID"),
      m("EmailAddress"),
      m("Roles").extract[List[String]]
    )
  }

  private def findRequestedRoles(requestedRoleNames: List[String], accountRoles: Set[AccountRole]): List[AccountRole] = {
    val foundRoles = accountRoles.filter(ar ⇒ requestedRoleNames.contains(ar.name)).toList
    if (foundRoles.size != requestedRoleNames.size) throw MissingRoles(requestedRoleNames)

    foundRoles
  }

  private def buildCreateOrUpdateResponse(accountId: String, createOrUpdate: CreateOrUpdate[AccountMember], existing: Option[AccountMember]): HandlerResponse = {
    val accountMember = createOrUpdate.value
    val data = Map(
      "accountMember" → accountMember,
      "created" → createOrUpdate.create,
      "updated" → createOrUpdate.update,
      "oldAccountMember" → existing
    )

    logger.info(s"Cloudflare AccountMembership response data: ${Serialization.write(data)}")

    HandlerResponse(accountMember.uri(accountId), Map("accountMemberId" → accountMember.id))
  }
}

case class AccountMembershipRequest(accountId: String, emailAddress: String, roles: List[String])
