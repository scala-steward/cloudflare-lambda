package com.dwolla.lambda.cloudflare.requests.processors

import cats._
import cats.syntax.all._
import com.dwolla.circe._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model.ZoneId
import com.dwolla.cloudflare.domain.model.firewallrules.{FirewallRule, FirewallRuleId}
import com.dwolla.lambda.cloudflare.Exceptions._
import com.dwolla.lambda.cloudflare.requests.processors.ResourceRequestProcessor.physicalResourceIdFromUri
import feral.lambda.cloudformation.CloudFormationRequestType._
import feral.lambda.cloudformation._
import fs2.Stream
import io.circe._
import io.circe.syntax._

class FirewallRuleProcessor[F[_] : MonadThrow](zoneClient: ZoneClient[F], firewallRuleClient: FirewallRuleClient[F]) extends ResourceRequestProcessor[F] {

  def this(executor: StreamingCloudflareApiExecutor[F]) = this(ZoneClient(executor), FirewallRuleClient(executor))

  override def process(action: CloudFormationRequestType,
                       physicalResourceId: Option[PhysicalResourceId],
                       properties: JsonObject): Stream[F, HandlerResponse[Json]] =
    for {
      request <- parseRecordFrom[FirewallRule](properties, "FirewallRule")
      resp <- handleAction(action, request, physicalResourceId, properties)
    } yield resp

  private def handleAction(action: CloudFormationRequestType,
                           request: FirewallRule,
                           physicalResourceId: Option[PhysicalResourceId],
                           properties: JsonObject,
                          ): Stream[F, HandlerResponse[Json]] = (action, physicalResourceId) match {
    case (CreateRequest, None) => handleCreate(request, properties)
    case (UpdateRequest, ZoneAndFirewallRuleIds(zid, prid)) => handleUpdate(zid, prid, request)
    case (DeleteRequest, ZoneAndFirewallRuleIds(zid, prid)) => handleDelete(zid, prid)
    case (CreateRequest, Some(id)) => Stream.raiseError(UnexpectedPhysicalId(id))
    case (_, Some(id)) => Stream.raiseError(InvalidCloudflareUri(id.some))
    case (UpdateRequest, None) | (DeleteRequest, None) => Stream.raiseError(MissingPhysicalId(action))
    case (OtherRequestType(_), _) => Stream.raiseError(UnsupportedRequestType(action))
  }

  private def handleCreate(request: FirewallRule, properties: JsonObject): Stream[F, HandlerResponse[Json]] =
    for {
      zone <- parseRecordFrom[String](properties, "Zone")
      zoneId <- zoneClient.getZoneId(zone)
      resp <- firewallRuleClient.create(zoneId, request)
      id <- resp.id.fold(PhysicalResourceId.unsafeApply("Unknown FirewallRule ID").pure[Stream[F, *]])(id => physicalResourceIdFromUri[Stream[F, *]](firewallRuleClient.buildUri(zoneId, id)))
    } yield HandlerResponse(id, Json.obj(
      "created" -> resp.asJson
    ).some)

  private def handleUpdate(zoneId: ZoneId, firewallRuleId: FirewallRuleId, request: FirewallRule): Stream[F, HandlerResponse[Json]] =
    for {
      filterId <- if (request.filter.id.isEmpty) firewallRuleClient.getById(zoneId, firewallRuleId).map(_.filter.id) else Stream.emit(request.filter.id)
      resp <- firewallRuleClient.update(zoneId, request.copy(id = Option(firewallRuleId), filter = request.filter.copy(id = filterId)))
      id <- physicalResourceIdFromUri[Stream[F, *]](firewallRuleClient.buildUri(zoneId, resp.id.getOrElse(firewallRuleId)))
    } yield HandlerResponse(id, Json.obj(
      "updated" -> resp.asJson
    ).some)

  private def handleDelete(zoneId: ZoneId, firewallRuleId: FirewallRuleId): Stream[F, HandlerResponse[Json]] =
    for {
      resp <- firewallRuleClient.delete(zoneId, firewallRuleId)
      id <- physicalResourceIdFromUri[Stream[F, *]](firewallRuleClient.buildUri(zoneId, resp))
    } yield HandlerResponse(id, Json.obj(
      "deleted" -> resp.asJson
    ).some)

  private object ZoneAndFirewallRuleIds {
    def unapply(arg: Option[PhysicalResourceId]): Option[(ZoneId, FirewallRuleId)] =
      arg.map(_.value).flatMap(firewallRuleClient.parseUri)
  }
}
