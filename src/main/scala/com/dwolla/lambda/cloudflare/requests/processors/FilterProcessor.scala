package com.dwolla.lambda.cloudflare.requests.processors

import cats.effect._
import com.dwolla.circe._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model.ZoneId
import com.dwolla.cloudflare.domain.model.filters.{Filter, FilterId}
import com.dwolla.lambda.cloudflare.Exceptions._
import com.dwolla.lambda.cloudformation.CloudFormationRequestType._
import com.dwolla.lambda.cloudformation._
import fs2.Stream
import io.circe._
import io.circe.syntax._

class FilterProcessor[F[_] : Sync](zoneClient: ZoneClient[F], filterClient: FilterClient[F]) extends ResourceRequestProcessor[F] {

  def this(executor: StreamingCloudflareApiExecutor[F]) = this(ZoneClient(executor), FilterClient(executor))

  override def process(action: CloudFormationRequestType,
                       physicalResourceId: Option[PhysicalResourceId],
                       properties: JsonObject): Stream[F, HandlerResponse] =
    for {
      request <- parseRecordFrom[Filter](properties, "Filter")
      resp <- handleAction(action, request, physicalResourceId, properties)
    } yield resp

  private def handleAction(action: CloudFormationRequestType,
                           request: Filter,
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

  private def handleCreate(request: Filter, properties: JsonObject): Stream[F, HandlerResponse] =
    for {
      zone <- parseRecordFrom[String](properties, "Zone")
      zoneId <- zoneClient.getZoneId(zone)
      resp <- filterClient.create(zoneId, request)
    } yield HandlerResponse(tagPhysicalResourceId(resp.id.fold("Unknown Filter ID")(id => filterClient.buildUri(zoneId, id))), JsonObject(
      "created" -> resp.asJson
    ))

  private def handleUpdate(zoneId: ZoneId, filterId: FilterId, request: Filter): Stream[F, HandlerResponse] =
    for {
      resp <- filterClient.update(zoneId, request.copy(id = Option(filterId)))
    } yield HandlerResponse(tagPhysicalResourceId(filterClient.buildUri(zoneId, resp.id.getOrElse(filterId))), JsonObject(
      "updated" -> resp.asJson
    ))

  private def handleDelete(zoneId: ZoneId, filterId: FilterId): Stream[F, HandlerResponse] =
    for {
      resp <- filterClient.delete(zoneId, filterId)
    } yield HandlerResponse(tagPhysicalResourceId(filterClient.buildUri(zoneId, resp)), JsonObject(
      "deleted" -> resp.asJson
    ))

  private object PhysicalResourceId {
    def unapply(arg: Option[PhysicalResourceId]): Option[(ZoneId, FilterId)] =
      arg.flatMap(filterClient.parseUri)
  }
}
