package com.dwolla.lambda.cloudflare
package requests.processors

import cats._
import cats.syntax.all._
import com.dwolla.circe._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model.ZoneId
import com.dwolla.cloudflare.domain.model.pagerules.{PageRule, PageRuleId}
import com.dwolla.lambda.cloudflare.Exceptions._
import com.dwolla.lambda.cloudflare.requests.processors.ResourceRequestProcessor.physicalResourceIdFromUri
import feral.lambda.cloudformation.CloudFormationRequestType._
import feral.lambda.cloudformation._
import fs2.Stream
import io.circe._
import io.circe.syntax._

class PageRuleProcessor[F[_] : MonadThrow](zoneClient: ZoneClient[F], pageRuleClient: PageRuleClient[F]) extends ResourceRequestProcessor[F] {

  def this(executor: StreamingCloudflareApiExecutor[F]) = this(ZoneClient(executor), PageRuleClient(executor))

  override def process(action: CloudFormationRequestType,
                       physicalResourceId: Option[PhysicalResourceId],
                       properties: JsonObject): Stream[F, HandlerResponse[Json]] =
    for {
      request <- parseRecordFrom[PageRule](properties, "PageRule")
      resp <- handleAction(action, request, physicalResourceId, properties)
    } yield resp

  private def handleAction(action: CloudFormationRequestType,
                           request: PageRule,
                           physicalResourceId: Option[PhysicalResourceId],
                           properties: JsonObject,
                          ): Stream[F, HandlerResponse[Json]] = (action, physicalResourceId) match {
    case (CreateRequest, None) => handleCreate(request, properties)
    case (UpdateRequest, ZoneAndPageRuleId(zid, prid)) => handleUpdate(zid, prid, request)
    case (DeleteRequest, ZoneAndPageRuleId(zid, prid)) => handleDelete(zid, prid)
    case (CreateRequest, Some(id)) => Stream.raiseError(UnexpectedPhysicalId(id))
    case (_, Some(id)) => Stream.raiseError(InvalidCloudflareUri(id.some))
    case (UpdateRequest, None) | (DeleteRequest, None) => Stream.raiseError(MissingPhysicalId(action))
    case (OtherRequestType(_), _) => Stream.raiseError(UnsupportedRequestType(action))
  }

  private def handleCreate(request: PageRule, properties: JsonObject): Stream[F, HandlerResponse[Json]] =
    for {
      zone <- parseRecordFrom[String](properties, "Zone")
      zoneId <- zoneClient.getZoneId(zone)
      resp <- pageRuleClient.create(zoneId, request)
      id <- resp.id.fold(PhysicalResourceId.unsafeApply("Unknown PageRule ID").pure[Stream[F, *]])(id => physicalResourceIdFromUri[Stream[F, *]](pageRuleClient.buildUri(zoneId, id)))
    } yield HandlerResponse(id, Json.obj(
      "created" -> resp.asJson
    ).some)

  private def handleUpdate(zoneId: ZoneId, pageRuleId: PageRuleId, request: PageRule): Stream[F, HandlerResponse[Json]] =
    for {
      resp <- pageRuleClient.update(zoneId, request.copy(id = Option(pageRuleId)))
      id <- physicalResourceIdFromUri[Stream[F, *]](pageRuleClient.buildUri(zoneId, resp.id.getOrElse(pageRuleId)))
    } yield HandlerResponse(id, Json.obj(
      "updated" -> resp.asJson
    ).some)

  private def handleDelete(zoneId: ZoneId, pageRuleId: PageRuleId): Stream[F, HandlerResponse[Json]] =
    for {
      resp <- pageRuleClient.delete(zoneId, pageRuleId)
      id <- physicalResourceIdFromUri[Stream[F, *]](pageRuleClient.buildUri(zoneId, resp))
    } yield HandlerResponse(id, Json.obj(
      "deleted" -> resp.asJson
    ).some)

  private object ZoneAndPageRuleId {
    def unapply(arg: Option[PhysicalResourceId]): Option[(ZoneId, PageRuleId)] =
      arg.map(_.value).flatMap(pageRuleClient.parseUri)
  }
}
