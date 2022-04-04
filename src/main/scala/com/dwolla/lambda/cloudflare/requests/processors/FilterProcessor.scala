package com.dwolla.lambda.cloudflare.requests.processors

import cats._
import cats.syntax.all._
import com.dwolla.circe._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model.ZoneId
import com.dwolla.cloudflare.domain.model.filters.{Filter, FilterId}
import com.dwolla.lambda.cloudflare.Exceptions._
import com.dwolla.lambda.cloudflare.requests.processors.ResourceRequestProcessor.physicalResourceIdFromUri
import feral.lambda.cloudformation.CloudFormationRequestType._
import feral.lambda.cloudformation._
import fs2.Stream
import io.circe._
import io.circe.syntax._

class FilterProcessor[F[_] : MonadThrow](zoneClient: ZoneClient[F], filterClient: FilterClient[F]) extends ResourceRequestProcessor[F] {

  def this(executor: StreamingCloudflareApiExecutor[F]) = this(ZoneClient(executor), FilterClient(executor))

  override def process(action: CloudFormationRequestType,
                       physicalResourceId: Option[PhysicalResourceId],
                       properties: JsonObject): Stream[F, HandlerResponse[Json]] =
    for {
      request <- parseRecordFrom[Filter](properties, "Filter")
      resp <- handleAction(action, request, physicalResourceId, properties)
    } yield resp

  private def handleAction(action: CloudFormationRequestType,
                           request: Filter,
                           physicalResourceId: Option[PhysicalResourceId],
                           properties: JsonObject,
                          ): Stream[F, HandlerResponse[Json]] = (action, physicalResourceId) match {
    case (CreateRequest, None) => handleCreate(request, properties)
    case (UpdateRequest, ZoneAndFilterId(zid, prid)) => handleUpdate(zid, prid, request)
    case (DeleteRequest, ZoneAndFilterId(zid, prid)) => handleDelete(zid, prid)
    case (CreateRequest, Some(id)) => Stream.raiseError(UnexpectedPhysicalId(id))
    case (_, Some(id)) => Stream.raiseError(InvalidCloudflareUri(id.some))
    case (UpdateRequest, None) | (DeleteRequest, None) => Stream.raiseError(MissingPhysicalId(action))
    case (OtherRequestType(_), _) => Stream.raiseError(UnsupportedRequestType(action))
  }

  private def handleCreate(request: Filter, properties: JsonObject): Stream[F, HandlerResponse[Json]] =
    for {
      zone <- parseRecordFrom[String](properties, "Zone")
      zoneId <- zoneClient.getZoneId(zone)
      resp <- filterClient.create(zoneId, request)
      id <- resp.id.fold(PhysicalResourceId.unsafeApply("Unknown Filter ID").pure[Stream[F, *]])(id => physicalResourceIdFromUri[Stream[F, *]](filterClient.buildUri(zoneId, id)))
    } yield HandlerResponse(id, Json.obj(
      "created" -> resp.asJson
    ).some)

  private def handleUpdate(zoneId: ZoneId, filterId: FilterId, request: Filter): Stream[F, HandlerResponse[Json]] =
    for {
      resp <- filterClient.update(zoneId, request.copy(id = Option(filterId)))
      id <- physicalResourceIdFromUri[Stream[F, *]](filterClient.buildUri(zoneId, resp.id.getOrElse(filterId)))
    } yield HandlerResponse(id, Json.obj(
      "updated" -> resp.asJson
    ).some)

  private def handleDelete(zoneId: ZoneId, filterId: FilterId): Stream[F, HandlerResponse[Json]] =
    for {
      resp <- filterClient.delete(zoneId, filterId)
      id <- physicalResourceIdFromUri[Stream[F, *]](filterClient.buildUri(zoneId, resp))
    } yield HandlerResponse(id, Json.obj(
      "deleted" -> resp.asJson
    ).some)

  private object ZoneAndFilterId {
    def unapply(arg: Option[PhysicalResourceId]): Option[(ZoneId, FilterId)] =
      arg.map(_.value).flatMap(filterClient.parseUri)
  }
}
