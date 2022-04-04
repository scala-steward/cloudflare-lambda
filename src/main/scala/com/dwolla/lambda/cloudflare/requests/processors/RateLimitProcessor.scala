package com.dwolla.lambda.cloudflare.requests.processors

import cats._
import cats.syntax.all._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model.ZoneId
import com.dwolla.cloudflare.domain.model.ratelimits.{RateLimit, RateLimitId}
import feral.lambda.cloudformation._
import fs2.Stream
import io.circe._
import io.circe.syntax._
import com.dwolla.lambda.cloudflare.Exceptions._
import io.circe.literal._
import com.dwolla.circe._
import com.dwolla.lambda.cloudflare.requests.processors.ResourceRequestProcessor.physicalResourceIdFromUri
import feral.lambda.cloudformation.CloudFormationRequestType._

class RateLimitProcessor[F[_] : MonadThrow](zoneClient: ZoneClient[F], rateLimitClient: RateLimitClient[F]) extends ResourceRequestProcessor[F] {

  def this(executor: StreamingCloudflareApiExecutor[F]) = this(ZoneClient(executor), RateLimitClient(executor))

  override def process(action: CloudFormationRequestType,
                       physicalResourceId: Option[PhysicalResourceId],
                       properties: JsonObject): Stream[F, HandlerResponse[Json]] =
    for {
      request <- parseRecordFrom[RateLimit](properties, "RateLimit")
      resp <- handleAction(action, request, physicalResourceId, properties)
    } yield resp

  private def handleAction(action: CloudFormationRequestType,
                           request: RateLimit,
                           physicalResourceId: Option[PhysicalResourceId],
                           properties: JsonObject,
                          ): Stream[F, HandlerResponse[Json]] = (action, physicalResourceId) match {
    case (CreateRequest, None) => handleCreate(request, properties)
    case (UpdateRequest, ZoneAndRateLimitId(zid, prid)) => handleUpdate(zid, prid, request)
    case (DeleteRequest, ZoneAndRateLimitId(zid, prid)) => handleDelete(zid, prid)
    case (CreateRequest, Some(id)) => Stream.raiseError(UnexpectedPhysicalId(id))
    case (_, Some(id)) => Stream.raiseError(InvalidCloudflareUri(id.some))
    case (UpdateRequest, None) | (DeleteRequest, None) => Stream.raiseError(MissingPhysicalId(action))
    case (OtherRequestType(_), _) => Stream.raiseError(UnsupportedRequestType(action))
  }

  private def handleCreate(request: RateLimit, properties: JsonObject): Stream[F, HandlerResponse[Json]] =
    for {
      zone <- parseRecordFrom[String](properties, "Zone")
      zoneId <- zoneClient.getZoneId(zone)
      resp <- rateLimitClient.create(zoneId, request)
      id <- resp.id.fold(PhysicalResourceId.unsafeApply("Unknown RateLimit ID").pure[Stream[F, *]])(id => physicalResourceIdFromUri[Stream[F, *]](rateLimitClient.buildUri(zoneId, id)))
    } yield HandlerResponse(id, Json.obj(
      "created" -> resp.asJson
    ).some)

  private def handleUpdate(zoneId: ZoneId, rateLimitId: RateLimitId, request: RateLimit): Stream[F, HandlerResponse[Json]] =
    for {
      resp <- rateLimitClient.update(zoneId, request.copy(id = Option(rateLimitId)))
      id <- physicalResourceIdFromUri[Stream[F, *]](rateLimitClient.buildUri(zoneId, resp.id.getOrElse(rateLimitId)))
    } yield HandlerResponse(id, Json.obj(
      "updated" -> resp.asJson
    ).some)

  private def handleDelete(zoneId: ZoneId, rateLimitId: RateLimitId): Stream[F, HandlerResponse[Json]] =
    for {
      resp <- rateLimitClient.delete(zoneId, rateLimitId)
      id <- physicalResourceIdFromUri[Stream[F, *]](rateLimitClient.buildUri(zoneId, resp))
    } yield HandlerResponse(id, Json.obj(
      "deleted" -> resp.asJson
    ).some)

  private object ZoneAndRateLimitId {
    def unapply(arg: Option[PhysicalResourceId]): Option[(ZoneId, RateLimitId)] =
      arg.map(_.value).flatMap(rateLimitClient.parseUri)
  }
}
