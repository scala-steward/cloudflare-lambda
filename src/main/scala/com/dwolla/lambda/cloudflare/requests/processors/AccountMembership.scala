package com.dwolla.lambda.cloudflare.requests.processors

import _root_.fs2._
import _root_.io.circe._
import _root_.io.circe.syntax._
import cats.data._
import cats.effect._
import cats.implicits._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model._
import com.dwolla.cloudflare.domain.model.accounts._
import com.dwolla.lambda.cloudflare.Exceptions._
import com.dwolla.lambda.cloudflare._
import com.dwolla.lambda.cloudformation._
import com.dwolla.lambda.cloudflare.requests.processors.AccountMembership._
import shapeless.tag.@@
import org.slf4j.Logger

class AccountMembership(override val executor: StreamingCloudflareApiExecutor[IO]) extends ResourceRequestProcessor {
  protected lazy val logger: Logger = org.slf4j.LoggerFactory.getLogger("LambdaLogger")

  protected lazy val accountMembersClient = AccountMembersClient[IO](executor)
  protected lazy val accountsClient = AccountsClient[IO](executor)

  override def process(action: String, physicalResourceId: Option[String], properties: Map[String, Json]): Stream[IO, HandlerResponse] =
    for {
      request ← parseRecordFrom(properties)
      resp ← handleAction(action, request, physicalResourceId)
    } yield resp

  private def handleAction(action: String, request: AccountMembershipRequest, physicalResourceId: Option[String]): Stream[IO, HandlerResponse] =
    for {
      () ← Stream.eval(IO(logger.info(s"$request")))
      existingAccountMember ← findExistingMember(physicalResourceId, request.accountId).last
      res ← action match {
        case "UPDATE" if existingAccountMember.isEmpty ⇒ Stream.raiseError(AccountMemberNotFound(physicalResourceId)).covary[IO]
        case "CREATE" | "UPDATE" ⇒ handleCreateOrUpdate(request, existingAccountMember)
        case "DELETE" if physicalResourceId.isDefined ⇒ handleDelete(physicalResourceId.get)
        case _ ⇒ Stream.raiseError(UnsupportedAction(action)).covary[IO]
      }
    } yield res

  private def handleCreateOrUpdate(request: AccountMembershipRequest, existingAccountMember: Option[AccountMember]): Stream[IO, HandlerResponse] =
    for {
      c ← existingAccountMember.fold(createMember)(updateMember).run(request)
      response ← Stream.eval(createOrUpdateToHandlerResponse(request.accountId, c, existingAccountMember))
    } yield response

  private def handleDelete(physicalResourceId: String): Stream[IO, HandlerResponse] =
    accountMembersClient.parseUri(physicalResourceId) match {
      case Some((accountId, accountMemberId)) =>
        accountMembersClient.removeMember(accountId, accountMemberId)
          .map(deletedRecordId ⇒ HandlerResponse(physicalResourceId, Map("accountMemberId" → deletedRecordId.asJson)))
          .handleErrorWith {
            case ex: AccountMemberDoesNotExistException ⇒
              for {
                _ ← Stream.eval(IO(logger.error("The record could not be deleted because it did not exist; nonetheless, responding with Success!", ex)))
              } yield HandlerResponse(physicalResourceId)
          }
      case None => Stream.raiseError(InvalidCloudflareUri(physicalResourceId))
    }

  /*_*/
  private def createMember: Kleisli[Stream[IO, ?], AccountMembershipRequest, CreateOrUpdate[AccountMember]] =
    Kleisli { request ⇒
      for {
        foundRoles ← Stream.eval(findRequestedRoles(request.accountId, request.roles.toSet))
        created ← accountMembersClient.addMember(request.accountId, request.emailAddress, foundRoles.map(_.id))
      } yield Create(created)
    }
  /*_*/

  private def updateMember(existing: AccountMember): Kleisli[Stream[IO, ?], AccountMembershipRequest, CreateOrUpdate[AccountMember]] =
    for {
      update ← assertEmailAddressWillNotChange(existing.user.emailAddress).andThen { request ⇒
        for {
          foundRoles ← Stream.eval(findRequestedRoles(request.accountId, request.roles.toSet))
          updated ← accountMembersClient.updateMember(request.accountId, existing.copy(roles=foundRoles))
        } yield Update(updated)
      }
    } yield update

  private def findExistingMember(physicalResourceId: Option[String], requestedAccountId: String): Stream[IO, AccountMember] =
    physicalResourceId.map(accountMembersClient.parseUri).fold(Stream.empty.covaryAll[IO, AccountMember]) {
      case Some((accountId, accountMemberId)) if accountId == requestedAccountId ⇒
        accountMembersClient.getById(accountId, accountMemberId).head
      case Some((accountId, _)) if accountId != requestedAccountId ⇒
        Stream.raiseError(AccountIdMismatch(requestedAccountId, physicalResourceId.get))
      case None ⇒
        Stream.raiseError(InvalidCloudflareUri(physicalResourceId.get))
    }

  private def findRequestedRoles(accountId: AccountId, requestedRoleNames: Set[String]): IO[List[AccountRole]] =
    for {
      roles ← accountsClient.listRoles(accountId).compile.toList
      () ← IO(logger.debug(s"Requested roles for account: $requestedRoleNames"))
      () ← IO(logger.debug(s"Found roles for account: $roles"))
      foundRoles = roles.filter(ar ⇒ requestedRoleNames.contains(ar.name))
      res ← if (requestedRoleNames == foundRoles.map(_.name).toSet) IO.pure(roles) else IO.raiseError(MissingRoles(requestedRoleNames.toList))
    } yield res

  private def createOrUpdateToHandlerResponse(accountId: AccountId, createOrUpdate: CreateOrUpdate[AccountMember], existing: Option[AccountMember]): IO[HandlerResponse] = {
    import _root_.io.circe.generic.auto._
    val accountMember = createOrUpdate.value

    val data = Map(
      "accountMember" → accountMember.asJson,
      "created" → createOrUpdate.create.asJson,
      "updated" → createOrUpdate.update.asJson,
      "oldAccountMember" → existing.asJson
    )

    for {
      _ ← IO(logger.info(s"Cloudflare AccountMembership response data: ${data.asJson.noSpaces}"))
    } yield HandlerResponse(accountMember.uri(accountId).renderString, Map("accountMemberId" → Json.fromString(accountMember.id)))
  }

  /*_*/
  private def assertEmailAddressWillNotChange(existingEmailAddress: String): Kleisli[Stream[IO, ?], AccountMembershipRequest, AccountMembershipRequest] =
    Kleisli { request ⇒
      if (request.emailAddress == existingEmailAddress)
        Stream.emit(request)
      else
        Stream.raiseError(RefusingToChangeEmailAddress)
    }
  /*_*/

  private def parseRecordFrom(resourceProperties: Map[String, Json]): Stream[IO, AccountMembershipRequest] =
    Stream.eval(resourceProperties.get("AccountMember").fold(IO.raiseError[AccountMembershipRequest](MissingResourceProperties))(_.parseAs[AccountMembershipRequest]))

  private implicit def encodeTaggedString[A]: Encoder[String @@ A] = Encoder[String].narrow
}

object AccountMembership {
  private implicit def decodeTaggedString[A]: Decoder[String @@ A] = Decoder[String].map(shapeless.tag[A][String])

  implicit val accountMembershipRequestDecoder: Decoder[AccountMembershipRequest] = (c: HCursor) ⇒
    for {
      accountId ← c.downField("AccountID").as[AccountId]
      email ← c.downField("EmailAddress").as[String]
      roles ← c.downField("Roles").as[List[String]]
    } yield AccountMembershipRequest(accountId, email, roles)

}

case class AccountMembershipRequest(accountId: AccountId, emailAddress: String, roles: List[String])
