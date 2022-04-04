package com.dwolla.lambda.cloudflare.requests.processors

import _root_.fs2._
import _root_.io.circe._
import _root_.io.circe.syntax._
import cats._
import cats.data._
import cats.syntax.all._
import com.dwolla.circe._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model.AccountId
import com.dwolla.cloudflare.domain.model.accounts._
import com.dwolla.lambda.cloudflare.Exceptions._
import com.dwolla.lambda.cloudflare._
import com.dwolla.lambda.cloudflare.requests.processors.AccountMembership._
import com.dwolla.lambda.cloudflare.requests.processors.ResourceRequestProcessor.physicalResourceIdFromUri
import feral.lambda.cloudformation.CloudFormationRequestType._
import feral.lambda.cloudformation._
import org.typelevel.log4cats.Logger

class AccountMembership[F[_] : MonadThrow : Logger](accountsClient: AccountsClient[F], accountMembersClient: AccountMembersClient[F])
                                                   (implicit SC: Compiler[F, F]) extends ResourceRequestProcessor[F] {

  def this(executor: StreamingCloudflareApiExecutor[F])
          (implicit SC: Compiler[F, F]) = this(AccountsClient(executor), AccountMembersClient(executor))

  override def process(action: CloudFormationRequestType, physicalResourceId: Option[PhysicalResourceId], properties: JsonObject): Stream[F, HandlerResponse[Json]] =
    for {
      request <- parseRecordFrom[AccountMembershipRequest](properties, "AccountMember")
      resp <- handleAction(action, request, physicalResourceId)
    } yield resp

  private def handleAction(action: CloudFormationRequestType, request: AccountMembershipRequest, physicalResourceId: Option[PhysicalResourceId]): Stream[F, HandlerResponse[Json]] =
    for {
      _ <- Logger[Stream[F, *]].info(s"$action $request")
      existingAccountMember <- findExistingMember(physicalResourceId, request.accountId).last
      res <- action match {
        case UpdateRequest if existingAccountMember.isEmpty => Stream.raiseError[F](AccountMemberNotFound(physicalResourceId))
        case CreateRequest | UpdateRequest => handleCreateOrUpdate(request, existingAccountMember)
        case DeleteRequest if physicalResourceId.isDefined => handleDelete(physicalResourceId.get)
        case DeleteRequest | OtherRequestType(_) => Stream.raiseError[F](UnsupportedRequestType(action))
      }
    } yield res

  private def handleCreateOrUpdate(request: AccountMembershipRequest, existingAccountMember: Option[AccountMember]): Stream[F, HandlerResponse[Json]] =
    for {
      c <- existingAccountMember.fold(createMember)(updateMember).run(request)
      response <- Stream.eval(createOrUpdateToHandlerResponse(request.accountId, c, existingAccountMember))
    } yield response

  private def handleDelete(physicalResourceId: PhysicalResourceId): Stream[F, HandlerResponse[Json]] =
    accountMembersClient.parseUri(physicalResourceId.value) match {
      case Some((accountId, accountMemberId)) =>
        accountMembersClient.removeMember(accountId, accountMemberId)
          .map(deletedRecordId => HandlerResponse(physicalResourceId, Json.obj("accountMemberId" -> deletedRecordId.asJson).some))
          .recoverWith {
            case ex: AccountMemberDoesNotExistException =>
              Logger[Stream[F, *]].error(ex)("The record could not be deleted because it did not exist; nonetheless, responding with Success!")
                .as(HandlerResponse(physicalResourceId, None))
          }
      case None => Stream.raiseError[F](InvalidCloudflareUri(physicalResourceId.some))
    }

  /*_*/
  private def createMember: Kleisli[Stream[F, *], AccountMembershipRequest, CreateOrUpdate[AccountMember]] =
    Kleisli { request =>
      for {
        foundRoles <- Stream.eval(findRequestedRoles(request.accountId, request.roles.toSet))
        created <- accountMembersClient.addMember(request.accountId, request.emailAddress, foundRoles.map(_.id))
      } yield Create(created)
    }
  /*_*/

  private def updateMember(existing: AccountMember): Kleisli[Stream[F, *], AccountMembershipRequest, CreateOrUpdate[AccountMember]] =
    for {
      update <- assertEmailAddressWillNotChange(existing.user.emailAddress).andThen { request =>
        for {
          foundRoles <- Stream.eval(findRequestedRoles(request.accountId, request.roles.toSet))
          updated <- accountMembersClient.updateMember(request.accountId, existing.copy(roles=foundRoles))
        } yield Update(updated)
      }
    } yield update

  private def findExistingMember(physicalResourceId: Option[PhysicalResourceId], requestedAccountId: String): Stream[F, AccountMember] =
    physicalResourceId
      .map(_.value)
      .map(accountMembersClient.parseUri)
      .fold(Stream.empty.covaryAll[F, AccountMember]) {
        case Some((accountId, accountMemberId)) if accountId == requestedAccountId =>
          accountMembersClient.getById(accountId, accountMemberId).head
        case Some((accountId, _)) if accountId != requestedAccountId =>
          Stream.raiseError[F](AccountIdMismatch(requestedAccountId, physicalResourceId))
        case _ =>
          Stream.raiseError[F](InvalidCloudflareUri(physicalResourceId))
      }

  private def findRequestedRoles(accountId: AccountId, requestedRoleNames: Set[String]): F[List[AccountRole]] =
    Logger[F].debug(s"Requested roles for account: $requestedRoleNames") >>
      accountsClient.listRoles(accountId).compile.toList
        .flatTap(roles => Logger[F].debug(s"Found roles for account: $roles"))
        .map(_.filter(accountRole => requestedRoleNames.contains(accountRole.name)))
        .ensure(MissingRoles(requestedRoleNames.toList))(_.map(_.name).toSet == requestedRoleNames)

  private def createOrUpdateToHandlerResponse(accountId: AccountId, createOrUpdate: CreateOrUpdate[AccountMember], existing: Option[AccountMember]): F[HandlerResponse[Json]] = {
    import _root_.io.circe.generic.auto._
    val accountMember = createOrUpdate.value

    val data = Map(
      "accountMember" -> accountMember.asJson,
      "created" -> createOrUpdate.create.asJson,
      "updated" -> createOrUpdate.update.asJson,
      "oldAccountMember" -> existing.asJson
    )

    for {
      _ <- Logger[F].info(s"Cloudflare AccountMembership response data: ${data.asJson.noSpaces}")
      id <- physicalResourceIdFromUri[F](accountMember.uri(accountId))
    } yield HandlerResponse(id, Json.obj("accountMemberId" -> Json.fromString(accountMember.id)).some)
  }

  private def assertEmailAddressWillNotChange(existingEmailAddress: String): Kleisli[Stream[F, *], AccountMembershipRequest, AccountMembershipRequest] =
    Kleisli { request =>
      if (request.emailAddress == existingEmailAddress)
        Stream.emit(request)
      else
        Stream.raiseError[F](RefusingToChangeEmailAddress)
    }
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
