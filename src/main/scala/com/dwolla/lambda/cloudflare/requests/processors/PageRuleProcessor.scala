package com.dwolla.lambda.cloudflare
package requests.processors

import cats.effect._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model.ZoneId
import com.dwolla.cloudflare.domain.model.pagerules.{PageRule, PageRuleId}
import com.dwolla.lambda.cloudformation._
import com.dwolla.lambda.cloudformation.CloudFormationRequestType._
import fs2.Stream
import io.circe._
import io.circe.syntax._
import com.dwolla.circe._
import com.dwolla.lambda.cloudflare.Exceptions._

class PageRuleProcessor[F[_] : Sync](zoneClient: ZoneClient[F], pageRuleClient: PageRuleClient[F]) extends ResourceRequestProcessor[F] {

  def this(executor: StreamingCloudflareApiExecutor[F]) = this(ZoneClient(executor), PageRuleClient(executor))

  override def process(action: CloudFormationRequestType,
                       physicalResourceId: Option[PhysicalResourceId],
                       properties: JsonObject): Stream[F, HandlerResponse] =
    for {
      request <- parseRecordFrom[PageRule](properties, "PageRule")
      resp <- handleAction(action, request, physicalResourceId, properties)
    } yield resp

  private def handleAction(action: CloudFormationRequestType,
                           request: PageRule,
                           physicalResourceId: Option[PhysicalResourceId],
                           properties: JsonObject,
                          ): Stream[F, HandlerResponse] = (action, physicalResourceId) match {
    case (CreateRequest, None) => handleCreate(request, properties)
    case (UpdateRequest, PhysicalResourceId(zid, prid)) => handleUpdate(zid, prid, request)
    case (DeleteRequest, PhysicalResourceId(zid, prid)) => handleDelete(zid, prid)
    case (CreateRequest, Some(id)) => Stream.raiseError(UnexpectedPhysicalId(id))
    case (_, Some(id)) => Stream.raiseError(InvalidCloudflareUri(id))
    case (UpdateRequest, None) | (DeleteRequest, None) => Stream.raiseError(MissingPhysicalId(action))
    case (OtherRequestType(_), _) ⇒ Stream.raiseError(UnsupportedRequestType(action))
  }

  private def handleCreate(request: PageRule, properties: JsonObject): Stream[F, HandlerResponse] =
    for {
      zone <- parseRecordFrom[String](properties, "Zone")
      zoneId <- zoneClient.getZoneId(zone)
      resp <- pageRuleClient.create(zoneId, request)
    } yield HandlerResponse(tagPhysicalResourceId(resp.id.fold("Unknown PageRule ID")(id => pageRuleClient.buildUri(zoneId, id))), JsonObject(
      "created" -> resp.asJson
    ))

  private def handleUpdate(zoneId: ZoneId, pageRuleId: PageRuleId, request: PageRule): Stream[F, HandlerResponse] =
    for {
      resp <- pageRuleClient.update(zoneId, request.copy(id = Option(pageRuleId)))
    } yield HandlerResponse(tagPhysicalResourceId(pageRuleClient.buildUri(zoneId, resp.id.getOrElse(pageRuleId))), JsonObject(
      "updated" -> resp.asJson
    ))

  private def handleDelete(zoneId: ZoneId, pageRuleId: PageRuleId): Stream[F, HandlerResponse] =
    for {
      resp <- pageRuleClient.delete(zoneId, pageRuleId)
    } yield HandlerResponse(tagPhysicalResourceId(pageRuleClient.buildUri(zoneId, resp)), JsonObject(
      "deleted" -> resp.asJson
    ))

  private object PhysicalResourceId {
    def unapply(arg: Option[PhysicalResourceId]): Option[(ZoneId, PageRuleId)] =
      arg.flatMap(pageRuleClient.parseUri)
  }
}
