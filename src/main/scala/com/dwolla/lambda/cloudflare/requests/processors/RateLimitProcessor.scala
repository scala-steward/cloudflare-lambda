package com.dwolla.lambda.cloudflare.requests.processors

import cats.effect._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model.ZoneId
import com.dwolla.cloudflare.domain.model.ratelimits.{RateLimit, RateLimitId}
import com.dwolla.lambda.cloudformation._
import com.dwolla.lambda.cloudformation.CloudFormationRequestType._
import fs2.Stream
import io.circe._
import io.circe.syntax._
import com.dwolla.lambda.cloudflare.Exceptions._
import io.circe.literal._
import com.dwolla.circe._

class RateLimitProcessor[F[_] : Sync](zoneClient: ZoneClient[F], rateLimitClient: RateLimitClient[F]) extends ResourceRequestProcessor[F] {

  def this(executor: StreamingCloudflareApiExecutor[F]) = this(ZoneClient(executor), RateLimitClient(executor))

  override def process(action: CloudFormationRequestType,
                       physicalResourceId: Option[PhysicalResourceId],
                       properties: JsonObject): Stream[F, HandlerResponse] =
    for {
      request <- parseRecordFrom[RateLimit](properties, "RateLimit")
      resp <- handleAction(action, request, physicalResourceId, properties)
    } yield resp

  private def handleAction(action: CloudFormationRequestType,
                           request: RateLimit,
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

  private def handleCreate(request: RateLimit, properties: JsonObject): Stream[F, HandlerResponse] =
    for {
      zone <- parseRecordFrom[String](properties, "Zone")
      zoneId <- zoneClient.getZoneId(zone)
      resp <- rateLimitClient.create(zoneId, request)
    } yield HandlerResponse(tagPhysicalResourceId(resp.id.fold("Unknown RateLimit ID")(id => rateLimitClient.buildUri(zoneId, id))), JsonObject(
      "created" -> resp.asJson
    ))

  private def handleUpdate(zoneId: ZoneId, rateLimitId: RateLimitId, request: RateLimit): Stream[F, HandlerResponse] =
    for {
      resp <- rateLimitClient.update(zoneId, request.copy(id = Option(rateLimitId)))
    } yield HandlerResponse(tagPhysicalResourceId(rateLimitClient.buildUri(zoneId, resp.id.getOrElse(rateLimitId))), JsonObject(
      "updated" -> resp.asJson
    ))

  private def handleDelete(zoneId: ZoneId, rateLimitId: RateLimitId): Stream[F, HandlerResponse] =
    for {
      resp <- rateLimitClient.delete(zoneId, rateLimitId)
    } yield HandlerResponse(tagPhysicalResourceId(rateLimitClient.buildUri(zoneId, resp)), JsonObject(
      "deleted" -> resp.asJson
    ))

  private object PhysicalResourceId {
    def unapply(arg: Option[PhysicalResourceId]): Option[(ZoneId, RateLimitId)] =
      arg.flatMap(rateLimitClient.parseUri)
  }
}