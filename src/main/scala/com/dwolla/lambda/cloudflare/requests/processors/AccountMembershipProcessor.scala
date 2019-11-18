package com.dwolla.lambda.cloudflare.requests.processors

import _root_.fs2._
import _root_.io.circe._
import _root_.io.circe.syntax._
import cats.data._
import cats.effect._
import cats.implicits._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model.accounts._
import com.dwolla.lambda.cloudflare.Exceptions._
import com.dwolla.lambda.cloudformation._
import com.dwolla.lambda.cloudformation.CloudFormationRequestType._
import com.dwolla.lambda.cloudflare.requests.processors.AccountMembership._
import org.slf4j.Logger
import com.dwolla.circe._
import com.dwolla.cloudflare.domain.model.AccountId

class AccountMembership[F[_] : Sync](accountsClient: AccountsClient[F], accountMembersClient: AccountMembersClient[F]) extends ResourceRequestProcessor[F] {

  def this(executor: StreamingCloudflareApiExecutor[F]) = this(AccountsClient(executor), AccountMembersClient(executor))

  protected lazy val logger: Logger = org.slf4j.LoggerFactory.getLogger("LambdaLogger")

  override def process(action: CloudFormationRequestType, physicalResourceId: Option[PhysicalResourceId], properties: JsonObject): Stream[F, HandlerResponse] =
    for {
      request <- parseRecordFrom[AccountMembershipRequest](properties, "AccountMember")
      resp <- handleAction(action, request, physicalResourceId)
    } yield resp

  private def handleAction(action: CloudFormationRequestType, request: AccountMembershipRequest, physicalResourceId: Option[PhysicalResourceId]): Stream[F, HandlerResponse] =
    for {
      () <- Stream.eval(Sync[F].delay(logger.info(s"$action $request")))
      existingAccountMember <- findExistingMember(physicalResourceId, request.accountId).last
      res <- action match {
        case UpdateRequest if existingAccountMember.isEmpty => Stream.raiseError(AccountMemberNotFound(physicalResourceId)).covary[F]
        case CreateRequest | UpdateRequest => handleCreateOrUpdate(request, existingAccountMember)
        case DeleteRequest if physicalResourceId.isDefined => handleDelete(physicalResourceId.get)
        case OtherRequestType(_) => Stream.raiseError(UnsupportedRequestType(action)).covary[F]
      }
    } yield res

  private def handleCreateOrUpdate(request: AccountMembershipRequest, existingAccountMember: Option[AccountMember]): Stream[F, HandlerResponse] =
    for {
      c <- existingAccountMember.fold(createMember)(updateMember).run(request)
      response <- Stream.eval(createOrUpdateToHandlerResponse(request.accountId, c, existingAccountMember))
    } yield response

  private def handleDelete(physicalResourceId: PhysicalResourceId): Stream[F, HandlerResponse] =
    accountMembersClient.parseUri(physicalResourceId) match {
      case Some((accountId, accountMemberId)) =>
        accountMembersClient.removeMember(accountId, accountMemberId)
          .map(deletedRecordId => HandlerResponse(physicalResourceId, JsonObject("accountMemberId" -> deletedRecordId.asJson)))
          .handleErrorWith {
            case ex: AccountMemberDoesNotExistException =>
              for {
                _ <- Stream.eval(Sync[F].delay(logger.error("The record could not be deleted because it did not exist; nonetheless, responding with Success!", ex)))
              } yield HandlerResponse(physicalResourceId)
          }
      case None => Stream.raiseError(InvalidCloudflareUri(physicalResourceId))
    }

  /*_*/
  private def createMember: Kleisli[Stream[F, ?], AccountMembershipRequest, CreateOrUpdate[AccountMember]] =
    Kleisli { request =>
      for {
        foundRoles <- Stream.eval(findRequestedRoles(request.accountId, request.roles.toSet))
        created <- accountMembersClient.addMember(request.accountId, request.emailAddress, foundRoles.map(_.id))
      } yield Create(created)
    }
  /*_*/

  private def updateMember(existing: AccountMember): Kleisli[Stream[F, ?], AccountMembershipRequest, CreateOrUpdate[AccountMember]] =
    for {
      update <- assertEmailAddressWillNotChange(existing.user.emailAddress).andThen { request =>
        for {
          foundRoles <- Stream.eval(findRequestedRoles(request.accountId, request.roles.toSet))
          updated <- accountMembersClient.updateMember(request.accountId, existing.copy(roles=foundRoles))
        } yield Update(updated)
      }
    } yield update

  private def findExistingMember(physicalResourceId: Option[String], requestedAccountId: String): Stream[F, AccountMember] =
    physicalResourceId.map(accountMembersClient.parseUri).fold(Stream.empty.covaryAll[F, AccountMember]) {
      case Some((accountId, accountMemberId)) if accountId == requestedAccountId =>
        accountMembersClient.getById(accountId, accountMemberId).head
      case Some((accountId, _)) if accountId != requestedAccountId =>
        Stream.raiseError(AccountIdMismatch(requestedAccountId, physicalResourceId.get))
      case None =>
        Stream.raiseError(InvalidCloudflareUri(physicalResourceId.get))
    }

  private def findRequestedRoles(accountId: AccountId, requestedRoleNames: Set[String]): F[List[AccountRole]] =
    Sync[F].delay(logger.debug(s"Requested roles for account: $requestedRoleNames")) *>
    accountsClient.listRoles(accountId).compile.toList
      .flatTap(roles => Sync[F].delay(logger.debug(s"Found roles for account: $roles")))
      .map(_.filter(accountRole => requestedRoleNames.contains(accountRole.name)))
      .ensure(MissingRoles(requestedRoleNames.toList))(_.map(_.name).toSet == requestedRoleNames)

  private def createOrUpdateToHandlerResponse(accountId: AccountId, createOrUpdate: CreateOrUpdate[AccountMember], existing: Option[AccountMember]): F[HandlerResponse] = {
    import _root_.io.circe.generic.auto._
    val accountMember = createOrUpdate.value

    val data = Map(
      "accountMember" -> accountMember.asJson,
      "created" -> createOrUpdate.create.asJson,
      "updated" -> createOrUpdate.update.asJson,
      "oldAccountMember" -> existing.asJson
    )

    for {
      _ <- Sync[F].delay(logger.info(s"Cloudflare AccountMembership response data: ${data.asJson.noSpaces}"))
    } yield HandlerResponse(tagPhysicalResourceId(accountMember.uri(accountId).renderString), JsonObject("accountMemberId" -> Json.fromString(accountMember.id)))
  }

  /*_*/
  private def assertEmailAddressWillNotChange(existingEmailAddress: String): Kleisli[Stream[F, ?], AccountMembershipRequest, AccountMembershipRequest] =
    Kleisli { request =>
      if (request.emailAddress == existingEmailAddress)
        Stream.emit(request)
      else
        Stream.raiseError(RefusingToChangeEmailAddress)
    }
  /*_*/
}

object AccountMembership {
  implicit val accountMembershipRequestDecoder: Decoder[AccountMembershipRequest] = (c: HCursor) =>
    for {
      accountId <- c.downField("AccountID").as[AccountId]
      email <- c.downField("EmailAddress").as[String]
      roles <- c.downField("Roles").as[List[String]]
    } yield AccountMembershipRequest(accountId, email, roles)

}

case class AccountMembershipRequest(accountId: AccountId, emailAddress: String, roles: List[String])
