package com.dwolla.lambda.cloudflare.requests.processors

import cats.effect._
import com.dwolla.circe._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model.ZoneId
import com.dwolla.cloudflare.domain.model.firewallrules.{FirewallRule, FirewallRuleId}
import com.dwolla.lambda.cloudflare.Exceptions._
import com.dwolla.lambda.cloudformation.CloudFormationRequestType._
import com.dwolla.lambda.cloudformation._
import fs2.Stream
import io.circe._
import io.circe.syntax._

class FirewallRuleProcessor[F[_] : Sync](zoneClient: ZoneClient[F], firewallRuleClient: FirewallRuleClient[F]) extends ResourceRequestProcessor[F] {

  def this(executor: StreamingCloudflareApiExecutor[F]) = this(ZoneClient(executor), FirewallRuleClient(executor))

  override def process(action: CloudFormationRequestType,
                       physicalResourceId: Option[PhysicalResourceId],
                       properties: JsonObject): Stream[F, HandlerResponse] =
    for {
      request <- parseRecordFrom[FirewallRule](properties, "FirewallRule")
      resp <- handleAction(action, request, physicalResourceId, properties)
    } yield resp

  private def handleAction(action: CloudFormationRequestType,
                           request: FirewallRule,
                           physicalResourceId: Option[PhysicalResourceId],
                           properties: JsonObject,
                          ): Stream[F, HandlerResponse] = (action, physicalResourceId) match {
    case (CreateRequest, None) => handleCreate(request, properties)
    case (UpdateRequest, PhysicalResourceId(zid, prid)) => handleUpdate(zid, prid, request)
    case (DeleteRequest, PhysicalResourceId(zid, prid)) => handleDelete(zid, prid)
    case (CreateRequest, Some(id)) => Stream.raiseError(UnexpectedPhysicalId(id))
    case (_, Some(id)) => Stream.raiseError(InvalidCloudflareUri(id))
    case (UpdateRequest, None) | (DeleteRequest, None) => Stream.raiseError(MissingPhysicalId(action))
    case (OtherRequestType(_), _) => Stream.raiseError(UnsupportedRequestType(action))
  }

  private def handleCreate(request: FirewallRule, properties: JsonObject): Stream[F, HandlerResponse] =
    for {
      zone <- parseRecordFrom[String](properties, "Zone")
      zoneId <- zoneClient.getZoneId(zone)
      resp <- firewallRuleClient.create(zoneId, request)
    } yield HandlerResponse(tagPhysicalResourceId(resp.id.fold("Unknown FirewallRule ID")(id => firewallRuleClient.buildUri(zoneId, id))), JsonObject(
      "created" -> resp.asJson
    ))

  private def handleUpdate(zoneId: ZoneId, firewallRuleId: FirewallRuleId, request: FirewallRule): Stream[F, HandlerResponse] =
    for {
      filterId <- if (request.filter.id.isEmpty) firewallRuleClient.getById(zoneId, firewallRuleId).map(_.filter.id) else Stream.emit(request.filter.id)
      resp <- firewallRuleClient.update(zoneId, request.copy(id = Option(firewallRuleId), filter = request.filter.copy(id = filterId)))
    } yield HandlerResponse(tagPhysicalResourceId(firewallRuleClient.buildUri(zoneId, resp.id.getOrElse(firewallRuleId))), JsonObject(
      "updated" -> resp.asJson
    ))

  private def handleDelete(zoneId: ZoneId, firewallRuleId: FirewallRuleId): Stream[F, HandlerResponse] =
    for {
      resp <- firewallRuleClient.delete(zoneId, firewallRuleId)
    } yield HandlerResponse(tagPhysicalResourceId(firewallRuleClient.buildUri(zoneId, resp)), JsonObject(
      "deleted" -> resp.asJson
    ))

  private object PhysicalResourceId {
    def unapply(arg: Option[PhysicalResourceId]): Option[(ZoneId, FirewallRuleId)] =
      arg.flatMap(firewallRuleClient.parseUri)
  }
}
